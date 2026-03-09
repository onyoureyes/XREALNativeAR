package com.xreal.nativear.resource

/**
 * ResourceType — 시스템이 활용할 수 있는 모든 리소스 카탈로그.
 *
 * AI(Gemini)가 [list_resources] 도구로 조회하여 상황에 맞는 조합을 선택.
 * capabilities 목록은 AI 추론에 사용되는 자연어 메타데이터.
 *
 * ## 카테고리
 * - CAMERA_*: 비전 소스 (XREAL, 폰, 컴패니언, 네트워크)
 * - MIC_*: 오디오 소스
 * - COMPUTE_*: 연산 백엔드
 * - DISPLAY_*: 출력 화면
 * - SENSOR_*: 센서 데이터
 *
 * ## PowerCost 기준
 * - LOW: 배터리 영향 미미 (상시 활성 가능)
 * - MEDIUM: 적절한 배터리 사용 (필요할 때만)
 * - HIGH: 배터리 집중 소비 (신중하게 사용)
 */
enum class ResourceType(
    val displayName: String,
    val capabilities: List<String>,
    val powerCost: PowerCost,
    val requiresCompanionDevice: Boolean = false,
    val requiresNetwork: Boolean = false,
    val requiresUserAction: Boolean = false,   // 사용자가 직접 뭔가 해야 하는지
    val activationHint: String? = null         // requiresUserAction=true일 때 안내 메시지
) {
    // =========================================================================
    // 비전 소스
    // =========================================================================

    CAMERA_XREAL_OV580(
        displayName = "XREAL 스테레오 카메라",
        capabilities = listOf("slam", "depth_estimation", "narrow_fov_30deg",
            "ar_tracking", "hand_tracking"),
        powerCost = PowerCost.MEDIUM
    ),
    CAMERA_XREAL_RGB(
        displayName = "XREAL RGB 카메라",
        capabilities = listOf("ocr", "color_recognition", "wide_fov_90deg",
            "face_detection", "object_detection"),
        powerCost = PowerCost.HIGH
    ),
    CAMERA_FOLD4_REAR(
        displayName = "폴드4 후면 카메라",
        capabilities = listOf("ocr", "face_detection", "barcode_qr",
            "high_resolution", "document_scan", "meeting_materials"),
        powerCost = PowerCost.MEDIUM
    ),
    CAMERA_FOLD4_FRONT(
        displayName = "폴드4 전면 카메라",
        capabilities = listOf("face_detection", "selfie", "user_expression"),
        powerCost = PowerCost.LOW
    ),
    CAMERA_COMPANION(
        displayName = "컴패니언 기기 카메라",
        capabilities = listOf("remote_view", "ocr", "wide_fov",
            "alternative_angle", "meeting_materials", "document_scan"),
        powerCost = PowerCost.LOW,
        requiresCompanionDevice = true,
        activationHint = "컴패니언 기기(갤폴드3)를 꺼내 카메라 방향을 맞춰주세요"
    ),
    CAMERA_NETWORK_ENDPOINT(
        displayName = "네트워크 카메라",
        capabilities = listOf("remote_view", "wide_angle", "room_overview",
            "classroom_monitor", "environment_context"),
        powerCost = PowerCost.LOW,
        requiresNetwork = true,
        requiresUserAction = true,
        activationHint = "네트워크 카메라 서버 URL을 설정해주세요"
    ),

    // =========================================================================
    // 오디오 소스
    // =========================================================================

    MIC_FOLD4(
        displayName = "폴드4 마이크",
        capabilities = listOf("stt", "audio_lifelog", "noise_cancel",
            "voice_detection", "ambient_sound"),
        powerCost = PowerCost.LOW
    ),
    MIC_COMPANION_BEAM(
        displayName = "컴패니언 빔포밍 마이크",
        capabilities = listOf("directional_audio", "noise_cancel",
            "sound_direction", "beamforming", "distance_estimation"),
        powerCost = PowerCost.LOW,
        requiresCompanionDevice = true,
        activationHint = "컴패니언 기기를 대화 방향으로 향하게 해주세요"
    ),

    // =========================================================================
    // 연산 백엔드
    // =========================================================================

    COMPUTE_SERVER_AI(
        displayName = "서버 AI (Gemini/GPT)",
        capabilities = listOf("complex_reasoning", "tool_calling", "multilingual",
            "long_context", "image_understanding"),
        powerCost = PowerCost.LOW,
        requiresNetwork = true
    ),
    COMPUTE_REMOTE_LLM(
        displayName = "리모트 LLM (PC Tailscale)",
        capabilities = listOf("complex_reasoning", "multilingual", "long_context",
            "free_unlimited", "privacy_preserved", "medium_latency"),
        powerCost = PowerCost.LOW,
        requiresNetwork = true
    ),
    COMPUTE_STEAMDECK_LLM(
        displayName = "스팀덱 LLM (Tailscale)",
        capabilities = listOf("basic_reasoning", "multilingual", "korean",
            "free_unlimited", "privacy_preserved", "low_latency",
            "gemma_3_4b", "lightweight_tasks"),
        powerCost = PowerCost.LOW,
        requiresNetwork = true
    ),
    COMPUTE_EDGE_1B(
        displayName = "엣지 AI 1B (온디바이스)",
        capabilities = listOf("basic_reasoning", "offline_operation",
            "fast_response", "privacy_preserved"),
        powerCost = PowerCost.MEDIUM
    ),
    COMPUTE_EDGE_E2B(
        displayName = "엣지 AI E2B 멀티모달 (온디바이스)",
        capabilities = listOf("multimodal_reasoning", "vision_language", "offline_full_capable",
            "image_understanding", "complex_reasoning", "emergency_fallback",
            "privacy_preserved", "no_network_required"),
        powerCost = PowerCost.HIGH  // 2.2GB RAM 사용 — 배터리 집중 소비
    ),
    COMPUTE_COMPANION_LLM(
        displayName = "컴패니언 엣지 AI",
        capabilities = listOf("parallel_reasoning", "compute_offload",
            "extended_context", "dual_ai_processing"),
        powerCost = PowerCost.LOW,
        requiresCompanionDevice = true
    ),

    // =========================================================================
    // 출력 화면
    // =========================================================================

    DISPLAY_XREAL_HUD(
        displayName = "XREAL AR HUD",
        capabilities = listOf("ar_overlay", "spatial_anchoring",
            "head_tracking_ui", "immersive_display"),
        powerCost = PowerCost.LOW
    ),
    DISPLAY_FOLD4_SCREEN(
        displayName = "폴드4 화면 패널",
        capabilities = listOf("text_panel", "scrollable_content", "touch_interaction",
            "glasses_free_display", "information_panel"),
        powerCost = PowerCost.LOW
    ),
    DISPLAY_COMPANION_SCREEN(
        displayName = "컴패니언 화면",
        capabilities = listOf("secondary_display", "extended_ui",
            "auxiliary_information"),
        powerCost = PowerCost.LOW,
        requiresCompanionDevice = true
    ),

    // =========================================================================
    // 센서
    // =========================================================================

    SENSOR_WATCH_HR(
        displayName = "Watch 심박",
        capabilities = listOf("heart_rate", "stress_detection", "exertion_level"),
        powerCost = PowerCost.LOW
    ),
    SENSOR_WATCH_HRV(
        displayName = "Watch HRV",
        capabilities = listOf("hrv", "fatigue_detection", "recovery_status"),
        powerCost = PowerCost.LOW
    ),
    SENSOR_WATCH_SPO2(
        displayName = "Watch SpO2",
        capabilities = listOf("blood_oxygen", "health_monitoring"),
        powerCost = PowerCost.LOW
    ),
    SENSOR_XREAL_IMU(
        displayName = "XREAL IMU",
        capabilities = listOf("head_pose", "head_gesture", "orientation_tracking"),
        powerCost = PowerCost.LOW
    ),
    SENSOR_PHONE_GPS(
        displayName = "폰 GPS",
        capabilities = listOf("location", "navigation", "geofencing"),
        powerCost = PowerCost.MEDIUM
    );

    /**
     * AI 도구 응답용 JSON 형식 메타데이터.
     */
    fun toJson(isAvailable: Boolean, isActive: Boolean): String {
        val caps = capabilities.joinToString(",") { "\"$it\"" }
        val hint = activationHint?.let { ", \"activationHint\": \"$it\"" } ?: ""
        return """{"type":"${name}","displayName":"$displayName","capabilities":[$caps],"powerCost":"${powerCost.name}","requiresCompanion":$requiresCompanionDevice,"requiresNetwork":$requiresNetwork,"isAvailable":$isAvailable,"isActive":$isActive$hint}"""
    }
}

enum class PowerCost { LOW, MEDIUM, HIGH }
