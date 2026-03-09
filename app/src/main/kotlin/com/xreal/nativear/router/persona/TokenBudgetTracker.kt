package com.xreal.nativear.router.persona

import android.util.Log
import com.xreal.nativear.ai.ProviderId
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * TokenBudgetTracker: Enforces daily token budgets per AI provider.
 *
 * Architecture Principle 1: "AI Call Gate"
 * - 90% consumed → block ENRICHED calls
 * - 95% consumed → block STANDARD, only MINIMAL allowed
 * - 100% consumed → block ALL non-essential calls (only TTS + safety)
 *
 * Budget tiers cascade automatically. When budget is exhausted,
 * AI calls are gracefully rejected with informative messages.
 *
 * Economic constraint: ~$3/day (~12만원/month), Gemini Flash 기준.
 */
class TokenBudgetTracker(
    private val dailyBudgets: Map<ProviderId, Int> = DEFAULT_BUDGETS,
    private val analyticsService: com.xreal.nativear.analytics.SystemAnalyticsService? = null
) {
    companion object {
        private const val TAG = "TokenBudgetTracker"

        // ★ Policy Department: PolicyRegistry shadow read (fallback = 기존 하드코딩 값)
        val DEFAULT_BUDGETS: Map<ProviderId, Int> get() = mapOf(
            ProviderId.GEMINI to com.xreal.nativear.policy.PolicyReader.getInt("budget.gemini_daily_tokens", 500_000),
            ProviderId.OPENAI to com.xreal.nativear.policy.PolicyReader.getInt("budget.openai_daily_tokens", 100_000),
            ProviderId.CLAUDE to com.xreal.nativear.policy.PolicyReader.getInt("budget.claude_daily_tokens", 100_000),
            ProviderId.GROK to com.xreal.nativear.policy.PolicyReader.getInt("budget.grok_daily_tokens", 100_000),
            // 로컬/엣지 LLM은 비용 $0 → 무제한 예산
            ProviderId.LOCAL to Int.MAX_VALUE,
            ProviderId.LOCAL_STEAMDECK to Int.MAX_VALUE,
            ProviderId.EDGE_ROUTER to Int.MAX_VALUE,
            ProviderId.EDGE_AGENT to Int.MAX_VALUE,
            ProviderId.EDGE_EMERGENCY to Int.MAX_VALUE
        )

        // Budget tier thresholds (Architecture Principle 1)
        // ★ Policy Department: PolicyRegistry shadow read
        private val CAREFUL_THRESHOLD: Float get() =
            com.xreal.nativear.policy.PolicyReader.getFloat("budget.careful_threshold", 0.90f)
        private val MINIMAL_THRESHOLD: Float get() =
            com.xreal.nativear.policy.PolicyReader.getFloat("budget.minimal_threshold", 0.95f)
        private val BLOCKED_THRESHOLD: Float get() =
            com.xreal.nativear.policy.PolicyReader.getFloat("budget.blocked_threshold", 1.00f)
    }

    private val usage = ConcurrentHashMap<ProviderId, AtomicInteger>()
    @Volatile private var lastResetDay: Int = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

    // ★ P1-4: 사용자 승인 임시 오버라이드 (음성 명령 예산 초과 시)
    @Volatile private var overrideExpiresAt: Long = 0L

    // Track tier transitions for logging
    private val lastLoggedTier = ConcurrentHashMap<ProviderId, BudgetTier>()

    init {
        ProviderId.entries.forEach { usage[it] = AtomicInteger(0) }
    }

    fun recordUsage(providerId: ProviderId, tokens: Int) {
        checkAndResetIfNewDay()
        val total = usage.getOrPut(providerId) { AtomicInteger(0) }.addAndGet(tokens)
        val budget = dailyBudgets[providerId] ?: 0
        val ratio = if (budget > 0) total.toFloat() / budget else 1f

        Log.d(TAG, "$providerId usage: +$tokens = $total / $budget (${String.format("%.1f", ratio * 100)}%)")

        // Log tier transitions
        val newTier = getBudgetTier(providerId)
        val prevTier = lastLoggedTier[providerId]
        if (prevTier != null && newTier != prevTier && newTier.ordinal > prevTier.ordinal) {
            Log.w(TAG, "Budget tier escalation: $providerId $prevTier -> $newTier " +
                    "($total / $budget, ${String.format("%.1f", ratio * 100)}%)")
        }
        lastLoggedTier[providerId] = newTier
    }

    fun isWithinBudget(providerId: ProviderId, estimatedTokens: Int = 0): Boolean {
        checkAndResetIfNewDay()
        val current = usage[providerId]?.get() ?: 0
        val budget = dailyBudgets[providerId] ?: 0
        return current + estimatedTokens <= budget
    }

    /**
     * Get the current budget tier for a provider.
     *
     * Tiers enforce the Architecture Principle 1 cascade:
     * - NORMAL: < 90% used -> all call types allowed
     * - CAREFUL: 90-95% -> ENRICHED calls blocked
     * - MINIMAL: 95-100% -> only MINIMAL calls allowed
     * - BLOCKED: 100%+ -> all non-essential calls blocked
     */
    fun getBudgetTier(providerId: ProviderId): BudgetTier {
        checkAndResetIfNewDay()
        val current = usage[providerId]?.get() ?: 0
        val budget = dailyBudgets[providerId] ?: 0
        if (budget == 0) return BudgetTier.BLOCKED

        val ratio = current.toFloat() / budget
        return when {
            ratio >= BLOCKED_THRESHOLD -> BudgetTier.BLOCKED
            ratio >= MINIMAL_THRESHOLD -> BudgetTier.MINIMAL
            ratio >= CAREFUL_THRESHOLD -> BudgetTier.CAREFUL
            else -> BudgetTier.NORMAL
        }
    }

    /**
     * Check if an AI call is allowed under the current budget.
     * Used by MultiAIOrchestrator to gate AI calls before dispatch.
     *
     * @param providerId The AI provider
     * @param isEssential True for TTS and safety-critical calls (always allowed)
     * @param estimatedTokens Estimated token usage for the call
     * @return BudgetCheckResult with allowed/denied status and reason
     */
    fun checkBudget(providerId: ProviderId, isEssential: Boolean = false,
                    estimatedTokens: Int = 0): BudgetCheckResult {
        checkAndResetIfNewDay()
        val providerTier = getBudgetTier(providerId)
        val globalTier = getGlobalBudgetTier()
        // 더 엄격한 쪽 적용
        val tier = if (globalTier.ordinal > providerTier.ordinal) globalTier else providerTier
        val remaining = getRemainingBudget(providerId)
        val globalRemaining = getGlobalRemainingBudget()

        // Essential calls (TTS, safety) are always allowed
        if (isEssential) {
            return BudgetCheckResult(
                allowed = true,
                tier = tier,
                remainingTokens = minOf(remaining, globalRemaining),
                reason = "필수 호출 (TTS/안전)"
            )
        }

        // ★ P1-4: 사용자 승인 오버라이드 활성 시 허용
        if (hasActiveOverride()) {
            val remainingSec = (overrideExpiresAt - System.currentTimeMillis()) / 1000
            return BudgetCheckResult(
                allowed = true,
                tier = tier,
                remainingTokens = minOf(remaining, globalRemaining),
                reason = "사용자 승인 오버라이드 (${remainingSec}초 남음)"
            )
        }

        // 글로벌 예산 초과 시 provider 상태와 무관하게 차단
        val isGlobalBlock = globalTier.ordinal > providerTier.ordinal
        val tierSource = if (isGlobalBlock) "글로벌" else providerId.name

        return when (tier) {
            BudgetTier.BLOCKED -> BudgetCheckResult(
                allowed = false,
                tier = tier,
                remainingTokens = minOf(remaining, globalRemaining),
                reason = "일일 토큰 예산 소진 ($tierSource). 내일 자동 초기화됩니다."
            )
            BudgetTier.MINIMAL -> {
                // Only allow if estimated tokens are small (< 300 = roughly MINIMAL level)
                val smallCall = estimatedTokens <= 300
                BudgetCheckResult(
                    allowed = smallCall,
                    tier = tier,
                    remainingTokens = minOf(remaining, globalRemaining),
                    reason = if (smallCall) "예산 95%+ ($tierSource) — 최소 호출만 허용"
                        else "예산 95%+ ($tierSource) — 대규모 호출 차단 (est: $estimatedTokens tokens)"
                )
            }
            BudgetTier.CAREFUL -> BudgetCheckResult(
                allowed = true,
                tier = tier,
                remainingTokens = minOf(remaining, globalRemaining),
                reason = "예산 90%+ ($tierSource) — ENRICHED 호출 비권장"
            )
            BudgetTier.NORMAL -> BudgetCheckResult(
                allowed = true,
                tier = tier,
                remainingTokens = minOf(remaining, globalRemaining),
                reason = "예산 정상"
            )
        }
    }

    // ★ P1-4: 임시 오버라이드 메커니즘 (사용자 NOD 승인 시 60초간 예산 무시)
    fun grantTemporaryOverride(durationMs: Long = 60_000L) {
        overrideExpiresAt = System.currentTimeMillis() + durationMs
        Log.i(TAG, "Budget override granted for ${durationMs}ms")
    }

    fun hasActiveOverride(): Boolean = System.currentTimeMillis() < overrideExpiresAt

    /**
     * 전체 클라우드 프로바이더의 평균 예산 사용률 (0-100%).
     * PersonaProviderRouter가 3-Tier 라우팅 결정에 사용.
     */
    fun getBudgetUsagePercent(): Float {
        checkAndResetIfNewDay()
        val cloudProviders = listOf(ProviderId.GEMINI, ProviderId.OPENAI, ProviderId.CLAUDE, ProviderId.GROK)
        var totalUsed = 0
        var totalBudget = 0
        for (pid in cloudProviders) {
            totalUsed += usage[pid]?.get() ?: 0
            totalBudget += dailyBudgets[pid] ?: 0
        }
        return if (totalBudget > 0) (totalUsed.toFloat() / totalBudget * 100f) else 0f
    }

    fun getRemainingBudget(providerId: ProviderId): Int {
        checkAndResetIfNewDay()
        val current = usage[providerId]?.get() ?: 0
        val budget = dailyBudgets[providerId] ?: 0
        return (budget - current).coerceAtLeast(0)
    }

    // ★ 글로벌 합산 예산 (전체 클라우드 provider 토큰 합계)
    private val CLOUD_PROVIDERS = listOf(ProviderId.GEMINI, ProviderId.OPENAI, ProviderId.CLAUDE, ProviderId.GROK)

    private val GLOBAL_DAILY_LIMIT: Int get() =
        com.xreal.nativear.policy.PolicyReader.getInt("gateway.global_daily_tokens", 300_000)

    fun getGlobalUsage(): Int {
        checkAndResetIfNewDay()
        return CLOUD_PROVIDERS.sumOf { usage[it]?.get() ?: 0 }
    }

    fun getGlobalRemainingBudget(): Int =
        (GLOBAL_DAILY_LIMIT - getGlobalUsage()).coerceAtLeast(0)

    fun getGlobalBudgetTier(): BudgetTier {
        val globalLimit = GLOBAL_DAILY_LIMIT
        if (globalLimit <= 0) return BudgetTier.BLOCKED
        val ratio = getGlobalUsage().toFloat() / globalLimit
        return when {
            ratio >= BLOCKED_THRESHOLD -> BudgetTier.BLOCKED
            ratio >= MINIMAL_THRESHOLD -> BudgetTier.MINIMAL
            ratio >= CAREFUL_THRESHOLD -> BudgetTier.CAREFUL
            else -> BudgetTier.NORMAL
        }
    }

    /** 글로벌 예산 사용률 (0.0 ~ 1.0+) — AICallGateway가 proactive 차단 판단에 사용 */
    fun getGlobalUsageRatio(): Float {
        val limit = GLOBAL_DAILY_LIMIT
        return if (limit > 0) getGlobalUsage().toFloat() / limit else 1f
    }

    fun getUsageSummary(): Map<ProviderId, Pair<Int, Int>> {
        checkAndResetIfNewDay()
        return ProviderId.entries.associateWith { pid ->
            (usage[pid]?.get() ?: 0) to (dailyBudgets[pid] ?: 0)
        }
    }

    fun getTierSummary(): Map<ProviderId, BudgetTier> {
        return ProviderId.entries.associateWith { getBudgetTier(it) }
    }

    private fun checkAndResetIfNewDay() {
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        if (today != lastResetDay) {
            synchronized(this) {
                if (today != lastResetDay) {
                    // Log daily token usage to DB before reset
                    logDailyUsageBeforeReset()

                    Log.i(TAG, "New day detected. Resetting token budgets.")
                    usage.values.forEach { it.set(0) }
                    lastLoggedTier.clear()
                    lastResetDay = today
                }
            }
        }
    }

    private fun logDailyUsageBeforeReset() {
        val analytics = analyticsService ?: return
        try {
            for ((providerId, usageAtom) in usage) {
                val tokensUsed = usageAtom.get()
                if (tokensUsed > 0) {
                    val budget = dailyBudgets[providerId] ?: 0
                    // ★ getBudgetTier() 직접 호출 금지: checkAndResetIfNewDay() → 무한 재귀 원인
                    val tier = computeTierDirectly(tokensUsed, budget)
                    analytics.logDailyTokenUsage(
                        providerId = providerId.name,
                        tokensUsed = tokensUsed,
                        budget = budget,
                        tier = tier.name
                    )
                }
            }
            Log.i(TAG, "Daily token usage logged to analytics DB")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log daily token usage: ${e.message}")
        }
    }

    /** getBudgetTier()를 호출하지 않는 직접 tier 계산 (무한 재귀 방지용) */
    private fun computeTierDirectly(current: Int, budget: Int): BudgetTier {
        if (budget == 0) return BudgetTier.BLOCKED
        val ratio = current.toFloat() / budget
        return when {
            ratio >= BLOCKED_THRESHOLD -> BudgetTier.BLOCKED
            ratio >= MINIMAL_THRESHOLD -> BudgetTier.MINIMAL
            ratio >= CAREFUL_THRESHOLD -> BudgetTier.CAREFUL
            else -> BudgetTier.NORMAL
        }
    }
}

/**
 * Budget enforcement tiers.
 * Implements Architecture Principle 1: AI Call Gate.
 */
enum class BudgetTier(val displayName: String) {
    NORMAL("정상"),          // < 90% — all call types allowed
    CAREFUL("주의"),         // 90-95% — ENRICHED calls blocked
    MINIMAL("최소"),         // 95-100% — only MINIMAL calls allowed
    BLOCKED("차단")          // 100%+ — all non-essential blocked
}

/**
 * Result of a budget check before an AI call.
 */
data class BudgetCheckResult(
    val allowed: Boolean,
    val tier: BudgetTier,
    val remainingTokens: Int,
    val reason: String
)
