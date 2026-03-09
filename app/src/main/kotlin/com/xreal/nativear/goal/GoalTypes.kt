package com.xreal.nativear.goal

import java.util.UUID

/**
 * GoalTypes: Hierarchical goal tracking system.
 *
 * Supports day -> week -> month -> quarter -> year goal hierarchy,
 * with automatic progress tracking from events (running sessions,
 * todo completions, etc.) and streak tracking.
 */

data class Goal(
    val id: String = UUID.randomUUID().toString().take(12),
    val title: String,
    val description: String? = null,
    val timeframe: GoalTimeframe,
    val parentGoalId: String? = null,
    val targetValue: Float? = null,
    val currentValue: Float = 0f,
    val unit: String? = null,
    val status: GoalStatus = GoalStatus.ACTIVE,
    val assignedDomain: String? = null,
    val category: GoalCategory = GoalCategory.GENERAL,
    val createdAt: Long = System.currentTimeMillis(),
    val deadline: Long? = null,
    val completedAt: Long? = null,
    val streakDays: Int = 0,
    val lastProgressAt: Long? = null,
    val milestones: List<Milestone> = emptyList()
)

enum class GoalTimeframe(val displayName: String, val maxDurationDays: Int) {
    DAY("일간", 1),
    WEEK("주간", 7),
    MONTH("월간", 31),
    QUARTER("분기", 92),
    YEAR("연간", 366)
}

enum class GoalStatus {
    ACTIVE,
    COMPLETED,
    FAILED,
    PAUSED,
    CANCELLED
}

enum class GoalCategory(val displayName: String) {
    HEALTH("건강"),
    FITNESS("운동"),
    PRODUCTIVITY("생산성"),
    LEARNING("학습"),
    MUSIC("음악"),
    SOCIAL("소셜"),
    FINANCE("재정"),
    MINDFULNESS("마인드풀니스"),
    GENERAL("일반")
}

data class Milestone(
    val id: String = UUID.randomUUID().toString().take(8),
    val title: String,
    val targetValue: Float,
    val reachedAt: Long? = null,
    val isReached: Boolean = false
)

/**
 * GoalProgress: Tracks a single progress update event.
 */
data class GoalProgress(
    val id: String = UUID.randomUUID().toString().take(12),
    val goalId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val previousValue: Float,
    val newValue: Float,
    val delta: Float,
    val source: String,       // "running_session", "todo_completed", "manual", "ai_tracked"
    val notes: String? = null
)

/**
 * GoalSummary: Aggregated goal statistics for display.
 */
data class GoalSummary(
    val goal: Goal,
    val progressPercent: Float,
    val childGoals: List<Goal>,
    val recentProgress: List<GoalProgress>,
    val isOnTrack: Boolean,
    val projectedCompletion: Long?
)
