package com.xreal.nativear.spatial

/**
 * SpatialAnchor — 3D 월드 좌표에 고정된 공간 앵커.
 *
 * ## 생성 흐름
 * ```
 * ObjectsDetected / OcrDetected
 *   → 깊이 추정 (Stereo SAD/SGBM → BBox → Category fallback)
 *   → CameraModel.unproject(u, v, depth) → 카메라 로컬 3D
 *   → PoseTransform.cameraToWorld(camPos, vioPose) → 월드 3D
 *   → SpatialAnchor(worldX, worldY, worldZ, ...)
 * ```
 *
 * ## 재투영 (매 HeadPoseUpdated, ~30Hz)
 * ```
 * 월드 3D → PoseTransform.worldToCamera → CameraModel.project → 화면 %좌표
 * ```
 *
 * ## 앵커 병합
 * 같은 라벨 + MERGE_DISTANCE_M(1.5m) 이내 → 기존 앵커 위치 가중 평균 업데이트,
 * seenCount 증가. 3회 이상 관측 = "확정 앵커" (만료 시간 2배).
 */
data class SpatialAnchor(
    val id: String,                           // UUID
    val label: String,                        // "person", "car", OCR 텍스트 (20자 제한)
    val type: AnchorType,                     // OBJECT or OCR_TEXT
    var worldX: Float,                        // VIO 월드 좌표 (미터)
    var worldY: Float,
    var worldZ: Float,
    val confidence: Float,                    // 감지 신뢰도 (0-1)
    val createdAt: Long,                      // 생성 시각 (System.currentTimeMillis)
    var lastSeenAt: Long,                     // 마지막 관측 시각
    var seenCount: Int = 1,                   // 관측 횟수
    var depthMeters: Float,                   // 추정 깊이
    val depthSource: DepthSource,             // 깊이 추정 방법
    val metadata: Map<String, Any> = emptyMap()  // OCR fullText, bboxAreaRatio 등
) {
    /** 확정 앵커 여부 (3회 이상 관측) */
    val isConfirmed: Boolean get() = seenCount >= 3

    /** 만료 시간 (초): 확정 앵커는 10분, 미확정은 5분 */
    val expirySeconds: Long get() = if (isConfirmed) 600L else 300L
}

// AnchorType → core-models 모듈로 이동 (com.xreal.nativear.spatial.AnchorType)

/** 깊이 추정 소스 */
enum class DepthSource {
    STEREO_DISPARITY,   // SLAM 스테레오 매칭 (가장 정확)
    BBOX_SIZE,          // 알려진 객체 크기 + bbox 높이 기반
    CATEGORY_PRIOR      // 카테고리별 기본 거리
}

/** 앵커 지속성 수준 */
enum class AnchorPersistenceLevel {
    SESSION_ONLY,       // Level 0: 메모리만, 세션 종료 시 소멸
    SESSION_WITH_DB,    // Level 1: 세션 중 동작 동일 + SceneDB에 기록 보존
    CROSS_SESSION       // Level 2: DB 기록 + 재시작 시 visual re-localization으로 복원
}

// AnchorLabel2D → core-models 모듈로 이동 (com.xreal.nativear.spatial.AnchorLabel2D)

/**
 * PoseState — 최신 VIO 포즈 캐시.
 *
 * SpatialAnchorManager가 HeadPoseUpdated 이벤트마다 갱신.
 * 앵커 생성/재투영 시 참조.
 */
data class PoseState(
    val x: Float, val y: Float, val z: Float,
    val qx: Float, val qy: Float, val qz: Float, val qw: Float,
    val is6DoF: Boolean,
    val timestamp: Long
)
