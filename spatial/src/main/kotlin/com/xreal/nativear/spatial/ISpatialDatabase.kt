package com.xreal.nativear.spatial

/**
 * ISpatialDatabase — :spatial 모듈이 필요한 DB 연산 인터페이스.
 *
 * SceneDatabase가 :app에서 구현.
 * IPolicyStore 패턴과 동일: 모듈 경계에서 인터페이스로 의존성 역전.
 */
interface ISpatialDatabase {

    // ── Spatial Anchors ──

    fun saveSpatialAnchor(
        label: String, type: String,
        gpsLat: Double?, gpsLon: Double?,
        localX: Float, localY: Float, localZ: Float,
        depthMeters: Float, depthSource: String,
        confidence: Float, seenCount: Int,
        createdAt: Long, lastSeenAt: Long,
        visualEmbedding: ByteArray? = null,
        metadata: String? = null
    ): Long

    fun loadSpatialAnchorsNear(lat: Double, lon: Double, radiusM: Double = 100.0): List<SpatialAnchorRecord>

    fun pruneSpatialAnchors(maxAgeDays: Int = 30, minSeenCount: Int = 3): Int

    fun getSpatialAnchorCount(): Int

    // ── Place Signatures ──

    fun savePlaceSignature(
        gpsLat: Double, gpsLon: Double, gpsAccuracy: Float,
        headingDegrees: Float,
        vioYDisplacement: Float, estimatedFloor: Int,
        pathDistanceFromGps: Float, pathSummaryJson: String?,
        visualEmbedding: ByteArray?,
        sceneLabels: String,
        anchorIds: String
    ): Long

    fun loadPlaceSignaturesNear(lat: Double, lon: Double, radiusM: Double = 200.0): List<PlaceSignatureRecord>

    fun findSimilarPlaces(queryEmbedding: ByteArray, topK: Int = 10): List<Pair<PlaceSignatureRecord, Float>>

    fun updatePlaceSignatureMatched(id: Long)
}

/**
 * 앵커 DB 레코드 (SceneDatabase.SpatialAnchorRecord 대체).
 */
data class SpatialAnchorRecord(
    val id: Long,
    val label: String,
    val type: String,
    val gpsLatitude: Double?,
    val gpsLongitude: Double?,
    val localX: Float,
    val localY: Float,
    val localZ: Float,
    val depthMeters: Float,
    val depthSource: String,
    val confidence: Float,
    val seenCount: Int,
    val createdAt: Long,
    val lastSeenAt: Long,
    val visualEmbedding: ByteArray?,
    val metadata: String?
)

/**
 * 장소 시그니처 DB 레코드 (SceneDatabase.PlaceSignatureRecord 대체).
 */
data class PlaceSignatureRecord(
    val id: Long,
    val gpsLatitude: Double,
    val gpsLongitude: Double,
    val gpsAccuracy: Float,
    val headingDegrees: Float,
    val vioYDisplacement: Float,
    val estimatedFloor: Int,
    val pathDistanceFromGps: Float,
    val pathSummaryJson: String?,
    val visualEmbedding: ByteArray?,
    val sceneLabels: String,
    val createdAt: Long,
    val lastMatchedAt: Long,
    val matchCount: Int,
    val anchorIds: String
)
