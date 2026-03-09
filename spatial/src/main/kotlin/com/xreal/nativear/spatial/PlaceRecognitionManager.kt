package com.xreal.nativear.spatial

import android.util.Log
import com.xreal.nativear.ImageEmbedder
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos

/**
 * PlaceRecognitionManager — 크로스 세션 장소 인식 관리자.
 *
 * ## 핵심 역할
 * 1. **시그니처 생성**: 앵커 확정 시 현재 장소의 6축 시그니처를 DB에 저장
 * 2. **장소 인식**: 앱 재시작 시 GPS + 시각 임베딩으로 이전 방문 장소 인식
 * 3. **고스트 앵커 복원**: 인식된 장소의 저장된 앵커를 고스트로 복원
 *
 * ## 6축 매칭 파이프라인
 * ```
 * 1. GPS 반경 200m 내 후보 시그니처 로드
 * 2. 시각 임베딩 KNN으로 상위 10개 필터링
 * 3. 각 후보에 대해 6축 점수 계산:
 *    GPS(10%) + 방위(15%) + 층수(15%) + 경로(10%) + 시각(35%) + 씬그래프(15%)
 * 4. 복합 점수 >= 0.6 → 매칭 성공 → 고스트 앵커 복원
 * ```
 *
 * ## 데이터 흐름
 * ```
 * [세션 중]
 * SpatialAnchorEvent(CREATED) → 앵커 누적 → 확정 시 시그니처 저장
 * VisualEmbedding → 최신 시각 임베딩 캐시
 *
 * [앱 재시작]
 * PhoneGps → GPS 확보 → 후보 시그니처 로드 → 매칭 시도
 * VisualEmbedding → 매칭 성공 시 고스트 앵커 복원 → SpatialAnchorManager에 주입
 * ```
 *
 * @param sceneDatabase DB (place_signatures, spatial_anchors 테이블)
 * @param imageEmbedder MobileNetV3 시각 임베딩 추출
 * @param pathTracker VIO 이동 경로 추적
 * @param anchorPersistence 앵커 DB 연동
 * @param eventBus 이벤트 버스
 * @param log 로깅 콜백
 */
class PlaceRecognitionManager(
    private val spatialDatabase: ISpatialDatabase,
    private val imageEmbedder: ImageEmbedder,
    private val pathTracker: PathTracker,
    private val anchorPersistence: AnchorPersistence,
    private val eventBus: GlobalEventBus,
    private val log: (String) -> Unit
) {
    companion object {
        private const val TAG = "PlaceRecognition"

        /** GPS 후보 검색 반경 (미터) */
        const val GPS_SEARCH_RADIUS_M = 200.0

        /** 시그니처 저장 최소 앵커 수 (이 이상 확정 앵커가 있어야 저장) */
        const val MIN_ANCHORS_FOR_SIGNATURE = 2

        /** 매칭 시도 간격 (ms). 너무 자주 하면 성능 낭비. */
        const val MATCH_INTERVAL_MS = 10_000L

        /** 매칭 성공 후 쿨다운 (ms). 같은 장소 반복 매칭 방지. */
        const val MATCH_COOLDOWN_MS = 60_000L

        /** 시그니처 저장 쿨다운 (ms). 같은 장소 반복 저장 방지. */
        const val SAVE_COOLDOWN_MS = 120_000L

        /** 방위각 매칭 허용 범위 (도). ±30° 이내면 같은 방향. */
        const val HEADING_TOLERANCE_DEG = 30f

        /** 고스트 앵커 타임아웃 (초). 매칭 불확실 고스트 제거. */
        const val GHOST_TIMEOUT_SEC = 60
    }

    // ── 상태 ──

    /** 최신 시각 임베딩 (VisualEmbedding 이벤트에서 갱신) */
    @Volatile
    private var latestVisualEmbedding: FloatArray? = null
    @Volatile
    private var latestVisualEmbeddingBytes: ByteArray? = null

    /** 현재 세션의 활성 앵커 라벨 (씬 그래프 구성용) */
    private val sessionLabels = mutableSetOf<String>()

    /** 마지막 매칭 시도 시각 */
    @Volatile
    private var lastMatchAttemptTime = 0L

    /** 마지막 매칭 성공 시각 */
    @Volatile
    private var lastMatchSuccessTime = 0L

    /** 마지막 시그니처 저장 시각 */
    @Volatile
    private var lastSaveTime = 0L

    /** 현재 세션에서 매칭된 장소 시그니처 ID (중복 방지) */
    private val matchedSignatureIds = mutableSetOf<Long>()

    /** 복원된 고스트 앵커 목록 */
    private val restoredGhosts = mutableListOf<SpatialAnchor>()

    /** 고스트 앵커 콜백 (SpatialAnchorManager가 등록) */
    var onGhostsRestored: ((List<SpatialAnchor>) -> Unit)? = null

    // ── 코루틴 ──
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var eventJob: Job? = null
    private var matchJob: Job? = null

    // ── Public API ──

    /** 시작: EventBus 구독 + 주기적 매칭 시도 */
    fun start() {
        eventJob = scope.launch {
            // 시각 임베딩 구독
            launch {
                eventBus.events.filterIsInstance<XRealEvent.PerceptionEvent.VisualEmbedding>()
                    .collect { onVisualEmbedding(it) }
            }
            // 앵커 이벤트 구독 (씬 그래프 구성 + 시그니처 저장 트리거)
            launch {
                eventBus.events.filterIsInstance<XRealEvent.PerceptionEvent.SpatialAnchorEvent>()
                    .collect { onAnchorEvent(it) }
            }
            // GPS 구독 (매칭 트리거)
            launch {
                eventBus.events.filterIsInstance<XRealEvent.PerceptionEvent.PhoneGps>()
                    .collect { onGpsForMatching(it) }
            }
        }

        // 주기적 매칭 시도 루프
        matchJob = scope.launch {
            delay(5000) // 초기 대기 (VIO 안정화)
            while (isActive) {
                delay(MATCH_INTERVAL_MS)
                tryMatchCurrentPlace()
            }
        }

        log("PlaceRecognitionManager started")
    }

    /** 정지 */
    fun stop() {
        eventJob?.cancel()
        matchJob?.cancel()
        log("PlaceRecognitionManager stopped (${sessionLabels.size} labels, ${matchedSignatureIds.size} matches)")
    }

    /**
     * 현재 장소의 시그니처를 즉시 저장.
     * SpatialAnchorManager에서 앵커 확정 시 호출.
     *
     * @param confirmedAnchors 확정된 앵커 목록 (seenCount >= 3)
     */
    fun saveCurrentPlaceSignature(confirmedAnchors: List<SpatialAnchor>) {
        val now = System.currentTimeMillis()
        if (now - lastSaveTime < SAVE_COOLDOWN_MS) return
        if (confirmedAnchors.size < MIN_ANCHORS_FOR_SIGNATURE) return

        val lat = pathTracker.lastGpsLatitude ?: return
        val lon = pathTracker.lastGpsLongitude ?: return

        val pathState = pathTracker.getCurrentPathState()
        val heading = pathTracker.getCurrentHeading()
        val pathJson = pathTracker.buildPathSummaryJson()

        // 씬 그래프 라벨 (빈도순)
        val sceneLabels = confirmedAnchors
            .groupBy { it.label }
            .entries
            .sortedByDescending { it.value.size }
            .joinToString(",") { it.key }

        // 시각 임베딩
        val embBytes = latestVisualEmbeddingBytes

        // 연관 앵커 DB ID들 (AnchorPersistence에서 저장된 것)
        // 여기서는 앵커 라벨로 대체 (DB ID가 없을 수 있음)
        val anchorIds = ""

        val id = spatialDatabase.savePlaceSignature(
            gpsLat = lat,
            gpsLon = lon,
            gpsAccuracy = pathTracker.lastGpsAccuracy,
            headingDegrees = heading,
            vioYDisplacement = pathState.vioYDisplacement,
            estimatedFloor = pathState.estimatedFloor,
            pathDistanceFromGps = pathState.totalDistance,
            pathSummaryJson = pathJson,
            visualEmbedding = embBytes,
            sceneLabels = sceneLabels,
            anchorIds = anchorIds
        )

        if (id > 0) {
            lastSaveTime = now
            log("Place signature saved: id=$id, floor=${pathState.estimatedFloor}, labels=$sceneLabels")

            // 확정 앵커들을 DB에도 저장 (Level 1+ 이면 이미 저장됨)
            for (anchor in confirmedAnchors) {
                anchorPersistence.saveAnchor(anchor, lat, lon)
                // 시그니처에 앵커 ID 연결은 별도 작업 필요
            }
        }
    }

    /**
     * 현재 프레임으로 장소 매칭 시도.
     */
    fun tryMatchCurrentPlace(): PlaceMatchResult? {
        val now = System.currentTimeMillis()
        if (now - lastMatchAttemptTime < MATCH_INTERVAL_MS) return null
        if (now - lastMatchSuccessTime < MATCH_COOLDOWN_MS) return null
        lastMatchAttemptTime = now

        val lat = pathTracker.lastGpsLatitude ?: return null
        val lon = pathTracker.lastGpsLongitude ?: return null
        val embBytes = latestVisualEmbeddingBytes ?: return null
        val embFloats = latestVisualEmbedding ?: return null

        // 1. GPS 반경 내 후보 로드
        val candidates = spatialDatabase.loadPlaceSignaturesNear(lat, lon, GPS_SEARCH_RADIUS_M)
        if (candidates.isEmpty()) return null

        // 2. 시각 유사도 KNN 상위 필터 (선택적, 후보가 많을 때)
        val filteredCandidates = if (candidates.size > 20) {
            val knnResults = spatialDatabase.findSimilarPlaces(embBytes, 10)
            val knnIds = knnResults.map { it.first.id }.toSet()
            candidates.filter { it.id in knnIds }
        } else {
            candidates
        }

        // 3. 각 후보에 대해 6축 복합 매칭
        var bestResult: PlaceMatchResult? = null

        for (candidate in filteredCandidates) {
            if (candidate.id in matchedSignatureIds) continue // 이미 매칭된 건 스킵

            val result = computeMatchScore(candidate, lat, lon, embFloats)
            if (result.isMatch) {
                if (bestResult == null || result.compositeScore > bestResult.compositeScore) {
                    bestResult = result
                }
            }
        }

        // 4. 매칭 성공 → 고스트 앵커 복원
        if (bestResult != null) {
            lastMatchSuccessTime = now
            matchedSignatureIds.add(bestResult.signature.id)
            spatialDatabase.updatePlaceSignatureMatched(bestResult.signature.id)

            log("Place matched! score=${bestResult.compositeScore}, " +
                    "floor=${bestResult.signature.estimatedFloor}, " +
                    "labels=${bestResult.signature.sceneLabels}")

            restoreGhostAnchors(bestResult)
        }

        return bestResult
    }

    /**
     * 6축 복합 매칭 점수 계산.
     */
    private fun computeMatchScore(
        candidate: PlaceSignatureRecord,
        currentLat: Double,
        currentLon: Double,
        currentEmbedding: FloatArray
    ): PlaceMatchResult {
        // GPS 점수: 거리 기반 (0m=1.0, 200m=0.0)
        val gpsDist = haversineDistance(currentLat, currentLon, candidate.gpsLatitude, candidate.gpsLongitude)
        val gpsScore = (1.0f - (gpsDist / GPS_SEARCH_RADIUS_M).toFloat()).coerceIn(0f, 1f)

        // 방위 점수: 각도 차이 (0°=1.0, 30°=0.0)
        val currentHeading = pathTracker.getCurrentHeading()
        val headingDiff = angleDifference(currentHeading, candidate.headingDegrees)
        val headingScore = (1.0f - headingDiff / HEADING_TOLERANCE_DEG).coerceIn(0f, 1f)

        // 층수 점수: 정확 일치=1.0, ±1층=0.5, 그 외=0.0
        val pathState = pathTracker.getCurrentPathState()
        val floorDiff = abs(pathState.estimatedFloor - candidate.estimatedFloor)
        val floorScore = when (floorDiff) {
            0 -> 1.0f
            1 -> 0.5f
            else -> 0.0f
        }

        // 경로 점수: 이동 거리 유사도
        val pathScore = pathTracker.comparePathSummaries(
            pathTracker.buildPathSummaryJson(),
            candidate.pathSummaryJson
        )

        // 시각 점수: 임베딩 코사인 유사도
        val visualScore = if (candidate.visualEmbedding != null) {
            val candidateFloats = byteArrayToFloatArray(candidate.visualEmbedding)
            if (candidateFloats != null) {
                imageEmbedder.calculateSimilarity(currentEmbedding, candidateFloats)
                    .coerceIn(0f, 1f)
            } else 0f
        } else 0f

        // 씬 그래프 점수: 라벨 Jaccard 유사도
        val sceneGraphScore = computeSceneGraphScore(
            sessionLabels.toSet(),
            candidate.sceneLabels.split(",").filter { it.isNotBlank() }.toSet()
        )

        // 복합 점수
        val composite = PlaceMatchResult.computeComposite(
            gps = gpsScore,
            heading = headingScore,
            floor = floorScore,
            path = pathScore,
            visual = visualScore,
            sceneGraph = sceneGraphScore
        )

        return PlaceMatchResult(
            signature = PlaceSignature(
                id = candidate.id,
                gpsLatitude = candidate.gpsLatitude,
                gpsLongitude = candidate.gpsLongitude,
                gpsAccuracy = candidate.gpsAccuracy,
                headingDegrees = candidate.headingDegrees,
                vioYDisplacement = candidate.vioYDisplacement,
                estimatedFloor = candidate.estimatedFloor,
                pathDistanceFromGps = candidate.pathDistanceFromGps,
                pathSummaryJson = candidate.pathSummaryJson,
                visualEmbedding = candidate.visualEmbedding,
                sceneLabels = candidate.sceneLabels,
                createdAt = candidate.createdAt,
                lastMatchedAt = candidate.lastMatchedAt,
                matchCount = candidate.matchCount,
                anchorIds = candidate.anchorIds
            ),
            gpsScore = gpsScore,
            headingScore = headingScore,
            floorScore = floorScore,
            pathScore = pathScore,
            visualScore = visualScore,
            sceneGraphScore = sceneGraphScore,
            compositeScore = composite
        )
    }

    /**
     * 매칭된 장소의 앵커를 고스트로 복원.
     */
    private fun restoreGhostAnchors(matchResult: PlaceMatchResult) {
        val sig = matchResult.signature
        val lat = sig.gpsLatitude
        val lon = sig.gpsLongitude

        // DB에서 해당 장소 근처 앵커 로드
        val anchorRecords = spatialDatabase.loadSpatialAnchorsNear(lat, lon, 50.0)
        if (anchorRecords.isEmpty()) {
            log("No anchors found near matched place (lat=$lat, lon=$lon)")
            return
        }

        val ghosts = mutableListOf<SpatialAnchor>()
        for (record in anchorRecords) {
            val ghost = anchorPersistence.recordToGhostAnchor(record)
            ghosts.add(ghost)
        }

        restoredGhosts.clear()
        restoredGhosts.addAll(ghosts)

        log("Restored ${ghosts.size} ghost anchors from matched place " +
                "(score=${matchResult.compositeScore}, floor=${sig.estimatedFloor})")

        // SpatialAnchorManager에 콜백
        onGhostsRestored?.invoke(ghosts)
    }

    // ── 이벤트 핸들러 ──

    private fun onVisualEmbedding(event: XRealEvent.PerceptionEvent.VisualEmbedding) {
        val floats = byteArrayToFloatArray(event.embedding)
        if (floats != null) {
            latestVisualEmbedding = floats
            latestVisualEmbeddingBytes = event.embedding
        }
    }

    private fun onAnchorEvent(event: XRealEvent.PerceptionEvent.SpatialAnchorEvent) {
        when (event.action) {
            "CREATED", "UPDATED" -> sessionLabels.add(event.label)
            "REMOVED" -> {} // 라벨은 세션 동안 유지 (히스토리)
        }
    }

    private fun onGpsForMatching(event: XRealEvent.PerceptionEvent.PhoneGps) {
        // GPS가 처음 확보되면 즉시 매칭 시도
        if (matchedSignatureIds.isEmpty() && latestVisualEmbedding != null) {
            scope.launch {
                delay(2000) // VIO 안정화 대기
                tryMatchCurrentPlace()
            }
        }
    }

    // ── 유틸리티 ──

    /**
     * 두 각도의 최소 차이 (0-180°).
     */
    private fun angleDifference(a: Float, b: Float): Float {
        val diff = abs(a - b) % 360f
        return if (diff > 180f) 360f - diff else diff
    }

    /**
     * 두 라벨 집합의 Jaccard 유사도 (0-1).
     */
    private fun computeSceneGraphScore(set1: Set<String>, set2: Set<String>): Float {
        if (set1.isEmpty() && set2.isEmpty()) return 0.5f // 둘 다 비어있으면 중립
        if (set1.isEmpty() || set2.isEmpty()) return 0f

        val intersection = set1.intersect(set2).size
        val union = set1.union(set2).size
        return intersection.toFloat() / union.toFloat()
    }

    /**
     * ByteArray → FloatArray 변환 (1280-dim 임베딩).
     */
    private fun byteArrayToFloatArray(bytes: ByteArray): FloatArray? {
        if (bytes.size % 4 != 0) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) {
            floats[i] = buffer.getFloat()
        }
        return floats
    }

    /**
     * Haversine 거리 (미터).
     */
    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return R * c
    }
}
