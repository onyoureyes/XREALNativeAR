package com.xreal.nativear

import org.junit.Test
import org.junit.Assert.*

class DetectionTest {

    @Test
    fun `Detection 생성 시 프로퍼티 정상 접근`() {
        val det = Detection(
            label = "person",
            confidence = 0.95f,
            x = 10f, y = 20f, width = 100f, height = 200f
        )
        assertEquals("person", det.label)
        assertEquals(0.95f, det.confidence, 0.001f)
        assertEquals(10f, det.x, 0.001f)
        assertEquals(20f, det.y, 0.001f)
        assertEquals(100f, det.width, 0.001f)
        assertEquals(200f, det.height, 0.001f)
    }

    @Test
    fun `confidence 경계값 0`() {
        val det = Detection("test", 0.0f, 0f, 0f, 0f, 0f)
        assertEquals(0.0f, det.confidence, 0.001f)
    }

    @Test
    fun `confidence 경계값 1`() {
        val det = Detection("test", 1.0f, 0f, 0f, 0f, 0f)
        assertEquals(1.0f, det.confidence, 0.001f)
    }

    @Test
    fun `빈 라벨 허용`() {
        val det = Detection("", 0.5f, 0f, 0f, 0f, 0f)
        assertEquals("", det.label)
    }

    @Test
    fun `data class copy 동작`() {
        val original = Detection("car", 0.8f, 10f, 20f, 30f, 40f)
        val modified = original.copy(label = "truck", confidence = 0.9f)
        assertEquals("truck", modified.label)
        assertEquals(0.9f, modified.confidence, 0.001f)
        assertEquals(10f, modified.x, 0.001f) // 나머지 유지
    }

    @Test
    fun `data class equals 동작`() {
        val a = Detection("person", 0.9f, 1f, 2f, 3f, 4f)
        val b = Detection("person", 0.9f, 1f, 2f, 3f, 4f)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `toString 포함 내용`() {
        val det = Detection("cat", 0.75f, 10f, 20f, 30f, 40f)
        val str = det.toString()
        assertTrue(str.contains("cat"))
    }
}
