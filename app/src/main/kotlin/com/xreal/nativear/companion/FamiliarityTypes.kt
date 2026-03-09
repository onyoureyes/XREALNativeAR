package com.xreal.nativear.companion

import java.util.UUID

/**
 * FamiliarityTypes: Data types for the Familiarity Engine.
 *
 * Tracks how familiar the user is with objects, people, and places,
 * and manages progressive insight depth to avoid repetitive AI analysis.
 */

data class EntityFamiliarity(
    val id: String = UUID.randomUUID().toString().take(12),
    val entityType: EntityType,
    val entityLabel: String,
    val entityRefId: String? = null,
    val totalEncounters: Int = 1,
    val recentEncounters: Int = 1,
    val firstSeenAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = System.currentTimeMillis(),
    val familiarityScore: Float = 0f,
    val familiarityLevel: FamiliarityLevel = FamiliarityLevel.STRANGER,
    val insightDepth: Int = 0,
    val lastInsightAt: Long? = null,
    val contextDiversity: Float = 0f,
    val emotionalValence: Float = 0f,
    val metadata: String? = null
)

enum class EntityType(val code: Int) {
    OBJECT(0),
    PERSON(1),
    PLACE(2)
}

enum class FamiliarityLevel(val minScore: Float, val displayName: String) {
    STRANGER(0.0f, "처음"),
    RECOGNIZED(0.15f, "인식됨"),
    ACQUAINTANCE(0.3f, "안면"),
    FAMILIAR(0.5f, "친숙"),
    INTIMATE(0.7f, "깊은 이해"),
    PROFOUND(0.9f, "완전한 이해");

    companion object {
        fun fromScore(score: Float): FamiliarityLevel {
            return entries.sortedByDescending { it.minScore }
                .firstOrNull { score >= it.minScore } ?: STRANGER
        }
    }
}

data class InsightRecord(
    val id: String = UUID.randomUUID().toString().take(12),
    val entityId: String,
    val depth: Int,
    val category: InsightCategory,
    val content: String,
    val situation: String? = null,
    val wasAppreciated: Boolean? = null,
    val createdAt: Long = System.currentTimeMillis()
)

enum class InsightCategory(val code: Int, val displayName: String) {
    IDENTIFICATION(0, "식별"),
    FACTUAL(1, "사실"),
    CONTEXTUAL(2, "맥락"),
    RELATIONAL(3, "관계"),
    TEMPORAL(4, "변화"),
    PHILOSOPHICAL(5, "철학"),
    CHALLENGE(6, "도전"),
    CARE(7, "관리"),
    CREATIVE(8, "창의");

    companion object {
        fun fromCode(code: Int): InsightCategory {
            return entries.firstOrNull { it.code == code } ?: IDENTIFICATION
        }
    }
}

data class CachedAnalysis(
    val sceneHash: String,
    val labels: Set<String>,
    val analysisResult: String,
    val insightsProvided: List<String>,
    val cachedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 30 * 60 * 1000L
) {
    val isExpired: Boolean get() = System.currentTimeMillis() > expiresAt
}

enum class SceneChangeResult {
    UNCHANGED,
    MINOR_CHANGE,
    MAJOR_CHANGE,
    NEW_SCENE
}
