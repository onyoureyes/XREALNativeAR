package com.xreal.nativear.camera

/**
 * 카메라 프레임 소스 유형 + 상태 추적.
 *
 * priority가 낮을수록 우선순위 높음 (RGB > SLAM > PHONE > REMOTE).
 * CameraStreamManager가 소스 선택/fallback에 사용.
 */
enum class FrameSourceType(val priority: Int, val label: String) {
    RGB_CENTER(0, "RGB 센터 카메라"),
    SLAM_LEFT(1, "SLAM 좌안"),
    PHONE_CAMERA(2, "폰 후면 카메라"),
    REMOTE_PC(3, "PC 웹캠")
}

/**
 * 개별 프레임 소스 건강 상태.
 * CameraStreamManager가 주기적으로 갱신.
 */
data class SourceHealth(
    val type: FrameSourceType,
    val isConnected: Boolean = false,
    val isHealthy: Boolean = false,
    val lastFrameTimeMs: Long = 0L,
    val frameCount: Long = 0L,
    val consecutiveDarkFrames: Int = 0,
    val errorCount: Int = 0,
    val fps: Float = 0f
) {
    /** 프레임 수신 중이고 다크 프레임이 아닌 정상 상태 */
    val isActive: Boolean get() = isConnected && isHealthy
}
