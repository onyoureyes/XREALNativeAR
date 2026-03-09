package com.xreal.nativear.mission

import com.xreal.nativear.ai.ProviderId
import java.util.UUID

// ─── Mission lifecycle ───

enum class MissionType {
    RUNNING_COACH,
    TRAVEL_GUIDE,
    EXPLORATION,
    DAILY_COMMUTE,
    SOCIAL_ENCOUNTER,
    CUSTOM
}

enum class MissionState {
    PLANNING,
    ACTIVE,
    PAUSED,
    COMPLETED
}

// ─── Agent definition ───

/**
 * Defines a single agent's role within a mission team.
 *
 * @param roleName   Unique role identifier within the mission (e.g. "pace_coach")
 * @param providerId Which AI backend to use
 * @param systemPrompt Mission-context system prompt for this agent
 * @param tools      Subset of tool names this agent is allowed to call
 * @param rules      Natural-language behavioral rules
 * @param isProactive If true, runs on its own periodic schedule
 * @param proactiveIntervalMs Interval for proactive execution (only if isProactive)
 */
data class AgentRole(
    val roleName: String,
    val providerId: ProviderId,
    val systemPrompt: String,
    val tools: List<String> = emptyList(),
    val rules: List<String> = emptyList(),
    val temperature: Float = 0.7f,
    val maxTokens: Int = 1024,
    val isProactive: Boolean = false,
    val proactiveIntervalMs: Long = 60_000L
) {
    /** Generated persona ID for PersonaManager registration */
    val personaId: String get() = "mission_$roleName"
}

// ─── Task ───

enum class TaskStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED }

data class AgentTask(
    val id: String = UUID.randomUUID().toString().take(8),
    val agentRoleName: String,
    val description: String,
    val query: String,
    val status: TaskStatus = TaskStatus.PENDING,
    val result: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

// ─── Mission plan ───

data class MissionPlan(
    val goals: List<String>,
    val initialTasks: List<AgentTask>,
    val contingencies: List<String> = emptyList()
)

// ─── Mission aggregate ───

data class Mission(
    val id: String = UUID.randomUUID().toString().take(12),
    val type: MissionType,
    val state: MissionState = MissionState.PLANNING,
    val plan: MissionPlan? = null,
    val agentRoles: List<AgentRole>,
    val context: SharedMissionContext = SharedMissionContext(),
    val createdAt: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap()
)
