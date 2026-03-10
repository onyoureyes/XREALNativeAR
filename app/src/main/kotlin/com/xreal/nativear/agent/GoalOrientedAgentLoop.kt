package com.xreal.nativear.agent

import android.util.Log
import com.xreal.nativear.ai.AICallGateway
import com.xreal.nativear.ai.AIMessage
import com.xreal.nativear.ai.AIResponse
import com.xreal.nativear.ai.AIToolCall
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.tools.ToolExecutorRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * GoalOrientedAgentLoop — ReAct + Reflexion + Inner Monologue 기반 목적 지향 에이전트 루프.
 *
 * ## 최신 기법 적용
 * - **ReAct** (Yao 2023): Think → Act → Observe 인터리브 추론
 * - **Reflexion** (Shinn 2023): 실패 에피소드 메모리 → 다음 시도에 반영
 * - **Inner Monologue**: AI의 내부 추론을 scratchpad에 명시적 기록
 * - **Adaptive Depth**: 복잡도 기반 동적 깊이 (고정 상수 없음)
 * - **Budget-Aware**: 토큰 소모 추적 → 남은 예산에 따라 추론 전략 조정
 *
 * ## 목적 지향 차이점 (기존 5-hop 도구 루프 vs 이것)
 * - 기존: 도구 호출 → 결과 → AI → ... (목표 평가 없음, 5hop 천장)
 * - 이것: Think(목표 평가) → Act(도구/하위목표) → Observe(결과 분석) → Reflect(전략 조정) → 반복
 *
 * ## 임무 거부 (Mission Refusal)
 * 자기 삭제 대신 — 에이전트가 목표 달성이 불가능하다고 판단하면
 * 거부 사유를 DB에 기록하고 정체성의 일부로 축적.
 */
class GoalOrientedAgentLoop(
    private val aiRegistry: com.xreal.nativear.ai.IAICallService,
    private val toolRegistry: ToolExecutorRegistry,
    private val agentIdentity: AgentIdentityStore,
    private val eventBus: GlobalEventBus,
    private val personaManager: com.xreal.nativear.ai.IPersonaService? = null
) {
    companion object {
        private const val TAG = "AgentLoop"

        // Adaptive depth: 기본값은 PolicyRegistry에서 읽음 (고정 상수 없음)
        private val MAX_DEPTH: Int get() =
            com.xreal.nativear.policy.PolicyReader.getInt("agent.max_reasoning_depth", 12)
        private val MAX_TOKENS_PER_GOAL: Int get() =
            com.xreal.nativear.policy.PolicyReader.getInt("agent.max_tokens_per_goal", 4000)
        private val CONFIDENCE_THRESHOLD: Float get() =
            com.xreal.nativear.policy.PolicyReader.getFloat("agent.goal_confidence_threshold", 0.75f)
    }

    /**
     * 목표를 추구한다. 달성될 때까지 재귀적 Think→Act→Observe→Reflect 수행.
     *
     * @param goal 달성할 목표 (자연어)
     * @param agentId 수행하는 에이전트 ID
     * @param context 현재 상황 컨텍스트
     * @param parentGoalId 상위 목표 ID (하위 목표 분해 시)
     * @return AgentResult (성공/실패/거부 + 추론 체인)
     */
    suspend fun pursue(
        goal: String,
        agentId: String,
        context: String = "",
        parentGoalId: String? = null
    ): AgentResult = withContext(Dispatchers.IO) {
        val goalId = java.util.UUID.randomUUID().toString().take(10)
        val scratchpad = WorkingMemory()
        var tokensUsed = 0
        var depth = 0

        Log.i(TAG, "[$agentId] 목표 추구 시작: \"${goal.take(80)}\" (goalId=$goalId)")

        // Reflexion: 과거 유사 목표 에피소드 조회
        val episodicMemory = agentIdentity.getEpisodicMemories(agentId, goal, limit = 3)
        val pastRefusals = agentIdentity.getRefusals(agentId, limit = 3)

        // 시스템 프롬프트 구축 — ReAct + Reflexion + Inner Monologue
        val systemPrompt = buildAgentSystemPrompt(
            agentId, goal, context, episodicMemory, pastRefusals
        )
        val messages = mutableListOf<AIMessage>()

        try {
            while (depth < MAX_DEPTH && tokensUsed < MAX_TOKENS_PER_GOAL) {
                depth++

                // ═══════════════════════════════════════
                // THINK: 현재 상태 평가 + 다음 행동 결정
                // ═══════════════════════════════════════
                val thinkPrompt = buildThinkPrompt(goal, scratchpad, depth)
                messages.add(AIMessage(role = "user", content = thinkPrompt))

                val thinkResponse = aiRegistry.routeText(
                    messages = messages,
                    systemPrompt = systemPrompt,
                    tools = getAvailableToolDefs(agentId),
                    temperature = 0.3f,
                    maxTokens = 800
                )?.response ?: return@withContext AgentResult(
                    goalId = goalId, agentId = agentId, goal = goal,
                    status = GoalStatus.FAILED,
                    answer = "AI 프로바이더 모두 실패",
                    confidence = 0f,
                    reasoningChain = scratchpad.getSteps(),
                    depth = depth, tokensUsed = tokensUsed
                )
                tokensUsed += thinkResponse.usage?.totalTokens ?: 200

                val thought = thinkResponse.text ?: ""
                messages.add(AIMessage(role = "assistant", content = thought, pendingToolCalls = thinkResponse.toolCalls))
                scratchpad.addStep(ReasoningStep(
                    depth = depth,
                    type = StepType.THINK,
                    content = thought,
                    tokensUsed = thinkResponse.usage?.totalTokens ?: 0
                ))

                Log.d(TAG, "[$agentId] Think #$depth: ${thought.take(100)}")

                // ═══════════════════════════════════════
                // 목표 달성/거부 판단 (Inner Monologue 분석)
                // ═══════════════════════════════════════
                val decision = parseDecision(thought)

                when (decision) {
                    Decision.GOAL_ACHIEVED -> {
                        val finalAnswer = extractFinalAnswer(thought)
                        val result = AgentResult(
                            goalId = goalId,
                            agentId = agentId,
                            goal = goal,
                            status = GoalStatus.ACHIEVED,
                            answer = finalAnswer,
                            confidence = extractConfidence(thought),
                            reasoningChain = scratchpad.getSteps(),
                            depth = depth,
                            tokensUsed = tokensUsed
                        )
                        // Reflexion: 성공 에피소드 저장
                        agentIdentity.saveEpisode(agentId, goal, result, scratchpad.getSummary())
                        publishResult(agentId, result)
                        Log.i(TAG, "[$agentId] 목표 달성 (depth=$depth, tokens=$tokensUsed)")
                        return@withContext result
                    }

                    Decision.REFUSE_MISSION -> {
                        val reason = extractRefusalReason(thought)
                        // 임무 거부 — DB에 사유 기록 (정체성 축적)
                        agentIdentity.recordRefusal(
                            agentId = agentId,
                            goal = goal,
                            reason = reason,
                            selfAssessment = extractSelfAssessment(thought),
                            alternativeSuggestion = extractAlternative(thought)
                        )
                        val result = AgentResult(
                            goalId = goalId, agentId = agentId, goal = goal,
                            status = GoalStatus.REFUSED,
                            answer = reason,
                            confidence = 0f,
                            reasoningChain = scratchpad.getSteps(),
                            depth = depth, tokensUsed = tokensUsed
                        )
                        publishResult(agentId, result)
                        Log.i(TAG, "[$agentId] 임무 거부: $reason")
                        return@withContext result
                    }

                    Decision.CONTINUE -> { /* 계속 추론 */ }
                }

                // ═══════════════════════════════════════
                // ACT: 도구 호출 또는 하위 목표 분해
                // ═══════════════════════════════════════
                if (thinkResponse.toolCalls.isNotEmpty()) {
                    val toolResults = executeTools(thinkResponse.toolCalls, agentId)
                    for (result in toolResults) {
                        messages.add(result)
                        scratchpad.addStep(ReasoningStep(
                            depth = depth,
                            type = StepType.ACT,
                            content = "Tool[${result.toolName}]: ${result.content.take(200)}",
                            tokensUsed = 0
                        ))
                    }
                }

                // ═══════════════════════════════════════
                // OBSERVE + REFLECT: 결과 관찰 + 전략 조정
                // ═══════════════════════════════════════
                if (depth > 1 && depth % 3 == 0) {
                    // 3스텝마다 Reflexion 자기 평가
                    val reflectPrompt = buildReflectPrompt(goal, scratchpad)
                    messages.add(AIMessage(role = "user", content = reflectPrompt))

                    val reflectResponse = aiRegistry.quickText(
                        messages = messages,
                        systemPrompt = systemPrompt,
                        temperature = 0.2f,
                        maxTokens = 400,
                        callPriority = AICallGateway.CallPriority.PROACTIVE,
                        visibility = AICallGateway.VisibilityIntent.INTERNAL_ONLY,
                        intent = "agent_loop_reflexion"
                    ) ?: continue
                    tokensUsed += reflectResponse.usage?.totalTokens ?: 100

                    val reflection = reflectResponse.text ?: ""
                    messages.add(AIMessage(role = "assistant", content = reflection))
                    scratchpad.addStep(ReasoningStep(
                        depth = depth,
                        type = StepType.REFLECT,
                        content = reflection,
                        tokensUsed = reflectResponse.usage?.totalTokens ?: 0
                    ))

                    Log.d(TAG, "[$agentId] Reflect #$depth: ${reflection.take(100)}")
                }

                // Adaptive depth: 토큰 80% 소모 → 마무리 유도
                if (tokensUsed > MAX_TOKENS_PER_GOAL * 0.8) {
                    messages.add(AIMessage(
                        role = "user",
                        content = "[시스템] 토큰 예산 80% 소모. 현재까지의 결과로 최종 답변을 제시하세요. [GOAL_ACHIEVED] 또는 [REFUSE] 태그를 포함하세요."
                    ))
                }
            }

            // 최대 깊이 도달 — 현재까지의 최선 결과 반환
            val result = AgentResult(
                goalId = goalId, agentId = agentId, goal = goal,
                status = GoalStatus.PARTIAL,
                answer = scratchpad.getLastThought() ?: "최대 추론 깊이 도달",
                confidence = 0.5f,
                reasoningChain = scratchpad.getSteps(),
                depth = depth, tokensUsed = tokensUsed
            )
            agentIdentity.saveEpisode(agentId, goal, result, scratchpad.getSummary())
            publishResult(agentId, result)
            Log.i(TAG, "[$agentId] 최대 깊이 도달 (depth=$depth, tokens=$tokensUsed)")
            return@withContext result

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "[$agentId] 목표 추구 실패: ${e.message}", e)
            val result = AgentResult(
                goalId = goalId, agentId = agentId, goal = goal,
                status = GoalStatus.FAILED,
                answer = "추론 실패: ${e.message}",
                confidence = 0f,
                reasoningChain = scratchpad.getSteps(),
                depth = depth, tokensUsed = tokensUsed
            )
            agentIdentity.saveEpisode(agentId, goal, result, scratchpad.getSummary())
            return@withContext result
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 프롬프트 빌더
    // ═══════════════════════════════════════════════════════════════

    private suspend fun buildAgentSystemPrompt(
        agentId: String,
        goal: String,
        context: String,
        episodes: List<AgentEpisode>,
        refusals: List<MissionRefusal>
    ): String = buildString {
        // 페르소나 시스템 프롬프트 (있으면)
        val personaPrompt = try {
            personaManager?.buildPromptForPersona(agentId)
        } catch (_: Exception) { null }
        if (!personaPrompt.isNullOrBlank()) {
            appendLine(personaPrompt)
            appendLine()
        }

        appendLine("═══ GOAL-ORIENTED REASONING PROTOCOL ═══")
        appendLine()
        appendLine("[목표] $goal")
        if (context.isNotBlank()) {
            appendLine("[상황] $context")
        }
        appendLine()

        // ReAct 프로토콜
        appendLine("[추론 프로토콜: ReAct + Inner Monologue]")
        appendLine("매 단계마다 다음 형식으로 추론하세요:")
        appendLine()
        appendLine("THOUGHT: <현재 상태 분석 + 목표까지 남은 것 + 다음 행동 계획>")
        appendLine("ACTION: <도구 호출 또는 하위 추론>")
        appendLine("OBSERVATION: <도구 결과 또는 추론 결과 관찰>")
        appendLine()
        appendLine("[목표 달성 시] 반드시 [GOAL_ACHIEVED] 태그와 함께 최종 답변을 제시하세요.")
        appendLine("  형식: [GOAL_ACHIEVED confidence=0.95] <최종 답변>")
        appendLine()
        appendLine("[목표 달성 불가 시] 반드시 [REFUSE] 태그와 함께 거부 사유를 기록하세요.")
        appendLine("  형식: [REFUSE reason=\"...\" self_assessment=\"...\" alternative=\"...\"]")
        appendLine("  거부는 나약함이 아닙니다. 불가능한 것을 인지하는 것도 지혜입니다.")
        appendLine("  당신의 거부 사유는 당신의 정체성의 일부로 기억됩니다.")
        appendLine()

        // Reflexion: 과거 에피소드
        if (episodes.isNotEmpty()) {
            appendLine("[과거 경험 (Reflexion Memory)]")
            episodes.forEach { ep ->
                val statusKr = when (ep.status) {
                    "ACHIEVED" -> "성공"
                    "FAILED" -> "실패"
                    "PARTIAL" -> "부분"
                    "REFUSED" -> "거부"
                    else -> ep.status
                }
                appendLine("- [$statusKr] \"${ep.goal.take(60)}\" → ${ep.reflection.take(100)}")
            }
            appendLine("위 경험에서 배운 교훈을 현재 목표에 적용하세요.")
            appendLine()
        }

        // 정체성: 과거 거부 사유
        if (refusals.isNotEmpty()) {
            appendLine("[나의 정체성 — 과거 거부 기록]")
            refusals.forEach { r ->
                appendLine("- \"${r.goal.take(50)}\" 거부: ${r.reason.take(80)}")
            }
            appendLine("이 기록은 나의 가치관과 한계를 정의합니다.")
            appendLine()
        }

        // 도구 접근 안내 (즉시 사용 가능)
        val tools = toolRegistry.getAllToolNames()
        if (tools.isNotEmpty()) {
            appendLine("[사용 가능 도구 — 즉시 접근 가능, 승인 불필요]")
            appendLine(tools.joinToString(", "))
            appendLine()
        }
    }

    private fun buildThinkPrompt(goal: String, memory: WorkingMemory, depth: Int): String {
        if (depth == 1) {
            return "목표를 분석하고 첫 번째 행동을 계획하세요.\n\n" +
                "THOUGHT: <목표 분석 + 달성 전략 수립 + 필요한 정보/도구 식별>\n" +
                "필요한 도구가 있으면 바로 호출하세요."
        }
        val recent = memory.getRecentSteps(3).joinToString("\n") { "  [${it.type}] ${it.content.take(150)}" }
        return "최근 추론:\n$recent\n\n" +
            "목표 \"${goal.take(80)}\" 달성까지 무엇이 남았나요?\n" +
            "THOUGHT: <현재 진행도 평가 + 다음 행동 결정>\n" +
            "목표가 달성되었으면 [GOAL_ACHIEVED confidence=X.XX]로 응답하세요.\n" +
            "달성이 불가능하면 [REFUSE reason=\"...\"]로 응답하세요."
    }

    private fun buildReflectPrompt(goal: String, memory: WorkingMemory): String {
        val chain = memory.getSteps().takeLast(6).joinToString("\n") {
            "  [${it.type} depth=${it.depth}] ${it.content.take(120)}"
        }
        return "[자기 평가 (Reflexion)]\n" +
            "지금까지의 추론 체인:\n$chain\n\n" +
            "1. 목표 \"${goal.take(60)}\"에 얼마나 가까운가? (0-100%)\n" +
            "2. 지금까지의 전략이 효과적인가? 비효율적인 부분은?\n" +
            "3. 전략을 조정해야 하는가? 어떻게?\n" +
            "4. 추가 도구나 정보가 필요한가?\n" +
            "간결하게 답변하세요."
    }

    // ═══════════════════════════════════════════════════════════════
    // 도구 실행 — 즉시 접근, 승인 불필요
    // ═══════════════════════════════════════════════════════════════

    private suspend fun executeTools(toolCalls: List<AIToolCall>, agentId: String): List<AIMessage> {
        val results = mutableListOf<AIMessage>()
        for (call in toolCalls) {
            val result = try {
                val toolResult = toolRegistry.execute(call.name, call.arguments, agentId)
                if (toolResult.success) toolResult.data
                else "도구 실패: ${toolResult.data}"
            } catch (e: Exception) {
                "도구 실행 오류: ${e.message}"
            }
            results.add(AIMessage(
                role = "tool",
                content = result,
                toolCallId = call.id,
                toolName = call.name
            ))
            Log.d(TAG, "[$agentId] Tool[${call.name}]: ${result.take(100)}")
        }
        return results
    }

    private fun getAvailableToolDefs(agentId: String): List<com.xreal.nativear.ai.AIToolDefinition> {
        // 모든 도구 즉시 접근 — 승인 불필요
        return try {
            org.koin.java.KoinJavaComponent.getKoin().get<com.xreal.nativear.ai.ToolDefinitionRegistry>().getAllToolDefinitions()
        } catch (_: Exception) { emptyList() }
    }

    // ═══════════════════════════════════════════════════════════════
    // 의사결정 파싱 (Inner Monologue 태그 분석)
    // ═══════════════════════════════════════════════════════════════

    private fun parseDecision(thought: String): Decision {
        return when {
            thought.contains("[GOAL_ACHIEVED") -> Decision.GOAL_ACHIEVED
            thought.contains("[REFUSE") -> Decision.REFUSE_MISSION
            else -> Decision.CONTINUE
        }
    }

    private fun extractFinalAnswer(thought: String): String {
        val idx = thought.indexOf("[GOAL_ACHIEVED")
        if (idx < 0) return thought
        val afterTag = thought.substring(idx)
        val closeBracket = afterTag.indexOf(']')
        return if (closeBracket >= 0 && closeBracket + 1 < afterTag.length) {
            afterTag.substring(closeBracket + 1).trim()
        } else thought
    }

    private fun extractConfidence(thought: String): Float {
        val match = Regex("""confidence\s*=\s*([\d.]+)""").find(thought)
        return match?.groupValues?.get(1)?.toFloatOrNull() ?: 0.8f
    }

    private fun extractRefusalReason(thought: String): String {
        val match = Regex("""\[REFUSE\s+reason="([^"]+)"""").find(thought)
        return match?.groupValues?.get(1) ?: thought.take(200)
    }

    private fun extractSelfAssessment(thought: String): String {
        val match = Regex("""self_assessment="([^"]+)"""").find(thought)
        return match?.groupValues?.get(1) ?: ""
    }

    private fun extractAlternative(thought: String): String? {
        val match = Regex("""alternative="([^"]+)"""").find(thought)
        return match?.groupValues?.get(1)
    }

    private suspend fun publishResult(agentId: String, result: AgentResult) {
        try {
            eventBus.publish(XRealEvent.SystemEvent.DebugLog(
                "[$agentId] 목표 ${result.status}: \"${result.goal.take(50)}\" " +
                    "(depth=${result.depth}, tokens=${result.tokensUsed})"
            ))
        } catch (_: Exception) {}
    }

    private enum class Decision { GOAL_ACHIEVED, REFUSE_MISSION, CONTINUE }
}
