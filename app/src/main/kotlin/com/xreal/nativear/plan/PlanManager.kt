package com.xreal.nativear.plan

import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.core.GlobalEventBus
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * PlanManager: CRUD operations for Todos and Schedules.
 *
 * Stores data in UnifiedMemoryDatabase (user_todos, schedule_blocks tables).
 * Provides query methods for AI agents and HUD rendering.
 */
class PlanManager(
    private val database: UnifiedMemoryDatabase,
    private val eventBus: GlobalEventBus
) : IPlanService {
    companion object {
        private const val TAG = "PlanManager"
    }

    // C4 FIX: Dedicated scope replaces GlobalScope usage
    private val eventScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    // ─── Todo CRUD ───

    override fun createTodo(todo: TodoItem): TodoItem {
        try {
            val db = database.writableDatabase
            val values = android.content.ContentValues().apply {
                put("id", todo.id)
                put("title", todo.title)
                put("description", todo.description)
                put("priority", todo.priority.level)
                put("deadline", todo.deadline)
                put("status", todo.status.ordinal)
                put("category", todo.category)
                put("assigned_expert", todo.assignedExpert)
                put("created_by", todo.createdBy)
                put("parent_goal_id", todo.parentGoalId)
                put("context_tags", todo.contextTags.joinToString(","))
                put("recurrence", todo.recurrence?.let { serializeRecurrence(it) })
                put("completed_at", todo.completedAt)
                put("created_at", todo.createdAt)
            }
            db.insertWithOnConflict("user_todos", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            Log.d(TAG, "Created todo: ${todo.title} (${todo.id})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create todo: ${e.message}")
        }
        return todo
    }

    override fun completeTodo(todoId: String): Boolean {
        return try {
            val db = database.writableDatabase
            val values = android.content.ContentValues().apply {
                put("status", TodoStatus.COMPLETED.ordinal)
                put("completed_at", System.currentTimeMillis())
            }
            val rows = db.update("user_todos", values, "id = ?", arrayOf(todoId))
            if (rows > 0) {
                Log.d(TAG, "Completed todo: $todoId")

                // Publish TodoCompleted event for GoalTracker propagation
                // C4 FIX: Replace GlobalScope with dedicated scope
                val todo = getTodo(todoId)
                if (todo != null) {
                    eventScope.launch {
                        eventBus.publish(
                            com.xreal.nativear.core.XRealEvent.SystemEvent.TodoCompleted(
                                todoId = todoId,
                                todoTitle = todo.title,
                                parentGoalId = todo.parentGoalId,
                                category = todo.category
                            )
                        )
                    }
                }

                true
            } else {
                Log.w(TAG, "Todo not found: $todoId")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to complete todo: ${e.message}")
            false
        }
    }

    override fun updateTodoStatus(todoId: String, status: TodoStatus): Boolean {
        return try {
            val db = database.writableDatabase
            val values = android.content.ContentValues().apply {
                put("status", status.ordinal)
                if (status == TodoStatus.COMPLETED) {
                    put("completed_at", System.currentTimeMillis())
                }
            }
            db.update("user_todos", values, "id = ?", arrayOf(todoId)) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update todo status: ${e.message}")
            false
        }
    }

    override fun deleteTodo(todoId: String): Boolean {
        return try {
            database.writableDatabase.delete("user_todos", "id = ?", arrayOf(todoId)) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete todo: ${e.message}")
            false
        }
    }

    override fun getTodo(todoId: String): TodoItem? {
        return try {
            val cursor = database.readableDatabase.query(
                "user_todos", null, "id = ?", arrayOf(todoId),
                null, null, null
            )
            cursor.use {
                if (it.moveToFirst()) cursorToTodo(it) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get todo: ${e.message}")
            null
        }
    }

    override fun listTodos(
        status: TodoStatus?,
        category: String?,
        limit: Int
    ): List<TodoItem> {
        return try {
            val where = mutableListOf<String>()
            val whereArgs = mutableListOf<String>()

            status?.let {
                where.add("status = ?")
                whereArgs.add(it.ordinal.toString())
            }
            category?.let {
                where.add("category = ?")
                whereArgs.add(it)
            }

            val cursor = database.readableDatabase.query(
                "user_todos", null,
                if (where.isEmpty()) null else where.joinToString(" AND "),
                if (whereArgs.isEmpty()) null else whereArgs.toTypedArray(),
                null, null, "priority ASC, deadline ASC NULLS LAST",
                limit.toString()
            )
            cursor.use {
                val todos = mutableListOf<TodoItem>()
                while (it.moveToNext()) {
                    todos.add(cursorToTodo(it))
                }
                todos
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list todos: ${e.message}")
            emptyList()
        }
    }

    override fun getPendingTodos(): List<TodoItem> = listTodos(status = TodoStatus.PENDING)

    override fun getTodayTodos(): List<TodoItem> {
        val now = System.currentTimeMillis()
        val startOfDay = now - (now % 86_400_000L)
        val endOfDay = startOfDay + 86_400_000L

        return try {
            val cursor = database.readableDatabase.rawQuery(
                """SELECT * FROM user_todos
                   WHERE (status < 2) AND (deadline IS NULL OR deadline <= ?)
                   ORDER BY priority ASC, deadline ASC""",
                arrayOf(endOfDay.toString())
            )
            cursor.use {
                val todos = mutableListOf<TodoItem>()
                while (it.moveToNext()) {
                    todos.add(cursorToTodo(it))
                }
                todos
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get today todos: ${e.message}")
            emptyList()
        }
    }

    override fun getUpcomingTodoTitles(limit: Int): List<String> {
        return getPendingTodos().take(limit).map { it.title }
    }

    // ─── Schedule CRUD ───

    override fun createScheduleBlock(block: ScheduleBlock): ScheduleBlock {
        try {
            val db = database.writableDatabase
            val values = android.content.ContentValues().apply {
                put("id", block.id)
                put("title", block.title)
                put("start_time", block.startTime)
                put("end_time", block.endTime)
                put("type", block.type.ordinal)
                put("linked_todo_ids", block.linkedTodoIds.joinToString(","))
                put("reminder", block.reminder)
                put("notes", block.notes)
                put("created_at", block.createdAt)
            }
            db.insertWithOnConflict("schedule_blocks", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            Log.d(TAG, "Created schedule: ${block.title}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create schedule: ${e.message}")
        }
        return block
    }

    override fun getScheduleForDay(timestamp: Long): List<ScheduleBlock> {
        val startOfDay = timestamp - (timestamp % 86_400_000L)
        val endOfDay = startOfDay + 86_400_000L

        return try {
            val cursor = database.readableDatabase.rawQuery(
                """SELECT * FROM schedule_blocks
                   WHERE start_time >= ? AND start_time < ?
                   ORDER BY start_time ASC""",
                arrayOf(startOfDay.toString(), endOfDay.toString())
            )
            cursor.use {
                val blocks = mutableListOf<ScheduleBlock>()
                while (it.moveToNext()) {
                    blocks.add(cursorToScheduleBlock(it))
                }
                blocks
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get schedule: ${e.message}")
            emptyList()
        }
    }

    override fun getTodaySchedule(): List<ScheduleBlock> = getScheduleForDay(System.currentTimeMillis())

    override fun getCurrentScheduleBlock(): ScheduleBlock? {
        val now = System.currentTimeMillis()
        return try {
            val cursor = database.readableDatabase.rawQuery(
                """SELECT * FROM schedule_blocks
                   WHERE start_time <= ? AND end_time > ?
                   ORDER BY start_time DESC LIMIT 1""",
                arrayOf(now.toString(), now.toString())
            )
            cursor.use {
                if (it.moveToFirst()) cursorToScheduleBlock(it) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current schedule: ${e.message}")
            null
        }
    }

    override fun getNextScheduleBlock(): ScheduleBlock? {
        val now = System.currentTimeMillis()
        return try {
            val cursor = database.readableDatabase.rawQuery(
                """SELECT * FROM schedule_blocks
                   WHERE start_time > ?
                   ORDER BY start_time ASC LIMIT 1""",
                arrayOf(now.toString())
            )
            cursor.use {
                if (it.moveToFirst()) cursorToScheduleBlock(it) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get next schedule: ${e.message}")
            null
        }
    }

    override fun deleteScheduleBlock(blockId: String): Boolean {
        return try {
            database.writableDatabase.delete("schedule_blocks", "id = ?", arrayOf(blockId)) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete schedule: ${e.message}")
            false
        }
    }

    // ─── Summary methods (for AI context injection) ───

    override fun buildDailySummary(): String {
        val todos = getTodayTodos()
        val schedule = getTodaySchedule()
        val parts = mutableListOf<String>()

        if (todos.isNotEmpty()) {
            val pending = todos.count { it.status == TodoStatus.PENDING }
            val inProgress = todos.count { it.status == TodoStatus.IN_PROGRESS }
            parts.add("할일: ${pending}개 대기, ${inProgress}개 진행중")
            todos.take(3).forEach { parts.add("  - ${it.title} (${it.priority.displayName})") }
        }

        if (schedule.isNotEmpty()) {
            parts.add("일정: ${schedule.size}개")
            schedule.take(3).forEach { block ->
                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(block.startTime))
                parts.add("  - $time ${block.title}")
            }
        }

        return if (parts.isEmpty()) "오늘 예정된 할일/일정 없음"
        else parts.joinToString("\n")
    }

    // ─── Helpers ───

    private fun cursorToTodo(cursor: android.database.Cursor): TodoItem {
        return TodoItem(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
            description = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("description")),
            priority = TodoPriority.values().getOrElse(
                cursor.getInt(cursor.getColumnIndexOrThrow("priority"))
            ) { TodoPriority.NORMAL },
            deadline = cursor.getLongOrNull(cursor.getColumnIndexOrThrow("deadline")),
            status = TodoStatus.values().getOrElse(
                cursor.getInt(cursor.getColumnIndexOrThrow("status"))
            ) { TodoStatus.PENDING },
            category = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("category")),
            assignedExpert = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("assigned_expert")),
            createdBy = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("created_by")),
            parentGoalId = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("parent_goal_id")),
            contextTags = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("context_tags"))
                ?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
            recurrence = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("recurrence"))
                ?.let { deserializeRecurrence(it) },
            completedAt = cursor.getLongOrNull(cursor.getColumnIndexOrThrow("completed_at")),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
        )
    }

    private fun cursorToScheduleBlock(cursor: android.database.Cursor): ScheduleBlock {
        return ScheduleBlock(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
            startTime = cursor.getLong(cursor.getColumnIndexOrThrow("start_time")),
            endTime = cursor.getLong(cursor.getColumnIndexOrThrow("end_time")),
            type = ScheduleType.values().getOrElse(
                cursor.getInt(cursor.getColumnIndexOrThrow("type"))
            ) { ScheduleType.TASK },
            linkedTodoIds = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("linked_todo_ids"))
                ?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
            reminder = cursor.getLongOrNull(cursor.getColumnIndexOrThrow("reminder")),
            notes = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("notes")),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
        )
    }

    private fun serializeRecurrence(rule: RecurrenceRule): String {
        return JSONObject().apply {
            put("pattern", rule.pattern.name)
            put("interval", rule.interval)
            rule.endDate?.let { put("endDate", it) }
        }.toString()
    }

    private fun deserializeRecurrence(json: String): RecurrenceRule? {
        return try {
            val obj = JSONObject(json)
            RecurrenceRule(
                pattern = RecurrencePattern.valueOf(obj.getString("pattern")),
                interval = obj.optInt("interval", 1),
                endDate = if (obj.has("endDate")) obj.getLong("endDate") else null
            )
        } catch (e: Exception) { null }
    }

    // Cursor extension helpers
    private fun android.database.Cursor.getStringOrNull(columnIndex: Int): String? {
        return if (isNull(columnIndex)) null else getString(columnIndex)
    }

    private fun android.database.Cursor.getLongOrNull(columnIndex: Int): Long? {
        return if (isNull(columnIndex)) null else getLong(columnIndex)
    }
}
