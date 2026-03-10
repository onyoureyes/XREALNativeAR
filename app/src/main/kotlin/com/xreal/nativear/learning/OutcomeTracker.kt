package com.xreal.nativear.learning

import android.content.ContentValues
import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.context.LifeSituation
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.util.UUID

/**
 * OutcomeTracker: Measures AI intervention effectiveness and
 * builds a feedback loop for strategy optimization.
 *
 * Records every AI intervention (advice, action, HUD change),
 * then tracks the outcome (accepted, dismissed, ignored).
 * Over time, builds per-expert and per-situation effectiveness scores.
 *
 * Feedback signals:
 * - NOD gesture -> FOLLOWED (positive)
 * - SHAKE gesture -> DISMISSED (negative)
 * - Todo completed -> FOLLOWED
 * - Todo deadline exceeded -> IGNORED
 * - Voice "좋았어"/"별로야" -> sentiment mapping
 */
class OutcomeTracker(
    private val database: UnifiedMemoryDatabase,
    private val eventBus: GlobalEventBus,
    private val scope: CoroutineScope,
    private val agentEvolution: com.xreal.nativear.companion.AgentPersonalityEvolution? = null,
    private val analyticsService: com.xreal.nativear.analytics.SystemAnalyticsService? = null
) : IOutcomeRecorder {
    companion object {
        private const val TAG = "OutcomeTracker"
        private const val TABLE_INTERVENTIONS = "ai_interventions"
        private const val TABLE_STRATEGIES = "strategy_records"
        // Pending interventions waiting for outcome (max 20 in memory)
        private const val MAX_PENDING = 20
    }

    // In-memory pending interventions awaiting outcome
    private val pendingInterventions = mutableListOf<PendingIntervention>()
    private var eventJob: Job? = null
    private var cleanupJob: Job? = null

    data class PendingIntervention(
        val id: String,
        val expertId: String,
        val situation: String,
        val action: String,
        val timestamp: Long
    )

    override fun start() {
        Log.i(TAG, "OutcomeTracker started")
        eventJob = scope.launch(Dispatchers.Default) {
            eventBus.events.collectLatest { event ->
                try {
                    processEvent(event)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing event: ${e.message}")
                }
            }
        }

        // Cleanup expired pending interventions every 10 minutes
        cleanupJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(600_000L)
                cleanupExpiredPending()
            }
        }
    }

    override fun stop() {
        eventJob?.cancel()
        cleanupJob?.cancel()
        Log.i(TAG, "OutcomeTracker stopped")
    }

    // ─── Record an AI Intervention ───

    override fun recordIntervention(
        expertId: String,
        situation: LifeSituation,
        action: String,
        contextSummary: String?
    ): String {
        val id = UUID.randomUUID().toString().take(12)
        try {
            val db = database.writableDatabase
            val values = ContentValues().apply {
                put("id", id)
                put("expert_id", expertId)
                put("situation", situation.name)
                put("action", action)
                put("context_summary", contextSummary)
                put("timestamp", System.currentTimeMillis())
                put("outcome", InterventionOutcome.PENDING.ordinal)
            }
            db.insertWithOnConflict(TABLE_INTERVENTIONS, null, values,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)

            // Add to pending list for outcome tracking
            synchronized(pendingInterventions) {
                if (pendingInterventions.size >= MAX_PENDING) {
                    // Mark oldest as IGNORED
                    val oldest = pendingInterventions.removeAt(0)
                    recordOutcome(oldest.id, InterventionOutcome.IGNORED)
                }
                pendingInterventions.add(PendingIntervention(
                    id = id, expertId = expertId,
                    situation = situation.name, action = action,
                    timestamp = System.currentTimeMillis()
                ))
            }

            Log.d(TAG, "Intervention recorded: $id by $expertId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record intervention: ${e.message}")
        }
        return id
    }

    // ─── Record Outcome ───

    override fun recordOutcome(
        interventionId: String,
        outcome: InterventionOutcome,
        satisfaction: Float?,
        notes: String?
    ) {
        try {
            val db = database.writableDatabase
            val values = ContentValues().apply {
                put("outcome", outcome.ordinal)
                put("outcome_timestamp", System.currentTimeMillis())
                satisfaction?.let { put("satisfaction", it.toDouble()) }
                notes?.let { put("notes", it) }
            }
            db.update(TABLE_INTERVENTIONS, values, "id = ?", arrayOf(interventionId))

            // Remove from pending
            synchronized(pendingInterventions) {
                pendingInterventions.removeAll { it.id == interventionId }
            }

            // Update strategy record + propagate to AgentEvolution + Analytics
            val cursor = db.query(TABLE_INTERVENTIONS, null, "id = ?",
                arrayOf(interventionId), null, null, null, "1")
            cursor.use {
                if (it.moveToFirst()) {
                    val expertId = it.getString(it.getColumnIndexOrThrow("expert_id"))
                    val situation = it.getString(it.getColumnIndexOrThrow("situation"))
                    val action = it.getString(it.getColumnIndexOrThrow("action"))
                    updateStrategy(expertId, situation, action, outcome, satisfaction)

                    // Gap I: 실시간 outcome 이벤트 발행 — 5분 반성 주기를 기다리지 않음
                    scope.launch {
                        try {
                            eventBus.publish(XRealEvent.SystemEvent.OutcomeRecorded(
                                interventionId = interventionId,
                                expertId = expertId,
                                outcome = outcome.name,
                                situation = situation,
                                action = action.take(100)
                            ))
                        } catch (e: Exception) {
                            Log.w(TAG, "OutcomeRecorded 이벤트 발행 실패: ${e.message}")
                        }
                    }

                    // Propagate to AgentPersonalityEvolution (feedback → growth)
                    try {
                        agentEvolution?.recordExperience(
                            agentId = expertId,
                            outcome = outcome,
                            context = "$situation: ${action.take(50)}",
                            wasSuccessful = (outcome == InterventionOutcome.FOLLOWED)
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "AgentEvolution propagation failed: ${e.message}")
                    }

                    // Propagate to SystemAnalyticsService decision log
                    try {
                        analyticsService?.updateDecisionOutcome(
                            decisionId = interventionId,
                            outcome = outcome.name,
                            satisfaction = satisfaction ?: if (outcome == InterventionOutcome.FOLLOWED) 0.8f else 0.2f
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Analytics outcome propagation failed: ${e.message}")
                    }
                }
            }

            Log.d(TAG, "Outcome recorded: $interventionId -> $outcome (→ AgentEvolution + Analytics)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record outcome: ${e.message}")
        }
    }

    // ─── Strategy Records ───

    private fun updateStrategy(
        expertId: String,
        situation: String,
        action: String,
        outcome: InterventionOutcome,
        satisfaction: Float?
    ) {
        try {
            val db = database.writableDatabase
            val stratKey = "${expertId}_${situation}_${action.hashCode()}"

            // Upsert strategy record
            val cursor = db.query(TABLE_STRATEGIES, null, "id = ?",
                arrayOf(stratKey), null, null, null, "1")
            val exists = cursor.use { it.moveToFirst() }

            if (exists) {
                // Update existing
                val updateSql = when (outcome) {
                    InterventionOutcome.FOLLOWED -> """
                        UPDATE $TABLE_STRATEGIES SET
                            success_count = success_count + 1,
                            total_count = total_count + 1,
                            effectiveness = CAST(success_count + 1 AS REAL) / CAST(total_count + 1 AS REAL),
                            last_used_at = ?
                        WHERE id = ?
                    """.trimIndent()
                    InterventionOutcome.DISMISSED -> """
                        UPDATE $TABLE_STRATEGIES SET
                            total_count = total_count + 1,
                            effectiveness = CAST(success_count AS REAL) / CAST(total_count + 1 AS REAL),
                            last_used_at = ?
                        WHERE id = ?
                    """.trimIndent()
                    else -> """
                        UPDATE $TABLE_STRATEGIES SET
                            total_count = total_count + 1,
                            effectiveness = CAST(success_count AS REAL) / CAST(total_count + 1 AS REAL),
                            last_used_at = ?
                        WHERE id = ?
                    """.trimIndent()
                }
                db.execSQL(updateSql, arrayOf(System.currentTimeMillis(), stratKey))
            } else {
                // Insert new
                val isSuccess = outcome == InterventionOutcome.FOLLOWED
                val values = ContentValues().apply {
                    put("id", stratKey)
                    put("expert_id", expertId)
                    put("situation", situation)
                    put("action_summary", action.take(100))
                    put("success_count", if (isSuccess) 1 else 0)
                    put("total_count", 1)
                    put("effectiveness", if (isSuccess) 1.0 else 0.0)
                    put("avg_satisfaction", (satisfaction ?: if (isSuccess) 0.8f else 0.3f).toDouble())
                    put("first_used_at", System.currentTimeMillis())
                    put("last_used_at", System.currentTimeMillis())
                }
                db.insertWithOnConflict(TABLE_STRATEGIES, null, values,
                    android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update strategy: ${e.message}")
        }
    }

    // ─── Query API ───

    override fun getExpertEffectiveness(expertId: String): Float {
        return try {
            val db = database.readableDatabase
            val cursor = db.rawQuery(
                "SELECT AVG(effectiveness) FROM $TABLE_STRATEGIES WHERE expert_id = ? AND total_count >= 3",
                arrayOf(expertId))
            cursor.use {
                if (it.moveToFirst()) it.getFloat(0) else 0.5f
            }
        } catch (_: Exception) { 0.5f }
    }

    override fun getSituationStrategies(situation: LifeSituation, limit: Int): List<StrategyRecord> {
        return try {
            val db = database.readableDatabase
            val cursor = db.query(TABLE_STRATEGIES, null,
                "situation = ? AND total_count >= 2",
                arrayOf(situation.name), null, null,
                "effectiveness DESC", "$limit")
            val list = mutableListOf<StrategyRecord>()
            cursor.use {
                while (it.moveToNext()) {
                    list.add(cursorToStrategy(it))
                }
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    override fun getBestStrategyFor(expertId: String, situation: LifeSituation): StrategyRecord? {
        return try {
            val db = database.readableDatabase
            val cursor = db.query(TABLE_STRATEGIES, null,
                "expert_id = ? AND situation = ? AND total_count >= 3",
                arrayOf(expertId, situation.name), null, null,
                "effectiveness DESC", "1")
            cursor.use {
                if (it.moveToFirst()) cursorToStrategy(it) else null
            }
        } catch (_: Exception) { null }
    }

    override fun getOverallStats(): OutcomeStats {
        return try {
            val db = database.readableDatabase
            val cursor = db.rawQuery("""
                SELECT
                    COUNT(*) as total,
                    SUM(CASE WHEN outcome = ${InterventionOutcome.FOLLOWED.ordinal} THEN 1 ELSE 0 END) as followed,
                    SUM(CASE WHEN outcome = ${InterventionOutcome.DISMISSED.ordinal} THEN 1 ELSE 0 END) as dismissed,
                    SUM(CASE WHEN outcome = ${InterventionOutcome.IGNORED.ordinal} THEN 1 ELSE 0 END) as ignored,
                    AVG(CASE WHEN satisfaction IS NOT NULL THEN satisfaction END) as avg_satisfaction
                FROM $TABLE_INTERVENTIONS
                WHERE timestamp > ?
            """.trimIndent(), arrayOf("${System.currentTimeMillis() - 7 * 86400_000L}"))
            cursor.use {
                if (it.moveToFirst()) {
                    OutcomeStats(
                        totalInterventions = it.getInt(0),
                        followed = it.getInt(1),
                        dismissed = it.getInt(2),
                        ignored = it.getInt(3),
                        avgSatisfaction = if (it.isNull(4)) null else it.getFloat(4)
                    )
                } else OutcomeStats()
            }
        } catch (_: Exception) { OutcomeStats() }
    }

    /**
     * StrategistReflector 피드백 루프용: 최근 전략 성과 요약 텍스트 반환.
     * StrategistService.runReflectionCycle()에서 호출 → reflector.reflect()에 전달.
     *
     * 포함 내용:
     * - 효과적인 전략 (effectiveness ≥ 0.5, 최근 7일)
     * - 실패한 전략 (effectiveness < 0.3)
     * - 최근 24시간 개입 결과 (FOLLOWED/DISMISSED/IGNORED)
     */
    override fun getRecentOutcomeSummary(): String {
        return try {
            val db = database.readableDatabase
            val weekAgo = System.currentTimeMillis() - 7 * 86400_000L
            val dayAgo  = System.currentTimeMillis() - 86400_000L

            // 효과적인 전략 (top 10)
            val topSb = StringBuilder()
            db.rawQuery(
                """SELECT expert_id, situation, action_summary, success_count, total_count, effectiveness
                   FROM $TABLE_STRATEGIES
                   WHERE total_count >= 2 AND last_used_at > $weekAgo AND effectiveness >= 0.5
                   ORDER BY effectiveness DESC LIMIT 10""",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val eff = c.getFloat(5)
                    topSb.appendLine("  - [${c.getString(0)}/${c.getString(1)}] ${c.getString(2).take(60)} (${c.getInt(3)}/${c.getInt(4)}, ${String.format("%.0f", eff * 100)}%)")
                }
            }

            // 실패한 전략 (bottom 5)
            val failSb = StringBuilder()
            db.rawQuery(
                """SELECT expert_id, situation, action_summary, effectiveness
                   FROM $TABLE_STRATEGIES
                   WHERE total_count >= 2 AND last_used_at > $weekAgo AND effectiveness < 0.3
                   ORDER BY last_used_at DESC LIMIT 5""",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    failSb.appendLine("  - [${c.getString(0)}/${c.getString(1)}] ${c.getString(2).take(60)} (${String.format("%.0f", c.getFloat(3) * 100)}%)")
                }
            }

            // 최근 24시간 개입 결과
            val recentSb = StringBuilder()
            val outcomeNames = mapOf(
                InterventionOutcome.PENDING.ordinal   to "대기",
                InterventionOutcome.FOLLOWED.ordinal  to "따름",
                InterventionOutcome.DISMISSED.ordinal to "기각",
                InterventionOutcome.IGNORED.ordinal   to "무시"
            )
            db.rawQuery(
                """SELECT expert_id, situation, action, outcome
                   FROM $TABLE_INTERVENTIONS
                   WHERE timestamp > $dayAgo AND outcome != ${InterventionOutcome.PENDING.ordinal}
                   ORDER BY timestamp DESC LIMIT 15""",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val name = outcomeNames[c.getInt(3)] ?: "알수없음"
                    recentSb.appendLine("  - [${c.getString(0)}/${c.getString(1)}] $name: ${c.getString(2).take(60)}")
                }
            }

            buildString {
                appendLine("[AI 개입 성과 데이터 (피드백 루프)]")
                appendLine("▶ 효과적인 전략 (7일, effectiveness ≥ 50%):")
                if (topSb.isBlank()) appendLine("  데이터 없음") else append(topSb)
                appendLine("▶ 개선 필요 전략 (7일, effectiveness < 30%):")
                if (failSb.isBlank()) appendLine("  데이터 없음") else append(failSb)
                appendLine("▶ 최근 24시간 개입 결과:")
                if (recentSb.isBlank()) appendLine("  데이터 없음") else append(recentSb)
            }.trim()
        } catch (e: Exception) {
            Log.w(TAG, "getRecentOutcomeSummary 실패: ${e.message}")
            ""
        }
    }

    // ─── Event Processing ───

    private fun processEvent(event: XRealEvent) {
        when (event) {
            // NOD gesture = positive feedback for most recent intervention
            is XRealEvent.InputEvent.Gesture -> {
                when (event.type) {
                    com.xreal.nativear.core.GestureType.NOD -> recordLatestPendingOutcome(InterventionOutcome.FOLLOWED)
                    com.xreal.nativear.core.GestureType.SHAKE -> recordLatestPendingOutcome(InterventionOutcome.DISMISSED)
                    else -> { /* ignore other gestures */ }
                }
            }
            // Voice feedback (Fix 2: voice → outcome pipeline)
            is XRealEvent.InputEvent.VoiceFeedback -> {
                val outcome = when (event.sentiment) {
                    com.xreal.nativear.core.FeedbackSentiment.POSITIVE -> InterventionOutcome.FOLLOWED
                    com.xreal.nativear.core.FeedbackSentiment.NEGATIVE -> InterventionOutcome.DISMISSED
                    com.xreal.nativear.core.FeedbackSentiment.NEUTRAL -> InterventionOutcome.IGNORED
                }
                Log.d(TAG, "Voice feedback: '${event.text}' → $outcome")
                recordLatestPendingOutcome(outcome, satisfaction = event.confidenceScore)
            }
            else -> { /* ignore */ }
        }
    }

    private fun recordLatestPendingOutcome(outcome: InterventionOutcome, satisfaction: Float? = null) {
        synchronized(pendingInterventions) {
            val latest = pendingInterventions.lastOrNull() ?: return
            // Only if recent (within 60 seconds)
            if (System.currentTimeMillis() - latest.timestamp < 60_000L) {
                scope.launch(Dispatchers.IO) {
                    recordOutcome(latest.id, outcome, satisfaction)
                }
            }
        }
    }

    private fun cleanupExpiredPending() {
        synchronized(pendingInterventions) {
            val cutoff = System.currentTimeMillis() - 300_000L // 5 minutes
            val expired = pendingInterventions.filter { it.timestamp < cutoff }
            expired.forEach { pending ->
                scope.launch(Dispatchers.IO) {
                    recordOutcome(pending.id, InterventionOutcome.IGNORED)
                }
            }
            pendingInterventions.removeAll { it.timestamp < cutoff }
        }
    }

    private fun cursorToStrategy(cursor: android.database.Cursor): StrategyRecord {
        return StrategyRecord(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            expertId = cursor.getString(cursor.getColumnIndexOrThrow("expert_id")),
            situation = cursor.getString(cursor.getColumnIndexOrThrow("situation")),
            actionSummary = cursor.getString(cursor.getColumnIndexOrThrow("action_summary")),
            successCount = cursor.getInt(cursor.getColumnIndexOrThrow("success_count")),
            totalCount = cursor.getInt(cursor.getColumnIndexOrThrow("total_count")),
            effectiveness = cursor.getFloat(cursor.getColumnIndexOrThrow("effectiveness")),
            avgSatisfaction = cursor.getFloat(cursor.getColumnIndexOrThrow("avg_satisfaction")),
            firstUsedAt = cursor.getLong(cursor.getColumnIndexOrThrow("first_used_at")),
            lastUsedAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_used_at"))
        )
    }
}
