package com.xreal.nativear.batch

import android.content.Context
import android.util.Log
import androidx.work.*
import com.xreal.nativear.ai.AICallGateway
import com.xreal.nativear.ai.AIMessage
import com.xreal.nativear.ai.IAIProvider
import com.xreal.nativear.companion.AgentOwnPlan
import com.xreal.nativear.companion.SituationLifecycleManager
import com.xreal.nativear.context.LifeSituation
import com.xreal.nativear.core.ErrorReporter
import com.xreal.nativear.memory.MemorySaveHelper
import org.koin.core.qualifier.named
import org.koin.java.KoinJavaComponent
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * AgentWarmupWorker — 예측된 상황 X분 전에 에이전트 콘텐츠를 사전 생성하는 Worker (Phase F-3).
 *
 * ## 동작 흐름
 * ```
 * AgentWarmupScheduler → 예측 확인 → scheduleWarmup() 호출
 *    → WorkManager OneTimeWork (delayMs 후 실행)
 *    → AgentWarmupWorker.doWork() 실행:
 *         1. 에이전트 계획(AgentOwnPlan) 로드
 *         2. 워밍업 프롬프트 생성
 *         3. Gemini 호출 → 코칭/브리핑/정보 사전 생성
 *         4. MemorySaveHelper.saveMemory() → DB 저장 (persona_id=agentId)
 *         5. SituationLifecycleManager.setWarmupCacheKey() → 캐시 키 등록
 * ```
 *
 * ## 캐시 키 형식
 * `"warmup_{agentId}_{situation}_{YYYY-MM-DD}"` — 실시간 조회 시 이 키로 DB 검색
 *
 * ## 입력 데이터
 * - `situation`: LifeSituation.name
 * - `agent_id`: AgentOwnPlan.agentId
 * - `predicted_time_ms`: 예상 상황 시작 시각
 * - `user_goal`: 에이전트 장기 목표 (warmup prompt에 삽입)
 */
class AgentWarmupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AgentWarmupWorker"
        private const val DATE_FORMAT = "yyyy-MM-dd"

        const val KEY_SITUATION      = "situation"
        const val KEY_AGENT_ID       = "agent_id"
        const val KEY_PREDICTED_TIME = "predicted_time_ms"
        const val KEY_USER_GOAL      = "user_goal"

        /**
         * OneTimeWork 예약.
         *
         * @param context Application context
         * @param situation 워밍업 대상 상황
         * @param agentId  담당 에이전트 ID
         * @param predictedTimeMs 예상 상황 시작 시각 (Unix ms)
         * @param delayMs  지금으로부터 몇 ms 후에 실행
         * @param userGoal 에이전트 장기 목표 (프롬프트 삽입용)
         */
        fun schedule(
            context: Context,
            situation: LifeSituation,
            agentId: String,
            predictedTimeMs: Long,
            delayMs: Long,
            userGoal: String = ""
        ) {
            if (delayMs <= 0) {
                Log.w(TAG, "워밍업 예약 건너뜀: 이미 지난 시간 ($agentId / ${situation.displayName})")
                return
            }

            val workName = "warmup_${agentId}_${situation.name}_${
                SimpleDateFormat(DATE_FORMAT, Locale.getDefault()).format(Date(predictedTimeMs))
            }"

            val request = OneTimeWorkRequestBuilder<AgentWarmupWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(
                    workDataOf(
                        KEY_SITUATION      to situation.name,
                        KEY_AGENT_ID       to agentId,
                        KEY_PREDICTED_TIME to predictedTimeMs,
                        KEY_USER_GOAL      to userGoal
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                .addTag("agent_warmup")
                .addTag(agentId)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                workName,
                ExistingWorkPolicy.KEEP,
                request
            )

            Log.i(TAG, "워밍업 예약 완료: $agentId → ${situation.displayName} " +
                "in ${delayMs / 60000}분 (${workName})")
        }
    }

    // ─── Worker 실행 ──────────────────────────────────────────────────────────

    override suspend fun doWork(): Result {
        val situationName    = inputData.getString(KEY_SITUATION) ?: return Result.failure()
        val agentId          = inputData.getString(KEY_AGENT_ID) ?: return Result.failure()
        val predictedTimeMs  = inputData.getLong(KEY_PREDICTED_TIME, System.currentTimeMillis())
        val userGoal         = inputData.getString(KEY_USER_GOAL) ?: ""

        val situation = try {
            LifeSituation.valueOf(situationName)
        } catch (e: Exception) {
            Log.e(TAG, "알 수 없는 상황: $situationName")
            return Result.failure()
        }

        val agentPlan = AgentOwnPlan.getAgentsForSituation(situation)
            .find { it.agentId == agentId }
            ?: AgentOwnPlan.getDefaultPlans().find { it.agentId == agentId }
            ?: run {
                Log.e(TAG, "에이전트 계획 없음: $agentId")
                return Result.failure()
            }

        Log.i(TAG, "워밍업 실행: ${agentPlan.displayName} → ${situation.displayName}")

        return try {
            // ── 1. Koin에서 필요 컴포넌트 조회 ──
            val memorySaveHelper = KoinJavaComponent.getKoin()
                .getOrNull<MemorySaveHelper>()

            if (memorySaveHelper == null) {
                Log.w(TAG, "필수 컴포넌트 없음 (memHelper=null) — 재시도")
                return Result.retry()
            }

            // ── 2. 워밍업 프롬프트 생성 ──
            val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(Date(predictedTimeMs))
            val prompt = agentPlan.warmupPromptTemplate
                .replace("{situation}", situation.displayName)
                .replace("{time}", timeStr)
                .replace("{user_goal}", agentPlan.longTermGoal.ifBlank { userGoal })

            // ── 3. AI 호출 (예산 절감: 최소 프롬프트) ──
            // Budget gate: 워밍업 전 예산 확인
            val tracker: com.xreal.nativear.router.persona.TokenBudgetTracker? = try {
                KoinJavaComponent.getKoin().getOrNull()
            } catch (_: Exception) { null }
            tracker?.let {
                val check = it.checkBudget(com.xreal.nativear.ai.ProviderId.GEMINI, estimatedTokens = 300)
                if (!check.allowed) {
                    Log.w(TAG, "워밍업 예산 초과: ${check.reason} — $agentId 건너뜀")
                    return Result.success()  // 예산 부족 시 조용히 종료
                }
            }

            val systemPrompt = "당신은 ${agentPlan.displayName}입니다. " +
                "사용자의 다음 상황을 위해 사전에 준비된 간결한 내용을 제공하세요. " +
                "응답은 200자 이내로 유지하세요."

            val aiRegistry: com.xreal.nativear.ai.IAICallService? = try {
                KoinJavaComponent.getKoin().getOrNull()
            } catch (_: Exception) { null }
            val response = aiRegistry?.quickText(
                messages = listOf(AIMessage(role = "user", content = prompt)),
                systemPrompt = systemPrompt,
                callPriority = AICallGateway.CallPriority.PROACTIVE,
                visibility = AICallGateway.VisibilityIntent.INTERNAL_ONLY,
                intent = "agent_warmup"
            ) ?: run {
                Log.w(TAG, "AIResourceRegistry 없음 — 재시도")
                return Result.retry()
            }

            val content = response.text?.takeIf { it.isNotBlank() }
                ?: run {
                    Log.w(TAG, "AI 응답 비어있음 — 재시도")
                    return Result.retry()
                }

            // ── 4. MemorySaveHelper로 DB 저장 ──
            val dateKey = SimpleDateFormat(DATE_FORMAT, Locale.getDefault())
                .format(Date(predictedTimeMs))
            val cacheKey = "warmup_${agentId}_${situation.name}_${dateKey}"

            memorySaveHelper.saveMemory(
                content = content,
                role = "WARMUP",
                metadata = """{"agent_id":"$agentId","situation":"${situation.name}","predicted_at":$predictedTimeMs,"cache_key":"$cacheKey"}""",
                personaId = agentId
            )

            Log.d(TAG, "워밍업 콘텐츠 저장 완료 (key=$cacheKey): ${content.take(60)}...")

            // ── 5. SituationLifecycleManager에 캐시 키 등록 ──
            KoinJavaComponent.getKoin()
                .getOrNull<SituationLifecycleManager>()
                ?.setWarmupCacheKey(situation, cacheKey)

            Log.i(TAG, "워밍업 완료: $agentId → ${situation.displayName} (캐시 키: $cacheKey)")
            Result.success()

        } catch (e: Exception) {
            ErrorReporter.report(TAG, "워밍업 실행 실패: $agentId → $situationName", e)
            Result.retry()
        }
    }
}
