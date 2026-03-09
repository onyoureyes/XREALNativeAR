package com.xreal.nativear.hud

import android.util.Log
import com.xreal.nativear.tools.IToolExecutor
import com.xreal.nativear.tools.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * HUDToolExecutor: AI-callable tools for HUD mode management.
 *
 * Provides 4 tools:
 * - switch_hud_mode: Switch to a specific HUD mode
 * - compose_hud_template: Create a custom HUD template with selected widgets
 * - add_hud_widget: Add a widget to the current template
 * - remove_hud_widget: Remove a widget from the current template
 */
class HUDToolExecutor(
    private val templateEngine: HUDTemplateEngine,
    private val modeManager: HUDModeManager
) : IToolExecutor {

    companion object {
        private const val TAG = "HUDToolExecutor"
    }

    override val supportedTools = setOf(
        "switch_hud_mode", "compose_hud_template",
        "add_hud_widget", "remove_hud_widget"
    )

    override suspend fun execute(name: String, args: Map<String, Any?>): ToolResult {
        return when (name) {
            "switch_hud_mode" -> switchHudMode(args)
            "compose_hud_template" -> composeTemplate(args)
            "add_hud_widget" -> addWidget(args)
            "remove_hud_widget" -> removeWidget(args)
            else -> ToolResult(false, "Unknown tool: $name")
        }
    }

    private fun switchHudMode(args: Map<String, Any?>): ToolResult {
        val modeStr = (args["mode"] as? String)?.uppercase()
            ?: return ToolResult(false, "mode is required")

        val mode = try {
            HUDMode.valueOf(modeStr)
        } catch (_: Exception) {
            return ToolResult(false, "Invalid mode: $modeStr. Available: ${HUDMode.values().joinToString(", ") { it.name }}")
        }

        modeManager.switchMode(mode)
        return ToolResult(true, "HUD 모드 변경: ${mode.displayName}")
    }

    private fun composeTemplate(args: Map<String, Any?>): ToolResult {
        val name = args["name"] as? String
            ?: return ToolResult(false, "name is required")
        val widgetNames = args["widgets"] as? List<*>
            ?: return ToolResult(false, "widgets array is required")

        val widgets = widgetNames.mapNotNull { widgetName ->
            try {
                HUDWidget.valueOf((widgetName as String).uppercase())
            } catch (_: Exception) {
                Log.w(TAG, "Unknown widget: $widgetName")
                null
            }
        }

        if (widgets.isEmpty()) {
            return ToolResult(false, "No valid widgets provided")
        }

        val situationStr = (args["situation"] as? String)?.uppercase()
        val situation = situationStr?.let {
            try { com.xreal.nativear.context.LifeSituation.valueOf(it) }
            catch (_: Exception) { null }
        } ?: com.xreal.nativear.context.LifeSituation.UNKNOWN

        val save = args["save"] as? Boolean ?: true

        val template = templateEngine.composeTemplate(name, situation, widgets)

        if (save) {
            templateEngine.saveTemplate(template)
        }

        // Activate the new template
        templateEngine.activateTemplate(template)

        return ToolResult(true, buildString {
            append("커스텀 HUD 생성: \"$name\"")
            append("\n위젯: ${widgets.joinToString(", ") { it.name }}")
            append("\nID: ${template.id}")
        })
    }

    private fun addWidget(args: Map<String, Any?>): ToolResult {
        val widgetStr = (args["widget"] as? String)?.uppercase()
            ?: return ToolResult(false, "widget name is required")

        val widget = try {
            HUDWidget.valueOf(widgetStr)
        } catch (_: Exception) {
            return ToolResult(false, "Invalid widget: $widgetStr")
        }

        val current = templateEngine.activeTemplate.value
            ?: return ToolResult(false, "No active template")

        if (widget in current.widgets) {
            return ToolResult(true, "위젯 이미 활성: ${widget.name}")
        }

        val updated = current.copy(widgets = current.widgets + widget)
        templateEngine.saveTemplate(updated)
        templateEngine.activateTemplate(updated)

        return ToolResult(true, "위젯 추가: ${widget.name} (${widget.region})")
    }

    private fun removeWidget(args: Map<String, Any?>): ToolResult {
        val widgetStr = (args["widget"] as? String)?.uppercase()
            ?: return ToolResult(false, "widget name is required")

        val widget = try {
            HUDWidget.valueOf(widgetStr)
        } catch (_: Exception) {
            return ToolResult(false, "Invalid widget: $widgetStr")
        }

        val current = templateEngine.activeTemplate.value
            ?: return ToolResult(false, "No active template")

        val updated = current.copy(widgets = current.widgets - widget)
        templateEngine.saveTemplate(updated)
        templateEngine.activateTemplate(updated)

        return ToolResult(true, "위젯 제거: ${widget.name}")
    }
}
