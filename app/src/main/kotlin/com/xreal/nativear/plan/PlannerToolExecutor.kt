package com.xreal.nativear.plan

import android.util.Log
import com.xreal.nativear.tools.IToolExecutor
import com.xreal.nativear.tools.ToolResult
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * PlannerToolExecutor: AI-callable tools for Todo/Schedule management.
 *
 * Provides 6 tools:
 * - create_todo: Create a new todo item
 * - complete_todo: Mark a todo as completed
 * - list_todos: List todos with optional filters
 * - create_schedule: Create a new schedule block
 * - get_schedule: Get today's or a specific day's schedule
 * - get_daily_summary: Get combined daily summary (todos + schedule)
 */
class PlannerToolExecutor(
    private val planManager: PlanManager
) : IToolExecutor {

    companion object {
        private const val TAG = "PlannerToolExecutor"
    }

    override val supportedTools = setOf(
        "create_todo", "complete_todo", "list_todos",
        "create_schedule", "get_schedule", "get_daily_summary"
    )

    override suspend fun execute(name: String, args: Map<String, Any?>): ToolResult {
        return when (name) {
            "create_todo" -> createTodo(args)
            "complete_todo" -> completeTodo(args)
            "list_todos" -> listTodos(args)
            "create_schedule" -> createSchedule(args)
            "get_schedule" -> getSchedule(args)
            "get_daily_summary" -> getDailySummary(args)
            else -> ToolResult(false, "Unknown tool: $name")
        }
    }

    private fun createTodo(args: Map<String, Any?>): ToolResult {
        val title = args["title"] as? String
            ?: return ToolResult(false, "title is required")
        val description = args["description"] as? String
        val priorityStr = (args["priority"] as? String)?.uppercase()
        val priority = priorityStr?.let {
            try { TodoPriority.valueOf(it) } catch (_: Exception) { null }
        } ?: TodoPriority.NORMAL
        val deadlineStr = args["deadline"] as? String
        val deadline = deadlineStr?.let { parseDateTime(it) }
        val category = args["category"] as? String
        val assignedExpert = args["assigned_expert"] as? String

        val todo = TodoItem(
            title = title,
            description = description,
            priority = priority,
            deadline = deadline,
            category = category,
            assignedExpert = assignedExpert
        )

        val created = planManager.createTodo(todo)
        return ToolResult(true, buildString {
            append("할일 생성 완료: \"${created.title}\"")
            append(" (ID: ${created.id})")
            append(" [${created.priority.displayName}]")
            deadline?.let {
                val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                append(" 마감: ${df.format(java.util.Date(it))}")
            }
        })
    }

    private fun completeTodo(args: Map<String, Any?>): ToolResult {
        val todoId = args["todo_id"] as? String
            ?: return ToolResult(false, "todo_id is required")

        return if (planManager.completeTodo(todoId)) {
            ToolResult(true, "할일 완료 처리됨: $todoId")
        } else {
            ToolResult(false, "할일을 찾을 수 없음: $todoId")
        }
    }

    private fun listTodos(args: Map<String, Any?>): ToolResult {
        val statusStr = (args["status"] as? String)?.uppercase()
        val status = statusStr?.let {
            try { TodoStatus.valueOf(it) } catch (_: Exception) { null }
        }
        val category = args["category"] as? String
        val limit = (args["limit"] as? Number)?.toInt() ?: 20

        val todos = planManager.listTodos(status = status, category = category, limit = limit)

        if (todos.isEmpty()) {
            return ToolResult(true, "할일이 없습니다.")
        }

        val json = JSONArray()
        for (todo in todos) {
            json.put(JSONObject().apply {
                put("id", todo.id)
                put("title", todo.title)
                put("priority", todo.priority.displayName)
                put("status", todo.status.displayName)
                todo.description?.let { put("description", it) }
                todo.deadline?.let {
                    val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    put("deadline", df.format(java.util.Date(it)))
                }
                todo.category?.let { put("category", it) }
            })
        }

        return ToolResult(true, json.toString(2))
    }

    private fun createSchedule(args: Map<String, Any?>): ToolResult {
        val title = args["title"] as? String
            ?: return ToolResult(false, "title is required")
        val startTimeStr = args["start_time"] as? String
            ?: return ToolResult(false, "start_time is required (format: yyyy-MM-dd HH:mm)")
        val endTimeStr = args["end_time"] as? String
            ?: return ToolResult(false, "end_time is required (format: yyyy-MM-dd HH:mm)")

        val startTime = parseDateTime(startTimeStr)
            ?: return ToolResult(false, "Invalid start_time format")
        val endTime = parseDateTime(endTimeStr)
            ?: return ToolResult(false, "Invalid end_time format")

        val typeStr = (args["type"] as? String)?.uppercase()
        val type = typeStr?.let {
            try { ScheduleType.valueOf(it) } catch (_: Exception) { null }
        } ?: ScheduleType.TASK

        val notes = args["notes"] as? String

        val block = ScheduleBlock(
            title = title,
            startTime = startTime,
            endTime = endTime,
            type = type,
            notes = notes
        )

        val created = planManager.createScheduleBlock(block)
        val df = SimpleDateFormat("HH:mm", Locale.getDefault())
        return ToolResult(true, buildString {
            append("일정 생성: \"${created.title}\"")
            append(" ${df.format(java.util.Date(created.startTime))}")
            append("-${df.format(java.util.Date(created.endTime))}")
            append(" [${type.displayName}]")
        })
    }

    private fun getSchedule(args: Map<String, Any?>): ToolResult {
        val dateStr = args["date"] as? String
        val timestamp = if (dateStr != null) {
            parseDate(dateStr) ?: System.currentTimeMillis()
        } else {
            System.currentTimeMillis()
        }

        val blocks = planManager.getScheduleForDay(timestamp)

        if (blocks.isEmpty()) {
            return ToolResult(true, "해당일 일정이 없습니다.")
        }

        val json = JSONArray()
        val df = SimpleDateFormat("HH:mm", Locale.getDefault())
        for (block in blocks) {
            json.put(JSONObject().apply {
                put("id", block.id)
                put("title", block.title)
                put("start_time", df.format(java.util.Date(block.startTime)))
                put("end_time", df.format(java.util.Date(block.endTime)))
                put("type", block.type.displayName)
                block.notes?.let { put("notes", it) }
            })
        }

        return ToolResult(true, json.toString(2))
    }

    private fun getDailySummary(args: Map<String, Any?>): ToolResult {
        return ToolResult(true, planManager.buildDailySummary())
    }

    // ─── Time parsing helpers ───

    private fun parseDateTime(str: String): Long? {
        val formats = listOf(
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "MM-dd HH:mm",
            "HH:mm"
        )
        for (format in formats) {
            try {
                val sdf = SimpleDateFormat(format, Locale.getDefault())
                val date = sdf.parse(str) ?: continue
                // If only time provided, use today's date
                if (format == "HH:mm" || format == "MM-dd HH:mm") {
                    val cal = java.util.Calendar.getInstance()
                    val parsed = java.util.Calendar.getInstance().apply { time = date }
                    if (format == "HH:mm") {
                        cal.set(java.util.Calendar.HOUR_OF_DAY, parsed.get(java.util.Calendar.HOUR_OF_DAY))
                        cal.set(java.util.Calendar.MINUTE, parsed.get(java.util.Calendar.MINUTE))
                    } else {
                        cal.set(java.util.Calendar.MONTH, parsed.get(java.util.Calendar.MONTH))
                        cal.set(java.util.Calendar.DAY_OF_MONTH, parsed.get(java.util.Calendar.DAY_OF_MONTH))
                        cal.set(java.util.Calendar.HOUR_OF_DAY, parsed.get(java.util.Calendar.HOUR_OF_DAY))
                        cal.set(java.util.Calendar.MINUTE, parsed.get(java.util.Calendar.MINUTE))
                    }
                    cal.set(java.util.Calendar.SECOND, 0)
                    cal.set(java.util.Calendar.MILLISECOND, 0)
                    return cal.timeInMillis
                }
                return date.time
            } catch (_: Exception) { }
        }
        return null
    }

    private fun parseDate(str: String): Long? {
        return try {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(str)?.time
        } catch (_: Exception) { null }
    }
}
