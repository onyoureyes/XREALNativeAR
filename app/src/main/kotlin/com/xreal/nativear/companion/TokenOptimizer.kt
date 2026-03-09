package com.xreal.nativear.companion

import android.util.Log
import com.xreal.nativear.core.CapabilityTier
import com.xreal.nativear.monitoring.TokenEconomyManager
import com.xreal.nativear.learning.RoutineClassifier
import java.util.Calendar

/**
 * TokenOptimizer: Decides whether an AI call is necessary based on
 * scene change detection and familiarity caching.
 *
 * Decision hierarchy:
 * 0. RoutineClassifier (.tflite) — 온디바이스 분류기 우선 적용 (모델 있을 때만)
 * 1. SKIP: Cache hit + no scene change → save tokens entirely
 * 2. MINIMAL: Minor scene change → analyze only changed objects
 * 3. STANDARD: New scene or unfamiliar objects → full analysis
 * 4. ENRICHED: Routine + novelty injection timing → deep + novel perspective
 */
class TokenOptimizer(
    private val tokenEconomy: TokenEconomyManager,
    private val analysisCacheManager: AnalysisCacheManager,
    private val familiarityEngine: FamiliarityEngine,
    // ★ CapabilityTier 공급자 — FailsafeController 현재 티어를 주입.
    // null이면 기존 동작 유지 (TIER_0_FULL 동일). AppModule에서 lazy 제공.
    private val capabilityTierProvider: (() -> CapabilityTier)? = null
) {
    companion object {
        private const val TAG = "TokenOptimizer"
    }

    // 온디바이스 분류기 — lazy 로딩 (Koin 주입, 실패 시 null)
    private val routineClassifier: RoutineClassifier? by lazy {
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<RoutineClassifier>()
        } catch (e: Exception) {
            Log.w(TAG, "RoutineClassifier 없음 — 기존 로직 사용: ${e.message}")
            null
        }
    }

    private var totalCalls = 0
    private var skippedCalls = 0
    private var minimalCalls = 0
    private var enrichedCalls = 0
    private var classifierCalls = 0  // 온디바이스 분류기로 결정된 횟수

    // Gap B: 연속 SKIP 카운터 — 과도한 SKIP 방지 (최소 N번 중 1번은 분석)
    private var consecutiveSkips = 0
    private val MAX_CONSECUTIVE_SKIPS: Int get() =
        com.xreal.nativear.policy.PolicyReader.getInt("vision.max_consecutive_skips", 10)

    // ─── AI Call Decision ───

    /**
     * AI 호출 여부 결정.
     *
     * @param situation 현재 상황 (LifeSituation.name, 기본값 null)
     * @param patternCount decision_log의 패턴 반복 수 (기본값 0)
     * @param outcomeEma 최근 결과 EMA (0.0~1.0, 기본값 0.5)
     * 추가 파라미터는 기존 호출과의 호환성을 위해 default 값으로 제공.
     */
    fun shouldCallAI(
        currentLabels: Set<String>,
        lat: Double?,
        lon: Double?,
        isRoutine: Boolean,
        isNoveltyTime: Boolean,
        // RoutineClassifier 보조 파라미터 (기존 호출 호환 — default 값)
        situation: String? = null,
        patternCount: Int = 0,
        outcomeEma: Float = 0.5f
    ): AICallDecision {
        totalCalls++

        // ★ CapabilityTier 게이트: 리소스/하드웨어 상태에 따라 AI 호출 수준 제한
        // TIER_4_LOW_POWER 이상 = 절전 모드 → ENRICHED/STANDARD 차단, MINIMAL만 허용
        // TIER_6_MINIMAL = 최소 동작 모드 → 모든 능동적 AI 호출 SKIP
        val currentTier = capabilityTierProvider?.invoke() ?: CapabilityTier.TIER_0_FULL
        if (currentTier == CapabilityTier.TIER_6_MINIMAL) {
            skippedCalls++
            return AICallDecision(
                action = AICallAction.SKIP,
                tokenBudget = 0,
                reason = "최소 동작 모드 (TIER_6) — 능동적 AI 호출 금지",
                cachedResult = null
            )
        }
        if (currentTier.ordinal >= CapabilityTier.TIER_4_LOW_POWER.ordinal) {
            // 절전/엣지 전용 모드: 캐시 히트만 허용, STANDARD/ENRICHED 강제 하향
            val sceneChangeForTier = analysisCacheManager.detectSceneChange(currentLabels)
            val sceneHashForTier = analysisCacheManager.computeSceneHash(currentLabels, lat, lon)
            val cachedForTier = analysisCacheManager.getCachedAnalysis(sceneHashForTier)
            if (sceneChangeForTier == SceneChangeResult.UNCHANGED && cachedForTier != null) {
                skippedCalls++
                return AICallDecision(
                    action = AICallAction.SKIP,
                    tokenBudget = 0,
                    reason = "절전 모드 ($currentTier) + 장면 변화 없음",
                    cachedResult = cachedForTier.analysisResult
                )
            }
            // 장면 변화가 있어도 MINIMAL로 제한
            minimalCalls++
            return AICallDecision(
                action = AICallAction.MINIMAL,
                tokenBudget = 200,
                reason = "절전 모드 ($currentTier) — MINIMAL로 제한",
                cachedResult = cachedForTier?.analysisResult
            )
        }

        // 0. RoutineClassifier 우선 적용 (모델 있을 때만)
        routineClassifier?.let { classifier ->
            if (classifier.isReady()) {
                val hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                val action = classifier.classify(
                    situation = situation,
                    hourOfDay = hourOfDay,
                    userState = null,
                    patternCount = patternCount,
                    outcomeEma = outcomeEma
                )
                if (action != null) {
                    classifierCalls++
                    Log.d(TAG, "온디바이스 분류기 결정: $action (situation=$situation, pattern=$patternCount)")
                    return AICallDecision(
                        action = action,
                        tokenBudget = getTokenBudget(action),
                        reason = "온디바이스 분류기",
                        cachedResult = null
                    )
                }
            }
        }

        // 1. Scene change detection (기존 로직)
        val sceneChange = analysisCacheManager.detectSceneChange(currentLabels)

        // 2. Cache lookup
        val sceneHash = analysisCacheManager.computeSceneHash(currentLabels, lat, lon)
        val cached = analysisCacheManager.getCachedAnalysis(sceneHash)

        return when {
            // SKIP: Unchanged scene + valid cache
            // Gap B: 연속 SKIP 최대값 초과 시 MINIMAL로 강제 업그레이드 (과도한 SKIP 방지)
            sceneChange == SceneChangeResult.UNCHANGED && cached != null -> {
                if (consecutiveSkips >= MAX_CONSECUTIVE_SKIPS) {
                    consecutiveSkips = 0
                    minimalCalls++
                    AICallDecision(
                        action = AICallAction.MINIMAL,
                        tokenBudget = 200,
                        reason = "연속 SKIP $MAX_CONSECUTIVE_SKIPS 회 → 주기적 확인",
                        cachedResult = cached.analysisResult
                    )
                } else {
                    consecutiveSkips++
                    skippedCalls++
                    AICallDecision(
                        action = AICallAction.SKIP,
                        tokenBudget = 0,
                        reason = "장면 변화 없음 + 캐시 유효",
                        cachedResult = cached.analysisResult
                    )
                }
            }

            // MINIMAL: Minor change — only analyze new objects
            sceneChange == SceneChangeResult.MINOR_CHANGE -> {
                consecutiveSkips = 0
                val changedLabels = analysisCacheManager.getChangedLabels(currentLabels)
                minimalCalls++
                AICallDecision(
                    action = AICallAction.MINIMAL,
                    tokenBudget = com.xreal.nativear.policy.PolicyReader.getInt("budget.minimal_tokens", 200),
                    reason = "소폭 변화: ${changedLabels.joinToString(", ")}",
                    cachedResult = cached?.analysisResult
                )
            }

            // ENRICHED: Routine + novelty timing → deep + novel
            isRoutine && isNoveltyTime -> {
                consecutiveSkips = 0
                enrichedCalls++
                AICallDecision(
                    action = AICallAction.ENRICHED,
                    tokenBudget = com.xreal.nativear.policy.PolicyReader.getInt("budget.enriched_tokens", 500),
                    reason = "루틴 중 새로움 주입 타이밍",
                    cachedResult = null
                )
            }

            // STANDARD: New scene or major change
            else -> {
                consecutiveSkips = 0
                AICallDecision(
                    action = AICallAction.STANDARD,
                    tokenBudget = com.xreal.nativear.policy.PolicyReader.getInt("budget.standard_tokens", 400),
                    reason = when (sceneChange) {
                        SceneChangeResult.NEW_SCENE -> "새로운 장면"
                        SceneChangeResult.MAJOR_CHANGE -> "큰 변화 감지"
                        else -> "표준 분석"
                    },
                    cachedResult = null
                )
            }
        }
    }

    private fun getTokenBudget(action: AICallAction): Int = when (action) {
        AICallAction.SKIP     -> 0
        AICallAction.MINIMAL  -> 200
        AICallAction.STANDARD -> 400
        AICallAction.ENRICHED -> 500
    }

    // ─── Statistics ───

    fun getOptimizationStats(): OptimizationStats {
        val skipRate = if (totalCalls > 0) skippedCalls.toFloat() / totalCalls else 0f
        val avgTokenSaved = skippedCalls * 400 + minimalCalls * 200 // rough estimate
        return OptimizationStats(
            totalCalls = totalCalls,
            skippedCalls = skippedCalls,
            minimalCalls = minimalCalls,
            standardCalls = totalCalls - skippedCalls - minimalCalls - enrichedCalls,
            enrichedCalls = enrichedCalls,
            skipRate = skipRate,
            estimatedTokensSaved = avgTokenSaved,
            classifierCalls = classifierCalls
        )
    }

    fun resetStats() {
        totalCalls = 0
        skippedCalls = 0
        minimalCalls = 0
        enrichedCalls = 0
        classifierCalls = 0
    }
}

data class AICallDecision(
    val action: AICallAction,
    val tokenBudget: Int,
    val reason: String,
    val cachedResult: String?
)

enum class AICallAction {
    SKIP,      // No AI call — use cache
    MINIMAL,   // Reduced analysis — only changed objects
    STANDARD,  // Full analysis
    ENRICHED   // Full analysis + novelty injection
}

data class OptimizationStats(
    val totalCalls: Int,
    val skippedCalls: Int,
    val minimalCalls: Int,
    val standardCalls: Int,
    val enrichedCalls: Int,
    val skipRate: Float,
    val estimatedTokensSaved: Int,
    val classifierCalls: Int = 0  // 온디바이스 분류기로 결정된 횟수
)
