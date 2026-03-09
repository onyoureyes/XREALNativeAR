package com.xreal.nativear.resilience

import android.util.Log
import com.xreal.nativear.core.CapabilityTier
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.policy.PolicyReader
import kotlin.coroutines.cancellation.CancellationException
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.edge.EdgeDelegationRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * FailsafeController — 하드웨어 장애·배터리 위기 시 즉각 대응.
 *
 * ## 역할 분리
 * - FailsafeController: 하드코딩 규칙 기반 즉시 대응 (AI 없이 동작)
 * - ResourceRegistry (AI 주도): 상황 인식 → 창의적 리소스 조합 → 사용자 제안
 *
 * ## CapabilityTier 평가 로직
 * ```
 * 배터리 < 10%        → TIER_6_MINIMAL (최우선)
 * 안경+네트+Watch 모두 없음 → TIER_6_MINIMAL
 * 배터리 < 20% or 과열 → TIER_4_LOW_POWER
 * 네트워크 없음+엣지LLM 있음 → TIER_1_NO_NETWORK
 * 안경 없음           → TIER_2_NO_GLASSES
 * Watch 없음          → TIER_3_NO_WATCH
 * 전체 정상           → TIER_0_FULL
 * ```
 *
 * ## 발행 이벤트
 * [XRealEvent.SystemEvent.CapabilityTierChanged] — 모든 컴포넌트가 구독
 */
class FailsafeController(
    private val eventBus: GlobalEventBus,
    private val edgeDelegationRouter: EdgeDelegationRouter? = null,
    private val minimalOperationMode: MinimalOperationMode? = null
) {
    companion object {
        private const val TAG = "FailsafeController"
        private val BATTERY_CRITICAL: Int get() = PolicyReader.getInt("resilience.battery_critical_percent", 10)    // 배터리 위험 (%)
        private val BATTERY_LOW: Int get() = PolicyReader.getInt("resilience.battery_low_percent", 20)         // 배터리 낮음 (%)
        private val THERMAL_SEVERE: Int get() = PolicyReader.getInt("resilience.thermal_severe_level", 4)       // 과열 임계값 (ThermalStatus)
    }

    @Volatile
    var currentTier: CapabilityTier = CapabilityTier.TIER_0_FULL
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            eventBus.events.collect { event ->
                try {
                    when (event) {
                        is XRealEvent.SystemEvent.DeviceHealthUpdated -> {
                            val newTier = evaluateTier(event)
                            if (newTier != currentTier) {
                                val previous = currentTier
                                currentTier = newTier
                                applyTier(newTier, previous, event)
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
        Log.i(TAG, "FailsafeController 시작")
    }

    fun stop() {
        Log.i(TAG, "FailsafeController 종료")
    }

    /**
     * DeviceHealthUpdated 이벤트에서 CapabilityTier 결정.
     */
    fun evaluateTier(report: XRealEvent.SystemEvent.DeviceHealthUpdated): CapabilityTier {
        // 배터리 위험 → 최소 모드
        if (report.batteryPercent < BATTERY_CRITICAL) return CapabilityTier.TIER_6_MINIMAL

        // 안경 + 네트워크 + Watch 모두 없음 → 최소 모드
        if (!report.glassesConnected && !report.networkOnline && !report.watchConnected) {
            return CapabilityTier.TIER_6_MINIMAL
        }

        // 배터리 낮음 or 과열 → 절전
        if (report.batteryPercent < BATTERY_LOW || report.thermalStatus >= THERMAL_SEVERE) {
            return CapabilityTier.TIER_4_LOW_POWER
        }

        // 네트워크 없음 + 엣지 LLM 준비됨 → 엣지 전용
        if (!report.networkOnline && report.edgeLlmReady) {
            return CapabilityTier.TIER_1_NO_NETWORK
        }

        // 안경 없음 → 폰 화면 모드
        if (!report.glassesConnected) return CapabilityTier.TIER_2_NO_GLASSES

        // Watch 없음 → 생체 신호 제한
        if (!report.watchConnected) return CapabilityTier.TIER_3_NO_WATCH

        // 전체 정상
        return CapabilityTier.TIER_0_FULL
    }

    /**
     * 티어 변경 시 즉각 대응 (ResourceRegistry를 통해 리소스 재배치).
     */
    private fun applyTier(
        tier: CapabilityTier,
        previous: CapabilityTier,
        report: XRealEvent.SystemEvent.DeviceHealthUpdated
    ) {
        Log.w(TAG, "CapabilityTier 변경: $previous → $tier")

        val reason = buildReason(tier, report)

        when (tier) {
            CapabilityTier.TIER_1_NO_NETWORK -> {
                // 엣지 AI 자동 전환
                edgeDelegationRouter?.recordServerFailure()
                scope.launch {
                    eventBus.publish(
                        XRealEvent.SystemEvent.ResourceActivated(
                            resourceType = "COMPUTE_EDGE_1B",
                            displayName = "엣지 AI 1B",
                            activatedBy = "failsafe",
                            isActive = true
                        )
                    )
                }
            }

            CapabilityTier.TIER_2_NO_GLASSES -> {
                // 안경 없음 → 폰 카메라 + 폰 화면 전환
                scope.launch {
                    eventBus.publish(
                        XRealEvent.SystemEvent.ResourceActivated(
                            resourceType = "CAMERA_FOLD4_REAR",
                            displayName = "폴드4 후면 카메라",
                            activatedBy = "failsafe",
                            isActive = true
                        )
                    )
                    eventBus.publish(
                        XRealEvent.SystemEvent.ResourceActivated(
                            resourceType = "DISPLAY_FOLD4_SCREEN",
                            displayName = "폴드4 화면 패널",
                            activatedBy = "failsafe",
                            isActive = true
                        )
                    )
                    eventBus.publish(XRealEvent.ActionRequest.SpeakTTS("안경 연결 해제 — 폰 화면 모드 전환"))
                }
            }

            CapabilityTier.TIER_4_LOW_POWER -> {
                // 배터리 절약: RGB 카메라 비활성화
                scope.launch {
                    eventBus.publish(
                        XRealEvent.SystemEvent.ResourceActivated(
                            resourceType = "CAMERA_XREAL_RGB",
                            displayName = "XREAL RGB 카메라",
                            activatedBy = "failsafe",
                            isActive = false
                        )
                    )
                    // Fold 3 연결되어 있으면 연산 오프로드
                    if (report.fold3Connected) {
                        eventBus.publish(
                            XRealEvent.SystemEvent.ResourceActivated(
                                resourceType = "COMPUTE_COMPANION_LLM",
                                displayName = "컴패니언 엣지 AI",
                                activatedBy = "failsafe",
                                isActive = true
                            )
                        )
                    }
                    eventBus.publish(XRealEvent.ActionRequest.SpeakTTS("배터리 부족 — 절전 모드"))
                }
            }

            CapabilityTier.TIER_5_EDGE_ONLY -> {
                // 서버 AI 차단, 엣지 LLM만
                edgeDelegationRouter?.enableForcedEdge()
                scope.launch {
                    eventBus.publish(XRealEvent.ActionRequest.SpeakTTS("서버 연결 불가 — 엣지 AI 전용 모드"))
                }
            }

            CapabilityTier.TIER_6_MINIMAL -> {
                // 최소 모드 — MinimalOperationMode가 나머지 처리
                minimalOperationMode?.activate()
            }

            CapabilityTier.TIER_0_FULL, CapabilityTier.TIER_3_NO_WATCH -> {
                // TIER_0: 정상 복귀 — 이전에 엣지 강제였으면 해제
                if (previous >= CapabilityTier.TIER_5_EDGE_ONLY) {
                    edgeDelegationRouter?.disableForcedEdge()
                }
                // TIER_6에서 복귀 시 MinimalMode 비활성화
                if (previous == CapabilityTier.TIER_6_MINIMAL) {
                    minimalOperationMode?.deactivate()
                }
                if (previous != tier) {
                    scope.launch {
                        eventBus.publish(XRealEvent.ActionRequest.SpeakTTS("시스템 복구 — ${tier.description}"))
                    }
                }
            }
        }

        // CapabilityTierChanged 이벤트 발행 (모든 컴포넌트에 알림)
        scope.launch {
            eventBus.publish(
                XRealEvent.SystemEvent.CapabilityTierChanged(
                    tier = tier,
                    previousTier = previous,
                    reason = reason
                )
            )
        }
    }

    private fun buildReason(tier: CapabilityTier, report: XRealEvent.SystemEvent.DeviceHealthUpdated): String {
        return when (tier) {
            CapabilityTier.TIER_6_MINIMAL -> if (report.batteryPercent < BATTERY_CRITICAL)
                "battery_critical_${report.batteryPercent}%" else "all_devices_disconnected"
            CapabilityTier.TIER_4_LOW_POWER -> if (report.thermalStatus >= THERMAL_SEVERE)
                "thermal_severe" else "battery_low_${report.batteryPercent}%"
            CapabilityTier.TIER_1_NO_NETWORK -> "network_offline"
            CapabilityTier.TIER_2_NO_GLASSES -> "glasses_disconnected"
            CapabilityTier.TIER_3_NO_WATCH -> "watch_disconnected"
            CapabilityTier.TIER_5_EDGE_ONLY -> "server_ai_unavailable"
            CapabilityTier.TIER_0_FULL -> "all_systems_nominal"
        }
    }
}
