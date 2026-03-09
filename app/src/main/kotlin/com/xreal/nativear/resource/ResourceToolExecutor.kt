package com.xreal.nativear.resource

import android.util.Log
import com.xreal.nativear.tools.IToolExecutor
import com.xreal.nativear.tools.ToolResult

/**
 * ResourceToolExecutor — AI(Gemini)에게 노출되는 리소스 관리 도구.
 *
 * ## 제공 도구
 * - `list_resources`: 현재 사용 가능한 리소스 목록 + 상태 JSON 반환
 * - `activate_resource`: 리소스 활성화 (저전력=자동, 고전력=사용자 승인 요청)
 * - `deactivate_resource`: 리소스 비활성화
 * - `propose_resource_combo`: AI가 상황에 맞는 조합을 사용자에게 제안
 * - `scan_companion_devices`: 주변 Nearby 기기 스캔
 *
 * ## AI 활용 시나리오
 * Gemini가 상황을 인식한 후:
 * 1. `list_resources()` → 가용 리소스 확인
 * 2. 최적 조합 선택 후 `propose_resource_combo()` → 사용자 제안
 * 3. 사용자 승인 → 각 리소스 `activate_resource()` 순차 호출
 */
class ResourceToolExecutor(
    private val resourceRegistry: ResourceRegistry,
    private val resourceProposalManager: ResourceProposalManager
) : IToolExecutor {

    companion object {
        private const val TAG = "ResourceToolExecutor"
    }

    override val supportedTools = setOf(
        "list_resources",
        "activate_resource",
        "deactivate_resource",
        "propose_resource_combo",
        "scan_companion_devices"
    )

    override suspend fun execute(name: String, args: Map<String, Any?>): ToolResult {
        return when (name) {
            "list_resources" -> handleListResources(args)
            "activate_resource" -> handleActivateResource(args)
            "deactivate_resource" -> handleDeactivateResource(args)
            "propose_resource_combo" -> handleProposeResourceCombo(args)
            "scan_companion_devices" -> handleScanCompanionDevices()
            else -> ToolResult(false, "지원하지 않는 도구: $name")
        }
    }

    // =========================================================================
    // 도구 핸들러
    // =========================================================================

    /**
     * list_resources: 전체 또는 카테고리별 리소스 목록 + 상태 JSON 반환.
     * AI가 리소스 조합 선택 전에 호출.
     *
     * args:
     *   - category (optional): "camera" | "audio" | "compute" | "display" | "sensor"
     */
    private fun handleListResources(args: Map<String, Any?>): ToolResult {
        val category = args["category"] as? String
        val json = resourceRegistry.listResources(category)
        Log.d(TAG, "list_resources 호출 (category=$category) → ${json.length}자")
        return ToolResult(true, json, mapOf("category" to (category ?: "all")))
    }

    /**
     * activate_resource: 특정 리소스 활성화.
     * - 저전력(LOW) → 자동 활성화
     * - 중간(MEDIUM) → 활성화 시도 (이미 isAvailable이면 바로)
     * - 고전력(HIGH) → 사용자 승인 요청 후 활성화
     * - 미가용 → 실패 (activationHint 포함)
     *
     * args:
     *   - type (required): ResourceType enum 이름 (예: "CAMERA_COMPANION")
     *   - reason (optional): 활성화 이유 (HUD 표시용)
     */
    private suspend fun handleActivateResource(args: Map<String, Any?>): ToolResult {
        val typeName = args["type"] as? String
            ?: return ToolResult(false, "필수 파라미터 누락: type")

        val type = try {
            ResourceType.valueOf(typeName)
        } catch (e: IllegalArgumentException) {
            return ToolResult(false, "알 수 없는 리소스 타입: $typeName. 사용 가능: ${ResourceType.values().joinToString { it.name }}")
        }

        Log.d(TAG, "activate_resource: $typeName")

        return when (val result = resourceRegistry.activate(type, "ai_request")) {
            is ActivationResult.Success ->
                ToolResult(true, "✅ 활성화됨: ${type.displayName}", mapOf("resourceType" to typeName))

            is ActivationResult.NeedsUserApproval -> {
                // 고전력 리소스 → 사용자 승인 요청 (비동기, 결과는 ResourceProposalManager에서 처리)
                val approved = resourceProposalManager.requestApproval(type, result.reason)
                if (approved) {
                    // 승인 후 실제 활성화
                    resourceRegistry.activate(type, "user_approved")
                    ToolResult(true, "✅ 사용자 승인 후 활성화됨: ${type.displayName}", mapOf("resourceType" to typeName))
                } else {
                    ToolResult(false, "❌ 사용자가 거부함: ${type.displayName}", mapOf("resourceType" to typeName))
                }
            }

            is ActivationResult.NeedsUserAction ->
                ToolResult(false, "⚠️ 사용자 조치 필요: ${result.hint}", mapOf("resourceType" to typeName, "hint" to result.hint))

            is ActivationResult.Failed ->
                ToolResult(false, "❌ 활성화 실패: ${result.reason}", mapOf("resourceType" to typeName))
        }
    }

    /**
     * deactivate_resource: 리소스 비활성화.
     *
     * args:
     *   - type (required): ResourceType enum 이름
     */
    private fun handleDeactivateResource(args: Map<String, Any?>): ToolResult {
        val typeName = args["type"] as? String
            ?: return ToolResult(false, "필수 파라미터 누락: type")

        val type = try {
            ResourceType.valueOf(typeName)
        } catch (e: IllegalArgumentException) {
            return ToolResult(false, "알 수 없는 리소스 타입: $typeName")
        }

        resourceRegistry.deactivate(type)
        Log.d(TAG, "deactivate_resource: $typeName")
        return ToolResult(true, "비활성화됨: ${type.displayName}")
    }

    /**
     * propose_resource_combo: AI가 현재 상황에 유용한 리소스 조합을 사용자에게 제안.
     *
     * 사용자가 수락하면 AI가 각 리소스를 activate_resource로 순차 활성화.
     * 사용자가 거부하면 현재 상태 유지.
     *
     * args:
     *   - resources (required): List<String> — 제안하는 ResourceType 이름 목록
     *   - benefit (required): String — 기대 효과 (한국어, 예: "아이 글쓰기 정확도 향상 + 학습 집중도 파악")
     *   - explanation (required): String — 조합 이유 (예: "근접 카메라로 손글씨 감지 + 웹캠으로 교실 전경 파악")
     *   - scenario (optional): String — 시나리오 이름 (예: "아이 글쓰기 수업")
     */
    private suspend fun handleProposeResourceCombo(args: Map<String, Any?>): ToolResult {
        @Suppress("UNCHECKED_CAST")
        val resourceNames = (args["resources"] as? List<*>)
            ?.filterIsInstance<String>()
            ?: return ToolResult(false, "필수 파라미터 누락: resources (List<String>)")

        val benefit = args["benefit"] as? String
            ?: return ToolResult(false, "필수 파라미터 누락: benefit")

        val explanation = args["explanation"] as? String
            ?: return ToolResult(false, "필수 파라미터 누락: explanation")

        val scenario = args["scenario"] as? String ?: ""

        // ResourceType 유효성 검사
        val validTypes = resourceNames.mapNotNull { name ->
            try { ResourceType.valueOf(name) }
            catch (e: IllegalArgumentException) {
                Log.w(TAG, "알 수 없는 리소스 타입 무시: $name")
                null
            }
        }

        if (validTypes.isEmpty()) {
            return ToolResult(false, "유효한 리소스 타입이 없음: $resourceNames")
        }

        Log.i(TAG, "propose_resource_combo: ${validTypes.map { it.name }} — $benefit")

        // ResourceProposalManager에 제안 위임 (HUD + TTS)
        resourceProposalManager.propose(
            resources = validTypes,
            explanation = explanation,
            benefit = benefit,
            scenario = scenario
        )

        return ToolResult(
            true,
            "제안 전송됨 — 사용자 응답 대기 중",
            mapOf(
                "proposed_resources" to validTypes.map { it.displayName },
                "benefit" to benefit
            )
        )
    }

    /**
     * scan_companion_devices: 주변 Nearby 기기 스캔.
     * 연결 가능한 컴패니언 기기 목록 반환.
     */
    private fun handleScanCompanionDevices(): ToolResult {
        val companionConnected = resourceRegistry.companionConnected
        val ramAvail = resourceRegistry.companionRamAvailMb

        return if (companionConnected) {
            ToolResult(
                true,
                "컴패니언 기기 연결됨 — 가용 RAM: ${ramAvail}MB",
                mapOf("connected" to true, "ram_mb" to ramAvail)
            )
        } else {
            ToolResult(
                false,
                "연결된 컴패니언 기기 없음. 갤럭시 폴드3를 꺼내 Nearby 연결을 시도하세요.",
                mapOf("connected" to false)
            )
        }
    }
}
