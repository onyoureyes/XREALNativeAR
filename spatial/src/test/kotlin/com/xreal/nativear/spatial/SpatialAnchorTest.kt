package com.xreal.nativear.spatial

import org.junit.Assert.*
import org.junit.Test

/**
 * SpatialAnchor + SpatialAnchorRecord + PlaceSignature 데이터 클래스 테스트.
 */
class SpatialAnchorTest {

    // ── SpatialAnchor 테스트 ──

    @Test
    fun `SpatialAnchor - isConfirmed 3회 이상 관측`() {
        val anchor = createTestAnchor(seenCount = 3)
        assertTrue(anchor.isConfirmed)
    }

    @Test
    fun `SpatialAnchor - isConfirmed 미달`() {
        val anchor = createTestAnchor(seenCount = 2)
        assertFalse(anchor.isConfirmed)
    }

    @Test
    fun `SpatialAnchor - expirySeconds 확정 앵커 10분`() {
        val anchor = createTestAnchor(seenCount = 5)
        assertEquals(600L, anchor.expirySeconds)
    }

    @Test
    fun `SpatialAnchor - expirySeconds 미확정 앵커 5분`() {
        val anchor = createTestAnchor(seenCount = 1)
        assertEquals(300L, anchor.expirySeconds)
    }

    // ── DepthSource enum 테스트 ──

    @Test
    fun `DepthSource - 3가지 값 존재`() {
        val values = DepthSource.values()
        assertEquals(3, values.size)
        assertNotNull(DepthSource.valueOf("STEREO_DISPARITY"))
        assertNotNull(DepthSource.valueOf("BBOX_SIZE"))
        assertNotNull(DepthSource.valueOf("CATEGORY_PRIOR"))
    }

    // ── AnchorPersistenceLevel enum 테스트 ──

    @Test
    fun `AnchorPersistenceLevel - 3단계 존재`() {
        val values = AnchorPersistenceLevel.values()
        assertEquals(3, values.size)
        assertNotNull(AnchorPersistenceLevel.valueOf("SESSION_ONLY"))
        assertNotNull(AnchorPersistenceLevel.valueOf("SESSION_WITH_DB"))
        assertNotNull(AnchorPersistenceLevel.valueOf("CROSS_SESSION"))
    }

    // ── PoseState 테스트 ──

    @Test
    fun `PoseState - 데이터 클래스 생성`() {
        val pose = PoseState(1f, 2f, 3f, 0f, 0f, 0f, 1f, is6DoF = true, timestamp = 12345L)
        assertEquals(1f, pose.x, 0.001f)
        assertTrue(pose.is6DoF)
        assertEquals(12345L, pose.timestamp)
    }

    @Test
    fun `PoseState - copy로 위치 업데이트`() {
        val pose = PoseState(0f, 0f, 0f, 0f, 0f, 0f, 1f, is6DoF = true, timestamp = 0L)
        val updated = pose.copy(x = 5f, y = 3f)
        assertEquals(5f, updated.x, 0.001f)
        assertEquals(3f, updated.y, 0.001f)
        assertEquals(0f, updated.z, 0.001f)
    }

    // ── SpatialAnchorRecord 테스트 ──

    @Test
    fun `SpatialAnchorRecord - 필드 접근`() {
        val record = SpatialAnchorRecord(
            id = 1L, label = "chair", type = "OBJECT",
            gpsLatitude = 37.5, gpsLongitude = 127.0,
            localX = 1f, localY = 2f, localZ = 3f,
            depthMeters = 2.0f, depthSource = "CATEGORY_PRIOR",
            confidence = 0.8f, seenCount = 5,
            createdAt = 100L, lastSeenAt = 200L,
            visualEmbedding = null, metadata = null
        )
        assertEquals("chair", record.label)
        assertEquals(5, record.seenCount)
        assertEquals(37.5, record.gpsLatitude!!, 0.001)
    }

    // ── PlaceSignature 테스트 ──

    @Test
    fun `PlaceSignature - isConfirmed 3회 매칭`() {
        val sig = PlaceSignature(
            gpsLatitude = 37.5, gpsLongitude = 127.0,
            headingDegrees = 90f, matchCount = 3
        )
        assertTrue(sig.isConfirmed)
    }

    @Test
    fun `PlaceSignature - expiryDays 확정 90일`() {
        val sig = PlaceSignature(
            gpsLatitude = 37.5, gpsLongitude = 127.0,
            headingDegrees = 90f, matchCount = 5
        )
        assertEquals(90, sig.expiryDays)
    }

    @Test
    fun `PlaceSignature - expiryDays 미확정 30일`() {
        val sig = PlaceSignature(
            gpsLatitude = 37.5, gpsLongitude = 127.0,
            headingDegrees = 90f, matchCount = 0
        )
        assertEquals(30, sig.expiryDays)
    }

    // ── PlaceMatchResult 복합 점수 테스트 ──

    @Test
    fun `PlaceMatchResult - computeComposite 가중합`() {
        // 모든 축 1.0 → 복합 1.0
        val score = PlaceMatchResult.computeComposite(
            gps = 1f, heading = 1f, floor = 1f,
            path = 1f, visual = 1f, sceneGraph = 1f
        )
        assertEquals(1.0f, score, 0.01f)
    }

    @Test
    fun `PlaceMatchResult - computeComposite 모두 0이면 0`() {
        val score = PlaceMatchResult.computeComposite(
            gps = 0f, heading = 0f, floor = 0f,
            path = 0f, visual = 0f, sceneGraph = 0f
        )
        assertEquals(0.0f, score, 0.01f)
    }

    @Test
    fun `PlaceMatchResult - isMatch 임계값 0_6`() {
        val sig = PlaceSignature(gpsLatitude = 37.5, gpsLongitude = 127.0, headingDegrees = 90f)
        val result = PlaceMatchResult(
            signature = sig,
            gpsScore = 0.5f, headingScore = 0.5f, floorScore = 0.5f,
            pathScore = 0.5f, visualScore = 0.8f, sceneGraphScore = 0.5f,
            compositeScore = 0.61f
        )
        assertTrue(result.isMatch)
    }

    @Test
    fun `PlaceMatchResult - isMatch 미달`() {
        val sig = PlaceSignature(gpsLatitude = 37.5, gpsLongitude = 127.0, headingDegrees = 90f)
        val result = PlaceMatchResult(
            signature = sig,
            gpsScore = 0.3f, headingScore = 0.3f, floorScore = 0.3f,
            pathScore = 0.3f, visualScore = 0.3f, sceneGraphScore = 0.3f,
            compositeScore = 0.3f
        )
        assertFalse(result.isMatch)
    }

    // ── PathWaypoint 테스트 ──

    @Test
    fun `PathWaypoint - 데이터 생성`() {
        val wp = PathWaypoint(dx = 5f, dy = 0f, dz = 10f, timestamp = 1000L)
        assertEquals(5f, wp.dx, 0.001f)
        assertEquals(10f, wp.dz, 0.001f)
    }

    // ── 헬퍼 ──

    private fun createTestAnchor(seenCount: Int = 1) = SpatialAnchor(
        id = "test-id",
        label = "person",
        type = AnchorType.OBJECT,
        worldX = 0f, worldY = 0f, worldZ = 5f,
        confidence = 0.9f,
        createdAt = System.currentTimeMillis(),
        lastSeenAt = System.currentTimeMillis(),
        seenCount = seenCount,
        depthMeters = 5f,
        depthSource = DepthSource.CATEGORY_PRIOR
    )
}
