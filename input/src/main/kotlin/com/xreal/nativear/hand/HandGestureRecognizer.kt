package com.xreal.nativear.hand

import com.xreal.nativear.core.XRealLogger
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * HandGestureRecognizer — 21개 손 관절 랜드마크 → 제스처 분류.
 *
 * ## 순수 기하학 기반 (ML 모델 불필요)
 * 손가락 펼침/접힘, 거리, 각도, 이동 속도로 제스처 판별.
 *
 * ## 지원 제스처
 * | 제스처 | 조건 |
 * |--------|------|
 * | POINT | 검지만 펼침 |
 * | PINCH | 엄지↔검지 거리 < 임계값 |
 * | TAP | 핀치 → 300ms 이내 릴리즈 |
 * | FIST | 모든 손가락 접힘 |
 * | OPEN_PALM | 모든 손가락 펼침 |
 * | PEACE | 검지+중지만 펼침 |
 * | THUMBS_UP | 엄지만 펼침 |
 * | SWIPE_* | 손바닥 이동 속도 > 임계값 |
 * | DRAW | POINT 유지 + 검지 이동 |
 * | PINCH_MOVE | PINCH 유지 + 이동 |
 *
 * ## 시간 기반 제스처 (TAP, SWIPE, DRAW)
 * 연속 프레임 분석 필요 → 내부 상태 관리.
 *
 * @param log 로깅 콜백
 */
class HandGestureRecognizer(
    private val log: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "HandGesture"

        /** 핀치 거리 임계값 (검지끝↔엄지끝, 랜드마크 정규화 좌표) */
        const val PINCH_THRESHOLD = 0.07f

        /** 손가락 "펼침" 판별: 끝관절이 MCP보다 멀리 있으면 펼침 */
        const val FINGER_EXTENDED_THRESHOLD = 0.02f

        /** 스와이프 속도 임계값 (퍼센트/초) */
        const val SWIPE_VELOCITY_THRESHOLD = 80f

        /** 탭 최대 지속 시간 (ms) */
        const val TAP_MAX_DURATION_MS = 300L

        /** 탭 최소 지속 시간 (ms) — 너무 짧으면 노이즈 */
        const val TAP_MIN_DURATION_MS = 50L

        /** 드로잉 최소 프레임 수 */
        const val DRAW_MIN_FRAMES = 5

        /** 제스처 쿨다운 (같은 제스처 연속 발생 방지) */
        const val GESTURE_COOLDOWN_MS = 300L
    }

    // ── 상태 ──

    /** 이전 프레임 손바닥 중심 (퍼센트 좌표) */
    private var prevPalmX = 50f
    private var prevPalmY = 50f
    private var prevTimestamp = 0L

    /** 핀치 상태 추적 */
    private var pinchStartTime = 0L
    private var isPinching = false
    private var pinchStartX = 0f
    private var pinchStartY = 0f

    /** 포인팅 상태 추적 (드로잉용) */
    private var pointingFrameCount = 0
    private var lastPointX = 0f
    private var lastPointY = 0f

    /** 제스처 쿨다운 */
    private var lastGestureType = HandGestureType.NONE
    private var lastGestureTime = 0L

    /** 드로잉 궤적 */
    private val drawTrail = mutableListOf<Pair<Float, Float>>()
    val currentDrawTrail: List<Pair<Float, Float>> get() = drawTrail.toList()

    // ── Public API ──

    /**
     * 손 데이터 → 제스처 이벤트 리스트.
     *
     * 매 프레임 호출. 여러 제스처가 동시에 감지될 수 있음 (예: PINCH + PINCH_MOVE).
     *
     * @param hands 감지된 손 목록
     * @return 감지된 제스처 이벤트 리스트
     */
    fun recognize(hands: List<HandData>): List<GestureEvent> {
        val now = System.currentTimeMillis()
        if (hands.isEmpty()) {
            onHandsLost(now)
            return emptyList()
        }

        val events = mutableListOf<GestureEvent>()
        val hand = hands[0]  // 첫 번째 손 기준
        val lm = hand.landmarks
        if (lm.size < 21) return emptyList()

        // 손가락 펼침 상태
        val thumbExtended = isThumbExtended(lm)
        val indexExtended = isFingerExtended(lm, HandData.INDEX_MCP, HandData.INDEX_PIP, HandData.INDEX_DIP, HandData.INDEX_TIP)
        val middleExtended = isFingerExtended(lm, HandData.MIDDLE_MCP, HandData.MIDDLE_PIP, HandData.MIDDLE_DIP, HandData.MIDDLE_TIP)
        val ringExtended = isFingerExtended(lm, HandData.RING_MCP, HandData.RING_PIP, HandData.RING_DIP, HandData.RING_TIP)
        val pinkyExtended = isFingerExtended(lm, HandData.PINKY_MCP, HandData.PINKY_PIP, HandData.PINKY_DIP, HandData.PINKY_TIP)
        val extendedCount = listOf(thumbExtended, indexExtended, middleExtended, ringExtended, pinkyExtended).count { it }

        // 핀치 거리 (엄지끝↔검지끝)
        val pinchDist = distance2D(lm[HandData.THUMB_TIP], lm[HandData.INDEX_TIP])
        val isPinchNow = pinchDist < PINCH_THRESHOLD

        // 손바닥 중심 (퍼센트 좌표)
        val (palmX, palmY) = hand.palmCenter
        val (indexX, indexY) = hand.indexTipPercent

        // 이동 속도 계산
        val dt = if (prevTimestamp > 0) (now - prevTimestamp).coerceAtLeast(1) / 1000f else 0.033f
        val velocityX = (palmX - prevPalmX) / dt
        val velocityY = (palmY - prevPalmY) / dt
        val speed = sqrt(velocityX * velocityX + velocityY * velocityY)

        // ── 제스처 분류 ──

        // 1. 핀치 관련 (TAP, PINCH, PINCH_MOVE)
        if (isPinchNow) {
            if (!isPinching) {
                // 핀치 시작
                isPinching = true
                pinchStartTime = now
                pinchStartX = palmX
                pinchStartY = palmY
            }
            val pinchMoveDistance = distance2DPct(palmX, palmY, pinchStartX, pinchStartY)
            if (pinchMoveDistance > 3f) {
                events.add(GestureEvent(
                    gesture = HandGestureType.PINCH_MOVE,
                    screenX = indexX, screenY = indexY,
                    velocityX = velocityX, velocityY = velocityY,
                    confidence = hand.confidence,
                    timestamp = now
                ))
            } else {
                events.add(GestureEvent(
                    gesture = HandGestureType.PINCH,
                    screenX = indexX, screenY = indexY,
                    confidence = hand.confidence,
                    timestamp = now
                ))
            }
        } else if (isPinching) {
            // 핀치 해제 → TAP 판정
            val pinchDuration = now - pinchStartTime
            isPinching = false
            if (pinchDuration in TAP_MIN_DURATION_MS..TAP_MAX_DURATION_MS) {
                events.add(GestureEvent(
                    gesture = HandGestureType.TAP,
                    screenX = indexX, screenY = indexY,
                    confidence = hand.confidence,
                    timestamp = now
                ))
            }
        }

        // 2. 스와이프 (손바닥 빠른 이동)
        if (speed > SWIPE_VELOCITY_THRESHOLD && extendedCount >= 4) {
            val swipeType = when {
                abs(velocityX) > abs(velocityY) && velocityX > 0 -> HandGestureType.SWIPE_RIGHT
                abs(velocityX) > abs(velocityY) && velocityX < 0 -> HandGestureType.SWIPE_LEFT
                velocityY < 0 -> HandGestureType.SWIPE_UP
                else -> HandGestureType.SWIPE_DOWN
            }
            if (canEmitGesture(swipeType, now)) {
                events.add(GestureEvent(
                    gesture = swipeType,
                    screenX = palmX, screenY = palmY,
                    velocityX = velocityX, velocityY = velocityY,
                    confidence = hand.confidence,
                    timestamp = now
                ))
                lastGestureType = swipeType
                lastGestureTime = now
            }
        }

        // 3. 포인트 (검지만 펼침)
        if (!isPinchNow && indexExtended && !middleExtended && !ringExtended && !pinkyExtended) {
            pointingFrameCount++
            events.add(GestureEvent(
                gesture = HandGestureType.POINT,
                screenX = indexX, screenY = indexY,
                confidence = hand.confidence,
                timestamp = now
            ))

            // 포인트 지속 → DRAW (궤적 추적)
            if (pointingFrameCount >= DRAW_MIN_FRAMES) {
                drawTrail.add(indexX to indexY)
                if (drawTrail.size > 200) drawTrail.removeAt(0)  // 최대 200 포인트
                events.add(GestureEvent(
                    gesture = HandGestureType.DRAW,
                    screenX = indexX, screenY = indexY,
                    confidence = hand.confidence,
                    timestamp = now
                ))
            }
            lastPointX = indexX
            lastPointY = indexY
        } else {
            if (pointingFrameCount > 0) {
                // 포인팅 끝 → 드로잉 궤적 유지 (3초 후 자동 클리어)
                pointingFrameCount = 0
            }
        }

        // 4. 주먹 (모든 손가락 접힘)
        if (extendedCount == 0) {
            events.add(GestureEvent(
                gesture = HandGestureType.FIST,
                screenX = palmX, screenY = palmY,
                confidence = hand.confidence,
                timestamp = now
            ))
        }

        // 5. 손바닥 활짝 (모든 손가락 펼침, 핀치 아님, 빠른 이동 아님)
        if (extendedCount >= 5 && !isPinchNow && speed < SWIPE_VELOCITY_THRESHOLD / 2) {
            events.add(GestureEvent(
                gesture = HandGestureType.OPEN_PALM,
                screenX = palmX, screenY = palmY,
                confidence = hand.confidence,
                timestamp = now
            ))
        }

        // 6. 피스 (검지+중지만 펼침)
        if (indexExtended && middleExtended && !ringExtended && !pinkyExtended && !thumbExtended) {
            events.add(GestureEvent(
                gesture = HandGestureType.PEACE,
                screenX = palmX, screenY = palmY,
                confidence = hand.confidence,
                timestamp = now
            ))
        }

        // 7. 엄지 업 (엄지만 펼침)
        if (thumbExtended && !indexExtended && !middleExtended && !ringExtended && !pinkyExtended) {
            events.add(GestureEvent(
                gesture = HandGestureType.THUMBS_UP,
                screenX = palmX, screenY = palmY,
                confidence = hand.confidence,
                timestamp = now
            ))
        }

        // 상태 업데이트
        prevPalmX = palmX
        prevPalmY = palmY
        prevTimestamp = now

        return events
    }

    /** 드로잉 궤적 클리어 */
    fun clearDrawTrail() {
        drawTrail.clear()
        pointingFrameCount = 0
    }

    /** 상태 리셋 */
    fun reset() {
        isPinching = false
        pinchStartTime = 0
        pointingFrameCount = 0
        drawTrail.clear()
        prevTimestamp = 0
        lastGestureType = HandGestureType.NONE
        lastGestureTime = 0
    }

    // ── 내부 로직 ──

    private fun onHandsLost(now: Long) {
        if (isPinching) {
            isPinching = false
        }
        pointingFrameCount = 0
    }

    /**
     * 손가락 펼침 판별.
     * 끝관절(TIP)이 PIP 관절보다 MCP에서 더 멀면 펼침.
     */
    private fun isFingerExtended(
        lm: List<HandLandmark>,
        mcp: Int, pip: Int, dip: Int, tip: Int
    ): Boolean {
        val mcpToTip = distance2D(lm[mcp], lm[tip])
        val mcpToPip = distance2D(lm[mcp], lm[pip])
        return mcpToTip > mcpToPip + FINGER_EXTENDED_THRESHOLD
    }

    /**
     * 엄지 펼침 판별 (특수 처리: 축이 다름).
     * 엄지 끝이 검지 MCP보다 손목에서 더 멀면 펼침.
     */
    private fun isThumbExtended(lm: List<HandLandmark>): Boolean {
        val wristToThumbTip = distance2D(lm[HandData.WRIST], lm[HandData.THUMB_TIP])
        val wristToThumbMCP = distance2D(lm[HandData.WRIST], lm[HandData.THUMB_MCP])
        return wristToThumbTip > wristToThumbMCP + FINGER_EXTENDED_THRESHOLD
    }

    private fun distance2D(a: HandLandmark, b: HandLandmark): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun distance2DPct(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    private fun canEmitGesture(type: HandGestureType, now: Long): Boolean {
        if (type == lastGestureType && now - lastGestureTime < GESTURE_COOLDOWN_MS) return false
        return true
    }
}
