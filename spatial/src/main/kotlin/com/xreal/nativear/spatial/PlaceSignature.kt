package com.xreal.nativear.spatial

/**
 * PlaceSignature — 다중 모달 장소 시그니처.
 *
 * ## 6축 장소 인식
 * 같은 위치 + 같은 방위 = 같은 시각 장면이라는 원리를 활용하여
 * 크로스 세션 장소 인식의 정확도를 극대화.
 *
 * | 축 | 소스 | 용도 |
 * |---|------|------|
 * | GPS | LocationManager | 대략적 위치 (100m 반경 후보 축소) |
 * | 방위(heading) | IMU 쿼터니언 yaw | 바라보는 방향 필터링 |
 * | 고도/층수 | VIO Y축 변위 | 층수 구분 |
 * | 이동 경로 | VIO 누적 변위 | GPS 끊긴 후 실내 위치 식별 |
 * | 시각 임베딩 | MobileNetV3 1280-dim | 시각적 유사도 매칭 |
 * | 객체 목록 | YOLO/OCR 감지 | 씬 그래프 구조 매칭 |
 *
 * ## 사용 흐름
 * ```
 * 세션 중: 앵커 확정(seenCount>=3) 시 → PlaceSignature 저장
 * 재시작: GPS 근접 → 시그니처 후보 로드 → 현재 프레임 매칭 → 고스트 앵커 복원
 * ```
 *
 * @see PlaceRecognitionManager 시그니처 생성/매칭/복원 관리자
 * @see PathTracker VIO 이동 경로 추적
 */
data class PlaceSignature(
    val id: Long = 0,                          // DB primary key (0 = 미저장)

    // ── GPS (대략적 위치) ──
    val gpsLatitude: Double,
    val gpsLongitude: Double,
    val gpsAccuracy: Float = 0f,               // GPS 정확도 (미터)

    // ── 방위 (heading) ──
    /** 절대 방위각 (0-360°, 북=0, 동=90). VIO yaw + GPS heading 보정. */
    val headingDegrees: Float,

    // ── 고도/층수 ──
    /** VIO Y축 누적 변위 (미터). 시작점 대비 상대 고도. */
    val vioYDisplacement: Float = 0f,
    /** 추정 층수 (GPS 진입점 대비). 3m/층 가정. */
    val estimatedFloor: Int = 0,

    // ── 이동 경로 (GPS 끊긴 후) ──
    /** GPS 끊긴 후 VIO 누적 이동 거리 (미터) */
    val pathDistanceFromGps: Float = 0f,
    /** 이동 경로 요약: 직선 방향 + 총 거리. JSON 인코딩. */
    val pathSummaryJson: String? = null,

    // ── 시각 임베딩 ──
    /** MobileNetV3 1280-dim L2-normalized. ByteArray(5120) = 1280 * 4bytes. */
    val visualEmbedding: ByteArray? = null,

    // ── 씬 그래프 (감지 객체 목록) ──
    /** 해당 장소에서 감지된 주요 객체 라벨 (빈도순, 쉼표 구분) */
    val sceneLabels: String = "",

    // ── 메타데이터 ──
    /** 시그니처 생성 시각 */
    val createdAt: Long = System.currentTimeMillis(),
    /** 마지막 매칭 성공 시각 */
    val lastMatchedAt: Long = 0,
    /** 매칭 성공 횟수 (반복 방문 신뢰도) */
    val matchCount: Int = 0,
    /** 연관된 앵커 ID 목록 (DB rowId, 쉼표 구분) */
    val anchorIds: String = ""
) {
    /**
     * 반복 방문 확정 여부 (3회 이상 매칭).
     * 확정된 시그니처는 만료 기간 3배, 우선 매칭.
     */
    val isConfirmed: Boolean get() = matchCount >= 3

    /**
     * 시그니처 만료 기간 (일).
     * 확정: 90일, 미확정: 30일.
     */
    val expiryDays: Int get() = if (isConfirmed) 90 else 30

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlaceSignature) return false
        return id == other.id && gpsLatitude == other.gpsLatitude &&
                gpsLongitude == other.gpsLongitude && headingDegrees == other.headingDegrees
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + gpsLatitude.hashCode()
        result = 31 * result + gpsLongitude.hashCode()
        result = 31 * result + headingDegrees.hashCode()
        return result
    }
}

/**
 * PlaceMatchResult — 장소 매칭 결과.
 *
 * PlaceRecognitionManager.matchCurrentFrame()의 반환 타입.
 * 각 축별 점수와 복합 점수를 포함.
 */
data class PlaceMatchResult(
    val signature: PlaceSignature,

    // ── 축별 점수 (0-1, 높을수록 유사) ──
    val gpsScore: Float,            // haversine 거리 → 점수 변환
    val headingScore: Float,        // 방위각 차이 → 점수 변환
    val floorScore: Float,          // 층수 일치 여부
    val pathScore: Float,           // 이동 경로 거리 유사도
    val visualScore: Float,         // 임베딩 코사인 유사도
    val sceneGraphScore: Float,     // 객체 목록 Jaccard 유사도

    // ── 복합 점수 ──
    /** 가중 합산 복합 점수 (0-1). 임계값: 0.6 이상이면 매칭 성공. */
    val compositeScore: Float
) {
    companion object {
        /** 매칭 성공 임계값 */
        const val MATCH_THRESHOLD = 0.6f

        /** 높은 확신 매칭 임계값 (고스트 앵커 즉시 승격) */
        const val HIGH_CONFIDENCE_THRESHOLD = 0.8f

        // ── 축별 가중치 ──
        const val WEIGHT_GPS = 0.10f
        const val WEIGHT_HEADING = 0.15f
        const val WEIGHT_FLOOR = 0.15f
        const val WEIGHT_PATH = 0.10f
        const val WEIGHT_VISUAL = 0.35f
        const val WEIGHT_SCENE_GRAPH = 0.15f

        /**
         * 축별 점수에서 복합 점수 계산.
         */
        fun computeComposite(
            gps: Float, heading: Float, floor: Float,
            path: Float, visual: Float, sceneGraph: Float
        ): Float {
            return (gps * WEIGHT_GPS +
                    heading * WEIGHT_HEADING +
                    floor * WEIGHT_FLOOR +
                    path * WEIGHT_PATH +
                    visual * WEIGHT_VISUAL +
                    sceneGraph * WEIGHT_SCENE_GRAPH).coerceIn(0f, 1f)
        }
    }

    val isMatch: Boolean get() = compositeScore >= MATCH_THRESHOLD
    val isHighConfidence: Boolean get() = compositeScore >= HIGH_CONFIDENCE_THRESHOLD
}

/**
 * PathWaypoint — VIO 이동 경로의 단일 지점.
 *
 * PathTracker가 GPS 끊긴 후 VIO 포즈 변위를 기록.
 * 이동 경로를 시계열 waypoint로 저장하여 장소 시그니처의 pathSummaryJson으로 인코딩.
 */
data class PathWaypoint(
    /** GPS 마지막 지점 대비 VIO 변위 (미터) */
    val dx: Float,
    val dy: Float,      // 수직 (고도 변화)
    val dz: Float,
    /** 기록 시각 */
    val timestamp: Long
)
