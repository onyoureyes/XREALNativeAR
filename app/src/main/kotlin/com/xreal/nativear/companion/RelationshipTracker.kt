package com.xreal.nativear.companion

import android.content.ContentValues
import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.SceneDatabase
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONArray
import org.json.JSONObject

/**
 * RelationshipTracker: Tracks relationship dynamics for regularly encountered people.
 *
 * Monitors: conversation topics, promises/requests, mood trends,
 * interaction frequency, and generates relationship insights for briefings.
 */
class RelationshipTracker(
    private val database: UnifiedMemoryDatabase,
    private val sceneDatabase: SceneDatabase,
    private val familiarityEngine: FamiliarityEngine,
    private val eventBus: GlobalEventBus,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "RelationshipTracker"
        private const val TABLE_PROFILES = "relationship_profiles"
        private const val TABLE_JOURNAL = "conversation_journal"
    }

    private var eventJob: Job? = null

    // ─── Lifecycle ───

    fun start() {
        Log.i(TAG, "RelationshipTracker started")
        eventJob = scope.launch(Dispatchers.Default) {
            eventBus.events.collectLatest { event ->
                try {
                    when (event) {
                        is XRealEvent.PerceptionEvent.PersonIdentified -> {
                            onPersonSeen(event.personId, event.personName ?: "Unknown")
                        }
                        else -> {}
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Event processing error: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        eventJob?.cancel()
        Log.i(TAG, "RelationshipTracker stopped")
    }

    // ─── Person Encounter ───

    private fun onPersonSeen(personId: Long, name: String) {
        val profile = getOrCreateProfile(personId, name)
        val now = System.currentTimeMillis()

        // Update last interaction timestamp
        try {
            val db = database.writableDatabase
            db.execSQL(
                "UPDATE $TABLE_PROFILES SET last_interaction_at = ?, updated_at = ? WHERE person_id = ?",
                arrayOf(now, now, personId)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update interaction time: ${e.message}")
        }

        // Also record as familiarity encounter
        familiarityEngine.recordEncounter(EntityType.PERSON, name, personId.toString(), null, null)
    }

    // ─── Profile Management ───

    fun getOrCreateProfile(personId: Long, name: String): RelationshipProfile {
        val existing = getProfileByPersonId(personId)
        if (existing != null) return existing

        val profile = RelationshipProfile(
            personId = personId,
            personName = name
        )

        try {
            val db = database.writableDatabase
            db.insertWithOnConflict(TABLE_PROFILES, null, profileToValues(profile),
                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
            Log.d(TAG, "Created relationship profile for $name (ID: $personId)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create profile: ${e.message}")
        }

        return profile
    }

    fun getProfileByPersonId(personId: Long): RelationshipProfile? {
        return try {
            val db = database.readableDatabase
            val cursor = db.query(TABLE_PROFILES, null, "person_id = ?",
                arrayOf(personId.toString()), null, null, null, "1")
            cursor.use { if (it.moveToFirst()) cursorToProfile(it) else null }
        } catch (_: Exception) { null }
    }

    fun getProfileByName(name: String): RelationshipProfile? {
        return try {
            val db = database.readableDatabase
            val cursor = db.query(TABLE_PROFILES, null, "person_name = ?",
                arrayOf(name), null, null, null, "1")
            cursor.use { if (it.moveToFirst()) cursorToProfile(it) else null }
        } catch (_: Exception) { null }
    }

    fun getAllProfiles(limit: Int = 50): List<RelationshipProfile> {
        return try {
            val db = database.readableDatabase
            val cursor = db.query(TABLE_PROFILES, null, null, null,
                null, null, "last_interaction_at DESC", "$limit")
            val list = mutableListOf<RelationshipProfile>()
            cursor.use { while (it.moveToNext()) list.add(cursorToProfile(it)) }
            list
        } catch (_: Exception) { emptyList() }
    }

    // ─── Conversation Journal ───

    fun recordConversation(entry: ConversationEntry) {
        try {
            val db = database.writableDatabase
            db.insertWithOnConflict(TABLE_JOURNAL, null, ContentValues().apply {
                put("id", entry.id)
                put("person_id", entry.personId)
                put("timestamp", entry.timestamp)
                put("location", entry.location)
                put("situation", entry.situation)
                put("topics", JSONArray(entry.topics).toString())
                put("key_points", JSONArray(entry.keyPoints).toString())
                put("promises", JSONArray(entry.promises.map { p ->
                    JSONObject().apply {
                        put("id", p.id)
                        put("content", p.content)
                        put("made_by", p.madeBy.name)
                        put("deadline", p.deadline ?: 0)
                        put("status", p.status.code)
                        put("created_at", p.createdAt)
                    }
                }).toString())
                put("emotion_observed", entry.emotionObserved)
                put("my_emotion", entry.myEmotion)
                put("transcript", entry.transcript)
                put("ai_summary", entry.aiSummary)
            }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)

            // Update profile with conversation data
            updateProfileFromConversation(entry)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record conversation: ${e.message}")
        }
    }

    private fun updateProfileFromConversation(entry: ConversationEntry) {
        val profile = getProfileByPersonId(entry.personId) ?: return
        val now = System.currentTimeMillis()

        // Merge topics
        val mergedTopics = (profile.topTopics + entry.topics)
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(5).map { it.key }

        // Track mood
        val newMoodHistory = if (entry.emotionObserved != null) {
            val snapshot = MoodSnapshot(entry.timestamp, entry.emotionObserved, 0.7f, entry.situation)
            (listOf(snapshot) + profile.moodHistory).take(10)
        } else profile.moodHistory

        // Update sentiment trend (EMA)
        val newSentiment = if (entry.emotionObserved != null) {
            val emotionVal = emotionToValence(entry.emotionObserved)
            profile.sentimentTrend * 0.8f + emotionVal * 0.2f
        } else profile.sentimentTrend

        // Pending promises
        val newPending = profile.pendingRequests.toMutableList()
        entry.promises.filter { it.status == PromiseStatus.PENDING }.forEach {
            newPending.add(it.content)
        }

        try {
            val db = database.writableDatabase
            db.update(TABLE_PROFILES, ContentValues().apply {
                put("top_topics", JSONArray(mergedTopics).toString())
                put("mood_history", JSONArray(newMoodHistory.map { m ->
                    JSONObject().apply {
                        put("timestamp", m.timestamp)
                        put("mood", m.mood)
                        put("confidence", m.confidence.toDouble())
                        put("context", m.context ?: "")
                    }
                }).toString())
                put("sentiment_trend", newSentiment)
                put("last_mood_observed", entry.emotionObserved)
                put("pending_requests", JSONArray(newPending.take(10)).toString())
                put("last_interaction_at", now)
                put("updated_at", now)
            }, "person_id = ?", arrayOf(entry.personId.toString()))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update profile from conversation: ${e.message}")
        }
    }

    // ─── Promise Tracking ───

    fun getPendingPromises(personId: Long): List<Promise> {
        return try {
            val db = database.readableDatabase
            val cursor = db.rawQuery(
                "SELECT promises FROM $TABLE_JOURNAL WHERE person_id = ? ORDER BY timestamp DESC LIMIT 20",
                arrayOf(personId.toString())
            )
            val promises = mutableListOf<Promise>()
            cursor.use {
                while (it.moveToNext()) {
                    val json = it.getString(0) ?: continue
                    val arr = JSONArray(json)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        if (obj.getInt("status") == PromiseStatus.PENDING.code) {
                            promises.add(Promise(
                                id = obj.getString("id"),
                                content = obj.getString("content"),
                                madeBy = PromiseActor.valueOf(obj.getString("made_by")),
                                deadline = obj.optLong("deadline", 0).let { d -> if (d == 0L) null else d },
                                status = PromiseStatus.PENDING,
                                createdAt = obj.getLong("created_at")
                            ))
                        }
                    }
                }
            }
            promises
        } catch (_: Exception) { emptyList() }
    }

    fun getAllPendingPromises(): List<Pair<String, Promise>> {
        val allProfiles = getAllProfiles()
        return allProfiles.flatMap { profile ->
            getPendingPromises(profile.personId).map { profile.personName to it }
        }
    }

    // ─── AI Prompt Context ───

    fun getRelationshipContext(personId: Long): String? {
        val profile = getProfileByPersonId(personId) ?: return null
        val pendingPromises = getPendingPromises(personId)
        val recentConvos = getRecentConversations(personId, 3)

        return buildString {
            appendLine("${profile.personName} (${profile.relationship.displayName}, " +
                    "친밀도 ${String.format("%.1f", profile.closenessScore)})")
            if (profile.lastMoodObserved != null) {
                appendLine("  최근 기분: ${profile.lastMoodObserved}")
            }
            if (profile.topTopics.isNotEmpty()) {
                appendLine("  주요 주제: ${profile.topTopics.joinToString(", ")}")
            }
            if (pendingPromises.isNotEmpty()) {
                pendingPromises.take(3).forEach { p ->
                    val actor = if (p.madeBy == PromiseActor.ME) "내가" else "상대가"
                    val deadline = p.deadline?.let {
                        val daysLeft = ((it - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).toInt()
                        " (D${if (daysLeft >= 0) "-$daysLeft" else "+${-daysLeft}"})"
                    } ?: ""
                    appendLine("  약속: $actor \"${p.content}\"$deadline")
                }
            }
            if (recentConvos.isNotEmpty()) {
                appendLine("  최근 대화: ${recentConvos.first().aiSummary.take(50)}")
            }
        }
    }

    // ─── Relationship Insights (for Briefing) ───

    fun detectRelationshipChanges(): List<RelationshipInsight> {
        val insights = mutableListOf<RelationshipInsight>()
        val profiles = getAllProfiles(20)
        val now = System.currentTimeMillis()

        for (profile in profiles) {
            // Check pending promises past deadline
            val overdue = getPendingPromises(profile.personId).filter {
                it.deadline != null && it.deadline < now
            }
            overdue.forEach { p ->
                val actor = if (p.madeBy == PromiseActor.ME) "내가" else "${profile.personName}이(가)"
                insights.add(RelationshipInsight(
                    personId = profile.personId,
                    personName = profile.personName,
                    type = RelationshipInsightType.BROKEN_PROMISE,
                    description = "$actor 약속한 '${p.content}' 기한 초과",
                    suggestedAction = if (p.madeBy == PromiseActor.ME) "리마인드: 약속 이행하세요" else "상대에게 확인해보세요",
                    priority = 2
                ))
            }

            // Check mood trend — 3 consecutive negative moods
            if (profile.moodHistory.size >= 3) {
                val recentNegative = profile.moodHistory.take(3).all {
                    emotionToValence(it.mood) < -0.3f
                }
                if (recentNegative) {
                    insights.add(RelationshipInsight(
                        personId = profile.personId,
                        personName = profile.personName,
                        type = RelationshipInsightType.MOOD_TREND,
                        description = "${profile.personName}의 기분이 최근 3회 연속 부정적",
                        suggestedAction = "안부를 먼저 물어보세요",
                        priority = 3
                    ))
                }
            }
        }

        return insights.sortedBy { it.priority }
    }

    // ─── Helpers ───

    private fun getRecentConversations(personId: Long, limit: Int): List<ConversationEntry> {
        return try {
            val db = database.readableDatabase
            val cursor = db.rawQuery(
                "SELECT * FROM $TABLE_JOURNAL WHERE person_id = ? ORDER BY timestamp DESC LIMIT ?",
                arrayOf(personId.toString(), limit.toString())
            )
            val list = mutableListOf<ConversationEntry>()
            cursor.use {
                while (it.moveToNext()) {
                    list.add(ConversationEntry(
                        id = it.getString(it.getColumnIndexOrThrow("id")),
                        personId = it.getLong(it.getColumnIndexOrThrow("person_id")),
                        timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp")),
                        location = it.getString(it.getColumnIndexOrThrow("location")),
                        situation = it.getString(it.getColumnIndexOrThrow("situation")),
                        topics = parseJsonArray(it.getString(it.getColumnIndexOrThrow("topics"))),
                        keyPoints = parseJsonArray(it.getString(it.getColumnIndexOrThrow("key_points"))),
                        emotionObserved = it.getString(it.getColumnIndexOrThrow("emotion_observed")),
                        myEmotion = it.getString(it.getColumnIndexOrThrow("my_emotion")),
                        transcript = it.getString(it.getColumnIndexOrThrow("transcript")),
                        aiSummary = it.getString(it.getColumnIndexOrThrow("ai_summary")) ?: ""
                    ))
                }
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    private fun emotionToValence(emotion: String): Float {
        return when (emotion.lowercase()) {
            "happy", "joy", "excited" -> 0.8f
            "neutral", "calm" -> 0.0f
            "sad", "disappointed" -> -0.5f
            "angry", "frustrated" -> -0.8f
            "tired", "exhausted" -> -0.3f
            "surprise" -> 0.3f
            else -> 0f
        }
    }

    private fun parseJsonArray(json: String?): List<String> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { emptyList() }
    }

    private fun profileToValues(profile: RelationshipProfile): ContentValues {
        return ContentValues().apply {
            put("id", profile.id)
            put("person_id", profile.personId)
            put("person_name", profile.personName)
            put("relationship_type", profile.relationship.code)
            put("closeness_score", profile.closenessScore)
            put("sentiment_trend", profile.sentimentTrend)
            put("interaction_frequency", profile.interactionFrequency)
            put("top_topics", JSONArray(profile.topTopics).toString())
            put("pending_requests", JSONArray(profile.pendingRequests).toString())
            put("shared_memories", JSONArray(profile.sharedMemories).toString())
            put("last_mood_observed", profile.lastMoodObserved)
            put("mood_history", JSONArray(profile.moodHistory.map { m ->
                JSONObject().apply {
                    put("timestamp", m.timestamp)
                    put("mood", m.mood)
                    put("confidence", m.confidence.toDouble())
                    put("context", m.context ?: "")
                }
            }).toString())
            put("communication_style", profile.communicationStyle)
            put("notable_traits", JSONArray(profile.notableTraits).toString())
            put("last_interaction_at", profile.lastInteractionAt)
            put("created_at", profile.createdAt)
            put("updated_at", profile.updatedAt)
        }
    }

    private fun cursorToProfile(cursor: android.database.Cursor): RelationshipProfile {
        return RelationshipProfile(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            personId = cursor.getLong(cursor.getColumnIndexOrThrow("person_id")),
            personName = cursor.getString(cursor.getColumnIndexOrThrow("person_name")),
            relationship = RelationshipType.fromCode(cursor.getInt(cursor.getColumnIndexOrThrow("relationship_type"))),
            closenessScore = cursor.getFloat(cursor.getColumnIndexOrThrow("closeness_score")),
            sentimentTrend = cursor.getFloat(cursor.getColumnIndexOrThrow("sentiment_trend")),
            interactionFrequency = cursor.getFloat(cursor.getColumnIndexOrThrow("interaction_frequency")),
            topTopics = parseJsonArray(cursor.getString(cursor.getColumnIndexOrThrow("top_topics"))),
            pendingRequests = parseJsonArray(cursor.getString(cursor.getColumnIndexOrThrow("pending_requests"))),
            sharedMemories = parseJsonArray(cursor.getString(cursor.getColumnIndexOrThrow("shared_memories"))),
            lastMoodObserved = cursor.getString(cursor.getColumnIndexOrThrow("last_mood_observed")),
            communicationStyle = cursor.getString(cursor.getColumnIndexOrThrow("communication_style")),
            notableTraits = parseJsonArray(cursor.getString(cursor.getColumnIndexOrThrow("notable_traits"))),
            lastInteractionAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_interaction_at")),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
        )
    }
}
