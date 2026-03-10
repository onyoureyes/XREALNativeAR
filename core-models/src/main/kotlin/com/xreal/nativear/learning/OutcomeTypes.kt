package com.xreal.nativear.learning

// ─── Outcome Data Types ───
// OutcomeTracker에서 추출 — 순수 데이터 타입 (Android 의존 없음)

enum class InterventionOutcome {
    PENDING,
    FOLLOWED,       // User accepted/acted on advice
    DISMISSED,      // User explicitly rejected
    IGNORED,        // No response within timeout
    PARTIAL         // Partially followed
}

data class StrategyRecord(
    val id: String,
    val expertId: String,
    val situation: String,
    val actionSummary: String,
    val successCount: Int,
    val totalCount: Int,
    val effectiveness: Float,
    val avgSatisfaction: Float,
    val firstUsedAt: Long,
    val lastUsedAt: Long
)

data class OutcomeStats(
    val totalInterventions: Int = 0,
    val followed: Int = 0,
    val dismissed: Int = 0,
    val ignored: Int = 0,
    val avgSatisfaction: Float? = null
) {
    val acceptanceRate: Float get() = if (totalInterventions > 0)
        followed.toFloat() / totalInterventions else 0f
}
