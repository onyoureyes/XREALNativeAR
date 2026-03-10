package com.xreal.nativear.renderer

import org.junit.Test
import org.junit.Assert.*
import kotlin.math.sqrt

/**
 * QuaternionMatrixConverter 단위 테스트.
 * 쿼터니언 → 4x4 column-major 변환 행렬 검증.
 */
class QuaternionMatrixConverterTest {

    private val EPSILON = 1e-6

    @Test
    fun `단위 쿼터니언은 단위행렬 생성`() {
        val matrix = QuaternionMatrixConverter.toMatrix(
            x = 0f, y = 0f, z = 0f,
            qx = 0f, qy = 0f, qz = 0f, qw = 1f
        )

        // 대각선은 1.0
        assertEquals(1.0, matrix[0], EPSILON)   // [0][0]
        assertEquals(1.0, matrix[5], EPSILON)   // [1][1]
        assertEquals(1.0, matrix[10], EPSILON)  // [2][2]
        assertEquals(1.0, matrix[15], EPSILON)  // [3][3]

        // 비대각선은 0.0
        assertEquals(0.0, matrix[1], EPSILON)
        assertEquals(0.0, matrix[2], EPSILON)
        assertEquals(0.0, matrix[3], EPSILON)
        assertEquals(0.0, matrix[4], EPSILON)
        assertEquals(0.0, matrix[6], EPSILON)
        assertEquals(0.0, matrix[7], EPSILON)
        assertEquals(0.0, matrix[8], EPSILON)
        assertEquals(0.0, matrix[9], EPSILON)
        assertEquals(0.0, matrix[11], EPSILON)
        assertEquals(0.0, matrix[12], EPSILON)
        assertEquals(0.0, matrix[13], EPSILON)
        assertEquals(0.0, matrix[14], EPSILON)
    }

    @Test
    fun `병진만 있는 경우 행렬 12,13,14 위치에 반영`() {
        val tx = 3.0f; val ty = -1.5f; val tz = 7.0f
        val matrix = QuaternionMatrixConverter.toMatrix(
            x = tx, y = ty, z = tz,
            qx = 0f, qy = 0f, qz = 0f, qw = 1f
        )

        // 회전 부분은 단위행렬
        assertEquals(1.0, matrix[0], EPSILON)
        assertEquals(1.0, matrix[5], EPSILON)
        assertEquals(1.0, matrix[10], EPSILON)

        // 병진 (column 3의 처음 3원소)
        assertEquals(tx.toDouble(), matrix[12], EPSILON)
        assertEquals(ty.toDouble(), matrix[13], EPSILON)
        assertEquals(tz.toDouble(), matrix[14], EPSILON)
        assertEquals(1.0, matrix[15], EPSILON)
    }

    @Test
    fun `Y축 90도 회전 쿼터니언 검증`() {
        // Y축 90도 회전: qw = cos(45deg), qy = sin(45deg)
        val angle = Math.PI / 2.0
        val qw = Math.cos(angle / 2.0).toFloat()
        val qy = Math.sin(angle / 2.0).toFloat()

        val matrix = QuaternionMatrixConverter.toMatrix(
            x = 0f, y = 0f, z = 0f,
            qx = 0f, qy = qy, qz = 0f, qw = qw
        )

        // Y축 90도 회전행렬 (column-major):
        // [ 0, 0, 1, 0]   column 0: [0, 0, 1, 0]
        // [ 0, 1, 0, 0]   column 1: [0, 1, 0, 0]
        // [-1, 0, 0, 0]   column 2: [-1, 0, 0, 0]  (실제로는 반대 부호 가능)
        // [ 0, 0, 0, 1]   column 3: [0, 0, 0, 1]

        // Column 0: [cos90, 0, -sin90, 0] = [0, 0, -1, 0]
        // 실제 Hamilton convention: R_00 = 1-(2*qy^2), R_20 = 2*qx*qz - 2*qw*qy
        // qx=0, qz=0이므로 R_20 = -2*qw*qy
        assertEquals(0.0, matrix[0], EPSILON)   // 1 - 2*qy^2 = 1 - 1 = 0
        assertEquals(0.0, matrix[1], EPSILON)   // xy + wz = 0
        assertEquals(-1.0, matrix[2], 0.01)     // xz - wy = 0 - qw*2*qy

        // Column 1
        assertEquals(0.0, matrix[4], EPSILON)
        assertEquals(1.0, matrix[5], EPSILON)
        assertEquals(0.0, matrix[6], EPSILON)

        // Column 2
        assertEquals(1.0, matrix[8], 0.01)      // xz + wy
        assertEquals(0.0, matrix[9], EPSILON)
        assertEquals(0.0, matrix[10], EPSILON)   // 1 - 2*(qx^2+qy^2) = 1-1 = 0
    }

    @Test
    fun `X축 90도 회전 쿼터니언 검증`() {
        val angle = Math.PI / 2.0
        val qw = Math.cos(angle / 2.0).toFloat()
        val qx = Math.sin(angle / 2.0).toFloat()

        val matrix = QuaternionMatrixConverter.toMatrix(
            x = 0f, y = 0f, z = 0f,
            qx = qx, qy = 0f, qz = 0f, qw = qw
        )

        // X축 90도 회전: Y→Z, Z→-Y
        assertEquals(1.0, matrix[0], EPSILON)   // 1 - 0 = 1
        assertEquals(0.0, matrix[5], EPSILON)   // 1 - 2*qx^2 = 0
        assertEquals(0.0, matrix[10], EPSILON)  // 1 - 2*qx^2 = 0
    }

    @Test
    fun `행렬 크기는 항상 16`() {
        val matrix = QuaternionMatrixConverter.toMatrix(1f, 2f, 3f, 0.1f, 0.2f, 0.3f, 0.9f)
        assertEquals(16, matrix.size)
    }

    @Test
    fun `동차 좌표 행의 값 검증`() {
        // row 3 (indices 3, 7, 11)은 항상 0, index 15는 항상 1
        val matrix = QuaternionMatrixConverter.toMatrix(
            x = 5f, y = 10f, z = 15f,
            qx = 0.5f, qy = 0.5f, qz = 0.5f, qw = 0.5f
        )

        assertEquals(0.0, matrix[3], EPSILON)
        assertEquals(0.0, matrix[7], EPSILON)
        assertEquals(0.0, matrix[11], EPSILON)
        assertEquals(1.0, matrix[15], EPSILON)
    }

    @Test
    fun `왕복 검증 - 쿼터니언 변환행렬과 역행렬 곱은 단위행렬`() {
        // 임의 정규화된 쿼터니언
        val len = sqrt(0.1f * 0.1f + 0.2f * 0.2f + 0.3f * 0.3f + 0.9f * 0.9f)
        val qx = 0.1f / len; val qy = 0.2f / len; val qz = 0.3f / len; val qw = 0.9f / len

        val matrix = QuaternionMatrixConverter.toMatrix(2f, 3f, 4f, qx, qy, qz, qw)

        // 역 쿼터니언 (켤레): (-qx, -qy, -qz, qw), 역 병진
        val invMatrix = QuaternionMatrixConverter.toMatrix(0f, 0f, 0f, -qx, -qy, -qz, qw)

        // 회전 부분만 곱하기 (3x3): R * R^-1 = I
        // Column-major에서 M[row][col] = array[col*4 + row]
        for (row in 0..2) {
            for (col in 0..2) {
                var sum = 0.0
                for (k in 0..2) {
                    sum += matrix[k * 4 + row] * invMatrix[col * 4 + k]
                }
                val expected = if (row == col) 1.0 else 0.0
                assertEquals("R*R^-1 [$row][$col]", expected, sum, 1e-5)
            }
        }
    }

    @Test
    fun `180도 회전 쿼터니언 검증`() {
        // Z축 180도 회전: qw=0, qz=1
        val matrix = QuaternionMatrixConverter.toMatrix(
            x = 0f, y = 0f, z = 0f,
            qx = 0f, qy = 0f, qz = 1f, qw = 0f
        )

        // Z축 180도: x→-x, y→-y, z→z
        assertEquals(-1.0, matrix[0], EPSILON)  // cos(180) = -1
        assertEquals(-1.0, matrix[5], EPSILON)  // cos(180) = -1
        assertEquals(1.0, matrix[10], EPSILON)  // z 불변
    }
}
