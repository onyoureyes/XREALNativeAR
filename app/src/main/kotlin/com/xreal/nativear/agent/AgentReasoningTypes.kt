package com.xreal.nativear.agent

/**
 * 목표 추구 결과 — 추론 체인 전체를 포함.
 */
data class AgentResult(
    val goalId: String,
    val agentId: String,
    val goal: String,
    val status: GoalStatus,
    val answer: String,
    val confidence: Float,
    val reasoningChain: List<ReasoningStep>,
    val depth: Int,
    val tokensUsed: Int
)

enum class GoalStatus {
    ACHIEVED,   // 목표 달성
    PARTIAL,    // 부분 달성 (최대 깊이/예산 도달)
    FAILED,     // 실행 오류
    REFUSED     // 임무 거부 (정체성 기반 결정)
}

/**
 * 추론 체인의 한 단계.
 */
data class ReasoningStep(
    val depth: Int,
    val type: StepType,
    val content: String,
    val tokensUsed: Int,
    val timestamp: Long = System.currentTimeMillis()
)

enum class StepType {
    THINK,      // 사고 (상태 평가 + 계획)
    ACT,        // 행동 (도구 호출)
    OBSERVE,    // 관찰 (결과 분석)
    REFLECT     // 반성 (전략 재평가, Reflexion)
}

/**
 * WorkingMemory — 현재 추론 체인의 작업 기억.
 * Inner Monologue의 scratchpad 역할.
 */
class WorkingMemory {
    private val steps = mutableListOf<ReasoningStep>()

    fun addStep(step: ReasoningStep) {
        steps.add(step)
    }

    fun getSteps(): List<ReasoningStep> = steps.toList()

    fun getRecentSteps(n: Int): List<ReasoningStep> = steps.takeLast(n)

    fun getLastThought(): String? =
        steps.lastOrNull { it.type == StepType.THINK }?.content

    /** 추론 체인 요약 (Reflexion 에피소드 저장용) */
    fun getSummary(): String {
        if (steps.isEmpty()) return "추론 없음"
        val thinks = steps.filter { it.type == StepType.THINK }
        val acts = steps.filter { it.type == StepType.ACT }
        val reflects = steps.filter { it.type == StepType.REFLECT }
        return buildString {
            appendLine("추론 ${steps.size}단계 (Think:${thinks.size}, Act:${acts.size}, Reflect:${reflects.size})")
            // 핵심 사고만 요약
            thinks.takeLast(3).forEach { t ->
                appendLine("  T${t.depth}: ${t.content.take(100)}")
            }
            reflects.lastOrNull()?.let { r ->
                appendLine("  최종 반성: ${r.content.take(100)}")
            }
        }.trim()
    }
}

/**
 * Reflexion 에피소드 — 과거 목표 추구 경험.
 */
data class AgentEpisode(
    val agentId: String,
    val goal: String,
    val status: String,     // GoalStatus.name
    val reflection: String, // 무엇을 배웠는가
    val depth: Int,
    val tokensUsed: Int,
    val timestamp: Long
)

/**
 * 임무 거부 기록 — 에이전트 정체성의 일부.
 */
data class MissionRefusal(
    val agentId: String,
    val goal: String,
    val reason: String,
    val selfAssessment: String,
    val alternativeSuggestion: String?,
    val timestamp: Long
)
