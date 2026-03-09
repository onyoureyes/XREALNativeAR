package com.xreal.nativear.hand

import android.graphics.RectF

/**
 * HandLandmark — 손 관절 키포인트 (21개, MediaPipe 규격).
 */
data class HandLandmark(
    val x: Float,
    val y: Float,
    val z: Float = 0f
)

/**
 * HandData — 감지된 손 하나의 전체 정보.
 */
data class HandData(
    val landmarks: List<HandLandmark>,
    val isRightHand: Boolean,
    val confidence: Float,
    val boundingBox: RectF
) {
    companion object {
        const val WRIST = 0
        const val THUMB_CMC = 1
        const val THUMB_MCP = 2
        const val THUMB_IP = 3
        const val THUMB_TIP = 4
        const val INDEX_MCP = 5
        const val INDEX_PIP = 6
        const val INDEX_DIP = 7
        const val INDEX_TIP = 8
        const val MIDDLE_MCP = 9
        const val MIDDLE_PIP = 10
        const val MIDDLE_DIP = 11
        const val MIDDLE_TIP = 12
        const val RING_MCP = 13
        const val RING_PIP = 14
        const val RING_DIP = 15
        const val RING_TIP = 16
        const val PINKY_MCP = 17
        const val PINKY_PIP = 18
        const val PINKY_DIP = 19
        const val PINKY_TIP = 20
    }

    /** 검지 끝 위치 (퍼센트 좌표 0~100) */
    val indexTipPercent: Pair<Float, Float>
        get() = if (landmarks.size > INDEX_TIP) {
            landmarks[INDEX_TIP].x * 100f to landmarks[INDEX_TIP].y * 100f
        } else 50f to 50f

    /** 엄지 끝 위치 */
    val thumbTipPercent: Pair<Float, Float>
        get() = if (landmarks.size > THUMB_TIP) {
            landmarks[THUMB_TIP].x * 100f to landmarks[THUMB_TIP].y * 100f
        } else 50f to 50f

    /** 손바닥 중심 (MCP 관절들의 평균) */
    val palmCenter: Pair<Float, Float>
        get() {
            if (landmarks.size < 21) return 50f to 50f
            val mcps = listOf(WRIST, INDEX_MCP, MIDDLE_MCP, RING_MCP, PINKY_MCP)
            val cx = mcps.map { landmarks[it].x }.average().toFloat() * 100f
            val cy = mcps.map { landmarks[it].y }.average().toFloat() * 100f
            return cx to cy
        }
}

/**
 * HandGestureType — 인식 가능한 손 제스처.
 */
enum class HandGestureType {
    NONE, POINT, PINCH, PINCH_MOVE, TAP, FIST, OPEN_PALM,
    PEACE, THUMBS_UP, SWIPE_LEFT, SWIPE_RIGHT, SWIPE_UP, SWIPE_DOWN, DRAW
}

/**
 * GestureEvent — 제스처 인식 결과 이벤트 데이터.
 */
data class GestureEvent(
    val gesture: HandGestureType,
    val handIndex: Int = 0,
    val confidence: Float = 1.0f,
    val screenX: Float = 50f,
    val screenY: Float = 50f,
    val velocityX: Float = 0f,
    val velocityY: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * PalmDetection — 손바닥 감지 결과 (내부용).
 */
data class PalmDetection(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val score: Float,
    val keypoints: List<Pair<Float, Float>>
)
