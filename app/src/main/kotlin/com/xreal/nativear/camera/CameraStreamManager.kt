package com.xreal.nativear.camera

import android.graphics.Bitmap
import android.util.Log
import com.xreal.nativear.VisionManager
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.policy.PolicyReader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CameraStreamManager — 카메라 소스 선택 + 건강 모니터링 + 자동 fallback.
 *
 * ## 역할
 * HardwareManager(프레임 생산) ↔ CameraStreamManager(소스 선택) → VisionManager(프레임 소비)
 *
 * ## 프레임 소스 우선순위
 * RGB_CENTER(0) > SLAM_LEFT(1) > PHONE_CAMERA(2) > REMOTE_PC(3)
 *
 * ## 상태 머신
 * - 글래스 연결 → RGB 시도 → 성공 시 RGB, RGB 건강 이상 시 SLAM fallback
 * - 글래스 미연결 → PHONE_CAMERA
 * - 수동 cycle() → 다음 available 소스로 순환
 *
 * ## 건강 판단 (PolicyRegistry 정책으로 조절 가능)
 * - fps < threshold → unhealthy
 * - 연속 다크 프레임 > limit → unhealthy
 * - no_frame_timeout 초과 → unhealthy
 */
class CameraStreamManager(
    private val visionManager: VisionManager,
    private val eventBus: GlobalEventBus
) {
    private val TAG = "CameraStreamMgr"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** RGB ISOC 재시작 콜백 — HardwareManager가 설정 */
    var rgbRestartCallback: (() -> Unit)? = null
    private var rgbRestartCount = 0
    private var lastRgbRestartMs = 0L

    // 현재 활성 소스
    private val _activeSource = MutableStateFlow(FrameSourceType.PHONE_CAMERA)
    val activeSource: StateFlow<FrameSourceType> = _activeSource.asStateFlow()

    // 각 소스별 건강 상태
    private val healthMap = mutableMapOf(
        FrameSourceType.RGB_CENTER to SourceHealth(FrameSourceType.RGB_CENTER),
        FrameSourceType.SLAM_LEFT to SourceHealth(FrameSourceType.SLAM_LEFT),
        FrameSourceType.PHONE_CAMERA to SourceHealth(FrameSourceType.PHONE_CAMERA, isConnected = true, isHealthy = true),
        FrameSourceType.REMOTE_PC to SourceHealth(FrameSourceType.REMOTE_PC)
    )

    // 수동 전환 모드 (사용자 cycle 후 자동 fallback 일시 중지)
    @Volatile private var manualOverride = false
    @Volatile private var manualOverrideExpiresAt = 0L

    // 소스 전환 직후 유예 기간 (프레임 도착 대기)
    @Volatile private var switchGraceExpiresAt = 0L

    // fps 추적용 (소스별)
    private val fpsCounters = mutableMapOf<FrameSourceType, FpsCounter>()

    private var healthCheckJob: Job? = null

    // =========================================================================
    // 프레임 수신 — HardwareManager에서 호출
    // =========================================================================

    /**
     * RGB 프레임 수신. HardwareManager.processRgbFrameData()에서 호출.
     * @return true면 이 프레임이 VisionManager에 전달됨
     */
    fun onRgbFrame(bitmap: Bitmap): Boolean {
        updateHealth(FrameSourceType.RGB_CENTER, bitmap)

        if (_activeSource.value != FrameSourceType.RGB_CENTER) {
            // RGB가 연결되었지만 아직 active가 아닐 때 → 자동 전환
            if (!manualOverrideActive() && getHealth(FrameSourceType.RGB_CENTER).isHealthy) {
                switchTo(FrameSourceType.RGB_CENTER, "RGB 카메라 건강 확인 → 자동 전환")
            } else {
                return false
            }
        }

        visionManager.feedExternalFrame(bitmap)
        return true
    }

    /**
     * SLAM 프레임 수신. HardwareManager SLAM 리스너에서 호출.
     * @return true면 이 프레임이 VisionManager에 전달됨
     */
    fun onSlamFrame(bitmap: Bitmap): Boolean {
        updateHealth(FrameSourceType.SLAM_LEFT, bitmap)

        // RGB가 active면 SLAM 비전 프레임은 무시 (기존 동작 유지)
        if (_activeSource.value == FrameSourceType.RGB_CENTER) return false
        if (_activeSource.value != FrameSourceType.SLAM_LEFT) return false

        visionManager.feedExternalFrame(bitmap)
        return true
    }

    /**
     * CameraX 폰 카메라 프레임. VisionManager.analyzer에서 호출 가능.
     * isExternalFrameSourceActive를 대체하는 게이트.
     */
    fun shouldProcessPhoneFrame(): Boolean {
        return _activeSource.value == FrameSourceType.PHONE_CAMERA
    }

    // =========================================================================
    // 소스 전환
    // =========================================================================

    /**
     * 다음 사용 가능한 소스로 순환 (사용자/AI cycleCamera 명령).
     */
    fun cycle() {
        val available = FrameSourceType.entries
            .filter { getHealth(it).isConnected }
            .sortedBy { it.priority }

        if (available.size <= 1) {
            Log.w(TAG, "순환 가능한 소스 없음 (연결: ${available.map { it.label }})")
            return
        }

        val currentIdx = available.indexOfFirst { it == _activeSource.value }
        val next = available[(currentIdx + 1) % available.size]

        // 수동 오버라이드: 30초간 자동 fallback 비활성
        manualOverride = true
        manualOverrideExpiresAt = System.currentTimeMillis() + MANUAL_OVERRIDE_DURATION_MS

        switchTo(next, "사용자 수동 전환")
    }

    /**
     * 특정 소스로 전환.
     */
    fun switchTo(type: FrameSourceType, reason: String) {
        val prev = _activeSource.value
        if (prev == type) return

        _activeSource.value = type
        // VisionManager 연동: PHONE_CAMERA가 아니면 external = true
        visionManager.isExternalFrameSourceActive = (type != FrameSourceType.PHONE_CAMERA)

        // 전환 직후 유예 기간 (카메라 시작까지 시간 필요)
        val gracePeriod = PolicyReader.getLong("camera.switch_grace_period_ms", SWITCH_GRACE_PERIOD_MS)
        switchGraceExpiresAt = System.currentTimeMillis() + gracePeriod

        Log.i(TAG, "소스 전환: ${prev.label} → ${type.label} ($reason) [유예 ${gracePeriod}ms]")
        scope.launch {
            eventBus.publish(XRealEvent.SystemEvent.DebugLog(
                "카메라 소스: ${type.label} ($reason)"
            ))
        }
    }

    // =========================================================================
    // 연결 상태 알림 — HardwareManager에서 호출
    // =========================================================================

    /** 글래스 활성화 시 호출 (SLAM + 잠재적 RGB) */
    fun onGlassesConnected() {
        healthMap[FrameSourceType.SLAM_LEFT] = getHealth(FrameSourceType.SLAM_LEFT).copy(isConnected = true)
        // SLAM 즉시 활성 → RGB 도착하면 자동 전환
        if (_activeSource.value == FrameSourceType.PHONE_CAMERA) {
            switchTo(FrameSourceType.SLAM_LEFT, "XREAL 글래스 연결")
        }
    }

    /** RGB 카메라 시작 성공 시 호출 */
    fun onRgbConnected() {
        healthMap[FrameSourceType.RGB_CENTER] = getHealth(FrameSourceType.RGB_CENTER).copy(isConnected = true)
        Log.i(TAG, "RGB 카메라 연결됨 — 첫 정상 프레임 대기 중")
        // 실제 전환은 onRgbFrame에서 건강 확인 후 수행
    }

    /** 글래스 분리 시 호출 */
    fun onGlassesDisconnected() {
        healthMap[FrameSourceType.SLAM_LEFT] = SourceHealth(FrameSourceType.SLAM_LEFT)
        healthMap[FrameSourceType.RGB_CENTER] = SourceHealth(FrameSourceType.RGB_CENTER)
        manualOverride = false
        switchTo(FrameSourceType.PHONE_CAMERA, "XREAL 글래스 분리")
    }

    /** 원격 카메라 연결/해제 */
    fun onRemoteConnected() {
        healthMap[FrameSourceType.REMOTE_PC] = getHealth(FrameSourceType.REMOTE_PC).copy(isConnected = true, isHealthy = true)
    }
    fun onRemoteDisconnected() {
        healthMap[FrameSourceType.REMOTE_PC] = SourceHealth(FrameSourceType.REMOTE_PC)
        if (_activeSource.value == FrameSourceType.REMOTE_PC) {
            autoFallback("원격 카메라 연결 끊김")
        }
    }

    // =========================================================================
    // 건강 모니터링
    // =========================================================================

    fun start() {
        healthCheckJob = scope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                checkHealth()
            }
        }
        Log.i(TAG, "CameraStreamManager started (건강 모니터링 ${HEALTH_CHECK_INTERVAL_MS}ms 주기)")
    }

    private fun checkHealth() {
        val now = System.currentTimeMillis()
        val active = _activeSource.value
        val health = getHealth(active)

        // 전환 직후 유예 기간 — fallback 하지 않음
        if (now < switchGraceExpiresAt) return

        // 현재 활성 소스가 건강하지 않으면 fallback
        if (health.isConnected && !health.isHealthy && !manualOverrideActive()) {
            autoFallback("${active.label} 건강 이상 (fps=${health.fps}, dark=${health.consecutiveDarkFrames})")
            return
        }

        // 프레임 타임아웃 체크
        if (health.isConnected && health.lastFrameTimeMs > 0) {
            val elapsed = now - health.lastFrameTimeMs
            val timeout = PolicyReader.getLong("camera.no_frame_timeout_ms", NO_FRAME_TIMEOUT_MS)
            if (elapsed > timeout && !manualOverrideActive()) {
                // 건강 상태 갱신
                healthMap[active] = health.copy(isHealthy = false)
                autoFallback("${active.label} 프레임 타임아웃 (${elapsed}ms)")
            }
        }

        // 우선순위 높은 소스가 회복되면 자동 복귀
        if (!manualOverrideActive() && PolicyReader.getBoolean("camera.auto_recover_enabled", true)) {
            val betterSource = FrameSourceType.entries
                .filter { it.priority < active.priority }
                .filter { getHealth(it).isActive }
                .minByOrNull { it.priority }

            if (betterSource != null) {
                switchTo(betterSource, "${betterSource.label} 복구 → 자동 복귀")
            }
        }
    }

    private fun autoFallback(reason: String) {
        val current = _activeSource.value
        val fallback = FrameSourceType.entries
            .filter { it != current && getHealth(it).isConnected }
            .sortedBy { it.priority }
            .firstOrNull { getHealth(it).isHealthy || it == FrameSourceType.PHONE_CAMERA }

        if (fallback != null) {
            switchTo(fallback, "자동 fallback: $reason")
        } else {
            Log.w(TAG, "fallback 대상 없음: $reason")
        }

        // ★ RGB ISOC 실패 시 정책 기반 재시작 — 하드코딩 제거
        if (current == FrameSourceType.RGB_CENTER && rgbRestartCallback != null) {
            val now = System.currentTimeMillis()
            val retryEnabled = PolicyReader.getBoolean("resource.hw_retry_enabled", true)
            val maxRetries = PolicyReader.getInt("resource.hw_max_retries", 3)
            val backoffMs = PolicyReader.getLong("resource.hw_retry_backoff_ms", 5000L)
            val suspendUntil = PolicyReader.getLong("resource.hw_suspend_until_ms", 0L)

            when {
                // 정책으로 재시도 자체가 비활성화됨
                !retryEnabled -> {
                    Log.i(TAG, "RGB 재시도 비활성화 (정책: hw_retry_enabled=false)")
                }
                // AI가 일시 중지 판정 내림 (suspend_until 미래 시각)
                now < suspendUntil -> {
                    Log.i(TAG, "RGB 재시도 일시 중지 (${(suspendUntil - now) / 1000}초 남음)")
                }
                // 정책 허용 횟수 내
                rgbRestartCount < maxRetries && now - lastRgbRestartMs > backoffMs -> {
                    rgbRestartCount++
                    lastRgbRestartMs = now
                    Log.i(TAG, "RGB ISOC 재시작 예약 (${rgbRestartCount}/${maxRetries}, ${backoffMs}ms 후)")
                    scope.launch {
                        delay(backoffMs)
                        healthMap[FrameSourceType.RGB_CENTER] = SourceHealth(FrameSourceType.RGB_CENTER, isConnected = true)
                        try {
                            rgbRestartCallback?.invoke()
                            Log.i(TAG, "RGB ISOC 재시작 완료 (${rgbRestartCount}/${maxRetries})")
                        } catch (e: Exception) {
                            Log.e(TAG, "RGB ISOC 재시작 실패: ${e.message}")
                        }
                    }
                }
                // 한도 초과 → ResourceGuardian에 보고 → AI가 정책 결정
                rgbRestartCount >= maxRetries -> {
                    Log.w(TAG, "RGB 재시도 한도 초과 (${maxRetries}회) — ResourceGuardian에 보고")
                    try {
                        val guardian = org.koin.java.KoinJavaComponent.getKoin()
                            .getOrNull<com.xreal.nativear.resilience.ResourceGuardian>()
                        guardian?.reportAnomaly(
                            "USB_RGB",
                            com.xreal.nativear.resilience.ResourceGuardian.AnomalyType.RETRY_EXHAUSTED,
                            "RGB ISOC 재시도 ${maxRetries}회 실패 — USB 호스트 컨트롤러 한계"
                        )
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun updateHealth(type: FrameSourceType, bitmap: Bitmap) {
        val prev = getHealth(type)
        val now = System.currentTimeMillis()

        // fps 계산
        val counter = fpsCounters.getOrPut(type) { FpsCounter() }
        val fps = counter.tick(now)

        // 다크 프레임 감지 (간단한 체크 — 매 10프레임마다)
        val darkCount = if (counter.totalFrames % 10 == 0L) {
            if (isFrameTooDark(bitmap)) prev.consecutiveDarkFrames + 1 else 0
        } else prev.consecutiveDarkFrames

        val darkLimit = PolicyReader.getInt("camera.dark_frame_limit", DARK_FRAME_LIMIT)
        val fpsThreshold = PolicyReader.getFloat("camera.health_fps_threshold", FPS_THRESHOLD)

        val healthy = fps >= fpsThreshold && darkCount < darkLimit

        healthMap[type] = prev.copy(
            isConnected = true,
            isHealthy = healthy,
            lastFrameTimeMs = now,
            frameCount = prev.frameCount + 1,
            consecutiveDarkFrames = darkCount,
            fps = fps
        )
    }

    private fun isFrameTooDark(bitmap: Bitmap): Boolean {
        // 4x4 샘플링으로 빠른 밝기 체크
        val w = bitmap.width; val h = bitmap.height
        if (w == 0 || h == 0) return true
        var totalLum = 0f
        val samples = 16
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                val px = bitmap.getPixel(col * w / 4, row * h / 4)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                totalLum += 0.299f * r + 0.587f * g + 0.114f * b
            }
        }
        return totalLum / samples < 18f  // BT.601 가중 평균 < 18
    }

    fun getHealth(type: FrameSourceType): SourceHealth =
        healthMap[type] ?: SourceHealth(type)

    fun getAllHealth(): Map<FrameSourceType, SourceHealth> = healthMap.toMap()

    private fun manualOverrideActive(): Boolean =
        manualOverride && System.currentTimeMillis() < manualOverrideExpiresAt

    fun stop() {
        healthCheckJob?.cancel()
        scope.cancel()
    }

    // =========================================================================
    // FPS 카운터
    // =========================================================================

    private class FpsCounter {
        private var windowStart = 0L
        private var windowCount = 0
        var currentFps = 0f; private set
        var totalFrames = 0L; private set

        fun tick(now: Long): Float {
            totalFrames++
            windowCount++
            if (windowStart == 0L) windowStart = now
            val elapsed = now - windowStart
            if (elapsed >= 1000L) {
                currentFps = windowCount * 1000f / elapsed
                windowCount = 0
                windowStart = now
            }
            return currentFps
        }
    }

    companion object {
        // 기본값 — PolicyRegistry에서 오버라이드 가능
        private const val HEALTH_CHECK_INTERVAL_MS = 3000L
        private const val NO_FRAME_TIMEOUT_MS = 5000L
        private const val DARK_FRAME_LIMIT = 30
        private const val FPS_THRESHOLD = 3.0f
        private const val MANUAL_OVERRIDE_DURATION_MS = 30_000L
        private const val SWITCH_GRACE_PERIOD_MS = 10_000L  // 전환 후 10초 유예 (카메라 시작 대기)
    }
}
