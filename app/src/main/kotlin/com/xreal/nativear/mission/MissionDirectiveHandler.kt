package com.xreal.nativear.mission

import android.util.Log
import com.xreal.nativear.ai.ProviderId
import com.xreal.nativear.policy.PolicyReader
import com.xreal.nativear.strategist.DirectiveStore
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * Polls DirectiveStore for "mission_conductor" directives and creates custom missions.
 *
 * Directive format:
 * create_mission:{"mission_name":"study_coach","goals":[...],"agents":[...]}
 */
class MissionDirectiveHandler(
    private val directiveStore: DirectiveStore,
    private val conductor: MissionConductor
) {
    private val TAG = "MissionDirectiveHandler"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val processedDirectiveIds = mutableSetOf<String>()
    private var pollingJob: Job? = null

    companion object {
        const val POLL_INTERVAL_MS = 60_000L
        val MAX_AGENTS_PER_MISSION: Int get() = PolicyReader.getInt("mission.max_agents_per_mission", 3)
        const val CREATE_MISSION_PREFIX = "create_mission:"
    }

    fun start() {
        if (pollingJob != null) return
        pollingJob = scope.launch {
            Log.i(TAG, "MissionDirectiveHandler polling started")
            while (isActive) {
                try {
                    checkDirectives()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Directive check failed: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun checkDirectives() {
        val directives = directiveStore.getDirectivesForPersona("mission_conductor")
        for (directive in directives) {
            if (directive.id in processedDirectiveIds) continue
            if (!directive.instruction.startsWith(CREATE_MISSION_PREFIX)) continue

            processedDirectiveIds.add(directive.id)

            try {
                val jsonStr = directive.instruction.removePrefix(CREATE_MISSION_PREFIX)
                val config = parseCustomMissionConfig(jsonStr)
                if (config != null) {
                    Log.i(TAG, "Creating custom mission: ${config.type} with ${config.agentRoles.size} agents")
                    conductor.activateCustomMission(config)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse create_mission directive: ${e.message}", e)
            }
        }

        // Prevent unbounded growth
        if (processedDirectiveIds.size > 100) {
            val toKeep = processedDirectiveIds.toList().takeLast(50)
            processedDirectiveIds.clear()
            processedDirectiveIds.addAll(toKeep)
        }
    }

    private fun parseCustomMissionConfig(jsonStr: String): MissionTemplateConfig? {
        val json = JSONObject(jsonStr)
        val missionName = json.optString("mission_name", "custom_mission")
        val goalsArray = json.optJSONArray("goals")
        val agentsArray = json.optJSONArray("agents")

        if (agentsArray == null || agentsArray.length() == 0) {
            Log.w(TAG, "No agents defined in custom mission config")
            return null
        }

        val goals = mutableListOf<String>()
        if (goalsArray != null) {
            for (i in 0 until goalsArray.length()) {
                goals.add(goalsArray.getString(i))
            }
        }

        val agentRoles = mutableListOf<AgentRole>()
        val agentCount = minOf(agentsArray.length(), MAX_AGENTS_PER_MISSION)

        for (i in 0 until agentCount) {
            val agentJson = agentsArray.getJSONObject(i)
            val roleName = agentJson.optString("role_name", "agent_$i")
            val providerStr = agentJson.optString("provider", "GEMINI")
            val systemPrompt = agentJson.optString("system_prompt", "")
            val toolsArray = agentJson.optJSONArray("tools")
            val rulesArray = agentJson.optJSONArray("rules")

            val tools = mutableListOf<String>()
            if (toolsArray != null) {
                for (j in 0 until toolsArray.length()) {
                    tools.add(toolsArray.getString(j))
                }
            }

            val rules = mutableListOf<String>()
            if (rulesArray != null) {
                for (j in 0 until rulesArray.length()) {
                    rules.add(rulesArray.getString(j))
                }
            }

            val providerId = try {
                ProviderId.valueOf(providerStr.uppercase())
            } catch (e: Exception) {
                ProviderId.GEMINI
            }

            agentRoles.add(AgentRole(
                roleName = "${missionName}_$roleName",
                providerId = providerId,
                systemPrompt = systemPrompt,
                tools = tools,
                rules = rules,
                temperature = agentJson.optDouble("temperature", 0.5).toFloat(),
                maxTokens = agentJson.optInt("max_tokens", 1024),
                isProactive = agentJson.optBoolean("is_proactive", false),
                proactiveIntervalMs = agentJson.optLong("proactive_interval_ms", 120_000L)
            ))
        }

        return MissionTemplateConfig(
            type = MissionType.CUSTOM,
            agentRoles = agentRoles,
            initialPlanGoals = goals,
            maxDurationMs = json.optLong("max_duration_ms", 2 * 3600_000L)
        )
    }

    fun release() {
        stop()
        scope.cancel()
    }
}
