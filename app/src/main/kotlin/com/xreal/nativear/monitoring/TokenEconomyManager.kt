package com.xreal.nativear.monitoring

import android.content.ContentValues
import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import java.text.SimpleDateFormat
import java.util.*

/**
 * TokenEconomyManager: Tracks AI token usage, costs, and provides
 * budget-aware recommendations for efficient AI usage.
 *
 * Pricing based on actual models used:
 * - gemini-2.0-flash: $0.10/$0.40 per 1M (input/output)
 * - gpt-4o-mini: $0.15/$0.60 per 1M
 * - claude-sonnet-4-20250514: $3.00/$15.00 per 1M
 * - grok-2: $2.00/$10.00 per 1M
 */
class TokenEconomyManager(
    private val database: UnifiedMemoryDatabase
) {
    companion object {
        private const val TAG = "TokenEconomy"
        private const val TABLE_AI_ACTIVITY = "ai_activity_log"

        val PRICING = mapOf(
            // --- Gemini ---
            "gemini-2.0-flash" to ProviderPricing(
                inputPer1M = 0.10f,
                outputPer1M = 0.40f,
                freeInputTokens = 1_000_000L,
                freeOutputTokens = 1_000_000L
            ),
            // --- OpenAI ---
            "gpt-4o-mini" to ProviderPricing(
                inputPer1M = 0.15f,
                outputPer1M = 0.60f,
                freeInputTokens = 0L,
                freeOutputTokens = 0L
            ),
            // --- Claude ---
            "claude-sonnet-4-20250514" to ProviderPricing(
                inputPer1M = 3.00f,
                outputPer1M = 15.00f,
                freeInputTokens = 0L,
                freeOutputTokens = 0L
            ),
            // --- Grok ---
            "grok-2" to ProviderPricing(
                inputPer1M = 2.00f,
                outputPer1M = 10.00f,
                freeInputTokens = 0L,
                freeOutputTokens = 0L
            )
        )

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    // ─── Record Activity ───

    fun logActivity(record: AIActivityRecord) {
        try {
            val costRecord = record.copy(
                costUsd = if (record.costUsd > 0) record.costUsd else
                    calculateCost(record.modelName, record.inputTokens, record.outputTokens)
            )

            val db = database.writableDatabase
            val values = ContentValues().apply {
                put("id", costRecord.id)
                put("timestamp", costRecord.timestamp)
                put("expert_id", costRecord.expertId)
                put("domain_id", costRecord.domainId)
                put("provider_id", costRecord.providerId)
                put("model_name", costRecord.modelName)
                put("action", costRecord.action)
                put("input_tokens", costRecord.inputTokens)
                put("output_tokens", costRecord.outputTokens)
                put("total_tokens", costRecord.totalTokens)
                put("cost_usd", costRecord.costUsd.toDouble())
                put("latency_ms", costRecord.latencyMs)
                put("situation", costRecord.situation)
                costRecord.wasAccepted?.let { put("was_accepted", if (it) 1 else 0) }
                put("tool_calls", costRecord.toolCalls)
                put("context_summary", costRecord.contextSummary)
                put("response_summary", costRecord.responseSummary)
                put("error_message", costRecord.errorMessage)
            }
            db.insertWithOnConflict(TABLE_AI_ACTIVITY, null, values,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)

            Log.d(TAG, "Activity: ${costRecord.expertId}/${costRecord.providerId} " +
                    "${costRecord.totalTokens}tok $${String.format("%.4f", costRecord.costUsd)} " +
                    "${costRecord.latencyMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log activity: ${e.message}")
        }
    }

    // ─── Cost Calculation ───

    fun calculateCost(modelName: String, inputTokens: Int, outputTokens: Int): Float {
        val pricing = PRICING[modelName] ?: return 0f

        // Calculate input cost (accounting for free tier)
        val todayInput = getTodayTokensByModel(modelName, isInput = true)
        val billableInput = maxOf(0L, (todayInput + inputTokens) - pricing.freeInputTokens)
        val prevBillableInput = maxOf(0L, todayInput - pricing.freeInputTokens)
        val inputCost = (billableInput - prevBillableInput) * pricing.inputPer1M / 1_000_000f

        // Calculate output cost
        val todayOutput = getTodayTokensByModel(modelName, isInput = false)
        val billableOutput = maxOf(0L, (todayOutput + outputTokens) - pricing.freeOutputTokens)
        val prevBillableOutput = maxOf(0L, todayOutput - pricing.freeOutputTokens)
        val outputCost = (billableOutput - prevBillableOutput) * pricing.outputPer1M / 1_000_000f

        return inputCost + outputCost
    }

    private fun getTodayTokensByModel(modelName: String, isInput: Boolean): Long {
        return try {
            val db = database.readableDatabase
            val todayStart = getTodayStartMs()
            val column = if (isInput) "input_tokens" else "output_tokens"
            val cursor = db.rawQuery(
                "SELECT COALESCE(SUM($column), 0) FROM $TABLE_AI_ACTIVITY " +
                        "WHERE model_name = ? AND timestamp >= ?",
                arrayOf(modelName, todayStart.toString())
            )
            cursor.use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            }
        } catch (_: Exception) { 0L }
    }

    // ─── Daily Stats ───

    fun getTodayUsage(): DailyUsageStats {
        val todayStart = getTodayStartMs()
        return getUsageForPeriod(todayStart, System.currentTimeMillis())
    }

    fun getUsageForPeriod(startMs: Long, endMs: Long): DailyUsageStats {
        return try {
            val db = database.readableDatabase

            // Overall stats
            val overallCursor = db.rawQuery("""
                SELECT
                    COUNT(*) as calls,
                    COALESCE(SUM(input_tokens), 0) as input_tok,
                    COALESCE(SUM(output_tokens), 0) as output_tok,
                    COALESCE(SUM(cost_usd), 0) as cost,
                    COALESCE(AVG(latency_ms), 0) as avg_latency
                FROM $TABLE_AI_ACTIVITY
                WHERE timestamp BETWEEN ? AND ?
            """.trimIndent(), arrayOf(startMs.toString(), endMs.toString()))

            var totalCalls = 0; var inputTok = 0L; var outputTok = 0L
            var totalCost = 0f; var avgLatency = 0L
            overallCursor.use {
                if (it.moveToFirst()) {
                    totalCalls = it.getInt(0)
                    inputTok = it.getLong(1)
                    outputTok = it.getLong(2)
                    totalCost = it.getFloat(3)
                    avgLatency = it.getLong(4)
                }
            }

            // Per-provider stats
            val providerMap = mutableMapOf<String, ProviderStats>()
            val provCursor = db.rawQuery("""
                SELECT provider_id,
                    COUNT(*) as calls,
                    COALESCE(SUM(input_tokens), 0),
                    COALESCE(SUM(output_tokens), 0),
                    COALESCE(SUM(cost_usd), 0),
                    COALESCE(AVG(latency_ms), 0)
                FROM $TABLE_AI_ACTIVITY
                WHERE timestamp BETWEEN ? AND ?
                GROUP BY provider_id
            """.trimIndent(), arrayOf(startMs.toString(), endMs.toString()))
            provCursor.use {
                while (it.moveToNext()) {
                    providerMap[it.getString(0)] = ProviderStats(
                        calls = it.getInt(1),
                        inputTokens = it.getLong(2),
                        outputTokens = it.getLong(3),
                        costUsd = it.getFloat(4),
                        avgLatencyMs = it.getLong(5)
                    )
                }
            }

            // Per-expert stats
            val expertMap = mutableMapOf<String, ExpertStats>()
            val expCursor = db.rawQuery("""
                SELECT expert_id,
                    COUNT(*) as calls,
                    COALESCE(SUM(total_tokens), 0),
                    COALESCE(SUM(cost_usd), 0),
                    COALESCE(AVG(CASE WHEN was_accepted = 1 THEN 1.0 ELSE 0.0 END), 0)
                FROM $TABLE_AI_ACTIVITY
                WHERE timestamp BETWEEN ? AND ?
                GROUP BY expert_id
            """.trimIndent(), arrayOf(startMs.toString(), endMs.toString()))
            expCursor.use {
                while (it.moveToNext()) {
                    expertMap[it.getString(0)] = ExpertStats(
                        calls = it.getInt(1),
                        tokens = it.getLong(2),
                        costUsd = it.getFloat(3),
                        acceptanceRate = it.getFloat(4)
                    )
                }
            }

            DailyUsageStats(
                date = dateFormat.format(Date(startMs)),
                totalCalls = totalCalls,
                totalInputTokens = inputTok,
                totalOutputTokens = outputTok,
                totalCostUsd = totalCost,
                avgLatencyMs = avgLatency,
                byProvider = providerMap,
                byExpert = expertMap
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get usage: ${e.message}")
            DailyUsageStats(date = dateFormat.format(Date()))
        }
    }

    // ─── Budget Summary ───

    fun getBudgetSummary(): String {
        val usage = getTodayUsage()
        val sb = StringBuilder()
        sb.appendLine("💰 오늘 AI 사용량:")
        sb.appendLine("  호출: ${usage.totalCalls}회")
        sb.appendLine("  토큰: ${formatTokens(usage.totalInputTokens + usage.totalOutputTokens)}")
        sb.appendLine("  비용: $${String.format("%.4f", usage.totalCostUsd)}")
        sb.appendLine("  평균 지연: ${usage.avgLatencyMs}ms")

        if (usage.byProvider.isNotEmpty()) {
            sb.appendLine("\n  제공자별:")
            usage.byProvider.forEach { (provider, stats) ->
                sb.appendLine("    $provider: ${stats.calls}회, ${formatTokens(stats.inputTokens + stats.outputTokens)}, $${String.format("%.4f", stats.costUsd)}")
            }
        }

        if (usage.byExpert.isNotEmpty()) {
            sb.appendLine("\n  전문가별:")
            usage.byExpert.entries.sortedByDescending { it.value.tokens }.take(5).forEach { (expert, stats) ->
                val rate = (stats.acceptanceRate * 100).toInt()
                sb.appendLine("    $expert: ${stats.calls}회, ${formatTokens(stats.tokens)}, 채택 ${rate}%")
            }
        }

        return sb.toString()
    }

    // ─── Token Gauge (for Debug HUD) ───

    data class TokenGauge(
        val modelName: String,
        val usedTokens: Long,
        val budgetTokens: Long,
        val usagePercent: Float,
        val costUsd: Float
    )

    fun getTokenGauges(): List<TokenGauge> {
        val todayStart = getTodayStartMs()
        return try {
            val db = database.readableDatabase
            val cursor = db.rawQuery("""
                SELECT model_name,
                    COALESCE(SUM(input_tokens + output_tokens), 0),
                    COALESCE(SUM(cost_usd), 0)
                FROM $TABLE_AI_ACTIVITY
                WHERE timestamp >= ?
                GROUP BY model_name
            """.trimIndent(), arrayOf(todayStart.toString()))

            val gauges = mutableListOf<TokenGauge>()
            cursor.use {
                while (it.moveToNext()) {
                    val model = it.getString(0)
                    val used = it.getLong(1)
                    val cost = it.getFloat(2)
                    // Budget: Gemini 500K, others 100K daily
                    val budget = if (model.contains("gemini", ignoreCase = true)) 500_000L else 100_000L
                    gauges.add(TokenGauge(
                        modelName = model,
                        usedTokens = used,
                        budgetTokens = budget,
                        usagePercent = (used.toFloat() / budget * 100f).coerceIn(0f, 100f),
                        costUsd = cost
                    ))
                }
            }
            gauges
        } catch (_: Exception) { emptyList() }
    }

    // ─── Helpers ───

    private fun getTodayStartMs(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun formatTokens(tokens: Long): String = when {
        tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
        tokens >= 1_000 -> "${tokens / 1_000}K"
        else -> "${tokens}"
    }
}
