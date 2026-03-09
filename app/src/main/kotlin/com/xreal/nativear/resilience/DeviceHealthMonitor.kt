package com.xreal.nativear.resilience

import android.util.Log
import com.xreal.nativear.HardwareManager
import com.xreal.nativear.VisionManager
import kotlin.coroutines.cancellation.CancellationException
import com.xreal.nativear.core.CapabilityTier
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.policy.PolicyReader
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.edge.ConnectivityMonitor
import com.xreal.nativear.edge.EdgeModelManager
import com.xreal.nativear.edge.EdgeModelTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * DeviceHealthMonitor — 30초 주기 하드웨어 상태 통합 모니터.
 *
 * ## 모니터링 항목
 * - XREAL 안경: MCU heartbeat 간격 (HardwareManager.lastMcuHeartbeatMs)
 * - XREAL 카메라: OV580 마지막 프레임 수신 시각 (VisionManager.lastFrameReceivedMs)
 * - Galaxy Watch: WatchHeartRate 이벤트 타임스탬프 갭
 * - 엣지 LLM: EdgeModelManager.isReady(AGENT_1B)
 * - 네트워크: ConnectivityMonitor.isOnline
 * - 배터리: ResourceAlert 이벤트에서 수신
 * - Fold 3 컴패니언: CompanionDeviceManager (존재하면 주입)
 *
 * ## 발행 이벤트
 * [XRealEvent.SystemEvent.DeviceHealthUpdated] — FailsafeController가 구독
 */
class DeviceHealthMonitor(
    private val eventBus: GlobalEventBus,
    private val hardwareManager: HardwareManager,
    private val visionManager: VisionManager,
    private val edgeModelManager: EdgeModelManager,
    private val connectivityMonitor: ConnectivityMonitor
) {
    companion object {
        private const val TAG = "DeviceHealthMonitor"
        private val POLL_INTERVAL_MS: Long get() = PolicyReader.getLong("resilience.health_poll_interval_ms", 30_000L)   // 30초 주기
        private val GLASSES_HEARTBEAT_TIMEOUT_MS: Long get() = PolicyReader.getLong("resilience.glasses_heartbeat_timeout_ms", 3000L)   // MCU heartbeat 3초 없으면 연결 해제
        private val GLASSES_FRAME_TIMEOUT_MS: Long get() = PolicyReader.getLong("resilience.glasses_frame_timeout_ms", 5000L)       // OV580 프레임 5초 없으면 카메라 다운
        private val WATCH_DATA_TIMEOUT_MS: Long get() = PolicyReader.getLong("resilience.watch_data_timeout_ms", 30_000L)        // Watch HR 30초 없으면 연결 해제
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Watch heartbeat 타임스탬프 추적
    @Volatile private var lastWatchHrMs = 0L
    @Volatile private var lastWatchHrBpm = 0f

    // 배터리 상태 (ResourceAlert에서 갱신)
    @Volatile private var batteryPercent = 100
    @Volatile private var isCharging = false
    @Volatile private var thermalStatus = 0

    // Fold 3 상태 (CompanionDeviceManager에서 갱신 - 선택적 주입)
    @Volatile var fold3Connected = false
    @Volatile var fold3RamAvailMb = 0

    fun start() {
        // Watch HR 타임스탬프 추적
        scope.launch {
            eventBus.events.collect { event ->
                try {
                    when (event) {
                        is XRealEvent.PerceptionEvent.WatchHeartRate -> {
                            lastWatchHrMs = event.timestamp
                            lastWatchHrBpm = event.bpm
                        }
                        is XRealEvent.SystemEvent.ResourceAlert -> {
                            thermalStatus = when {
                                event.batteryTempC > 50f -> 5   // SHUTDOWN
                                event.batteryTempC > 46f -> 4   // SEVERE
                                event.batteryTempC > 42f -> 3   // MODERATE
                                else -> 0
                            }
                        }
                        else -> {}
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "이벤트 처리 오류 (루프 유지됨): ${event::class.simpleName} — ${e.message}", e)
                }
            }
        }

        // 배터리 정보 별도 모니터링
        scope.launch {
            eventBus.events.collect { event ->
                try {
                    when (event) {
                        is XRealEvent.SystemEvent.BatteryLevel -> {
                            batteryPercent = event.percent
                        }
                        else -> {}
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "배터리 모니터링 오류 (루프 유지됨): ${e.message}", e)
                }
            }
        }

        // 30초 주기 헬스 체크
        scope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                assessHealth()
            }
        }
        Log.i(TAG, "DeviceHealthMonitor 시작 (30s 주기)")
    }

    fun stop() {
        Log.i(TAG, "DeviceHealthMonitor 종료")
    }

    /**
     * 수동 즉시 평가 (FailsafeController나 테스트에서 호출 가능).
     */
    fun assessNow() {
        scope.launch { assessHealth() }
    }

    // =========================================================================
    // 내부 헬스 평가
    // =========================================================================

    private suspend fun assessHealth() {
        val now = System.currentTimeMillis()

        // 안경 연결 상태
        val glassesConnected = (now - hardwareManager.lastMcuHeartbeatMs) < GLASSES_HEARTBEAT_TIMEOUT_MS
        val glassesFrameRateFps = visionManager.currentFrameRateFps

        // Watch 연결 상태
        val watchConnected = lastWatchHrMs > 0 && (now - lastWatchHrMs) < WATCH_DATA_TIMEOUT_MS
        val watchHrValid = lastWatchHrBpm in 30f..220f

        // 엣지 LLM 준비 상태
        val edgeLlmReady = edgeModelManager.isReady(EdgeModelTier.AGENT_1B)

        // 네트워크
        val networkOnline = connectivityMonitor.isOnline
        val networkType = connectivityMonitor.networkType

        Log.d(TAG, "헬스 체크 — 안경:$glassesConnected, Watch:$watchConnected, " +
                "네트워크:$networkOnline($networkType), 배터리:${batteryPercent}%, " +
                "엣지LLM:$edgeLlmReady, Fold3:$fold3Connected")

        eventBus.publish(
            XRealEvent.SystemEvent.DeviceHealthUpdated(
                glassesConnected = glassesConnected,
                glassesFrameRateFps = glassesFrameRateFps,
                watchConnected = watchConnected,
                watchHrValid = watchHrValid,
                networkOnline = networkOnline,
                networkType = networkType,
                batteryPercent = batteryPercent,
                isCharging = isCharging,
                thermalStatus = thermalStatus,
                edgeLlmReady = edgeLlmReady,
                fold3Connected = fold3Connected,
                fold3RamAvailMb = fold3RamAvailMb
            )
        )
    }
}
