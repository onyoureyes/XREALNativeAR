package com.xreal.nativear.spatial

import kotlin.math.sqrt

/**
 * CameraModel — 핀홀 카메라 투영/역투영 수학 모델.
 *
 * ## 좌표계
 * - 카메라 좌표: X=오른쪽, Y=아래, Z=앞(깊이) — OpenCV 표준
 * - 이미지 좌표: u=가로(px), v=세로(px)
 *
 * ## 투영 공식
 * ```
 * u = fx * (X/Z) + cx
 * v = fy * (Y/Z) + cy
 * ```
 *
 * ## 역투영 공식
 * ```
 * X = (u - cx) * depth / fx
 * Y = (v - cy) * depth / fy
 * Z = depth
 * ```
 *
 * @param fx 수평 초점 거리 (pixels)
 * @param fy 수직 초점 거리 (pixels)
 * @param cx 주점 x (pixels)
 * @param cy 주점 y (pixels)
 * @param imageWidth 이미지 너비 (pixels)
 * @param imageHeight 이미지 높이 (pixels)
 */
class CameraModel(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val imageWidth: Int,
    val imageHeight: Int
) {
    /**
     * 3D 카메라 좌표 → 2D 이미지 픽셀.
     *
     * @param camX 카메라 로컬 X (오른쪽, 미터)
     * @param camY 카메라 로컬 Y (아래, 미터)
     * @param camZ 카메라 로컬 Z (앞/깊이, 미터)
     * @return (u, v) 이미지 좌표, 또는 null (카메라 뒤에 있을 때)
     */
    fun project(camX: Float, camY: Float, camZ: Float): Pair<Float, Float>? {
        if (camZ <= MIN_DEPTH) return null  // 카메라 뒤 또는 너무 가까움

        val invZ = 1f / camZ
        val u = fx * camX * invZ + cx
        val v = fy * camY * invZ + cy
        return Pair(u, v)
    }

    /**
     * 2D 이미지 픽셀 + 깊이 → 3D 카메라 좌표.
     *
     * @param u 이미지 x (pixels)
     * @param v 이미지 y (pixels)
     * @param depth 깊이 (미터, >0)
     * @return (X, Y, Z) 카메라 로컬 좌표 (미터)
     */
    fun unproject(u: Float, v: Float, depth: Float): FloatArray {
        val x = (u - cx) * depth / fx
        val y = (v - cy) * depth / fy
        val z = depth
        return floatArrayOf(x, y, z)
    }

    /**
     * 3D 카메라 좌표가 화면에 보이는지 판별.
     *
     * 조건: (1) 카메라 앞에 있고 (Z > 0), (2) 투영 좌표가 이미지 범위 내.
     *
     * @param camX 카메라 로컬 X
     * @param camY 카메라 로컬 Y
     * @param camZ 카메라 로컬 Z
     * @param margin 이미지 경계 여유 비율 (0.0 = 정확한 경계, 0.1 = 10% 여유)
     * @return true면 화면에 투영 가능
     */
    fun isVisible(camX: Float, camY: Float, camZ: Float, margin: Float = 0.05f): Boolean {
        val uv = project(camX, camY, camZ) ?: return false
        val (u, v) = uv
        val mx = imageWidth * margin
        val my = imageHeight * margin
        return u >= -mx && u <= imageWidth + mx &&
               v >= -my && v <= imageHeight + my
    }

    /**
     * 이미지 픽셀 좌표 → 화면 퍼센트 좌표 (DrawElement 좌표계, 0-100).
     *
     * @param u 이미지 x (pixels)
     * @param v 이미지 y (pixels)
     * @return (xPercent, yPercent) 0-100 범위
     */
    fun pixelToPercent(u: Float, v: Float): Pair<Float, Float> {
        val xPct = (u / imageWidth) * 100f
        val yPct = (v / imageHeight) * 100f
        return Pair(xPct, yPct)
    }

    /**
     * 카메라 좌표에서 유클리드 거리 (미터).
     */
    fun distanceFromCamera(camX: Float, camY: Float, camZ: Float): Float {
        return sqrt(camX * camX + camY * camY + camZ * camZ)
    }

    override fun toString(): String =
        "CameraModel(fx=$fx, fy=$fy, cx=$cx, cy=$cy, ${imageWidth}x${imageHeight})"

    companion object {
        /** 투영 유효 최소 깊이 (미터) */
        private const val MIN_DEPTH = 0.01f

        /**
         * SLAM 좌안 rectified 카메라.
         *
         * StereoRectifier의 P1 행렬에서 추출:
         * - fx = fy = 350.706615
         * - cx = 320.046310
         * - cy = 255.721750
         * - Image: 640×480
         * - Baseline: 104mm
         */
        fun slamCamera() = CameraModel(
            fx = 350.71f,
            fy = 350.71f,
            cx = 320.05f,
            cy = 255.72f,
            imageWidth = 640,
            imageHeight = 480
        )

        /**
         * RGB 센터 카메라 (캘리브레이션 미완, FOV ~70° 추정).
         *
         * HFOV ≈ 70° → fx = (imageWidth / 2) / tan(HFOV/2) ≈ 914
         * 추후 정밀 캘리브레이션으로 교체 가능.
         *
         * - fx = fy ≈ 914 (추정)
         * - cx = 640 (이미지 중심)
         * - cy = 480 (이미지 중심)
         * - Image: 1280×960
         */
        fun rgbCamera() = CameraModel(
            fx = 914f,
            fy = 914f,
            cx = 640f,
            cy = 480f,
            imageWidth = 1280,
            imageHeight = 960
        )

        /**
         * RGB 이미지 좌표를 SLAM 이미지 좌표로 근사 변환.
         *
         * 두 카메라가 같은 방향을 향하고 FOV가 겹치는 영역에서 유효.
         * 정확한 extrinsic 캘리브레이션 없이 선형 스케일링으로 근사.
         *
         * @param rgbU RGB 이미지 x (0-1280)
         * @param rgbV RGB 이미지 y (0-960)
         * @return (slamU, slamV) SLAM 이미지 좌표 (0-640, 0-480)
         */
        fun rgbToSlamPixel(rgbU: Float, rgbV: Float): Pair<Float, Float> {
            val slamU = rgbU * 640f / 1280f
            val slamV = rgbV * 480f / 960f
            return Pair(slamU, slamV)
        }
    }
}
