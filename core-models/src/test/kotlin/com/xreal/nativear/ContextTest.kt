package com.xreal.nativear

import com.xreal.nativear.context.LifeSituation
import com.xreal.nativear.context.TimeSlot
import org.junit.Test
import org.junit.Assert.*

class ContextTest {

    // ─── LifeSituation ───

    @Test
    fun `LifeSituation 전체 값 개수`() {
        assertEquals(26, LifeSituation.values().size)
    }

    @Test
    fun `LifeSituation displayName 존재`() {
        LifeSituation.values().forEach { situation ->
            assertTrue(
                "${situation.name}의 displayName이 비어있음",
                situation.displayName.isNotBlank()
            )
        }
    }

    @Test
    fun `LifeSituation icon 존재`() {
        LifeSituation.values().forEach { situation ->
            assertTrue(
                "${situation.name}의 icon이 비어있음",
                situation.icon.isNotBlank()
            )
        }
    }

    @Test
    fun `TEACHING 상황 존재`() {
        val teaching = LifeSituation.TEACHING
        assertEquals("수업/교실", teaching.displayName)
        assertEquals("school", teaching.icon)
    }

    @Test
    fun `UNKNOWN과 CUSTOM 존재`() {
        assertNotNull(LifeSituation.UNKNOWN)
        assertNotNull(LifeSituation.CUSTOM)
    }

    // ─── TimeSlot ───

    @Test
    fun `TimeSlot 전체 값 개수`() {
        assertEquals(6, TimeSlot.values().size)
    }

    @Test
    fun `fromHour 새벽 경계 (0-3)`() {
        assertEquals(TimeSlot.LATE_NIGHT, TimeSlot.fromHour(0))
        assertEquals(TimeSlot.LATE_NIGHT, TimeSlot.fromHour(3))
    }

    @Test
    fun `fromHour 새벽-아침 경계 (3-4)`() {
        assertEquals(TimeSlot.LATE_NIGHT, TimeSlot.fromHour(3))
        assertEquals(TimeSlot.EARLY_MORNING, TimeSlot.fromHour(4))
    }

    @Test
    fun `fromHour 아침 경계 (6-7)`() {
        assertEquals(TimeSlot.EARLY_MORNING, TimeSlot.fromHour(6))
        assertEquals(TimeSlot.MORNING, TimeSlot.fromHour(7))
    }

    @Test
    fun `fromHour 오전-오후 경계 (11-12)`() {
        assertEquals(TimeSlot.MORNING, TimeSlot.fromHour(11))
        assertEquals(TimeSlot.AFTERNOON, TimeSlot.fromHour(12))
    }

    @Test
    fun `fromHour 오후-저녁 경계 (16-17)`() {
        assertEquals(TimeSlot.AFTERNOON, TimeSlot.fromHour(16))
        assertEquals(TimeSlot.EVENING, TimeSlot.fromHour(17))
    }

    @Test
    fun `fromHour 저녁-밤 경계 (20-21)`() {
        assertEquals(TimeSlot.EVENING, TimeSlot.fromHour(20))
        assertEquals(TimeSlot.NIGHT, TimeSlot.fromHour(21))
    }

    @Test
    fun `fromHour 밤 끝 (23)`() {
        assertEquals(TimeSlot.NIGHT, TimeSlot.fromHour(23))
    }

    @Test
    fun `fromHour 범위 밖 음수`() {
        // else branch → LATE_NIGHT
        assertEquals(TimeSlot.LATE_NIGHT, TimeSlot.fromHour(-1))
    }

    @Test
    fun `fromHour 범위 밖 24 이상`() {
        assertEquals(TimeSlot.LATE_NIGHT, TimeSlot.fromHour(24))
        assertEquals(TimeSlot.LATE_NIGHT, TimeSlot.fromHour(100))
    }

    @Test
    fun `TimeSlot displayName 한글`() {
        assertEquals("새벽", TimeSlot.EARLY_MORNING.displayName)
        assertEquals("아침", TimeSlot.MORNING.displayName)
        assertEquals("오후", TimeSlot.AFTERNOON.displayName)
        assertEquals("저녁", TimeSlot.EVENING.displayName)
        assertEquals("밤", TimeSlot.NIGHT.displayName)
        assertEquals("심야", TimeSlot.LATE_NIGHT.displayName)
    }
}
