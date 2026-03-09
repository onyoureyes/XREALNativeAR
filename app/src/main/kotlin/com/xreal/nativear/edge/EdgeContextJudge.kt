package com.xreal.nativear.edge

import android.util.Log
import com.xreal.nativear.ai.AIMessage
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * EdgeContextJudge — ROUTER_270M 기반 meta-결정 게이트.
 *
 * ## 역할
 * 하드코딩된 상황별 규칙 대신, 온디바이스 270M 모델이 동적으로 판단:
 * - ExpertTeamManager: 지금 AI 전문가 팀을 활성화해야 하는가?
 * - MissionAgentRunner: proactive 루프를 지금 실행해야 하는가?
 *
 * ## 작동 방식
 * - ROUTER_270M (항상 로드됨, EdgeDelegationRouter와 공유)에 ACT/SKIP 질문 전달
 * - 결과를 90초 캐시하여 중복 추론 방지 (270M 불필요 부하 제거)
 * - 모델 미준비 또는 타임아웃 → 기본값 ACT (안전 방향, 기존 동작 유지)
 *
 * ## Koin: single {} 싱글톤
 */
class EdgeContextJudge(
    private val routerProvider: EdgeLLMProvider   // ROUTER_270M — 항상 로드됨
) {
    companion object {
        private const val TAG = "EdgeContextJudge"
        private const val CACHE_TTL_MS = 90_000L          // 90초 캐시 유효
        private const val INFERENCE_TIMEOUT_MS = 7_000L   // 재시도 포함 여유: 1s대기+400ms생성+~4s추론
        private const val MAX_CACHE_SIZE = 20
        const val ACT = "ACT"
        const val SKIP = "SKIP"
    }

    data class JudgeDecision(
        val action: String,   // ACT or SKIP
        val reason: String,
        val cachedAt: Long = System.currentTimeMillis()
    ) {
        val isSkip: Boolean get() = action == SKIP
        val isAct: Boolean get() = action == ACT
        val isExpired: Boolean get() = System.currentTimeMillis() - cachedAt > CACHE_TTL_MS
    }

    private val cache = ConcurrentHashMap<String, JudgeDecision>()

    // ─────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────

    /**
     * 전문가 팀 활성화 여부 판단.
     * ExpertTeamManager의 하드코딩된 shouldSuppressExpertActivation() 대체.
     * @return true = 활성화해야 함, false = 억제
     */
    suspend fun shouldActivateExperts(
        situation: String,
        hourOfDay: Int,
        isMoving: Boolean,
        hasSpeech: Boolean,
        hasVisiblePeople: Boolean
    ): Boolean {
        val ctx = "상황:$situation, 시간:${hourOfDay}시, 이동:$isMoving, 음성:$hasSpeech, 주변인:$hasVisiblePeople"
        val question = "AR 안경 AI 전문가 팀을 지금 활성화해야 하는가? 수면중·새벽 무활동·아무 변화 없음이면 SKIP."
        val cacheKey = "experts_${situation}_${hourOfDay}_${isMoving}_${hasSpeech}_${hasVisiblePeople}"
        val decision = judge(ctx, question, cacheKey)
        return decision.action == ACT
    }

    /**
     * Proactive 에이전트 루프 실행 여부 판단.
     * MissionAgentRunner proactive loop 앞에 호출.
     */
    suspend fun shouldRunProactiveAgent(
        roleName: String,
        situation: String,
        recentOutputHash: Int,     // 최근 출력 해시 (변화 감지용)
        lastOutputAgeMs: Long
    ): JudgeDecision {
        val ctx = "에이전트:$roleName, 상황:$situation, 마지막출력:${lastOutputAgeMs / 1000}초전, 내용해시:$recentOutputHash"
        val question = "이 에이전트의 주기적 작업을 지금 실행해야 하는가? 상황·출력이 동일하면 SKIP."
        val cacheKey = "proactive_${roleName}_${situation}_${recentOutputHash}"
        return judge(ctx, question, cacheKey)
    }

    /**
     * 캐시 강제 초기화 (상황 급변 시 ExpertTeamManager에서 호출).
     */
    fun invalidateCache() {
        cache.clear()
        Log.d(TAG, "캐시 초기화됨")
    }

    fun getCacheStats(): String {
        val expired = cache.values.count { it.isExpired }
        return "EdgeContextJudge 캐시: ${cache.size}개 (만료: $expired)"
    }

    // ─────────────────────────────────────────────────────────────────
    // Core inference
    // ─────────────────────────────────────────────────────────────────

    private suspend fun judge(
        context: String,
        question: String,
        cacheKey: String
    ): JudgeDecision {
        // 캐시 확인
        val cached = cache[cacheKey]
        if (cached != null && !cached.isExpired) {
            Log.v(TAG, "캐시 히트: ${cacheKey.take(40)} → ${cached.action}")
            return cached
        }

        // ROUTER_270M 준비 확인
        if (!routerProvider.isAvailable) {
            Log.d(TAG, "ROUTER_270M 미준비 → 기본값 ACT (안전)")
            return JudgeDecision(ACT, "모델 미준비 — 기존 동작 유지")
        }

        // 270M 추론
        val decision = runInference(context, question)
            ?: JudgeDecision(ACT, "추론 실패 → 안전 기본값 ACT")

        // 캐시 저장 (초과 시 만료 항목 정리)
        if (cache.size >= MAX_CACHE_SIZE) {
            cache.entries.removeIf { it.value.isExpired }
        }
        cache[cacheKey] = decision

        Log.d(TAG, "[${cacheKey.take(35)}] → ${decision.action}: ${decision.reason}")
        return decision
    }

    private suspend fun runInference(context: String, question: String): JudgeDecision? {
        return try {
            // 270M 모델은 짧은 영어 지시를 가장 잘 따름
            // Few-shot 방식: 예시 → 즉각 분류 패턴 학습
            val systemPrompt = "Reply ACT or SKIP only. ACT=do it now, SKIP=skip.\nExamples: ACT new event / SKIP same as before"

            // 사용자 메시지도 영어로 + 최소화
            val situation = context
                .replace("상황:", "situation:")
                .replace("시간:", "hour:")
                .replace("시", "h")
                .replace("이동:", "moving:")
                .replace("음성:", "speech:")
                .replace("주변인:", "people:")
                .replace("에이전트:", "agent:")
                .replace("마지막출력:", "lastOutput:")
                .replace("초전", "s ago")
                .replace("내용해시:", "hash:")
            val q = if (question.contains("활성화")) "activate experts?" else "run agent loop?"

            val response = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                routerProvider.sendMessage(
                    messages = listOf(AIMessage("user", "$situation\n$q")),
                    systemPrompt = systemPrompt,
                    tools = emptyList(),
                    temperature = 0.1f,
                    maxTokens = 20  // ROUTER_270M은 짧은 분류 응답만 필요
                )
            }

            if (response == null) {
                Log.w(TAG, "추론 타임아웃 (${INFERENCE_TIMEOUT_MS}ms)")
                return null
            }

            val text = response.text?.trim() ?: return null
            val action = when {
                text.uppercase().startsWith("ACT") -> ACT
                text.uppercase().startsWith("SKIP") -> SKIP
                else -> ACT  // 불명확 → 안전하게 ACT
            }
            val reason = text.drop(4).trim().take(60)

            JudgeDecision(action = action, reason = reason.ifBlank { "270M 판단" })
        } catch (e: Exception) {
            Log.e(TAG, "추론 오류: ${e.message}")
            null
        }
    }
}
