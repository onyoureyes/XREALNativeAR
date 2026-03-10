package com.xreal.nativear.hud

import com.xreal.nativear.core.XRealLogger
import com.xreal.nativear.DrawCommand
import com.xreal.nativear.DrawElement
import com.xreal.nativear.context.LifeSituation
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * HUDTemplateEngine: Manages HUD templates and renders widgets.
 *
 * Provides:
 * - 11 built-in templates (one per HUDMode)
 * - Dynamic template composition (AI can create new templates)
 * - Situation-based auto-switching
 * - Widget rendering via DrawElement system
 */
class HUDTemplateEngine(
    private val eventBus: GlobalEventBus
) {
    companion object {
        private const val TAG = "HUDTemplateEngine"
        private const val WIDGET_PREFIX = "hud_widget_"
    }

    // ─── Domain Widget Renderers ───
    private val widgetRenderers = mutableListOf<IHUDWidgetRenderer>()
    private val activeRenderedWidgets = mutableSetOf<HUDWidget>()

    /**
     * Register a domain HUD renderer. Called during DI setup.
     */
    fun registerRenderer(renderer: IHUDWidgetRenderer) {
        widgetRenderers.add(renderer)
        XRealLogger.impl.d(TAG, "Registered renderer: ${renderer.javaClass.simpleName} for ${renderer.supportedWidgets}")
    }

    private val templates = mutableMapOf<String, HUDTemplate>()
    private val _activeTemplate = MutableStateFlow<HUDTemplate?>(null)
    val activeTemplate: StateFlow<HUDTemplate?> = _activeTemplate.asStateFlow()

    private val _currentMode = MutableStateFlow(HUDMode.DEFAULT)
    val currentMode: StateFlow<HUDMode> = _currentMode.asStateFlow()

    private val activeWidgetIds = mutableSetOf<String>()

    init {
        registerBuiltInTemplates()
    }

    // ─── Built-in Templates ───

    private fun registerBuiltInTemplates() {
        val builtIn = listOf(
            HUDTemplate(
                id = "default", name = "기본", mode = HUDMode.DEFAULT,
                widgets = listOf(
                    HUDWidget.CLOCK, HUDWidget.SITUATION_BADGE,
                    HUDWidget.TODO_CARD, HUDWidget.OBJECT_LABELS,
                    HUDWidget.PERSON_LABELS
                )
            ),
            HUDTemplate(
                id = "briefing", name = "브리핑", mode = HUDMode.BRIEFING,
                widgets = listOf(
                    HUDWidget.CLOCK, HUDWidget.TODO_CARD,
                    HUDWidget.SCHEDULE_COUNTDOWN, HUDWidget.GOAL_PROGRESS,
                    HUDWidget.WEATHER_MINI, HUDWidget.BIOMETRIC_PANEL
                ),
                situationTriggers = setOf(LifeSituation.MORNING_ROUTINE)
            ),
            HUDTemplate(
                id = "running", name = "러닝", mode = HUDMode.RUNNING,
                widgets = listOf(
                    HUDWidget.CLOCK, HUDWidget.RUNNING_STATS,
                    HUDWidget.BIOMETRIC_PANEL, HUDWidget.DAILY_PROGRESS_BAR
                ),
                situationTriggers = setOf(LifeSituation.RUNNING)
            ),
            HUDTemplate(
                id = "travel", name = "여행", mode = HUDMode.TRAVEL,
                widgets = listOf(
                    HUDWidget.CLOCK, HUDWidget.TRANSLATION_OVERLAY,
                    HUDWidget.OBJECT_LABELS, HUDWidget.WEATHER_MINI
                ),
                situationTriggers = setOf(
                    LifeSituation.TRAVELING_NEW_PLACE,
                    LifeSituation.TRAVELING_TRANSIT
                )
            ),
            HUDTemplate(
                id = "music", name = "음악 연습", mode = HUDMode.MUSIC,
                widgets = listOf(
                    HUDWidget.CLOCK, HUDWidget.MUSIC_TIMER,
                    HUDWidget.GOAL_PROGRESS
                ),
                situationTriggers = setOf(LifeSituation.GUITAR_PRACTICE)
            ),
            HUDTemplate(
                id = "work", name = "업무", mode = HUDMode.WORK,
                widgets = listOf(
                    HUDWidget.CLOCK, HUDWidget.TODO_CARD,
                    HUDWidget.SCHEDULE_COUNTDOWN, HUDWidget.NOTIFICATION_DOT
                ),
                situationTriggers = setOf(
                    LifeSituation.AT_DESK_WORKING,
                    LifeSituation.IN_MEETING
                )
            ),
            HUDTemplate(
                id = "social", name = "소셜", mode = HUDMode.SOCIAL,
                widgets = listOf(
                    HUDWidget.CLOCK, HUDWidget.PERSON_LABELS,
                    HUDWidget.EXPERT_ADVICE
                ),
                situationTriggers = setOf(
                    LifeSituation.SOCIAL_GATHERING,
                    LifeSituation.PHONE_CALL
                )
            ),
            HUDTemplate(
                id = "focus", name = "집중", mode = HUDMode.FOCUS,
                widgets = listOf(HUDWidget.CLOCK, HUDWidget.TODO_CARD),
                situationTriggers = setOf(
                    LifeSituation.STUDYING,
                    LifeSituation.READING
                )
            ),
            HUDTemplate(
                id = "health", name = "건강", mode = HUDMode.HEALTH,
                widgets = listOf(
                    HUDWidget.CLOCK, HUDWidget.BIOMETRIC_PANEL,
                    HUDWidget.DAILY_PROGRESS_BAR, HUDWidget.GOAL_PROGRESS
                )
            ),
            HUDTemplate(
                id = "minimal", name = "최소화", mode = HUDMode.MINIMAL,
                widgets = listOf(HUDWidget.CLOCK),
                situationTriggers = setOf(
                    LifeSituation.SLEEPING_PREP,
                    LifeSituation.RELAXING_HOME
                )
            ),
            HUDTemplate(
                id = "debug", name = "개발자", mode = HUDMode.DEBUG,
                widgets = listOf(
                    HUDWidget.CLOCK, HUDWidget.DEBUG_PANELS,
                    HUDWidget.EXPERT_STATUS
                )
            )
        )

        builtIn.forEach { template ->
            templates[template.id] = template
        }
        XRealLogger.impl.i(TAG, "Registered ${builtIn.size} built-in templates")
    }

    // ─── Template Management ───

    /**
     * Compose a new template dynamically (used by AI experts).
     */
    fun composeTemplate(
        name: String,
        situation: LifeSituation,
        widgets: List<HUDWidget>,
        createdBy: String? = null
    ): HUDTemplate {
        val id = "custom_${name.lowercase().replace(" ", "_")}_${System.currentTimeMillis() % 10000}"
        val template = HUDTemplate(
            id = id,
            name = name,
            mode = HUDMode.CUSTOM,
            widgets = widgets,
            isBuiltIn = false,
            createdBy = createdBy,
            situationTriggers = setOf(situation)
        )
        templates[id] = template
        XRealLogger.impl.i(TAG, "Composed new template: $name with ${widgets.size} widgets")
        return template
    }

    /**
     * Save a custom template.
     */
    fun saveTemplate(template: HUDTemplate) {
        templates[template.id] = template
    }

    /**
     * Get the best template for a situation.
     */
    fun getBestTemplate(situation: LifeSituation): HUDTemplate {
        // 1. Check custom templates first (user/AI created)
        val custom = templates.values.filter {
            !it.isBuiltIn && it.situationTriggers?.contains(situation) == true
        }.maxByOrNull { it.widgets.size }

        if (custom != null) return custom

        // 2. Check built-in templates
        val builtIn = templates.values.filter {
            it.isBuiltIn && it.situationTriggers?.contains(situation) == true
        }.firstOrNull()

        if (builtIn != null) return builtIn

        // 3. Fallback to DEFAULT
        return templates["default"]!!
    }

    /**
     * Activate a template — renders its widgets and deactivates others.
     */
    fun activateTemplate(template: HUDTemplate) {
        if (_activeTemplate.value?.id == template.id) return

        XRealLogger.impl.i(TAG, "Switching HUD: ${_activeTemplate.value?.name ?: "none"} -> ${template.name}")

        // Deactivate widgets no longer in the new template
        val newWidgets = template.widgets.toSet()
        val widgetsToDeactivate = activeRenderedWidgets - newWidgets
        val widgetsToActivate = newWidgets - activeRenderedWidgets

        // Notify renderers of deactivated widgets
        for (widget in widgetsToDeactivate) {
            widgetRenderers.filter { widget in it.supportedWidgets }.forEach { renderer ->
                try { renderer.onWidgetDeactivated(widget) }
                catch (e: Exception) { XRealLogger.impl.e(TAG, "Deactivate error: ${e.message}") }
            }
        }

        // Clear previous HUDTemplateEngine-owned elements
        deactivateAll()

        // Set new template
        _activeTemplate.value = template
        _currentMode.value = template.mode

        // Render built-in widgets + notify domain renderers
        renderWidgetIndicators(template)

        // Notify renderers of newly activated widgets
        for (widget in widgetsToActivate) {
            widgetRenderers.filter { widget in it.supportedWidgets }.forEach { renderer ->
                try { renderer.onWidgetActivated(widget) }
                catch (e: Exception) { XRealLogger.impl.e(TAG, "Activate error: ${e.message}") }
            }
        }

        activeRenderedWidgets.clear()
        activeRenderedWidgets.addAll(newWidgets)
    }

    /**
     * Deactivate all HUD widgets.
     */
    fun deactivateAll() {
        for (widgetId in activeWidgetIds.toList()) {
            publishDraw(DrawCommand.Remove(widgetId))
        }
        activeWidgetIds.clear()
    }

    /**
     * Switch to a specific HUD mode.
     */
    fun switchMode(mode: HUDMode) {
        val template = templates.values.find { it.mode == mode }
            ?: templates["default"]!!
        activateTemplate(template)
    }

    /**
     * Auto-switch based on situation.
     */
    fun onSituationChanged(situation: LifeSituation) {
        val best = getBestTemplate(situation)
        activateTemplate(best)
    }

    // ─── Widget Rendering ───

    private fun renderWidgetIndicators(template: HUDTemplate) {
        for (widget in template.widgets) {
            val id = "$WIDGET_PREFIX${widget.name.lowercase()}"

            when (widget) {
                HUDWidget.CLOCK -> renderClock(id)
                HUDWidget.SITUATION_BADGE -> renderSituationBadge(id)
                // Other widgets are rendered by their respective HUD classes
                // (PlanHUD, RunningCoachHUD, etc.) — we just show a minimal indicator
                else -> {
                    // No-op: specific widgets rendered by domain HUD managers
                }
            }

            activeWidgetIds.add(id)
        }
    }

    private fun renderClock(id: String) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        publishDraw(DrawCommand.Add(DrawElement.Text(
            id = id, x = 2f, y = 2f,
            text = time, size = 16f,
            color = "#FFFFFF", opacity = 0.6f
        )))
    }

    private fun renderSituationBadge(id: String) {
        val template = _activeTemplate.value ?: return
        publishDraw(DrawCommand.Add(DrawElement.Text(
            id = id, x = 40f, y = 2f,
            text = template.name, size = 14f,
            color = "#00CCFF", opacity = 0.5f, bold = true
        )))
    }

    private fun publishDraw(command: DrawCommand) {
        eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(command))
    }

    // ─── Query API ───

    fun getTemplate(id: String): HUDTemplate? = templates[id]
    fun getAllTemplates(): List<HUDTemplate> = templates.values.toList()
    fun getBuiltInTemplates(): List<HUDTemplate> = templates.values.filter { it.isBuiltIn }
    fun getCustomTemplates(): List<HUDTemplate> = templates.values.filter { !it.isBuiltIn }
}
