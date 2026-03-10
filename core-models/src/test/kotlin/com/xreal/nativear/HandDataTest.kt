package com.xreal.nativear

import android.graphics.RectF
import com.xreal.nativear.hand.*
import org.junit.Test
import org.junit.Assert.*

class HandDataTest {

    // ─── HandLandmark ───

    @Test
    fun `HandLandmark 생성 및 기본 z값`() {
        val lm = HandLandmark(0.5f, 0.3f)
        assertEquals(0.5f, lm.x, 0.001f)
        assertEquals(0.3f, lm.y, 0.001f)
        assertEquals(0f, lm.z, 0.001f) // 기본값
    }

    @Test
    fun `HandLandmark 3D 좌표`() {
        val lm = HandLandmark(0.1f, 0.2f, 0.3f)
        assertEquals(0.3f, lm.z, 0.001f)
    }

    // ─── HandData 상수 ───

    @Test
    fun `21개 랜드마크 인덱스 상수`() {
        assertEquals(0, HandData.WRIST)
        assertEquals(4, HandData.THUMB_TIP)
        assertEquals(8, HandData.INDEX_TIP)
        assertEquals(12, HandData.MIDDLE_TIP)
        assertEquals(16, HandData.RING_TIP)
        assertEquals(20, HandData.PINKY_TIP)
    }

    @Test
    fun `랜드마크 인덱스 연속성`() {
        val indices = listOf(
            HandData.WRIST,
            HandData.THUMB_CMC, HandData.THUMB_MCP, HandData.THUMB_IP, HandData.THUMB_TIP,
            HandData.INDEX_MCP, HandData.INDEX_PIP, HandData.INDEX_DIP, HandData.INDEX_TIP,
            HandData.MIDDLE_MCP, HandData.MIDDLE_PIP, HandData.MIDDLE_DIP, HandData.MIDDLE_TIP,
            HandData.RING_MCP, HandData.RING_PIP, HandData.RING_DIP, HandData.RING_TIP,
            HandData.PINKY_MCP, HandData.PINKY_PIP, HandData.PINKY_DIP, HandData.PINKY_TIP
        )
        assertEquals(21, indices.size)
        assertEquals((0..20).toList(), indices)
    }

    // ─── HandGestureType ───

    @Test
    fun `HandGestureType 전체 값 개수`() {
        assertEquals(14, HandGestureType.values().size)
    }

    @Test
    fun `주요 제스처 타입 존재`() {
        assertNotNull(HandGestureType.NONE)
        assertNotNull(HandGestureType.POINT)
        assertNotNull(HandGestureType.PINCH)
        assertNotNull(HandGestureType.FIST)
        assertNotNull(HandGestureType.OPEN_PALM)
        assertNotNull(HandGestureType.THUMBS_UP)
        assertNotNull(HandGestureType.SWIPE_LEFT)
        assertNotNull(HandGestureType.SWIPE_RIGHT)
    }

    // ─── GestureEvent ───

    @Test
    fun `GestureEvent 기본값`() {
        val event = GestureEvent(gesture = HandGestureType.POINT)
        assertEquals(HandGestureType.POINT, event.gesture)
        assertEquals(0, event.handIndex)
        assertEquals(1.0f, event.confidence, 0.001f)
        assertEquals(50f, event.screenX, 0.001f) // 화면 중심
        assertEquals(50f, event.screenY, 0.001f)
        assertEquals(0f, event.velocityX, 0.001f)
        assertEquals(0f, event.velocityY, 0.001f)
        assertTrue(event.timestamp > 0)
    }

    @Test
    fun `GestureEvent 스와이프 속도`() {
        val swipe = GestureEvent(
            gesture = HandGestureType.SWIPE_RIGHT,
            velocityX = 150f,
            velocityY = -20f
        )
        assertEquals(150f, swipe.velocityX, 0.001f)
        assertEquals(-20f, swipe.velocityY, 0.001f)
    }

    // ─── PalmDetection ───

    @Test
    fun `PalmDetection 생성`() {
        val palm = PalmDetection(
            centerX = 0.5f, centerY = 0.5f,
            width = 0.2f, height = 0.3f,
            score = 0.88f,
            keypoints = listOf(0.4f to 0.4f, 0.6f to 0.6f)
        )
        assertEquals(0.5f, palm.centerX, 0.001f)
        assertEquals(0.88f, palm.score, 0.001f)
        assertEquals(2, palm.keypoints.size)
    }
}
