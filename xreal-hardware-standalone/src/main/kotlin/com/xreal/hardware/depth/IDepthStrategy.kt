package com.xreal.hardware.depth

/**
 * IDepthStrategy — 스테레오 깊이 추정 전략 인터페이스.
 *
 * ## 구현체
 * - [SparseSADDepth]: SAD 윈도우 매칭, 포인트별 희소 깊이 (~0.5ms/점)
 * - [DenseSGBMDepth]: OpenCV StereoSGBM, 전체 디스패리티 맵 (~15ms/프레임)
 *
 * ## 사용 흐름
 * ```
 * 1. updateStereoFrames(left, right, timestamp) — 매 스테레오 프레임
 * 2. queryDepthAt(u, v)                          — 특정 픽셀의 깊이 조회
 * ```
 */
interface IDepthStrategy {
    /** 전략 이름 (로깅/디버그용) */
    val name: String

    /**
     * 새 스테레오 프레임 쌍 업데이트.
     *
     * Rectified 640×480 그레이스케일 프레임.
     * SGBM은 여기서 디스패리티 맵을 갱신하고,
     * SAD는 프레임만 저장해두고 queryDepthAt에서 매칭.
     *
     * @param left 좌안 rectified 프레임 (640×480 grayscale, ByteArray)
     * @param right 우안 rectified 프레임
     * @param timestamp 프레임 타임스탬프 (마이크로초)
     */
    fun updateStereoFrames(left: ByteArray, right: ByteArray, timestamp: Long)

    /**
     * 특정 이미지 좌표의 깊이 조회.
     *
     * @param u 좌안 이미지 x 좌표 (0-639)
     * @param v 좌안 이미지 y 좌표 (0-479)
     * @return 깊이 (미터), 또는 null (디스패리티 무효, 범위 밖 등)
     */
    fun queryDepthAt(u: Int, v: Int): Float?

    /**
     * 최신 전체 디스패리티 맵 (SGBM 전용).
     *
     * SAD는 null 반환 (포인트별 매칭이므로 전체 맵 없음).
     *
     * @return 640×480 Float 배열 (디스패리티 값) 또는 null
     */
    fun getLatestDisparityMap(): FloatArray?

    /** 리소스 해제 */
    fun release()
}
