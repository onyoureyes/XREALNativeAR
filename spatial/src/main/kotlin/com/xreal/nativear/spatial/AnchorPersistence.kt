package com.xreal.nativear.spatial

import com.xreal.nativear.core.XRealLogger
import org.json.JSONObject

/**
 * AnchorPersistence — 앵커 지속성 관리자.
 *
 * ## 3단계 지속성 수준
 *
 * ### Level 0: SESSION_ONLY (기본)
 * - 메모리만 사용, 세션 종료 시 소멸
 * - DB 접근 없음, 오버헤드 제로
 *
 * ### Level 1: SESSION_WITH_DB
 * - 세션 중 동작은 Level 0과 동일
 * - 앵커 생성/업데이트 시 SceneDatabase.spatial_anchors에 기록
 * - 앱 종료 후에도 기록 보존 (히스토리/분석용)
 * - 재시작 시 자동 복원하지 않음
 *
 * ### Level 2: CROSS_SESSION
 * - Level 1의 모든 기능 포함
 * - 앱 재시작 시 GPS 근접 앵커를 "고스트"로 복원
 * - Visual embedding 유사도로 VIO 좌표계 정렬 시도
 * - 실제 감지로 확인되면 고스트 → 활성 앵커로 승격
 *
 * ## 사용
 * ```kotlin
 * persistence.persistenceLevel = AnchorPersistenceLevel.SESSION_WITH_DB
 * persistence.saveAnchor(anchor, gpsLat, gpsLon)
 * ```
 *
 * @param spatialDatabase 공간 DB 인터페이스 (spatial_anchors 테이블)
 * @param log 로깅 콜백
 */
class AnchorPersistence(
    private val spatialDatabase: ISpatialDatabase,
    private val log: (String) -> Unit
) {
    companion object {
        private const val TAG = "AnchorPersistence"

        /** 크로스 세션 GPS 검색 반경 (미터) */
        const val CROSS_SESSION_RADIUS_M = 100.0

        /** 고스트 앵커 매칭 시간 제한 (초) — 미매칭 시 제거 */
        const val GHOST_TIMEOUT_SEC = 30

        /** 오래된 앵커 정리: 최대 보존 기간 (일) */
        const val PRUNE_MAX_AGE_DAYS = 30

        /** 오래된 앵커 정리: 최소 관측 횟수 미만이면 삭제 */
        const val PRUNE_MIN_SEEN_COUNT = 3
    }

    /** 현재 지속성 수준 */
    @Volatile
    var persistenceLevel: AnchorPersistenceLevel = AnchorPersistenceLevel.SESSION_ONLY

    /**
     * Level 1+: 앵커를 DB에 저장.
     *
     * SESSION_ONLY이면 무시. SESSION_WITH_DB/CROSS_SESSION이면 저장.
     */
    fun saveAnchor(anchor: SpatialAnchor, gpsLat: Double?, gpsLon: Double?) {
        if (persistenceLevel == AnchorPersistenceLevel.SESSION_ONLY) return

        val metadataJson = if (anchor.metadata.isNotEmpty()) {
            try {
                JSONObject(anchor.metadata.mapValues { it.value.toString() }).toString()
            } catch (e: Exception) { null }
        } else null

        val rowId = spatialDatabase.saveSpatialAnchor(
            label = anchor.label,
            type = anchor.type.name,
            gpsLat = gpsLat,
            gpsLon = gpsLon,
            localX = anchor.worldX,
            localY = anchor.worldY,
            localZ = anchor.worldZ,
            depthMeters = anchor.depthMeters,
            depthSource = anchor.depthSource.name,
            confidence = anchor.confidence,
            seenCount = anchor.seenCount,
            createdAt = anchor.createdAt,
            lastSeenAt = anchor.lastSeenAt,
            visualEmbedding = null,  // 추후 VisionManager에서 추출 시 전달
            metadata = metadataJson
        )

        if (rowId > 0) {
            log("Anchor saved to DB: ${anchor.label} (rowId=$rowId, level=$persistenceLevel)")
        }
    }

    /**
     * Level 2: 현재 GPS 위치 근처 앵커 후보 로드.
     *
     * 크로스 세션 복원 시 사용: GPS 반경 100m 내 DB 앵커를 불러와
     * 고스트 앵커로 변환.
     *
     * @param lat 현재 GPS 위도
     * @param lon 현재 GPS 경도
     * @return 복원 가능한 앵커 레코드 목록
     */
    fun loadNearbyAnchors(lat: Double, lon: Double): List<SpatialAnchorRecord> {
        if (persistenceLevel != AnchorPersistenceLevel.CROSS_SESSION) return emptyList()

        val anchors = spatialDatabase.loadSpatialAnchorsNear(lat, lon, CROSS_SESSION_RADIUS_M)
        log("Loaded ${anchors.size} nearby anchors from DB (${CROSS_SESSION_RADIUS_M}m radius)")
        return anchors
    }

    /**
     * DB 레코드 → 고스트 SpatialAnchor 변환.
     *
     * 고스트 앵커는 confidence=0.3, seenCount=0 으로 생성.
     * 실제 감지로 확인되면 SpatialAnchorManager에서 정상 앵커로 병합/승격.
     */
    fun recordToGhostAnchor(record: SpatialAnchorRecord): SpatialAnchor {
        return SpatialAnchor(
            id = "ghost_${record.id}",
            label = record.label,
            type = try { AnchorType.valueOf(record.type) } catch (_: Exception) { AnchorType.OBJECT },
            worldX = record.localX,  // VIO 좌표 — 좌표계 정렬 필요
            worldY = record.localY,
            worldZ = record.localZ,
            confidence = 0.3f,  // 고스트: 낮은 신뢰도
            createdAt = record.createdAt,
            lastSeenAt = System.currentTimeMillis(),
            seenCount = 0,  // 이번 세션에서 아직 미확인
            depthMeters = record.depthMeters,
            depthSource = try { DepthSource.valueOf(record.depthSource) } catch (_: Exception) { DepthSource.CATEGORY_PRIOR },
            metadata = if (record.metadata != null) {
                try {
                    val json = JSONObject(record.metadata)
                    json.keys().asSequence().associate { it to (json.get(it) as Any) }
                } catch (_: Exception) { emptyMap() }
            } else emptyMap()
        )
    }

    /**
     * 오래된 앵커 정리 (30일 초과, seenCount < 3).
     */
    fun pruneOldAnchors() {
        val deleted = spatialDatabase.pruneSpatialAnchors(PRUNE_MAX_AGE_DAYS, PRUNE_MIN_SEEN_COUNT)
        if (deleted > 0) {
            log("Pruned $deleted old anchors from DB")
        }
    }

    /** DB 앵커 총 개수 */
    fun getPersistedAnchorCount(): Int = spatialDatabase.getSpatialAnchorCount()
}
