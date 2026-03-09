package com.xreal.nativear.running

/**
 * KalmanFilter2D — 4-state 선형 칼만 필터 (순수 수학, Android 의존성 없음).
 *
 * ## 설계 의도
 * GPS와 PDR(Pedestrian Dead Reckoning)을 융합하여 부드럽고 정확한 위치 추정.
 * Android 의존성이 없으므로 단위 테스트 가능.
 *
 * ## 상태 벡터
 * x = [northM, eastM, vNorth, vEast] — 로컬 ENU 미터 좌표
 *
 * ## 수학 모델
 * - **예측**: 등속도 모델 + 연속 백색잡음 가속 (processNoiseAccel = 1.5 m/s² 러닝용)
 * - **GPS 업데이트**: H=[I₂ 0], R=diag(accuracy²) — 위치 관측
 * - **PDR 업데이트**: H=[0 I₂], R=diag(uncertainty²) — 속도 의사관측
 *
 * ## 행렬 연산
 * 4x4 행렬을 DoubleArray(16) row-major로 직접 구현 (라이브러리 의존 없음).
 * predict(), updateGPS(), updatePDR() 각각 O(n³) with n=4 → 실시간 충분.
 *
 * @see PositionFusionEngine 이 필터를 사용하는 상위 엔진
 */
class KalmanFilter2D {

    // State vector [northM, eastM, vNorth, vEast]
    private val x = DoubleArray(4)

    // 4x4 covariance matrix P (row-major)
    private val P = DoubleArray(16)

    // Process noise acceleration spectral density (m/s²)
    var processNoiseAccel = 1.5

    var initialized = false
        private set

    fun reset(northM: Double, eastM: Double) {
        x[0] = northM
        x[1] = eastM
        x[2] = 0.0
        x[3] = 0.0

        // Initial covariance: large position uncertainty, moderate velocity uncertainty
        fillZero(P)
        P[idx(0, 0)] = 25.0   // 5m position std
        P[idx(1, 1)] = 25.0
        P[idx(2, 2)] = 4.0    // 2 m/s velocity std
        P[idx(3, 3)] = 4.0

        initialized = true
    }

    /**
     * Predict step: constant-velocity model.
     * F = [[1,0,dt,0],[0,1,0,dt],[0,0,1,0],[0,0,0,1]]
     */
    fun predict(dt: Double) {
        if (!initialized || dt <= 0) return

        // State prediction: x = F * x
        x[0] += x[2] * dt
        x[1] += x[3] * dt
        // x[2], x[3] unchanged (constant velocity)

        // Covariance prediction: P = F * P * F^T + Q
        // For constant-velocity with acceleration noise q:
        // Q = q² * [[dt³/3, 0, dt²/2, 0],
        //           [0, dt³/3, 0, dt²/2],
        //           [dt²/2, 0, dt, 0],
        //           [0, dt²/2, 0, dt]]
        val q = processNoiseAccel
        val q2 = q * q
        val dt2 = dt * dt
        val dt3 = dt2 * dt

        // F * P * F^T (in-place by computing new P values)
        val newP = DoubleArray(16)

        // Compute F*P first, then (F*P)*F^T
        // F*P: row i of result = row i of P + dt * row (i+2) of P (for i=0,1)
        //                        row i of P unchanged (for i=2,3)
        val FP = DoubleArray(16)
        for (j in 0..3) {
            FP[idx(0, j)] = P[idx(0, j)] + dt * P[idx(2, j)]
            FP[idx(1, j)] = P[idx(1, j)] + dt * P[idx(3, j)]
            FP[idx(2, j)] = P[idx(2, j)]
            FP[idx(3, j)] = P[idx(3, j)]
        }

        // (F*P)*F^T: col j of result = col j of FP + dt * col (j+2) of FP (for j=0,1)
        //                               col j of FP unchanged (for j=2,3)
        for (i in 0..3) {
            newP[idx(i, 0)] = FP[idx(i, 0)] + dt * FP[idx(i, 2)]
            newP[idx(i, 1)] = FP[idx(i, 1)] + dt * FP[idx(i, 3)]
            newP[idx(i, 2)] = FP[idx(i, 2)]
            newP[idx(i, 3)] = FP[idx(i, 3)]
        }

        // Add Q
        newP[idx(0, 0)] += q2 * dt3 / 3.0
        newP[idx(1, 1)] += q2 * dt3 / 3.0
        newP[idx(0, 2)] += q2 * dt2 / 2.0
        newP[idx(2, 0)] += q2 * dt2 / 2.0
        newP[idx(1, 3)] += q2 * dt2 / 2.0
        newP[idx(3, 1)] += q2 * dt2 / 2.0
        newP[idx(2, 2)] += q2 * dt
        newP[idx(3, 3)] += q2 * dt

        System.arraycopy(newP, 0, P, 0, 16)
    }

    /**
     * GPS measurement update: observe position [northM, eastM].
     * H = [[1,0,0,0],[0,1,0,0]], R = diag(accuracyM², accuracyM²)
     */
    fun updateGPS(northM: Double, eastM: Double, accuracyM: Float) {
        if (!initialized) return

        val r = (accuracyM * accuracyM).toDouble().coerceAtLeast(1.0)

        // Innovation y = z - H*x
        val y0 = northM - x[0]
        val y1 = eastM - x[1]

        // S = H*P*H^T + R (2x2)
        val s00 = P[idx(0, 0)] + r
        val s01 = P[idx(0, 1)]
        val s10 = P[idx(1, 0)]
        val s11 = P[idx(1, 1)] + r

        // S^-1 (2x2 inverse)
        val det = s00 * s11 - s01 * s10
        if (Math.abs(det) < 1e-12) return
        val invDet = 1.0 / det
        val si00 = s11 * invDet
        val si01 = -s01 * invDet
        val si10 = -s10 * invDet
        val si11 = s00 * invDet

        // K = P * H^T * S^-1 (4x2)
        // P*H^T = first two columns of P (since H^T = [[1,0],[0,1],[0,0],[0,0]])
        val k = DoubleArray(8) // 4x2
        for (i in 0..3) {
            val ph0 = P[idx(i, 0)]
            val ph1 = P[idx(i, 1)]
            k[i * 2 + 0] = ph0 * si00 + ph1 * si10
            k[i * 2 + 1] = ph0 * si01 + ph1 * si11
        }

        // x = x + K * y
        for (i in 0..3) {
            x[i] += k[i * 2 + 0] * y0 + k[i * 2 + 1] * y1
        }

        // P = (I - K*H) * P
        // K*H is 4x4: (K*H)[i][j] = K[i][0]*H[0][j] + K[i][1]*H[1][j]
        // H[0][j] = if j==0 then 1 else 0; H[1][j] = if j==1 then 1 else 0
        // So (K*H)[i][j] = K[i][j] for j=0,1 and 0 for j=2,3
        val newP2 = DoubleArray(16)
        for (i in 0..3) {
            for (j in 0..3) {
                var sum = P[idx(i, j)]
                sum -= k[i * 2 + 0] * P[idx(0, j)]
                sum -= k[i * 2 + 1] * P[idx(1, j)]
                newP2[idx(i, j)] = sum
            }
        }
        System.arraycopy(newP2, 0, P, 0, 16)
    }

    /**
     * PDR measurement update: observe velocity [vNorthM, vEastM] from step direction.
     * H = [[0,0,1,0],[0,0,0,1]], R = diag(uncertainty², uncertainty²)
     */
    fun updatePDR(vNorthMps: Double, vEastMps: Double, uncertaintyMps: Double) {
        if (!initialized) return

        val r = (uncertaintyMps * uncertaintyMps).coerceAtLeast(0.01)

        // Innovation y = z - H*x
        val y0 = vNorthMps - x[2]
        val y1 = vEastMps - x[3]

        // S = H*P*H^T + R (2x2): rows 2,3 and cols 2,3 of P + R
        val s00 = P[idx(2, 2)] + r
        val s01 = P[idx(2, 3)]
        val s10 = P[idx(3, 2)]
        val s11 = P[idx(3, 3)] + r

        val det = s00 * s11 - s01 * s10
        if (Math.abs(det) < 1e-12) return
        val invDet = 1.0 / det
        val si00 = s11 * invDet
        val si01 = -s01 * invDet
        val si10 = -s10 * invDet
        val si11 = s00 * invDet

        // K = P * H^T * S^-1 (4x2)
        // P*H^T = cols 2,3 of P
        val k = DoubleArray(8)
        for (i in 0..3) {
            val ph0 = P[idx(i, 2)]
            val ph1 = P[idx(i, 3)]
            k[i * 2 + 0] = ph0 * si00 + ph1 * si10
            k[i * 2 + 1] = ph0 * si01 + ph1 * si11
        }

        // x = x + K * y
        for (i in 0..3) {
            x[i] += k[i * 2 + 0] * y0 + k[i * 2 + 1] * y1
        }

        // P = (I - K*H) * P
        val newP2 = DoubleArray(16)
        for (i in 0..3) {
            for (j in 0..3) {
                var sum = P[idx(i, j)]
                sum -= k[i * 2 + 0] * P[idx(2, j)]
                sum -= k[i * 2 + 1] * P[idx(3, j)]
                newP2[idx(i, j)] = sum
            }
        }
        System.arraycopy(newP2, 0, P, 0, 16)
    }

    fun getPosition(): Pair<Double, Double> = Pair(x[0], x[1])
    fun getVelocity(): Pair<Double, Double> = Pair(x[2], x[3])
    fun getPositionUncertainty(): Double = Math.sqrt(P[idx(0, 0)] + P[idx(1, 1)])

    // Row-major 4x4 index
    private fun idx(row: Int, col: Int) = row * 4 + col

    private fun fillZero(arr: DoubleArray) {
        for (i in arr.indices) arr[i] = 0.0
    }
}
