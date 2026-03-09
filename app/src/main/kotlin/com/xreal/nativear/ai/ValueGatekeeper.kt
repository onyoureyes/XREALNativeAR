package com.xreal.nativear.ai

import android.util.Log
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.policy.PolicyReader
import com.xreal.nativear.router.persona.TokenBudgetTracker
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ValueGatekeeper — 통계 기반 AI 호출 가치 판단기.
 *
 * ## 핵심 철학
 * - 예산 남아도 무의미한 호출은 차단 (사용자에게 안 보이는 결과만 만드는 루프)
 * - 예산 초과돼도 가치 높으면 사용자 승인 후 허용
 * - AI가 아닌 **측정**으로 판단: TTS/HUD 출력 여부 + 사용자 반응(NOD/SHAKE) 이력
 *
 * ## 측정 지표
 * 1. intent별 가시율: 최근 N회 중 사용자에게 보인 비율
 * 2. intent별 유용율: 사용자가 NOD(긍정) 반응한 비율
 * 3. 중복 빈도: 같은 intent가 짧은 시간 내 반복되는가
 *
 * ## 판단 체계 (3단계)
 * 1단계: 통계 규칙 (순수 측정 — 비용 0)
 *   - 가시율 < 10% + 5회 이상 이력 → SKIP
 *   - 중복 호출 2회/2분 이내 → SKIP
 *   - 예산 초과 + INTERNAL_ONLY → SKIP
 * 2단계: AI 보강 판단 (경계 사례 — 리모트 $0 우선, 필요 시 서버 API)
 *   - 통계로 결론 못 내는 경우 리모트 LLM에 1줄 판단 요청
 *   - 리모트 불가 시 서버 API 사용 (비용 발생하지만 잘못된 호출 방지 가치 > 판단 비용)
 *   - 엣지 270M은 네트워크 불가 시에만 최후 수단
 * 3단계: 사용자 승인 (예산 초과 + 가치 있음)
 *   - 예산 초과 + USER_FACING → ASK_USER (NOD/SHAKE)
 *   - 그 외 → ALLOW
 */
class ValueGatekeeper(
    private val tokenBudgetTracker: TokenBudgetTracker?,
    private val eventBus: GlobalEventBus
) {
    companion object {
        private const val TAG = "ValueGatekeeper"
        private const val MAX_HISTORY_PER_INTENT = 20
    }

    // ── 판단 결과 ──

    enum class Verdict {
        ALLOW,
        SKIP,
        ASK_USER
    }

    data class ValueDecision(
        val verdict: Verdict,
        val reason: String,
        val visibilityRate: Float = -1f  // -1 = 이력 없음
    )

    // ── intent별 이력 ──

    data class CallRecord(
        val timestamp: Long,
        val wasUserFacing: Boolean,  // TTS/HUD로 출력됨
        val userReaction: Reaction = Reaction.UNKNOWN
    )

    enum class Reaction { POSITIVE, NEGATIVE, IGNORED, UNKNOWN }

    private val intentHistory = ConcurrentHashMap<String, ConcurrentLinkedDeque<CallRecord>>()
    private val totalSkips = AtomicInteger(0)
    private val totalAllows = AtomicInteger(0)
    private val lastAIJudgeMs = AtomicLong(0L)

    // ★ AI 보강 판단용 — 순환 의존성 방지를 위해 setter 주입
    // AppBootstrapper에서 초기화 후 주입
    @Volatile
    private var aiRegistry: IAICallService? = null

    /**
     * AI 보강 판단을 위한 AIResourceRegistry 설정.
     * DI 순환 의존성(ValueGatekeeper→AICallGateway→AIResourceRegistry→ValueGatekeeper) 방지를 위해
     * 생성자가 아닌 setter로 주입. AppBootstrapper.initLevel4()에서 호출.
     */
    fun setAIRegistry(registry: IAICallService) {
        aiRegistry = registry
        Log.d(TAG, "AI 보강 판단 활성화 (AIResourceRegistry 연결됨)")
    }

    // ── PRE-CALL: 가치 판단 ──

    fun checkValue(
        intent: String,
        priority: AICallGateway.CallPriority,
        visibility: AICallGateway.VisibilityIntent,
        estimatedTokens: Int
    ): ValueDecision {
        if (!PolicyReader.getBoolean("gateway.value_gatekeeper_enabled", true)) {
            return ValueDecision(Verdict.ALLOW, "게이트키퍼 비활성화")
        }

        // 필수 호출 → 항상 허용
        if (priority == AICallGateway.CallPriority.USER_COMMAND ||
            priority == AICallGateway.CallPriority.SAFETY) {
            return ValueDecision(Verdict.ALLOW, "필수 호출")
        }

        val now = System.currentTimeMillis()
        val history = intentHistory.getOrPut(intent) { ConcurrentLinkedDeque() }

        // ── 1. 중복 감지 ──
        val duplicateWindowMs = PolicyReader.getLong("gateway.duplicate_window_ms", 120_000L)
        val recentSame = history.count { (now - it.timestamp) < duplicateWindowMs }
        if (recentSame >= 2) {
            totalSkips.incrementAndGet()
            return ValueDecision(
                Verdict.SKIP,
                "중복 (${recentSame}회/${duplicateWindowMs / 1000}초 내)"
            )
        }

        // ── 2. 가시율 기반 차단 (내부에서만 소비되는 루프 감지) ──
        val minHistoryForJudge = PolicyReader.getInt("gateway.min_history_for_judge", 5)
        val minVisibilityRate = PolicyReader.getFloat("gateway.min_visibility_rate", 0.10f)

        if (history.size >= minHistoryForJudge) {
            val visibilityRate = history.count { it.wasUserFacing }.toFloat() / history.size

            // PROACTIVE + INTERNAL + 가시율 10% 미만 → 무의미한 루프
            if (priority == AICallGateway.CallPriority.PROACTIVE &&
                visibility == AICallGateway.VisibilityIntent.INTERNAL_ONLY &&
                visibilityRate < minVisibilityRate) {
                totalSkips.incrementAndGet()
                return ValueDecision(
                    Verdict.SKIP,
                    "가시율 ${formatPercent(visibilityRate)} < ${formatPercent(minVisibilityRate)} (${history.size}회 이력)",
                    visibilityRate = visibilityRate
                )
            }

            // 유용율 체크: 사용자 반응 이력이 충분하면
            val withReaction = history.filter { it.userReaction != Reaction.UNKNOWN }
            if (withReaction.size >= minHistoryForJudge) {
                val usefulRate = withReaction.count {
                    it.userReaction == Reaction.POSITIVE
                }.toFloat() / withReaction.size

                val minUsefulRate = PolicyReader.getFloat("gateway.min_useful_rate", 0.05f)
                if (usefulRate < minUsefulRate && priority == AICallGateway.CallPriority.PROACTIVE) {
                    totalSkips.incrementAndGet()
                    return ValueDecision(
                        Verdict.SKIP,
                        "유용율 ${formatPercent(usefulRate)} < ${formatPercent(minUsefulRate)} (${withReaction.size}회)",
                        visibilityRate = visibilityRate
                    )
                }
            }
        }

        // ── 3. AI 보강 판단 (경계 사례) ──
        // 통계 이력 부족(3~4건) + PROACTIVE + INTERNAL → 리모트 AI에 1줄 판단 요청
        val aiJudgeEnabled = PolicyReader.getBoolean("gateway.ai_judge_enabled", true)
        if (aiJudgeEnabled && aiRegistry != null &&
            history.size in 1..(minHistoryForJudge - 1) &&
            priority == AICallGateway.CallPriority.PROACTIVE &&
            visibility == AICallGateway.VisibilityIntent.INTERNAL_ONLY
        ) {
            val aiVerdict = tryAIJudge(intent, history, estimatedTokens)
            if (aiVerdict != null) return aiVerdict
        }

        // ── 4. 예산 초과 분기 ──
        val usageRatio = tokenBudgetTracker?.getGlobalUsageRatio() ?: 0f
        val isOverBudget = usageRatio >= 1.0f

        if (isOverBudget) {
            // 사용자 대면 호출 → 물어보기
            if (visibility == AICallGateway.VisibilityIntent.USER_FACING) {
                return ValueDecision(
                    Verdict.ASK_USER,
                    "예산 초과 (${formatPercent(usageRatio)}) — 사용자 승인 필요"
                )
            }
            // 내부 호출 → 차단
            totalSkips.incrementAndGet()
            return ValueDecision(
                Verdict.SKIP,
                "예산 초과 + 내부 호출 → 차단"
            )
        }

        // ── 통과 ──
        totalAllows.incrementAndGet()
        return ValueDecision(Verdict.ALLOW, "통과")
    }

    // ── POST-CALL: 결과 기록 ──

    /**
     * AI 호출 완료 후 호출. 가시성/유용성 이력 축적.
     *
     * @param intent 호출 목적 (checkValue에 전달한 것과 동일)
     * @param wasUserFacing 결과가 TTS/HUD로 사용자에게 전달되었는가
     */
    fun recordOutcome(intent: String, wasUserFacing: Boolean) {
        val history = intentHistory.getOrPut(intent) { ConcurrentLinkedDeque() }
        history.addLast(CallRecord(
            timestamp = System.currentTimeMillis(),
            wasUserFacing = wasUserFacing
        ))
        while (history.size > MAX_HISTORY_PER_INTENT) history.pollFirst()
    }

    /**
     * 사용자 반응 기록 (NOD=POSITIVE, SHAKE=NEGATIVE, 무반응=IGNORED).
     * OutcomeTracker에서 호출.
     */
    fun recordUserReaction(intent: String, reaction: Reaction) {
        val history = intentHistory[intent] ?: return
        // 가장 최근 레코드에 반응 기록
        val last = history.peekLast() ?: return
        // ConcurrentLinkedDeque는 in-place 수정이 안전하지 않으므로 교체
        history.pollLast()
        history.addLast(last.copy(userReaction = reaction))
    }

    // ── 사용자 승인 흐름 ──

    /**
     * ASK_USER 판정 시 TTS/HUD로 사용자에게 확인 요청.
     * 결과는 AIAgentManager.handleBudgetOverrideGesture()에서 처리:
     * - NOD → TokenBudgetTracker.grantTemporaryOverride(60초)
     * - SHAKE → 취소
     */
    suspend fun requestUserApproval(intent: String, estimatedTokens: Int) {
        eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(
            "예산 초과입니다. 이 요청을 진행할까요?",
            important = true
        ))
        eventBus.publish(XRealEvent.ActionRequest.ShowMessage(
            "⚠️ 예산 초과 — NOD: 진행 / SHAKE: 취소 ($intent)"
        ))
    }

    // ── intent별 통계 조회 (StrategistReflector 컨텍스트용) ──

    /**
     * 전체 intent별 가시율/유용율 요약.
     * StrategistReflector가 Gemini에게 전달 → 정책 조정 지시사항 생성 근거.
     */
    fun getIntentStats(): String = buildString {
        if (intentHistory.isEmpty()) return@buildString

        appendLine("[AI 호출 가치 통계]")
        for ((intent, history) in intentHistory) {
            if (history.size < 3) continue  // 이력 부족

            val visRate = history.count { it.wasUserFacing }.toFloat() / history.size
            val withReaction = history.filter { it.userReaction != Reaction.UNKNOWN }
            val usefulRate = if (withReaction.isNotEmpty()) {
                withReaction.count { it.userReaction == Reaction.POSITIVE }.toFloat() / withReaction.size
            } else -1f

            val usefulStr = if (usefulRate >= 0) "${formatPercent(usefulRate)}" else "미측정"
            appendLine("  $intent: 가시 ${formatPercent(visRate)}, 유용 $usefulStr (${history.size}회)")
        }
        appendLine("  총계: allow=${totalAllows.get()} skip=${totalSkips.get()}")
    }

    // ── AI 보강 판단 ──

    /**
     * 리모트($0) → 서버($) → 엣지(offline) cascade로 1줄 판단 요청.
     * 판단 자체는 게이트웨이를 거치지 않음 (무한재귀 방지).
     * 최소 30초 간격으로만 호출 (판단 비용 자체 제한).
     */
    private fun tryAIJudge(
        intent: String,
        history: ConcurrentLinkedDeque<CallRecord>,
        estimatedTokens: Int
    ): ValueDecision? {
        val registry = aiRegistry ?: return null

        // 최소 간격: 30초에 한번만 AI 판단
        val judgeIntervalMs = PolicyReader.getLong("gateway.ai_judge_interval_ms", 30_000L)
        val now = System.currentTimeMillis()
        val lastJudge = lastAIJudgeMs.get()
        if (now - lastJudge < judgeIntervalMs) return null
        if (!lastAIJudgeMs.compareAndSet(lastJudge, now)) return null

        return try {
            // 가시율/유용율 요약
            val visRate = if (history.isNotEmpty()) {
                history.count { it.wasUserFacing }.toFloat() / history.size
            } else -1f
            val withReaction = history.filter { it.userReaction != Reaction.UNKNOWN }
            val usefulRate = if (withReaction.isNotEmpty()) {
                withReaction.count { it.userReaction == Reaction.POSITIVE }.toFloat() / withReaction.size
            } else -1f
            val budgetUsage = tokenBudgetTracker?.getGlobalUsageRatio() ?: 0f

            val prompt = "AI호출 가치 판단. intent=$intent, 이력=${history.size}건, " +
                    "가시율=${if (visRate >= 0) formatPercent(visRate) else "미측정"}, " +
                    "유용율=${if (usefulRate >= 0) formatPercent(usefulRate) else "미측정"}, " +
                    "예산사용=${formatPercent(budgetUsage)}, 예상토큰=$estimatedTokens. " +
                    "ACT 또는 SKIP 한 단어만 답변."

            // ★ 게이트웨이 우회: 직접 프로바이더 호출 (무한재귀 방지)
            // routeText/quickText를 쓰면 → AICallGateway.checkGate() → ValueGatekeeper.checkValue() 재귀
            val response = kotlinx.coroutines.runBlocking {
                withTimeoutOrNull(5000L) {
                    registry.quickText(
                        messages = listOf(AIMessage("user", prompt)),
                        systemPrompt = "ACT 또는 SKIP 한 단어만 답변. 사용자에게 보이지 않는 내부 호출이 반복되고 가치가 낮으면 SKIP.",
                        maxTokens = 10,
                        isEssential = false,
                        // ★ USER_COMMAND로 설정 → AICallGateway에서 필수 호출로 판단 → ValueGatekeeper 재진입 방지
                        callPriority = AICallGateway.CallPriority.USER_COMMAND,
                        visibility = AICallGateway.VisibilityIntent.INTERNAL_ONLY,
                        intent = "value_judge"
                    )
                }
            }

            val answer = response?.text?.trim()?.uppercase() ?: return null

            if (answer.contains("SKIP")) {
                totalSkips.incrementAndGet()
                Log.d(TAG, "AI 판단 SKIP [$intent]: $answer")
                ValueDecision(Verdict.SKIP, "AI 보강 판단: SKIP ($answer)")
            } else {
                Log.d(TAG, "AI 판단 ACT [$intent]: $answer")
                null  // ACT → 통계 판단 통과, 이후 예산 체크로 진행
            }
        } catch (e: Exception) {
            Log.w(TAG, "AI 판단 실패 (통계로 폴백): ${e.message}")
            null  // 실패 시 통계 판단만으로 진행
        }
    }

    // ── 유틸 ──

    private fun formatPercent(ratio: Float): String =
        String.format("%.0f%%", ratio * 100)

    fun getStatusSummary(): String {
        val total = totalAllows.get() + totalSkips.get()
        val skipRate = if (total > 0) totalSkips.get() * 100 / total else 0
        return "Value: allow=${totalAllows.get()} skip=${totalSkips.get()} (차단 ${skipRate}%)"
    }
}
