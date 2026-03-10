package com.xreal.nativear.spatial

/**
 * FakeSpatialDatabase — ISpatialDatabase 테스트용 Fake 구현.
 *
 * 인메모리 리스트로 모든 DB 연산을 수행.
 */
class FakeSpatialDatabase : ISpatialDatabase {

    private val anchors = mutableListOf<SpatialAnchorRecord>()
    private val places = mutableListOf<PlaceSignatureRecord>()
    private var nextAnchorId = 1L
    private var nextPlaceId = 1L

    // ── Spatial Anchors ──

    override fun saveSpatialAnchor(
        label: String, type: String,
        gpsLat: Double?, gpsLon: Double?,
        localX: Float, localY: Float, localZ: Float,
        depthMeters: Float, depthSource: String,
        confidence: Float, seenCount: Int,
        createdAt: Long, lastSeenAt: Long,
        visualEmbedding: ByteArray?, metadata: String?
    ): Long {
        val id = nextAnchorId++
        anchors.add(SpatialAnchorRecord(
            id = id, label = label, type = type,
            gpsLatitude = gpsLat, gpsLongitude = gpsLon,
            localX = localX, localY = localY, localZ = localZ,
            depthMeters = depthMeters, depthSource = depthSource,
            confidence = confidence, seenCount = seenCount,
            createdAt = createdAt, lastSeenAt = lastSeenAt,
            visualEmbedding = visualEmbedding, metadata = metadata
        ))
        return id
    }

    override fun loadSpatialAnchorsNear(lat: Double, lon: Double, radiusM: Double): List<SpatialAnchorRecord> {
        // 간단히 GPS가 있는 모든 앵커 반환 (거리 필터링 생략)
        return anchors.filter { it.gpsLatitude != null && it.gpsLongitude != null }
    }

    override fun pruneSpatialAnchors(maxAgeDays: Int, minSeenCount: Int): Int {
        val now = System.currentTimeMillis()
        val maxAgeMs = maxAgeDays * 24L * 60 * 60 * 1000
        val before = anchors.size
        anchors.removeAll { (now - it.createdAt) > maxAgeMs && it.seenCount < minSeenCount }
        return before - anchors.size
    }

    override fun getSpatialAnchorCount(): Int = anchors.size

    // ── Place Signatures ──

    override fun savePlaceSignature(
        gpsLat: Double, gpsLon: Double, gpsAccuracy: Float,
        headingDegrees: Float,
        vioYDisplacement: Float, estimatedFloor: Int,
        pathDistanceFromGps: Float, pathSummaryJson: String?,
        visualEmbedding: ByteArray?,
        sceneLabels: String, anchorIds: String
    ): Long {
        val id = nextPlaceId++
        places.add(PlaceSignatureRecord(
            id = id,
            gpsLatitude = gpsLat, gpsLongitude = gpsLon, gpsAccuracy = gpsAccuracy,
            headingDegrees = headingDegrees,
            vioYDisplacement = vioYDisplacement, estimatedFloor = estimatedFloor,
            pathDistanceFromGps = pathDistanceFromGps, pathSummaryJson = pathSummaryJson,
            visualEmbedding = visualEmbedding,
            sceneLabels = sceneLabels,
            createdAt = System.currentTimeMillis(),
            lastMatchedAt = 0L,
            matchCount = 0,
            anchorIds = anchorIds
        ))
        return id
    }

    override fun loadPlaceSignaturesNear(lat: Double, lon: Double, radiusM: Double): List<PlaceSignatureRecord> {
        return places.toList()
    }

    override fun findSimilarPlaces(queryEmbedding: ByteArray, topK: Int): List<Pair<PlaceSignatureRecord, Float>> {
        return places.take(topK).map { it to 0.9f }
    }

    override fun updatePlaceSignatureMatched(id: Long) {
        // No-op in fake
    }

    // ── 테스트 헬퍼 ──

    fun getAnchors(): List<SpatialAnchorRecord> = anchors.toList()
    fun getPlaces(): List<PlaceSignatureRecord> = places.toList()
    fun clear() { anchors.clear(); places.clear() }
}
