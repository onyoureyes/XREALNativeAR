package com.xreal.nativear.spatial

/**
 * PoseTransform — VIO 포즈 (위치 + 쿼터니언) ↔ 변환 행렬 연산.
 *
 * ## 좌표계 규약
 * - VIO 월드 프레임: OpenVINS 출력 (첫 프레임 기준, 중력 정렬)
 * - 카메라 프레임: X=오른쪽, Y=아래, Z=앞 (OpenCV 표준)
 * - 쿼터니언: Hamilton 규약 (w, x, y, z) — OpenVINS에서 JPL→Hamilton 변환 완료
 *
 * ## 행렬 레이아웃
 * 4×4 변환 행렬은 **row-major** FloatArray[16]:
 * ```
 * [R00 R01 R02 Tx]    인덱스: [0  1  2  3 ]
 * [R10 R11 R12 Ty]            [4  5  6  7 ]
 * [R20 R21 R22 Tz]            [8  9  10 11]
 * [0   0   0   1 ]            [12 13 14 15]
 * ```
 *
 * ## 사용 예시
 * ```kotlin
 * // 앵커 생성: 카메라 로컬 → 월드
 * val poseMatrix = PoseTransform.poseToMatrix(pose)
 * val worldPos = PoseTransform.cameraToWorld(camPos, poseMatrix)
 *
 * // 재투영: 월드 → 카메라 로컬
 * val camPos = PoseTransform.worldToCamera(worldPos, poseMatrix)
 * ```
 */
object PoseTransform {

    /**
     * VIO 포즈 → 4×4 변환 행렬 (row-major).
     *
     * 행렬 T는 카메라→월드 변환:
     * ```
     * worldPoint = T × cameraPoint
     * ```
     *
     * @param x, y, z 위치 (미터, VIO 월드 프레임)
     * @param qx, qy, qz, qw 쿼터니언 (Hamilton 규약)
     * @return FloatArray[16] row-major 4×4 행렬
     */
    fun poseToMatrix(
        x: Float, y: Float, z: Float,
        qx: Float, qy: Float, qz: Float, qw: Float
    ): FloatArray {
        // 쿼터니언 → 회전 행렬 (row-major)
        val xx = qx * qx; val yy = qy * qy; val zz = qz * qz
        val xy = qx * qy; val xz = qx * qz; val yz = qy * qz
        val wx = qw * qx; val wy = qw * qy; val wz = qw * qz

        return floatArrayOf(
            // Row 0
            1f - 2f * (yy + zz),  2f * (xy - wz),      2f * (xz + wy),      x,
            // Row 1
            2f * (xy + wz),       1f - 2f * (xx + zz),  2f * (yz - wx),      y,
            // Row 2
            2f * (xz - wy),       2f * (yz + wx),       1f - 2f * (xx + yy), z,
            // Row 3
            0f,                    0f,                    0f,                   1f
        )
    }

    /**
     * PoseState → 4×4 변환 행렬 (편의 오버로드).
     */
    fun poseToMatrix(pose: PoseState): FloatArray =
        poseToMatrix(pose.x, pose.y, pose.z, pose.qx, pose.qy, pose.qz, pose.qw)

    /**
     * 월드 좌표 → 카메라 로컬 좌표.
     *
     * T_cam→world 의 역변환을 적용:
     * ```
     * cameraPoint = R^T × (worldPoint - translation)
     * ```
     *
     * 회전 행렬 R은 직교 행렬이므로 R^(-1) = R^T (전치)로 효율적 역변환.
     *
     * @param worldPos [x, y, z] 월드 좌표 (미터)
     * @param poseMatrix row-major 4×4 카메라→월드 행렬
     * @return [x, y, z] 카메라 로컬 좌표 (미터)
     */
    fun worldToCamera(worldPos: FloatArray, poseMatrix: FloatArray): FloatArray {
        // 평행이동 추출
        val tx = poseMatrix[3]
        val ty = poseMatrix[7]
        val tz = poseMatrix[11]

        // 월드 좌표에서 카메라 위치 빼기
        val dx = worldPos[0] - tx
        val dy = worldPos[1] - ty
        val dz = worldPos[2] - tz

        // R^T × (world - t): 전치 회전 적용
        // R^T의 행 = 원래 R의 열
        val camX = poseMatrix[0] * dx + poseMatrix[4] * dy + poseMatrix[8]  * dz
        val camY = poseMatrix[1] * dx + poseMatrix[5] * dy + poseMatrix[9]  * dz
        val camZ = poseMatrix[2] * dx + poseMatrix[6] * dy + poseMatrix[10] * dz

        return floatArrayOf(camX, camY, camZ)
    }

    /**
     * 카메라 로컬 좌표 → 월드 좌표.
     *
     * ```
     * worldPoint = R × cameraPoint + translation
     * ```
     *
     * @param camPos [x, y, z] 카메라 로컬 좌표 (미터)
     * @param poseMatrix row-major 4×4 카메라→월드 행렬
     * @return [x, y, z] 월드 좌표 (미터)
     */
    fun cameraToWorld(camPos: FloatArray, poseMatrix: FloatArray): FloatArray {
        // R × camPos + t
        val worldX = poseMatrix[0] * camPos[0] + poseMatrix[1] * camPos[1] + poseMatrix[2]  * camPos[2] + poseMatrix[3]
        val worldY = poseMatrix[4] * camPos[0] + poseMatrix[5] * camPos[1] + poseMatrix[6]  * camPos[2] + poseMatrix[7]
        val worldZ = poseMatrix[8] * camPos[0] + poseMatrix[9] * camPos[1] + poseMatrix[10] * camPos[2] + poseMatrix[11]

        return floatArrayOf(worldX, worldY, worldZ)
    }

    /**
     * 4×4 행렬의 역행렬 (리지드 변환 전용, 효율적).
     *
     * 리지드 변환 T = [R|t] 의 역변환:
     * ```
     * T^(-1) = [R^T | -R^T × t]
     * ```
     *
     * @param matrix row-major 4×4 행렬
     * @return row-major 4×4 역행렬
     */
    fun invertRigid(matrix: FloatArray): FloatArray {
        // 회전 부분 전치
        val r00 = matrix[0]; val r01 = matrix[4]; val r02 = matrix[8]
        val r10 = matrix[1]; val r11 = matrix[5]; val r12 = matrix[9]
        val r20 = matrix[2]; val r21 = matrix[6]; val r22 = matrix[10]

        // 평행이동
        val tx = matrix[3]; val ty = matrix[7]; val tz = matrix[11]

        // -R^T × t
        val itx = -(r00 * tx + r01 * ty + r02 * tz)
        val ity = -(r10 * tx + r11 * ty + r12 * tz)
        val itz = -(r20 * tx + r21 * ty + r22 * tz)

        return floatArrayOf(
            r00, r01, r02, itx,
            r10, r11, r12, ity,
            r20, r21, r22, itz,
            0f,  0f,  0f,  1f
        )
    }

    /**
     * 두 3D 점 사이 유클리드 거리 (미터).
     */
    fun distance3D(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * 두 3D 점의 가중 평균 (앵커 위치 병합용).
     *
     * @param a 첫째 점 [x, y, z]
     * @param b 둘째 점 [x, y, z]
     * @param weightA a의 가중치 (0-1)
     * @return 가중 평균 점
     */
    fun weightedAverage(a: FloatArray, b: FloatArray, weightA: Float): FloatArray {
        val wB = 1f - weightA
        return floatArrayOf(
            a[0] * weightA + b[0] * wB,
            a[1] * weightA + b[1] * wB,
            a[2] * weightA + b[2] * wB
        )
    }
}
