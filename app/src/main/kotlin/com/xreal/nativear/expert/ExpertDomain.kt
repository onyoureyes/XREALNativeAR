package com.xreal.nativear.expert

import com.xreal.nativear.ai.ProviderId
import com.xreal.nativear.context.LifeSituation

/**
 * ExpertDomain: Defines a life-domain expert AI team.
 *
 * Each domain contains a team of ExpertProfiles that activate
 * when specific LifeSituations are detected by SituationRecognizer.
 *
 * The domain bridges to the existing Mission system:
 * ExpertDomain -> MissionTemplateConfig -> Mission lifecycle
 */
data class ExpertDomain(
    val id: String,
    val name: String,
    val description: String,
    val triggerSituations: Set<LifeSituation>,
    val experts: List<ExpertProfile>,
    val leadExpertId: String,
    val hudMode: String,                      // Phase 4: HUDMode enum name
    val priority: Int,                        // Higher = more important (0-100)
    val isAlwaysActive: Boolean = false,       // DAILY_LIFE, HEALTH
    val requiredTools: Set<String>,            // Tools this domain needs
    val desiredTools: List<ToolRequest> = emptyList(),  // Phase 11: AI-requested tools
    val maxDurationMs: Long = 4 * 3600_000L,  // Default 4 hours
    val briefingDomains: List<String> = emptyList() // Phase 17: structured_data domains to pre-fetch for mission briefing
)

/**
 * ExpertProfile: Defines a single AI expert within a domain team.
 *
 * Maps to AgentRole in the Mission system via toAgentRole().
 */
data class ExpertProfile(
    val id: String,
    val name: String,
    val role: String,
    val personality: String,
    val systemPrompt: String,
    val providerId: ProviderId,
    val specialties: List<String>,
    val tools: List<String>,
    val rules: List<String> = emptyList(),
    val isProactive: Boolean = false,
    val proactiveIntervalMs: Long = 60_000L,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 1024,
    val canRequestCapabilities: Boolean = true  // Phase 11
) {
    /**
     * Convert to Mission system's AgentRole for execution.
     */
    fun toAgentRole(): com.xreal.nativear.mission.AgentRole {
        return com.xreal.nativear.mission.AgentRole(
            roleName = id,
            providerId = providerId,
            systemPrompt = systemPrompt,
            tools = tools,
            rules = rules,
            temperature = temperature,
            maxTokens = maxTokens,
            isProactive = isProactive,
            proactiveIntervalMs = proactiveIntervalMs
        )
    }
}

/**
 * ToolRequest: AI-requested capability (Phase 11).
 * Experts can request tools they need but don't have yet.
 */
data class ToolRequest(
    val toolName: String,
    val description: String,
    val requestedBy: String,      // Expert ID
    val priority: Int = 2         // 0=CRITICAL, 1=HIGH, 2=NORMAL, 3=LOW
)

/**
 * Active domain session tracking.
 */
data class ActiveDomainSession(
    val domain: ExpertDomain,
    val missionId: String?,       // Linked Mission ID (null if not yet activated)
    val activatedAt: Long = System.currentTimeMillis(),
    val situation: LifeSituation,
    val isActive: Boolean = true
)
