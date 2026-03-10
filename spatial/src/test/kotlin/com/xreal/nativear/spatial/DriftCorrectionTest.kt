package com.xreal.nativear.spatial

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * DriftCorrection 수학 테스트 — CorrectedPose, DriftCorrectionState, QuaternionUtils.
 */
class DriftCorrectionTest {

    private val EPSILON = 0.01f

    // ══════════════════════════════════════════════
    //  CorrectedPose 테스트
    // ══════════════════════════════════════════════

    @Test
    fun `CorrectedPose - 데이터 클래스 생성 및 속성 접근`() {
        val pose = CorrectedPose(1f, 2f, 3f, 0f, 0f, 0f, 1f)
        assertEquals(1f, pose.x, EPSILON)
        assertEquals(2f, pose.y, EPSILON)
        assertEquals(3f, pose.z, EPSILON)
        assertEquals(1f, pose.qw, EPSILON)
    }

    @Test
    fun `CorrectedPose - copy로 보정 적용`() {
        val original = CorrectedPose(0f, 0f, 0f, 0f, 0f, 0f, 1f)
        val corrected = original.copy(x = original.x + 1f, y = original.y + 0.5f)
        assertEquals(1f, corrected.x, EPSILON)
        assertEquals(0.5f, corrected.y, EPSILON)
    }

    // ══════════════════════════════════════════════
    //  DriftCorrectionState 테스트
    // ══════════════════════════════════════════════

    @Test
    fun `DriftCorrectionState - 초기값 모두 0`() {
        val state = DriftCorrectionState()
        assertEquals(0f, state.offsetX, EPSILON)
        assertEquals(0f, state.offsetY, EPSILON)
        assertEquals(0f, state.offsetZ, EPSILON)
        assertEquals(0f, state.yawCorrectionRad, EPSILON)
        assertEquals(0, state.baroCorrectionsApplied)
        assertEquals(0, state.yawCorrectionsApplied)
        assertEquals(0, state.loopClosuresDetected)
    }

    @Test
    fun `DriftCorrectionState - 보정 누적 추적`() {
        val state = DriftCorrectionState()
        state.offsetY += 0.1f
        state.baroCorrectionsApplied++
        state.totalBaroDriftCorrected += 0.1f

        state.offsetY += 0.15f
        state.baroCorrectionsApplied++
        state.totalBaroDriftCorrected += 0.15f

        assertEquals(0.25f, state.offsetY, EPSILON)
        assertEquals(2, state.baroCorrectionsApplied)
        assertEquals(0.25f, state.totalBaroDriftCorrected, EPSILON)
    }

    // ══════════════════════════════════════════════
    //  QuaternionUtils 테스트
    // ══════════════════════════════════════════════

    @Test
    fun `applyYawCorrection - 0 라디안 보정은 원본 유지`() {
        val result = QuaternionUtils.applyYawCorrection(0f, 0f, 0f, 1f, 0f)
        assertEquals(0f, result[0], EPSILON)
        assertEquals(0f, result[1], EPSILON)
        assertEquals(0f, result[2], EPSILON)
        assertEquals(1f, result[3], EPSILON)
    }

    @Test
    fun `applyYawCorrection - 보정 후 정규화 유지`() {
        val result = QuaternionUtils.applyYawCorrection(
            0.1f, 0.2f, 0.3f, 0.9274f,
            Math.toRadians(45.0).toFloat()
        )
        val norm = kotlin.math.sqrt(
            result[0] * result[0] + result[1] * result[1] +
            result[2] * result[2] + result[3] * result[3]
        )
        assertEquals("쿼터니언 노름 = 1", 1f, norm, 0.02f)
    }

    @Test
    fun `applyYawCorrection - 90도 Y축 회전 적용`() {
        // 단위 쿼터니언에 Y축 90도 보정
        val result = QuaternionUtils.applyYawCorrection(
            0f, 0f, 0f, 1f,
            Math.toRadians(90.0).toFloat()
        )
        // q_yaw(90°) = (0, sin(45°), 0, cos(45°)) = (0, 0.7071, 0, 0.7071)
        // q_yaw × q_identity = q_yaw
        assertEquals(0f, result[0], 0.02f) // qx
        assertEquals(0.7071f, result[1], 0.02f) // qy ≈ sin(45°)
        assertEquals(0f, result[2], 0.02f) // qz
        assertEquals(0.7071f, result[3], 0.02f) // qw ≈ cos(45°)
    }

    @Test
    fun `extractYawDegrees - 단위 쿼터니언은 0도`() {
        val yaw = QuaternionUtils.extractYawDegrees(0f, 0f, 0f, 1f)
        assertEquals(0f, yaw, EPSILON)
    }

    @Test
    fun `extractYawDegrees - Y축 90도 회전 쿼터니언`() {
        // Y축 90도 → qy=sin(45°), qw=cos(45°)
        val yaw = QuaternionUtils.extractYawDegrees(0f, 0.7071068f, 0f, 0.7071068f)
        assertEquals(90f, yaw, 1f) // ±1도 허용
    }

    @Test
    fun `extractYawDegrees - 결과는 0-360 범위`() {
        // 음수 yaw가 나올 수 있는 쿼터니언
        val yaw = QuaternionUtils.extractYawDegrees(0f, -0.7071068f, 0f, 0.7071068f)
        assertTrue("yaw=$yaw 범위 0-360", yaw in 0f..360f)
    }

    @Test
    fun `angleDifference - 같은 각도 차이는 0`() {
        assertEquals(0f, QuaternionUtils.angleDifference(90f, 90f), EPSILON)
    }

    @Test
    fun `angleDifference - 기본 차이 계산`() {
        assertEquals(10f, QuaternionUtils.angleDifference(100f, 90f), EPSILON)
        assertEquals(-10f, QuaternionUtils.angleDifference(90f, 100f), EPSILON)
    }

    @Test
    fun `angleDifference - 0도와 350도 차이는 10도`() {
        // 360도 래핑 처리
        val diff = QuaternionUtils.angleDifference(10f, 350f)
        assertEquals(20f, diff, EPSILON)
    }

    @Test
    fun `angleDifference - 350도와 10도 차이는 -20도`() {
        val diff = QuaternionUtils.angleDifference(350f, 10f)
        assertEquals(-20f, diff, EPSILON)
    }

    // ══════════════════════════════════════════════
    //  DriftStats 테스트
    // ══════════════════════════════════════════════

    @Test
    fun `DriftStats - 생성 및 필드 접근`() {
        val stats = DriftStats(
            baroYDrift = 0.5f,
            yawDrift = 3.0f,
            totalCorrections = 15,
            keyframeCount = 10,
            loopClosuresDetected = 2,
            isBaroActive = true,
            isMagActive = true,
            isLoopClosureActive = false
        )
        assertEquals(0.5f, stats.baroYDrift, EPSILON)
        assertEquals(3.0f, stats.yawDrift, EPSILON)
        assertEquals(15, stats.totalCorrections)
        assertTrue(stats.isBaroActive)
        assertTrue(stats.isMagActive)
        assertFalse(stats.isLoopClosureActive)
    }

    // ══════════════════════════════════════════════
    //  VisualKeyframe 테스트
    // ══════════════════════════════════════════════

    @Test
    fun `VisualKeyframe - 동일 타임스탬프면 equals true`() {
        val kf1 = VisualKeyframe(ByteArray(0), 0f, 0f, 0f, 0f, 0f, 0f, 0f, 12345L)
        val kf2 = VisualKeyframe(ByteArray(10), 1f, 1f, 1f, 1f, 1f, 1f, 90f, 12345L)
        assertEquals(kf1, kf2) // timestamp 같으면 equals
    }

    @Test
    fun `VisualKeyframe - 다른 타임스탬프면 equals false`() {
        val kf1 = VisualKeyframe(ByteArray(0), 0f, 0f, 0f, 0f, 0f, 0f, 0f, 100L)
        val kf2 = VisualKeyframe(ByteArray(0), 0f, 0f, 0f, 0f, 0f, 0f, 0f, 200L)
        assertNotEquals(kf1, kf2)
    }
}
