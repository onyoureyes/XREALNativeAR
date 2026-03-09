package com.xreal.nativear.analytics

import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.SceneDatabase
import com.xreal.nativear.core.GlobalEventBus
import java.text.SimpleDateFormat
import java.util.*

/**
 * SystemAnalyticsService: System-wide singleton data mining service.
 *
 * Follows DigitalTwinBuilder pattern: Pure Kotlin DB mining, ZERO AI calls.
 *
 * 5 Roles:
 * ① Mission Briefing Build (pre-fetch → SharedMissionContext, 0 tokens)
 * ② Tool Call Result Logging (audit trail for all tool results)
 * ③ Token Usage History Logging (daily per-provider snapshots)
 * ④ Statistics Aggregation (expert/student/IEP/token/system reports)
 * ⑤ Pattern Analysis → Edge Delegation Candidate Extraction
 */
class SystemAnalyticsService(
    private val database: UnifiedMemoryDatabase,
    private val sceneDatabase: SceneDatabase,
    private val eventBus: GlobalEventBus
) {
    private val TAG = "SystemAnalytics"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    // ── Data Classes ──

    data class MissionBriefing(
        val domainSummary: String,
        val expertStatsSummary: String,
        val peopleSummary: String,
        val iepSummary: String,
        val recentObsSummary: String
    )

    data class EdgeCandidate(
        val patternHash: String,
        val situation: String?,
        val actionType: String?,
        val expertId: String?,
        val totalCount: Int,
        val avgSatisfaction: Float,
        val followedCount: Int
    )

    // ══════════════════════════════════════════════════════════════
    // ① Mission Briefing (MissionConductor calls before plan generation)
    //    0 AI tokens — pure DB reads
    // ══════════════════════════════════════════════════════════════

    fun generateMissionBriefing(
        briefingDomains: List<String>,
        expertIds: List<String>,
        lookbackDays: Int = 90
    ): MissionBriefing {
        val since = System.currentTimeMillis() - lookbackDays * 86_400_000L

        // 1. Domain data summaries (structured_data)
        val domainSummary = buildDomainSummary(briefingDomains, since)

        // 2. Expert performance stats
        val expertStatsSummary = buildExpertStatsSummary(expertIds)

        // 3. People context (relationship_profiles)
        val peopleSummary = buildPeopleSummary()

        // 4. IEP progress (sped-specific but generalized)
        val iepSummary = buildIepSummary(since)

        // 5. Recent observations summary
        val recentObsSummary = buildRecentObsSummary(briefingDomains, limit = 20)

        Log.i(TAG, "Mission briefing generated: ${briefingDomains.size} domains, ${expertIds.size} experts")
        return MissionBriefing(domainSummary, expertStatsSummary, peopleSummary, iepSummary, recentObsSummary)
    }

    private fun buildDomainSummary(domains: List<String>, since: Long): String {
        if (domains.isEmpty()) return "No domains specified."
        val sb = StringBuilder()
        for (domain in domains) {
            try {
                val records = database.queryStructuredData(domain, limit = 10)
                sb.appendLine("[$domain] ${records.size} recent records")
                for (record in records.take(5)) {
                    val keyPreview = record.dataKey.take(30)
                    val valuePreview = record.value.take(80)
                    sb.appendLine("  • $keyPreview: $valuePreview")
                }
            } catch (e: Exception) {
                sb.appendLine("[$domain] query failed: ${e.message}")
            }
        }
        return sb.toString().take(2000)
    }

    private fun buildExpertStatsSummary(expertIds: List<String>): String {
        val sb = StringBuilder()
        try {
            val db = database.readableDatabase
            for (expertId in expertIds) {
                // Effectiveness from strategy_records
                val effCursor = db.rawQuery(
                    "SELECT AVG(effectiveness), COUNT(*), SUM(total_count) FROM strategy_records WHERE expert_id = ? AND total_count >= 2",
                    arrayOf(expertId)
                )
                var effectiveness = 0.5f; var strategyCount = 0; var totalInterventions = 0
                if (effCursor.moveToFirst()) {
                    effectiveness = effCursor.getFloat(0)
                    strategyCount = effCursor.getInt(1)
                    totalInterventions = effCursor.getInt(2)
                }
                effCursor.close()

                // Growth stage from agent_characters
                val charCursor = db.rawQuery(
                    "SELECT growth_stage, acquired_traits FROM agent_characters WHERE agent_id = ?",
                    arrayOf(expertId)
                )
                var growthStage = "UNKNOWN"; var traits = ""
                if (charCursor.moveToFirst()) {
                    growthStage = charCursor.getString(0) ?: "NEWBORN"
                    traits = charCursor.getString(1) ?: ""
                }
                charCursor.close()

                sb.appendLine("$expertId: effectiveness=${String.format("%.0f%%", effectiveness * 100)}, strategies=$strategyCount, interventions=$totalInterventions, growth=$growthStage, traits=[$traits]")
            }
        } catch (e: Exception) {
            sb.appendLine("Expert stats query failed: ${e.message}")
        }
        return sb.toString().take(1000)
    }

    private fun buildPeopleSummary(): String {
        val sb = StringBuilder()
        try {
            val db = database.readableDatabase
            val cursor = db.rawQuery(
                "SELECT person_name, relationship_type, closeness_score, last_mood_observed FROM relationship_profiles ORDER BY closeness_score DESC LIMIT 15",
                null
            )
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: "?"
                val type = cursor.getString(1) ?: "?"
                val closeness = cursor.getFloat(2)
                val mood = cursor.getString(3) ?: "?"
                sb.appendLine("$name ($type, closeness=${String.format("%.1f", closeness)}, mood=$mood)")
            }
            cursor.close()
        } catch (e: Exception) {
            sb.appendLine("People query failed: ${e.message}")
        }
        return sb.toString().take(800)
    }

    private fun buildIepSummary(since: Long): String {
        try {
            val iepRecords = database.queryStructuredData("sped_iep", limit = 20)
            if (iepRecords.isEmpty()) return "No IEP data yet."
            val sb = StringBuilder("IEP Goals (${iepRecords.size}):\n")
            for (record in iepRecords) {
                sb.appendLine("  • ${record.dataKey}: ${record.value.take(60)}")
            }
            return sb.toString().take(1000)
        } catch (e: Exception) {
            return "IEP query failed: ${e.message}"
        }
    }

    private fun buildRecentObsSummary(domains: List<String>, limit: Int): String {
        val obsDomain = domains.find { it.contains("obs") } ?: return "No observation domain."
        try {
            val records = database.queryStructuredData(obsDomain, limit = limit)
            if (records.isEmpty()) return "No observations yet."
            val sb = StringBuilder("Recent Observations (${records.size}):\n")
            for (record in records.take(10)) {
                sb.appendLine("  • ${record.dataKey}: ${record.value.take(60)}")
            }
            return sb.toString().take(1000)
        } catch (e: Exception) {
            return "Observation query failed: ${e.message}"
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ② Tool Call Result Logging (audit trail)
    // ══════════════════════════════════════════════════════════════

    fun logToolResult(
        expertId: String?,
        toolName: String,
        args: Map<String, Any?>,
        result: String,
        tokensUsed: Int = 0,
        latencyMs: Long = 0
    ) {
        try {
            val argsJson = args.entries.joinToString(", ") { "${it.key}=${it.value}" }.take(300)
            database.insertToolActivityLog(
                expertId = expertId,
                toolName = toolName,
                argsJson = argsJson,
                resultSummary = result.take(500),
                tokensUsed = tokensUsed,
                latencyMs = latencyMs
            )
        } catch (e: Exception) {
            Log.w(TAG, "Tool activity log failed: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ③ Token Usage History Logging (daily snapshots)
    // ══════════════════════════════════════════════════════════════

    fun logDailyTokenUsage(
        providerId: String,
        tokensUsed: Int,
        budget: Int,
        tier: String
    ) {
        try {
            val date = dateFormat.format(Date())
            database.upsertTokenUsageLog(date, providerId, tokensUsed, budget, tier)
            Log.d(TAG, "Token usage logged: $providerId $tokensUsed/$budget ($tier)")
        } catch (e: Exception) {
            Log.w(TAG, "Token usage log failed: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ④ Statistics Aggregation (AI tools call these)
    // ══════════════════════════════════════════════════════════════

    fun getExpertPerformanceReport(expertId: String? = null): String {
        val sb = StringBuilder("=== Expert Performance Report ===\n")
        try {
            val db = database.readableDatabase

            val whereClause = if (expertId != null) "WHERE expert_id = ?" else ""
            val args = if (expertId != null) arrayOf(expertId) else emptyArray()

            // Strategy records summary
            val cursor = db.rawQuery("""
                SELECT expert_id,
                       AVG(effectiveness) as avg_eff,
                       COUNT(*) as strategy_count,
                       SUM(total_count) as total_interventions,
                       SUM(success_count) as total_success
                FROM strategy_records
                $whereClause
                GROUP BY expert_id
                ORDER BY avg_eff DESC
            """.trimIndent(), args)

            while (cursor.moveToNext()) {
                val eid = cursor.getString(0)
                val avgEff = cursor.getFloat(1)
                val stratCount = cursor.getInt(2)
                val totalIntv = cursor.getInt(3)
                val totalSuccess = cursor.getInt(4)
                sb.appendLine("$eid: effectiveness=${String.format("%.0f%%", avgEff * 100)}, strategies=$stratCount, interventions=$totalIntv, successes=$totalSuccess")
            }
            cursor.close()

            // Agent growth stages
            val charCursor = db.rawQuery("""
                SELECT agent_id, growth_stage, trust_score, acquired_traits
                FROM agent_characters
                ${if (expertId != null) "WHERE agent_id = ?" else ""}
                ORDER BY trust_score DESC
            """.trimIndent(), args)

            sb.appendLine("\n--- Growth Status ---")
            while (charCursor.moveToNext()) {
                val aid = charCursor.getString(0)
                val stage = charCursor.getString(1) ?: "?"
                val trust = charCursor.getFloat(2)
                val traits = charCursor.getString(3) ?: ""
                sb.appendLine("$aid: stage=$stage, trust=${String.format("%.2f", trust)}, traits=[$traits]")
            }
            charCursor.close()
        } catch (e: Exception) {
            sb.appendLine("Error: ${e.message}")
        }
        return sb.toString()
    }

    fun getStudentProgressReport(studentKey: String? = null): String {
        val sb = StringBuilder("=== Student Progress Report ===\n")
        try {
            // Student profiles
            val students = database.queryStructuredData("sped_students", dataKey = studentKey, limit = 30)
            sb.appendLine("Registered Students: ${students.size}")
            for (student in students) {
                sb.appendLine("\n[${student.dataKey}]")
                sb.appendLine("  Profile: ${student.value.take(100)}")

                // Observation count
                val obs = database.queryStructuredData("sped_obs", dataKey = student.dataKey, limit = 50)
                sb.appendLine("  Observations: ${obs.size}")

                // IEP goals
                val iep = database.queryStructuredData("sped_iep", dataKey = student.dataKey, limit = 10)
                sb.appendLine("  IEP Goals: ${iep.size}")
                for (goal in iep) {
                    sb.appendLine("    • ${goal.dataKey}: ${goal.value.take(60)}")
                }

                // Activity outcomes
                val outcomes = database.queryStructuredData("sped_outcomes", dataKey = student.dataKey, limit = 10)
                sb.appendLine("  Activity Outcomes: ${outcomes.size}")
            }
        } catch (e: Exception) {
            sb.appendLine("Error: ${e.message}")
        }
        return sb.toString()
    }

    fun getTokenUsageReport(days: Int = 7): String {
        val sb = StringBuilder("=== Token Usage Report (${days}d) ===\n")
        try {
            val logs = database.queryTokenUsageLog(days)
            if (logs.isEmpty()) {
                sb.appendLine("No token usage history yet.")
                return sb.toString()
            }

            // Group by provider
            val byProvider = logs.groupBy { it["provider_id"] as? String ?: "?" }
            for ((provider, entries) in byProvider) {
                val totalUsed = entries.sumOf { (it["tokens_used"] as? Int) ?: 0 }
                val budget = entries.firstOrNull()?.get("budget") as? Int ?: 0
                val avgDaily = if (entries.isNotEmpty()) totalUsed / entries.size else 0
                val latestTier = entries.firstOrNull()?.get("tier") ?: "?"
                sb.appendLine("$provider: total=${totalUsed}tk, avg_daily=${avgDaily}tk, budget=${budget}tk, tier=$latestTier")
            }

            // Daily breakdown
            sb.appendLine("\n--- Daily Breakdown ---")
            val byDate = logs.groupBy { it["date"] as? String ?: "?" }
            for ((date, entries) in byDate.entries.take(days)) {
                val dayTotal = entries.sumOf { (it["tokens_used"] as? Int) ?: 0 }
                val providers = entries.joinToString(", ") {
                    val p = it["provider_id"] as? String ?: "?"
                    val t = it["tokens_used"] as? Int ?: 0
                    "$p:${t}tk"
                }
                sb.appendLine("$date: $dayTotal total ($providers)")
            }
        } catch (e: Exception) {
            sb.appendLine("Error: ${e.message}")
        }
        return sb.toString()
    }

    fun getSystemHealthReport(): String {
        val sb = StringBuilder("=== System Health Report ===\n")
        try {
            val db = database.readableDatabase

            // Memory nodes count
            val memCursor = db.rawQuery("SELECT COUNT(*) FROM memory_nodes", null)
            if (memCursor.moveToFirst()) sb.appendLine("Memory Nodes: ${memCursor.getInt(0)}")
            memCursor.close()

            // Structured data domains
            val domainCursor = db.rawQuery(
                "SELECT domain, COUNT(*) FROM structured_data GROUP BY domain ORDER BY COUNT(*) DESC", null
            )
            sb.appendLine("\n--- Structured Data Domains ---")
            while (domainCursor.moveToNext()) {
                sb.appendLine("  ${domainCursor.getString(0)}: ${domainCursor.getInt(1)} records")
            }
            domainCursor.close()

            // Recent tool activity
            val toolCursor = db.rawQuery(
                "SELECT tool_name, COUNT(*), AVG(latency_ms) FROM tool_activity_log WHERE timestamp > ? GROUP BY tool_name ORDER BY COUNT(*) DESC LIMIT 10",
                arrayOf((System.currentTimeMillis() - 7 * 86_400_000L).toString())
            )
            sb.appendLine("\n--- Tool Activity (7d) ---")
            while (toolCursor.moveToNext()) {
                sb.appendLine("  ${toolCursor.getString(0)}: ${toolCursor.getInt(1)} calls, avg ${toolCursor.getLong(2)}ms")
            }
            toolCursor.close()

            // Expert effectiveness summary
            val effCursor = db.rawQuery(
                "SELECT expert_id, AVG(effectiveness), SUM(total_count) FROM strategy_records GROUP BY expert_id ORDER BY AVG(effectiveness) DESC",
                null
            )
            sb.appendLine("\n--- Expert Effectiveness ---")
            while (effCursor.moveToNext()) {
                sb.appendLine("  ${effCursor.getString(0)}: ${String.format("%.0f%%", effCursor.getFloat(1) * 100)} (${effCursor.getInt(2)} interventions)")
            }
            effCursor.close()

            // Edge delegation candidates
            val edgeCandidates = database.queryEdgeDelegationCandidates()
            sb.appendLine("\n--- Edge Delegation Candidates ---")
            if (edgeCandidates.isEmpty()) {
                sb.appendLine("  None yet (need more decision data)")
            } else {
                for (c in edgeCandidates.take(5)) {
                    sb.appendLine("  pattern=${c["pattern_hash"]}: ${c["total"]}x, satisfaction=${String.format("%.0f%%", ((c["avg_satisfaction"] as? Float) ?: 0f) * 100)}")
                }
            }
        } catch (e: Exception) {
            sb.appendLine("Error: ${e.message}")
        }

        // ★ Provider latency stats (24h)
        sb.append("\n")
        sb.append(getProviderLatencyStats(24))

        return sb.toString()
    }

    // ══════════════════════════════════════════════════════════════
    // ⑤ Decision Logging + Pattern Analysis (Edge Delegation)
    // ══════════════════════════════════════════════════════════════

    fun logDecision(
        situation: String?,
        contextHash: String?,
        expertId: String?,
        actionType: String?,
        toolCalls: List<String>?,
        responseSummary: String?,
        tokensUsed: Int = 0,
        latencyMs: Long = 0
    ): String {
        val id = UUID.randomUUID().toString()
        try {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            database.insertDecisionLog(
                id = id,
                situation = situation,
                contextHash = contextHash,
                visibleObjects = null,
                userState = null,
                hourOfDay = hour,
                expertId = expertId,
                actionType = actionType,
                toolCalls = toolCalls?.joinToString(","),
                responseSummary = responseSummary,
                tokensUsed = tokensUsed,
                latencyMs = latencyMs
            )
        } catch (e: Exception) {
            Log.w(TAG, "Decision log failed: ${e.message}")
        }
        return id
    }

    fun updateDecisionOutcome(decisionId: String, outcome: String, satisfaction: Float = 0f) {
        try {
            database.updateDecisionOutcome(decisionId, outcome, satisfaction)
        } catch (e: Exception) {
            Log.w(TAG, "Decision outcome update failed: ${e.message}")
        }
    }

    fun analyzeEdgeCandidates(): List<EdgeCandidate> {
        return try {
            database.queryEdgeDelegationCandidates().map { row ->
                EdgeCandidate(
                    patternHash = row["pattern_hash"] as? String ?: "",
                    situation = row["situation"] as? String,
                    actionType = row["action_type"] as? String,
                    expertId = row["expert_id"] as? String,
                    totalCount = row["total"] as? Int ?: 0,
                    avgSatisfaction = row["avg_satisfaction"] as? Float ?: 0f,
                    followedCount = row["followed_count"] as? Int ?: 0
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Edge analysis failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * 프로바이더별 응답 레이턴시 통계 (lookbackHours 내 decision_log 집계).
     *
     * - P50(평균), P_max, 5초 초과 비율로 속도/품질 트레이드오프 판단 지원
     * - StrategistReflector 반성 컨텍스트에 포함 → Gemini가 느린 AI 교체 권고 가능
     * - EdgeDelegationRouter.isServerTooSlow()와 보완 관계: 이쪽은 5분 주기 감사(Audit)
     *
     * @param lookbackHours 집계 기간 (기본 24시간)
     * @return 사람/AI 모두 읽기 좋은 형식의 통계 문자열
     */
    fun getProviderLatencyStats(lookbackHours: Int = 24): String {
        val since = System.currentTimeMillis() - lookbackHours * 3_600_000L
        val sb = StringBuilder("=== Provider Latency Stats (${lookbackHours}h) ===\n")
        try {
            val db = database.readableDatabase
            val cursor = db.rawQuery("""
                SELECT expert_id,
                       COUNT(*) as cnt,
                       CAST(AVG(latency_ms) AS INTEGER) as avg_ms,
                       MAX(latency_ms) as max_ms,
                       SUM(CASE WHEN latency_ms > 5000 THEN 1 ELSE 0 END) * 100 / COUNT(*) as slow_pct
                FROM decision_log
                WHERE latency_ms > 0 AND timestamp > ?
                GROUP BY expert_id
                ORDER BY avg_ms ASC
            """.trimIndent(), arrayOf(since.toString()))

            if (!cursor.moveToFirst()) {
                sb.appendLine("  No latency data yet (need AI calls with latencyMs > 0).")
            } else {
                do {
                    val eid = cursor.getString(0) ?: continue
                    val cnt = cursor.getInt(1)
                    val avgMs = cursor.getLong(2)
                    val maxMs = cursor.getLong(3)
                    val slowPct = cursor.getInt(4)
                    val rating = when {
                        avgMs < 2_000 -> "✅ Fast"
                        avgMs < 5_000 -> "⚡ OK"
                        avgMs < 8_000 -> "⚠️ Slow"
                        else          -> "🔴 Critical"
                    }
                    sb.appendLine("  $eid: avg=${avgMs}ms, max=${maxMs}ms, slow>5s=${slowPct}%, n=$cnt  $rating")
                } while (cursor.moveToNext())
            }
            cursor.close()
        } catch (e: Exception) {
            sb.appendLine("  Latency stats query failed: ${e.message}")
            Log.w(TAG, "getProviderLatencyStats error: ${e.message}")
        }
        return sb.toString()
    }

    /**
     * Run daily edge delegation report (called by StrategistService).
     * Saves results to structured_data for later retrieval.
     */
    fun generateEdgeDelegationReport(): String {
        val candidates = analyzeEdgeCandidates()
        if (candidates.isEmpty()) return "No edge delegation candidates found."

        val report = candidates.joinToString("\n") { c ->
            "${c.expertId}/${c.situation}/${c.actionType}: ${c.totalCount}x, satisfaction=${String.format("%.0f%%", c.avgSatisfaction * 100)}, followed=${c.followedCount}"
        }

        // Save to structured_data for persistence
        try {
            val today = dateFormat.format(Date())
            database.upsertStructuredData(
                domain = "edge_delegation_report",
                dataKey = "report_$today",
                value = report,
                tags = "edge,ml,delegation"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Edge report save failed: ${e.message}")
        }

        return report
    }
}
