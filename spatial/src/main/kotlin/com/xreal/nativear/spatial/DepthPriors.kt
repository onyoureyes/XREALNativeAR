package com.xreal.nativear.spatial

/**
 * DepthPriors — 스테레오 깊이 불가 시 폴백 깊이 추정.
 *
 * ## 3단계 폴백 체인
 * ```
 * 1단계: 스테레오 디스패리티 (StereoDepthEngine) ← 여기서 실패 시
 * 2단계: bbox 크기 기반 (DepthPriors.estimateDepthFromBbox)
 * 3단계: 카테고리 기본값 (DepthPriors.getCategoryDepth)
 * ```
 *
 * ## bbox 기반 깊이 공식
 * 알려진 실제 높이 H (미터)와 초점 거리 f (pixels), bbox 높이 h (pixels):
 * ```
 * depth = H × f / h
 * ```
 */
object DepthPriors {

    /** 기본 폴백 깊이 (미등록 카테고리, 미터) */
    private const val DEFAULT_DEPTH = 3.0f

    /** 최소/최대 유효 깊이 (미터) */
    private const val MIN_DEPTH = 0.3f
    private const val MAX_DEPTH = 20.0f

    /**
     * COCO 80 라벨별 전형적 관찰 거리 (미터).
     *
     * 일상 환경에서 카메라가 해당 객체를 감지할 때의 전형적 거리.
     * 높은 정확도는 아니지만, 스테레오 깊이 불가 시 합리적 폴백 제공.
     */
    private val categoryDepth = mapOf(
        // 사람/동물
        "person" to 3.0f,
        "cat" to 2.0f,
        "dog" to 2.5f,
        "horse" to 6.0f,
        "sheep" to 6.0f,
        "cow" to 8.0f,
        "elephant" to 12.0f,
        "bear" to 8.0f,
        "zebra" to 8.0f,
        "giraffe" to 12.0f,
        "bird" to 4.0f,

        // 차량/교통
        "bicycle" to 5.0f,
        "car" to 8.0f,
        "motorcycle" to 6.0f,
        "airplane" to 15.0f,
        "bus" to 12.0f,
        "train" to 15.0f,
        "truck" to 10.0f,
        "boat" to 12.0f,

        // 교통 시설
        "traffic light" to 8.0f,
        "fire hydrant" to 4.0f,
        "stop sign" to 6.0f,
        "parking meter" to 3.0f,
        "bench" to 3.0f,

        // 실내 가구
        "chair" to 2.0f,
        "couch" to 3.0f,
        "potted plant" to 2.0f,
        "bed" to 3.0f,
        "dining table" to 2.5f,
        "toilet" to 2.0f,

        // 전자기기
        "tv" to 3.0f,
        "laptop" to 1.0f,
        "mouse" to 0.8f,
        "remote" to 1.5f,
        "keyboard" to 1.0f,
        "cell phone" to 1.0f,
        "microwave" to 2.0f,
        "oven" to 2.0f,
        "toaster" to 1.5f,
        "sink" to 1.5f,
        "refrigerator" to 3.0f,

        // 소품
        "bottle" to 1.5f,
        "wine glass" to 1.0f,
        "cup" to 1.0f,
        "fork" to 0.8f,
        "knife" to 0.8f,
        "spoon" to 0.8f,
        "bowl" to 1.0f,
        "banana" to 1.0f,
        "apple" to 1.0f,
        "sandwich" to 1.0f,
        "orange" to 1.0f,
        "broccoli" to 1.0f,
        "carrot" to 1.0f,
        "hot dog" to 1.0f,
        "pizza" to 1.0f,
        "donut" to 1.0f,
        "cake" to 1.5f,

        // 기타
        "backpack" to 2.0f,
        "umbrella" to 3.0f,
        "handbag" to 2.0f,
        "tie" to 1.5f,
        "suitcase" to 3.0f,
        "frisbee" to 5.0f,
        "skis" to 5.0f,
        "snowboard" to 5.0f,
        "sports ball" to 5.0f,
        "kite" to 10.0f,
        "baseball bat" to 3.0f,
        "baseball glove" to 3.0f,
        "skateboard" to 3.0f,
        "surfboard" to 5.0f,
        "tennis racket" to 3.0f,
        "book" to 1.0f,
        "clock" to 3.0f,
        "vase" to 2.0f,
        "scissors" to 1.0f,
        "teddy bear" to 2.0f,
        "hair drier" to 1.0f,
        "toothbrush" to 0.5f
    )

    /**
     * 알려진 실제 높이 (미터) — bbox 기반 깊이 추정에 사용.
     *
     * 객체의 세로 실제 크기. bbox 높이와 비교하여 깊이 계산.
     */
    private val knownHeights = mapOf(
        "person" to 1.7f,
        "car" to 1.5f,
        "bus" to 3.0f,
        "truck" to 2.5f,
        "motorcycle" to 1.1f,
        "bicycle" to 1.0f,
        "traffic light" to 0.6f,
        "stop sign" to 0.6f,
        "fire hydrant" to 0.5f,
        "chair" to 0.9f,
        "bottle" to 0.25f,
        "cup" to 0.12f,
        "dog" to 0.5f,
        "cat" to 0.3f,
        "tv" to 0.5f,
        "laptop" to 0.25f,
        "refrigerator" to 1.7f,
        "bench" to 0.8f,
        "couch" to 0.85f,
        "potted plant" to 0.4f,
        "suitcase" to 0.6f,
        "backpack" to 0.5f
    )

    /**
     * 카테고리 기본 깊이 조회.
     *
     * @param label YOLO 감지 라벨 (소문자)
     * @return 전형적 관찰 거리 (미터)
     */
    fun getCategoryDepth(label: String): Float {
        return categoryDepth[label.lowercase()] ?: DEFAULT_DEPTH
    }

    /**
     * bbox 크기 기반 깊이 추정.
     *
     * 알려진 실제 높이가 있는 객체에 대해:
     * ```
     * depth = realHeight × focalLength / bboxHeightPixels
     * ```
     *
     * @param label YOLO 감지 라벨
     * @param bboxHeightPx bbox 높이 (pixels)
     * @param focalLength 카메라 초점 거리 (pixels)
     * @return 추정 깊이 (미터), 또는 null (알려진 높이 없는 객체)
     */
    fun estimateDepthFromBbox(label: String, bboxHeightPx: Float, focalLength: Float): Float? {
        if (bboxHeightPx <= 0f) return null

        val realHeight = knownHeights[label.lowercase()] ?: return null
        val depth = realHeight * focalLength / bboxHeightPx

        return depth.coerceIn(MIN_DEPTH, MAX_DEPTH)
    }

    /**
     * OCR 텍스트 기본 깊이 추정.
     *
     * 텍스트 크기(길이)와 bbox 면적 비율로 간판 vs 근거리 텍스트 구분:
     * - 큰 텍스트 (간판): ~5m
     * - 중간 텍스트: ~3m
     * - 작은 텍스트 (라벨, 메뉴): ~1.5m
     *
     * @param textLength 인식된 텍스트 문자 수
     * @param bboxAreaRatio bbox 면적 / 이미지 면적 비율 (0-1)
     * @return 추정 깊이 (미터)
     */
    fun getOcrDepth(textLength: Int, bboxAreaRatio: Float): Float {
        return when {
            // 큰 간판: 긴 텍스트 또는 이미지 대비 큰 bbox
            bboxAreaRatio > 0.05f || textLength > 15 -> 5.0f
            // 중간 크기
            bboxAreaRatio > 0.02f || textLength > 8 -> 3.0f
            // 작은 텍스트 (가까운 라벨)
            else -> 1.5f
        }
    }

    /**
     * 라벨이 알려진 높이 정보를 가지고 있는지 확인.
     */
    fun hasKnownHeight(label: String): Boolean {
        return knownHeights.containsKey(label.lowercase())
    }
}
