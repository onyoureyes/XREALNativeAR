package com.xreal.nativear.spatial

import org.junit.Assert.*
import org.junit.Test

/**
 * LoopClosureResult + VisualLoopCloser 상수 테스트.
 */
class LoopClosureResultTest {

    @Test
    fun `LoopClosureResult - 데이터 클래스 필드 접근`() {
        val keyframe = VisualKeyframe(ByteArray(0), 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1000L)
        val result = LoopClosureResult(
            driftX = 2.5f,
            driftZ = 1.5f,
            driftDistance = 2.915f,
            similarity = 0.92f,
            matchedKeyframe = keyframe,
            timeDiffMs = 120_000L
        )
        assertEquals(2.5f, result.driftX, 0.01f)
        assertEquals(1.5f, result.driftZ, 0.01f)
        assertEquals(0.92f, result.similarity, 0.01f)
        assertEquals(120_000L, result.timeDiffMs)
    }

    @Test
    fun `VisualLoopCloser - 상수 유효성`() {
        assertEquals(360, VisualLoopCloser.MAX_KEYFRAMES)
        assertEquals(60L, VisualLoopCloser.MIN_TIME_GAP_SEC)
        assertTrue(VisualLoopCloser.SIMILARITY_THRESHOLD > 0f)
        assertTrue(VisualLoopCloser.SIMILARITY_THRESHOLD < 1f)
        assertTrue(VisualLoopCloser.MIN_DRIFT_DISTANCE_M > 0f)
        assertTrue(VisualLoopCloser.MAX_CORRECTION_DISTANCE_M > VisualLoopCloser.MIN_DRIFT_DISTANCE_M)
    }

    @Test
    fun `DriftCorrectionManager - 보정률 상수 범위 검증`() {
        // 보정률은 0-1 사이
        assertTrue(DriftCorrectionManager.BARO_CORRECTION_RATE in 0f..1f)
        assertTrue(DriftCorrectionManager.YAW_CORRECTION_RATE in 0f..1f)
        assertTrue(DriftCorrectionManager.LOOP_CLOSURE_CORRECTION_RATE in 0f..1f)
    }
}
