package com.xreal.nativear.spatial

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * AnchorPersistence 단위 테스트 — FakeSpatialDatabase 사용.
 */
class AnchorPersistenceTest {

    private lateinit var fakeDb: FakeSpatialDatabase
    private lateinit var persistence: AnchorPersistence
    private val logs = mutableListOf<String>()

    @Before
    fun setup() {
        fakeDb = FakeSpatialDatabase()
        persistence = AnchorPersistence(fakeDb) { logs.add(it) }
    }

    // ── saveAnchor 테스트 ──

    @Test
    fun `saveAnchor - SESSION_ONLY이면 DB에 저장하지 않음`() {
        persistence.persistenceLevel = AnchorPersistenceLevel.SESSION_ONLY
        val anchor = createTestAnchor()
        persistence.saveAnchor(anchor, 37.5, 127.0)
        assertEquals(0, fakeDb.getSpatialAnchorCount())
    }

    @Test
    fun `saveAnchor - SESSION_WITH_DB이면 DB에 저장`() {
        persistence.persistenceLevel = AnchorPersistenceLevel.SESSION_WITH_DB
        val anchor = createTestAnchor()
        persistence.saveAnchor(anchor, 37.5, 127.0)
        assertEquals(1, fakeDb.getSpatialAnchorCount())
    }

    @Test
    fun `saveAnchor - CROSS_SESSION이면 DB에 저장`() {
        persistence.persistenceLevel = AnchorPersistenceLevel.CROSS_SESSION
        val anchor = createTestAnchor()
        persistence.saveAnchor(anchor, 37.5, 127.0)
        assertEquals(1, fakeDb.getSpatialAnchorCount())
    }

    // ── recordToGhostAnchor 테스트 ──

    @Test
    fun `recordToGhostAnchor - ghost 접두사 ID`() {
        val record = SpatialAnchorRecord(
            id = 42L, label = "chair", type = "OBJECT",
            gpsLatitude = 37.5, gpsLongitude = 127.0,
            localX = 1f, localY = 2f, localZ = 3f,
            depthMeters = 2f, depthSource = "CATEGORY_PRIOR",
            confidence = 0.8f, seenCount = 5,
            createdAt = 100L, lastSeenAt = 200L,
            visualEmbedding = null, metadata = null
        )
        val ghost = persistence.recordToGhostAnchor(record)
        assertEquals("ghost_42", ghost.id)
        assertEquals("chair", ghost.label)
        assertEquals(0.3f, ghost.confidence, 0.01f) // 고스트 낮은 신뢰도
        assertEquals(0, ghost.seenCount) // 이번 세션 미확인
        assertEquals(1f, ghost.worldX, 0.01f)
    }

    // ── loadNearbyAnchors 테스트 ──

    @Test
    fun `loadNearbyAnchors - CROSS_SESSION이 아니면 빈 리스트`() {
        persistence.persistenceLevel = AnchorPersistenceLevel.SESSION_WITH_DB
        val result = persistence.loadNearbyAnchors(37.5, 127.0)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `loadNearbyAnchors - CROSS_SESSION이면 DB에서 로드`() {
        persistence.persistenceLevel = AnchorPersistenceLevel.CROSS_SESSION
        // DB에 앵커 저장
        fakeDb.saveSpatialAnchor(
            "desk", "OBJECT", 37.5, 127.0,
            1f, 2f, 3f, 2f, "CATEGORY_PRIOR",
            0.9f, 5, 100L, 200L
        )
        val result = persistence.loadNearbyAnchors(37.5, 127.0)
        assertEquals(1, result.size)
    }

    // ── getPersistedAnchorCount ──

    @Test
    fun `getPersistedAnchorCount - DB 앵커 수 반환`() {
        assertEquals(0, persistence.getPersistedAnchorCount())
        fakeDb.saveSpatialAnchor("a", "OBJECT", null, null, 0f, 0f, 0f, 1f, "CATEGORY_PRIOR", 0.5f, 1, 0L, 0L)
        assertEquals(1, persistence.getPersistedAnchorCount())
    }

    // ── 헬퍼 ──

    private fun createTestAnchor() = SpatialAnchor(
        id = "test-id",
        label = "person",
        type = AnchorType.OBJECT,
        worldX = 0f, worldY = 0f, worldZ = 5f,
        confidence = 0.9f,
        createdAt = System.currentTimeMillis(),
        lastSeenAt = System.currentTimeMillis(),
        seenCount = 3,
        depthMeters = 5f,
        depthSource = DepthSource.CATEGORY_PRIOR
    )
}
