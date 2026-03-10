package com.xreal.nativear

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.xreal.ai.UnifiedAIOrchestrator
import com.xreal.nativear.ai.AIMessage
import com.xreal.nativear.ai.AIResponse
import com.xreal.nativear.ai.AIToolDefinition
import com.xreal.nativear.ai.GeminiProvider
import com.xreal.nativear.ai.IAIProvider
import com.xreal.nativear.ai.ProviderId
import com.xreal.nativear.router.persona.TokenBudgetTracker
import com.xreal.nativear.core.ErrorReporter
import com.xreal.nativear.memory.api.IMemoryStore
import kotlinx.coroutines.launch

/**
 * AIAgentManager: Orchestrates AI interactions, tool calling, and memory.
 *
 * Uses IAIProvider (GeminiProvider) for all AI calls, ensuring token budget tracking.
 * Vision calls use GeminiProvider.sendVisionMessage() for bitmap support.
 */
class AIAgentManager(
    private val context: Context,
    private val memoryStore: IMemoryStore,
    private val searchService: ISearchService,
    private val weatherService: IWeatherService,
    private val navigationService: INavigationService,
    private val visionService: IVisionService,
    private val aiOrchestrator: UnifiedAIOrchestrator,
    private val locationService: ILocationService,
    private val cloudBackupManager: CloudBackupManager,
    private val eventBus: com.xreal.nativear.core.GlobalEventBus,
    private val toolExecutorRegistry: com.xreal.nativear.tools.ToolExecutorRegistry,
    private val tokenBudgetTracker: TokenBudgetTracker? = null
) {
    // C1 FIX: Dispatchers.Main → Dispatchers.Default (AI/DB ops must not block UI thread)
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )
    private var callback: AIAgentCallback? = null

    // Multi-AI Orchestrator (lazy Koin inject to avoid circular dependency)
    private val multiOrchestrator: com.xreal.nativear.ai.MultiAIOrchestrator? by lazy {
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull()
        } catch (e: Exception) { null }
    }

    // PersonaRouter (lazy Koin inject)
    private val personaRouter: com.xreal.nativear.router.persona.PersonaRouter? by lazy {
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull()
        } catch (e: Exception) { null }
    }

    // ★ Phase K: PersonaManager (lazy Koin inject) — 단일 AI 호출 컨텍스트 강화
    private val personaManager: com.xreal.nativear.ai.PersonaManager? by lazy {
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull() } catch (_: Exception) { null }
    }

    // FocusModeManager (lazy Koin inject) — DND/PRIVATE 시 AI 능동 행동 억제
    private val focusModeManager: com.xreal.nativear.focus.FocusModeManager? by lazy {
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull() } catch (e: Exception) { null }
    }

    // EdgeDelegationRouter (lazy Koin inject) — 엣지 AI 라우팅
    // RESEARCH.md §2 LiteRT-LM, §11 IAIProvider 참조
    private val edgeDelegationRouter: com.xreal.nativear.edge.EdgeDelegationRouter? by lazy {
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull()
        } catch (e: Exception) { null }
    }

    // ★ 모든 프로바이더 공통 도구 정의 (매 호출마다 최신 목록 — 원격 도구 동적 등록 반영)
    private val toolDefs: List<AIToolDefinition>
        get() = try { org.koin.java.KoinJavaComponent.getKoin().get<com.xreal.nativear.ai.ToolDefinitionRegistry>().getAllToolDefinitions() } catch (e: Exception) { emptyList() }

    // ★ Phase C: LifeSessionManager (lazy Koin inject)
    private val lifeSessionManager: com.xreal.nativear.session.LifeSessionManager? by lazy {
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull() } catch (e: Exception) { null }
    }

    // ★ GoalOrientedAgentLoop (lazy Koin inject) — 심층 추론 에이전트
    private val goalAgentLoop: com.xreal.nativear.agent.GoalOrientedAgentLoop? by lazy {
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull() } catch (_: Exception) { null }
    }

    // ★ AIResourceRegistry (lazy Koin inject) — 통합 AI 프로바이더 라우팅
    private val aiRegistry: com.xreal.nativear.ai.IAICallService? by lazy {
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull() } catch (_: Exception) { null }
    }

    // ★ Remote LLM Pool (lazy Koin inject) — PC + 스팀덱 어레이, $0 비용
    private val remoteLLMPool: com.xreal.nativear.ai.RemoteLLMPool? by lazy {
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.ai.RemoteLLMPool>()
        } catch (e: Exception) { null }
    }

    // 하위 호환용 — RemoteLLMPool에서 가용 서버 하나 가져오기
    private val localProvider: com.xreal.nativear.ai.IAIProvider? get() =
        remoteLLMPool?.pickServer() ?: try {
            org.koin.java.KoinJavaComponent.getKoin()
                .get<com.xreal.nativear.ai.IAIProvider>(org.koin.core.qualifier.named("local"))
        } catch (e: Exception) { null }

    // 엣지 AI 프로바이더 맵 (lazy) — 엣지 응답 시 직접 호출
    private val edgeProviders: Map<String, com.xreal.nativear.ai.IAIProvider>? by lazy {
        try {
            val koin = org.koin.java.KoinJavaComponent.getKoin()
            mapOf(
                "edge_assistant" to koin.get<com.xreal.nativear.ai.IAIProvider>(
                    org.koin.core.qualifier.named("edge_agent")
                ),
                "edge_emergency" to koin.get<com.xreal.nativear.ai.IAIProvider>(
                    org.koin.core.qualifier.named("edge_emergency")
                )
            )
        } catch (e: Exception) { null }
    }

    fun setCallback(cb: AIAgentCallback) {
        this.callback = cb
    }


    private val TAG = "AIAgentManager"
    var isGeminiBusy = false
        private set

    // ★ Phase J: 마지막 Multi-AI 세션 추적 (VoiceFeedback 피드백 루프용)
    @Volatile private var lastMultiAISessionId: Long = -1L
    @Volatile private var lastMultiAIPersonaIds: List<String>? = null

    // ★ Phase L: 세션 내 대화 버퍼 (role=user/assistant 쌍, 최근 N턴)
    private val sessionConversationBuffer = ArrayDeque<Pair<AIMessage, AIMessage>>()
    // ★ Policy Department: PolicyRegistry shadow read
    private val MAX_CONVERSATION_TURNS: Int get() =
        com.xreal.nativear.policy.PolicyReader.getInt("system.max_conversation_turns", 4)

    // ★ P1-4: 예산 초과 시 사용자 확인 대기 (NOD/SHAKE)
    @Volatile private var pendingBudgetQuery: String? = null
    @Volatile private var pendingBudgetContext: String? = null

    // For throttling spatial indexing (H1 FIX: LRU eviction, cap 200)
    private val recentlyIndexedObjects = LinkedHashMap<String, Long>(200, 0.75f, true)
    // ★ Policy Department: PolicyRegistry shadow read
    private val INDEX_THROTTLE_MS: Long get() =
        com.xreal.nativear.policy.PolicyReader.getLong("system.index_throttle_ms", 60000L)
    private val MAX_INDEXED_OBJECTS: Int get() =
        com.xreal.nativear.policy.PolicyReader.getInt("system.max_indexed_objects", 200)

    // ★ Edge AI 추론 중복 방지 — GlobalMutex 대기열 폭증 방지
    // interpretScene 폴백이 동시에 여러 번 호출되면 GlobalMutex에 쌓여 24s+ freeze 발생
    // compareAndSet(false, true) 실패 = 이미 추론 중 → 즉시 스킵
    private val isEdgeInferencePending = java.util.concurrent.atomic.AtomicBoolean(false)

    interface AIAgentCallback {
        fun onCentralMessage(text: String)
        fun onGeminiResponse(reply: String)
        fun onSearchResults(resultsJson: String)
        fun showSnapshotFeedback()
        fun onGetLatestBitmap(): Bitmap?
        fun onGetScreenObjects(): String
    }


    fun processWithGemini(userText: String, externalContext: String? = null) {
        if (isGeminiBusy) return

        // ─── FocusMode 게이트: 사용자 명령은 항상 허용, 능동적 AI만 억제
        // DND/PRIVATE 모드에서도 직접 음성 명령은 USER_COMMAND로 처리
        val focusGate = focusModeManager
        if (focusGate != null && !focusGate.canAIAct(com.xreal.nativear.focus.AITrigger.USER_COMMAND)) {
            Log.d(TAG, "processWithGemini 억제됨 (FocusMode: ${focusGate.currentMode})")
            callback?.onGeminiResponse("현재 ${focusGate.currentMode.name} 모드입니다. 안전 관련 명령만 처리됩니다.")
            return
        }

        // ─── 엣지 모드 음성 명령 처리 (RESEARCH.md §2 참조)
        // "범블비 엣지 모드" → E2B 로딩 + 강제 엣지 전환
        // "범블비 서버 모드" → 강제 엣지 해제
        val lowerText = userText.lowercase()
        if (lowerText.contains("엣지 모드") || lowerText.contains("오프라인 모드")) {
            edgeDelegationRouter?.enableForcedEdge()
            scope.launch {
                eventBus.publish(
                    com.xreal.nativear.core.XRealEvent.ActionRequest.SpeakTTS(
                        "엣지 모드 활성화. Gemma E2B 로딩 중."
                    )
                )
            }
            callback?.onGeminiResponse("엣지 모드 활성화 — Gemma E2B (긴급 AI) 로딩 중")
            return
        }
        if (lowerText.contains("서버 모드") || lowerText.contains("온라인 모드")) {
            edgeDelegationRouter?.disableForcedEdge()
            scope.launch {
                eventBus.publish(
                    com.xreal.nativear.core.XRealEvent.ActionRequest.SpeakTTS("서버 AI 모드 복귀")
                )
            }
            callback?.onGeminiResponse("서버 AI 모드 복귀")
            return
        }

        // Budget gate: check before AI call
        tokenBudgetTracker?.let { tracker ->
            val check = tracker.checkBudget(ProviderId.GEMINI, estimatedTokens = 1500)
            if (!check.allowed) {
                Log.w(TAG, "processWithGemini blocked by budget: ${check.reason}")
                // ★ P1-4: 사용자 음성 명령 → 차단 대신 NOD/SHAKE 확인 요청
                pendingBudgetQuery = userText
                pendingBudgetContext = externalContext
                scope.launch {
                    eventBus.publish(com.xreal.nativear.core.XRealEvent.ActionRequest.SpeakTTS(
                        "토큰 예산이 초과되었습니다. 계속 진행하려면 끄덕여주세요.",
                        important = true
                    ))
                    eventBus.publish(com.xreal.nativear.core.XRealEvent.ActionRequest.ShowMessage(
                        "⚠️ 예산 초과 (${check.tier.displayName}) — NOD: 진행 / SHAKE: 취소"
                    ))
                }
                return
            }
        }

        scope.launch {
            isGeminiBusy = true

            // ★ Phase K: PersonaManager 10레이어 컨텍스트 주입 (예산 MINIMAL/BLOCKED 시 스킵)
            val budgetForContext = tokenBudgetTracker?.getBudgetTier(ProviderId.GEMINI)
            val enrichedSystemPrompt = if (
                budgetForContext != com.xreal.nativear.router.persona.BudgetTier.MINIMAL &&
                budgetForContext != com.xreal.nativear.router.persona.BudgetTier.BLOCKED
            ) {
                try {
                    val addendum = personaManager?.buildContextAddendum() ?: ""
                    if (addendum.isNotBlank()) "${GeminiPrompts.SYSTEM_INSTRUCTION}\n\n$addendum"
                    else GeminiPrompts.SYSTEM_INSTRUCTION
                } catch (e: Exception) {
                    Log.w(TAG, "PersonaManager 컨텍스트 주입 실패, 기본 사용: ${e.message}")
                    GeminiPrompts.SYSTEM_INSTRUCTION
                }
            } else {
                GeminiPrompts.SYSTEM_INSTRUCTION  // 예산 절약 모드
            }

            // ★ Phase C: 세션 음성 명령 처리 (AI 호출 전 선처리)
            val trimmed = userText.trim()
            when {
                // ★ Phase H: 피드백 세션 트리거
                trimmed.contains("피드백 시작") || trimmed.contains("오늘 리뷰") || trimmed.contains("피드백 세션") -> {
                    val feedbackManager = try {
                        org.koin.java.KoinJavaComponent.getKoin()
                            .getOrNull<com.xreal.nativear.session.FeedbackSessionManager>()
                    } catch (e: Exception) { null }
                    if (feedbackManager != null && !feedbackManager.isSessionActive()) {
                        feedbackManager.startFeedbackSession()
                    } else {
                        callback?.onGeminiResponse("피드백 세션이 이미 진행 중입니다.")
                    }
                    isGeminiBusy = false
                    return@launch
                }
                trimmed.startsWith("세션 시작") || trimmed.startsWith("새 세션") -> {
                    val situation = trimmed.removePrefix("세션 시작").removePrefix("새 세션").trim()
                        .takeIf { it.isNotEmpty() }
                    lifeSessionManager?.startSession(situation)
                    // ★ Phase L: 새 세션 시작 시 대화 버퍼 초기화
                    synchronized(sessionConversationBuffer) { sessionConversationBuffer.clear() }
                    Log.d(TAG, "대화 버퍼 초기화 (새 세션 시작)")
                    isGeminiBusy = false
                    return@launch
                }
                trimmed == "세션 종료" || trimmed == "세션 끝" -> {
                    lifeSessionManager?.endSession(generateSummary = true)
                    // ★ Phase L: 세션 종료 시 대화 버퍼 초기화
                    synchronized(sessionConversationBuffer) { sessionConversationBuffer.clear() }
                    Log.d(TAG, "대화 버퍼 초기화 (세션 종료)")
                    callback?.onGeminiResponse("세션을 종료했습니다.")
                    isGeminiBusy = false
                    return@launch
                }
                trimmed == "세션 요약" -> {
                    val prompt = lifeSessionManager?.getSessionSummaryPrompt()
                    if (prompt != null) {
                        // 재귀 호출로 AI 요약 요청
                        isGeminiBusy = false
                        processWithGemini(prompt, externalContext)
                        return@launch
                    } else {
                        callback?.onGeminiResponse("현재 활성 세션이 없습니다.")
                        isGeminiBusy = false
                        return@launch
                    }
                }
            }

            // ★ GoalOrientedAgentLoop: 목표 지향 심층 추론 라우팅
            if (shouldUseDeepReasoning(trimmed)) {
                Log.i(TAG, "Deep reasoning 라우팅: '$trimmed'")
                try {
                    val result = goalAgentLoop?.pursue(
                        goal = trimmed,
                        agentId = "general_assistant",
                        context = externalContext ?: ""
                    )
                    if (result != null) {
                        callback?.onGeminiResponse(result.answer)
                        isGeminiBusy = false
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Deep reasoning 실패, 일반 처리로 폴백: ${e.message}")
                }
            }

            // ★ Phase J: 고복잡도 쿼리 → 멀티-AI 자동 라우팅
            if (shouldUseMultiAI(trimmed)) {
                Log.i(TAG, "Multi-AI 라우팅: '$trimmed'")
                isGeminiBusy = false
                processWithMultiplePersonas(userText, context = externalContext)
                return@launch
            }

            // ─── EdgeDelegationRouter: 서버 vs 엣지 결정
            val resolvedPersonaId = edgeDelegationRouter?.resolvePersonaId(
                query = userText,
                defaultServerPersonaId = "gemini"
            )

            // 엣지 AI로 라우팅된 경우 직접 처리
            if (resolvedPersonaId != null && resolvedPersonaId.startsWith("edge_")) {
                Log.i(TAG, "엣지 AI 라우팅: $resolvedPersonaId")
                val edgeProvider = edgeProviders?.get(resolvedPersonaId)
                if (edgeProvider != null) {
                    callback?.onCentralMessage("🔋 엣지 AI 처리 중...")
                    try {
                        val loc = locationService.getCurrentLocation()
                        val contextInfo = "위치: ${loc?.latitude?.let { "%.4f".format(it) } ?: "불명"}, ${loc?.longitude?.let { "%.4f".format(it) } ?: "불명"}"
                        val fullPrompt = if (externalContext != null) "$externalContext\n\n$contextInfo\n\n$userText" else "$contextInfo\n\n$userText"
                        val messages = listOf(AIMessage(role = "user", content = fullPrompt))
                        val response = edgeProvider.sendMessage(
                            messages = messages,
                            systemPrompt = "당신은 XREAL AR 안경을 위한 AI 어시스턴트입니다. 간결하게 한국어로 답하세요."
                        )
                        val reply = "[엣지] ${response.text ?: "[엣지 응답 없음]"}"
                        memoryStore.save(reply, "EDGE_AI")
                        callback?.onGeminiResponse(reply)
                        // important=true: 사용자 명령에 대한 응답 → 조용히 모드에서도 출력
                        eventBus.publish(com.xreal.nativear.core.XRealEvent.ActionRequest.SpeakTTS(reply, important = true))
                    } catch (e: Exception) {
                        Log.e(TAG, "엣지 AI 처리 실패: ${e.message}")
                        eventBus.publish(com.xreal.nativear.core.XRealEvent.SystemEvent.Error(
                            code = "EDGE_AI_ERROR",
                            message = "엣지 AI 처리 실패: ${e.message?.take(100)}",
                            throwable = e
                        ))
                        callback?.onGeminiResponse("[엣지 AI 오류: ${e.message}]")
                    }
                    isGeminiBusy = false
                    return@launch
                }
            }

            callback?.onCentralMessage("Thinking...")

            // Context Building
            val loc = locationService.getCurrentLocation()
            val contextInfo = "Current Location: ${loc?.latitude ?: "Unknown"}, ${loc?.longitude ?: "Unknown"}"
            val fullPrompt = if (externalContext != null) {
                "$externalContext\n\nContext: $contextInfo\nUser: $userText"
            } else {
                "$contextInfo\n\nUser: $userText"
            }
            memoryStore.save(userText, "USER")

            // ★ Phase L: 예산별 히스토리 턴 수 결정 후 messages 구성 (히스토리 + 현재 메시지)
            val maxHistoryTurns = when (tokenBudgetTracker?.getBudgetTier(ProviderId.GEMINI)) {
                com.xreal.nativear.router.persona.BudgetTier.NORMAL -> MAX_CONVERSATION_TURNS
                com.xreal.nativear.router.persona.BudgetTier.CAREFUL -> 2
                else -> 0  // MINIMAL / BLOCKED: 히스토리 스킵 (토큰 절약)
            }
            val messages = mutableListOf<AIMessage>()
            if (maxHistoryTurns > 0) {
                synchronized(sessionConversationBuffer) {
                    sessionConversationBuffer.takeLast(maxHistoryTurns).forEach { (userMsg, assistantMsg) ->
                        messages.add(userMsg)
                        messages.add(assistantMsg)
                    }
                }
            }
            messages.add(AIMessage(role = "user", content = fullPrompt))

            // ★ AIResourceRegistry 통합 라우팅: 리모트($0) → 서버($) → 엣지($0) 자동 cascade
            // tool calling이 필요한 경우 hasToolCalling=true 프로바이더 우선,
            // 리모트 LLM(tool 미지원)도 tools 없이 후보에 포함됨
            var actualTierLabel = "[서버]"
            var response: AIResponse
            var routedProviderId: ProviderId? = null

            val registry = aiRegistry
            if (registry != null) {
                // 1차: tools 없이 전체 프로바이더 cascade (리모트 포함)
                val routeResult = registry.routeText(
                    messages = messages,
                    systemPrompt = enrichedSystemPrompt,
                    tools = emptyList(),  // 리모트 LLM도 후보에 포함
                    isEssential = true
                )
                if (routeResult != null) {
                    response = routeResult.response
                    actualTierLabel = routeResult.label
                    routedProviderId = routeResult.providerId
                    callback?.onCentralMessage("${actualTierLabel} Thinking...")

                    // 리모트/엣지가 응답했지만 tool calling이 필요한 경우 → Gemini로 재시도
                    if (response.toolCalls.isEmpty() && toolDefs.isNotEmpty() &&
                        routeResult.tier != com.xreal.nativear.ai.AIResourceRegistry.ProviderTier.CLOUD) {
                        // 리모트/엣지 응답이 유효하면 그대로 사용 (tool 불필요한 단순 질문)
                        // tool calling은 Cloud tier에서만 지원됨
                    }
                } else {
                    // registry 모든 프로바이더 실패
                    Log.w(TAG, "processWithGemini: AIResourceRegistry 모든 프로바이더 실패")
                    callback?.onGeminiResponse("모든 AI 연결 실패. 잠시 후 다시 시도해 주세요.")
                    eventBus.publish(com.xreal.nativear.core.XRealEvent.ActionRequest.SpeakTTS(
                        "AI 연결 실패. 잠시 후 다시 시도해 주세요.", important = true
                    ))
                    isGeminiBusy = false
                    return@launch
                }
            } else {
                // Registry 미초기화 — 불가능하지만 안전 처리
                Log.e(TAG, "processWithGemini: AIResourceRegistry 미초기화 — 불가능 상태")
                callback?.onGeminiResponse("AI 시스템 초기화 중. 잠시 후 다시 시도해 주세요.")
                isGeminiBusy = false
                return@launch
            }

            var hops = 0

            // ★ 도구 호출 루프 — 각 프로바이더 API 규격에 맞는 구조화 포맷 사용
            while (response.toolCalls.isNotEmpty() && hops < 5) {
                hops++

                // assistant 메시지에 pendingToolCalls 첨부 (OpenAI/Claude 규격)
                messages.add(AIMessage(
                    role = "assistant",
                    content = response.text ?: "",
                    pendingToolCalls = response.toolCalls
                ))

                for (call in response.toolCalls) {
                    val result = handleToolCall(call.name, call.arguments)

                    // 메모리 쿼리 결과는 UI에도 전달
                    if (call.name.startsWith("query_")) {
                        callback?.onSearchResults(result)
                    }

                    // tool 역할 메시지 — toolCallId + toolName 포함 (OpenAI/Claude 연결용)
                    messages.add(AIMessage(
                        role = "tool",
                        content = result,
                        toolCallId = call.id,
                        toolName = call.name
                    ))
                }

                // ★ 도구 루프에서도 AIResourceRegistry 통합 라우팅 사용
                val toolRouteResult = registry?.routeText(
                    messages = messages,
                    systemPrompt = enrichedSystemPrompt,
                    tools = toolDefs,
                    isEssential = true
                )
                if (toolRouteResult != null) {
                    response = toolRouteResult.response
                    actualTierLabel = toolRouteResult.label
                } else {
                    Log.w(TAG, "도구 루프 AI 호출 실패 — 루프 중단")
                    break
                }
            }

            val rawReply = response.text
                ?: if (hops > 0) "I've processed your request."
                else GeminiPrompts.getClarificationPrompt(userText)

            // ★ 티어 라벨 추가 — 사용자가 어떤 AI가 응답했는지 식별
            val reply = "$actualTierLabel $rawReply"

            // Record token usage (rough estimate: ~4 chars per token)
            tokenBudgetTracker?.recordUsage(routedProviderId ?: ProviderId.GEMINI, (rawReply.length / 4).coerceAtLeast(100))

            memoryStore.save(reply, "AI")

            // ★ Phase L: 대화 교환 버퍼에 저장 (trimmed=순수 질문, reply=AI 답변)
            synchronized(sessionConversationBuffer) {
                sessionConversationBuffer.addLast(
                    AIMessage(role = "user", content = trimmed.take(400)) to
                    AIMessage(role = "assistant", content = reply.take(400))
                )
                while (sessionConversationBuffer.size > MAX_CONVERSATION_TURNS) {
                    sessionConversationBuffer.removeFirst()
                }
            }

            callback?.onGeminiResponse(reply)
            // important=true: 사용자 직접 명령에 대한 AI 응답 → 조용히 모드에서도 출력
            eventBus.publish(com.xreal.nativear.core.XRealEvent.ActionRequest.SpeakTTS(reply, important = true))

            isGeminiBusy = false
        }
    }

    /**
     * Process a query through multiple AI personas in parallel.
     * Used for complex analysis that benefits from multiple perspectives.
     */
    fun processWithMultiplePersonas(
        userText: String,
        personaIds: List<String>? = null,
        context: String? = null
    ) {
        if (isGeminiBusy) return

        scope.launch {
            isGeminiBusy = true
            callback?.onCentralMessage("Multi-AI 분석 중...")

            // ★ Phase K: Multi-AI HUD 배지 표시
            eventBus.publish(com.xreal.nativear.core.XRealEvent.ActionRequest.DrawingCommand(
                DrawCommand.Add(DrawElement.Text(
                    id = "multiAI_badge",
                    x = 90f, y = 4f,
                    text = "🤝 멀티AI",
                    size = 12f,
                    color = "#00FFFF",
                    opacity = 0.85f
                ))
            ))

            try {
                // ★ Phase J: 예산 기반 페르소나 수 제어
                val budgetTier = tokenBudgetTracker?.getBudgetTier(ProviderId.GEMINI)
                val maxPersonas = if (budgetTier == com.xreal.nativear.router.persona.BudgetTier.CAREFUL) 2 else null

                // Use PersonaRouter for smart persona selection when no explicit IDs given
                val targetIds = (personaIds ?: personaRouter?.route(
                    query = userText,
                    triggerSource = "user"
                )?.selectedPersonaIds)
                    ?.let { ids -> if (maxPersonas != null) ids.take(maxPersonas) else ids }

                val result = multiOrchestrator?.dispatchParallel(
                    query = userText,
                    personaIds = targetIds,
                    context = context
                )

                if (result != null) {
                    val reply = result.synthesizedText ?: "분석 결과 없음"
                    memoryStore.save(reply, "MULTI_AI")
                    callback?.onGeminiResponse(reply)
                    eventBus.publish(com.xreal.nativear.core.XRealEvent.ActionRequest.SpeakTTS(reply, important = true))
                    // ★ Phase K: Multi-AI HUD 배지 제거
                    eventBus.publish(com.xreal.nativear.core.XRealEvent.ActionRequest.DrawingCommand(
                        DrawCommand.Remove("multiAI_badge")
                    ))
                    Log.i(TAG, "Multi-AI result: ${result.personaResponses.size} personas, " +
                        "consensus=${String.format("%.2f", result.consensusLevel)}, " +
                        "mode=${result.synthesisMode}, latency=${result.totalLatencyMs}ms")

                    // ★ Phase L: Multi-AI 응답을 대화 버퍼에 저장 (다음 단일 AI 호출에서 참조 가능)
                    synchronized(sessionConversationBuffer) {
                        sessionConversationBuffer.addLast(
                            AIMessage(role = "user", content = userText.take(400)) to
                            AIMessage(role = "assistant", content = reply.take(400))
                        )
                        while (sessionConversationBuffer.size > MAX_CONVERSATION_TURNS) {
                            sessionConversationBuffer.removeFirst()
                        }
                    }

                    // ★ Phase J: 세션 저장 + 피드백 대기 등록
                    val sessionId = saveMultiAISession(userText, result)
                    lastMultiAISessionId = sessionId
                    lastMultiAIPersonaIds = result.personaResponses.keys.toList()
                }
            } catch (e: Exception) {
                ErrorReporter.report(TAG, "Multi-AI failed", e)
                callback?.onGeminiResponse("Multi-AI Error: ${e.message}")
                // ★ Phase K: Multi-AI HUD 배지 제거 (에러 시)
                eventBus.publish(com.xreal.nativear.core.XRealEvent.ActionRequest.DrawingCommand(
                    DrawCommand.Remove("multiAI_badge")
                ))
            }

            isGeminiBusy = false
        }
    }

    /**
     * ★ Phase J: 멀티-AI 세션을 DB에 저장. 가치 마이닝용.
     */
    private fun saveMultiAISession(
        query: String,
        result: com.xreal.nativear.ai.AggregatedResponse
    ): Long {
        return try {
            val db = org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<UnifiedMemoryDatabase>() ?: return -1L
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date())
            val personaIdsJson = org.json.JSONArray(result.personaResponses.keys.toList()).toString()
            val tokenCost = result.personaResponses.values
                .sumOf { (it.usage?.totalTokens ?: 0).toLong() }.toFloat() * 0.000003f

            db.insertMultiAISession(UnifiedMemoryDatabase.MultiAISession(
                sessionDate = today,
                queryText = query.take(200),
                personaIds = personaIdsJson,
                consensusLevel = result.consensusLevel,
                synthesisMode = result.synthesisMode,
                synthesizedText = result.synthesizedText?.take(500),
                totalLatencyMs = result.totalLatencyMs,
                tokenCostUsd = tokenCost
            ))
        } catch (e: Exception) {
            Log.w(TAG, "멀티-AI 세션 저장 실패: ${e.message}")
            -1L
        }
    }

    /**
     * ★ Phase J: shouldUseMultiAI — 고복잡도 쿼리 판별기.
     */
    private fun shouldUseMultiAI(text: String): Boolean {
        // FeedbackSession 중이면 멀티-AI 스킵
        val feedbackManager = try {
            org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.session.FeedbackSessionManager>()
        } catch (e: Exception) { null }
        if (feedbackManager?.isSessionActive() == true) return false

        // 예산 MINIMAL/BLOCKED → 단일 AI 폴백
        val budget = tokenBudgetTracker?.getBudgetTier(ProviderId.GEMINI)
        if (budget == com.xreal.nativear.router.persona.BudgetTier.MINIMAL ||
            budget == com.xreal.nativear.router.persona.BudgetTier.BLOCKED) return false

        val lower = text.lowercase()
        val decisionKeywords = listOf("결정", "선택", "어떻게", "뭐가 좋", "어느게", "조언", "추천", "판단", "어떤게")
        val safetyKeywords = listOf("아파", "증상", "위험", "안전", "건강", "치료", "병원", "응급")
        val complexityKeywords = listOf("비교", "장단점", "분석", "전략", "계획", "왜", "이유")
        return decisionKeywords.any { lower.contains(it) } ||
               safetyKeywords.any { lower.contains(it) } ||
               complexityKeywords.any { lower.contains(it) }
    }

    /**
     * ★ GoalOrientedAgentLoop: 목표 지향 심층 추론이 필요한 쿼리 판별.
     * 단순 질문이 아닌, 다단계 추론/조사/계획이 필요한 요청을 감지.
     */
    private fun shouldUseDeepReasoning(text: String): Boolean {
        if (goalAgentLoop == null) return false

        // 예산 부족 시 스킵
        val budget = tokenBudgetTracker?.getBudgetTier(ProviderId.GEMINI)
        if (budget == com.xreal.nativear.router.persona.BudgetTier.MINIMAL ||
            budget == com.xreal.nativear.router.persona.BudgetTier.BLOCKED) return false

        val lower = text.lowercase()
        // 목표 지향 키워드: 다단계 추론/조사/실행이 필요한 요청
        val goalKeywords = listOf(
            "찾아줘", "조사해", "알아봐", "해결해", "만들어", "정리해",
            "검색해서", "단계별", "순서대로", "방법 찾아",
            "~까지", "목표", "달성", "실행"
        )
        // 최소 길이 threshold: 짧은 질문은 단순 처리
        val COMPLEX_QUERY_THRESHOLD: Int =
            com.xreal.nativear.policy.PolicyReader.getInt("agent.complex_query_threshold", 30)
        return text.length >= COMPLEX_QUERY_THRESHOLD && goalKeywords.any { lower.contains(it) }
    }

    /**
     * ★ Phase J: EventBus 구독 시작 — VoiceFeedback → Multi-AI hit rate 피드백 루프.
     */
    fun start() {
        scope.launch {
            eventBus.events.collect { event ->
                try {
                    when (event) {
                        is com.xreal.nativear.core.XRealEvent.InputEvent.VoiceFeedback -> {
                            // ★ Phase J: 멀티-AI 세션 피드백 기록
                            val sessionId = lastMultiAISessionId
                            val personaIds = lastMultiAIPersonaIds
                            if (sessionId > 0 && personaIds != null) {
                                val wasPositive = event.sentiment == com.xreal.nativear.core.FeedbackSentiment.POSITIVE
                                val wasHelpful = if (wasPositive) 1 else 0
                                try {
                                    val db = org.koin.java.KoinJavaComponent.getKoin()
                                        .getOrNull<UnifiedMemoryDatabase>()
                                    db?.updateMultiAISessionHelpful(sessionId, wasHelpful)
                                } catch (e: Exception) {
                                    Log.w(TAG, "멀티-AI 피드백 DB 업데이트 실패: ${e.message}")
                                }
                                personaIds.forEach { personaId ->
                                    multiOrchestrator?.recordPredictionResult(personaId, wasPositive)
                                }
                                Log.d(TAG, "Multi-AI 피드백 기록: sessionId=$sessionId, helpful=$wasHelpful, personas=$personaIds")
                                lastMultiAIPersonaIds = null
                                lastMultiAISessionId = -1L
                            }
                        }
                        else -> { /* 다른 이벤트 무시 */ }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "AIAgentManager 이벤트 처리 오류: ${e.message}")
                }
            }
        }
    }

    /**
     * ★ P1-4: 예산 초과 사용자 확인 제스처 처리.
     * @return true if a pending budget query was handled
     */
    fun handleBudgetOverrideGesture(approved: Boolean): Boolean {
        val query = pendingBudgetQuery ?: return false
        val ctx = pendingBudgetContext
        pendingBudgetQuery = null
        pendingBudgetContext = null

        if (approved) {
            tokenBudgetTracker?.grantTemporaryOverride(60_000L)
            processWithGemini(query, ctx)  // 오버라이드 활성 상태로 재시도
        } else {
            callback?.onGeminiResponse("예산 초과 — 요청이 취소되었습니다.")
            scope.launch {
                eventBus.publish(com.xreal.nativear.core.XRealEvent.ActionRequest.SpeakTTS(
                    "요청이 취소되었습니다.", important = true
                ))
            }
        }
        return true
    }

    private suspend fun handleToolCall(name: String, args: Map<String, Any?>): String {
        Log.i(TAG, "Dispatching Tool: $name")
        val result = toolExecutorRegistry.execute(name, args)
        if (!result.success) {
            Log.w(TAG, "Tool $name failed: ${result.data}")
        }
        return result.data
    }


    /**
     * 씬 해석 — 통합 AI 라우팅: 리모트($0) → 서버(Gemini Vision) → 엣지(E2B/1B)
     *
     * processAIQuery()와 동일한 우선순위 정책 적용:
     * 1. 리모트 LLM (gemma-3-12b, $0, 비전 지원) — 가용 시 최우선
     * 2. 서버 AI (Gemini Vision, $) — 리모트 실패/불가용 시
     * 3. 엣지 AI (E2B 멀티모달 or 1B 텍스트) — 모든 클라우드 실패 시
     */
    fun interpretScene(bitmap: Bitmap, ocrText: String) {
        callback?.showSnapshotFeedback()
        callback?.onCentralMessage("Interpreting scene...")
        Log.i(TAG, "interpretScene called (bitmap: ${bitmap.width}x${bitmap.height}, ocrText: ${ocrText.take(30)})")

        scope.launch {
            try {
                val prompt = GeminiPrompts.getSceneInterpretationPrompt(ocrText)
                val visionSystemPrompt = "AR 안경 AI. 장면을 한국어 한 문장으로 설명. 질문 금지."
                val ocrHint = ocrText.takeIf { it.isNotBlank() && it != "Instant Snapshot" }

                // ★ AIResourceRegistry 통합 라우팅: 리모트($0) → 서버($) → 엣지($0) 자동 cascade
                val registry = aiRegistry
                if (registry != null) {
                    val result = registry.routeVision(
                        bitmap = bitmap,
                        textPrompt = prompt,
                        systemPrompt = visionSystemPrompt,
                        ocrHint = ocrHint
                    )
                    if (result != null) {
                        val reply = result.response.text?.trim() ?: ""
                        Log.i(TAG, "interpretScene: ${result.label} 성공 (${result.latencyMs}ms): ${reply.take(60)}")
                        saveScenesMemory(reply)
                        callback?.onGeminiResponse("${result.label} Scene: $reply")
                        eventBus.publish(com.xreal.nativear.core.XRealEvent.ActionRequest.SpeakTTS(reply, important = true))
                        return@launch
                    }
                    Log.w(TAG, "interpretScene: AIResourceRegistry 모든 프로바이더 실패")
                } else {
                    // Registry 미초기화 — 불가능하지만 안전 처리
                    Log.e(TAG, "interpretScene: AIResourceRegistry 미초기화")
                }

                Log.d(TAG, "interpretScene: 모든 AI 경로 실패")
            } catch (e: Exception) {
                ErrorReporter.report(TAG, "interpretScene failed", e)
                callback?.onGeminiResponse("Scene Error: ${e.message}")
            }
        }
    }

    private fun saveScenesMemory(reply: String) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            memoryStore.save(reply, "CAMERA", "{\"trigger\":\"MANUAL_STABILITY\"}")
        }
    }

    fun processDetections(results: List<Detection>) {
        if (results.isEmpty()) return

        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val currentTime = System.currentTimeMillis()

            for (detection in results) {
                if (detection.confidence > 0.70f) {
                    val lastIndexed = recentlyIndexedObjects[detection.label] ?: 0L
                    if (currentTime - lastIndexed > INDEX_THROTTLE_MS) {
                        Log.i(TAG, "indexing Detection: ${detection.label}")
                        recentlyIndexedObjects[detection.label] = currentTime
                        // H1 FIX: Evict oldest when exceeding LRU cap
                        while (recentlyIndexedObjects.size > MAX_INDEXED_OBJECTS) {
                            val oldest = recentlyIndexedObjects.entries.firstOrNull() ?: break
                            recentlyIndexedObjects.remove(oldest.key)
                        }

                        val metadata = org.json.JSONObject().apply {
                            put("confidence", detection.confidence)
                            put("x", detection.x)
                            put("y", detection.y)
                            put("trigger", "AUTO_DETECTION")
                        }.toString()

                        memoryStore.save(
                            content = "I see a ${detection.label}.",
                            role = "CAMERA",
                            metadata = metadata
                        )
                    }
                }
            }
        }
    }
}
