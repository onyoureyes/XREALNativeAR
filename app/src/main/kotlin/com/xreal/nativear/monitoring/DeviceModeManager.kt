package com.xreal.nativear.monitoring

import android.util.Log
import com.xreal.nativear.cadence.CadenceConfig
import com.xreal.nativear.cadence.CadenceProfile
import kotlin.coroutines.cancellation.CancellationException
import com.xreal.nativear.core.DeviceMode
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.ResourceSeverity
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * DeviceModeManager — 갤럭시 폴드4 운영 모드 관리.
 *
 * ## 모드별 활성 파이프라인
 * | 모드       | AR 안경 | SLAM/VIO | RGB카메라 | 폰카메라 | 비전AI | 마이크 |
 * |-----------|--------|---------|---------|---------|-------|-------|
 * | FULL_AR   |  ✅    |   ✅    |   ✅    |   ❌   |  ✅  |  ✅   |
 * | HUD_ONLY  |  ✅    |  SLAM만 |   ❌    |   ✅   |  ✅  |  ✅   |
 * | PHONE_CAM |  ❌    |   ❌    |   ❌    |   ✅   |  ✅  |  ✅   |
 * | AUDIO_ONLY|  ❌    |   ❌    |   ❌    |   ❌   |  ❌  |  ✅   |
 *
 * ## 자동 전환 규칙 (ResourceAlert 기반)
 * - CRITICAL + FULL_AR  → HUD_ONLY 전환
 * - CRITICAL + HUD_ONLY → PHONE_CAM 전환
 * - CRITICAL + PHONE_CAM → AUDIO_ONLY 전환
 * - NORMAL 5분 이상 → 이전 모드로 복귀 (업그레이드)
 *
 * ## CadenceConfig 프리셋
 * - FULL_AR: 기본값 (2초 OCR, 2초 감지, 0.5Hz 포즈)
 * - HUD_ONLY: 느린 감지 (4초 OCR, 4초 감지, 프레임스킵 3)
 * - PHONE_CAM: 중간 (3초 OCR, 3초 감지)
 * - AUDIO_ONLY: 비전 비활성 (OCR/감지 30초 — 실질적 비활성)
 */
class DeviceModeManager(
    private val eventBus: GlobalEventBus,
    private val cadenceConfig: CadenceConfig,
    private val resourceMonitor: ResourceMonitor
) {
    private val TAG = "DeviceModeManager"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var eventJob: Job? = null

    @Volatile
    var currentMode: DeviceMode = DeviceMode.FULL_AR
        private set

    // 자동 다운그레이드 후 복귀를 위한 이전 모드 기록
    private var previousMode: DeviceMode? = null
    private var lastNormalTimestamp = System.currentTimeMillis()
    private var consecutiveCriticalCount = 0

    // 사용자가 수동으로 설정한 모드 (자동 복귀 제한)
    private var userForcedMode: DeviceMode? = null

    // 모드별 CadenceProfile 프리셋
    private val cadencePresets = mapOf(
        DeviceMode.FULL_AR to CadenceProfile(
            ocrIntervalMs     = 2000L,
            detectIntervalMs  = 2000L,
            poseIntervalMs    = 500L,
            frameSkip         = 2,
            slamFrameInterval = 3,
            rgbFrameInterval  = 3,
            visualEmbeddingIntervalMs = 5000L
        ),
        DeviceMode.HUD_ONLY to CadenceProfile(
            ocrIntervalMs     = 4000L,   // OCR 절반 빈도
            detectIntervalMs  = 4000L,   // 감지 절반 빈도
            poseIntervalMs    = 1000L,   // 포즈 2배 느리게
            frameSkip         = 3,       // 프레임 더 많이 건너뜀
            slamFrameInterval = 3,       // SLAM 활성 유지
            rgbFrameInterval  = 5,       // RGB 카메라 비활성에 가깝게
            visualEmbeddingIntervalMs = 10000L
        ),
        DeviceMode.PHONE_CAM to CadenceProfile(
            ocrIntervalMs     = 3000L,
            detectIntervalMs  = 3000L,
            poseIntervalMs    = 1000L,
            frameSkip         = 2,
            slamFrameInterval = 99,      // SLAM 실질적 비활성 (안경 없음)
            rgbFrameInterval  = 99,      // RGB 카메라 비활성
            visualEmbeddingIntervalMs = 8000L
        ),
        DeviceMode.AUDIO_ONLY to CadenceProfile(
            ocrIntervalMs     = 30000L,  // 비전 실질적 비활성
            detectIntervalMs  = 30000L,
            poseIntervalMs    = 30000L,
            frameSkip         = 10,
            slamFrameInterval = 99,
            rgbFrameInterval  = 99,
            visualEmbeddingIntervalMs = 60000L
        )
    )

    fun start() {
        Log.i(TAG, "DeviceModeManager started (초기 모드: $currentMode)")
        // 초기 CadenceProfile 적용
        applyModeProfile(currentMode)

        eventJob = scope.launch {
            eventBus.events.collect { event ->
                try {
                    when (event) {
                        is XRealEvent.SystemEvent.ResourceAlert -> handleResourceAlert(event)
                        else -> {}
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "이벤트 처리 오류 (루프 유지됨): ${event::class.simpleName} — ${e.message}", e)
                }
            }
        }
    }

    fun stop() {
        eventJob?.cancel()
        Log.i(TAG, "DeviceModeManager stopped")
    }

    /**
     * 수동 모드 전환 (사용자 또는 DirectiveConsumer에서 호출).
     * 수동 전환 후에는 자동 복귀를 하지 않음.
     */
    fun switchMode(newMode: DeviceMode, reason: String = "수동 전환") {
        if (newMode == currentMode) return
        val prev = currentMode
        userForcedMode = newMode
        applyModeSwitch(prev, newMode, reason)
    }

    /**
     * 수동 전환 해제 — 자동 관리 모드로 복귀.
     */
    fun clearForcedMode() {
        userForcedMode = null
        Log.i(TAG, "수동 모드 설정 해제 — 자동 관리 활성화")
    }

    private fun handleResourceAlert(alert: XRealEvent.SystemEvent.ResourceAlert) {
        when (alert.severity) {
            ResourceSeverity.CRITICAL -> {
                consecutiveCriticalCount++
                lastNormalTimestamp = 0L

                // 사용자가 수동 설정한 경우 자동 다운그레이드 건너뜀
                if (userForcedMode != null) {
                    Log.d(TAG, "수동 모드 설정 중 — 자동 다운그레이드 건너뜀 (${alert.toLogString()})")
                    return
                }

                // 연속 2회 이상 CRITICAL이면 모드 다운그레이드
                if (consecutiveCriticalCount >= 2) {
                    val downgraded = downgradeMode(currentMode)
                    if (downgraded != currentMode) {
                        val prev = currentMode
                        previousMode = prev
                        applyModeSwitch(prev, downgraded, "CPU/온도 위험: ${alert.toLogString()}")
                        consecutiveCriticalCount = 0
                    }
                }
            }
            ResourceSeverity.WARNING -> {
                consecutiveCriticalCount = 0
                // WARNING만으로는 모드 전환 안 함 — StrategistService가 케이던스 절감 지시
            }
            ResourceSeverity.NORMAL -> {
                consecutiveCriticalCount = 0
                val now = System.currentTimeMillis()
                if (lastNormalTimestamp == 0L) lastNormalTimestamp = now

                // NORMAL이 5분 이상 지속되면 이전 모드로 복귀 (사용자 강제 없는 경우)
                if (userForcedMode == null && previousMode != null) {
                    val normalDuration = now - lastNormalTimestamp
                    if (normalDuration >= 5 * 60_000L) {
                        val restore = previousMode!!
                        if (restore.ordinal < currentMode.ordinal) { // 상위 모드로만 복귀
                            val prev = currentMode
                            previousMode = null
                            applyModeSwitch(prev, restore, "자원 정상화 5분 경과 — 복귀")
                        }
                    }
                }
            }
        }
    }

    private fun downgradeMode(mode: DeviceMode): DeviceMode = when (mode) {
        DeviceMode.FULL_AR   -> DeviceMode.HUD_ONLY
        DeviceMode.HUD_ONLY  -> DeviceMode.PHONE_CAM
        DeviceMode.PHONE_CAM -> DeviceMode.AUDIO_ONLY
        DeviceMode.AUDIO_ONLY -> DeviceMode.AUDIO_ONLY // 최하위 모드
    }

    private fun applyModeSwitch(prev: DeviceMode, new: DeviceMode, reason: String) {
        currentMode = new
        Log.i(TAG, "🔄 모드 전환: $prev → $new (이유: $reason)")
        applyModeProfile(new)
        scope.launch {
            eventBus.publish(XRealEvent.SystemEvent.DeviceModeChanged(
                previousMode = prev,
                newMode = new,
                reason = reason
            ))
        }
    }

    private fun applyModeProfile(mode: DeviceMode) {
        cadencePresets[mode]?.let { profile ->
            cadenceConfig.applyProfile(profile)
            Log.d(TAG, "CadenceProfile 적용: $mode (OCR:${profile.ocrIntervalMs}ms, 감지:${profile.detectIntervalMs}ms)")
        }
    }

    private fun XRealEvent.SystemEvent.ResourceAlert.toLogString() =
        "CPU:${cpuPercent}% 온도:${batteryTempC}°C [$severity]"

    fun getStatusSummary(): String {
        val snap = resourceMonitor.getSnapshot()
        return "모드:$currentMode | ${snap.toLogString()} | 자동:${userForcedMode == null}"
    }
}
