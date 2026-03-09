package com.xreal.hardware.depth

import android.util.Log

/**
 * StereoDepthEngine — 스테레오 깊이 추정 전략 관리자.
 *
 * ## 이중 전략
 * - **SparseSAD** (기본): 요청 포인트만 매칭, 빠름 (~0.5ms/점), 외부 의존성 없음
 * - **DenseSGBM**: 전체 디스패리티 맵, 느림 (~15ms/프레임), OpenCV 필요
 *
 * ## 런타임 전환
 * ```kotlin
 * engine.setStrategy(useDense = true)   // SGBM으로 전환
 * engine.setStrategy(useDense = false)  // SAD로 전환
 * ```
 *
 * ## 캘리브레이션 상수
 * StereoRectifier P1 행렬에서 추출:
 * - Focal Length: 350.706615 px (rectified)
 * - Baseline: 0.104 m (104 mm)
 * - Principal Point: (320.046, 255.722)
 */
class StereoDepthEngine {

    companion object {
        private const val TAG = "StereoDepthEngine"

        /** Rectified 초점 거리 (pixels) — StereoRectifier P1[0,0] */
        const val FOCAL_LENGTH = 350.706615f

        /** 스테레오 기선 길이 (미터) */
        const val BASELINE_M = 0.104f

        /** Rectified 주점 X — StereoRectifier P1[0,2] */
        const val CX = 320.046310f

        /** Rectified 주점 Y — StereoRectifier P1[1,2] */
        const val CY = 255.721750f

        /** 이미지 크기 */
        const val IMAGE_WIDTH = 640
        const val IMAGE_HEIGHT = 480
    }

    private val sparseSAD = SparseSADDepth(
        width = IMAGE_WIDTH, height = IMAGE_HEIGHT,
        focalLength = FOCAL_LENGTH, baselineM = BASELINE_M
    )

    private var denseSGBM: DenseSGBMDepth? = null

    @Volatile
    private var activeStrategy: IDepthStrategy = sparseSAD

    @Volatile
    var isDenseMode: Boolean = false
        private set

    /**
     * 전략 전환.
     *
     * @param useDense true=SGBM(전체 맵), false=SAD(희소)
     */
    fun setStrategy(useDense: Boolean) {
        if (useDense == isDenseMode) return

        if (useDense) {
            // SGBM 지연 초기화 (OpenCV 필요 시점에만)
            if (denseSGBM == null) {
                denseSGBM = DenseSGBMDepth(
                    fullWidth = IMAGE_WIDTH, fullHeight = IMAGE_HEIGHT,
                    focalLength = FOCAL_LENGTH, baselineM = BASELINE_M
                )
            }
            val sgbm = denseSGBM
            if (sgbm != null && sgbm.isInitialized) {
                activeStrategy = sgbm
                isDenseMode = true
                Log.i(TAG, "Switched to DenseSGBM strategy")
            } else {
                Log.w(TAG, "DenseSGBM not available, staying with SparseSAD")
            }
        } else {
            activeStrategy = sparseSAD
            isDenseMode = false
            Log.i(TAG, "Switched to SparseSAD strategy")
        }
    }

    /**
     * 새 스테레오 프레임 쌍 업데이트.
     *
     * 현재 활성 전략에 프레임 전달.
     * 두 전략 모두에 전달하여 전환 시 즉시 사용 가능하게 할 수도 있으나,
     * 성능 우선으로 활성 전략에만 전달.
     */
    fun updateStereoFrames(left: ByteArray, right: ByteArray, timestamp: Long) {
        activeStrategy.updateStereoFrames(left, right, timestamp)
    }

    /**
     * 특정 좌표의 깊이 조회 (미터).
     *
     * @param u 좌안 이미지 x (0-639)
     * @param v 좌안 이미지 y (0-479)
     * @return 깊이 (미터), 또는 null
     */
    fun queryDepthAt(u: Int, v: Int): Float? {
        return activeStrategy.queryDepthAt(u, v)
    }

    /**
     * 전체 디스패리티 맵 (SGBM 모드에서만 사용 가능).
     */
    fun getLatestDisparityMap(): FloatArray? {
        return activeStrategy.getLatestDisparityMap()
    }

    /** 현재 활성 전략 이름 */
    fun getActiveStrategyName(): String = activeStrategy.name

    /** SGBM 깊이 맵의 유효 픽셀 비율 (디버그용) */
    fun getValidPixelRatio(): Float {
        return (denseSGBM as? DenseSGBMDepth)?.getValidPixelRatio() ?: 0f
    }

    fun release() {
        sparseSAD.release()
        denseSGBM?.release()
        denseSGBM = null
        Log.i(TAG, "StereoDepthEngine released")
    }
}
