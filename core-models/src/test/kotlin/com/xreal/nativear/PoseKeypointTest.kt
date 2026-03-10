package com.xreal.nativear

import org.junit.Test
import org.junit.Assert.*

class PoseKeypointTest {

    @Test
    fun `PoseKeypoint 생성`() {
        val kp = PoseKeypoint(id = 0, x = 100f, y = 200f, score = 0.95f)
        assertEquals(0, kp.id)
        assertEquals(100f, kp.x, 0.001f)
        assertEquals(200f, kp.y, 0.001f)
        assertEquals(0.95f, kp.score, 0.001f)
    }

    @Test
    fun `CenterNet 17-keypoint 인덱스 범위`() {
        // CenterNet은 0~16 (17개 키포인트)
        val keypoints = (0..16).map { PoseKeypoint(it, 0f, 0f, 0.5f) }
        assertEquals(17, keypoints.size)
        assertEquals(0, keypoints.first().id)
        assertEquals(16, keypoints.last().id)
    }

    @Test
    fun `score 경계값`() {
        val low = PoseKeypoint(0, 0f, 0f, 0.0f)
        val high = PoseKeypoint(0, 0f, 0f, 1.0f)
        assertEquals(0.0f, low.score, 0.001f)
        assertEquals(1.0f, high.score, 0.001f)
    }

    @Test
    fun `data class equals`() {
        val a = PoseKeypoint(5, 10f, 20f, 0.8f)
        val b = PoseKeypoint(5, 10f, 20f, 0.8f)
        assertEquals(a, b)
    }
}
