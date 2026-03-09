package com.xreal.nativear.spatial

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DriftCorrection — VIO 드리프트 보정 데이터 타입 + 유틸리티.
 *
 * ## 드리프트 보정 3축
 * | 축 | 소스 | 보정 방법 |
 * |---|------|---------|
 * | Y (수직) | 기압계 FloorChange | 기압 층수 vs VIO Y 비교 → 오프셋 |
 * | Yaw (방위) | TYPE_ROTATION_VECTOR | 자기 방위 vs VIO yaw 비교 → 쿼터니언 보정 |
 * | X,Z (수평) | 시각 임베딩 루프 클로저 | 키프레임 재방문 감지 → 위치 오프셋 |
 *
 * ## 보정 적용 지점
 * HardwareManager.vioPoseListener 내부에서 HeadPoseUpdated 발행 전에 적용.
 * 모든 하류 소비자 (SpatialAnchorManager, PathTracker 등)가 보정된 포즈를 받음.
 *
 * @see DriftCorrectionManager 보정 관리자
 * @see MagneticHeadingProvider 자기 방위 제공자
 * @see VisualLoopCloser 시각 루프 클로저
 */

/**
 * CorrectedPose — 드리프트 보정이 적용된 VIO 포즈.
 */
data class CorrectedPose(
    val x: Float,
    val y: Float,
    val z: Float,
    val qx: Float,
    val qy: Float,
    val qz: Float,
    val qw: Float
)

/**
 * DriftCorrectionState — 현재 드리프트 보정 상태.
 *
 * 누적 오프셋을 추적. applyCorrection() 시 원본 VIO 포즈에 가산.
 * 보정은 점진적으로 적용 (갑작스러운 점프 방지).
 */
data class DriftCorrectionState(
    // ── 위치 보정 오프셋 (미터, VIO 좌표 가산) ──
    var offsetX: Float = 0f,
    var offsetY: Float = 0f,       // Y = 수직 (기압계 보정)
    var offsetZ: Float = 0f,

    // ── Yaw 보정 (라디안, 쿼터니언에 적용) ──
    var yawCorrectionRad: Float = 0f,

    // ── 타임스탬프 ──
    var lastBaroCorrection: Long = 0,
    var lastYawCorrection: Long = 0,
    var lastLoopClosureCorrection: Long = 0,

    // ── 통계 ──
    var baroCorrectionsApplied: Int = 0,
    var yawCorrectionsApplied: Int = 0,
    var loopClosuresDetected: Int = 0,

    var totalBaroDriftCorrected: Float = 0f,    // 누적 Y축 보정량 (미터)
    var totalYawDriftCorrected: Float = 0f,     // 누적 Yaw 보정량 (도)
    var totalXZDriftCorrected: Float = 0f       // 누적 XZ 보정량 (미터)
)

/**
 * VisualKeyframe — 루프 클로저용 시각 키프레임.
 *
 * 10초마다 저장: VIO 포즈 + 시각 임베딩 + (선택) SLAM 썸네일.
 * 재방문 감지 시 저장된 포즈와 현재 포즈 비교 → X,Z 드리프트 추정.
 *
 * @param embedding MobileNetV3 1280-dim L2-normalized 임베딩
 * @param rawVioX 보정 전 원본 VIO 좌표 (루프 클로저 참조용)
 * @param rawVioY 보정 전 원본 VIO Y
 * @param rawVioZ 보정 전 원본 VIO Z
 * @param correctedVioX 보정 후 VIO X (실제 위치 추정)
 * @param correctedVioY 보정 후 VIO Y
 * @param correctedVioZ 보정 후 VIO Z
 * @param headingDegrees 방위각 (0-360°)
 * @param timestamp 생성 시각
 * @param slamThumbnail 압축된 SLAM 좌안 썸네일 (80×60, JPEG, ~2-3KB)
 */
data class VisualKeyframe(
    val embedding: ByteArray,
    val rawVioX: Float,
    val rawVioY: Float,
    val rawVioZ: Float,
    val correctedVioX: Float,
    val correctedVioY: Float,
    val correctedVioZ: Float,
    val headingDegrees: Float,
    val timestamp: Long,
    val slamThumbnail: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VisualKeyframe) return false
        return timestamp == other.timestamp
    }

    override fun hashCode(): Int = timestamp.hashCode()
}

/**
 * DriftStats — 드리프트 보정 시스템 현재 상태 요약 (디버그/HUD용).
 */
data class DriftStats(
    val baroYDrift: Float,          // 기압계 감지 Y축 드리프트 (미터)
    val yawDrift: Float,            // 자기 감지 Yaw 드리프트 (도)
    val totalCorrections: Int,      // 총 보정 횟수
    val keyframeCount: Int,         // 저장된 키프레임 수
    val loopClosuresDetected: Int,  // 루프 클로저 감지 횟수
    val isBaroActive: Boolean,      // 기압 보정 활성 여부
    val isMagActive: Boolean,       // 자기 보정 활성 여부
    val isLoopClosureActive: Boolean // 루프 클로저 활성 여부
)

/**
 * 쿼터니언 유틸리티 — Y축 회전 보정 + Yaw 추출.
 */
object QuaternionUtils {

    /**
     * 쿼터니언에 Y축 yaw 보정 적용.
     *
     * q_corrected = q_yaw × q_original
     * q_yaw = (qx=0, qy=sin(δ/2), qz=0, qw=cos(δ/2))
     *
     * @param qx,qy,qz,qw 원본 Hamilton 쿼터니언
     * @param yawRad Y축 회전 보정량 (라디안)
     * @return 보정된 (qx, qy, qz, qw)
     */
    fun applyYawCorrection(
        qx: Float, qy: Float, qz: Float, qw: Float,
        yawRad: Float
    ): FloatArray {
        if (yawRad == 0f) return floatArrayOf(qx, qy, qz, qw)

        val halfAngle = yawRad / 2f
        val cy = cos(halfAngle)
        val sy = sin(halfAngle)

        // q_yaw = (0, sy, 0, cy) in (x, y, z, w) format
        // Hamilton multiplication: q_yaw × q_original
        val rw = cy * qw - sy * qy
        val rx = cy * qx + sy * qz
        val ry = cy * qy + sy * qw
        val rz = cy * qz - sy * qx

        // Normalize
        val len = sqrt(rx * rx + ry * ry + rz * rz + rw * rw)
        return if (len > 0.001f) {
            floatArrayOf(rx / len, ry / len, rz / len, rw / len)
        } else {
            floatArrayOf(qx, qy, qz, qw) // fallback
        }
    }

    /**
     * 쿼터니언에서 Yaw (Y축 회전, 방위각) 추출.
     *
     * Y-up 좌표계 기준. PathTracker와 동일한 공식.
     * @return 0-360° (0=초기 방위)
     */
    fun extractYawDegrees(qx: Float, qy: Float, qz: Float, qw: Float): Float {
        val yaw = kotlin.math.atan2(
            2f * (qw * qy + qx * qz),
            1f - 2f * (qy * qy + qz * qz)
        )
        val degrees = Math.toDegrees(yaw.toDouble()).toFloat()
        return ((degrees % 360f) + 360f) % 360f
    }

    /**
     * 두 각도(도) 차이 계산 (-180 ~ +180 범위).
     */
    fun angleDifference(a: Float, b: Float): Float {
        var diff = a - b
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        return diff
    }
}
