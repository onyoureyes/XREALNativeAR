package com.xreal.nativear.renderer

/**
 * Hamilton 쿼터니언 → 4x4 변환 행렬 (column-major) 변환.
 *
 * Filament/OpenGL 컨벤션의 column-major 배열을 생성한다.
 * 회전(쿼터니언) + 병진(x, y, z)을 하나의 동차 변환 행렬로 결합.
 */
object QuaternionMatrixConverter {

    /**
     * 쿼터니언(qx, qy, qz, qw)과 병진(x, y, z)을 column-major 4x4 행렬로 변환.
     *
     * @param x 병진 X
     * @param y 병진 Y
     * @param z 병진 Z
     * @param qx 쿼터니언 X 성분
     * @param qy 쿼터니언 Y 성분
     * @param qz 쿼터니언 Z 성분
     * @param qw 쿼터니언 W 성분 (스칼라)
     * @return 16원소 DoubleArray (column-major 4x4)
     */
    fun toMatrix(
        x: Float, y: Float, z: Float,
        qx: Float, qy: Float, qz: Float, qw: Float
    ): DoubleArray {
        val x2 = qx + qx; val y2 = qy + qy; val z2 = qz + qz
        val xx = qx * x2; val xy = qx * y2; val xz = qx * z2
        val yy = qy * y2; val yz = qy * z2; val zz = qz * z2
        val wx = qw * x2; val wy = qw * y2; val wz = qw * z2

        return doubleArrayOf(
            // Column 0
            (1.0 - (yy + zz)),
            (xy + wz).toDouble(),
            (xz - wy).toDouble(),
            0.0,
            // Column 1
            (xy - wz).toDouble(),
            (1.0 - (xx + zz)),
            (yz + wx).toDouble(),
            0.0,
            // Column 2
            (xz + wy).toDouble(),
            (yz - wx).toDouble(),
            (1.0 - (xx + yy)),
            0.0,
            // Column 3 (translation)
            x.toDouble(),
            y.toDouble(),
            z.toDouble(),
            1.0
        )
    }
}
