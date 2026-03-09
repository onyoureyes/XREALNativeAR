package com.xreal.hardware.depth

import android.util.Log
import org.opencv.calib3d.StereoSGBM
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * DenseSGBMDepth — OpenCV StereoSGBM 기반 전체 디스패리티 맵 생성.
 *
 * ## 특징
 * - 320×240 (절반 해상도) 입력으로 ~15ms/프레임 처리
 * - 전체 디스패리티 맵 → 이후 queryDepthAt은 O(1) 룩업
 * - WLS 필터 옵션으로 디스패리티 스무딩 가능 (현재 비활성)
 *
 * ## 파라미터 (StereoSGBM)
 * - numDisparities=64 (검색 범위, 절반 해상도 기준)
 * - blockSize=5
 * - P1=100, P2=400 (스무딩 패널티: 작은/큰 디스패리티 변화)
 *
 * ## 깊이 변환
 * ```
 * depth_full_res = baseline × focal_length_full / disparity_full
 * disparity_full = disparity_half × 2  (해상도 스케일링)
 * ```
 *
 * @param fullWidth 전체 해상도 너비 (기본 640)
 * @param fullHeight 전체 해상도 높이 (기본 480)
 * @param focalLength 전체 해상도 초점 거리 (기본 350.71)
 * @param baselineM 기선 길이 (미터, 기본 0.104)
 */
class DenseSGBMDepth(
    private val fullWidth: Int = 640,
    private val fullHeight: Int = 480,
    private val focalLength: Float = FOCAL_LENGTH,
    private val baselineM: Float = BASELINE_M
) : IDepthStrategy {

    override val name = "DenseSGBM"

    companion object {
        private const val TAG = "DenseSGBMDepth"
        const val FOCAL_LENGTH = 350.706615f
        const val BASELINE_M = 0.104f

        // SGBM 파라미터
        private const val NUM_DISPARITIES = 64   // 절반 해상도 기준
        private const val BLOCK_SIZE = 5
        private const val P1 = 8 * 1 * BLOCK_SIZE * BLOCK_SIZE        // ~200
        private const val P2 = 32 * 1 * BLOCK_SIZE * BLOCK_SIZE       // ~800
        private const val DISP12_MAX_DIFF = 1
        private const val PRE_FILTER_CAP = 63
        private const val UNIQUENESS_RATIO = 10
        private const val SPECKLE_WINDOW_SIZE = 100
        private const val SPECKLE_RANGE = 32

        // 유효 깊이 범위
        private const val MIN_DEPTH = 0.3f
        private const val MAX_DEPTH = 20.0f

        // 최소 유효 디스패리티 (정수 × 16, StereoSGBM은 16배 스케일 출력)
        private const val MIN_DISPARITY_SCALED = 2 * 16
    }

    // 절반 해상도
    private val halfWidth = fullWidth / 2    // 320
    private val halfHeight = fullHeight / 2  // 240

    // OpenCV 객체 (재사용으로 할당 최소화)
    private var sgbm: StereoSGBM? = null
    private val srcMatL = Mat(fullHeight, fullWidth, CvType.CV_8UC1)
    private val srcMatR = Mat(fullHeight, fullWidth, CvType.CV_8UC1)
    private val halfMatL = Mat()
    private val halfMatR = Mat()
    private val disparityMat = Mat()  // CV_16SC1, 16배 스케일

    // 깊이 맵 캐시 (전체 해상도 기준, 미터)
    @Volatile
    private var depthCache: FloatArray? = null
    @Volatile
    private var disparityCache: FloatArray? = null
    @Volatile
    private var cacheTimestamp: Long = 0

    @Volatile
    var isInitialized = false
        private set

    init {
        try {
            sgbm = StereoSGBM.create(
                0,                     // minDisparity
                NUM_DISPARITIES,       // numDisparities (must be divisible by 16)
                BLOCK_SIZE,
                P1,
                P2,
                DISP12_MAX_DIFF,
                PRE_FILTER_CAP,
                UNIQUENESS_RATIO,
                SPECKLE_WINDOW_SIZE,
                SPECKLE_RANGE,
                StereoSGBM.MODE_SGBM_3WAY  // 3-way 최적화 (속도/품질 균형)
            )
            isInitialized = true
            Log.i(TAG, "DenseSGBMDepth initialized (${halfWidth}×${halfHeight})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SGBM: ${e.message}", e)
        }
    }

    override fun updateStereoFrames(left: ByteArray, right: ByteArray, timestamp: Long) {
        if (!isInitialized) return
        if (left.size != fullWidth * fullHeight || right.size != fullWidth * fullHeight) return

        try {
            // 전체 해상도 Mat에 데이터 쓰기
            srcMatL.put(0, 0, left)
            srcMatR.put(0, 0, right)

            // 절반 해상도로 리사이즈 (속도 최적화)
            val halfSize = Size(halfWidth.toDouble(), halfHeight.toDouble())
            Imgproc.resize(srcMatL, halfMatL, halfSize, 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.resize(srcMatR, halfMatR, halfSize, 0.0, 0.0, Imgproc.INTER_AREA)

            // SGBM 디스패리티 계산
            sgbm?.compute(halfMatL, halfMatR, disparityMat)

            // 디스패리티 맵 → 깊이 맵 변환 (전체 해상도 기준으로 스케일)
            convertToDepthMap(timestamp)

        } catch (e: Exception) {
            Log.e(TAG, "SGBM computation failed: ${e.message}", e)
        }
    }

    /**
     * SGBM 디스패리티 맵 → 전체 해상도 깊이 맵 변환.
     *
     * StereoSGBM 출력은 CV_16SC1, 값 = disparity × 16.
     * 절반 해상도 디스패리티를 전체 해상도로 변환:
     * disparity_full = disparity_half × 2
     */
    private fun convertToDepthMap(timestamp: Long) {
        val rows = disparityMat.rows()
        val cols = disparityMat.cols()
        if (rows == 0 || cols == 0) return

        val depthMap = FloatArray(fullWidth * fullHeight)
        val dispMap = FloatArray(fullWidth * fullHeight)
        val disparityData = ShortArray(cols)

        for (row in 0 until rows) {
            disparityMat.get(row, 0, disparityData)

            // 절반 해상도 행 → 전체 해상도 2행에 매핑
            val fullRow1 = row * 2
            val fullRow2 = fullRow1 + 1

            for (col in 0 until cols) {
                val dispScaled = disparityData[col].toInt()

                // 전체 해상도 좌표
                val fullCol1 = col * 2
                val fullCol2 = fullCol1 + 1

                if (dispScaled > MIN_DISPARITY_SCALED) {
                    // 절반 해상도 디스패리티 → 전체 해상도 디스패리티
                    // disp_half = dispScaled / 16.0 (SGBM 16배 스케일)
                    // disp_full = disp_half × 2
                    val disparityFull = dispScaled.toFloat() / 16f * 2f

                    val depth = baselineM * focalLength / disparityFull
                    val validDepth = if (depth in MIN_DEPTH..MAX_DEPTH) depth else 0f

                    // 4개 전체 해상도 픽셀에 복사
                    if (fullRow1 < fullHeight && fullCol1 < fullWidth) {
                        depthMap[fullRow1 * fullWidth + fullCol1] = validDepth
                        dispMap[fullRow1 * fullWidth + fullCol1] = disparityFull
                    }
                    if (fullRow1 < fullHeight && fullCol2 < fullWidth) {
                        depthMap[fullRow1 * fullWidth + fullCol2] = validDepth
                        dispMap[fullRow1 * fullWidth + fullCol2] = disparityFull
                    }
                    if (fullRow2 < fullHeight && fullCol1 < fullWidth) {
                        depthMap[fullRow2 * fullWidth + fullCol1] = validDepth
                        dispMap[fullRow2 * fullWidth + fullCol1] = disparityFull
                    }
                    if (fullRow2 < fullHeight && fullCol2 < fullWidth) {
                        depthMap[fullRow2 * fullWidth + fullCol2] = validDepth
                        dispMap[fullRow2 * fullWidth + fullCol2] = disparityFull
                    }
                }
            }
        }

        depthCache = depthMap
        disparityCache = dispMap
        cacheTimestamp = timestamp
    }

    override fun queryDepthAt(u: Int, v: Int): Float? {
        val cache = depthCache ?: return null
        if (u < 0 || u >= fullWidth || v < 0 || v >= fullHeight) return null

        val depth = cache[v * fullWidth + u]
        return if (depth > 0f) depth else null
    }

    override fun getLatestDisparityMap(): FloatArray? = disparityCache

    /**
     * 깊이 맵의 유효 픽셀 비율 (디버그/품질 모니터링용).
     */
    fun getValidPixelRatio(): Float {
        val cache = depthCache ?: return 0f
        val validCount = cache.count { it > 0f }
        return validCount.toFloat() / cache.size
    }

    /**
     * 캐시된 깊이 맵의 타임스탬프.
     */
    fun getCacheTimestamp(): Long = cacheTimestamp

    override fun release() {
        sgbm = null
        srcMatL.release()
        srcMatR.release()
        halfMatL.release()
        halfMatR.release()
        disparityMat.release()
        depthCache = null
        disparityCache = null
        isInitialized = false
        Log.i(TAG, "DenseSGBMDepth released")
    }
}
