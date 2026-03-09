package com.xreal.nativear.companion

import java.util.UUID

/**
 * RelationshipTypes: Data types for the Relationship Intelligence system.
 *
 * Tracks attitudes, intentions, conversation history, promises,
 * and emotional trends for regularly encountered people.
 */

data class RelationshipProfile(
    val id: String = UUID.randomUUID().toString().take(12),
    val personId: Long,
    val personName: String,
    val relationship: RelationshipType = RelationshipType.STRANGER,
    val closenessScore: Float = 0f,
    val sentimentTrend: Float = 0f,
    val interactionFrequency: Float = 0f,
    val topTopics: List<String> = emptyList(),
    val pendingRequests: List<String> = emptyList(),
    val sharedMemories: List<String> = emptyList(),
    val lastMoodObserved: String? = null,
    val moodHistory: List<MoodSnapshot> = emptyList(),
    val communicationStyle: String? = null,
    val notableTraits: List<String> = emptyList(),
    val lastInteractionAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class RelationshipType(val code: Int, val displayName: String) {
    STRANGER(0, "모르는 사람"),
    ACQUAINTANCE(1, "지인"),
    COLLEAGUE(2, "동료"),
    FRIEND(3, "친구"),
    CLOSE_FRIEND(4, "가까운 친구"),
    FAMILY(5, "가족"),
    ROMANTIC(6, "연인"),
    MENTOR(7, "멘토"),
    MENTEE(8, "멘티"),
    SERVICE(9, "서비스 관계"),
    CUSTOM(10, "기타");

    companion object {
        fun fromCode(code: Int): RelationshipType =
            entries.firstOrNull { it.code == code } ?: STRANGER
    }
}

data class ConversationEntry(
    val id: String = UUID.randomUUID().toString().take(12),
    val personId: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val location: String? = null,
    val situation: String? = null,
    val topics: List<String> = emptyList(),
    val keyPoints: List<String> = emptyList(),
    val promises: List<Promise> = emptyList(),
    val emotionObserved: String? = null,
    val myEmotion: String? = null,
    val transcript: String? = null,
    val aiSummary: String = ""
)

data class Promise(
    val id: String = UUID.randomUUID().toString().take(8),
    val content: String,
    val madeBy: PromiseActor,
    val deadline: Long? = null,
    val status: PromiseStatus = PromiseStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis()
)

enum class PromiseActor { ME, THEM }

enum class PromiseStatus(val code: Int) {
    PENDING(0), FULFILLED(1), BROKEN(2), CANCELLED(3);
    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: PENDING
    }
}

data class MoodSnapshot(
    val timestamp: Long,
    val mood: String,
    val confidence: Float,
    val context: String? = null
)

data class RelationshipInsight(
    val personId: Long,
    val personName: String,
    val type: RelationshipInsightType,
    val description: String,
    val suggestedAction: String? = null,
    val priority: Int = 3
)

enum class RelationshipInsightType {
    FREQUENCY_CHANGE,
    MOOD_TREND,
    BROKEN_PROMISE,
    TOPIC_SHIFT,
    RELATIONSHIP_MILESTONE,
    REMINDER
}
