package com.xreal.nativear.hud

/**
 * HUDMode: Defines the available HUD display modes.
 * Each mode represents a different layout optimized for a life situation.
 */
enum class HUDMode(val displayName: String) {
    DEFAULT("기본"),
    BRIEFING("브리핑"),
    RUNNING("러닝"),
    TRAVEL("여행"),
    MUSIC("음악 연습"),
    WORK("업무"),
    SOCIAL("소셜"),
    FOCUS("집중"),
    HEALTH("건강 리포트"),
    MINIMAL("최소화"),
    DEBUG("개발자"),
    CUSTOM("사용자 정의");
}

/**
 * HUDWidget: Atomic UI components that compose a HUD template.
 * Each widget occupies a specific region of the AR display.
 */
enum class HUDWidget(val region: HUDRegion) {
    // Top
    CLOCK(HUDRegion.TOP_LEFT),
    SITUATION_BADGE(HUDRegion.TOP_CENTER),
    NOTIFICATION_DOT(HUDRegion.TOP_RIGHT),

    // Left
    TODO_CARD(HUDRegion.LEFT),
    SCHEDULE_COUNTDOWN(HUDRegion.LEFT),
    GOAL_PROGRESS(HUDRegion.LEFT),

    // Right
    BIOMETRIC_PANEL(HUDRegion.RIGHT),
    EXPERT_STATUS(HUDRegion.RIGHT),
    WEATHER_MINI(HUDRegion.RIGHT),

    // Bottom
    RUNNING_STATS(HUDRegion.BOTTOM),
    SPEED_GRAPH(HUDRegion.OVERLAY),
    TRANSLATION_OVERLAY(HUDRegion.BOTTOM),
    MUSIC_TIMER(HUDRegion.BOTTOM),
    DAILY_PROGRESS_BAR(HUDRegion.BOTTOM),

    // Overlay (full screen)
    PERSON_LABELS(HUDRegion.OVERLAY),
    OBJECT_LABELS(HUDRegion.OVERLAY),
    DEBUG_PANELS(HUDRegion.OVERLAY),

    // Popup (temporary)
    REMINDER_POPUP(HUDRegion.POPUP),
    EXPERT_ADVICE(HUDRegion.POPUP),
    CELEBRATION(HUDRegion.POPUP);
}

enum class HUDRegion {
    TOP_LEFT, TOP_CENTER, TOP_RIGHT,
    LEFT, RIGHT,
    BOTTOM,
    OVERLAY, POPUP
}

/**
 * HUDTemplate: A named combination of widgets for a specific mode.
 */
data class HUDTemplate(
    val id: String,
    val name: String,
    val mode: HUDMode,
    val widgets: List<HUDWidget>,
    val isBuiltIn: Boolean = true,
    val createdBy: String? = "system",
    val situationTriggers: Set<com.xreal.nativear.context.LifeSituation>? = null
)

/**
 * IHUDWidgetRenderer: Interface for domain-specific HUD renderers.
 *
 * Domain HUDs (PlanHUD, DebugHUD, etc.) implement this interface to receive
 * lifecycle events from HUDTemplateEngine when templates are activated/deactivated.
 */
interface IHUDWidgetRenderer {
    /** Which widgets this renderer can render */
    val supportedWidgets: Set<HUDWidget>

    /** Called when a supported widget is activated in a template */
    fun onWidgetActivated(widget: HUDWidget)

    /** Called when a supported widget is deactivated (template switch) */
    fun onWidgetDeactivated(widget: HUDWidget)
}
