package com.xreal.nativear.spatial

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * PoseTransform 단위 테스트 — 4x4 변환 행렬 + 좌표계 변환 수학 검증.
 */
class PoseTransformTest {

    private val EPSILON = 0.001f

    // ── 단위 쿼터니언 (회전 없음) ──

    @Test
    fun `poseToMatrix - 단위 쿼터니언은 단위 회전행렬 생성`() {
        // qw=1, qx=qy=qz=0 → R = I
        val mat = PoseTransform.poseToMatrix(0f, 0f, 0f, 0f, 0f, 0f, 1f)
        // 대각선 = 1
        assertEquals(1f, mat[0], EPSILON)
        assertEquals(1f, mat[5], EPSILON)
        assertEquals(1f, mat[10], EPSILON)
        assertEquals(1f, mat[15], EPSILON)
        // 비대각선 (회전 부분) = 0
        assertEquals(0f, mat[1], EPSILON)
        assertEquals(0f, mat[2], EPSILON)
        assertEquals(0f, mat[4], EPSILON)
    }

    @Test
    fun `poseToMatrix - 평행이동이 행렬에 올바르게 배치`() {
        val mat = PoseTransform.poseToMatrix(1f, 2f, 3f, 0f, 0f, 0f, 1f)
        // row-major: [3]=tx, [7]=ty, [11]=tz
        assertEquals(1f, mat[3], EPSILON)
        assertEquals(2f, mat[7], EPSILON)
        assertEquals(3f, mat[11], EPSILON)
    }

    // ── cameraToWorld / worldToCamera 왕복 ──

    @Test
    fun `cameraToWorld - 단위 포즈에서 카메라 좌표와 월드 좌표 동일`() {
        val mat = PoseTransform.poseToMatrix(0f, 0f, 0f, 0f, 0f, 0f, 1f)
        val camPos = floatArrayOf(1f, 2f, 3f)
        val worldPos = PoseTransform.cameraToWorld(camPos, mat)
        assertEquals(1f, worldPos[0], EPSILON)
        assertEquals(2f, worldPos[1], EPSILON)
        assertEquals(3f, worldPos[2], EPSILON)
    }

    @Test
    fun `cameraToWorld - 평행이동만 있는 포즈`() {
        val mat = PoseTransform.poseToMatrix(5f, 10f, 15f, 0f, 0f, 0f, 1f)
        val camPos = floatArrayOf(1f, 0f, 0f)
        val worldPos = PoseTransform.cameraToWorld(camPos, mat)
        assertEquals(6f, worldPos[0], EPSILON)
        assertEquals(10f, worldPos[1], EPSILON)
        assertEquals(15f, worldPos[2], EPSILON)
    }

    @Test
    fun `worldToCamera - cameraToWorld 역변환`() {
        // 임의의 포즈 (평행이동 + Y축 90도 회전)
        val mat = PoseTransform.poseToMatrix(
            3f, 1f, 2f,
            0f, 0.7071068f, 0f, 0.7071068f  // Y축 90도 회전
        )
        val camPos = floatArrayOf(1f, 2f, 3f)
        val worldPos = PoseTransform.cameraToWorld(camPos, mat)
        val backToCam = PoseTransform.worldToCamera(worldPos, mat)
        assertEquals(camPos[0], backToCam[0], EPSILON)
        assertEquals(camPos[1], backToCam[1], EPSILON)
        assertEquals(camPos[2], backToCam[2], EPSILON)
    }

    // ── invertRigid 테스트 ──

    @Test
    fun `invertRigid - 단위행렬의 역행렬은 단위행렬`() {
        val identity = PoseTransform.poseToMatrix(0f, 0f, 0f, 0f, 0f, 0f, 1f)
        val inv = PoseTransform.invertRigid(identity)
        for (i in 0..15) {
            assertEquals("인덱스 $i", identity[i], inv[i], EPSILON)
        }
    }

    @Test
    fun `invertRigid - T * T_inv 은 단위행렬에 근사`() {
        // 임의의 리지드 변환
        val mat = PoseTransform.poseToMatrix(
            5f, -3f, 7f,
            0.1f, 0.2f, 0.3f, 0.9274f // 대략 정규화된 쿼터니언 (normalize 필요하지만 근사)
        )
        val inv = PoseTransform.invertRigid(mat)

        // mat * inv 를 수동 계산 (row-major 4x4 곱)
        val product = multiply4x4(mat, inv)

        // 단위행렬과 비교 (회전 부분)
        assertEquals(1f, product[0], 0.02f)
        assertEquals(1f, product[5], 0.02f)
        assertEquals(1f, product[10], 0.02f)
        assertEquals(1f, product[15], 0.02f)

        // 비대각선은 0에 가까움
        assertEquals(0f, product[1], 0.02f)
        assertEquals(0f, product[2], 0.02f)
        assertEquals(0f, product[3], 0.02f) // tx
    }

    @Test
    fun `invertRigid - 평행이동만 있는 변환의 역변환`() {
        val mat = PoseTransform.poseToMatrix(5f, 10f, 15f, 0f, 0f, 0f, 1f)
        val inv = PoseTransform.invertRigid(mat)
        // 역행렬의 평행이동 = -원본 평행이동
        assertEquals(-5f, inv[3], EPSILON)
        assertEquals(-10f, inv[7], EPSILON)
        assertEquals(-15f, inv[11], EPSILON)
    }

    // ── distance3D 테스트 ──

    @Test
    fun `distance3D - 같은 점은 거리 0`() {
        val a = floatArrayOf(1f, 2f, 3f)
        assertEquals(0f, PoseTransform.distance3D(a, a), EPSILON)
    }

    @Test
    fun `distance3D - 3-4-5 삼각형`() {
        val a = floatArrayOf(0f, 0f, 0f)
        val b = floatArrayOf(3f, 4f, 0f)
        assertEquals(5f, PoseTransform.distance3D(a, b), EPSILON)
    }

    @Test
    fun `distance3D - 3D 유클리드 거리`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(4f, 6f, 3f)
        // sqrt(9 + 16 + 0) = 5
        assertEquals(5f, PoseTransform.distance3D(a, b), EPSILON)
    }

    // ── weightedAverage 테스트 ──

    @Test
    fun `weightedAverage - 동일 가중치는 중간점`() {
        val a = floatArrayOf(0f, 0f, 0f)
        val b = floatArrayOf(10f, 10f, 10f)
        val result = PoseTransform.weightedAverage(a, b, 0.5f)
        assertEquals(5f, result[0], EPSILON)
        assertEquals(5f, result[1], EPSILON)
        assertEquals(5f, result[2], EPSILON)
    }

    @Test
    fun `weightedAverage - a 가중치 1이면 a 반환`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(10f, 20f, 30f)
        val result = PoseTransform.weightedAverage(a, b, 1f)
        assertEquals(1f, result[0], EPSILON)
        assertEquals(2f, result[1], EPSILON)
        assertEquals(3f, result[2], EPSILON)
    }

    @Test
    fun `weightedAverage - a 가중치 0이면 b 반환`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(10f, 20f, 30f)
        val result = PoseTransform.weightedAverage(a, b, 0f)
        assertEquals(10f, result[0], EPSILON)
        assertEquals(20f, result[1], EPSILON)
        assertEquals(30f, result[2], EPSILON)
    }

    // ── PoseState 오버로드 테스트 ──

    @Test
    fun `poseToMatrix - PoseState 오버로드 동작`() {
        val pose = PoseState(1f, 2f, 3f, 0f, 0f, 0f, 1f, is6DoF = true, timestamp = 0L)
        val mat = PoseTransform.poseToMatrix(pose)
        assertEquals(1f, mat[3], EPSILON)
        assertEquals(2f, mat[7], EPSILON)
        assertEquals(3f, mat[11], EPSILON)
    }

    // ── 유틸: row-major 4x4 행렬 곱 ──

    private fun multiply4x4(a: FloatArray, b: FloatArray): FloatArray {
        val result = FloatArray(16)
        for (row in 0..3) {
            for (col in 0..3) {
                var sum = 0f
                for (k in 0..3) {
                    sum += a[row * 4 + k] * b[k * 4 + col]
                }
                result[row * 4 + col] = sum
            }
        }
        return result
    }
}
