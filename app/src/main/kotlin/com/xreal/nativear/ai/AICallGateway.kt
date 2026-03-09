package com.xreal.nativear.ai

import android.util.Log
import com.xreal.nativear.policy.PolicyReader
import com.xreal.nativear.router.persona.TokenBudgetTracker
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * AICallGateway — 모든 클라우드 AI 호출의 중앙 게이트.
 *
 * AIResourceRegistry 내부에서 호출되어 모든 AI 호출 경로를 자동 보호.
 * 직접 호출자 코드 변경 불필요 (13+ callers 자동 적용).
 *
 * ## 보호 메커니즘
 * 1. 분당 호출 수 제한 (rolling window)
 * 2. 글로벌 예산 연동 (TokenBudgetTracker)
 * 3. PROACTIVE 호출 예산 cutoff
 * 4. 열 쓰로틀 (기기 온도 기반)
 * 5. 최소 호출 간격 (PROACTIVE)
 * 6. 동시 PROACTIVE 호출 수 제한
 * 7. 가시성 추적 (사용자에게 보이는 결과 비율 모니터링)
 *
 * ## 정책 키 (모두 PolicyReader 기반)
 * - gateway.enabled
 * - gateway.max_calls_per_minute
 * - gateway.proactive_budget_cutoff
 * - gateway.thermal_throttle_temp
 * - gateway.thermal_max_calls_per_minute
 * - gateway.max_concurrent_proactive
 * - gateway.min_cloud_interval_ms
 * - gateway.budget_scale_50_multiplier
 * - gateway.budget_scale_70_multiplier
 * - gateway.budget_scale_90_suspend
 * - gateway.invisible_call_warn_ratio
 */
class AICallGateway(
    private val tokenBudgetTracker: TokenBudgetTracker? = null,
    private val valueGatekeeper: ValueGatekeeper? = null
) {
    companion object {
        private const val TAG = "AICallGateway"
        private const val WINDOW_MS = 60_000L  // 1분 rolling window
    }

    // ── 호출 우선순위 ──

    enum class CallPriority(val level: Int) {
        /** 사용자 직접 명령 (음성, 터치) — 항상 허용 */
        USER_COMMAND(0),
        /** 안전/긴급 (TTS, 위험 감지) — 항상 허용 */
        SAFETY(1),
        /** 반응형 (이벤트 기반: OCR 감지, 장면 변화 등) */
        REACTIVE(2),
        /** 자율행동 (주기적 리플렉션, 프로액티브 에이전트 등) */
        PROACTIVE(3)
    }

    // ── 가시성 의도 ──

    enum class VisibilityIntent {
        /** 사용자에게 직접 TTS/HUD로 전달될 결과 */
        USER_FACING,
        /** 내부 처리용 (메모리 압축, 전략 분석 등) */
        INTERNAL_ONLY
    }

    // ── 게이트 결과 ──

    data class GateDecision(
        val allowed: Boolean,
        val reason: String,
        val waitMs: Long = 0      // 허용이지만 대기 필요 시
    )

    // ── 상태 추적 ──

    // Rolling window: 최근 1분간 호출 타임스탬프
    private val callTimestamps = ConcurrentLinkedDeque<Long>()

    // 동시 PROACTIVE 호출 수
    private val concurrentProactive = AtomicInteger(0)

    // 마지막 클라우드 PROACTIVE 호출 시각
    private val lastProactiveCallMs = AtomicLong(0L)

    // 가시성 통계 (최근 100건)
    private val visibilityLog = ConcurrentLinkedDeque<Boolean>() // true=USER_FACING
    private val totalCalls = AtomicInteger(0)

    // 열 쓰로틀 상태
    @Volatile var currentThermalTemp: Int = 25
        private set

    // Rate limit mutex (순서 보장)
    private val rateMutex = Mutex()

    // ── 메인 게이트 ──

    /**
     * AI 호출 전 게이트 체크.
     * AIResourceRegistry.routeText()/routeVision() 시작 시 호출.
     *
     * @param priority 호출 우선순위
     * @param visibility 결과 가시성 의도
     * @param estimatedTokens 예상 토큰 수
     * @param providerId 대상 프로바이더 (엣지/로컬은 게이트 면제)
     * @return GateDecision — allowed=false면 호출 차단
     */
    /**
     * @param intent 호출 목적 식별자 (ValueGatekeeper 이력 추적용, 예: "strategist_reflection")
     */
    suspend fun checkGate(
        priority: CallPriority,
        visibility: VisibilityIntent = VisibilityIntent.USER_FACING,
        estimatedTokens: Int = 500,
        providerId: ProviderId? = null,
        intent: String = "unknown"
    ): GateDecision {
        // 게이트 비활성화 시 무조건 허용
        if (!PolicyReader.getBoolean("gateway.enabled", true)) {
            return GateDecision(allowed = true, reason = "게이트 비활성화")
        }

        // 엣지/로컬 프로바이더는 게이트 면제 ($0 비용)
        if (providerId != null && isFreeTier(providerId)) {
            return GateDecision(allowed = true, reason = "무료 tier (${providerId.name})")
        }

        // USER_COMMAND + SAFETY는 항상 허용 (rate limit만 기록)
        if (priority == CallPriority.USER_COMMAND || priority == CallPriority.SAFETY) {
            recordCall(visibility)
            return GateDecision(allowed = true, reason = "필수 호출 (${priority.name})")
        }

        // ── 0. ValueGatekeeper: 통계 기반 가치 판단 (예산 체크보다 먼저) ──
        valueGatekeeper?.let { gk ->
            val valueDecision = gk.checkValue(intent, priority, visibility, estimatedTokens)
            when (valueDecision.verdict) {
                ValueGatekeeper.Verdict.SKIP -> {
                    Log.d(TAG, "ValueGatekeeper SKIP [$intent]: ${valueDecision.reason}")
                    return GateDecision(allowed = false, reason = "가치 판단 차단: ${valueDecision.reason}")
                }
                ValueGatekeeper.Verdict.ASK_USER -> {
                    // 사용자 승인 요청은 호출자가 처리해야 하므로 일단 차단
                    // (AIResourceRegistry에서 ASK_USER 처리)
                    Log.i(TAG, "ValueGatekeeper ASK_USER [$intent]: ${valueDecision.reason}")
                    return GateDecision(allowed = false, reason = "ASK_USER: ${valueDecision.reason}")
                }
                ValueGatekeeper.Verdict.ALLOW -> { /* 계속 진행 */ }
            }
        }

        // ── 1. 글로벌 예산 체크 ──
        val budgetDecision = checkBudgetGate(priority, estimatedTokens)
        if (!budgetDecision.allowed) return budgetDecision

        // ── 2. 분당 호출 수 제한 ──
        val rateDecision = checkRateLimit(priority)
        if (!rateDecision.allowed) return rateDecision

        // ── 3. 열 쓰로틀 ──
        val thermalDecision = checkThermalThrottle(priority)
        if (!thermalDecision.allowed) return thermalDecision

        // ── 4. PROACTIVE 전용 제한 ──
        if (priority == CallPriority.PROACTIVE) {
            val proactiveDecision = checkProactiveGate()
            if (!proactiveDecision.allowed) return proactiveDecision
        }

        // ── 통과 ──
        recordCall(visibility)
        return GateDecision(allowed = true, reason = "게이트 통과")
    }

    // ── 예산 게이트 ──

    private fun checkBudgetGate(priority: CallPriority, estimatedTokens: Int): GateDecision {
        val tracker = tokenBudgetTracker ?: return GateDecision(allowed = true, reason = "예산 추적기 없음")

        val usageRatio = tracker.getGlobalUsageRatio()

        // PROACTIVE 호출: 예산 cutoff 적용
        if (priority == CallPriority.PROACTIVE) {
            val cutoff = PolicyReader.getFloat("gateway.proactive_budget_cutoff", 0.70f)
            if (usageRatio >= cutoff) {
                return GateDecision(
                    allowed = false,
                    reason = "PROACTIVE 예산 cutoff (사용률 ${formatPercent(usageRatio)} >= ${formatPercent(cutoff)})"
                )
            }
        }

        // 90%+ 시 PROACTIVE 전면 중단
        if (priority == CallPriority.PROACTIVE &&
            PolicyReader.getBoolean("gateway.budget_scale_90_suspend", true) &&
            usageRatio >= 0.90f
        ) {
            return GateDecision(
                allowed = false,
                reason = "예산 90%+ PROACTIVE 전면 중단 (사용률 ${formatPercent(usageRatio)})"
            )
        }

        // REACTIVE도 95%에서 차단
        if (priority == CallPriority.REACTIVE && usageRatio >= 0.95f) {
            return GateDecision(
                allowed = false,
                reason = "예산 95%+ REACTIVE 차단 (사용률 ${formatPercent(usageRatio)})"
            )
        }

        return GateDecision(allowed = true, reason = "예산 정상")
    }

    // ── 분당 호출 수 제한 ──

    private suspend fun checkRateLimit(priority: CallPriority): GateDecision = rateMutex.withLock {
        pruneOldTimestamps()

        val maxPerMinute = if (currentThermalTemp >= PolicyReader.getInt("gateway.thermal_throttle_temp", 40)) {
            PolicyReader.getInt("gateway.thermal_max_calls_per_minute", 3)
        } else {
            PolicyReader.getInt("gateway.max_calls_per_minute", 10)
        }

        val currentCount = callTimestamps.size

        if (currentCount >= maxPerMinute) {
            val oldestTs = callTimestamps.peekFirst() ?: System.currentTimeMillis()
            val waitMs = (oldestTs + WINDOW_MS - System.currentTimeMillis()).coerceAtLeast(0)
            return@withLock GateDecision(
                allowed = false,
                reason = "분당 호출 한도 초과 ($currentCount/$maxPerMinute)",
                waitMs = waitMs
            )
        }

        GateDecision(allowed = true, reason = "호출 수 정상 ($currentCount/$maxPerMinute)")
    }

    // ── 열 쓰로틀 ──

    private fun checkThermalThrottle(priority: CallPriority): GateDecision {
        val thermalLimit = PolicyReader.getInt("gateway.thermal_throttle_temp", 40)
        if (currentThermalTemp >= thermalLimit && priority == CallPriority.PROACTIVE) {
            return GateDecision(
                allowed = false,
                reason = "열 쓰로틀 (${currentThermalTemp}°C >= ${thermalLimit}°C) — PROACTIVE 차단"
            )
        }
        return GateDecision(allowed = true, reason = "온도 정상")
    }

    // ── PROACTIVE 전용 제한 ──

    private fun checkProactiveGate(): GateDecision {
        // 동시 실행 수 제한
        val maxConcurrent = PolicyReader.getInt("gateway.max_concurrent_proactive", 2)
        if (concurrentProactive.get() >= maxConcurrent) {
            return GateDecision(
                allowed = false,
                reason = "동시 PROACTIVE 한도 초과 (${concurrentProactive.get()}/$maxConcurrent)"
            )
        }

        // 최소 간격 (예산 사용률에 따라 스케일링)
        val baseInterval = PolicyReader.getLong("gateway.min_cloud_interval_ms", 60_000L)
        val scaledInterval = computeScaledInterval(baseInterval)
        val elapsed = System.currentTimeMillis() - lastProactiveCallMs.get()
        if (elapsed < scaledInterval) {
            return GateDecision(
                allowed = false,
                reason = "PROACTIVE 최소 간격 미달 (${elapsed}ms / ${scaledInterval}ms)",
                waitMs = scaledInterval - elapsed
            )
        }

        return GateDecision(allowed = true, reason = "PROACTIVE 허용")
    }

    // ── 예산 기반 간격 스케일링 ──

    private fun computeScaledInterval(baseMs: Long): Long {
        val usageRatio = tokenBudgetTracker?.getGlobalUsageRatio() ?: 0f

        val multiplier = when {
            usageRatio >= 0.70f -> PolicyReader.getFloat("gateway.budget_scale_70_multiplier", 4.0f)
            usageRatio >= 0.50f -> PolicyReader.getFloat("gateway.budget_scale_50_multiplier", 2.0f)
            else -> 1.0f
        }

        return (baseMs * multiplier).toLong()
    }

    // ── PROACTIVE 동시성 관리 ──

    /**
     * PROACTIVE 호출 시작 시 호출 (concurrency 카운터 증가).
     * 반드시 onProactiveCallEnd()와 쌍으로 사용할 것.
     */
    fun onProactiveCallStart() {
        concurrentProactive.incrementAndGet()
        lastProactiveCallMs.set(System.currentTimeMillis())
    }

    /** PROACTIVE 호출 완료 시 호출. */
    fun onProactiveCallEnd() {
        concurrentProactive.decrementAndGet().coerceAtLeast(0).also {
            // AtomicInteger 음수 방지
            if (concurrentProactive.get() < 0) concurrentProactive.set(0)
        }
    }

    // ── 열 상태 갱신 ──

    /** BatteryMonitor 등에서 주기적으로 호출 */
    fun updateThermalTemp(tempCelsius: Int) {
        currentThermalTemp = tempCelsius
    }

    // ── 가시성 추적 ──

    private fun recordCall(visibility: VisibilityIntent) {
        val now = System.currentTimeMillis()
        callTimestamps.addLast(now)
        totalCalls.incrementAndGet()

        val isUserFacing = visibility == VisibilityIntent.USER_FACING
        visibilityLog.addLast(isUserFacing)
        while (visibilityLog.size > 100) visibilityLog.pollFirst()

        // 가시성 비율 경고
        if (totalCalls.get() % 20 == 0 && visibilityLog.size >= 20) {
            val userFacingRatio = visibilityLog.count { it }.toFloat() / visibilityLog.size
            val warnThreshold = PolicyReader.getFloat("gateway.invisible_call_warn_ratio", 0.50f)
            if (userFacingRatio < (1f - warnThreshold)) {
                Log.w(TAG, "가시성 경고: 최근 ${visibilityLog.size}건 중 " +
                        "${formatPercent(userFacingRatio)}만 사용자에게 표시됨 " +
                        "(임계값: ${formatPercent(1f - warnThreshold)})")
            }
        }
    }

    // ── 내부 유틸 ──

    private fun pruneOldTimestamps() {
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        while (callTimestamps.peekFirst()?.let { it < cutoff } == true) {
            callTimestamps.pollFirst()
        }
    }

    private fun isFreeTier(providerId: ProviderId): Boolean = when (providerId) {
        ProviderId.LOCAL, ProviderId.LOCAL_STEAMDECK, ProviderId.LOCAL_SPEECH_PC,
        ProviderId.EDGE_ROUTER, ProviderId.EDGE_AGENT, ProviderId.EDGE_EMERGENCY -> true
        else -> false
    }

    private fun formatPercent(ratio: Float): String =
        String.format("%.0f%%", ratio * 100)

    // ── 진단/상태 ──

    /** HUD/디버그용 상태 요약 */
    fun getStatusSummary(): String {
        pruneOldTimestamps()
        val maxPerMin = PolicyReader.getInt("gateway.max_calls_per_minute", 10)
        val usageRatio = tokenBudgetTracker?.getGlobalUsageRatio() ?: 0f
        val userFacingPct = if (visibilityLog.isNotEmpty()) {
            visibilityLog.count { it } * 100 / visibilityLog.size
        } else 0

        return "Gate: ${callTimestamps.size}/$maxPerMin/min | " +
                "Budget: ${formatPercent(usageRatio)} | " +
                "Proactive: ${concurrentProactive.get()} | " +
                "Visible: ${userFacingPct}% | " +
                "Temp: ${currentThermalTemp}°C"
    }

    /** 최근 1분 호출 수 */
    fun getRecentCallCount(): Int {
        pruneOldTimestamps()
        return callTimestamps.size
    }
}
