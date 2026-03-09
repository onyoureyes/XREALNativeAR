package com.xreal.nativear.ai

import android.util.Log

/**
 * PersonaManager: Builds enriched system prompts for each AI persona.
 *
 * Prompt injection layers (Architecture Principles 2, 4, 5):
 * 1. Base system prompt (persona identity)
 * 2. [YOUR IDENTITY] — Agent personality, growth stage, memories (Phase 16)
 * 3. [FAMILIARITY CONTEXT] — Current scene familiarity levels (Phase 12)
 * 4. [Recent memories, insights, hit rate] — Persona-specific history
 * 5. [Strategist directives] — Meta-strategic instructions
 *
 * This ensures every AI call is context-rich and avoids repeating known info.
 */
class PersonaManager(
    private val personaMemoryService: PersonaMemoryService,
    private val providers: Map<ProviderId, IAIProvider>,
    private val directiveStore: com.xreal.nativear.strategist.DirectiveStore? = null,
    private val familiarityEngine: com.xreal.nativear.companion.FamiliarityEngine? = null,
    private val agentEvolution: com.xreal.nativear.companion.AgentPersonalityEvolution? = null,
    private val userProfileManager: com.xreal.nativear.meeting.UserProfileManager? = null,
    // ★ Phase 19: 팀장 승인된 동적 프롬프트/도구 추가사항 (ExpertDynamicProfileStore)
    private val dynamicProfileStore: com.xreal.nativear.expert.ExpertDynamicProfileStore? = null,
    // ★ Phase H: 사용자 성향 DNA (UserDNAManager)
    private val userDnaManager: com.xreal.nativear.profile.UserDNAManager? = null
) : IPersonaService {
    private val TAG = "PersonaManager"
    private val personas = mutableMapOf<String, Persona>()

    init {
        PredefinedPersonas.getAll().forEach { registerPersona(it) }
    }

    override fun registerPersona(persona: Persona) {
        personas[persona.id] = persona
        Log.i(TAG, "Registered persona: ${persona.id} (${persona.providerId})")
    }

    override fun getPersona(id: String): Persona? = personas[id]
    override fun getAllPersonas(): List<Persona> = personas.values.toList()
    override fun getEnabledPersonas(): List<Persona> = personas.values.filter {
        it.isEnabled && providers[it.providerId]?.isAvailable == true
    }

    /**
     * Build the full system prompt for a persona by injecting:
     * 1. Base system prompt
     * 2. Agent personality (Phase 16: identity, traits, memories, growth stage)
     * 3. Familiarity context (Phase 12: what entities user knows, insight depth)
     * 4. Recent persona-specific memories
     * 5. Recent persona-specific insights/summaries
     * 6. Hit rate stats for self-awareness
     * 7. Strategist directives
     * 7b. ★ Phase 19: 팀장 승인된 동적 프롬프트 추가사항 (ExpertDynamicProfileStore)
     * 7c. ★ Phase 19: 동적 도구 권한 안내 (임시 접근 허가 도구 목록)
     */
    override suspend fun buildPromptForPersona(personaId: String): String {
        val persona = personas[personaId] ?: return ""

        val recentMemories = personaMemoryService.getRecentMemories(personaId, limit = 10)
        val insights = personaMemoryService.getInsights(personaId, limit = 5)
        val hitRate = personaMemoryService.getHitRate(personaId)

        return buildString {
            // 1. Base system prompt
            appendLine(persona.systemPrompt)
            appendLine()

            // 1b. User profile injection (Meeting Assistant: occupation, role context)
            injectUserProfile(this)

            // 2. Agent personality injection (Phase 16)
            injectAgentPersonality(this, personaId)

            // 3. Familiarity context injection (Phase 12)
            injectFamiliarityContext(this)

            // 3.5 ★ Phase C: 현재 세션 컨텍스트 주입
            try {
                val sessionManager = org.koin.java.KoinJavaComponent.getKoin()
                    .getOrNull<com.xreal.nativear.session.LifeSessionManager>()
                sessionManager?.currentSession?.let { session ->
                    val elapsed = session.durationMinutes
                    val situationText = session.situation?.let { ", 상황: $it" } ?: ""
                    appendLine("[현재 세션: ${elapsed}분째 진행 중${situationText}]")
                    appendLine()
                }
            } catch (_: Exception) {}

            // 4. Recent memories
            if (recentMemories.isNotEmpty()) {
                appendLine("[최근 내 기억]")
                recentMemories.forEach { mem ->
                    appendLine("- ${mem.content}")
                }
                appendLine()
            }

            // 5. Insights
            if (insights.isNotEmpty()) {
                appendLine("[내 인사이트]")
                insights.forEach { insight ->
                    appendLine("- ${insight.content}")
                }
                appendLine()
            }

            // 6. Hit rate
            if (hitRate != null) {
                appendLine("[내 예측 정확도: ${String.format("%.0f", hitRate * 100)}%]")
                appendLine()
            }

            // 7. Strategist directives
            directiveStore?.getDirectivesForPersona(personaId)?.let { directives ->
                if (directives.isNotEmpty()) {
                    appendLine("[전략가 지시사항]")
                    directives.forEach { d ->
                        appendLine("- ${d.instruction} (신뢰도: ${String.format("%.0f", d.confidence * 100)}%)")
                    }
                    appendLine()
                }
            }

            // 7b. ★ Phase 19: 팀장 승인된 동적 프롬프트 추가사항
            dynamicProfileStore?.getDynamicPromptAdditions(personaId)?.let { additions ->
                if (additions.isNotEmpty()) {
                    appendLine("[팀장 승인 추가 지침]")
                    additions.forEach { addition -> appendLine("- $addition") }
                    appendLine()
                }
            }

            // 7c. ★ Phase 19: 동적 도구 권한 안내 (임시 접근 허가 도구 목록)
            dynamicProfileStore?.getDynamicTools(personaId)?.let { tools ->
                if (tools.isNotEmpty()) {
                    appendLine("[임시 접근 허가 도구: ${tools.joinToString(", ")}]")
                    appendLine("위 도구는 팀장이 임시로 허가한 도구입니다. 현재 목적 수행에 적극 활용하세요.")
                    appendLine()
                }
            }

            // 7d. ★ Gap D: 도구 성능 데이터 주입 (PersonaManager Layer 7d)
            injectToolPerformance(this, personaId)

            // 8. ★ Phase H: 사용자 성향 DNA (UserDNAManager 10번째 레이어)
            userDnaManager?.buildDNAPromptLayer()?.let { dnaLayer ->
                appendLine(dnaLayer)
                appendLine()
            }

            // 9. Digital Twin 생리 프로파일 (PC 서버 예측 엔진)
            injectDigitalTwinPhysiology(this)
        }
    }

    /**
     * ★ Phase K: 단일 AI 호출용 컨텍스트 추가분.
     * GeminiPrompts.SYSTEM_INSTRUCTION에 append하여 사용.
     * Base system prompt 없음 — 컨텍스트 레이어(프로필, 성격, 친숙도, 세션, 기억, 지시사항, DNA)만 반환.
     */
    override suspend fun buildContextAddendum(personaId: String): String {
        val recentMemories = try {
            personaMemoryService.getRecentMemories(personaId, limit = 5)
        } catch (_: Exception) { emptyList() }
        val insights = try {
            personaMemoryService.getInsights(personaId, limit = 3)
        } catch (_: Exception) { emptyList() }
        val hitRate = try {
            personaMemoryService.getHitRate(personaId)
        } catch (_: Exception) { null }

        return buildString {
            // 사용자 프로필 (직업, 역할)
            injectUserProfile(this)
            // 에이전트 성격 + 성장 단계 (Phase 16)
            injectAgentPersonality(this, personaId)
            // 친숙도 컨텍스트 (Phase 12)
            injectFamiliarityContext(this)
            // 현재 세션 컨텍스트 (Phase C)
            try {
                val sessionManager = org.koin.java.KoinJavaComponent.getKoin()
                    .getOrNull<com.xreal.nativear.session.LifeSessionManager>()
                sessionManager?.currentSession?.let { session ->
                    val elapsed = session.durationMinutes
                    val situationText = session.situation?.let { ", 상황: $it" } ?: ""
                    appendLine("[현재 세션: ${elapsed}분째 진행 중${situationText}]")
                    appendLine()
                }
            } catch (_: Exception) {}
            // 최근 기억 (Phase I 중요도×최신성 정렬)
            if (recentMemories.isNotEmpty()) {
                appendLine("[최근 내 기억]")
                recentMemories.forEach { mem -> appendLine("- ${mem.content.take(100)}") }
                appendLine()
            }
            // 인사이트/요약
            if (insights.isNotEmpty()) {
                appendLine("[내 인사이트]")
                insights.forEach { ins -> appendLine("- ${ins.content.take(80)}") }
                appendLine()
            }
            // 예측 정확도
            if (hitRate != null) {
                appendLine("[내 예측 정확도: ${String.format("%.0f", hitRate * 100)}%]")
                appendLine()
            }
            // Strategist 지시사항 — 페르소나별 우선, 없으면 전체 활성 지시사항
            val allDirectives = directiveStore?.getDirectivesForPersona(personaId)
                ?.takeIf { it.isNotEmpty() }
                ?: directiveStore?.getAllActiveDirectives()
            allDirectives?.let { directives ->
                if (directives.isNotEmpty()) {
                    appendLine("[전략가 지시사항]")
                    directives.take(4).forEach { d -> appendLine("- ${d.instruction}") }
                    appendLine()
                }
            }
            // UserDNA 성향 (Phase H)
            userDnaManager?.buildDNAPromptLayer()?.let { dna ->
                if (dna.isNotBlank()) { appendLine(dna); appendLine() }
            }
            // Digital Twin 생리 프로파일
            injectDigitalTwinPhysiology(this)
        }.trim()
    }

    /**
     * Inject user profile (occupation, role) into prompt.
     * Helps AI understand user's professional context for relevant responses.
     */
    private fun injectUserProfile(builder: StringBuilder) {
        val profileManager = userProfileManager ?: return
        if (!profileManager.isProfileConfigured()) return

        val summary = profileManager.getProfileSummary()
        builder.appendLine(summary)
        builder.appendLine()
    }

    /**
     * Inject agent personality context from AgentPersonalityEvolution.
     *
     * Adds [YOUR IDENTITY] block with:
     * - Agent name, core traits, evolved traits
     * - Growth stage (NEWBORN → SAGE)
     * - User trust score
     * - Recent agent memories (successes, failures, reflections)
     * - Growth stage guidance
     */
    private fun injectAgentPersonality(builder: StringBuilder, personaId: String) {
        val evolution = agentEvolution ?: return
        val personality = evolution.buildPersonalityPrompt(personaId)
        if (personality.isNotBlank()) {
            builder.appendLine(personality)
            builder.appendLine()
        }
    }

    /**
     * Inject familiarity context from FamiliarityEngine.
     *
     * Adds [FAMILIARITY CONTEXT] block with:
     * - Top familiar entities the user regularly encounters
     * - Each entity's familiarity level + encounter count
     * - Previous insights already provided (to avoid repetition)
     * - Next recommended insight category
     *
     * This prevents AI from repeating "that's a flower pot" for the 47th time.
     */
    private fun injectFamiliarityContext(builder: StringBuilder) {
        val engine = familiarityEngine ?: return

        try {
            val topEntities = engine.getTopFamiliarEntities(limit = 8)
            if (topEntities.isEmpty()) return

            // Only include entities with meaningful familiarity
            val meaningful = topEntities.filter {
                it.familiarityLevel >= com.xreal.nativear.companion.FamiliarityLevel.RECOGNIZED
            }
            if (meaningful.isEmpty()) return

            builder.appendLine("[FAMILIARITY CONTEXT]")
            builder.appendLine("사용자가 자주 보는 대상들 (반복 설명 금지):")
            meaningful.take(5).forEach { entity ->
                val context = engine.getEntityContext(entity.entityType, entity.entityLabel)
                if (context != null) {
                    builder.appendLine(context)
                }
            }
            builder.appendLine()
            builder.appendLine("[INSIGHT GUIDELINES]")
            builder.appendLine("- 이미 제공한 인사이트는 반복하지 마세요")
            builder.appendLine("- 친숙도가 높은 대상: 새로운 각도에서 접근하세요")
            builder.appendLine("- 처음 보는 대상: 기본 식별 + 간단한 사실")
            builder.appendLine("- 중복 회피: 위 '이전 인사이트'에 있는 내용은 절대 반복하지 마세요")
            builder.appendLine()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inject familiarity context: ${e.message}")
        }
    }

    /**
     * Digital Twin 생리 프로파일 주입.
     * PC 서버 예측 엔진 결과 (회복, 부상위험, 기저선, 러너타입)를 AI 프롬프트에 포함.
     */
    private fun injectDigitalTwinPhysiology(builder: StringBuilder) {
        try {
            val sync = org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.sync.PredictionSyncService>() ?: return
            val summary = sync.getProfileSummary()
            if (summary.isNotBlank() && !summary.contains("없음")) {
                builder.appendLine("[사용자 생리 프로파일 (디지털트윈)]")
                builder.appendLine(summary)
                builder.appendLine()
            }
        } catch (_: Exception) {}
    }

    /**
     * Gap D: 도구 성능 데이터를 페르소나 프롬프트에 주입.
     * 해당 페르소나가 사용하는 도구의 성공률/평균 지연을 알려줌.
     */
    private fun injectToolPerformance(builder: StringBuilder, personaId: String) {
        try {
            val tracker = org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.tools.ToolUsageTracker>() ?: return
            val stats = tracker.getStatsSnapshot()
            if (stats.isEmpty()) return

            // 실패율 높은 도구 우선, 최대 5개
            val relevantStats = stats.sortedByDescending { it.failureRate }.take(5)
            if (relevantStats.all { it.callCount < 2 }) return

            builder.appendLine("[도구 성능 데이터]")
            relevantStats.filter { it.callCount >= 2 }.forEach { stat ->
                val successPct = ((1f - stat.failureRate) * 100).toInt()
                val warning = if (stat.failureRate > 0.4f) " ⚠️ 신뢰성 낮음" else ""
                builder.appendLine("- ${stat.toolName}: 성공률 ${successPct}%, ${stat.callCount}회, 평균 ${stat.avgLatencyMs}ms$warning")
            }
            builder.appendLine()
        } catch (_: Exception) { /* 도구 통계 없으면 무시 */ }
    }

    /**
     * Build conversation messages for a persona request.
     * Returns (enrichedSystemPrompt, messages).
     */
    override suspend fun buildMessagesForPersona(
        personaId: String,
        userQuery: String,
        additionalContext: String?
    ): Pair<String, List<AIMessage>> {
        val enrichedSystemPrompt = buildPromptForPersona(personaId)
        val messages = mutableListOf<AIMessage>()

        if (additionalContext != null) {
            messages.add(AIMessage(role = "user", content = "[Context] $additionalContext"))
            messages.add(AIMessage(role = "assistant", content = "맥락을 이해했습니다."))
        }
        messages.add(AIMessage(role = "user", content = userQuery))

        return enrichedSystemPrompt to messages
    }

    /**
     * Get the provider instance for a given persona.
     */
    override fun getProviderForPersona(personaId: String): IAIProvider? {
        val persona = personas[personaId] ?: return null
        return providers[persona.providerId]
    }
}
