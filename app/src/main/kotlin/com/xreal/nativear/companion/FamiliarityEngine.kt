package com.xreal.nativear.companion

import android.content.ContentValues
import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.SceneDatabase
import com.xreal.nativear.context.LifeSituation
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.policy.PolicyReader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.ln
import kotlin.math.max

/**
 * FamiliarityEngine: Tracks encounter frequency and provides progressive insight depth.
 *
 * Instead of repeating "that's a flower pot" 47 times, the system:
 * - Tracks how many times each entity is seen (encounters)
 * - Calculates a familiarity score (0-1)
 * - Determines the next insight category to provide (progressive deepening)
 * - Avoids duplicate insights via insight_history tracking
 */
class FamiliarityEngine(
    private val database: UnifiedMemoryDatabase,
    private val sceneDatabase: SceneDatabase,
    private val eventBus: GlobalEventBus,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "FamiliarityEngine"
        private const val TABLE_FAMILIARITY = "entity_familiarity"
        private const val TABLE_INSIGHTS = "insight_history"
        private const val LOG2_101 = 6.6582f // ln(101)/ln(2)
    }

    private var eventJob: Job? = null

    // ─── Lifecycle ───

    fun start() {
        Log.i(TAG, "FamiliarityEngine started")
        eventJob = scope.launch(Dispatchers.Default) {
            eventBus.events.collectLatest { event ->
                try {
                    when (event) {
                        is XRealEvent.PerceptionEvent.ObjectsDetected -> {
                            event.results.forEach { det ->
                                recordEncounter(EntityType.OBJECT, det.label, null, null, null)
                            }
                        }
                        is XRealEvent.PerceptionEvent.PersonIdentified -> {
                            recordEncounter(
                                EntityType.PERSON,
                                event.personName ?: "Unknown",
                                event.personId.toString(),
                                null, null
                            )
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
        Log.i(TAG, "FamiliarityEngine stopped")
    }

    // ─── Encounter Recording ───

    fun recordEncounter(
        entityType: EntityType,
        label: String,
        refId: String?,
        situation: LifeSituation?,
        emotion: String?
    ): EntityFamiliarity {
        val existing = getEntity(entityType, label)

        return if (existing != null) {
            updateEncounter(existing, situation, emotion)
        } else {
            createEntity(entityType, label, refId, situation, emotion)
        }
    }

    private fun createEntity(
        entityType: EntityType,
        label: String,
        refId: String?,
        situation: LifeSituation?,
        emotion: String?
    ): EntityFamiliarity {
        val entity = EntityFamiliarity(
            entityType = entityType,
            entityLabel = label,
            entityRefId = refId,
            totalEncounters = 1,
            recentEncounters = 1,
            familiarityScore = calculateScore(1, 1, 0f, 0),
            familiarityLevel = FamiliarityLevel.STRANGER,
            contextDiversity = if (situation != null) 0.1f else 0f,
            emotionalValence = parseEmotionValence(emotion)
        )

        try {
            val db = database.writableDatabase
            db.insertWithOnConflict(TABLE_FAMILIARITY, null, entityToValues(entity),
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create entity: ${e.message}")
        }

        return entity
    }

    private fun updateEncounter(
        existing: EntityFamiliarity,
        situation: LifeSituation?,
        emotion: String?
    ): EntityFamiliarity {
        val now = System.currentTimeMillis()
        val recentWindowMs = PolicyReader.getLong("companion.recent_encounter_window_ms", 604_800_000L)
        val sevenDaysAgo = now - recentWindowMs
        val daysSinceFirst = ((now - existing.firstSeenAt) / (24 * 60 * 60 * 1000L)).toInt()

        val newTotal = existing.totalEncounters + 1
        val newRecent = countRecentEncounters(existing.id, sevenDaysAgo) + 1
        val diversityIncrement = PolicyReader.getFloat("companion.diversity_increment", 0.05f)
        val newDiversity = if (situation != null) {
            (existing.contextDiversity + diversityIncrement).coerceAtMost(1f)
        } else existing.contextDiversity
        val emotionEmaAlpha = PolicyReader.getFloat("companion.emotion_ema_alpha", 0.1f)
        val newEmotionValence = if (emotion != null) {
            existing.emotionalValence * (1f - emotionEmaAlpha) + parseEmotionValence(emotion) * emotionEmaAlpha
        } else existing.emotionalValence

        val newScore = calculateScore(newTotal, newRecent, newDiversity, daysSinceFirst)
        val newLevel = FamiliarityLevel.fromScore(newScore)

        try {
            val db = database.writableDatabase
            val values = ContentValues().apply {
                put("total_encounters", newTotal)
                put("recent_encounters", newRecent)
                put("last_seen_at", now)
                put("familiarity_score", newScore)
                put("familiarity_level", newLevel.ordinal)
                put("context_diversity", newDiversity)
                put("emotional_valence", newEmotionValence)
            }
            db.update(TABLE_FAMILIARITY, values, "id = ?", arrayOf(existing.id))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update encounter: ${e.message}")
        }

        val updated = existing.copy(
            totalEncounters = newTotal,
            recentEncounters = newRecent,
            lastSeenAt = now,
            familiarityScore = newScore,
            familiarityLevel = newLevel,
            contextDiversity = newDiversity,
            emotionalValence = newEmotionValence
        )

        // Emit level change event
        if (newLevel != existing.familiarityLevel) {
            scope.launch {
                eventBus.publish(XRealEvent.SystemEvent.DebugLog(
                    "친숙도 변화: ${existing.entityLabel} ${existing.familiarityLevel.displayName}→${newLevel.displayName}"
                ))
            }
        }

        return updated
    }

    // ─── Familiarity Score Calculation ───

    fun calculateScore(
        encounters: Int,
        recentEncounters: Int,
        contextDiversity: Float,
        daysSinceFirst: Int
    ): Float {
        // Base: logarithmic growth, 0→0, 100→1.0
        val base = (ln((encounters + 1).toDouble()) / ln(101.0)).toFloat()
        // Recency weight: favor entities seen recently
        val recency = 0.7f + 0.3f * (recentEncounters.toFloat() / max(encounters * 0.1f, 1f))
        // Diversity bonus: entities seen in varied contexts
        val diversity = 1.0f + 0.2f * contextDiversity
        return (base * recency * diversity).coerceIn(0f, 1f)
    }

    // ─── Insight Depth Management ───

    fun determineNextInsightCategory(familiarity: EntityFamiliarity): InsightCategory? {
        val level = familiarity.familiarityLevel
        val providedCategories = getProvidedCategories(familiarity.id)

        val availableByLevel = when (level) {
            FamiliarityLevel.STRANGER -> listOf(InsightCategory.IDENTIFICATION)
            FamiliarityLevel.RECOGNIZED -> listOf(InsightCategory.IDENTIFICATION, InsightCategory.FACTUAL)
            FamiliarityLevel.ACQUAINTANCE -> listOf(
                InsightCategory.IDENTIFICATION, InsightCategory.FACTUAL,
                InsightCategory.CONTEXTUAL, InsightCategory.RELATIONAL
            )
            FamiliarityLevel.FAMILIAR -> listOf(
                InsightCategory.IDENTIFICATION, InsightCategory.FACTUAL,
                InsightCategory.CONTEXTUAL, InsightCategory.RELATIONAL,
                InsightCategory.TEMPORAL, InsightCategory.CARE
            )
            FamiliarityLevel.INTIMATE -> InsightCategory.entries.toList()
            FamiliarityLevel.PROFOUND -> InsightCategory.entries.toList()
        }

        // Find deepest category not yet provided recently (7 days)
        return availableByLevel.reversed().firstOrNull { it !in providedCategories }
            ?: if (level >= FamiliarityLevel.INTIMATE) {
                // For intimate+, cycle through all categories with new perspectives
                InsightCategory.entries.random()
            } else null
    }

    fun hasProvidedSimilarInsight(entityId: String, category: InsightCategory, recentDays: Int = PolicyReader.getInt("companion.recent_insight_window_days", 7)): Boolean {
        return try {
            val cutoff = System.currentTimeMillis() - recentDays * 24 * 60 * 60 * 1000L
            val db = database.readableDatabase
            val cursor = db.rawQuery(
                "SELECT COUNT(*) FROM $TABLE_INSIGHTS WHERE entity_id = ? AND category = ? AND created_at > ?",
                arrayOf(entityId, category.code.toString(), cutoff.toString())
            )
            cursor.use { it.moveToFirst() && it.getInt(0) > 0 }
        } catch (_: Exception) { false }
    }

    fun recordInsight(entityId: String, category: InsightCategory, content: String, situation: String?) {
        try {
            val record = InsightRecord(
                entityId = entityId,
                depth = category.code,
                category = category,
                content = content,
                situation = situation
            )
            val db = database.writableDatabase
            db.insertWithOnConflict(TABLE_INSIGHTS, null, ContentValues().apply {
                put("id", record.id)
                put("entity_id", record.entityId)
                put("depth", record.depth)
                put("category", record.category.code)
                put("content", record.content)
                put("situation", record.situation)
                put("created_at", record.createdAt)
            }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)

            // Update insight depth on entity
            db.execSQL(
                "UPDATE $TABLE_FAMILIARITY SET insight_depth = MAX(insight_depth, ?), last_insight_at = ? WHERE id = ?",
                arrayOf(category.code, System.currentTimeMillis(), entityId)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record insight: ${e.message}")
        }
    }

    // ─── AI Prompt Context Generation ───

    fun getEntityContext(entityType: EntityType, label: String): String? {
        val entity = getEntity(entityType, label) ?: return null
        val nextCategory = determineNextInsightCategory(entity)
        val recentInsights = getRecentInsights(entity.id, 3)

        return buildString {
            appendLine("[${entity.entityLabel}] 친숙도: ${String.format("%.2f", entity.familiarityScore)} " +
                    "(${entity.familiarityLevel.displayName}, ${entity.totalEncounters}회)")
            if (recentInsights.isNotEmpty()) {
                appendLine("  이전 인사이트: ${recentInsights.joinToString(", ") { it.content.take(30) }}")
            }
            if (nextCategory != null) {
                appendLine("  다음 인사이트: ${nextCategory.displayName} 카테고리 (새 관점)")
            } else {
                appendLine("  → 이미 충분한 인사이트 제공됨. 변화가 있을 때만 반응하세요.")
            }
        }
    }

    fun buildFamiliarityContextForScene(labels: List<String>): String {
        val contexts = labels.mapNotNull { getEntityContext(EntityType.OBJECT, it) }
        if (contexts.isEmpty()) return ""
        return buildString {
            appendLine("[FAMILIARITY CONTEXT]")
            contexts.forEach { appendLine(it) }
        }
    }

    // ─── Query Methods ───

    fun getEntity(entityType: EntityType, label: String): EntityFamiliarity? {
        return try {
            val db = database.readableDatabase
            val cursor = db.query(
                TABLE_FAMILIARITY, null,
                "entity_type = ? AND entity_label = ?",
                arrayOf(entityType.code.toString(), label),
                null, null, null, "1"
            )
            cursor.use {
                if (it.moveToFirst()) cursorToEntity(it) else null
            }
        } catch (_: Exception) { null }
    }

    fun getEntityById(id: String): EntityFamiliarity? {
        return try {
            val db = database.readableDatabase
            val cursor = db.query(TABLE_FAMILIARITY, null, "id = ?", arrayOf(id),
                null, null, null, "1")
            cursor.use { if (it.moveToFirst()) cursorToEntity(it) else null }
        } catch (_: Exception) { null }
    }

    fun getTopFamiliarEntities(limit: Int = 20): List<EntityFamiliarity> {
        return try {
            val db = database.readableDatabase
            val cursor = db.query(TABLE_FAMILIARITY, null, null, null,
                null, null, "familiarity_score DESC", "$limit")
            val list = mutableListOf<EntityFamiliarity>()
            cursor.use { while (it.moveToNext()) list.add(cursorToEntity(it)) }
            list
        } catch (_: Exception) { emptyList() }
    }

    // ─── Helpers ───

    private fun getProvidedCategories(entityId: String): Set<InsightCategory> {
        val sevenDaysAgo = System.currentTimeMillis() - PolicyReader.getLong("companion.recent_encounter_window_ms", 604_800_000L)
        return try {
            val db = database.readableDatabase
            val cursor = db.rawQuery(
                "SELECT DISTINCT category FROM $TABLE_INSIGHTS WHERE entity_id = ? AND created_at > ?",
                arrayOf(entityId, sevenDaysAgo.toString())
            )
            val cats = mutableSetOf<InsightCategory>()
            cursor.use { while (it.moveToNext()) cats.add(InsightCategory.fromCode(it.getInt(0))) }
            cats
        } catch (_: Exception) { emptySet() }
    }

    private fun getRecentInsights(entityId: String, limit: Int): List<InsightRecord> {
        return try {
            val db = database.readableDatabase
            val cursor = db.rawQuery(
                "SELECT * FROM $TABLE_INSIGHTS WHERE entity_id = ? ORDER BY created_at DESC LIMIT ?",
                arrayOf(entityId, limit.toString())
            )
            val list = mutableListOf<InsightRecord>()
            cursor.use {
                while (it.moveToNext()) {
                    list.add(InsightRecord(
                        id = it.getString(it.getColumnIndexOrThrow("id")),
                        entityId = it.getString(it.getColumnIndexOrThrow("entity_id")),
                        depth = it.getInt(it.getColumnIndexOrThrow("depth")),
                        category = InsightCategory.fromCode(it.getInt(it.getColumnIndexOrThrow("category"))),
                        content = it.getString(it.getColumnIndexOrThrow("content")),
                        situation = it.getString(it.getColumnIndexOrThrow("situation")),
                        wasAppreciated = if (it.isNull(it.getColumnIndexOrThrow("was_appreciated"))) null
                            else it.getInt(it.getColumnIndexOrThrow("was_appreciated")) == 1,
                        createdAt = it.getLong(it.getColumnIndexOrThrow("created_at"))
                    ))
                }
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    private fun countRecentEncounters(entityId: String, sinceMs: Long): Int {
        // Approximate: use total - based on time ratio
        return try {
            val entity = getEntityById(entityId) ?: return 0
            val totalDays = ((System.currentTimeMillis() - entity.firstSeenAt) / (24 * 60 * 60 * 1000L)).toInt()
            if (totalDays <= 7) entity.totalEncounters
            else (entity.totalEncounters * 7.0 / totalDays).toInt().coerceAtLeast(1)
        } catch (_: Exception) { 1 }
    }

    private fun parseEmotionValence(emotion: String?): Float {
        if (emotion == null) return 0f
        return when (emotion.lowercase()) {
            "happy", "joy", "excited" -> 0.8f
            "neutral", "calm" -> 0.0f
            "sad", "disappointed" -> -0.5f
            "angry", "frustrated", "annoyed" -> -0.8f
            "fear", "anxious" -> -0.6f
            "surprise" -> 0.3f
            else -> 0f
        }
    }

    private fun entityToValues(entity: EntityFamiliarity): ContentValues {
        return ContentValues().apply {
            put("id", entity.id)
            put("entity_type", entity.entityType.code)
            put("entity_label", entity.entityLabel)
            put("entity_ref_id", entity.entityRefId)
            put("total_encounters", entity.totalEncounters)
            put("recent_encounters", entity.recentEncounters)
            put("first_seen_at", entity.firstSeenAt)
            put("last_seen_at", entity.lastSeenAt)
            put("familiarity_score", entity.familiarityScore)
            put("familiarity_level", entity.familiarityLevel.ordinal)
            put("insight_depth", entity.insightDepth)
            put("last_insight_at", entity.lastInsightAt)
            put("context_diversity", entity.contextDiversity)
            put("emotional_valence", entity.emotionalValence)
            put("metadata", entity.metadata)
        }
    }

    private fun cursorToEntity(cursor: android.database.Cursor): EntityFamiliarity {
        return EntityFamiliarity(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            entityType = EntityType.entries.firstOrNull {
                it.code == cursor.getInt(cursor.getColumnIndexOrThrow("entity_type"))
            } ?: EntityType.OBJECT,
            entityLabel = cursor.getString(cursor.getColumnIndexOrThrow("entity_label")),
            entityRefId = cursor.getString(cursor.getColumnIndexOrThrow("entity_ref_id")),
            totalEncounters = cursor.getInt(cursor.getColumnIndexOrThrow("total_encounters")),
            recentEncounters = cursor.getInt(cursor.getColumnIndexOrThrow("recent_encounters")),
            firstSeenAt = cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_at")),
            lastSeenAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_at")),
            familiarityScore = cursor.getFloat(cursor.getColumnIndexOrThrow("familiarity_score")),
            familiarityLevel = FamiliarityLevel.entries.getOrElse(
                cursor.getInt(cursor.getColumnIndexOrThrow("familiarity_level"))
            ) { FamiliarityLevel.STRANGER },
            insightDepth = cursor.getInt(cursor.getColumnIndexOrThrow("insight_depth")),
            lastInsightAt = if (cursor.isNull(cursor.getColumnIndexOrThrow("last_insight_at"))) null
                else cursor.getLong(cursor.getColumnIndexOrThrow("last_insight_at")),
            contextDiversity = cursor.getFloat(cursor.getColumnIndexOrThrow("context_diversity")),
            emotionalValence = cursor.getFloat(cursor.getColumnIndexOrThrow("emotional_valence")),
            metadata = cursor.getString(cursor.getColumnIndexOrThrow("metadata"))
        )
    }
}
