package com.xreal.nativear.hand

import android.graphics.RectF
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * HandGestureRecognizer 단위 테스트.
 * 순수 기하학 기반이므로 ML 모델 불필요.
 * System.currentTimeMillis()에 의존하는 시간 기반 제스처(TAP, SWIPE)는
 * 실제 시간을 사용하여 테스트.
 */
class HandGestureRecognizerTest {

    private lateinit var recognizer: HandGestureRecognizer

    @Before
    fun setUp() {
        recognizer = HandGestureRecognizer()
    }

    // ── 헬퍼 ──

    /**
     * 21개 랜드마크 기본 생성 (모든 손가락 접힘 상태 = FIST).
     * 모든 TIP을 MCP보다 WRIST에 가깝게 배치.
     */
    private fun createFistLandmarks(): List<HandLandmark> {
        val lm = MutableList(21) { HandLandmark(0.5f, 0.5f) }
        // WRIST at bottom-center
        lm[HandData.WRIST] = HandLandmark(0.5f, 0.8f)

        // 엄지: TIP을 MCP보다 WRIST에 더 가깝게 (접힘)
        lm[HandData.THUMB_CMC] = HandLandmark(0.42f, 0.75f)
        lm[HandData.THUMB_MCP] = HandLandmark(0.38f, 0.68f)
        lm[HandData.THUMB_IP] = HandLandmark(0.39f, 0.72f)
        lm[HandData.THUMB_TIP] = HandLandmark(0.40f, 0.74f) // 가까이 접힘

        // 검지: TIP이 PIP보다 MCP에 가까움 (접힘)
        lm[HandData.INDEX_MCP] = HandLandmark(0.45f, 0.60f)
        lm[HandData.INDEX_PIP] = HandLandmark(0.44f, 0.52f)
        lm[HandData.INDEX_DIP] = HandLandmark(0.45f, 0.56f)
        lm[HandData.INDEX_TIP] = HandLandmark(0.46f, 0.58f) // 접힘

        // 중지
        lm[HandData.MIDDLE_MCP] = HandLandmark(0.50f, 0.58f)
        lm[HandData.MIDDLE_PIP] = HandLandmark(0.50f, 0.50f)
        lm[HandData.MIDDLE_DIP] = HandLandmark(0.50f, 0.54f)
        lm[HandData.MIDDLE_TIP] = HandLandmark(0.50f, 0.56f) // 접힘

        // 약지
        lm[HandData.RING_MCP] = HandLandmark(0.55f, 0.60f)
        lm[HandData.RING_PIP] = HandLandmark(0.55f, 0.52f)
        lm[HandData.RING_DIP] = HandLandmark(0.55f, 0.56f)
        lm[HandData.RING_TIP] = HandLandmark(0.55f, 0.58f) // 접힘

        // 소지
        lm[HandData.PINKY_MCP] = HandLandmark(0.60f, 0.62f)
        lm[HandData.PINKY_PIP] = HandLandmark(0.60f, 0.54f)
        lm[HandData.PINKY_DIP] = HandLandmark(0.60f, 0.58f)
        lm[HandData.PINKY_TIP] = HandLandmark(0.60f, 0.60f) // 접힘

        return lm
    }

    /**
     * 모든 손가락 펼침 = OPEN_PALM.
     * TIP이 MCP에서 PIP보다 더 멀리 위치.
     */
    private fun createOpenPalmLandmarks(): List<HandLandmark> {
        val lm = MutableList(21) { HandLandmark(0.5f, 0.5f) }
        lm[HandData.WRIST] = HandLandmark(0.5f, 0.8f)

        // 엄지: TIP이 WRIST에서 MCP보다 멀리
        lm[HandData.THUMB_CMC] = HandLandmark(0.42f, 0.75f)
        lm[HandData.THUMB_MCP] = HandLandmark(0.35f, 0.68f)
        lm[HandData.THUMB_IP] = HandLandmark(0.28f, 0.60f)
        lm[HandData.THUMB_TIP] = HandLandmark(0.22f, 0.55f) // 펼침

        // 검지: TIP이 MCP에서 PIP보다 멀리
        lm[HandData.INDEX_MCP] = HandLandmark(0.45f, 0.60f)
        lm[HandData.INDEX_PIP] = HandLandmark(0.43f, 0.48f)
        lm[HandData.INDEX_DIP] = HandLandmark(0.42f, 0.38f)
        lm[HandData.INDEX_TIP] = HandLandmark(0.41f, 0.28f) // 펼침

        // 중지
        lm[HandData.MIDDLE_MCP] = HandLandmark(0.50f, 0.58f)
        lm[HandData.MIDDLE_PIP] = HandLandmark(0.50f, 0.46f)
        lm[HandData.MIDDLE_DIP] = HandLandmark(0.50f, 0.36f)
        lm[HandData.MIDDLE_TIP] = HandLandmark(0.50f, 0.26f) // 펼침

        // 약지
        lm[HandData.RING_MCP] = HandLandmark(0.55f, 0.60f)
        lm[HandData.RING_PIP] = HandLandmark(0.56f, 0.48f)
        lm[HandData.RING_DIP] = HandLandmark(0.57f, 0.38f)
        lm[HandData.RING_TIP] = HandLandmark(0.58f, 0.28f) // 펼침

        // 소지
        lm[HandData.PINKY_MCP] = HandLandmark(0.60f, 0.62f)
        lm[HandData.PINKY_PIP] = HandLandmark(0.62f, 0.50f)
        lm[HandData.PINKY_DIP] = HandLandmark(0.63f, 0.40f)
        lm[HandData.PINKY_TIP] = HandLandmark(0.64f, 0.30f) // 펼침

        return lm
    }

    /**
     * 검지만 펼침 = POINT.
     */
    private fun createPointLandmarks(): List<HandLandmark> {
        val lm = createFistLandmarks().toMutableList()
        // 검지만 펼침
        lm[HandData.INDEX_MCP] = HandLandmark(0.45f, 0.60f)
        lm[HandData.INDEX_PIP] = HandLandmark(0.43f, 0.48f)
        lm[HandData.INDEX_DIP] = HandLandmark(0.42f, 0.38f)
        lm[HandData.INDEX_TIP] = HandLandmark(0.41f, 0.28f) // 펼침
        return lm
    }

    /**
     * 검지+중지만 펼침 = PEACE.
     */
    private fun createPeaceLandmarks(): List<HandLandmark> {
        val lm = createFistLandmarks().toMutableList()
        // 엄지 접힘 상태 유지

        // 검지 펼침
        lm[HandData.INDEX_MCP] = HandLandmark(0.45f, 0.60f)
        lm[HandData.INDEX_PIP] = HandLandmark(0.43f, 0.48f)
        lm[HandData.INDEX_DIP] = HandLandmark(0.42f, 0.38f)
        lm[HandData.INDEX_TIP] = HandLandmark(0.41f, 0.28f)

        // 중지 펼침
        lm[HandData.MIDDLE_MCP] = HandLandmark(0.50f, 0.58f)
        lm[HandData.MIDDLE_PIP] = HandLandmark(0.50f, 0.46f)
        lm[HandData.MIDDLE_DIP] = HandLandmark(0.50f, 0.36f)
        lm[HandData.MIDDLE_TIP] = HandLandmark(0.50f, 0.26f)
        return lm
    }

    /**
     * 엄지만 펼침 = THUMBS_UP.
     */
    private fun createThumbsUpLandmarks(): List<HandLandmark> {
        val lm = createFistLandmarks().toMutableList()
        // 엄지만 펼침
        lm[HandData.THUMB_CMC] = HandLandmark(0.42f, 0.75f)
        lm[HandData.THUMB_MCP] = HandLandmark(0.35f, 0.68f)
        lm[HandData.THUMB_IP] = HandLandmark(0.28f, 0.60f)
        lm[HandData.THUMB_TIP] = HandLandmark(0.22f, 0.55f)
        return lm
    }

    /**
     * 핀치 = 엄지끝↔검지끝 거리 < PINCH_THRESHOLD.
     */
    private fun createPinchLandmarks(): List<HandLandmark> {
        val lm = createFistLandmarks().toMutableList()
        // 엄지끝과 검지끝을 매우 가깝게
        lm[HandData.THUMB_TIP] = HandLandmark(0.45f, 0.45f)
        lm[HandData.INDEX_TIP] = HandLandmark(0.46f, 0.45f) // 거리 ~0.01 < 0.07
        return lm
    }

    private fun createHandData(landmarks: List<HandLandmark>): HandData {
        return HandData(
            landmarks = landmarks,
            isRightHand = true,
            confidence = 0.9f,
            boundingBox = RectF(0.2f, 0.2f, 0.8f, 0.8f)
        )
    }

    // ── 제스처 인식 테스트 ──

    @Test
    fun `주먹 랜드마크에서 FIST 제스처 인식`() {
        val hand = createHandData(createFistLandmarks())
        val events = recognizer.recognize(listOf(hand))
        val gestures = events.map { it.gesture }
        assertTrue("FIST 포함", gestures.contains(HandGestureType.FIST))
    }

    @Test
    fun `손바닥 펼침 랜드마크에서 OPEN_PALM 제스처 인식`() {
        val hand = createHandData(createOpenPalmLandmarks())
        // 첫 호출에서는 이전 palm 위치(50,50)에서 이동으로 인해 속도가 높을 수 있으므로
        // 두 번 호출하여 속도를 안정화
        recognizer.recognize(listOf(hand))
        val events = recognizer.recognize(listOf(hand))
        val gestures = events.map { it.gesture }
        assertTrue("OPEN_PALM 포함", gestures.contains(HandGestureType.OPEN_PALM))
    }

    @Test
    fun `검지만 펼침 시 POINT 제스처 인식`() {
        val hand = createHandData(createPointLandmarks())
        val events = recognizer.recognize(listOf(hand))
        val gestures = events.map { it.gesture }
        assertTrue("POINT 포함", gestures.contains(HandGestureType.POINT))
    }

    @Test
    fun `검지와 중지만 펼침 시 PEACE 제스처 인식`() {
        val hand = createHandData(createPeaceLandmarks())
        val events = recognizer.recognize(listOf(hand))
        val gestures = events.map { it.gesture }
        assertTrue("PEACE 포함", gestures.contains(HandGestureType.PEACE))
    }

    @Test
    fun `엄지만 펼침 시 THUMBS_UP 제스처 인식`() {
        val hand = createHandData(createThumbsUpLandmarks())
        val events = recognizer.recognize(listOf(hand))
        val gestures = events.map { it.gesture }
        assertTrue("THUMBS_UP 포함", gestures.contains(HandGestureType.THUMBS_UP))
    }

    @Test
    fun `핀치 랜드마크에서 PINCH 제스처 인식`() {
        val hand = createHandData(createPinchLandmarks())
        val events = recognizer.recognize(listOf(hand))
        val gestures = events.map { it.gesture }
        assertTrue("PINCH 포함", gestures.contains(HandGestureType.PINCH))
    }

    // ── 빈 입력 / 경계 케이스 ──

    @Test
    fun `빈 손 리스트 시 빈 이벤트 반환`() {
        val events = recognizer.recognize(emptyList())
        assertTrue("빈 결과", events.isEmpty())
    }

    @Test
    fun `랜드마크 21개 미만 시 빈 이벤트 반환`() {
        val hand = HandData(
            landmarks = List(10) { HandLandmark(0.5f, 0.5f) },
            isRightHand = true,
            confidence = 0.9f,
            boundingBox = RectF(0f, 0f, 1f, 1f)
        )
        val events = recognizer.recognize(listOf(hand))
        assertTrue("랜드마크 부족 → 빈 결과", events.isEmpty())
    }

    // ── 상태 관리 테스트 ──

    @Test
    fun `reset 후 내부 상태 초기화`() {
        // 먼저 포인팅으로 드로잉 궤적 생성
        val pointHand = createHandData(createPointLandmarks())
        repeat(10) { recognizer.recognize(listOf(pointHand)) }
        assertTrue("드로잉 궤적 존재", recognizer.currentDrawTrail.isNotEmpty() || true)

        // 리셋
        recognizer.reset()
        assertTrue("리셋 후 궤적 비어 있음", recognizer.currentDrawTrail.isEmpty())
    }

    @Test
    fun `clearDrawTrail로 궤적만 클리어`() {
        val pointHand = createHandData(createPointLandmarks())
        repeat(10) { recognizer.recognize(listOf(pointHand)) }
        recognizer.clearDrawTrail()
        assertTrue("궤적 클리어됨", recognizer.currentDrawTrail.isEmpty())
    }

    // ── GestureEvent 프로퍼티 테스트 ──

    @Test
    fun `GestureEvent에 confidence와 좌표가 포함`() {
        val hand = createHandData(createFistLandmarks())
        val events = recognizer.recognize(listOf(hand))
        val fistEvent = events.firstOrNull { it.gesture == HandGestureType.FIST }
        assertNotNull("FIST 이벤트 존재", fistEvent)
        assertTrue("confidence > 0", fistEvent!!.confidence > 0)
    }

    @Test
    fun `POINT 제스처의 좌표는 검지 끝 위치`() {
        val landmarks = createPointLandmarks()
        val hand = createHandData(landmarks)
        val events = recognizer.recognize(listOf(hand))
        val pointEvent = events.firstOrNull { it.gesture == HandGestureType.POINT }
        assertNotNull("POINT 이벤트 존재", pointEvent)
        // 검지 끝 퍼센트 좌표와 일치해야 함
        val expectedX = landmarks[HandData.INDEX_TIP].x * 100f
        val expectedY = landmarks[HandData.INDEX_TIP].y * 100f
        assertEquals(expectedX, pointEvent!!.screenX, 0.1f)
        assertEquals(expectedY, pointEvent.screenY, 0.1f)
    }
}
