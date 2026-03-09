package com.xreal.nativear.agent

import android.util.Log
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.*

/**
 * AdaptiveReflectionTrigger — 5분 고정 상수를 대체하는 이벤트 기반 동적 반성 트리거.
 *
 * ## 기존 문제
 * StrategistService가 5분(300초) 고정 주기로 반성. 급변하는 상황에서도 5분 대기,
 * 변화 없는 상황에서도 5분마다 불필요한 AI 호출.
 *
 * ## 새 접근: Adaptive Interval
 * - **가속 트리거**: 에러 급증, 상황 전환, 연속 DISMISSED, 목표 실패 → 즉시 또는 30초 내 반성
 * - **감속 조건**: 변화 없음, 성공 연속 → 간격 점진 증가 (최대 10분)
 * - **지수 백오프**: 트리거 없으면 간격이 서서히 늘어남
 *
 * ## StrategistService와의 관계
 * StrategistService의 고정 delay(REFLECTION_INTERVAL_MS)를 대체.
 * 이 클래스가 "지금 반성해야 한다"는 신호를 보내면 StrategistService가 즉시 실행.
 */
class AdaptiveReflectionTrigger(
    private val eventBus: GlobalEventBus
) {
    companion object {
        private const val TAG = "AdaptiveReflection"
        private const val MIN_INTERVAL_MS = 30_000L       // 최소 30초
        private const val DEFAULT_INTERVAL_MS = 180_000L   // 기본 3분 (5분에서 축소)
        private const val MAX_INTERVAL_MS = 600_000L       // 최대 10분
        private const val ACCELERATION_FACTOR = 0.5f       // 가속 시 현재 간격의 50%로
        private const val DECELERATION_FACTOR = 1.3f       // 감속 시 현재 간격의 130%로
    }

    @Volatile var currentIntervalMs: Long = DEFAULT_INTERVAL_MS
        private set
    @Volatile private var pendingImmediateReflection = false

    private var eventJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 트리거 카운터 (가속 판단용)
    @Volatile private var recentErrorCount = 0
    @Volatile private var recentDismissedCount = 0
    @Volatile private var lastSituationChange = 0L

    fun start() {
        eventJob = scope.launch {
            eventBus.events.collect { event ->
                try {
                    evaluateEvent(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "이벤트 평가 오류: ${e.message}")
                }
            }
        }
        Log.i(TAG, "AdaptiveReflectionTrigger started (초기 간격: ${currentIntervalMs / 1000}초)")
    }

    fun stop() {
        eventJob?.cancel()
        scope.cancel()
    }

    /**
     * 다음 반성까지의 대기 시간.
     * StrategistService가 매 루프에서 호출하여 delay() 인자로 사용.
     */
    fun getNextInterval(): Long {
        if (pendingImmediateReflection) {
            pendingImmediateReflection = false
            return MIN_INTERVAL_MS  // 즉시(30초) 반성
        }
        return currentIntervalMs
    }

    /**
     * 반성 완료 후 호출 — 결과에 따라 간격 조정.
     * @param hadMeaningfulChanges 의미 있는 변화가 있었는가 (새 Directive 생성 등)
     */
    fun onReflectionComplete(hadMeaningfulChanges: Boolean) {
        if (hadMeaningfulChanges) {
            // 변화가 있었으면 → 간격 유지 또는 약간 가속
            currentIntervalMs = (currentIntervalMs * 0.9f).toLong()
                .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        } else {
            // 변화 없으면 → 감속
            decelerate("변화 없음")
        }

        // 카운터 리셋
        recentErrorCount = 0
        recentDismissedCount = 0
    }

    private fun evaluateEvent(event: XRealEvent) {
        when (event) {
            // 에러 급증 → 가속
            is XRealEvent.SystemEvent.Error -> {
                recentErrorCount++
                if (recentErrorCount >= 3) {
                    accelerate("에러 ${recentErrorCount}건 급증")
                }
            }

            // 상황 전환 → 즉시 반성
            is XRealEvent.SystemEvent.SituationChanged -> {
                val now = System.currentTimeMillis()
                if (now - lastSituationChange > 60_000L) {
                    lastSituationChange = now
                    triggerImmediate("상황 전환: ${event.newSituation.name}")
                }
            }

            // 연속 DISMISSED → 가속
            is XRealEvent.SystemEvent.OutcomeRecorded -> {
                if (event.outcome == "DISMISSED") {
                    recentDismissedCount++
                    if (recentDismissedCount >= 2) {
                        accelerate("연속 DISMISSED ${recentDismissedCount}건")
                    }
                }
            }

            // CapabilityTier 변경 → 즉시 반성
            is XRealEvent.SystemEvent.CapabilityTierChanged -> {
                triggerImmediate("CapabilityTier 변경: ${event.previousTier}→${event.tier}")
            }

            else -> { /* 무시 */ }
        }
    }

    private fun accelerate(reason: String) {
        val newInterval = (currentIntervalMs * ACCELERATION_FACTOR).toLong()
            .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        if (newInterval < currentIntervalMs) {
            currentIntervalMs = newInterval
            Log.d(TAG, "반성 가속: ${currentIntervalMs / 1000}초 ($reason)")
        }
    }

    private fun decelerate(reason: String) {
        val newInterval = (currentIntervalMs * DECELERATION_FACTOR).toLong()
            .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        currentIntervalMs = newInterval
        Log.d(TAG, "반성 감속: ${currentIntervalMs / 1000}초 ($reason)")
    }

    private fun triggerImmediate(reason: String) {
        pendingImmediateReflection = true
        Log.i(TAG, "즉시 반성 트리거: $reason")
    }
}
