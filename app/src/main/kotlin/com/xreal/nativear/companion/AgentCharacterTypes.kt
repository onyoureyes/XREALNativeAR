package com.xreal.nativear.companion

import java.util.UUID

/**
 * AgentCharacterTypes: Data types for the Agent Personality Evolution system.
 *
 * Each AI agent (expert) develops a persistent personality through:
 * - Core traits (unchanging identity)
 * - Evolved traits (acquired through experience)
 * - Agent memories (successes, failures, insights)
 * - Growth stages (NEWBORN → SAGE)
 * - Self-reflection (weekly introspection)
 *
 * The personality prompt is injected into each expert's system prompt,
 * making each agent feel unique and alive.
 */

data class AgentCharacter(
    val agentId: String,                // expert profile id (e.g., "running_coach")
    val name: String,                   // display name (e.g., "러닝코치")
    val coreTraits: List<String>,       // immutable personality traits (max 3)
    val evolvedTraits: List<EvolvedTrait> = emptyList(),
    val specializations: List<String> = emptyList(),
    val catchphrases: List<String> = emptyList(),
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val totalInteractions: Int = 0,
    val userTrustScore: Float = 0.5f,   // EMA of user feedback (0-1)
    val growthStage: GrowthStage = GrowthStage.NEWBORN,
    val lastReflection: String? = null, // latest self-reflection text
    val lastReflectionAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastActiveAt: Long = System.currentTimeMillis()
) {
    val totalOutcomes: Int get() = successCount + failureCount
    val successRate: Float get() = if (totalOutcomes > 0) successCount.toFloat() / totalOutcomes else 0f
}

data class EvolvedTrait(
    val trait: String,                  // e.g., "공감적", "도전적", "과학적"
    val strength: Float,               // 0.0-1.0 (reinforced over time)
    val acquiredAt: Long,
    val source: String                 // what experience led to this trait
)

data class AgentMemory(
    val id: String = UUID.randomUUID().toString().take(12),
    val agentId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: AgentMemoryType,
    val content: String,
    val emotionalWeight: Float = 0f,    // -1.0 (negative) ~ 1.0 (positive)
    val relatedEntityId: String? = null, // person/place/object id
    val wasSuccessful: Boolean? = null
)

enum class AgentMemoryType(val code: Int) {
    SUCCESS(0),        // successful intervention (user NOD)
    FAILURE(1),        // failed intervention (user SHAKE)
    INSIGHT(2),        // pattern the agent discovered
    RELATIONSHIP(3),   // agent's perspective on a person/place
    GROWTH(4),         // growth milestone
    REFLECTION(5);     // self-reflection

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: SUCCESS
    }
}

enum class GrowthStage(val code: Int, val displayName: String, val minInteractions: Int) {
    NEWBORN(0, "초보", 0),
    LEARNING(1, "학습 중", 50),
    COMPETENT(2, "유능", 200),
    PROFICIENT(3, "숙련", 500),
    EXPERT(4, "전문가", 1000),
    MASTER(5, "마스터", 2000),
    SAGE(6, "현자", 5000);

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: NEWBORN
        fun fromInteractions(count: Int): GrowthStage {
            return entries.lastOrNull { count >= it.minInteractions } ?: NEWBORN
        }
    }
}

/**
 * Agent optimization suggestion from AgentMetaManager
 */
data class AgentOptimization(
    val agentId: String,
    val agentName: String,
    val currentEffectiveness: Float,
    val suggestion: String,
    val priority: Int                  // 1-5
)

/**
 * Weekly agent performance report
 */
data class AgentPerformanceReport(
    val weekStart: Long,
    val weekEnd: Long,
    val agentReports: List<SingleAgentReport>,
    val totalTokensUsed: Int,
    val totalInterventions: Int,
    val overallAcceptanceRate: Float
)

data class SingleAgentReport(
    val agentId: String,
    val agentName: String,
    val growthStage: GrowthStage,
    val interventionCount: Int,
    val acceptanceRate: Float,
    val topStrategy: String?,
    val weakArea: String?,
    val trustScore: Float,
    val traitsEvolved: List<String>
)
