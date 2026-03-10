package com.xreal.nativear.core

import org.junit.Test
import org.junit.Assert.*

class XRealEventTest {

    // ─── InputEvent ───

    @Test
    fun `VoiceCommand 생성`() {
        val event = XRealEvent.InputEvent.VoiceCommand("안녕하세요")
        assertEquals("안녕하세요", event.text)
    }

    @Test
    fun `GestureType 전체`() {
        val types = GestureType.values()
        assertTrue(types.size >= 7)
        assertNotNull(GestureType.TAP)
        assertNotNull(GestureType.NOD)
        assertNotNull(GestureType.SHAKE)
    }

    @Test
    fun `Touch 좌표`() {
        val event = XRealEvent.InputEvent.Touch(50f, 75f)
        assertEquals(50f, event.x, 0.001f)
        assertEquals(75f, event.y, 0.001f)
    }

    // ─── PerceptionEvent ───

    @Test
    fun `ObjectsDetected 리스트`() {
        val det = com.xreal.nativear.Detection("person", 0.9f, 10f, 20f, 30f, 40f)
        val event = XRealEvent.PerceptionEvent.ObjectsDetected(listOf(det))
        assertEquals(1, event.results.size)
        assertEquals("person", event.results[0].label)
    }

    @Test
    fun `WatchHeartRate 값`() {
        val event = XRealEvent.PerceptionEvent.WatchHeartRate(72f, System.currentTimeMillis())
        assertEquals(72f, event.bpm, 0.001f)
    }

    @Test
    fun `LocationUpdated 좌표`() {
        val event = XRealEvent.PerceptionEvent.LocationUpdated(37.5665, 126.9780, null)
        assertEquals(37.5665, event.lat, 0.0001)
        assertEquals(126.9780, event.lon, 0.0001)
    }

    // ─── SystemEvent ───

    @Test
    fun `Error 이벤트 생성`() {
        val error = XRealEvent.SystemEvent.Error(
            code = "AI_ERROR",
            message = "API 호출 실패",
            throwable = RuntimeException("timeout"),
            severity = ErrorSeverity.CRITICAL
        )
        assertEquals("AI_ERROR", error.code)
        assertEquals(ErrorSeverity.CRITICAL, error.severity)
        assertNotNull(error.throwable)
    }

    @Test
    fun `SituationChanged 이벤트`() {
        val event = XRealEvent.SystemEvent.SituationChanged(
            oldSituation = com.xreal.nativear.context.LifeSituation.MORNING_ROUTINE,
            newSituation = com.xreal.nativear.context.LifeSituation.TEACHING,
            confidence = 0.95f
        )
        assertEquals(com.xreal.nativear.context.LifeSituation.TEACHING, event.newSituation)
    }

    @Test
    fun `TodoCompleted 이벤트`() {
        val event = XRealEvent.SystemEvent.TodoCompleted(
            todoId = "todo_001",
            todoTitle = "수업 계획서 작성",
            parentGoalId = "goal_001",
            category = "교육"
        )
        assertEquals("todo_001", event.todoId)
    }

    @Test
    fun `DebugLog 메시지`() {
        val event = XRealEvent.SystemEvent.DebugLog("디버그 메시지")
        assertEquals("디버그 메시지", event.message)
    }

    // ─── ActionRequest ───

    @Test
    fun `SpeakTTS 이벤트`() {
        val event = XRealEvent.ActionRequest.SpeakTTS("안녕하세요")
        assertEquals("안녕하세요", event.text)
    }

    @Test
    fun `DrawingCommand 이벤트`() {
        val element = com.xreal.nativear.DrawElement.Text(
            id = "test", x = 50f, y = 50f, text = "HUD"
        )
        val cmd = com.xreal.nativear.DrawCommand.Add(element)
        val event = XRealEvent.ActionRequest.DrawingCommand(cmd)
        assertTrue(event.command is com.xreal.nativear.DrawCommand.Add)
    }

    @Test
    fun `SaveMemory 이벤트`() {
        val event = XRealEvent.ActionRequest.SaveMemory(
            content = "중요한 기억",
            role = "user"
        )
        assertEquals("중요한 기억", event.content)
    }

    // ─── Enum 완전성 ───

    @Test
    fun `ErrorSeverity 3개`() {
        assertEquals(3, ErrorSeverity.values().size)
        assertNotNull(ErrorSeverity.CRITICAL)
        assertNotNull(ErrorSeverity.WARNING)
        assertNotNull(ErrorSeverity.INFO)
    }

    @Test
    fun `CapabilityTier 7개`() {
        val tiers = CapabilityTier.values()
        assertEquals(7, tiers.size)
    }

    @Test
    fun `DeviceMode 4개`() {
        assertEquals(4, DeviceMode.values().size)
    }

    @Test
    fun `RunningCommandType 6개`() {
        assertEquals(6, RunningCommandType.values().size)
    }

    // ─── sealed class when 분기 ───

    @Test
    fun `sealed class 4대 카테고리 분기`() {
        val events: List<XRealEvent> = listOf(
            XRealEvent.InputEvent.VoiceCommand("test"),
            XRealEvent.PerceptionEvent.WatchHeartRate(80f, System.currentTimeMillis()),
            XRealEvent.SystemEvent.DebugLog("log"),
            XRealEvent.ActionRequest.SpeakTTS("tts")
        )

        events.forEach { event ->
            val category = when (event) {
                is XRealEvent.InputEvent -> "input"
                is XRealEvent.PerceptionEvent -> "perception"
                is XRealEvent.SystemEvent -> "system"
                is XRealEvent.ActionRequest -> "action"
            }
            assertTrue(category.isNotEmpty())
        }
    }
}
