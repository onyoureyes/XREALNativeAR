package com.xreal.nativear.plan

import android.util.Log
import com.xreal.nativear.DrawCommand
import com.xreal.nativear.DrawElement
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.hud.HUDWidget
import com.xreal.nativear.hud.IHUDWidgetRenderer
import kotlinx.coroutines.*

/**
 * PlanHUD: Renders Todo/Schedule information on the AR HUD.
 *
 * Uses the existing DrawElement system to display:
 * - Top-left: current schedule block (if any)
 * - Left panel: top 3 pending todos
 * - Bottom: daily progress bar
 *
 * Updates every 60 seconds or on plan changes.
 */
class PlanHUD(
    private val planManager: PlanManager,
    private val eventBus: GlobalEventBus,
    private val scope: CoroutineScope
) : IHUDWidgetRenderer {

    override val supportedWidgets = setOf(
        HUDWidget.TODO_CARD,
        HUDWidget.SCHEDULE_COUNTDOWN,
        HUDWidget.DAILY_PROGRESS_BAR,
        HUDWidget.GOAL_PROGRESS
    )

    override fun onWidgetActivated(widget: HUDWidget) {
        Log.d(TAG, "Widget activated: $widget")
        setVisible(true)
    }

    override fun onWidgetDeactivated(widget: HUDWidget) {
        Log.d(TAG, "Widget deactivated: $widget")
        // Only hide if NONE of our supported widgets remain active
        setVisible(false)
    }
    companion object {
        private const val TAG = "PlanHUD"
        private const val UPDATE_INTERVAL_MS = 60_000L // 1 minute
        private const val ID_PREFIX = "plan_hud_"
    }

    private var updateJob: Job? = null
    private var isVisible = false

    fun start() {
        Log.i(TAG, "PlanHUD started")
        isVisible = true
        updateJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    renderHUD()
                } catch (e: Exception) {
                    Log.e(TAG, "HUD render error: ${e.message}")
                }
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        updateJob?.cancel()
        clearHUD()
        isVisible = false
        Log.i(TAG, "PlanHUD stopped")
    }

    fun setVisible(visible: Boolean) {
        isVisible = visible
        if (!visible) {
            clearHUD()
        } else {
            scope.launch(Dispatchers.Default) { renderHUD() }
        }
    }

    /**
     * Force refresh the HUD (called when todos/schedule change).
     */
    fun refresh() {
        if (isVisible) {
            scope.launch(Dispatchers.Default) { renderHUD() }
        }
    }

    private fun renderHUD() {
        if (!isVisible) return

        // Clear previous plan HUD elements
        clearHUD()

        // 1. Current/Next schedule block (top-left area)
        renderScheduleInfo()

        // 2. Top pending todos (left panel)
        renderTodoList()

        // 3. Daily progress bar (bottom)
        renderDailyProgress()
    }

    private fun renderScheduleInfo() {
        val current = planManager.getCurrentScheduleBlock()
        val next = planManager.getNextScheduleBlock()

        if (current != null) {
            val df = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            val endTime = df.format(java.util.Date(current.endTime))
            val remaining = ((current.endTime - System.currentTimeMillis()) / 60_000).toInt()

            publishDraw(DrawCommand.Add(DrawElement.Text(
                id = "${ID_PREFIX}schedule_current",
                x = 2f, y = 5f,
                text = "${current.type.displayName}: ${current.title}",
                size = 16f, color = "#00FFFF", opacity = 0.9f, bold = true
            )))
            publishDraw(DrawCommand.Add(DrawElement.Text(
                id = "${ID_PREFIX}schedule_time",
                x = 2f, y = 9f,
                text = "~$endTime (${remaining}분 남음)",
                size = 13f, color = "#88CCFF", opacity = 0.7f
            )))
        } else if (next != null) {
            val df = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            val startTime = df.format(java.util.Date(next.startTime))
            val minutesUntil = ((next.startTime - System.currentTimeMillis()) / 60_000).toInt()

            if (minutesUntil in 0..60) {
                publishDraw(DrawCommand.Add(DrawElement.Text(
                    id = "${ID_PREFIX}schedule_next",
                    x = 2f, y = 5f,
                    text = "다음: ${next.title} ($startTime, ${minutesUntil}분 후)",
                    size = 14f, color = "#AAAAFF", opacity = 0.7f
                )))
            }
        }
    }

    private fun renderTodoList() {
        val todos = planManager.getPendingTodos().take(3)
        if (todos.isEmpty()) return

        val startY = 20f
        val lineHeight = 5f

        publishDraw(DrawCommand.Add(DrawElement.Text(
            id = "${ID_PREFIX}todo_header",
            x = 2f, y = startY,
            text = "할일",
            size = 14f, color = "#FFCC00", opacity = 0.8f, bold = true
        )))

        todos.forEachIndexed { index, todo ->
            val priorityIcon = when (todo.priority) {
                TodoPriority.URGENT -> "!!"
                TodoPriority.HIGH -> "!"
                TodoPriority.NORMAL -> "-"
                TodoPriority.LOW -> " "
            }
            val color = when (todo.priority) {
                TodoPriority.URGENT -> "#FF4444"
                TodoPriority.HIGH -> "#FFAA00"
                TodoPriority.NORMAL -> "#CCCCCC"
                TodoPriority.LOW -> "#888888"
            }

            publishDraw(DrawCommand.Add(DrawElement.Text(
                id = "${ID_PREFIX}todo_$index",
                x = 2f, y = startY + (index + 1) * lineHeight,
                text = "$priorityIcon ${todo.title}",
                size = 13f, color = color, opacity = 0.7f
            )))
        }
    }

    private fun renderDailyProgress() {
        val allTodos = planManager.listTodos(limit = 100)
        if (allTodos.isEmpty()) return

        val completed = allTodos.count { it.status == TodoStatus.COMPLETED }
        val total = allTodos.size
        val progress = if (total > 0) completed.toFloat() / total else 0f

        // Background bar
        publishDraw(DrawCommand.Add(DrawElement.Rect(
            id = "${ID_PREFIX}progress_bg",
            x = 20f, y = 95f,
            width = 60f, height = 2f,
            filled = true, color = "#333333", opacity = 0.4f
        )))

        // Progress fill
        if (progress > 0f) {
            publishDraw(DrawCommand.Add(DrawElement.Rect(
                id = "${ID_PREFIX}progress_fill",
                x = 20f, y = 95f,
                width = 60f * progress, height = 2f,
                filled = true, color = "#00FF88", opacity = 0.6f
            )))
        }

        // Label
        publishDraw(DrawCommand.Add(DrawElement.Text(
            id = "${ID_PREFIX}progress_label",
            x = 82f, y = 95f,
            text = "$completed/$total",
            size = 12f, color = "#AAAAAA", opacity = 0.5f
        )))
    }

    private fun clearHUD() {
        // Remove all plan HUD elements
        val elemIds = listOf(
            "schedule_current", "schedule_time", "schedule_next",
            "todo_header", "todo_0", "todo_1", "todo_2",
            "progress_bg", "progress_fill", "progress_label"
        )
        for (id in elemIds) {
            publishDraw(DrawCommand.Remove("$ID_PREFIX$id"))
        }
    }

    private fun publishDraw(command: DrawCommand) {
        eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(command))
    }
}
