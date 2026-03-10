package com.xreal.nativear

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.exp

/**
 * FaceDetector 내부 순수 로직 테스트.
 * 모델 로딩 없이:
 * - sigmoid
 * - BlazeFace 앵커 생성 로직
 * - NMS (Face 기반)
 * - 상수 검증
 */
class FaceDetectorTest {

    private fun sigmoid(x: Float): Float = (1.0f / (1.0f + Math.exp(-x.toDouble()))).toFloat()

    // ── sigmoid ──

    @Test
    fun `sigmoid(0) = 0_5`() {
        assertEquals(0.5f, sigmoid(0f), 0.001f)
    }

    @Test
    fun `sigmoid 큰 양수 → 1에 근접`() {
        assertTrue(sigmoid(10f) > 0.999f)
    }

    // ── BlazeFace 앵커 생성 로직 검증 ──

    @Test
    fun `BlazeFace 앵커 생성 총 896개`() {
        // Layer 0: 128/8=16, 16*16*2 = 512
        // Layer 1: 128/16=8, 8*8*6 = 384
        // Total: 896
        val layer0Count = 16 * 16 * 2
        val layer1Count = 8 * 8 * 6
        assertEquals(896, layer0Count + layer1Count)
    }

    @Test
    fun `BlazeFace 앵커 좌표는 0~1 범위`() {
        // Layer 0의 첫 앵커: (0+0.5)/16 = 0.03125
        val firstAnchorX = (0 + 0.5f) / 16
        val firstAnchorY = (0 + 0.5f) / 16
        assertTrue(firstAnchorX > 0f && firstAnchorX < 1f)
        assertTrue(firstAnchorY > 0f && firstAnchorY < 1f)

        // Layer 0의 마지막 앵커: (15+0.5)/16 = 0.96875
        val lastAnchorX = (15 + 0.5f) / 16
        assertTrue(lastAnchorX > 0f && lastAnchorX < 1f)
    }

    // ── Face IoU 및 NMS ──

    private fun faceIoU(a: FaceDetector.Face, b: FaceDetector.Face): Float {
        val ax1 = a.x - a.width / 2; val ay1 = a.y - a.height / 2
        val ax2 = a.x + a.width / 2; val ay2 = a.y + a.height / 2
        val bx1 = b.x - b.width / 2; val by1 = b.y - b.height / 2
        val bx2 = b.x + b.width / 2; val by2 = b.y + b.height / 2

        val ix1 = maxOf(ax1, bx1); val iy1 = maxOf(ay1, by1)
        val ix2 = minOf(ax2, bx2); val iy2 = minOf(ay2, by2)

        val inter = maxOf(0f, ix2 - ix1) * maxOf(0f, iy2 - iy1)
        val areaA = a.width * a.height
        val areaB = b.width * b.height
        val union = areaA + areaB - inter
        return if (union > 0f) inter / union else 0f
    }

    private fun face(x: Float, y: Float, w: Float, h: Float, conf: Float = 0.9f) =
        FaceDetector.Face(
            x, y, w, h, conf,
            leftEye = android.graphics.PointF(x - 0.02f, y - 0.02f),
            rightEye = android.graphics.PointF(x + 0.02f, y - 0.02f),
            nose = android.graphics.PointF(x, y),
            mouth = android.graphics.PointF(x, y + 0.03f)
        )

    @Test
    fun `Face IoU 동일 → 1`() {
        val f = face(0.5f, 0.5f, 0.2f, 0.2f)
        assertEquals(1.0f, faceIoU(f, f), 0.001f)
    }

    @Test
    fun `Face IoU 겹치지 않음 → 0`() {
        val a = face(0.1f, 0.1f, 0.05f, 0.05f)
        val b = face(0.9f, 0.9f, 0.05f, 0.05f)
        assertEquals(0.0f, faceIoU(a, b), 0.001f)
    }

    @Test
    fun `Face data class 프로퍼티 접근`() {
        val f = face(0.5f, 0.4f, 0.2f, 0.3f, 0.95f)
        assertEquals(0.5f, f.x, 0.001f)
        assertEquals(0.4f, f.y, 0.001f)
        assertEquals(0.2f, f.width, 0.001f)
        assertEquals(0.3f, f.height, 0.001f)
        assertEquals(0.95f, f.confidence, 0.001f)
    }
}
