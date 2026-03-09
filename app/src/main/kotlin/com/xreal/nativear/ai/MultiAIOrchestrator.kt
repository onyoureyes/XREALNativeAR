package com.xreal.nativear.ai

import android.util.Log
import com.xreal.nativear.core.ErrorReporter
import com.xreal.nativear.core.ErrorSeverity
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.IOException

/**
 * Aggregated response from multiple personas.
 */
data class AggregatedResponse(
    val personaResponses: Map<String, AIResponse>,
    val synthesizedText: String?,
    val consensusLevel: Float,
    val totalLatencyMs: Long,
    val synthesisMode: String = ""   // ★ Phase J: HIGH_CONSENSUS|MID_CONSENSUS|COORDINATOR|DISAGREEMENT
)

class MultiAIOrchestrator(
    private val personaManager: PersonaManager,
    private val personaMemoryService: PersonaMemoryService,
    private val providers: Map<ProviderId, IAIProvider>,
    private val eventBus: com.xreal.nativear.core.GlobalEventBus,
    private val toolExecutorRegistry: com.xreal.nativear.tools.ToolExecutorRegistry? = null,
    private val tokenBudgetTracker: com.xreal.nativear.router.persona.TokenBudgetTracker? = null,
    private val analyticsService: com.xreal.nativear.analytics.SystemAnalyticsService? = null,
    private val personaProviderRouter: PersonaProviderRouter? = null,                          // ★ Phase N
    private val toolHealthMonitor: com.xreal.nativear.tools.ToolHealthMonitor? = null,         // ★ Phase N
    private val remoteLLMPool: RemoteLLMPool? = null                                           // ★ Remote LLM 어레이
) {
    private val TAG = "MultiAIOrchestrator"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val aiRegistry: IAICallService? by lazy {
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull() } catch (_: Exception) { null }
    }

    companion object {
        // ★ Phase N: ToolHealthMonitor 상수 참조 (중복 정의 방지)
        private val ESSENTIAL_TOOLS = com.xreal.nativear.tools.ToolHealthMonitor.ESSENTIAL_TOOLS
        private const val MAX_CAREFUL_TOOLS = com.xreal.nativear.tools.ToolHealthMonitor.MAX_CAREFUL_TOOLS
    }

    /**
     * Dispatch a query to multiple personas in parallel.
     * Each persona gets the same user query but with its own system prompt
     * and injected memories.
     */
    suspend fun dispatchParallel(
        query: String,
        personaIds: List<String>? = null,
        context: String? = null
    ): AggregatedResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val targetPersonas = if (personaIds != null) {
            personaIds.mapNotNull { personaManager.getPersona(it) }
        } else {
            personaManager.getEnabledPersonas()
        }

        if (targetPersonas.isEmpty()) {
            return@withContext AggregatedResponse(
                personaResponses = emptyMap(),
                synthesizedText = "No available personas configured.",
                consensusLevel = 0f,
                totalLatencyMs = System.currentTimeMillis() - startTime
            )
        }

        Log.i(TAG, "Parallel dispatch to ${targetPersonas.size} personas: ${targetPersonas.map { it.id }}")

        // Launch all persona calls in parallel
        val deferredResults = targetPersonas.map { persona ->
            async {
                try {
                    callPersona(persona, query, context)
                } catch (e: Exception) {
                    ErrorReporter.report(TAG, "Multi-AI 페르소나 호출 실패: ${persona.id}", e, ErrorSeverity.WARNING)
                    persona.id to AIResponse(
                        text = "Error: ${e.message}",
                        providerId = persona.providerId,
                        latencyMs = 0
                    )
                }
            }
        }

        val results = deferredResults.awaitAll().toMap()
        val totalLatency = System.currentTimeMillis() - startTime

        // Store each persona's response in memory (fire and forget)
        results.forEach { (personaId, response) ->
            if (response.text != null && !response.text.startsWith("Error:")) {
                scope.launch {
                    try {
                        personaMemoryService.savePersonaMemory(
                            personaId = personaId,
                            content = response.text,
                            role = "AI_${personaId.uppercase()}",
                            metadata = JSONObject().apply {
                                put("query", query)
                                put("latency_ms", response.latencyMs)
                                put("provider", response.providerId.name)
                            }.toString()
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to save persona memory for $personaId: ${e.message}")
                    }
                }
            }
        }

        // ★ Phase J: 합의 수준 기반 스마트 합성
        val consensusLevel = calculateConsensus(results)
        val (rawSynthesis, rawMode) = synthesizeResponses(results, consensusLevel, query)

        // 낮은 합의 → Gemini 조율 합성 시도
        val (finalSynthesis, finalMode) = if (consensusLevel < 0.4f) {
            val coordinated = synthesizeWithCoordinator(results, query)
            if (coordinated != null) Pair(coordinated, "COORDINATOR") else Pair(rawSynthesis, rawMode)
        } else {
            Pair(rawSynthesis, rawMode)
        }

        AggregatedResponse(
            personaResponses = results,
            synthesizedText = finalSynthesis,
            consensusLevel = consensusLevel,
            totalLatencyMs = totalLatency,
            synthesisMode = finalMode
        )
    }

    /**
     * Dispatch a query to a single specific persona.
     */
    suspend fun dispatchSingle(
        query: String,
        personaId: String,
        context: String? = null
    ): AIResponse = withContext(Dispatchers.IO) {
        val persona = personaManager.getPersona(personaId)
            ?: return@withContext AIResponse(
                text = "Unknown persona: $personaId",
                providerId = ProviderId.GEMINI
            )

        val (_, response) = callPersona(persona, query, context)
        response
    }

    private suspend fun callPersona(
        persona: Persona,
        query: String,
        context: String?
    ): Pair<String, AIResponse> {
        // ★ Phase N: 동적 Provider 선택 (failover + 예산 기반)
        val resolvedProviderId = personaProviderRouter?.resolve(persona) ?: persona.providerId
        val provider = providers[resolvedProviderId]
            ?: return persona.id to AIResponse(
                text = "Provider $resolvedProviderId not configured",
                providerId = resolvedProviderId
            )

        if (!provider.isAvailable) {
            return persona.id to AIResponse(
                text = "Provider $resolvedProviderId has no API key",
                providerId = resolvedProviderId
            )
        }

        // ─── AICallGateway: 중앙 게이트 체크 (Budget + Rate + Thermal) ───
        try {
            val gateway: AICallGateway? = org.koin.java.KoinJavaComponent.getKoin().getOrNull()
            gateway?.let { gw ->
                val gateDecision = gw.checkGate(
                    priority = AICallGateway.CallPriority.REACTIVE,
                    visibility = AICallGateway.VisibilityIntent.USER_FACING,
                    estimatedTokens = persona.maxTokens,
                    providerId = resolvedProviderId
                )
                if (!gateDecision.allowed) {
                    Log.w(TAG, "GATEWAY GATE: ${persona.id}/$resolvedProviderId BLOCKED — ${gateDecision.reason}")
                    return persona.id to AIResponse(
                        text = "[게이트 차단] ${gateDecision.reason}",
                        providerId = resolvedProviderId,
                        latencyMs = 0
                    )
                }
            }
        } catch (_: Exception) {}

        // ─── Budget Enforcement Gate (Architecture Principle 1) ───
        // Check budget BEFORE building prompts or making API calls
        tokenBudgetTracker?.let { tracker ->
            val budgetCheck = tracker.checkBudget(
                providerId = resolvedProviderId,  // ★ Phase N
                isEssential = false,
                estimatedTokens = persona.maxTokens
            )

            if (!budgetCheck.allowed) {
                Log.w(TAG, "BUDGET GATE: ${persona.id}/$resolvedProviderId BLOCKED — ${budgetCheck.reason}")
                // Publish budget warning event
                scope.launch {
                    eventBus.publish(com.xreal.nativear.core.XRealEvent.SystemEvent.DebugLog(
                        "예산 차단: $resolvedProviderId ${budgetCheck.tier.displayName} " +
                                "(남은: ${budgetCheck.remainingTokens} tokens)"
                    ))
                }
                return persona.id to AIResponse(
                    text = "[예산 ${budgetCheck.tier.displayName}] ${budgetCheck.reason}",
                    providerId = resolvedProviderId,
                    latencyMs = 0
                )
            }

            // Log budget tier warnings
            if (budgetCheck.tier != com.xreal.nativear.router.persona.BudgetTier.NORMAL) {
                Log.w(TAG, "Budget tier ${budgetCheck.tier}: $resolvedProviderId — ${budgetCheck.reason}")
            }
        }
        // ─── End Budget Gate ───

        val (systemPrompt, initialMessages) = personaManager.buildMessagesForPersona(
            personaId = persona.id,
            userQuery = query,
            additionalContext = context
        )

        // ★ Phase N: 동적 도구 해결 (정적 persona.tools + ExpertDynamicProfileStore 승인 도구)
        val dynamicToolNames = try {
            org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.expert.ExpertDynamicProfileStore>()
                ?.getDynamicTools(persona.id)
                ?: emptyList()
        } catch (_: Exception) { emptyList() }

        var tools = (persona.tools + dynamicToolNames).distinct()
            .mapNotNull { ToolDefinitionRegistry.getToolDefinition(it) }

        // ★ Phase N: 예산 등급별 도구 필터링 (resolvedProviderId 기준)
        tools = tokenBudgetTracker?.let { tracker ->
            when (tracker.getBudgetTier(resolvedProviderId)) {
                com.xreal.nativear.router.persona.BudgetTier.MINIMAL ->
                    tools.filter { it.name in ESSENTIAL_TOOLS }   // 핵심 도구만
                com.xreal.nativear.router.persona.BudgetTier.CAREFUL ->
                    tools.take(MAX_CAREFUL_TOOLS)                  // 상위 5개만
                else -> tools
            }
        } ?: tools

        // ★ Phase N: 실패율 높은 도구 자동 격리 필터
        tools = toolHealthMonitor?.filterHealthyTools(tools) ?: tools

        // Adjust maxTokens based on budget tier
        val adjustedMaxTokens = tokenBudgetTracker?.let { tracker ->
            val tier = tracker.getBudgetTier(resolvedProviderId)  // ★ Phase N
            when (tier) {
                com.xreal.nativear.router.persona.BudgetTier.CAREFUL ->
                    (persona.maxTokens * 0.7f).toInt()  // 30% reduction at 90%+
                com.xreal.nativear.router.persona.BudgetTier.MINIMAL ->
                    (persona.maxTokens * 0.3f).toInt()  // 70% reduction at 95%+
                else -> persona.maxTokens
            }
        } ?: persona.maxTokens

        var currentMessages = initialMessages.toMutableList()

        // ★ 네트워크 에러 시 엣지 AI로 즉시 fallback하는 헬퍼
        suspend fun sendWithNetworkFallback(
            targetProvider: IAIProvider,
            msgs: List<AIMessage>,
            sysPrompt: String?,
            toolList: List<AIToolDefinition>,
            temp: Float?,
            maxTok: Int
        ): AIResponse {
            return try {
                targetProvider.sendMessage(
                    messages = msgs,
                    systemPrompt = sysPrompt,
                    tools = toolList,
                    temperature = temp,
                    maxTokens = maxTok
                )
            } catch (e: java.io.IOException) {
                // ★ 네트워크 에러 → Remote LLM Pool failover → Edge (최후의 보루)
                Log.w(TAG, "네트워크 에러 (${targetProvider.providerId}): ${e.message}")
                remoteLLMPool?.recordFailure(targetProvider.providerId, e)

                // 1차: Remote LLM Pool에서 다른 서버 시도
                val remoteResult = remoteLLMPool?.callWithFailover { remoteProvider ->
                    Log.i(TAG, "Remote LLM failover: ${remoteProvider.providerId}")
                    remoteProvider.sendMessage(
                        messages = msgs,
                        systemPrompt = sysPrompt,
                        tools = toolList,
                        temperature = temp,
                        maxTokens = maxTok
                    )
                }
                if (remoteResult != null) return remoteResult

                // 2차: Edge AI (최후의 보루)
                eventBus.publish(com.xreal.nativear.core.XRealEvent.SystemEvent.DebugLog(
                    "Remote LLM 전체 불가 — 엣지 AI 전환 (${persona.id})"
                ))
                val edgeProvider = providers[ProviderId.EDGE_AGENT]
                    ?: providers[ProviderId.EDGE_EMERGENCY]
                if (edgeProvider != null && edgeProvider.isAvailable) {
                    tokenBudgetTracker?.recordUsage(edgeProvider.providerId, minOf(maxTok, 512))
                    Log.i(TAG, "엣지 AI 호출 (최후의 보루): ${edgeProvider.providerId}")
                    edgeProvider.sendMessage(
                        messages = msgs,
                        systemPrompt = sysPrompt,
                        tools = emptyList(),
                        temperature = temp,
                        maxTokens = minOf(maxTok, 512)
                    )
                } else {
                    AIResponse(
                        text = "[오프라인] 모든 AI 서버에 연결할 수 없습니다. 잠시 후 다시 시도해주세요.",
                        providerId = ProviderId.EDGE_EMERGENCY,
                        latencyMs = 0
                    )
                }
            }
        }

        var response = sendWithNetworkFallback(
            targetProvider = provider,
            msgs = currentMessages,
            sysPrompt = systemPrompt,
            toolList = tools,
            temp = persona.temperature,
            maxTok = adjustedMaxTokens
        )

        // Tool call execution loop (max 5 rounds to prevent infinite loops)
        var round = 0
        while (response.toolCalls.isNotEmpty() && toolExecutorRegistry != null && round < 5) {
            round++
            Log.d(TAG, "Persona ${persona.id} tool round $round: ${response.toolCalls.size} calls")

            // Add assistant's response (with tool calls) to conversation
            currentMessages.add(AIMessage(
                role = "assistant",
                content = response.text ?: ""
            ))

            // Execute each tool call and build result messages
            for (toolCall in response.toolCalls) {
                val toolStart = System.currentTimeMillis()
                val result = toolExecutorRegistry.execute(toolCall.name, toolCall.arguments, persona.id)  // ★ Phase M: personaId 전달
                val toolLatency = System.currentTimeMillis() - toolStart
                Log.d(TAG, "Tool ${toolCall.name} → success=${result.success}: ${result.data.take(100)}")
                currentMessages.add(AIMessage(
                    role = "tool",
                    content = "{\"tool_call_id\":\"${toolCall.id}\",\"name\":\"${toolCall.name}\",\"result\":${JSONObject().put("success", result.success).put("data", result.data)}}"
                ))

                // Log tool call result to analytics (audit trail)
                analyticsService?.logToolResult(
                    expertId = persona.id,
                    toolName = toolCall.name,
                    args = toolCall.arguments,
                    result = result.data,
                    latencyMs = toolLatency
                )
            }

            // Send tool results back to provider (★ 도구 루프도 네트워크 fallback 적용)
            response = sendWithNetworkFallback(
                targetProvider = provider,
                msgs = currentMessages,
                sysPrompt = systemPrompt,
                toolList = tools,
                temp = persona.temperature,
                maxTok = persona.maxTokens
            )
        }

        // Record token usage (★ Phase N: 실제 사용된 resolvedProviderId에 기록)
        tokenBudgetTracker?.let { tracker ->
            val tokens = response.usage?.totalTokens ?: 0
            if (tokens > 0) {
                tracker.recordUsage(resolvedProviderId, tokens)
            }
        }

        // Log decision for edge delegation analysis
        analyticsService?.logDecision(
            situation = null, // filled by OutcomeTracker later
            contextHash = null,
            expertId = persona.id,
            actionType = if (response.toolCalls.isNotEmpty()) "TOOL_CALL" else "ADVICE",
            toolCalls = response.toolCalls.map { it.name },
            responseSummary = response.text?.take(100),
            tokensUsed = response.usage?.totalTokens ?: 0,
            latencyMs = response.latencyMs
        )

        return persona.id to response
    }

    /**
     * ★ Phase J: 합의 수준 기반 스마트 합성.
     * Returns Pair(synthesizedText, synthesisMode)
     */
    private fun synthesizeResponses(
        responses: Map<String, AIResponse>,
        consensusLevel: Float,
        query: String? = null
    ): Pair<String, String> {
        val valid = responses.filter {
            !it.value.text.isNullOrBlank() && !it.value.text!!.startsWith("Error")
        }
        if (valid.isEmpty()) return Pair("모든 전문가 응답 실패", "ERROR")
        if (valid.size == 1) return Pair(valid.values.first().text!!, "SINGLE")

        return when {
            consensusLevel >= 0.6f -> {
                // 높은 합의: 가장 상세한 응답 + 동의 표시
                val dominant = valid.maxByOrNull { it.value.text!!.length }
                val text = "${dominant?.value?.text}\n\n_(전문가 ${valid.size}명 합의)_"
                Pair(text, "HIGH_CONSENSUS")
            }
            consensusLevel >= 0.4f -> {
                // 중간 합의: 핵심 관점 2개 표시
                val text = buildString {
                    appendLine("전문가 ${valid.size}명 분석:")
                    valid.entries.take(2).forEach { (id, resp) ->
                        val name = personaManager.getPersona(id)?.name ?: id
                        appendLine("• [$name] ${resp.text?.take(120)}")
                    }
                    if (valid.size > 2) append("외 ${valid.size - 2}명 추가 분석")
                }
                Pair(text.trimEnd(), "MID_CONSENSUS")
            }
            else -> {
                // 낮은 합의: 이견 요약 (COORDINATOR 합성 시도는 dispatchParallel에서)
                val text = buildString {
                    appendLine("⚠️ 전문가 의견이 나뉩니다:")
                    valid.entries.forEach { (id, resp) ->
                        val name = personaManager.getPersona(id)?.name ?: id
                        appendLine("• [$name] ${resp.text?.take(80)}")
                    }
                }
                Pair(text.trimEnd(), "DISAGREEMENT")
            }
        }
    }

    /**
     * ★ Phase J: 낮은 합의 시 Gemini가 조율 합성 (budget NORMAL일 때만).
     */
    private suspend fun synthesizeWithCoordinator(
        responses: Map<String, AIResponse>,
        @Suppress("UNUSED_PARAMETER") originalQuery: String? = null
    ): String? {
        // 예산 NORMAL일 때만 실행
        val budgetTier = tokenBudgetTracker?.getBudgetTier(ProviderId.GEMINI)
        if (budgetTier != com.xreal.nativear.router.persona.BudgetTier.NORMAL) return null
        val coordinator = providers[ProviderId.GEMINI] ?: return null

        val perspectives = responses.entries
            .filter { !it.value.text.isNullOrBlank() && !it.value.text!!.startsWith("Error") }
            .joinToString("\n") { (id, resp) ->
                val name = personaManager.getPersona(id)?.name ?: id
                "[$name]: ${resp.text?.take(200)}"
            }
        if (perspectives.isBlank()) return null

        val metaPrompt = buildString {
            if (!originalQuery.isNullOrBlank()) appendLine("사용자 질문: \"$originalQuery\"\n")
            appendLine("4명의 전문가 관점:")
            appendLine(perspectives)
            appendLine()
            append("위 전문가 의견들을 종합해서 사용자에게 1~2문장으로 핵심만 명확하게 답해줘. ")
            append("이견이 있다면 핵심 선택지를 포함할 것. JSON 아님, 자연어로.")
        }

        // Budget gate: 조율 합성 전 예산 확인
        tokenBudgetTracker?.let { tracker ->
            val check = tracker.checkBudget(ProviderId.GEMINI, estimatedTokens = 300)
            if (!check.allowed) {
                Log.w(TAG, "조율 합성 예산 초과: ${check.reason}")
                return null
            }
        }

        return try {
            val msgs = listOf(AIMessage(role = "user", content = metaPrompt))
            val response = aiRegistry?.quickText(
                msgs, maxTokens = 300,
                callPriority = AICallGateway.CallPriority.PROACTIVE,
                visibility = AICallGateway.VisibilityIntent.INTERNAL_ONLY,
                intent = "multi_ai_synthesis"
            ) ?: coordinator.sendMessage(msgs, maxTokens = 300)
            response.text
        } catch (e: Exception) {
            Log.w(TAG, "조율 합성 실패, 이견 표시로 폴백: ${e.message}")
            null
        }
    }

    /**
     * Calculate consensus level by comparing response similarity.
     * Simple word overlap metric (Jaccard).
     */
    private fun calculateConsensus(responses: Map<String, AIResponse>): Float {
        val texts = responses.values.mapNotNull { it.text }.filter { !it.startsWith("Error") }
        if (texts.size <= 1) return 1.0f

        val words = texts.map { it.split("\\s+".toRegex()).toSet() }
        var totalOverlap = 0f
        var pairs = 0
        for (i in words.indices) {
            for (j in i + 1 until words.size) {
                val intersection = words[i].intersect(words[j])
                val union = words[i].union(words[j])
                if (union.isNotEmpty()) {
                    totalOverlap += intersection.size.toFloat() / union.size
                    pairs++
                }
            }
        }
        return if (pairs > 0) totalOverlap / pairs else 1.0f
    }

    /**
     * Record a hit (prediction was correct) or miss for a persona.
     */
    fun recordPredictionResult(personaId: String, wasCorrect: Boolean) {
        personaMemoryService.recordHit(personaId, wasCorrect)
    }

    fun release() {
        scope.cancel()
    }
}
