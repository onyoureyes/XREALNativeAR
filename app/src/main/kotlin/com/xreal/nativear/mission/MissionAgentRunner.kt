package com.xreal.nativear.mission

import android.util.Log
import com.xreal.nativear.ai.MultiAIOrchestrator
import com.xreal.nativear.ai.Persona
import com.xreal.nativear.ai.IPersonaService
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.*

/**
 * Bridges AgentRole definitions to the existing PersonaManager + MultiAIOrchestrator.
 *
 * Responsibilities:
 * 1. Register temporary Personas for each AgentRole in a mission
 * 2. Execute tasks via dispatchSingle with SharedMissionContext injected
 * 3. Manage proactive agent loops (coroutine-based periodic execution)
 * 4. Clean up personas when mission ends
 */
class MissionAgentRunner(
    private val orchestrator: MultiAIOrchestrator,
    private val personaManager: IPersonaService,
    private val eventBus: GlobalEventBus,
    private val outcomeTracker: com.xreal.nativear.learning.IOutcomeRecorder? = null,
    // ★ 동적 판단 게이트: proactive 루프가 지금 실행 필요한지 AI 판단
    private val edgeContextJudge: com.xreal.nativear.edge.EdgeContextJudge? = null,
    // ★ GoalOrientedAgentLoop — 심층 추론 에이전트 (복잡한 미션 태스크용)
    private val goalAgentLoop: com.xreal.nativear.agent.GoalOrientedAgentLoop? = null
) {
    private val TAG = "MissionAgentRunner"
    private val proactiveJobs = mutableMapOf<String, Job>()
    private val registeredPersonaIds = mutableSetOf<String>()

    /**
     * Register all agent roles as temporary Personas.
     */
    fun registerAgentRoles(roles: List<AgentRole>) {
        for (role in roles) {
            val persona = Persona(
                id = role.personaId,
                name = "Mission: ${role.roleName}",
                role = role.roleName,
                systemPrompt = role.systemPrompt,
                providerId = role.providerId,
                tools = role.tools,
                temperature = role.temperature,
                maxTokens = role.maxTokens,
                isEnabled = true
            )
            personaManager.registerPersona(persona)
            registeredPersonaIds.add(role.personaId)
            Log.d(TAG, "Registered mission persona: ${role.personaId} (${role.providerId})")
        }
    }

    /**
     * Execute a single agent task.
     * Injects SharedMissionContext summary + agent rules into the context parameter.
     */
    suspend fun executeTask(
        task: AgentTask,
        role: AgentRole,
        context: SharedMissionContext
    ): String? {
        val contextSummary = buildAgentContext(role, context)

        return try {
            val response = orchestrator.dispatchSingle(
                query = task.query,
                personaId = role.personaId,
                context = contextSummary
            )

            val result = response.text
            if (!result.isNullOrBlank()) {
                context.addAgentOutput(role.roleName, result)

                // Record intervention for outcome tracking (feedback loop)
                try {
                    val situation = context.getKnowledge("current_situation") ?: "UNKNOWN"
                    val interventionId = outcomeTracker?.recordIntervention(
                        expertId = role.personaId,
                        situation = com.xreal.nativear.context.LifeSituation.valueOf(
                            situation.uppercase().let { if (it == "UNKNOWN") "UNKNOWN" else it }
                        ),
                        action = result.take(100),
                        contextSummary = task.description
                    )
                    if (interventionId != null) {
                        context.putKnowledge("last_intervention_${role.roleName}", interventionId)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "OutcomeTracker recording failed: ${e.message}")
                }
            }

            Log.d(TAG, "Task [${task.id}] by ${role.roleName}: ${result?.take(100) ?: "(empty)"}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Task [${task.id}] by ${role.roleName} failed: ${e.message}", e)
            null
        }
    }

    /**
     * Execute a free-form query against a specific agent role (used by proactive loops).
     */
    suspend fun executeQuery(
        role: AgentRole,
        query: String,
        context: SharedMissionContext
    ): String? {
        val contextSummary = buildAgentContext(role, context)

        return try {
            val response = orchestrator.dispatchSingle(
                query = query,
                personaId = role.personaId,
                context = contextSummary
            )

            val result = response.text
            if (!result.isNullOrBlank()) {
                context.addAgentOutput(role.roleName, result)

                // ★ Fix 1: 결과를 사용자에게 전달 (TTS + HUD)
                deliverResult(role, result)

                // Record proactive intervention for outcome tracking
                try {
                    val situation = context.getKnowledge("current_situation") ?: "UNKNOWN"
                    val interventionId = outcomeTracker?.recordIntervention(
                        expertId = role.personaId,
                        situation = com.xreal.nativear.context.LifeSituation.valueOf(
                            situation.uppercase().let { if (it == "UNKNOWN") "UNKNOWN" else it }
                        ),
                        action = result.take(100),
                        contextSummary = "proactive: ${role.roleName}"
                    )
                    if (interventionId != null) {
                        context.putKnowledge("last_intervention_${role.roleName}", interventionId)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "OutcomeTracker proactive recording failed: ${e.message}")
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Query by ${role.roleName} failed: ${e.message}", e)
            null
        }
    }

    /**
     * ★ GoalOrientedAgentLoop을 사용한 심층 태스크 실행.
     * 일반 dispatchSingle 대신 ReAct+Reflexion 기반 다단계 추론 수행.
     * GoalOrientedAgentLoop이 없으면 일반 executeTask로 폴백.
     */
    suspend fun executeDeepTask(
        task: AgentTask,
        role: AgentRole,
        context: SharedMissionContext
    ): String? {
        val loop = goalAgentLoop ?: return executeTask(task, role, context)

        val contextSummary = buildAgentContext(role, context)
        return try {
            val result = loop.pursue(
                goal = task.query,
                agentId = role.personaId,
                context = contextSummary
            )
            val answer = result.answer
            if (answer.isNotBlank()) {
                context.addAgentOutput(role.roleName, answer)
                deliverResult(role, answer)
            }
            Log.d(TAG, "Deep task [${task.id}] by ${role.roleName}: ${result.status} (depth=${result.depth}, tokens=${result.tokensUsed})")
            answer
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Deep task failed, fallback to normal: ${e.message}")
            executeTask(task, role, context)
        }
    }

    /**
     * Start proactive execution loops for agents with isProactive=true.
     *
     * ★ EdgeContextJudge 통합:
     * - 각 루프 실행 전 270M에게 "지금 필요한가?" 질문
     * - SKIP 판단 시: backoff 적용 (최대 4× 기본 인터벌)
     * - ACT 판단 시: 즉시 실행, backoff 리셋
     * - judge 없으면: 기존 고정 타이머 동작 유지
     */
    fun startProactiveAgents(
        roles: List<AgentRole>,
        context: SharedMissionContext,
        scope: CoroutineScope
    ) {
        for (role in roles.filter { it.isProactive }) {
            if (proactiveJobs.containsKey(role.roleName)) continue

            val job = scope.launch {
                // ★ 최소 인터벌 하한선 적용 (PolicyReader 기반, 모든 템플릿에 일괄 적용)
                val minIntervalMs = com.xreal.nativear.policy.PolicyReader.getLong(
                    "gateway.min_cloud_interval_ms", 60_000L
                )
                val effectiveIntervalMs = maxOf(role.proactiveIntervalMs, minIntervalMs)
                Log.i(TAG, "Proactive loop started: ${role.roleName} (effective: ${effectiveIntervalMs}ms, requested: ${role.proactiveIntervalMs}ms, judge: ${if (edgeContextJudge != null) "ON" else "OFF"})")
                // 초기 딜레이: 미션 초기 작업이 먼저 완료되도록
                delay(effectiveIntervalMs / 2)

                var backoffMultiplier = 1.0f
                val maxBackoff = 4.0f

                while (isActive) {
                    try {
                        // ★ EdgeContextJudge: 지금 proactive 루프 실행이 필요한가?
                        val shouldRun = if (edgeContextJudge != null) {
                            val situation = context.getKnowledge("current_situation") ?: "UNKNOWN"
                            val recentOutput = context.getAgentOutputs(role.roleName, limit = 1).firstOrNull()
                            val lastOutputAgeMs = if (recentOutput != null)
                                System.currentTimeMillis() - recentOutput.timestamp
                            else Long.MAX_VALUE

                            val decision = edgeContextJudge.shouldRunProactiveAgent(
                                roleName = role.roleName,
                                situation = situation,
                                recentOutputHash = recentOutput?.content?.hashCode() ?: 0,
                                lastOutputAgeMs = lastOutputAgeMs
                            )
                            if (decision.isSkip) {
                                // 점진적 backoff: 1.5× 씩 최대 4× 까지
                                backoffMultiplier = (backoffMultiplier * 1.5f).coerceAtMost(maxBackoff)
                                Log.d(TAG, "Proactive SKIP [${role.roleName}] (${backoffMultiplier}×): ${decision.reason}")
                                false
                            } else {
                                backoffMultiplier = 1.0f  // ACT → backoff 리셋
                                true
                            }
                        } else {
                            true  // judge 없으면 항상 실행 (기존 동작 유지)
                        }

                        if (shouldRun) {
                            val query = buildProactiveQuery(role, context)
                            executeQuery(role, query, context)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Proactive ${role.roleName} error: ${e.message}")
                    }

                    // backoff 적용된 다음 인터벌 (최소 인터벌 하한선 적용)
                    val nextDelayMs = (effectiveIntervalMs * backoffMultiplier).toLong()
                    delay(nextDelayMs)
                }
            }
            proactiveJobs[role.roleName] = job
        }
    }

    /**
     * Stop all proactive loops.
     */
    fun stopProactiveAgents() {
        proactiveJobs.values.forEach { it.cancel() }
        proactiveJobs.clear()
        Log.i(TAG, "All proactive agents stopped")
    }

    /**
     * Unregister all mission personas (cleanup on mission end).
     */
    fun cleanup() {
        stopProactiveAgents()
        // Note: PersonaManager doesn't have unregister — personas are overwritten
        // if same ID is reused, or remain dormant. This is fine for mission lifecycle.
        registeredPersonaIds.clear()
        Log.d(TAG, "MissionAgentRunner cleaned up")
    }

    // ── Private helpers ──

    private fun buildAgentContext(role: AgentRole, context: SharedMissionContext): String = buildString {
        // Shared context from other agents
        val summary = context.buildContextSummary()
        if (summary.isNotBlank()) {
            appendLine(summary)
        }

        // Agent-specific rules
        if (role.rules.isNotEmpty()) {
            appendLine("[행동 규칙]")
            role.rules.forEach { appendLine("- $it") }
        }
    }

    /**
     * ★ Fix 1: proactive 결과를 사용자에게 전달.
     * - 안전 경고 등 긴급: TTS (important=true)
     * - 일반 정보: HUD ShowMessage
     * - 빈 응답 / "이상 없음" 류: 전달하지 않음
     */
    private fun deliverResult(role: AgentRole, result: String) {
        // 빈 내용이거나 "이상 없음" 류 응답은 전달하지 않음
        val trimmed = result.trim()
        if (trimmed.length < 5) return
        val skipPatterns = listOf("이상 없음", "변동 없음", "특이사항 없음", "안전합니다", "정상입니다")
        if (skipPatterns.any { trimmed.contains(it) }) return

        // 안전 관련 에이전트의 경고는 TTS로 즉시 전달
        val isSafetyAlert = role.roleName.contains("safety") ||
            trimmed.contains("조심") || trimmed.contains("위험") || trimmed.contains("경고")
        if (isSafetyAlert) {
            eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(
                text = trimmed.take(100),
                important = true
            ))
        }

        // HUD에 메시지 표시 (모든 유의미한 결과)
        eventBus.publish(XRealEvent.ActionRequest.ShowMessage(
            "[${role.roleName}] ${trimmed.take(150)}"
        ))
    }

    /**
     * ★ Fix 2: proactive 쿼리에 미션 목표 + 사용 가능 도구 + 행동 지침 주입.
     * 기존: "상황 점검하세요" 하드코딩 → AI가 도구를 안 씀
     * 수정: 구체적 목표 + 도구 목록 + 행동 지시 포함
     */
    private fun buildProactiveQuery(role: AgentRole, context: SharedMissionContext): String = buildString {
        appendLine("당신은 미션 에이전트 '${role.roleName}'입니다.")
        appendLine("역할: ${role.systemPrompt.take(200)}")
        appendLine()

        // 사용 가능 도구 목록
        if (role.tools.isNotEmpty()) {
            appendLine("[사용 가능 도구] ${role.tools.joinToString(", ")}")
        }

        // 현재 상황
        val location = context.currentLocation
        if (location.isNotBlank()) {
            appendLine("[현재 위치] $location")
        }
        val state = context.userStateSnapshot
        if (state.isNotBlank()) {
            appendLine("[사용자 상태] $state")
        }

        // 이전 보고 (반복 방지)
        val recentOwn = context.getAgentOutputs(role.roleName, limit = 2)
        if (recentOwn.isNotEmpty()) {
            appendLine("[이전 보고] ${recentOwn.first().content.take(150)}")
        }

        // 다른 에이전트의 최근 발견 (협업)
        val othersOutputs = context.getAllRecentOutputs(5)
            .filter { it.agentRoleName != role.roleName }
        if (othersOutputs.isNotEmpty()) {
            appendLine("[동료 에이전트 보고]")
            othersOutputs.take(3).forEach {
                appendLine("- [${it.agentRoleName}] ${it.content.take(100)}")
            }
        }

        appendLine()
        appendLine("지시: 위 상황을 바탕으로 도구를 적극 활용하여 사용자에게 유용한 정보를 수집/분석하세요.")
        appendLine("변동이 없으면 '이상 없음'이라고만 답하세요.")
        appendLine("중요한 발견이 있으면 구체적으로 보고하세요.")
    }
}
