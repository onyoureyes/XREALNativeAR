package com.xreal.nativear.resource

import com.xreal.nativear.core.XRealLogger
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * ResourceRegistry — 모든 리소스 상태 중앙 관리소.
 *
 * ## 역할
 * - 리소스 가용 여부 추적 (DeviceHealthUpdated 이벤트 구독)
 * - AI 도구 `list_resources` 응답: JSON 형식 리소스 목록
 * - AI 요청 `activate_resource`: 저전력은 자동, 고전력은 사용자 승인 요청
 * - FailsafeController의 즉각 대응 리소스 배치
 *
 * ## VisionManager 연결
 * CAMERA 리소스 활성화 시 ResourceActivated 이벤트 발행
 * → InputCoordinator가 구독하여 VisionManager.isExternalFrameSourceActive 설정
 *
 * ## 창고 개념
 * AI가 listResources()로 "어떤 재료가 있는지" 확인 후
 * proposeResourceCombo()나 activate()로 "필요한 재료를 꺼낸다"
 */
class ResourceRegistry(
    private val eventBus: GlobalEventBus
) {
    companion object {
        private const val TAG = "ResourceRegistry"
    }

    data class ResourceStatus(
        val type: ResourceType,
        val isAvailable: Boolean = false,
        val isActive: Boolean = false
    )

    private val statusMap = ConcurrentHashMap<ResourceType, ResourceStatus>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 외부에서 컴패니언 연결 상태 업데이트 (CompanionDeviceManager에서 호출)
    @Volatile var companionConnected = false
    @Volatile var companionRamAvailMb = 0

    // E2B 모델 준비 상태 (EdgeModelStateChanged 이벤트로 업데이트)
    @Volatile private var e2bReady = false

    init {
        // 기본 초기 상태 설정 (앱 시작 시)
        ResourceType.values().forEach { type ->
            statusMap[type] = ResourceStatus(
                type = type,
                isAvailable = getInitialAvailability(type),
                isActive = getInitialActive(type)
            )
        }
    }

    fun start() {
        // DeviceHealthUpdated → 리소스 가용 상태 업데이트
        scope.launch {
            eventBus.events.collect { event ->
                try {
                    when (event) {
                        is XRealEvent.SystemEvent.DeviceHealthUpdated -> updateFromHealth(event)
                        is XRealEvent.SystemEvent.EdgeModelStateChanged -> updateFromEdgeModel(event)
                        else -> {}
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    XRealLogger.impl.e(TAG, "이벤트 처리 오류: ${e.message}", e)
                }
            }
        }
        XRealLogger.impl.i(TAG, "ResourceRegistry 시작 — ${statusMap.size}개 리소스 등록")
    }

    fun stop() {
        XRealLogger.impl.i(TAG, "ResourceRegistry 종료")
    }

    // =========================================================================
    // AI 도구 인터페이스
    // =========================================================================

    /**
     * AI `list_resources` 도구 응답: 리소스 목록 JSON.
     * @param category null이면 전체, "camera"/"audio"/"compute"/"display"/"sensor"로 필터
     */
    fun listResources(category: String? = null): String {
        val filtered = statusMap.values
            .filter { status ->
                if (category == null) true
                else when (category.lowercase()) {
                    "camera" -> status.type.name.startsWith("CAMERA_")
                    "audio", "mic" -> status.type.name.startsWith("MIC_")
                    "compute" -> status.type.name.startsWith("COMPUTE_")
                    "display" -> status.type.name.startsWith("DISPLAY_")
                    "sensor" -> status.type.name.startsWith("SENSOR_")
                    else -> true
                }
            }
            .sortedBy { it.type.ordinal }

        val items = filtered.joinToString(",\n  ") { status ->
            status.type.toJson(status.isAvailable, status.isActive)
        }
        return "[\n  $items\n]"
    }

    /**
     * 리소스 활성화.
     * - LOW powerCost + available → 자동 활성화
     * - MEDIUM/HIGH powerCost → 사용자 승인 필요 알림
     * - unavailable → 실패 (activationHint 포함)
     */
    fun activate(type: ResourceType, requestedBy: String): ActivationResult {
        val current = statusMap[type] ?: return ActivationResult.Failed("알 수 없는 리소스: $type")

        if (!current.isAvailable) {
            val hint = type.activationHint ?: "해당 리소스를 사용할 수 없습니다"
            return ActivationResult.NeedsUserAction(hint)
        }

        if (current.isActive) {
            return ActivationResult.Success  // 이미 활성
        }

        // 고전력 리소스는 사용자 승인 요청
        if (type.powerCost == PowerCost.HIGH && requestedBy == "ai_request") {
            return ActivationResult.NeedsUserApproval(
                "\"${type.displayName}\"을 활성화하면 배터리를 많이 사용합니다. 허용하시겠어요?"
            )
        }

        // 컴패니언 필요 리소스 체크
        if (type.requiresCompanionDevice && !companionConnected) {
            return ActivationResult.NeedsUserAction(
                type.activationHint ?: "컴패니언 기기 연결이 필요합니다"
            )
        }

        // 네트워크 필요 리소스 체크
        if (type.requiresNetwork) {
            // 네트워크 상태는 이미 isAvailable로 반영됨
        }

        // 활성화 처리
        setActive(type, true, requestedBy)
        return ActivationResult.Success
    }

    fun deactivate(type: ResourceType) {
        setActive(type, false, "system")
    }

    fun isActive(type: ResourceType): Boolean = statusMap[type]?.isActive == true

    fun isAvailable(type: ResourceType): Boolean = statusMap[type]?.isAvailable == true

    fun getStatus(type: ResourceType): ResourceStatus? = statusMap[type]

    // =========================================================================
    // 내부 상태 관리
    // =========================================================================

    private fun setActive(type: ResourceType, active: Boolean, requestedBy: String) {
        val current = statusMap[type] ?: return
        statusMap[type] = current.copy(isActive = active)
        XRealLogger.impl.i(TAG, "리소스 ${if (active) "활성화" else "비활성화"}: ${type.displayName} (by: $requestedBy)")

        scope.launch {
            eventBus.publish(
                XRealEvent.SystemEvent.ResourceActivated(
                    resourceType = type.name,
                    displayName = type.displayName,
                    activatedBy = requestedBy,
                    isActive = active
                )
            )
        }
    }

    private fun updateFromHealth(report: XRealEvent.SystemEvent.DeviceHealthUpdated) {
        // XREAL 카메라
        updateAvailability(ResourceType.CAMERA_XREAL_OV580, report.glassesConnected)
        updateAvailability(ResourceType.CAMERA_XREAL_RGB, report.glassesConnected)
        updateAvailability(ResourceType.SENSOR_XREAL_IMU, report.glassesConnected)

        // Watch 센서
        updateAvailability(ResourceType.SENSOR_WATCH_HR, report.watchConnected)
        updateAvailability(ResourceType.SENSOR_WATCH_HRV, report.watchConnected)
        updateAvailability(ResourceType.SENSOR_WATCH_SPO2, report.watchConnected)

        // 네트워크 의존
        updateAvailability(ResourceType.COMPUTE_SERVER_AI, report.networkOnline)
        updateAvailability(ResourceType.COMPUTE_REMOTE_LLM, report.networkOnline)  // Tailscale 연결 시 가용
        updateAvailability(ResourceType.CAMERA_NETWORK_ENDPOINT, report.networkOnline)

        // 엣지 LLM (1B: DeviceHealthUpdated, E2B: EdgeModelStateChanged으로 별도 추적)
        updateAvailability(ResourceType.COMPUTE_EDGE_1B, report.edgeLlmReady)
        updateAvailability(ResourceType.COMPUTE_EDGE_E2B, e2bReady)

        // 컴패니언
        val companionAvail = report.fold3Connected
        updateAvailability(ResourceType.CAMERA_COMPANION, companionAvail)
        updateAvailability(ResourceType.MIC_COMPANION_BEAM, companionAvail)
        updateAvailability(ResourceType.COMPUTE_COMPANION_LLM,
            companionAvail && report.fold3RamAvailMb > 1500)
        updateAvailability(ResourceType.DISPLAY_COMPANION_SCREEN, companionAvail)

        // 폰 카메라/화면은 항상 가용
        updateAvailability(ResourceType.CAMERA_FOLD4_REAR, true)
        updateAvailability(ResourceType.CAMERA_FOLD4_FRONT, true)
        updateAvailability(ResourceType.DISPLAY_FOLD4_SCREEN, true)
        updateAvailability(ResourceType.MIC_FOLD4, true)
        updateAvailability(ResourceType.SENSOR_PHONE_GPS, true)

        // XREAL HUD - 안경 연결 시
        updateAvailability(ResourceType.DISPLAY_XREAL_HUD, report.glassesConnected)
    }

    private fun updateFromEdgeModel(event: XRealEvent.SystemEvent.EdgeModelStateChanged) {
        when (event.tier) {
            "EMERGENCY_E2B" -> {
                e2bReady = event.state == "READY"
                updateAvailability(ResourceType.COMPUTE_EDGE_E2B, e2bReady)
                XRealLogger.impl.i(TAG, "E2B 모델 상태 변경 → ${event.state}: COMPUTE_EDGE_E2B 가용=${e2bReady}")
            }
            "AGENT_1B" -> {
                // 1B 모델은 DeviceHealthUpdated.edgeLlmReady와 중복이지만 즉시 반영
                val ready = event.state == "READY"
                updateAvailability(ResourceType.COMPUTE_EDGE_1B, ready)
                XRealLogger.impl.i(TAG, "1B 모델 상태 변경 → ${event.state}: COMPUTE_EDGE_1B 가용=$ready")
            }
            // ROUTER_270M은 별도 리소스 타입 없음 (내부 라우팅 전용)
            else -> {}
        }
    }

    private fun updateAvailability(type: ResourceType, available: Boolean) {
        val current = statusMap[type] ?: return
        if (current.isAvailable != available) {
            statusMap[type] = current.copy(
                isAvailable = available,
                // 비가용 상태가 되면 자동으로 비활성화
                isActive = if (!available) false else current.isActive
            )
            if (!available && current.isActive) {
                XRealLogger.impl.w(TAG, "리소스 비가용으로 자동 비활성화: ${type.displayName}")
                scope.launch {
                    eventBus.publish(
                        XRealEvent.SystemEvent.ResourceActivated(
                            resourceType = type.name,
                            displayName = type.displayName,
                            activatedBy = "unavailable",
                            isActive = false
                        )
                    )
                }
            }
        }
    }

    private fun getInitialAvailability(type: ResourceType): Boolean {
        // 초기에는 폰 기본 리소스만 가용
        return when (type) {
            ResourceType.CAMERA_FOLD4_REAR, ResourceType.CAMERA_FOLD4_FRONT,
            ResourceType.DISPLAY_FOLD4_SCREEN, ResourceType.MIC_FOLD4,
            ResourceType.SENSOR_PHONE_GPS -> true
            else -> false
        }
    }

    private fun getInitialActive(type: ResourceType): Boolean {
        // 초기 활성 상태
        return when (type) {
            ResourceType.CAMERA_XREAL_OV580 -> true  // XREAL이 연결되면 기본 활성
            ResourceType.MIC_FOLD4 -> true
            ResourceType.DISPLAY_XREAL_HUD -> true
            ResourceType.COMPUTE_SERVER_AI -> true
            else -> false
        }
    }
}

sealed class ActivationResult {
    object Success : ActivationResult()
    data class NeedsUserApproval(val reason: String) : ActivationResult()
    data class NeedsUserAction(val hint: String) : ActivationResult()
    data class Failed(val reason: String) : ActivationResult()
}
