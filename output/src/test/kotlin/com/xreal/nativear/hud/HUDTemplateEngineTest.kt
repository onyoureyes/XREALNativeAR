package com.xreal.nativear.hud

import com.xreal.nativear.context.LifeSituation
import com.xreal.nativear.core.GlobalEventBus
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * HUDTemplateEngine 단위 테스트.
 * EventBus는 실제 GlobalEventBus 사용 (DrawCommand 발행만 하므로 문제 없음).
 */
class HUDTemplateEngineTest {

    private lateinit var eventBus: GlobalEventBus
    private lateinit var engine: HUDTemplateEngine

    @Before
    fun setup() {
        eventBus = GlobalEventBus()
        engine = HUDTemplateEngine(eventBus)
    }

    // ── Built-in Templates ──

    @Test
    fun `초기화 시 11개 built-in 템플릿 등록`() {
        val builtIn = engine.getBuiltInTemplates()
        assertEquals(11, builtIn.size)
    }

    @Test
    fun `built-in 템플릿 ID 전체 확인`() {
        val ids = engine.getBuiltInTemplates().map { it.id }.toSet()
        assertTrue(ids.contains("default"))
        assertTrue(ids.contains("briefing"))
        assertTrue(ids.contains("running"))
        assertTrue(ids.contains("travel"))
        assertTrue(ids.contains("music"))
        assertTrue(ids.contains("work"))
        assertTrue(ids.contains("social"))
        assertTrue(ids.contains("focus"))
        assertTrue(ids.contains("health"))
        assertTrue(ids.contains("minimal"))
        assertTrue(ids.contains("debug"))
    }

    @Test
    fun `getTemplate으로 개별 템플릿 조회`() {
        val running = engine.getTemplate("running")
        assertNotNull(running)
        assertEquals(HUDMode.RUNNING, running!!.mode)
        assertTrue(running.widgets.contains(HUDWidget.RUNNING_STATS))
    }

    @Test
    fun `존재하지 않는 템플릿 조회 시 null`() {
        assertNull(engine.getTemplate("nonexistent"))
    }

    // ── Situation → Template Mapping ──

    @Test
    fun `RUNNING 상황에서 running 템플릿 반환`() {
        val template = engine.getBestTemplate(LifeSituation.RUNNING)
        assertEquals("running", template.id)
    }

    @Test
    fun `MORNING_ROUTINE 상황에서 briefing 템플릿 반환`() {
        val template = engine.getBestTemplate(LifeSituation.MORNING_ROUTINE)
        assertEquals("briefing", template.id)
    }

    @Test
    fun `AT_DESK_WORKING 상황에서 work 템플릿 반환`() {
        val template = engine.getBestTemplate(LifeSituation.AT_DESK_WORKING)
        assertEquals("work", template.id)
    }

    @Test
    fun `SOCIAL_GATHERING 상황에서 social 템플릿 반환`() {
        val template = engine.getBestTemplate(LifeSituation.SOCIAL_GATHERING)
        assertEquals("social", template.id)
    }

    @Test
    fun `SLEEPING_PREP 상황에서 minimal 템플릿 반환`() {
        val template = engine.getBestTemplate(LifeSituation.SLEEPING_PREP)
        assertEquals("minimal", template.id)
    }

    @Test
    fun `매핑되지 않은 상황에서 default 템플릿 반환`() {
        val template = engine.getBestTemplate(LifeSituation.COOKING)
        assertEquals("default", template.id)
    }

    // ── Template Activation ──

    @Test
    fun `activateTemplate 후 activeTemplate 상태 변경`() {
        val running = engine.getTemplate("running")!!
        engine.activateTemplate(running)
        assertEquals("running", engine.activeTemplate.value?.id)
        assertEquals(HUDMode.RUNNING, engine.currentMode.value)
    }

    @Test
    fun `같은 템플릿 재적용 시 스킵 - 중복 전환 방지`() {
        val renderer = TestWidgetRenderer(setOf(HUDWidget.CLOCK))
        engine.registerRenderer(renderer)

        val running = engine.getTemplate("running")!!
        engine.activateTemplate(running)
        val firstCount = renderer.activationCount

        // 동일 템플릿 재적용
        engine.activateTemplate(running)
        assertEquals(firstCount, renderer.activationCount)
    }

    @Test
    fun `switchMode로 모드 전환`() {
        engine.switchMode(HUDMode.DEBUG)
        assertEquals(HUDMode.DEBUG, engine.currentMode.value)
        assertEquals("debug", engine.activeTemplate.value?.id)
    }

    @Test
    fun `onSituationChanged로 상황 기반 자동 전환`() {
        engine.onSituationChanged(LifeSituation.RUNNING)
        assertEquals(HUDMode.RUNNING, engine.currentMode.value)
    }

    // ── Renderer Registration ──

    @Test
    fun `렌더러 등록 후 위젯 활성화 시 콜백 호출`() {
        val renderer = TestWidgetRenderer(setOf(HUDWidget.RUNNING_STATS))
        engine.registerRenderer(renderer)

        engine.switchMode(HUDMode.RUNNING)

        assertTrue(renderer.activatedWidgets.contains(HUDWidget.RUNNING_STATS))
    }

    @Test
    fun `템플릿 전환 시 이전 위젯 비활성화 콜백 호출`() {
        val renderer = TestWidgetRenderer(setOf(HUDWidget.RUNNING_STATS, HUDWidget.DEBUG_PANELS))
        engine.registerRenderer(renderer)

        // RUNNING 활성화 → RUNNING_STATS 활성
        engine.switchMode(HUDMode.RUNNING)
        assertTrue(renderer.activatedWidgets.contains(HUDWidget.RUNNING_STATS))

        // DEBUG 전환 → RUNNING_STATS 비활성, DEBUG_PANELS 활성
        engine.switchMode(HUDMode.DEBUG)
        assertTrue(renderer.deactivatedWidgets.contains(HUDWidget.RUNNING_STATS))
        assertTrue(renderer.activatedWidgets.contains(HUDWidget.DEBUG_PANELS))
    }

    // ── Custom Templates ──

    @Test
    fun `composeTemplate으로 커스텀 템플릿 생성`() {
        val custom = engine.composeTemplate(
            name = "나의 러닝",
            situation = LifeSituation.RUNNING,
            widgets = listOf(HUDWidget.CLOCK, HUDWidget.RUNNING_STATS, HUDWidget.SPEED_GRAPH),
            createdBy = "ai_expert"
        )
        assertFalse(custom.isBuiltIn)
        assertEquals(HUDMode.CUSTOM, custom.mode)
        assertEquals(3, custom.widgets.size)
        assertEquals("ai_expert", custom.createdBy)
    }

    @Test
    fun `커스텀 템플릿이 built-in보다 우선`() {
        // 커스텀 러닝 템플릿 (위젯 4개 — built-in보다 많음)
        engine.composeTemplate(
            name = "커스텀 러닝",
            situation = LifeSituation.RUNNING,
            widgets = listOf(
                HUDWidget.CLOCK, HUDWidget.RUNNING_STATS,
                HUDWidget.SPEED_GRAPH, HUDWidget.BIOMETRIC_PANEL
            )
        )
        val best = engine.getBestTemplate(LifeSituation.RUNNING)
        assertFalse(best.isBuiltIn)
        assertEquals(4, best.widgets.size)
    }

    @Test
    fun `getCustomTemplates는 커스텀만 반환`() {
        assertEquals(0, engine.getCustomTemplates().size)
        engine.composeTemplate("test", LifeSituation.COOKING, listOf(HUDWidget.CLOCK))
        assertEquals(1, engine.getCustomTemplates().size)
    }

    @Test
    fun `saveTemplate으로 템플릿 저장`() {
        val template = HUDTemplate(
            id = "saved_1",
            name = "저장 테스트",
            mode = HUDMode.CUSTOM,
            widgets = listOf(HUDWidget.CLOCK)
        )
        engine.saveTemplate(template)
        assertNotNull(engine.getTemplate("saved_1"))
    }

    @Test
    fun `getAllTemplates는 built-in + custom 전부 반환`() {
        val initialCount = engine.getAllTemplates().size
        engine.composeTemplate("extra", LifeSituation.COOKING, listOf(HUDWidget.CLOCK))
        assertEquals(initialCount + 1, engine.getAllTemplates().size)
    }

    // ── deactivateAll ──

    @Test
    fun `deactivateAll 호출 시 에러 없음`() {
        engine.switchMode(HUDMode.RUNNING)
        engine.deactivateAll()
        // activeWidgetIds 클리어 확인은 내부 상태지만 에러 없이 실행되면 OK
    }

    // ── Helper ──

    private class TestWidgetRenderer(
        override val supportedWidgets: Set<HUDWidget>
    ) : IHUDWidgetRenderer {
        val activatedWidgets = mutableSetOf<HUDWidget>()
        val deactivatedWidgets = mutableSetOf<HUDWidget>()
        var activationCount = 0

        override fun onWidgetActivated(widget: HUDWidget) {
            activatedWidgets.add(widget)
            activationCount++
        }

        override fun onWidgetDeactivated(widget: HUDWidget) {
            deactivatedWidgets.add(widget)
            activatedWidgets.remove(widget)
        }
    }
}
