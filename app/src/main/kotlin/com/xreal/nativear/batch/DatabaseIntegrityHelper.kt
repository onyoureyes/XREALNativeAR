package com.xreal.nativear.batch

import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.SceneDatabase
import kotlinx.coroutines.*

/**
 * DatabaseIntegrityHelper: Maintains referential integrity across the dual-database system.
 *
 * SQLite limitations:
 * - Cannot add FK constraints via ALTER TABLE
 * - Cannot have cross-database foreign keys
 *
 * This helper provides:
 * 1. Cross-DB referential integrity checks (persons ↔ relationship_profiles)
 * 2. Orphan record cleanup (entries referencing deleted parents)
 * 3. AI activity trail linking via indexes
 * 4. Voice transcript deduplication audit
 * 5. Periodic integrity verification (run in batch)
 *
 * Architecture decision: Application-level integrity with indexed columns
 * instead of DB-level FK constraints, due to dual-database architecture.
 */
class DatabaseIntegrityHelper(
    private val unifiedDb: UnifiedMemoryDatabase,
    private val sceneDb: SceneDatabase,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "DBIntegrity"
    }

    // ─── Integrity Check ───

    fun runIntegrityCheck(): IntegrityReport {
        val issues = mutableListOf<IntegrityIssue>()

        // 1. Check orphan relationship_profiles (person_id not in SceneDB.persons)
        try {
            val profilePersonIds = mutableSetOf<Long>()
            val profileCursor = unifiedDb.readableDatabase.rawQuery(
                "SELECT DISTINCT person_id FROM relationship_profiles", null
            )
            profileCursor.use {
                while (it.moveToNext()) profilePersonIds.add(it.getLong(0))
            }

            val validPersonIds = mutableSetOf<Long>()
            val personCursor = sceneDb.readableDatabase.rawQuery(
                "SELECT id FROM persons", null
            )
            personCursor.use {
                while (it.moveToNext()) validPersonIds.add(it.getLong(0))
            }

            val orphanProfiles = profilePersonIds - validPersonIds
            if (orphanProfiles.isNotEmpty()) {
                issues.add(IntegrityIssue(
                    table = "relationship_profiles",
                    type = IssueType.ORPHAN_FK,
                    count = orphanProfiles.size,
                    description = "relationship_profiles with person_id not in SceneDB.persons: $orphanProfiles"
                ))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Relationship profile check failed: ${e.message}")
        }

        // 2. Check orphan conversation_journal entries
        try {
            val orphanConvCursor = unifiedDb.readableDatabase.rawQuery(
                """SELECT COUNT(*) FROM conversation_journal cj
                   WHERE NOT EXISTS (
                       SELECT 1 FROM relationship_profiles rp WHERE rp.person_id = cj.person_id
                   )""", null
            )
            orphanConvCursor.use {
                if (it.moveToFirst()) {
                    val count = it.getInt(0)
                    if (count > 0) {
                        issues.add(IntegrityIssue(
                            table = "conversation_journal",
                            type = IssueType.ORPHAN_FK,
                            count = count,
                            description = "conversation_journal entries with no matching relationship_profile"
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Conversation journal check failed: ${e.message}")
        }

        // 3. Check orphan goal_progress entries
        try {
            val orphanGoalCursor = unifiedDb.readableDatabase.rawQuery(
                """SELECT COUNT(*) FROM goal_progress gp
                   WHERE NOT EXISTS (
                       SELECT 1 FROM user_goals g WHERE g.id = gp.goal_id
                   )""", null
            )
            orphanGoalCursor.use {
                if (it.moveToFirst()) {
                    val count = it.getInt(0)
                    if (count > 0) {
                        issues.add(IntegrityIssue(
                            table = "goal_progress",
                            type = IssueType.ORPHAN_FK,
                            count = count,
                            description = "goal_progress entries with no matching user_goals"
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Goal progress check failed: ${e.message}")
        }

        // 4. Check orphan insight_history entries
        try {
            val orphanInsightCursor = unifiedDb.readableDatabase.rawQuery(
                """SELECT COUNT(*) FROM insight_history ih
                   WHERE NOT EXISTS (
                       SELECT 1 FROM entity_familiarity ef WHERE ef.id = ih.entity_id
                   )""", null
            )
            orphanInsightCursor.use {
                if (it.moveToFirst()) {
                    val count = it.getInt(0)
                    if (count > 0) {
                        issues.add(IntegrityIssue(
                            table = "insight_history",
                            type = IssueType.ORPHAN_FK,
                            count = count,
                            description = "insight_history entries with no matching entity_familiarity"
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Insight history check failed: ${e.message}")
        }

        // 5. Check orphan agent_journal entries
        try {
            val orphanJournalCursor = unifiedDb.readableDatabase.rawQuery(
                """SELECT COUNT(*) FROM agent_journal aj
                   WHERE NOT EXISTS (
                       SELECT 1 FROM agent_characters ac WHERE ac.agent_id = aj.agent_id
                   )""", null
            )
            orphanJournalCursor.use {
                if (it.moveToFirst()) {
                    val count = it.getInt(0)
                    if (count > 0) {
                        issues.add(IntegrityIssue(
                            table = "agent_journal",
                            type = IssueType.ORPHAN_FK,
                            count = count,
                            description = "agent_journal entries with no matching agent_characters"
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Agent journal check failed: ${e.message}")
        }

        // 6. Voice transcript duplication audit
        try {
            val memoryVoiceCount = unifiedDb.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM memory_nodes WHERE role = 'WHISPER' OR role = 'PASSIVE_VOICE'", null
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

            val voiceLogCount = sceneDb.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM voice_logs", null
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

            if (memoryVoiceCount > 0 && voiceLogCount > 0) {
                issues.add(IntegrityIssue(
                    table = "voice_logs + memory_nodes",
                    type = IssueType.DUPLICATION,
                    count = minOf(memoryVoiceCount, voiceLogCount),
                    description = "Voice transcripts duplicated: memory_nodes($memoryVoiceCount) + voice_logs($voiceLogCount). " +
                            "memory_nodes stores text, voice_logs adds emotion+embedding."
                ))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Voice transcript check failed: ${e.message}")
        }

        val report = IntegrityReport(
            checkedAt = System.currentTimeMillis(),
            totalIssues = issues.size,
            issues = issues
        )

        Log.i(TAG, "Integrity check: ${issues.size} issues found")
        issues.forEach { Log.d(TAG, "  ${it.type}: ${it.table} — ${it.description}") }

        return report
    }

    // ─── Orphan Cleanup ───

    fun cleanupOrphans(): Int {
        var cleaned = 0

        // Clean orphan goal_progress
        try {
            val count = unifiedDb.writableDatabase.delete(
                "goal_progress",
                "goal_id NOT IN (SELECT id FROM user_goals)",
                null
            )
            if (count > 0) {
                Log.i(TAG, "Cleaned $count orphan goal_progress entries")
                cleaned += count
            }
        } catch (e: Exception) {
            Log.w(TAG, "Goal progress cleanup failed: ${e.message}")
        }

        // Clean orphan insight_history
        try {
            val count = unifiedDb.writableDatabase.delete(
                "insight_history",
                "entity_id NOT IN (SELECT id FROM entity_familiarity)",
                null
            )
            if (count > 0) {
                Log.i(TAG, "Cleaned $count orphan insight_history entries")
                cleaned += count
            }
        } catch (e: Exception) {
            Log.w(TAG, "Insight history cleanup failed: ${e.message}")
        }

        // Clean orphan agent_journal
        try {
            val count = unifiedDb.writableDatabase.delete(
                "agent_journal",
                "agent_id NOT IN (SELECT agent_id FROM agent_characters)",
                null
            )
            if (count > 0) {
                Log.i(TAG, "Cleaned $count orphan agent_journal entries")
                cleaned += count
            }
        } catch (e: Exception) {
            Log.w(TAG, "Agent journal cleanup failed: ${e.message}")
        }

        // Clean orphan conversation_journal
        try {
            val count = unifiedDb.writableDatabase.delete(
                "conversation_journal",
                "person_id NOT IN (SELECT person_id FROM relationship_profiles)",
                null
            )
            if (count > 0) {
                Log.i(TAG, "Cleaned $count orphan conversation_journal entries")
                cleaned += count
            }
        } catch (e: Exception) {
            Log.w(TAG, "Conversation journal cleanup failed: ${e.message}")
        }

        Log.i(TAG, "Total orphans cleaned: $cleaned")
        return cleaned
    }

    // ─── AI Activity Trail Linking ───

    /**
     * Get unified AI activity trail for a specific expert, joining across tables.
     * Links: ai_activity_log ↔ ai_interventions ↔ strategy_records
     */
    fun getExpertActivityTrail(expertId: String, limit: Int = 50): List<ActivityTrailEntry> {
        val trail = mutableListOf<ActivityTrailEntry>()

        try {
            // AI activity log entries
            val activityCursor = unifiedDb.readableDatabase.rawQuery(
                """SELECT timestamp, action, total_tokens, cost_usd, was_accepted, response_summary
                   FROM ai_activity_log
                   WHERE expert_id = ?
                   ORDER BY timestamp DESC LIMIT ?""",
                arrayOf(expertId, limit.toString())
            )
            activityCursor.use {
                while (it.moveToNext()) {
                    trail.add(ActivityTrailEntry(
                        timestamp = it.getLong(0),
                        source = "ai_activity_log",
                        action = it.getString(1) ?: "",
                        tokensUsed = it.getInt(2),
                        cost = it.getDouble(3).toFloat(),
                        wasAccepted = if (it.isNull(4)) null else it.getInt(4) == 1,
                        summary = it.getString(5)
                    ))
                }
            }

            // Interventions
            val intervCursor = unifiedDb.readableDatabase.rawQuery(
                """SELECT timestamp, action, outcome, satisfaction, context_summary
                   FROM ai_interventions
                   WHERE expert_id = ?
                   ORDER BY timestamp DESC LIMIT ?""",
                arrayOf(expertId, limit.toString())
            )
            intervCursor.use {
                while (it.moveToNext()) {
                    trail.add(ActivityTrailEntry(
                        timestamp = it.getLong(0),
                        source = "ai_interventions",
                        action = it.getString(1) ?: "",
                        tokensUsed = 0,
                        cost = 0f,
                        wasAccepted = if (it.isNull(2)) null else it.getInt(2) == 1,
                        summary = it.getString(4)
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Activity trail query failed: ${e.message}")
        }

        return trail.sortedByDescending { it.timestamp }.take(limit)
    }

    // ─── Person Data Cross-DB Sync ───

    /**
     * Syncs person data between SceneDB.persons and UnifiedDB.relationship_profiles.
     * Updates person name changes and detects inconsistencies.
     */
    fun syncPersonData(): Int {
        var synced = 0

        try {
            // Get all persons from SceneDB
            val persons = mutableMapOf<Long, String>() // id → name
            val personCursor = sceneDb.readableDatabase.rawQuery(
                "SELECT id, name FROM persons WHERE name IS NOT NULL", null
            )
            personCursor.use {
                while (it.moveToNext()) {
                    persons[it.getLong(0)] = it.getString(1)
                }
            }

            // Update relationship_profiles where name changed
            val profileCursor = unifiedDb.readableDatabase.rawQuery(
                "SELECT id, person_id, person_name FROM relationship_profiles", null
            )
            profileCursor.use {
                while (it.moveToNext()) {
                    val profileId = it.getString(0)
                    val personId = it.getLong(1)
                    val currentName = it.getString(2)
                    val canonicalName = persons[personId]

                    if (canonicalName != null && canonicalName != currentName) {
                        val values = android.content.ContentValues().apply {
                            put("person_name", canonicalName)
                            put("updated_at", System.currentTimeMillis())
                        }
                        unifiedDb.writableDatabase.update(
                            "relationship_profiles", values,
                            "id = ?", arrayOf(profileId)
                        )
                        Log.d(TAG, "Synced person name: $currentName → $canonicalName (person_id=$personId)")
                        synced++
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Person sync failed: ${e.message}")
        }

        return synced
    }
}

// ─── Data Types ───

data class IntegrityReport(
    val checkedAt: Long,
    val totalIssues: Int,
    val issues: List<IntegrityIssue>
)

data class IntegrityIssue(
    val table: String,
    val type: IssueType,
    val count: Int,
    val description: String
)

enum class IssueType {
    ORPHAN_FK,      // Reference to non-existent parent
    DUPLICATION,    // Same data in multiple tables
    INCONSISTENCY,  // Data mismatch between related tables
    MISSING_INDEX   // Missing index for common query pattern
}

data class ActivityTrailEntry(
    val timestamp: Long,
    val source: String,     // which table
    val action: String,
    val tokensUsed: Int,
    val cost: Float,
    val wasAccepted: Boolean?,
    val summary: String?
)
