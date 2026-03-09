package com.xreal.nativear.mission

import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.ai.AIMessage
import com.xreal.nativear.cadence.UserStateTracker
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.policy.PolicyReader
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.router.persona.TokenBudgetTracker
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mission team leader (Conductor).
 *
 * Lifecycle:
 * 1. [activateMission] — triggered by MissionDetectorRouter
 * 2. Generate initial plan via Gemini
 * 3. Execute initial tasks in parallel
 * 4. Start proactive agent loops
 * 5. Monitor progress + re-plan every 5 minutes
 * 6. [deactivateMission] — when user state changes or mission expires
 */
class MissionConductor(
    private val agentRunner: MissionAgentRunner,
    private val aiRegistry: com.xreal.nativear.ai.IAICallService,
    private val tokenBudgetTracker: TokenBudgetTracker,
    private val eventBus: GlobalEventBus,
    private val userStateTracker: UserStateTracker,
    private val database: UnifiedMemoryDatabase,
    private val analyticsService: com.xreal.nativear.analytics.SystemAnalyticsService? = null,
    // ★ Phase L: 싱글톤 PersonaManager — 사용자 컨텍스트(DNA·프로필·기억) 주입용
    private val personaManager: com.xreal.nativear.ai.IPersonaService? = null,
    // ★ Fix 3: OutcomeTracker — 피드백 루프를 replan에 주입
    private val outcomeTracker: com.xreal.nativear.learning.IOutcomeRecorder? = null
) : IMissionService {
    private val TAG = "MissionConductor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val activeMissions = mutableMapOf<String, MissionRuntime>()

    // FocusMode 상태 (lazy inject)
    private val focusModeManager: com.xreal.nativear.focus.FocusModeManager? by lazy {
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull() } catch (e: Exception) { null }
    }

    companion object {
        val MAX_CONCURRENT_MISSIONS: Int get() = PolicyReader.getInt("mission.max_concurrent", 4)
        val MONITOR_INTERVAL_MS: Long get() = PolicyReader.getLong("mission.monitor_interval_ms", 300_000L) // 5분
        val PLAN_TEMPERATURE: Float get() = PolicyReader.getFloat("mission.plan_temperature", 0.4f)
        val PLAN_MAX_TOKENS: Int get() = PolicyReader.getInt("mission.plan_max_tokens", 2048)

        private const val PLAN_SYSTEM_PROMPT = """당신은 AR 라이프로그 시스템의 미션 팀장입니다.
미션 유형과 에이전트 팀 구성을 받고, 초기 실행 계획을 생성합니다.

각 에이전트에게 할당할 구체적인 태스크를 JSON 배열로 출력하세요.

출력 형식 (반드시 JSON 배열만 출력):
[
  {
    "agent_role": "에이전트 역할명",
    "description": "태스크 설명",
    "query": "에이전트에게 보낼 구체적 질문/지시"
  }
]

규칙:
- 각 에이전트의 도구와 규칙을 고려하여 실현 가능한 태스크만 할당
- 최대 6개 태스크
- 즉시 실행 가능한 태스크 위주 (순서 의존성 최소화)"""

        private const val REPLAN_SYSTEM_PROMPT = """당신은 진행 중인 미션을 점검하는 팀장입니다.
현재 진행 상황과 변동사항을 분석하고:

1. 추가 태스크가 필요하면 생성하세요
2. 미션 종료 조건이 충족되면 "MISSION_COMPLETE"를 반환하세요
3. 변동 없으면 빈 배열을 반환하세요

출력 형식: JSON 배열 (빈 배열 가능) 또는 "MISSION_COMPLETE" 문자열"""
    }

    /** Internal state per active mission */
    private data class MissionRuntime(
        var mission: Mission,
        val monitorJob: Job?,
        val startedAt: Long = System.currentTimeMillis()
    )

    // ── Public API ──

    /**
     * Activate a new mission. Called by MissionDetectorRouter.
     */
    override fun activateMission(type: MissionType, triggerMetadata: Map<String, String>) {
        // FocusMode DND/PRIVATE 시 신규 미션 시작 억제
        val fmm = focusModeManager
        if (fmm != null && !fmm.canAIAct(com.xreal.nativear.focus.AITrigger.PROACTIVE_MISSION)) {
            Log.i(TAG, "신규 미션 억제됨 (FocusMode: ${fmm.currentMode}): $type")
            return
        }

        // Check if same type already active
        if (activeMissions.values.any { it.mission.type == type && it.mission.state == MissionState.ACTIVE }) {
            Log.w(TAG, "Mission type $type already active, skipping")
            return
        }

        // Check concurrent limit
        if (activeMissions.size >= MAX_CONCURRENT_MISSIONS) {
            Log.w(TAG, "Max concurrent missions reached ($MAX_CONCURRENT_MISSIONS), skipping $type")
            return
        }

        val template = MissionTemplates.getTemplate(type)
        val mission = Mission(
            type = type,
            agentRoles = template.agentRoles,
            metadata = triggerMetadata
        )

        Log.i(TAG, "Activating mission: ${mission.id} ($type) with ${template.agentRoles.size} agents")

        // Register agent personas
        agentRunner.registerAgentRoles(template.agentRoles)

        // Update mission context
        mission.context.userStateSnapshot = userStateTracker.state.value.name

        // Launch mission lifecycle
        val monitorJob = scope.launch {
            try {
                // 1. Generate initial plan
                val plan = generateInitialPlan(mission, template)

                // ★ P1-2: plan null → 미션 비활성화 (proactive agents만으론 실효 없음)
                if (plan == null) {
                    Log.w(TAG, "미션 ${mission.id} 플랜 생성 실패 → 미션 비활성화")
                    publishMissionEvent(mission.id, type.name, "PLANNING", "PLAN_FAILED")
                    activeMissions.remove(mission.id)
                    return@launch
                }

                val activeMission = mission.copy(
                    state = MissionState.ACTIVE,
                    plan = plan
                )
                activeMissions[mission.id] = MissionRuntime(activeMission, coroutineContext[Job])

                publishMissionEvent(mission.id, type.name, "PLANNING", "ACTIVE")

                // 2. Execute initial tasks in parallel
                executeInitialTasks(activeMission, plan)

                // 3. Start proactive agents
                agentRunner.startProactiveAgents(template.agentRoles, mission.context, this)

                // 4. Monitor & re-plan loop
                monitorLoop(mission.id, template)
            } catch (e: CancellationException) {
                Log.i(TAG, "Mission ${mission.id} cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Mission ${mission.id} failed: ${e.message}", e)
            }
        }

        activeMissions[mission.id] = MissionRuntime(mission, monitorJob)
    }

    /**
     * Activate a custom mission from a dynamically generated config (Strategist-created).
     */
    override fun activateCustomMission(config: MissionTemplateConfig, metadata: Map<String, String>) {
        // Check concurrent limit
        if (activeMissions.size >= MAX_CONCURRENT_MISSIONS) {
            Log.w(TAG, "Max concurrent missions reached, skipping custom mission")
            return
        }

        val mission = Mission(
            type = MissionType.CUSTOM,
            agentRoles = config.agentRoles,
            metadata = metadata
        )

        Log.i(TAG, "Activating CUSTOM mission: ${mission.id} with ${config.agentRoles.size} agents")

        agentRunner.registerAgentRoles(config.agentRoles)
        mission.context.userStateSnapshot = userStateTracker.state.value.name

        // Pre-fetch domain briefing (0 AI tokens, pure DB mining)
        if (config.briefingDomains.isNotEmpty() && analyticsService != null) {
            try {
                val briefing = analyticsService.generateMissionBriefing(
                    briefingDomains = config.briefingDomains,
                    expertIds = config.agentRoles.map { it.roleName }
                )
                mission.context.putKnowledge("domain_briefing", briefing.domainSummary)
                mission.context.putKnowledge("expert_stats", briefing.expertStatsSummary)
                mission.context.putKnowledge("known_people", briefing.peopleSummary)
                mission.context.putKnowledge("iep_summary", briefing.iepSummary)
                mission.context.putKnowledge("recent_observations", briefing.recentObsSummary)
                Log.i(TAG, "Pre-fetched briefing for ${config.briefingDomains.size} domains (0 AI tokens)")
            } catch (e: Exception) {
                Log.w(TAG, "Mission briefing pre-fetch failed: ${e.message}")
            }
        }

        // Store current situation in context for OutcomeTracker
        mission.context.putKnowledge("current_situation", metadata["situation"] ?: "UNKNOWN")

        val monitorJob = scope.launch {
            try {
                val plan = generateInitialPlan(mission, config)

                // ★ P1-2: plan null → 커스텀 미션 비활성화
                if (plan == null) {
                    Log.w(TAG, "커스텀 미션 ${mission.id} 플랜 생성 실패 → 비활성화")
                    publishMissionEvent(mission.id, "CUSTOM", "PLANNING", "PLAN_FAILED")
                    activeMissions.remove(mission.id)
                    return@launch
                }

                val activeMission = mission.copy(
                    state = MissionState.ACTIVE,
                    plan = plan
                )
                activeMissions[mission.id] = MissionRuntime(activeMission, coroutineContext[Job])
                publishMissionEvent(mission.id, "CUSTOM", "PLANNING", "ACTIVE")

                executeInitialTasks(activeMission, plan)

                agentRunner.startProactiveAgents(config.agentRoles, mission.context, this)
                monitorLoop(mission.id, config)
            } catch (e: CancellationException) {
                Log.i(TAG, "Custom mission ${mission.id} cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Custom mission ${mission.id} failed: ${e.message}", e)
            }
        }

        activeMissions[mission.id] = MissionRuntime(mission, monitorJob)
    }

    /**
     * Deactivate a mission by ID.
     */
    override fun deactivateMission(missionId: String) {
        val runtime = activeMissions.remove(missionId) ?: return
        runtime.monitorJob?.cancel()
        agentRunner.stopProactiveAgents()

        val summary = runtime.mission.context.buildContextSummary()
        Log.i(TAG, "Mission $missionId deactivated. Summary:\n${summary.take(500)}")

        // Persist mission summary
        persistMissionSummary(runtime)

        publishMissionEvent(missionId, runtime.mission.type.name, "ACTIVE", "COMPLETED")

        // Announce mission end
        val durationMin = (System.currentTimeMillis() - runtime.startedAt) / 60_000
        eventBus.publish(
            XRealEvent.ActionRequest.ShowMessage("미션 종료: ${runtime.mission.type} (${durationMin}분)")
        )
    }

    /**
     * Deactivate all missions of a given type.
     */
    override fun deactivateMissionsByType(type: MissionType) {
        activeMissions.entries
            .filter { it.value.mission.type == type }
            .map { it.key }
            .forEach { deactivateMission(it) }
    }

    /**
     * Deactivate missions matching a specific metadata key-value pair.
     * Used by ExpertTeamManager to deactivate only the CUSTOM mission for a specific domain,
     * without killing other active CUSTOM missions.
     */
    override fun deactivateMissionsByMetadata(key: String, value: String) {
        activeMissions.entries
            .filter { it.value.mission.metadata[key] == value }
            .map { it.key }
            .forEach { deactivateMission(it) }
    }

    /**
     * Check if a mission type is currently active.
     */
    override fun isMissionActive(type: MissionType): Boolean =
        activeMissions.values.any { it.mission.type == type && it.mission.state == MissionState.ACTIVE }

    override fun getActiveMissionTypes(): List<MissionType> =
        activeMissions.values.map { it.mission.type }

    // ── Plan generation ──

    private suspend fun generateInitialPlan(
        mission: Mission,
        template: MissionTemplateConfig
    ): MissionPlan? {
        val basePlanPrompt = buildPlanPrompt(mission, template)

        // ★ Phase L: 사용자 컨텍스트(직업·DNA)를 user message에 추가 — JSON systemPrompt 유지하면서 안전하게 주입
        val contextAddendum = try {
            personaManager?.buildContextAddendum("mission_conductor")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
        val prompt = if (contextAddendum != null) {
            "$basePlanPrompt\n\n[사용자 컨텍스트]\n$contextAddendum"
        } else basePlanPrompt

        // ── 예산 게이트: 미션 플랜 생성은 2048 토큰 소모 ──
        val budgetCheck = tokenBudgetTracker.checkBudget(
            com.xreal.nativear.ai.ProviderId.GEMINI, estimatedTokens = PLAN_MAX_TOKENS
        )
        if (!budgetCheck.allowed) {
            Log.w(TAG, "미션 플랜 생성 차단 (예산 부족): ${budgetCheck.reason}")
            return MissionPlan(
                goals = template.initialPlanGoals,
                initialTasks = template.agentRoles.map { role ->
                    AgentTask(
                        agentRoleName = role.roleName,
                        description = "기본 초기화",
                        query = "미션이 시작되었습니다. 당신의 역할에 맞는 초기 작업을 수행하세요."
                    )
                }
            )
        }

        return try {
            val response = aiRegistry.quickText(
                messages = listOf(AIMessage(role = "user", content = prompt)),
                systemPrompt = PLAN_SYSTEM_PROMPT,
                temperature = PLAN_TEMPERATURE,
                maxTokens = PLAN_MAX_TOKENS,
                callPriority = com.xreal.nativear.ai.AICallGateway.CallPriority.PROACTIVE,
                visibility = com.xreal.nativear.ai.AICallGateway.VisibilityIntent.INTERNAL_ONLY,
                intent = "mission_plan"
            ) ?: return null

            val text = response.text ?: return null
            val tasks = parseTasks(text, mission.agentRoles)

            // ★ Phase L: 미션 계획을 personaMemory에 저장 → 다음 계획 시 이전 패턴 참조 가능
            try {
                val memService = org.koin.java.KoinJavaComponent.getKoin()
                    .getOrNull<com.xreal.nativear.ai.PersonaMemoryService>()
                val taskSummary = tasks.take(2).joinToString("; ") { it.description.take(60) }
                memService?.savePersonaMemory(
                    personaId = "mission_conductor",
                    content = "미션 계획: ${mission.type} — $taskSummary",
                    role = "AI"
                )
            } catch (_: Exception) {}

            Log.i(TAG, "Generated plan with ${tasks.size} initial tasks")
            MissionPlan(
                goals = template.initialPlanGoals,
                initialTasks = tasks
            )
        } catch (e: Exception) {
            Log.e(TAG, "Plan generation failed: ${e.message}", e)
            null
        }
    }

    private fun buildPlanPrompt(mission: Mission, template: MissionTemplateConfig): String = buildString {
        appendLine("=== 미션 계획 생성 요청 ===")
        appendLine("미션 유형: ${mission.type}")
        appendLine("미션 목표: ${template.initialPlanGoals.joinToString(", ")}")
        appendLine()
        appendLine("[에이전트 팀 구성]")
        for (role in mission.agentRoles) {
            appendLine("- ${role.roleName} (${role.providerId})")
            appendLine("  도구: ${role.tools.joinToString(", ")}")
            appendLine("  규칙: ${role.rules.joinToString("; ")}")
            appendLine("  주기적 실행: ${if (role.isProactive) "${role.proactiveIntervalMs / 1000}초 간격" else "비활성"}")
        }
        appendLine()
        appendLine("[현재 상황]")
        appendLine("- 사용자 상태: ${mission.context.userStateSnapshot}")
        if (mission.metadata.isNotEmpty()) {
            appendLine("- 트리거 메타데이터: ${mission.metadata}")
        }
        appendLine()
        appendLine("각 에이전트에게 즉시 실행할 초기 태스크를 할당하세요.")
    }

    // ── Task execution ──

    private suspend fun executeInitialTasks(mission: Mission, plan: MissionPlan) {
        if (plan.initialTasks.isEmpty()) return

        Log.i(TAG, "Executing ${plan.initialTasks.size} initial tasks")

        // Execute all initial tasks in parallel
        coroutineScope {
            for (task in plan.initialTasks) {
                val role = mission.agentRoles.find { it.roleName == task.agentRoleName }
                if (role == null) {
                    Log.w(TAG, "No role found for task agent: ${task.agentRoleName}")
                    continue
                }

                launch {
                    agentRunner.executeTask(task, role, mission.context)
                }
            }
        }

        Log.i(TAG, "Initial tasks completed")
    }

    // ── Monitor & re-plan loop ──

    private suspend fun monitorLoop(missionId: String, template: MissionTemplateConfig) {
        delay(MONITOR_INTERVAL_MS)

        while (true) {
            val runtime = activeMissions[missionId] ?: break

            // Check mission duration
            val elapsed = System.currentTimeMillis() - runtime.startedAt
            if (elapsed > template.maxDurationMs) {
                Log.i(TAG, "Mission $missionId exceeded max duration, deactivating")
                deactivateMission(missionId)
                break
            }

            // Re-plan
            try {
                monitorAndReplan(runtime)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Re-plan failed: ${e.message}")
            }

            delay(MONITOR_INTERVAL_MS)
        }
    }

    private suspend fun monitorAndReplan(runtime: MissionRuntime) {
        val mission = runtime.mission

        // Update context
        mission.context.userStateSnapshot = userStateTracker.state.value.name

        val prompt = buildReplanPrompt(mission, runtime.startedAt)

        // ── 예산 게이트: 5분×4미션×1024토큰 = 하루 최대 1,179,648 토큰 위험 방지 ──
        val budgetCheck = tokenBudgetTracker.checkBudget(
            com.xreal.nativear.ai.ProviderId.GEMINI, estimatedTokens = 1024
        )
        if (!budgetCheck.allowed) {
            Log.d(TAG, "Re-plan 건너뜀 (예산 게이트): ${budgetCheck.reason}")
            return
        }

        val response = aiRegistry.quickText(
            messages = listOf(AIMessage(role = "user", content = prompt)),
            systemPrompt = REPLAN_SYSTEM_PROMPT,
            temperature = PLAN_TEMPERATURE,
            maxTokens = 1024,
            callPriority = com.xreal.nativear.ai.AICallGateway.CallPriority.PROACTIVE,
            visibility = com.xreal.nativear.ai.AICallGateway.VisibilityIntent.INTERNAL_ONLY,
            intent = "mission_replan"
        ) ?: return

        val text = response.text ?: return

        if (text.contains("MISSION_COMPLETE")) {
            Log.i(TAG, "Conductor determined mission ${mission.id} complete")
            deactivateMission(mission.id)
            return
        }

        val newTasks = parseTasks(text, mission.agentRoles)
        if (newTasks.isNotEmpty()) {
            Log.i(TAG, "Re-plan added ${newTasks.size} new tasks")
            scope.launch {
                val updatedMission = mission.copy(
                    plan = mission.plan?.copy(
                        initialTasks = mission.plan.initialTasks + newTasks
                    )
                )
                runtime.mission = updatedMission

                coroutineScope {
                    for (task in newTasks) {
                        val role = mission.agentRoles.find { it.roleName == task.agentRoleName } ?: continue
                        launch { agentRunner.executeTask(task, role, mission.context) }
                    }
                }
            }
        }
    }

    private fun buildReplanPrompt(mission: Mission, startedAt: Long): String = buildString {
        val elapsedMin = (System.currentTimeMillis() - startedAt) / 60_000
        appendLine("=== 미션 진행 점검 (${elapsedMin}분 경과) ===")
        appendLine("미션 유형: ${mission.type}")
        appendLine("사용자 상태: ${mission.context.userStateSnapshot}")
        appendLine()

        val summary = mission.context.buildContextSummary()
        if (summary.isNotBlank()) {
            appendLine("[현재 상황 요약]")
            appendLine(summary.take(1500))
            appendLine()
        }

        // ★ Fix 3: OutcomeTracker 피드백 주입 — AI가 과거 전략 성과를 참고하여 replan
        try {
            val outcomeSummary = outcomeTracker?.getRecentOutcomeSummary()
            if (!outcomeSummary.isNullOrBlank()) {
                appendLine("[AI 개입 성과 피드백]")
                appendLine(outcomeSummary.take(800))
                appendLine()
                appendLine("위 피드백을 참고하여: 효과적이었던 전략은 유지하고, 실패한 전략은 다른 접근을 시도하세요.")
                appendLine()
            }
        } catch (e: Exception) {
            Log.w(TAG, "OutcomeTracker summary 조회 실패: ${e.message}")
        }

        appendLine("추가 태스크가 필요하면 JSON 배열로, 미션 종료 조건 충족 시 MISSION_COMPLETE를 반환하세요.")
    }

    // ── JSON parsing ──

    private fun parseTasks(text: String, roles: List<AgentRole>): List<AgentTask> {
        val jsonText = text.replace("```json", "").replace("```", "").trim()

        return try {
            val array = JSONArray(jsonText)
            val tasks = mutableListOf<AgentTask>()

            for (i in 0 until minOf(array.length(), 6)) {
                val obj = array.getJSONObject(i)
                val agentRole = obj.optString("agent_role", "")
                val description = obj.optString("description", "")
                val query = obj.optString("query", "")

                if (agentRole.isBlank() || query.isBlank()) continue

                // Verify agent exists
                if (roles.none { it.roleName == agentRole }) {
                    Log.w(TAG, "Plan references unknown agent: $agentRole, skipping")
                    continue
                }

                tasks.add(AgentTask(
                    agentRoleName = agentRole,
                    description = description,
                    query = query
                ))
            }
            tasks
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse plan tasks: ${e.message}")
            emptyList()
        }
    }

    // ── Event publishing ──

    private fun publishMissionEvent(
        missionId: String,
        missionType: String,
        oldState: String,
        newState: String
    ) {
        eventBus.publish(XRealEvent.SystemEvent.MissionStateChanged(
            missionId = missionId,
            missionType = missionType,
            oldState = oldState,
            newState = newState
        ))
    }

    private fun persistMissionSummary(runtime: MissionRuntime) {
        try {
            val mission = runtime.mission
            val durationMs = System.currentTimeMillis() - runtime.startedAt
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd_HH:mm", java.util.Locale.getDefault())
            val dateKey = sdf.format(java.util.Date(runtime.startedAt))

            val json = JSONObject().apply {
                put("mission_id", mission.id)
                put("type", mission.type.name)
                put("start_time", runtime.startedAt)
                put("duration_ms", durationMs)
                put("agents", JSONArray(mission.agentRoles.map { it.roleName }))
                put("goals", mission.plan?.goals?.let { JSONArray(it) } ?: JSONArray())
                put("context_summary", mission.context.buildContextSummary().take(2000))
            }

            database.upsertStructuredData(
                domain = "mission_summary",
                dataKey = "${mission.type.name}_$dateKey",
                value = json.toString(),
                tags = "mission,${mission.type.name.lowercase()}"
            )
            Log.i(TAG, "Mission summary persisted: mission_summary/${mission.type.name}_$dateKey")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist mission summary: ${e.message}", e)
        }
    }

    /**
     * Release all resources.
     */
    override fun release() {
        activeMissions.keys.toList().forEach { deactivateMission(it) }
        scope.cancel()
        agentRunner.cleanup()
    }
}
