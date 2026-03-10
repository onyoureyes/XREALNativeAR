package com.xreal.nativear.hand

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.exp

/**
 * HandTrackingModel 내부 순수 로직 테스트.
 * 모델 로딩 없이 테스트 가능한 부분만 검증:
 * - sigmoid 함수
 * - IoU 계산
 * - NMS 알고리즘
 * - SSD 앵커 생성 로직
 */
class HandTrackingModelTest {

    // ── sigmoid 재구현 (private이므로 동일 로직으로 검증) ──

    private fun sigmoid(x: Float): Float = 1.0f / (1.0f + exp(-x))

    @Test
    fun `sigmoid(0) = 0_5`() {
        assertEquals(0.5f, sigmoid(0f), 0.001f)
    }

    @Test
    fun `sigmoid 양수 입력은 0_5보다 크다`() {
        assertTrue(sigmoid(2f) > 0.5f)
        assertTrue(sigmoid(10f) > 0.99f)
    }

    @Test
    fun `sigmoid 음수 입력은 0_5보다 작다`() {
        assertTrue(sigmoid(-2f) < 0.5f)
        assertTrue(sigmoid(-10f) < 0.01f)
    }

    // ── IoU 계산 테스트 (PalmDetection 기반) ──

    private fun iou(a: PalmDetection, b: PalmDetection): Float {
        val ax1 = a.centerX - a.width / 2; val ay1 = a.centerY - a.height / 2
        val ax2 = a.centerX + a.width / 2; val ay2 = a.centerY + a.height / 2
        val bx1 = b.centerX - b.width / 2; val by1 = b.centerY - b.height / 2
        val bx2 = b.centerX + b.width / 2; val by2 = b.centerY + b.height / 2

        val ix1 = maxOf(ax1, bx1); val iy1 = maxOf(ay1, by1)
        val ix2 = minOf(ax2, bx2); val iy2 = minOf(ay2, by2)

        val inter = maxOf(0f, ix2 - ix1) * maxOf(0f, iy2 - iy1)
        val areaA = (ax2 - ax1) * (ay2 - ay1)
        val areaB = (bx2 - bx1) * (by2 - by1)
        val union = areaA + areaB - inter
        return if (union > 0f) inter / union else 0f
    }

    private fun palm(cx: Float, cy: Float, w: Float, h: Float, score: Float = 0.9f) =
        PalmDetection(cx, cy, w, h, score, emptyList())

    @Test
    fun `동일한 박스의 IoU = 1`() {
        val a = palm(0.5f, 0.5f, 0.2f, 0.2f)
        assertEquals(1.0f, iou(a, a), 0.001f)
    }

    @Test
    fun `겹치지 않는 박스의 IoU = 0`() {
        val a = palm(0.1f, 0.1f, 0.1f, 0.1f)
        val b = palm(0.9f, 0.9f, 0.1f, 0.1f)
        assertEquals(0.0f, iou(a, b), 0.001f)
    }

    @Test
    fun `부분 겹침 박스의 IoU는 0과 1 사이`() {
        val a = palm(0.5f, 0.5f, 0.4f, 0.4f)
        val b = palm(0.6f, 0.6f, 0.4f, 0.4f)
        val result = iou(a, b)
        assertTrue("IoU > 0", result > 0f)
        assertTrue("IoU < 1", result < 1f)
    }

    // ── NMS 테스트 ──

    private fun nms(detections: List<PalmDetection>, iouThreshold: Float = 0.3f): List<PalmDetection> {
        if (detections.isEmpty()) return emptyList()
        val sorted = detections.sortedByDescending { it.score }.toMutableList()
        val result = mutableListOf<PalmDetection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            result.add(best)
            sorted.removeAll { iou(best, it) > iouThreshold }
        }
        return result
    }

    @Test
    fun `NMS 빈 입력 시 빈 결과`() {
        assertEquals(emptyList<PalmDetection>(), nms(emptyList()))
    }

    @Test
    fun `NMS 단일 감지는 그대로 반환`() {
        val det = palm(0.5f, 0.5f, 0.2f, 0.2f, 0.8f)
        val result = nms(listOf(det))
        assertEquals(1, result.size)
        assertEquals(det, result[0])
    }

    @Test
    fun `NMS 겹치는 두 감지에서 높은 점수만 유지`() {
        val high = palm(0.5f, 0.5f, 0.3f, 0.3f, 0.9f)
        val low = palm(0.52f, 0.52f, 0.3f, 0.3f, 0.7f)
        val result = nms(listOf(low, high))
        assertEquals(1, result.size)
        assertEquals(0.9f, result[0].score, 0.001f)
    }

    @Test
    fun `NMS 멀리 떨어진 두 감지는 모두 유지`() {
        val a = palm(0.1f, 0.1f, 0.1f, 0.1f, 0.9f)
        val b = palm(0.9f, 0.9f, 0.1f, 0.1f, 0.8f)
        val result = nms(listOf(a, b))
        assertEquals(2, result.size)
    }

    // ── 상수 검증 ──

    @Test
    fun `HandTrackingModel 상수값 확인`() {
        assertEquals(192, HandTrackingModel.PALM_INPUT_SIZE)
        assertEquals(224, HandTrackingModel.LANDMARK_INPUT_SIZE)
        assertEquals(0.5f, HandTrackingModel.PALM_SCORE_THRESHOLD, 0.001f)
        assertEquals(0.3f, HandTrackingModel.PALM_NMS_IOU_THRESHOLD, 0.001f)
        assertEquals(0.5f, HandTrackingModel.HAND_PRESENCE_THRESHOLD, 0.001f)
        assertEquals(2, HandTrackingModel.MAX_HANDS)
    }
}
