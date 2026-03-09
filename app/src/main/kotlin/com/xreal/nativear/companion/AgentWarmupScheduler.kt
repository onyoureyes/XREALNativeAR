package com.xreal.nativear.companion

import android.content.Context
import android.util.Log
import com.xreal.nativear.batch.AgentWarmupWorker
import com.xreal.nativear.core.ErrorReporter
import com.xreal.nativear.policy.PolicyReader
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * AgentWarmupScheduler — 예측 기반 에이전트 워밍업 예약자 (Phase F-3).
 *
 * ## 역할
 * SituationPredictor의 예측 결과와 SituationLifecycleManager의 숙련도 변화를
 * 구독하여, 적절한 에이전트의 워밍업 작업(AgentWarmupWorker)을 WorkManager에 예약.
 *
 * ## 동작 흐름
 * ```
 * [start()]
 *   → predictor.getTodayPredictions() 조회
 *   → 미래 예측에 대해 scheduleWarmupForPredictions() 실행
 *
 * [SituationMasteryChanged 이벤트]
 *   → newLevel == ROUTINE || MASTERED 이면
 *   → 오늘 예측 중 해당 상황 항목 찾기
 *   → 워밍업 즉시 예약 (숙련도 진입 시점부터 워밍업 활성화)
 * ```
 *
 * ## 예약 조건
 * - 예측 확률 35% 이상 (SituationPredictor MIN_PROBABILITY 기준)
 * - 워밍업 실행 시각이 현재보다 미래일 것 (delayMs > 0)
 * - 해당 상황을 담당하는 AgentOwnPlan이 존재하고 isActive == true
 * - 워밍업 처리링: WARMUP_CACHE 또는 API_SINGLE (MISSION_TEAM은 실시간 대응)
 *
 * ## 캐시 키 형식
 * `"warmup_{agentId}_{situation}_{YYYY-MM-DD}"` — AgentWarmupWorker가 저장
 */
class AgentWarmupScheduler(
    private val context: Context,
    private val eventBus: GlobalEventBus,
    private val predictor: SituationPredictor
) {
    companion object {
        private const val TAG = "AgentWarmupScheduler"

        /** 워밍업이 의미 있으려면 최소 이 시간 이상 남아야 한다 (5분) */
        private val MIN_DELAY_MS: Long get() = PolicyReader.getLong("companion.warmup_min_delay_ms", 300_000L)

        /**
         * MISSION_TEAM은 워밍업 불필요 (상황 발생 시 즉시 미션 팀 구성).
         * LOCAL_ML도 워밍업 불필요 (온디바이스 추론, 토큰 0).
         * → WARMUP_CACHE / API_SINGLE 처리링만 워밍업 대상.
         */
        private val WARMUP_ELIGIBLE_RINGS = setOf(
            SituationLifecycleManager.ProcessingRing.WARMUP_CACHE.name,
            SituationLifecycleManager.ProcessingRing.API_SINGLE.name
        )
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var collectJob: Job? = null

    // ─── 생명주기 ─────────────────────────────────────────────────────────────

    fun start() {
        // ① 오늘 예측을 즉시 읽어 워밍업 예약
        scope.launch(Dispatchers.IO) {
            try {
                val predictions = predictor.getTodayPredictions()
                val scheduled = scheduleWarmupForPredictions(predictions)
                Log.i(TAG, "시작 시 워밍업 예약: $scheduled 개 작업 등록 (오늘 예측 ${predictions.size}개 중)")
            } catch (e: Exception) {
                ErrorReporter.report(TAG, "초기 워밍업 예약 실패", e)
            }
        }

        // ② SituationMasteryChanged 구독: 숙련도 진입 시 즉시 워밍업 활성화
        collectJob = scope.launch {
            eventBus.events.collect { event ->
                try {
                    if (event is XRealEvent.SystemEvent.SituationMasteryChanged) {
                        onMasteryChanged(event)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ErrorReporter.report(TAG, "숙련도 변경 처리 오류", e)
                }
            }
        }

        Log.i(TAG, "AgentWarmupScheduler 시작 — 예측 기반 워밍업 예약 활성")
    }

    fun stop() {
        collectJob?.cancel()
        scope.cancel()
        Log.i(TAG, "AgentWarmupScheduler 종료")
    }

    // ─── 핵심 로직 ────────────────────────────────────────────────────────────

    /**
     * 예측 목록을 순회하며 각 상황에 해당하는 에이전트 워밍업 WorkManager 작업 예약.
     *
     * @param predictions SituationPredictor.getTodayPredictions() 결과
     * @return 실제 예약된 워밍업 작업 수
     */
    private fun scheduleWarmupForPredictions(
        predictions: List<SituationPredictor.PredictedSituation>
    ): Int {
        val now = System.currentTimeMillis()
        var scheduledCount = 0

        for (prediction in predictions) {
            // 워밍업 대상 처리링 필터
            if (prediction.processingRing !in WARMUP_ELIGIBLE_RINGS) {
                Log.d(TAG, "워밍업 건너뜀 (링 부적합): ${prediction.situation.displayName} → ${prediction.processingRing}")
                continue
            }

            val delayMs = prediction.warmupTimeMs - now
            if (delayMs < MIN_DELAY_MS) {
                Log.d(TAG, "워밍업 건너뜀 (시간 부족): ${prediction.situation.displayName} " +
                    "(남은 시간 ${delayMs / 60000}분 < 최소 ${MIN_DELAY_MS / 60000}분)")
                continue
            }

            // 해당 상황을 담당하는 활성 에이전트 조회
            val agents = AgentOwnPlan.getAgentsForSituation(prediction.situation)
            if (agents.isEmpty()) {
                Log.d(TAG, "워밍업 건너뜀 (담당 에이전트 없음): ${prediction.situation.displayName}")
                continue
            }

            // 각 담당 에이전트에 대해 워밍업 예약
            for (agent in agents) {
                try {
                    AgentWarmupWorker.schedule(
                        context = context,
                        situation = prediction.situation,
                        agentId = agent.agentId,
                        predictedTimeMs = prediction.predictedTimeMs,
                        delayMs = delayMs,
                        userGoal = agent.longTermGoal
                    )
                    scheduledCount++
                    Log.d(TAG, "워밍업 예약: ${agent.displayName} → ${prediction.situation.displayName} " +
                        "in ${delayMs / 60000}분 (확률 ${(prediction.probability * 100).toInt()}%)")
                } catch (e: Exception) {
                    ErrorReporter.report(TAG,
                        "워밍업 WorkManager 예약 실패: ${agent.agentId} → ${prediction.situation.name}", e)
                }
            }
        }

        return scheduledCount
    }

    /**
     * 숙련도가 ROUTINE 또는 MASTERED에 도달했을 때 워밍업을 즉시 활성화.
     *
     * UNKNOWN/LEARNING 단계에서는 워밍업이 의미 없으므로 (패턴 미확립),
     * ROUTINE 이상 진입 시점에 오늘 예측에서 해당 상황을 찾아 즉시 예약.
     */
    private suspend fun onMasteryChanged(event: XRealEvent.SystemEvent.SituationMasteryChanged) {
        val newLevel = event.newLevel

        // ROUTINE 또는 MASTERED 진입 시에만 반응
        if (newLevel != SituationLifecycleManager.MasteryLevel.ROUTINE.name &&
            newLevel != SituationLifecycleManager.MasteryLevel.MASTERED.name) {
            return
        }

        Log.i(TAG, "숙련도 진입 감지: ${event.situation.displayName} → $newLevel " +
            "(처리링: ${event.processingRing}) — 워밍업 즉시 활성화")

        // 오늘 예측에서 해당 상황 항목 찾기
        val todayPredictions = try {
            predictor.getTodayPredictions()
        } catch (e: Exception) {
            ErrorReporter.report(TAG, "숙련도 변경 후 예측 조회 실패", e)
            return
        }

        val matchingPredictions = todayPredictions.filter {
            it.situation == event.situation
        }

        if (matchingPredictions.isEmpty()) {
            Log.d(TAG, "숙련도 진입 처리: 오늘 예측에 ${event.situation.displayName} 항목 없음 (내일부터 적용)")
            return
        }

        val scheduled = scheduleWarmupForPredictions(matchingPredictions)
        Log.i(TAG, "숙련도 진입 후 워밍업 예약 완료: ${event.situation.displayName} → $scheduled 개")
    }

    // ─── 상태 조회 ────────────────────────────────────────────────────────────

    /**
     * 현재 워밍업 스케줄러 상태 요약 (디버깅/AI 도구용).
     */
    fun getStatusSummary(): String = buildString {
        appendLine("[AgentWarmupScheduler]")
        appendLine("활성: ${collectJob?.isActive == true}")
        val rings = WARMUP_ELIGIBLE_RINGS.joinToString("/")
        appendLine("워밍업 대상 처리링: $rings")
        appendLine("등록 에이전트: ${AgentOwnPlan.getDefaultPlans().count { it.isActive }}개")
        val agentList = AgentOwnPlan.getDefaultPlans()
            .filter { it.isActive }
            .joinToString(", ") { "${it.displayName}(${it.agentId})" }
        appendLine("에이전트 목록: $agentList")
    }.trim()
}
