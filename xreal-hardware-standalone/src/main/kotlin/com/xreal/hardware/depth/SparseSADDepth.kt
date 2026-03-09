package com.xreal.hardware.depth

import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * SparseSADDepth — SAD(Sum of Absolute Differences) 윈도우 매칭 기반 희소 깊이 추정.
 *
 * ## 특징
 * - 순수 Kotlin 구현, 외부 의존성 없음
 * - 요청된 포인트만 매칭 (~0.5ms/점)
 * - 서브픽셀 보간으로 디스패리티 정밀도 향상
 * - 좌→우 / 우→좌 교차 검증으로 오매칭 필터링
 *
 * ## 스테레오 깊이 공식
 * ```
 * depth = baseline × focalLength / disparity
 * ```
 *
 * @param width 이미지 너비 (기본 640)
 * @param height 이미지 높이 (기본 480)
 * @param focalLength rectified 초점 거리 (기본 350.71, P1에서 추출)
 * @param baselineM 스테레오 기선 길이 (미터, 기본 0.104)
 */
class SparseSADDepth(
    private val width: Int = 640,
    private val height: Int = 480,
    private val focalLength: Float = FOCAL_LENGTH,
    private val baselineM: Float = BASELINE_M
) : IDepthStrategy {

    override val name = "SparseSAD"

    companion object {
        private const val TAG = "SparseSADDepth"
        const val FOCAL_LENGTH = 350.706615f
        const val BASELINE_M = 0.104f

        /** SAD 윈도우 반 크기 (총 윈도우 = 2*HALF_WIN+1) */
        private const val HALF_WIN = 10          // 21×21 윈도우

        /** 최대 디스패리티 탐색 범위 (pixels) */
        private const val MAX_DISPARITY = 96

        /** 최소 유효 디스패리티 (너무 작으면 먼 거리 → 노이즈) */
        private const val MIN_DISPARITY = 2

        /** 좌→우/우→좌 교차 검증 허용 오차 (pixels) */
        private const val LR_CHECK_THRESHOLD = 2

        /** 최소/최대 유효 깊이 (미터) */
        private const val MIN_DEPTH = 0.3f
        private const val MAX_DEPTH = 20.0f
    }

    @Volatile
    private var leftFrame: ByteArray? = null
    @Volatile
    private var rightFrame: ByteArray? = null
    @Volatile
    private var frameTimestamp: Long = 0

    override fun updateStereoFrames(left: ByteArray, right: ByteArray, timestamp: Long) {
        if (left.size != width * height || right.size != width * height) {
            Log.w(TAG, "Frame size mismatch: ${left.size} != ${width * height}")
            return
        }
        leftFrame = left
        rightFrame = right
        frameTimestamp = timestamp
    }

    override fun queryDepthAt(u: Int, v: Int): Float? {
        val left = leftFrame ?: return null
        val right = rightFrame ?: return null

        // 경계 체크 (윈도우가 이미지 밖으로 나가지 않도록)
        if (u - HALF_WIN < 0 || u + HALF_WIN >= width) return null
        if (v - HALF_WIN < 0 || v + HALF_WIN >= height) return null

        // 좌→우 매칭: 좌안 (u,v) → 우안 (u-d, v) 탐색
        val disparity = matchPoint(left, right, u, v) ?: return null

        // 교차 검증: 우안 (u-d, v) → 좌안 역매칭
        val uRight = (u - disparity).toInt()
        if (uRight - HALF_WIN >= 0 && uRight + HALF_WIN < width) {
            val reverseDisp = matchPointReverse(right, left, uRight, v)
            if (reverseDisp != null) {
                val diff = abs(disparity - reverseDisp)
                if (diff > LR_CHECK_THRESHOLD) {
                    return null  // 교차 검증 실패 → 신뢰 불가
                }
            }
        }

        // 디스패리티 → 깊이
        val depth = baselineM * focalLength / disparity
        if (depth < MIN_DEPTH || depth > MAX_DEPTH) return null

        return depth
    }

    /**
     * 좌→우 SAD 매칭 (서브픽셀 보간 포함).
     *
     * @return 서브픽셀 디스패리티 값, 또는 null (매칭 실패)
     */
    private fun matchPoint(left: ByteArray, right: ByteArray, u: Int, v: Int): Float? {
        var bestDisp = 0
        var bestCost = Int.MAX_VALUE
        var secondBestCost = Int.MAX_VALUE

        val maxSearchD = min(MAX_DISPARITY, u)  // 우안 좌표가 0 미만으로 가지 않도록

        // 전체 디스패리티 범위 SAD 비용 계산
        for (d in MIN_DISPARITY..maxSearchD) {
            val cost = computeSAD(left, right, u, v, d)
            if (cost < bestCost) {
                secondBestCost = bestCost
                bestCost = cost
                bestDisp = d
            } else if (cost < secondBestCost) {
                secondBestCost = cost
            }
        }

        // 고유성 검사: 최적 비용이 충분히 구분되는지
        if (bestCost == Int.MAX_VALUE) return null
        if (secondBestCost != Int.MAX_VALUE) {
            val ratio = bestCost.toFloat() / secondBestCost.toFloat()
            if (ratio > 0.95f) return null  // 모호한 매칭
        }

        // 서브픽셀 보간 (포물선 피팅)
        return subpixelRefine(left, right, u, v, bestDisp, maxSearchD)
    }

    /**
     * 우→좌 역매칭 (교차 검증용, 서브픽셀 없이 정수 디스패리티만).
     */
    private fun matchPointReverse(right: ByteArray, left: ByteArray, uRight: Int, v: Int): Float? {
        var bestDisp = 0
        var bestCost = Int.MAX_VALUE

        val maxSearchD = min(MAX_DISPARITY, width - 1 - uRight)

        for (d in MIN_DISPARITY..maxSearchD) {
            val uLeft = uRight + d
            if (uLeft + HALF_WIN >= width) continue

            var cost = 0
            for (dy in -HALF_WIN..HALF_WIN) {
                val rowOffset = (v + dy) * width
                for (dx in -HALF_WIN..HALF_WIN) {
                    val rVal = right[rowOffset + uRight + dx].toInt() and 0xFF
                    val lVal = left[rowOffset + uLeft + dx].toInt() and 0xFF
                    cost += abs(rVal - lVal)
                }
            }

            if (cost < bestCost) {
                bestCost = cost
                bestDisp = d
            }
        }

        return if (bestDisp >= MIN_DISPARITY) bestDisp.toFloat() else null
    }

    /**
     * SAD 비용 계산: 좌안 (u,v) vs 우안 (u-d, v).
     */
    private fun computeSAD(left: ByteArray, right: ByteArray, u: Int, v: Int, d: Int): Int {
        val uRight = u - d
        if (uRight - HALF_WIN < 0) return Int.MAX_VALUE

        var cost = 0
        for (dy in -HALF_WIN..HALF_WIN) {
            val rowOffset = (v + dy) * width
            for (dx in -HALF_WIN..HALF_WIN) {
                val lVal = left[rowOffset + u + dx].toInt() and 0xFF
                val rVal = right[rowOffset + uRight + dx].toInt() and 0xFF
                cost += abs(lVal - rVal)
            }
        }
        return cost
    }

    /**
     * 서브픽셀 보간 (포물선 피팅).
     *
     * 최적 디스패리티 d와 인접 d-1, d+1의 SAD 비용으로
     * 포물선 최솟값 위치를 계산하여 서브픽셀 정밀도 달성.
     *
     * ```
     * subpixel_offset = (C_minus - C_plus) / (2 * (C_minus - 2*C_center + C_plus))
     * ```
     */
    private fun subpixelRefine(
        left: ByteArray, right: ByteArray,
        u: Int, v: Int,
        bestDisp: Int, maxSearchD: Int
    ): Float {
        if (bestDisp <= MIN_DISPARITY || bestDisp >= maxSearchD) {
            return bestDisp.toFloat()
        }

        val cMinus = computeSAD(left, right, u, v, bestDisp - 1).toFloat()
        val cCenter = computeSAD(left, right, u, v, bestDisp).toFloat()
        val cPlus = computeSAD(left, right, u, v, bestDisp + 1).toFloat()

        val denominator = 2f * (cMinus - 2f * cCenter + cPlus)
        if (abs(denominator) < 1e-6f) return bestDisp.toFloat()

        val offset = (cMinus - cPlus) / denominator
        val refinedDisp = bestDisp + offset.coerceIn(-0.5f, 0.5f)

        return max(MIN_DISPARITY.toFloat(), refinedDisp)
    }

    override fun getLatestDisparityMap(): FloatArray? = null  // 희소 매칭이므로 전체 맵 없음

    override fun release() {
        leftFrame = null
        rightFrame = null
        Log.i(TAG, "SparseSADDepth released")
    }
}
