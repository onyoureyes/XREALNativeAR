package com.xreal.nativear.companion

import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.ai.AICallGateway
import com.xreal.nativear.ai.AIMessage
import com.xreal.nativear.core.ErrorReporter
import com.xreal.nativear.memory.api.IMemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * KnowledgePrefetcher — 에이전트 도메인 지식 선제 적재 (Phase F-4).
 *
 * ## 역할
 * 각 AI 에이전트(AgentOwnPlan)가 담당 상황에서 효과적으로 지원하기 위한
 * 배경 지식(도메인 지식)을 주기적으로 사전 생성하여 메모리 DB에 저장.
 *
 * ## Warmup과의 차이
 * ```
 * AgentWarmupWorker  → 특정 상황 X분 전 → 상황 맞춤 준비 (일별 갱신)
 * KnowledgePrefetcher → N일 주기       → 에이전트 배경 지식 (knowledgeRefreshIntervalDays)
 * ```
 *
 * ## 동작 흐름
 * ```
 * runPrefetchCycle() — WorkManager(KnowledgePrefetchWorker) 에서 호출
 *    → 각 활성 AgentOwnPlan 순회
 *    → isRefreshNeeded(agent) 확인 (마지막 갱신이 N일 초과 여부)
 *    → 필요시 prefetchKnowledge(agent) 실행:
 *         1. 도메인 지식 프롬프트 생성
 *         2. Gemini 호출 (150자 이내 배경 지식)
 *         3. MemorySaveHelper.saveMemory(role="KNOWLEDGE", personaId=agentId)
 *         4. 갱신 시각 structured_data(knowledge_cache)에 기록
 * ```
 *
 * ## 저장 구조
 * - **메모리 DB**: role="KNOWLEDGE", persona_id=agentId → PersonaManager 메모리 주입 경로 활용
 * - **갱신 추적**: structured_data(domain="knowledge_cache", key=agentId, value=lastRefreshMs)
 *
 * ## 비용 최적화
 * - 갱신 주기 3-14일 (에이전트별 상이) — 잦은 갱신 억제
 * - 응답 150자 이내 — 토큰 최소화
 * - `SKIP` 조건: 배터리 부족, 오프라인 (WorkManager 제약으로 보장)
 */
class KnowledgePrefetcher(
    private val aiRegistry: com.xreal.nativear.ai.IAICallService,
    private val memoryStore: IMemoryStore,
    private val database: UnifiedMemoryDatabase
) {
    companion object {
        private const val TAG = "KnowledgePrefetcher"
        private const val DB_DOMAIN = "knowledge_cache"
        private const val DATE_FORMAT = "yyyy-MM-dd"
    }

    // ─── 핵심 공개 API ────────────────────────────────────────────────────────

    /**
     * 모든 활성 에이전트에 대한 지식 사전 적재 사이클 실행.
     *
     * WorkManager(KnowledgePrefetchWorker)에서 매일 02:00 호출.
     * 각 에이전트의 갱신 주기(knowledgeRefreshIntervalDays)에 따라 선택적으로 실행.
     *
     * @return 실제 갱신된 에이전트 수
     */
    suspend fun runPrefetchCycle(): Int = withContext(Dispatchers.IO) {
        val plans = AgentOwnPlan.getDefaultPlans().filter { it.isActive }
        var refreshedCount = 0

        for (plan in plans) {
            try {
                if (isRefreshNeeded(plan)) {
                    Log.i(TAG, "도메인 지식 갱신 시작: ${plan.displayName} (${plan.knowledgeRefreshIntervalDays}일 주기)")
                    prefetchKnowledge(plan)
                    refreshedCount++
                } else {
                    Log.d(TAG, "도메인 지식 최신 상태 — 갱신 건너뜀: ${plan.displayName}")
                }
            } catch (e: Exception) {
                ErrorReporter.report(TAG, "도메인 지식 사전 적재 실패: ${plan.agentId}", e)
            }
        }

        Log.i(TAG, "지식 사전 적재 사이클 완료: $refreshedCount / ${plans.size} 에이전트 갱신")
        refreshedCount
    }

    // ─── 내부 로직 ────────────────────────────────────────────────────────────

    /**
     * 해당 에이전트의 도메인 지식 갱신이 필요한지 확인.
     *
     * structured_data(knowledge_cache)에서 마지막 갱신 시각을 조회하여
     * knowledgeRefreshIntervalDays가 경과했으면 true 반환.
     */
    private fun isRefreshNeeded(plan: AgentOwnPlan): Boolean {
        val lastRefreshMs = getLastRefreshMs(plan.agentId)
        if (lastRefreshMs == 0L) return true  // 한 번도 갱신 안 됨

        val intervalMs = plan.knowledgeRefreshIntervalDays * 24 * 60 * 60 * 1000L
        val elapsed = System.currentTimeMillis() - lastRefreshMs
        return elapsed >= intervalMs
    }

    /**
     * 에이전트 도메인 지식 단건 사전 적재.
     *
     * 1. 배경 지식 프롬프트 생성
     * 2. Gemini 호출 (150자 이내)
     * 3. MemorySaveHelper로 DB 저장 (role="KNOWLEDGE", personaId=agentId)
     * 4. 갱신 시각 기록
     */
    private suspend fun prefetchKnowledge(plan: AgentOwnPlan) {
        // Budget gate: 프리페치 전 예산 확인
        val tracker: com.xreal.nativear.router.persona.TokenBudgetTracker? = try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull()
        } catch (_: Exception) { null }
        tracker?.let {
            val check = it.checkBudget(com.xreal.nativear.ai.ProviderId.GEMINI, estimatedTokens = 200)
            if (!check.allowed) {
                Log.w(TAG, "지식 프리페치 예산 초과: ${check.reason} — ${plan.agentId} 건너뜀")
                return
            }
        }

        val situationNames = plan.targetSituations.joinToString(", ") { it.displayName }
        val dateStr = SimpleDateFormat(DATE_FORMAT, Locale.getDefault())
            .format(Date())

        val systemPrompt = "당신은 ${plan.displayName}입니다. " +
            "사용자 지원에 필요한 핵심 도메인 지식을 제공합니다. " +
            "응답은 150자 이내로 유지하세요."

        val userPrompt = """
            다음 상황들을 담당하는 ${plan.displayName}로서,
            아래에 대한 핵심 배경 지식을 150자 이내로 요약하세요:

            담당 상황: $situationNames
            장기 목표: ${plan.longTermGoal}
            갱신 일자: $dateStr

            사용자 지원 시 즉시 활용 가능한 최신 도메인 지식 2-3가지를 제공하세요.
            데이터가 없으면 일반적인 전문가 지식으로 대체하세요.
        """.trimIndent()

        val response = aiRegistry.quickText(
            messages = listOf(AIMessage(role = "user", content = userPrompt)),
            systemPrompt = systemPrompt,
            callPriority = AICallGateway.CallPriority.PROACTIVE,
            visibility = AICallGateway.VisibilityIntent.INTERNAL_ONLY,
            intent = "knowledge_prefetch"
        ) ?: run {
            Log.w(TAG, "AI 프로바이더 모두 실패 — 지식 적재 건너뜀: ${plan.agentId}")
            return
        }

        val content = response.text?.takeIf { it.isNotBlank() }
            ?: run {
                Log.w(TAG, "AI 응답 비어있음 — 지식 적재 건너뜀: ${plan.agentId}")
                return
            }

        // MemorySaveHelper로 persona_id 태깅 저장
        val metadata = """{
            "agent_id":"${plan.agentId}",
            "refresh_date":"$dateStr",
            "situations":"$situationNames",
            "knowledge_type":"domain_background"
        }""".trimIndent()

        memoryStore.save(
            content = content,
            role = "KNOWLEDGE",
            metadata = metadata,
            personaId = plan.agentId
        )

        // 갱신 시각 기록
        markRefreshed(plan.agentId)

        Log.i(TAG, "도메인 지식 저장 완료: ${plan.displayName} [$dateStr] → ${content.take(50)}...")
    }

    // ─── DB 헬퍼 ──────────────────────────────────────────────────────────────

    private fun getLastRefreshMs(agentId: String): Long {
        return try {
            database.queryStructuredData(domain = DB_DOMAIN, dataKey = agentId, limit = 1)
                .firstOrNull()?.value?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "지식 갱신 시각 조회 실패: $agentId — ${e.message}")
            0L
        }
    }

    private fun markRefreshed(agentId: String) {
        try {
            database.upsertStructuredData(
                domain = DB_DOMAIN,
                dataKey = agentId,
                value = System.currentTimeMillis().toString(),
                tags = "knowledge_refresh"
            )
        } catch (e: Exception) {
            Log.w(TAG, "지식 갱신 시각 기록 실패: $agentId — ${e.message}")
        }
    }

    // ─── 상태 조회 ────────────────────────────────────────────────────────────

    /**
     * 현재 에이전트별 지식 갱신 상태 요약 (디버깅/AI 도구용).
     */
    fun getStatusSummary(): String = buildString {
        appendLine("[KnowledgePrefetcher]")
        val plans = AgentOwnPlan.getDefaultPlans().filter { it.isActive }
        plans.forEach { plan ->
            val lastMs = getLastRefreshMs(plan.agentId)
            val lastStr = if (lastMs == 0L) "미갱신"
            else {
                val daysAgo = ((System.currentTimeMillis() - lastMs) / (24 * 60 * 60 * 1000L)).toInt()
                "${daysAgo}일 전"
            }
            val needsRefresh = isRefreshNeeded(plan)
            appendLine("  ${plan.displayName}: 최종 갱신 $lastStr " +
                "(주기 ${plan.knowledgeRefreshIntervalDays}일${if (needsRefresh) " ★갱신필요" else ""})")
        }
    }.trim()
}
