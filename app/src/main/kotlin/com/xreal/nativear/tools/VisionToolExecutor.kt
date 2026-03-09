package com.xreal.nativear.tools

import com.xreal.nativear.IVisionService

/**
 * VisionToolExecutor -- 비전 파이프라인 제어 AI 도구.
 *
 * ## 도구 목록
 * - `take_snapshot`: 현재 장면 스냅샷 캡처
 * - `get_screen_objects`: 화면에 보이는 객체/텍스트 목록 반환
 * - `setVisionControl`: 개별 비전 기능 on/off (레거시, 하위 호환)
 * - `control_vision_pipeline`: 전체 비전 파이프라인 제어 (권장)
 *   - feature: "OCR", "DETECTION", "POSE", "HAND_TRACKING", "ALL"
 *   - enabled: true/false
 *   - 예: {"feature": "ALL", "enabled": false} → 모든 비전 처리 중단 (CPU 절약)
 * - `get_vision_status`: 현재 비전 파이프라인 상태 반환
 */
class VisionToolExecutor(
    private val visionService: IVisionService,
    private val screenObjectsProvider: () -> String
) : IToolExecutor {

    override val supportedTools = setOf(
        "take_snapshot",
        "get_screen_objects",
        "setVisionControl",
        "control_vision_pipeline",
        "get_vision_status"
    )

    // 상태 추적 (VisionManager에도 있지만 도구 레벨에서도 추적)
    private var ocrEnabled = false
    private var detectionEnabled = true
    private var poseEnabled = false
    private var handTrackingEnabled = false

    override suspend fun execute(name: String, args: Map<String, Any?>): ToolResult {
        return when (name) {
            "take_snapshot" -> {
                visionService.captureSceneSnapshot()
                ToolResult(true, "Snapshot capture triggered.")
            }
            "get_screen_objects" -> {
                ToolResult(true, screenObjectsProvider())
            }
            "setVisionControl" -> {
                // 레거시 호환: OCR/POSE만 지원
                val feature = args["feature"] as? String ?: ""
                val enabled = args["enabled"] as? Boolean ?: false
                applyFeatureControl(feature, enabled)
            }
            "control_vision_pipeline" -> {
                controlVisionPipeline(args)
            }
            "get_vision_status" -> {
                getVisionStatus()
            }
            else -> ToolResult(false, "Unsupported tool: $name")
        }
    }

    /**
     * 전체 비전 파이프라인 제어.
     *
     * args:
     * - feature: "OCR" | "DETECTION" | "POSE" | "HAND_TRACKING" | "ALL"
     * - enabled: true | false
     *
     * ALL을 사용하면 모든 비전 기능을 한번에 켜거나 끌 수 있음.
     * CPU 부하가 높을 때 AI가 "ALL" off → 필수 기능만 on 하는 전략 가능.
     */
    private fun controlVisionPipeline(args: Map<String, Any?>): ToolResult {
        val feature = (args["feature"] as? String)?.uppercase() ?: ""
        val enabled = args["enabled"] as? Boolean ?: false

        if (feature.isEmpty()) {
            return ToolResult(false, "Missing 'feature' parameter. Use: OCR, DETECTION, POSE, HAND_TRACKING, or ALL")
        }

        return if (feature == "ALL") {
            applyFeatureControl("OCR", enabled)
            applyFeatureControl("DETECTION", enabled)
            applyFeatureControl("POSE", enabled)
            applyFeatureControl("HAND_TRACKING", enabled)
            ToolResult(true, "All vision features set to $enabled. " +
                    "OCR=$enabled, DETECTION=$enabled, POSE=$enabled, HAND_TRACKING=$enabled")
        } else {
            applyFeatureControl(feature, enabled)
        }
    }

    /**
     * 개별 비전 기능 제어.
     */
    private fun applyFeatureControl(feature: String, enabled: Boolean): ToolResult {
        return when (feature.uppercase()) {
            "OCR" -> {
                visionService.setOcrEnabled(enabled)
                ocrEnabled = enabled
                ToolResult(true, "OCR set to $enabled")
            }
            "DETECTION" -> {
                visionService.setDetectionEnabled(enabled)
                detectionEnabled = enabled
                ToolResult(true, "Object detection (YOLO) set to $enabled")
            }
            "POSE" -> {
                visionService.setPoseEnabled(enabled)
                poseEnabled = enabled
                ToolResult(true, "Pose estimation set to $enabled")
            }
            "HAND_TRACKING" -> {
                visionService.setHandTrackingEnabled(enabled)
                handTrackingEnabled = enabled
                ToolResult(true, "Hand tracking set to $enabled")
            }
            else -> {
                ToolResult(false, "Unknown feature: $feature. Use: OCR, DETECTION, POSE, HAND_TRACKING, or ALL")
            }
        }
    }

    /**
     * 현재 비전 파이프라인 상태 반환.
     */
    private fun getVisionStatus(): ToolResult {
        val status = buildString {
            appendLine("Vision Pipeline Status:")
            appendLine("- OCR: ${if (ocrEnabled) "ON" else "OFF"}")
            appendLine("- Object Detection (YOLO): ${if (detectionEnabled) "ON" else "OFF"}")
            appendLine("- Pose Estimation: ${if (poseEnabled) "ON" else "OFF"}")
            appendLine("- Hand Tracking: ${if (handTrackingEnabled) "ON" else "OFF"}")
            appendLine()
            appendLine("Usage tips:")
            appendLine("- Disable ALL to save CPU during intensive tasks (running, navigation)")
            appendLine("- Enable only needed features for specific situations")
            appendLine("- DETECTION is heaviest (YOLO inference), disable first if CPU is high")
        }
        return ToolResult(true, status)
    }
}
