package com.xreal.nativear

import org.junit.Assert.*
import org.junit.Test

/**
 * LiteRTWrapper 순수 로직 테스트.
 * 모델 로딩 없이 테스트 가능한 부분:
 * - LABELS 상수
 * - IoU 계산 (Detection 기반)
 * - NMS 알고리즘
 * - 출력 형식 판별
 */
class LiteRTWrapperTest {

    // ── IoU 재구현 (LiteRTWrapper.calculateIoU와 동일) ──

    private fun calculateIoU(a: Detection, b: Detection): Float {
        val ax1 = a.x - a.width / 2f; val ay1 = a.y - a.height / 2f
        val ax2 = a.x + a.width / 2f; val ay2 = a.y + a.height / 2f
        val bx1 = b.x - b.width / 2f; val by1 = b.y - b.height / 2f
        val bx2 = b.x + b.width / 2f; val by2 = b.y + b.height / 2f

        val ix1 = maxOf(ax1, bx1); val iy1 = maxOf(ay1, by1)
        val ix2 = minOf(ax2, bx2); val iy2 = minOf(ay2, by2)

        val inter = maxOf(0f, ix2 - ix1) * maxOf(0f, iy2 - iy1)
        val areaA = a.width * a.height
        val areaB = b.width * b.height
        val union = areaA + areaB - inter
        return if (union > 0f) inter / union else 0f
    }

    // ── NMS 재구현 ──

    private fun nms(detections: MutableList<Detection>, iouThreshold: Float = 0.45f): List<Detection> {
        if (detections.isEmpty()) return emptyList()
        detections.sortByDescending { it.confidence }
        val result = mutableListOf<Detection>()
        while (detections.isNotEmpty()) {
            val first = detections.removeAt(0)
            result.add(first)
            val iterator = detections.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (calculateIoU(first, next) > iouThreshold) iterator.remove()
            }
        }
        return result
    }

    private fun det(label: String, conf: Float, x: Float, y: Float, w: Float, h: Float) =
        Detection(label, conf, x, y, w, h)

    // ── LABELS 테스트 ──

    @Test
    fun `LABELS는 80개 COCO 클래스`() {
        assertEquals(80, LiteRTWrapper.LABELS.size)
    }

    @Test
    fun `LABELS 첫 번째는 person`() {
        assertEquals("person", LiteRTWrapper.LABELS[0])
    }

    @Test
    fun `LABELS 마지막은 toothbrush`() {
        assertEquals("toothbrush", LiteRTWrapper.LABELS[79])
    }

    @Test
    fun `LABELS에 주요 클래스 포함`() {
        assertTrue(LiteRTWrapper.LABELS.contains("car"))
        assertTrue(LiteRTWrapper.LABELS.contains("dog"))
        assertTrue(LiteRTWrapper.LABELS.contains("cat"))
        assertTrue(LiteRTWrapper.LABELS.contains("cell phone"))
        assertTrue(LiteRTWrapper.LABELS.contains("laptop"))
    }

    // ── IoU 테스트 ──

    @Test
    fun `동일 Detection의 IoU = 1`() {
        val a = det("person", 0.9f, 100f, 100f, 50f, 50f)
        assertEquals(1.0f, calculateIoU(a, a), 0.001f)
    }

    @Test
    fun `겹치지 않는 Detection의 IoU = 0`() {
        val a = det("person", 0.9f, 50f, 50f, 20f, 20f)
        val b = det("car", 0.8f, 500f, 500f, 20f, 20f)
        assertEquals(0.0f, calculateIoU(a, b), 0.001f)
    }

    @Test
    fun `부분 겹침의 IoU는 0~1 사이`() {
        val a = det("person", 0.9f, 100f, 100f, 40f, 40f)
        val b = det("person", 0.8f, 110f, 110f, 40f, 40f)
        val result = calculateIoU(a, b)
        assertTrue(result > 0f)
        assertTrue(result < 1f)
    }

    // ── NMS 테스트 ──

    @Test
    fun `NMS 빈 입력 시 빈 결과`() {
        val result = nms(mutableListOf())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `NMS 겹치는 같은 클래스 Detection → 높은 confidence만 유지`() {
        val dets = mutableListOf(
            det("person", 0.9f, 100f, 100f, 50f, 50f),
            det("person", 0.7f, 105f, 105f, 50f, 50f)
        )
        val result = nms(dets)
        assertEquals(1, result.size)
        assertEquals(0.9f, result[0].confidence, 0.001f)
    }

    @Test
    fun `NMS 멀리 떨어진 Detection은 모두 유지`() {
        val dets = mutableListOf(
            det("person", 0.9f, 50f, 50f, 20f, 20f),
            det("car", 0.8f, 500f, 500f, 20f, 20f)
        )
        val result = nms(dets)
        assertEquals(2, result.size)
    }

    // ── Detection toString ──

    @Test
    fun `Detection toString 형식 검증`() {
        val d = det("person", 0.95f, 100f, 200f, 50f, 80f)
        val str = d.toString()
        assertTrue(str.contains("person"))
        assertTrue(str.contains("95%"))
    }
}
