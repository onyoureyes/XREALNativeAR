package com.xreal.nativear.monitoring

import java.util.UUID

/**
 * AIActivityLog: Data types for AI activity tracking.
 *
 * Every AI call (LLM inference, tool call, deliberation) is logged
 * with token counts, latency, cost, and outcome.
 */

data class AIActivityRecord(
    val id: String = UUID.randomUUID().toString().take(12),
    val timestamp: Long = System.currentTimeMillis(),
    val expertId: String,
    val domainId: String? = null,
    val providerId: String,         // "GEMINI", "OPENAI", "CLAUDE", "GROK"
    val modelName: String,          // "gemini-2.0-flash", "gpt-4o-mini", etc.
    val action: String,             // "chat", "tool_call", "deliberation", "briefing"
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = 0,
    val costUsd: Float = 0f,
    val latencyMs: Long = 0,
    val situation: String? = null,
    val wasAccepted: Boolean? = null,
    val toolCalls: String? = null,  // JSON array of tool names
    val contextSummary: String? = null,
    val responseSummary: String? = null,
    val errorMessage: String? = null
)

data class ProviderPricing(
    val inputPer1M: Float,          // USD per 1M input tokens
    val outputPer1M: Float,         // USD per 1M output tokens
    val freeInputTokens: Long = 0L, // Free tier daily allowance
    val freeOutputTokens: Long = 0L
)

data class DailyUsageStats(
    val date: String,               // "2026-03-02"
    val totalCalls: Int = 0,
    val totalInputTokens: Long = 0,
    val totalOutputTokens: Long = 0,
    val totalCostUsd: Float = 0f,
    val avgLatencyMs: Long = 0,
    val byProvider: Map<String, ProviderStats> = emptyMap(),
    val byExpert: Map<String, ExpertStats> = emptyMap()
)

data class ProviderStats(
    val calls: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val costUsd: Float = 0f,
    val avgLatencyMs: Long = 0
)

data class ExpertStats(
    val calls: Int = 0,
    val tokens: Long = 0,
    val costUsd: Float = 0f,
    val acceptanceRate: Float = 0f
)
