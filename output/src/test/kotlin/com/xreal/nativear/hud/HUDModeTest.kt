package com.xreal.nativear.hud

import org.junit.Test
import org.junit.Assert.*

/**
 * HUDMode, HUDWidget, HUDRegion enum 테스트.
 */
class HUDModeTest {

    // ── HUDMode ──

    @Test
    fun `HUDMode 전체 12개 값 존재`() {
        val modes = HUDMode.entries
        assertEquals(12, modes.size)
    }

    @Test
    fun `HUDMode displayName 매핑 정확`() {
        assertEquals("기본", HUDMode.DEFAULT.displayName)
        assertEquals("브리핑", HUDMode.BRIEFING.displayName)
        assertEquals("러닝", HUDMode.RUNNING.displayName)
        assertEquals("여행", HUDMode.TRAVEL.displayName)
        assertEquals("음악 연습", HUDMode.MUSIC.displayName)
        assertEquals("업무", HUDMode.WORK.displayName)
        assertEquals("소셜", HUDMode.SOCIAL.displayName)
        assertEquals("집중", HUDMode.FOCUS.displayName)
        assertEquals("건강 리포트", HUDMode.HEALTH.displayName)
        assertEquals("최소화", HUDMode.MINIMAL.displayName)
        assertEquals("개발자", HUDMode.DEBUG.displayName)
        assertEquals("사용자 정의", HUDMode.CUSTOM.displayName)
    }

    @Test
    fun `HUDMode valueOf 역매핑`() {
        assertEquals(HUDMode.RUNNING, HUDMode.valueOf("RUNNING"))
        assertEquals(HUDMode.DEBUG, HUDMode.valueOf("DEBUG"))
    }

    // ── HUDWidget ──

    @Test
    fun `HUDWidget 전체 20개 값 존재`() {
        val widgets = HUDWidget.entries
        assertEquals(20, widgets.size)
    }

    @Test
    fun `HUDWidget region 매핑 정확`() {
        assertEquals(HUDRegion.TOP_LEFT, HUDWidget.CLOCK.region)
        assertEquals(HUDRegion.TOP_CENTER, HUDWidget.SITUATION_BADGE.region)
        assertEquals(HUDRegion.TOP_RIGHT, HUDWidget.NOTIFICATION_DOT.region)
        assertEquals(HUDRegion.LEFT, HUDWidget.TODO_CARD.region)
        assertEquals(HUDRegion.LEFT, HUDWidget.SCHEDULE_COUNTDOWN.region)
        assertEquals(HUDRegion.LEFT, HUDWidget.GOAL_PROGRESS.region)
        assertEquals(HUDRegion.RIGHT, HUDWidget.BIOMETRIC_PANEL.region)
        assertEquals(HUDRegion.RIGHT, HUDWidget.EXPERT_STATUS.region)
        assertEquals(HUDRegion.RIGHT, HUDWidget.WEATHER_MINI.region)
        assertEquals(HUDRegion.BOTTOM, HUDWidget.RUNNING_STATS.region)
        assertEquals(HUDRegion.OVERLAY, HUDWidget.SPEED_GRAPH.region)
        assertEquals(HUDRegion.BOTTOM, HUDWidget.TRANSLATION_OVERLAY.region)
        assertEquals(HUDRegion.BOTTOM, HUDWidget.MUSIC_TIMER.region)
        assertEquals(HUDRegion.BOTTOM, HUDWidget.DAILY_PROGRESS_BAR.region)
        assertEquals(HUDRegion.OVERLAY, HUDWidget.PERSON_LABELS.region)
        assertEquals(HUDRegion.OVERLAY, HUDWidget.OBJECT_LABELS.region)
        assertEquals(HUDRegion.OVERLAY, HUDWidget.DEBUG_PANELS.region)
        assertEquals(HUDRegion.POPUP, HUDWidget.REMINDER_POPUP.region)
        assertEquals(HUDRegion.POPUP, HUDWidget.EXPERT_ADVICE.region)
        assertEquals(HUDRegion.POPUP, HUDWidget.CELEBRATION.region)
    }

    // ── HUDRegion ──

    @Test
    fun `HUDRegion 전체 8개 값 존재`() {
        val regions = HUDRegion.entries
        assertEquals(8, regions.size)
    }

    @Test
    fun `HUDRegion 필수 값 포함`() {
        val regionNames = HUDRegion.entries.map { it.name }.toSet()
        assertTrue(regionNames.contains("TOP_LEFT"))
        assertTrue(regionNames.contains("TOP_CENTER"))
        assertTrue(regionNames.contains("TOP_RIGHT"))
        assertTrue(regionNames.contains("LEFT"))
        assertTrue(regionNames.contains("RIGHT"))
        assertTrue(regionNames.contains("BOTTOM"))
        assertTrue(regionNames.contains("OVERLAY"))
        assertTrue(regionNames.contains("POPUP"))
    }

    // ── HUDTemplate ──

    @Test
    fun `HUDTemplate 생성 기본값 확인`() {
        val template = HUDTemplate(
            id = "test",
            name = "테스트",
            mode = HUDMode.DEFAULT,
            widgets = listOf(HUDWidget.CLOCK)
        )
        assertTrue(template.isBuiltIn)
        assertEquals("system", template.createdBy)
        assertNull(template.situationTriggers)
    }

    @Test
    fun `HUDTemplate 커스텀 생성`() {
        val template = HUDTemplate(
            id = "custom_1",
            name = "내 템플릿",
            mode = HUDMode.CUSTOM,
            widgets = listOf(HUDWidget.CLOCK, HUDWidget.TODO_CARD),
            isBuiltIn = false,
            createdBy = "user"
        )
        assertFalse(template.isBuiltIn)
        assertEquals("user", template.createdBy)
        assertEquals(2, template.widgets.size)
    }
}
