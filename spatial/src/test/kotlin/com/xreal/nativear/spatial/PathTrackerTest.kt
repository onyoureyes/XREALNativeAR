package com.xreal.nativear.spatial

import com.xreal.nativear.core.GlobalEventBus
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * PathTracker 단위 테스트 — 경로 추적 + 경로 비교.
 */
class PathTrackerTest {

    private lateinit var eventBus: GlobalEventBus
    private lateinit var pathTracker: PathTracker
    private val logs = mutableListOf<String>()

    @Before
    fun setup() {
        eventBus = GlobalEventBus()
        pathTracker = PathTracker(eventBus) { logs.add(it) }
    }

    // ── 초기 상태 테스트 ──

    @Test
    fun `초기 상태 - totalPathDistance 0`() {
        assertEquals(0f, pathTracker.totalPathDistance, 0.001f)
    }

    @Test
    fun `초기 상태 - isIndoor false`() {
        assertFalse(pathTracker.isIndoor)
    }

    @Test
    fun `초기 상태 - getCurrentPathState 기본값`() {
        val state = pathTracker.getCurrentPathState()
        assertEquals(0f, state.totalDistance, 0.001f)
        assertEquals(0f, state.vioYDisplacement, 0.001f)
        assertEquals(0, state.estimatedFloor)
        assertFalse(state.isIndoor)
        assertEquals(0, state.segmentCount)
    }

    // ── resetPath 테스트 ──

    @Test
    fun `resetPath - 상태 초기화`() {
        // 수동으로 상태 변경 후 리셋
        pathTracker.resetPath()
        assertEquals(0f, pathTracker.totalPathDistance, 0.001f)
        assertFalse(pathTracker.isIndoor)
        val state = pathTracker.getCurrentPathState()
        assertEquals(0, state.segmentCount)
    }

    // ── PathState 데이터 클래스 테스트 ──

    @Test
    fun `PathState - 필드 접근 정확성`() {
        val state = PathState(
            totalDistance = 25.5f,
            vioYDisplacement = 9.0f,
            estimatedFloor = 3,
            isIndoor = true,
            segmentCount = 5
        )
        assertEquals(25.5f, state.totalDistance, 0.001f)
        assertEquals(9.0f, state.vioYDisplacement, 0.001f)
        assertEquals(3, state.estimatedFloor)
        assertTrue(state.isIndoor)
        assertEquals(5, state.segmentCount)
    }

    // ── comparePathSummaries 테스트 ──

    @Test
    fun `comparePathSummaries - null 입력은 0_5 반환`() {
        assertEquals(0.5f, pathTracker.comparePathSummaries(null, null), 0.001f)
        assertEquals(0.5f, pathTracker.comparePathSummaries("{}", null), 0.001f)
        assertEquals(0.5f, pathTracker.comparePathSummaries(null, "{}"), 0.001f)
    }

    @Test
    fun `comparePathSummaries - 동일 경로는 유사도 1`() {
        val json = """{"totalDistance": 30.0, "netDisplacement": 18.5, "floors": 1}"""
        val score = pathTracker.comparePathSummaries(json, json)
        assertEquals(1.0f, score, 0.01f)
    }

    // NOTE: comparePathSummaries의 JSON 파싱 테스트는 org.json이 Android 프레임워크
    // 클래스여서 JVM 테스트에서 스텁으로 동작함. 정확한 검증은 instrumented 테스트에서 수행.

    @Test
    fun `comparePathSummaries - 결과는 0에서 1 범위`() {
        val json = """{"totalDistance": 30.0, "netDisplacement": 18.5, "floors": 1}"""
        val score = pathTracker.comparePathSummaries(json, json)
        assertTrue("점수 범위 0-1: $score", score in 0f..1f)
    }

    // ── 상수 검증 ──

    @Test
    fun `상수 - GPS_VALID_ACCURACY_M은 30`() {
        assertEquals(30f, PathTracker.GPS_VALID_ACCURACY_M, 0.001f)
    }

    @Test
    fun `상수 - FLOOR_HEIGHT_M은 3`() {
        assertEquals(3.0f, PathTracker.FLOOR_HEIGHT_M, 0.001f)
    }

    @Test
    fun `상수 - SEGMENT_MIN_DISTANCE_M은 2`() {
        assertEquals(2.0f, PathTracker.SEGMENT_MIN_DISTANCE_M, 0.001f)
    }
}
