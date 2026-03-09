package com.xreal.nativear.goal

import android.content.ContentValues
import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * GoalTracker: Manages hierarchical goals with automatic progress tracking.
 *
 * Listens to EventBus for progress signals:
 * - RunningSession completed -> update running/fitness goals
 * - Todo completed -> update productivity/daily goals
 * - Practice sessions -> update music/learning goals
 *
 * Also provides manual CRUD and streak tracking.
 */
class GoalTracker(
    private val database: UnifiedMemoryDatabase,
    private val eventBus: GlobalEventBus,
    private val scope: CoroutineScope
) : IGoalService {
    companion object {
        private const val TAG = "GoalTracker"
        private const val TABLE_GOALS = "user_goals"
        private const val TABLE_GOAL_PROGRESS = "goal_progress"
    }

    private val _activeGoals = MutableStateFlow<List<Goal>>(emptyList())
    override val activeGoals: StateFlow<List<Goal>> = _activeGoals

    private var eventJob: Job? = null
    private var streakJob: Job? = null

    override fun start() {
        Log.i(TAG, "GoalTracker started")
        refreshActiveGoals()

        // Listen for progress-triggering events
        eventJob = scope.launch(Dispatchers.Default) {
            eventBus.events.collectLatest { event ->
                try {
                    processEvent(event)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing event: ${e.message}")
                }
            }
        }

        // Daily streak check (every hour)
        streakJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(3600_000L)
                updateStreaks()
            }
        }
    }

    override fun stop() {
        eventJob?.cancel()
        streakJob?.cancel()
        Log.i(TAG, "GoalTracker stopped")
    }

    // ─── CRUD ───

    override fun createGoal(goal: Goal): Goal {
        val db = database.writableDatabase
        val values = goalToContentValues(goal)
        db.insertWithOnConflict(TABLE_GOALS, null, values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        Log.i(TAG, "Goal created: ${goal.id} — \"${goal.title}\" (${goal.timeframe})")
        refreshActiveGoals()
        return goal
    }

    override fun updateGoal(goal: Goal): Boolean {
        val db = database.writableDatabase
        val values = goalToContentValues(goal)
        val rows = db.update(TABLE_GOALS, values, "id = ?", arrayOf(goal.id))
        if (rows > 0) refreshActiveGoals()
        return rows > 0
    }

    override fun getGoal(goalId: String): Goal? {
        val db = database.readableDatabase
        val cursor = db.query(TABLE_GOALS, null, "id = ?", arrayOf(goalId),
            null, null, null, "1")
        return cursor.use {
            if (it.moveToFirst()) cursorToGoal(it) else null
        }
    }

    override fun getActiveGoals(timeframe: GoalTimeframe?): List<Goal> {
        val db = database.readableDatabase
        val selection = if (timeframe != null) {
            "status = 0 AND timeframe = ${timeframe.ordinal}"
        } else {
            "status = 0"
        }
        val cursor = db.query(TABLE_GOALS, null, selection, null,
            null, null, "deadline ASC, priority DESC", "50")
        val goals = mutableListOf<Goal>()
        cursor.use {
            while (it.moveToNext()) {
                goals.add(cursorToGoal(it))
            }
        }
        return goals
    }

    override fun getChildGoals(parentGoalId: String): List<Goal> {
        val db = database.readableDatabase
        val cursor = db.query(TABLE_GOALS, null, "parent_goal_id = ?",
            arrayOf(parentGoalId), null, null, "timeframe ASC")
        val goals = mutableListOf<Goal>()
        cursor.use {
            while (it.moveToNext()) {
                goals.add(cursorToGoal(it))
            }
        }
        return goals
    }

    override fun completeGoal(goalId: String): Boolean {
        val goal = getGoal(goalId) ?: return false
        val updated = goal.copy(
            status = GoalStatus.COMPLETED,
            completedAt = System.currentTimeMillis(),
            currentValue = goal.targetValue ?: goal.currentValue
        )
        return updateGoal(updated)
    }

    // ─── Progress Tracking ───

    override fun recordProgress(goalId: String, delta: Float, source: String, notes: String?): Boolean {
        val goal = getGoal(goalId) ?: return false
        val newValue = goal.currentValue + delta

        // Record progress entry
        val progress = GoalProgress(
            goalId = goalId,
            previousValue = goal.currentValue,
            newValue = newValue,
            delta = delta,
            source = source,
            notes = notes
        )
        persistProgress(progress)

        // Update goal
        val updatedMilestones = goal.milestones.map { m ->
            if (!m.isReached && newValue >= m.targetValue) {
                m.copy(isReached = true, reachedAt = System.currentTimeMillis())
            } else m
        }

        val isComplete = goal.targetValue != null && newValue >= goal.targetValue
        val updated = goal.copy(
            currentValue = newValue,
            lastProgressAt = System.currentTimeMillis(),
            milestones = updatedMilestones,
            status = if (isComplete) GoalStatus.COMPLETED else goal.status,
            completedAt = if (isComplete) System.currentTimeMillis() else goal.completedAt
        )
        updateGoal(updated)

        // Check for milestone celebrations
        updatedMilestones.filter { it.isReached && it.reachedAt != null }
            .filter { goal.milestones.find { m -> m.id == it.id }?.isReached != true }
            .forEach { milestone ->
                Log.i(TAG, "🎉 Milestone reached: ${milestone.title} for goal ${goal.title}")
                scope.launch {
                    eventBus.publish(XRealEvent.SystemEvent.DebugLog(
                        "🎉 마일스톤 달성: ${milestone.title} (${goal.title})"
                    ))
                }
            }

        // Also update parent goal if exists
        if (goal.parentGoalId != null) {
            propagateToParent(goal.parentGoalId, delta, source)
        }

        Log.d(TAG, "Progress: ${goal.title} += $delta -> $newValue (source: $source)")
        return true
    }

    private fun propagateToParent(parentGoalId: String, delta: Float, source: String) {
        val parent = getGoal(parentGoalId) ?: return
        // For parent goals, we propagate the same delta
        recordProgress(parentGoalId, delta, "child_propagation:$source")
    }

    override fun getGoalSummary(goalId: String): GoalSummary? {
        val goal = getGoal(goalId) ?: return null
        val children = getChildGoals(goalId)
        val progress = getRecentProgress(goalId, 10)
        val progressPercent = if (goal.targetValue != null && goal.targetValue > 0) {
            (goal.currentValue / goal.targetValue * 100f).coerceIn(0f, 100f)
        } else 0f

        // Projection: based on recent rate
        val projectedCompletion = if (goal.targetValue != null && progress.size >= 2) {
            val recentRate = progress.take(5).sumOf { it.delta.toDouble() }.toFloat() /
                    maxOf(1, progress.take(5).size)
            if (recentRate > 0) {
                val remaining = goal.targetValue - goal.currentValue
                val daysNeeded = remaining / recentRate
                System.currentTimeMillis() + (daysNeeded * 86400_000L).toLong()
            } else null
        } else null

        val isOnTrack = when {
            goal.deadline == null -> true
            goal.targetValue == null -> true
            else -> {
                val elapsed = System.currentTimeMillis() - goal.createdAt
                val total = goal.deadline - goal.createdAt
                val expectedProgress = if (total > 0) elapsed.toFloat() / total else 1f
                progressPercent / 100f >= expectedProgress * 0.8f
            }
        }

        return GoalSummary(
            goal = goal,
            progressPercent = progressPercent,
            childGoals = children,
            recentProgress = progress,
            isOnTrack = isOnTrack,
            projectedCompletion = projectedCompletion
        )
    }

    override fun getRecentProgress(goalId: String, limit: Int): List<GoalProgress> {
        val db = database.readableDatabase
        val cursor = db.query(TABLE_GOAL_PROGRESS, null, "goal_id = ?",
            arrayOf(goalId), null, null, "timestamp DESC", "$limit")
        val list = mutableListOf<GoalProgress>()
        cursor.use {
            while (it.moveToNext()) {
                list.add(GoalProgress(
                    id = it.getString(it.getColumnIndexOrThrow("id")),
                    goalId = it.getString(it.getColumnIndexOrThrow("goal_id")),
                    timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp")),
                    previousValue = it.getFloat(it.getColumnIndexOrThrow("previous_value")),
                    newValue = it.getFloat(it.getColumnIndexOrThrow("new_value")),
                    delta = it.getFloat(it.getColumnIndexOrThrow("delta")),
                    source = it.getString(it.getColumnIndexOrThrow("source")),
                    notes = it.getString(it.getColumnIndexOrThrow("notes"))
                ))
            }
        }
        return list
    }

    // ─── Today Summary ───

    override fun getTodaySummary(): String {
        val today = getActiveGoals(GoalTimeframe.DAY)
        val week = getActiveGoals(GoalTimeframe.WEEK)
        val sb = StringBuilder()
        sb.appendLine("📊 오늘의 목표:")
        if (today.isEmpty()) {
            sb.appendLine("  설정된 일간 목표 없음")
        } else {
            today.forEach { g ->
                val pct = if (g.targetValue != null && g.targetValue > 0)
                    (g.currentValue / g.targetValue * 100).toInt() else 0
                val bar = "█".repeat(pct / 10) + "░".repeat(10 - pct / 10)
                sb.appendLine("  ${g.title}: $bar $pct% (${g.currentValue}/${g.targetValue ?: "?"} ${g.unit ?: ""})")
                if (g.streakDays > 1) sb.appendLine("    🔥 ${g.streakDays}일 연속!")
            }
        }
        if (week.isNotEmpty()) {
            sb.appendLine("\n📅 주간 목표:")
            week.take(3).forEach { g ->
                val pct = if (g.targetValue != null && g.targetValue > 0)
                    (g.currentValue / g.targetValue * 100).toInt() else 0
                sb.appendLine("  ${g.title}: $pct% (${g.currentValue}/${g.targetValue ?: "?"} ${g.unit ?: ""})")
            }
        }
        return sb.toString()
    }

    // ─── Event Processing ───

    private fun processEvent(event: XRealEvent) {
        when (event) {
            // Running session completed -> update fitness goals
            is XRealEvent.SystemEvent.MissionStateChanged -> {
                if (event.newState == "COMPLETED" && event.missionId.contains("running", ignoreCase = true)) {
                    updateGoalsByCategory(GoalCategory.FITNESS, 1f, "running_session")
                }
            }

            // Todo completed -> propagate to linked goal
            is XRealEvent.SystemEvent.TodoCompleted -> {
                val goalId = event.parentGoalId
                if (goalId != null) {
                    Log.i(TAG, "Todo '${event.todoTitle}' completed → propagating to goal $goalId")
                    recordProgress(goalId, 1f, "todo_completed:${event.todoId}")
                }
                // Also update goals matching the todo's category
                val categoryStr = event.category
                if (categoryStr != null) {
                    try {
                        val cat = GoalCategory.valueOf(categoryStr.uppercase())
                        updateGoalsByCategory(cat, 1f, "todo_completed:${event.todoId}")
                    } catch (_: Exception) { /* unknown category, skip */ }
                }
            }

            else -> { /* ignore */ }
        }
    }

    private fun updateGoalsByCategory(category: GoalCategory, delta: Float, source: String) {
        val goals = getActiveGoals().filter { it.category == category }
        goals.forEach { goal ->
            recordProgress(goal.id, delta, source)
        }
    }

    // ─── Streak Tracking ───

    private fun updateStreaks() {
        val goals = getActiveGoals()
        val today = Calendar.getInstance()
        today.set(Calendar.HOUR_OF_DAY, 0)
        today.set(Calendar.MINUTE, 0)
        today.set(Calendar.SECOND, 0)
        today.set(Calendar.MILLISECOND, 0)
        val todayStart = today.timeInMillis

        goals.filter { it.timeframe == GoalTimeframe.DAY }.forEach { goal ->
            val lastProgress = goal.lastProgressAt
            if (lastProgress != null && lastProgress >= todayStart) {
                // Has progress today, increment streak if not already done
                // (Streak is updated daily)
            } else if (lastProgress != null && lastProgress < todayStart - 86400_000L) {
                // Missed yesterday -> reset streak
                if (goal.streakDays > 0) {
                    updateGoal(goal.copy(streakDays = 0))
                    Log.d(TAG, "Streak reset for: ${goal.title}")
                }
            }
        }
    }

    // ─── Helper ───

    private fun refreshActiveGoals() {
        try {
            _activeGoals.value = getActiveGoals()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh goals: ${e.message}")
        }
    }

    private fun goalToContentValues(goal: Goal): ContentValues {
        return ContentValues().apply {
            put("id", goal.id)
            put("title", goal.title)
            put("description", goal.description)
            put("timeframe", goal.timeframe.ordinal)
            put("parent_goal_id", goal.parentGoalId)
            put("target_value", goal.targetValue?.toDouble())
            put("current_value", goal.currentValue.toDouble())
            put("unit", goal.unit)
            put("status", goal.status.ordinal)
            put("assigned_domain", goal.assignedDomain)
            put("category", goal.category.ordinal)
            put("created_at", goal.createdAt)
            put("deadline", goal.deadline)
            put("completed_at", goal.completedAt)
            put("streak_days", goal.streakDays)
            put("last_progress_at", goal.lastProgressAt)
            put("milestones", if (goal.milestones.isNotEmpty()) {
                JSONArray().apply {
                    goal.milestones.forEach { m ->
                        put(JSONObject().apply {
                            put("id", m.id)
                            put("title", m.title)
                            put("targetValue", m.targetValue.toDouble())
                            m.reachedAt?.let { put("reachedAt", it) }
                            put("isReached", m.isReached)
                        })
                    }
                }.toString()
            } else null)
        }
    }

    private fun cursorToGoal(cursor: android.database.Cursor): Goal {
        val milestonesJson = cursor.getString(cursor.getColumnIndexOrThrow("milestones"))
        val milestones = if (milestonesJson != null) {
            try {
                val arr = JSONArray(milestonesJson)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    Milestone(
                        id = obj.optString("id", ""),
                        title = obj.getString("title"),
                        targetValue = obj.getDouble("targetValue").toFloat(),
                        reachedAt = if (obj.has("reachedAt")) obj.getLong("reachedAt") else null,
                        isReached = obj.optBoolean("isReached", false)
                    )
                }
            } catch (_: Exception) { emptyList() }
        } else emptyList()

        return Goal(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
            description = cursor.getString(cursor.getColumnIndexOrThrow("description")),
            timeframe = GoalTimeframe.entries.getOrElse(
                cursor.getInt(cursor.getColumnIndexOrThrow("timeframe"))
            ) { GoalTimeframe.DAY },
            parentGoalId = cursor.getString(cursor.getColumnIndexOrThrow("parent_goal_id")),
            targetValue = if (cursor.isNull(cursor.getColumnIndexOrThrow("target_value"))) null
                else cursor.getFloat(cursor.getColumnIndexOrThrow("target_value")),
            currentValue = cursor.getFloat(cursor.getColumnIndexOrThrow("current_value")),
            unit = cursor.getString(cursor.getColumnIndexOrThrow("unit")),
            status = GoalStatus.entries.getOrElse(
                cursor.getInt(cursor.getColumnIndexOrThrow("status"))
            ) { GoalStatus.ACTIVE },
            assignedDomain = cursor.getString(cursor.getColumnIndexOrThrow("assigned_domain")),
            category = GoalCategory.entries.getOrElse(
                cursor.getInt(cursor.getColumnIndexOrThrow("category"))
            ) { GoalCategory.GENERAL },
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            deadline = if (cursor.isNull(cursor.getColumnIndexOrThrow("deadline"))) null
                else cursor.getLong(cursor.getColumnIndexOrThrow("deadline")),
            completedAt = if (cursor.isNull(cursor.getColumnIndexOrThrow("completed_at"))) null
                else cursor.getLong(cursor.getColumnIndexOrThrow("completed_at")),
            streakDays = cursor.getInt(cursor.getColumnIndexOrThrow("streak_days")),
            lastProgressAt = if (cursor.isNull(cursor.getColumnIndexOrThrow("last_progress_at"))) null
                else cursor.getLong(cursor.getColumnIndexOrThrow("last_progress_at")),
            milestones = milestones
        )
    }

    private fun persistProgress(progress: GoalProgress) {
        try {
            val db = database.writableDatabase
            val values = ContentValues().apply {
                put("id", progress.id)
                put("goal_id", progress.goalId)
                put("timestamp", progress.timestamp)
                put("previous_value", progress.previousValue.toDouble())
                put("new_value", progress.newValue.toDouble())
                put("delta", progress.delta.toDouble())
                put("source", progress.source)
                put("notes", progress.notes)
            }
            db.insertWithOnConflict(TABLE_GOAL_PROGRESS, null, values,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist progress: ${e.message}")
        }
    }
}
