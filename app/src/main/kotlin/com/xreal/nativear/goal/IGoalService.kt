package com.xreal.nativear.goal

import kotlinx.coroutines.flow.StateFlow

/**
 * IGoalService: 목표 관리 및 진행 추적 인터페이스.
 * 구현체: GoalTracker
 */
interface IGoalService {
    val activeGoals: StateFlow<List<Goal>>
    fun start()
    fun stop()
    fun createGoal(goal: Goal): Goal
    fun updateGoal(goal: Goal): Boolean
    fun getGoal(goalId: String): Goal?
    fun getActiveGoals(timeframe: GoalTimeframe? = null): List<Goal>
    fun getChildGoals(parentGoalId: String): List<Goal>
    fun completeGoal(goalId: String): Boolean
    fun recordProgress(goalId: String, delta: Float, source: String, notes: String? = null): Boolean
    fun getGoalSummary(goalId: String): GoalSummary?
    fun getRecentProgress(goalId: String, limit: Int = 10): List<GoalProgress>
    fun getTodaySummary(): String
}
