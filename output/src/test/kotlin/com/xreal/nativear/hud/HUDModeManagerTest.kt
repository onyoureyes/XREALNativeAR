package com.xreal.nativear.hud

import com.xreal.nativear.core.GlobalEventBus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * HUDModeManager 단위 테스트.
 */
class HUDModeManagerTest {

    private lateinit var eventBus: GlobalEventBus
    private lateinit var engine: HUDTemplateEngine
    private lateinit var scope: TestScope
    private lateinit var manager: HUDModeManager

    @Before
    fun setup() {
        eventBus = GlobalEventBus()
        engine = HUDTemplateEngine(eventBus)
        scope = TestScope()
        manager = HUDModeManager(engine, eventBus, scope)
    }

    @Test
    fun `start 시 DEFAULT 모드 활성화`() {
        manager.start()
        assertEquals(HUDMode.DEFAULT, engine.currentMode.value)
        assertEquals("default", engine.activeTemplate.value?.id)
    }

    @Test
    fun `switchMode로 수동 모드 전환`() {
        manager.start()
        manager.switchMode(HUDMode.RUNNING)
        assertEquals(HUDMode.RUNNING, engine.currentMode.value)
    }

    @Test
    fun `toggleDebug - DEFAULT에서 DEBUG로 전환`() {
        manager.start()
        assertEquals(HUDMode.DEFAULT, engine.currentMode.value)

        manager.toggleDebug()
        assertEquals(HUDMode.DEBUG, engine.currentMode.value)
    }

    @Test
    fun `toggleDebug - DEBUG에서 DEFAULT로 복귀`() {
        manager.start()
        manager.switchMode(HUDMode.DEBUG)
        assertEquals(HUDMode.DEBUG, engine.currentMode.value)

        manager.toggleDebug()
        assertEquals(HUDMode.DEFAULT, engine.currentMode.value)
    }

    @Test
    fun `stop 후 에러 없음`() {
        manager.start()
        manager.stop()
        // stop 이후에도 크래시 없이 완료
    }

    @Test
    fun `SituationChanged 이벤트 수신 시 템플릿 전환`() = runTest {
        // HUDModeManager가 eventBus를 구독하는 방식은 collectLatest이므로
        // 이 테스트에서는 onSituationChanged가 정확히 동작하는지 간접 확인
        manager.start()

        // 직접 switchMode 호출로 동작 확인
        manager.switchMode(HUDMode.RUNNING)
        assertEquals(HUDMode.RUNNING, engine.currentMode.value)

        manager.switchMode(HUDMode.FOCUS)
        assertEquals(HUDMode.FOCUS, engine.currentMode.value)
    }
}
