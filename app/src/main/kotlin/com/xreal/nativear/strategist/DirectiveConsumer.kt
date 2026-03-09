package com.xreal.nativear.strategist

import android.util.Log
import com.xreal.nativear.ai.ProviderId
import com.xreal.nativear.cadence.CadenceConfig
import com.xreal.nativear.core.DeviceMode
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.mission.AgentRole
import com.xreal.nativear.mission.IMissionService
import com.xreal.nativear.mission.MissionTemplateConfig
import com.xreal.nativear.mission.MissionType
import com.xreal.nativear.monitoring.DeviceModeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * DirectiveConsumer — 전략가 지시사항을 실제 시스템에 적용하는 소비자.
 *
 * ## 문제 (수정 전)
 * StrategistReflector가 지시사항을 생성하고 DirectiveStore에 저장하지만,
 * 아무 컴포넌트도 읽지 않아 완전히 무시됨.
 *
 * ## 해결 (수정 후)
 * StrategistService가 반영 주기 완료 후 DirectiveConsumer.consumeAll()을 호출.
 * DirectiveConsumer가 각 지시사항 유형별로 적절한 컴포넌트에 위임.
 *
 * ## 지시사항 유형 및 처리
 * | 대상              | 형식 예시                          | 처리 컴포넌트     |
 * |------------------|------------------------------------|----------------|
 * | cadence_controller | "cadence:ocr_interval=1000"        | CadenceConfig  |
 * | cadence_controller | "cadence:decrease_capture_rate"    | CadenceConfig  |
 * | mission_conductor  | "create_mission:{...JSON...}"      | MissionConductor|
 * | device_mode        | "device_mode:HUD_ONLY"             | DeviceModeManager|
 * | resource          | "resource:disable_DETECTION"       | EventBus publish|
 * | persona_id        | 그 외 모든 지시 → 로그 (PersonaManager Layer 7에서 주입됨) |
 *
 * ## 향후 확장
 * - PersonaManager에 persona-specific 지시사항 직접 주입 (현재는 Layer 7 DB 기반)
 * - RuntimeConfig AI-튜닝 파라미터 적용
 */
class DirectiveConsumer(
    private val directiveStore: DirectiveStore,
    private val cadenceConfig: CadenceConfig,
    private val missionConductor: IMissionService? = null,
    private val deviceModeManager: DeviceModeManager? = null,
    private val eventBus: GlobalEventBus? = null,
    // ★ Phase 19: 전문가 팀 조합 최적화 Directive 처리
    private val expertTeamManager: com.xreal.nativear.expert.IExpertService? = null
) {
    private val TAG = "DirectiveConsumer"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * DirectiveStore의 모든 활성 지시사항을 소비.
     * StrategistService.runReflectionCycle() 완료 후 호출.
     *
     * ★ 긴급 지시사항 우선 처리:
     * isEmergency=true인 지시사항을 신뢰도 내림차순으로 먼저 처리.
     * EmergencyOrchestrator가 생성한 에러 우회 지시가 일반 전략보다 앞서 실행됨.
     */
    /** 마지막 실행 결과 (StrategistService 피드백용) */
    @Volatile var lastExecutionResult: ExecutionResult? = null
        private set

    data class ExecutionResult(
        val applied: Int,
        val failed: Int,
        val appliedTargets: List<String>,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun consumeAll() {
        val directives = directiveStore.getAllActiveDirectives()
        if (directives.isEmpty()) {
            Log.d(TAG, "소비할 활성 지시사항 없음")
            return
        }

        // ★ 긴급/일반 분리 — 긴급 먼저 처리
        val (emergencyDirs, normalDirs) = directives.partition { it.isEmergency }

        Log.i(TAG, "지시사항 소비 시작: ${directives.size}개 (긴급: ${emergencyDirs.size}, 일반: ${normalDirs.size})")
        var applied = 0
        var skipped = 0
        val appliedTargets = mutableListOf<String>()

        // 긴급 지시: 신뢰도 내림차순 정렬 후 즉시 처리
        for (directive in emergencyDirs.sortedByDescending { it.confidence }) {
            val success = dispatchDirective(directive)
            if (success) { applied++; appliedTargets.add(directive.targetPersonaId) } else skipped++
        }

        // 일반 지시: 기존 순서 유지
        for (directive in normalDirs) {
            val success = dispatchDirective(directive)
            if (success) { applied++; appliedTargets.add(directive.targetPersonaId) } else skipped++
        }

        lastExecutionResult = ExecutionResult(applied, skipped, appliedTargets)
        Log.i(TAG, "지시사항 소비 완료: 적용 $applied, 건너뜀 $skipped / 전체 ${directives.size}")
    }

    private fun dispatchDirective(directive: Directive): Boolean {
        val target = directive.targetPersonaId
        val instruction = directive.instruction

        Log.d(TAG, "지시사항 처리: [$target] '$instruction' (신뢰도:${directive.confidence})")

        return when {
            // === 긴급 지휘자 (EmergencyOrchestrator에서 생성된 지시) ===
            target == "emergency_commander" -> {
                applyEmergencyDirective(instruction, directive.rationale)
            }

            // === 케이던스 제어 ===
            target == "cadence_controller" || instruction.startsWith("cadence:") -> {
                applyCadenceDirective(instruction)
            }

            // === 커스텀 미션 생성 ===
            instruction.startsWith("create_mission:") -> {
                applyMissionDirective(instruction)
            }

            // === 디바이스 모드 전환 ===
            instruction.startsWith("device_mode:") -> {
                applyDeviceModeDirective(instruction, directive.rationale)
            }

            // === 비전 파이프라인 제어 ===
            instruction.startsWith("resource:") -> {
                applyResourceDirective(instruction)
            }

            // === ★ Phase 19: 전문가 팀 조합 최적화 ===
            target == "expert_team" -> {
                applyExpertTeamDirective(instruction)
            }

            // === ★ Policy Department: 정책 영구 오버라이드 ===
            // 형식: "policy:cadence.ocr_interval_ms=3000" 또는 target=="policy"
            target == "policy" || instruction.startsWith("policy:") -> {
                applyPolicyDirective(instruction)
            }

            // === 페르소나 지시사항 ===
            // PersonaManager의 Layer 7 (DirectiveStore에서 로드)을 통해 자동 주입됨.
            // 여기서는 적용 확인 로그만 남김.
            target in KNOWN_PERSONA_IDS || target == "*" -> {
                Log.d(TAG, "페르소나 지시사항 확인: [$target] → PersonaManager Layer 7에서 자동 주입됨")
                true // 실제 적용은 PersonaManager.buildPrompt()에서 수행
            }

            else -> {
                Log.w(TAG, "알 수 없는 지시사항 대상: [$target] — 건너뜀")
                false
            }
        }
    }

    // =========================================================================
    // 긴급 지휘자 지시사항
    // =========================================================================

    /**
     * EmergencyOrchestrator 또는 StrategistReflector가 생성한 긴급 지시 처리.
     *
     * 지원 명령:
     *   "activate_crisis_mode"          — 비전 파이프라인 최소화 + TTS 알림
     *   "restore_normal"                — 정상 모드 복구 (cadence 기본값)
     *   "reroute:ERROR_CODE→ACTION"     — 에러 코드별 우회 경로 로그 기록
     *   "suppress_error:ERROR_CODE"     — 30분간 해당 에러 억제 (로그만)
     */
    private fun applyEmergencyDirective(instruction: String, rationale: String): Boolean {
        Log.w(TAG, "🚨 긴급 지시사항 처리: '$instruction' / 이유: $rationale")
        return try {
            when {
                instruction == "activate_crisis_mode" -> {
                    // 비전 파이프라인 최소화 (모든 간격 30s로 → 실질 정지)
                    cadenceConfig.update {
                        copy(
                            ocrIntervalMs    = 30_000L,
                            detectIntervalMs = 30_000L,
                            poseIntervalMs   = 30_000L,
                            frameSkip        = 10
                        )
                    }
                    eventBus?.let { bus ->
                        scope.launch {
                            bus.publish(XRealEvent.SystemEvent.DebugLog("🚨 위기 모드 — 비전 파이프라인 최소화"))
                            bus.publish(XRealEvent.ActionRequest.SpeakTTS(
                                "비상 모드로 전환합니다. 모든 비필수 기능을 일시 중지합니다."
                            ))
                        }
                    }
                    Log.w(TAG, "✅ 위기 모드 활성화 — cadence 최소화 완료")
                    true
                }

                instruction == "restore_normal" -> {
                    // cadence 기본값으로 복구
                    cadenceConfig.update {
                        copy(
                            ocrIntervalMs    = 2000L,
                            detectIntervalMs = 2000L,
                            poseIntervalMs   = 500L,
                            frameSkip        = 2
                        )
                    }
                    eventBus?.let { bus ->
                        scope.launch {
                            bus.publish(XRealEvent.SystemEvent.DebugLog("✅ 정상 모드 복귀 — cadence 기본값"))
                            bus.publish(XRealEvent.ActionRequest.SpeakTTS("시스템이 정상 모드로 복귀합니다."))
                        }
                    }
                    Log.i(TAG, "✅ 정상 모드 복구 완료")
                    true
                }

                instruction.startsWith("reroute:") -> {
                    // reroute:SERVER_AI_ERROR→EDGE_AI  (Gap C: EmergencyOrchestrator에 즉시 피드백)
                    val payload = instruction.removePrefix("reroute:")
                    Log.i(TAG, "✅ 우회 경로 지시: $payload")
                    // EmergencyOrchestrator에 새 우회 규칙 전달
                    try {
                        org.koin.java.KoinJavaComponent.getKoin()
                            .getOrNull<com.xreal.nativear.resilience.EmergencyOrchestrator>()
                            ?.applyRerouteRule(payload)
                    } catch (e: Exception) {
                        Log.w(TAG, "EmergencyOrchestrator 우회 규칙 적용 실패: ${e.message}")
                    }
                    eventBus?.let { bus ->
                        scope.launch {
                            bus.publish(XRealEvent.SystemEvent.DebugLog("🔄 우회 경로 적용: $payload"))
                        }
                    }
                    true
                }

                instruction.startsWith("suppress_error:") -> {
                    val errorCode = instruction.removePrefix("suppress_error:")
                    // 실제 억제는 SystemErrorLogger가 처리. 여기서는 로그만.
                    Log.i(TAG, "✅ 에러 억제 지시 기록: $errorCode (30분)")
                    eventBus?.let { bus ->
                        scope.launch {
                            bus.publish(XRealEvent.SystemEvent.DebugLog("🔕 에러 억제: $errorCode"))
                        }
                    }
                    true
                }

                else -> {
                    Log.w(TAG, "알 수 없는 emergency 지시사항: '$instruction'")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "emergency 지시사항 처리 실패: ${e.message}")
            false
        }
    }

    // =========================================================================
    // 케이던스 제어
    // =========================================================================

    /**
     * 케이던스 지시사항을 CadenceConfig에 즉시 적용.
     *
     * 형식:
     *   "cadence:ocr_interval=1000"         → OCR 간격 1000ms
     *   "cadence:detect_interval=2000"       → 감지 간격 2000ms
     *   "cadence:pose_interval=500"          → 포즈 간격 500ms
     *   "cadence:frame_skip=2"              → 프레임 스킵 2
     *   "cadence:step_threshold=10"         → PDR 걸음 임계값 10
     *   "cadence:slam_frame_interval=3"     → SLAM 프레임 간격 3
     *   "cadence:increase_capture_rate"     → 전체 60% 증가 (모든 간격 0.625배)
     *   "cadence:decrease_capture_rate"     → 전체 50% 감소 (모든 간격 2배)
     */
    private fun applyCadenceDirective(instruction: String): Boolean {
        val payload = instruction.removePrefix("cadence:")
        return try {
            when {
                payload.startsWith("ocr_interval=") -> {
                    val ms = payload.removePrefix("ocr_interval=").toLong()
                    cadenceConfig.update { copy(ocrIntervalMs = ms.coerceIn(500L, 30_000L)) }
                    Log.i(TAG, "✅ OCR 간격 → ${ms}ms")
                    true
                }
                payload.startsWith("detect_interval=") -> {
                    val ms = payload.removePrefix("detect_interval=").toLong()
                    cadenceConfig.update { copy(detectIntervalMs = ms.coerceIn(500L, 30_000L)) }
                    Log.i(TAG, "✅ 감지 간격 → ${ms}ms")
                    true
                }
                payload.startsWith("pose_interval=") -> {
                    val ms = payload.removePrefix("pose_interval=").toLong()
                    cadenceConfig.update { copy(poseIntervalMs = ms.coerceIn(200L, 10_000L)) }
                    Log.i(TAG, "✅ 포즈 간격 → ${ms}ms")
                    true
                }
                payload.startsWith("frame_skip=") -> {
                    val skip = payload.removePrefix("frame_skip=").toInt()
                    cadenceConfig.update { copy(frameSkip = skip.coerceIn(1, 10)) }
                    Log.i(TAG, "✅ 프레임 스킵 → $skip")
                    true
                }
                payload.startsWith("step_threshold=") -> {
                    val thr = payload.removePrefix("step_threshold=").toInt()
                    cadenceConfig.update { copy(pdrStepThreshold = thr.coerceIn(5, 50)) }
                    Log.i(TAG, "✅ PDR 걸음 임계값 → $thr")
                    true
                }
                payload.startsWith("slam_frame_interval=") -> {
                    val interval = payload.removePrefix("slam_frame_interval=").toInt()
                    cadenceConfig.update { copy(slamFrameInterval = interval.coerceIn(1, 10)) }
                    Log.i(TAG, "✅ SLAM 프레임 간격 → $interval")
                    true
                }
                payload == "increase_capture_rate" -> {
                    // 모든 간격 60% 증가 (빠르게): 현재값의 0.625배
                    cadenceConfig.update {
                        copy(
                            ocrIntervalMs    = (ocrIntervalMs * 0.625).toLong().coerceAtLeast(500L),
                            detectIntervalMs = (detectIntervalMs * 0.625).toLong().coerceAtLeast(500L),
                            poseIntervalMs   = (poseIntervalMs * 0.625).toLong().coerceAtLeast(200L)
                        )
                    }
                    Log.i(TAG, "✅ 캡처율 60% 증가 적용")
                    true
                }
                payload == "decrease_capture_rate" -> {
                    // 모든 간격 50% 감소 (느리게): 현재값의 2배
                    cadenceConfig.update {
                        copy(
                            ocrIntervalMs    = (ocrIntervalMs * 2).coerceAtMost(30_000L),
                            detectIntervalMs = (detectIntervalMs * 2).coerceAtMost(30_000L),
                            poseIntervalMs   = (poseIntervalMs * 2).coerceAtMost(10_000L)
                        )
                    }
                    Log.i(TAG, "✅ 캡처율 50% 감소 적용")
                    true
                }
                else -> {
                    Log.w(TAG, "알 수 없는 cadence 지시사항: '$payload'")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "cadence 지시사항 파싱 실패: '$instruction' — ${e.message}")
            false
        }
    }

    // =========================================================================
    // 커스텀 미션 생성
    // =========================================================================

    /**
     * StrategistReflector가 생성한 미션 JSON을 MissionConductor에 전달.
     *
     * 형식: "create_mission:{"mission_name":"...","goals":[...],"agents":[...]}"
     *
     * agents 배열 항목:
     *   { "role_name": "...", "provider": "GEMINI|OPENAI|CLAUDE|GROK",
     *     "system_prompt": "...", "tools": [...], "rules": [...],
     *     "is_proactive": true, "proactive_interval_ms": 300000 }
     */
    private fun applyMissionDirective(instruction: String): Boolean {
        if (missionConductor == null) {
            Log.w(TAG, "MissionConductor 없음 — 미션 생성 건너뜀")
            return false
        }
        return try {
            val jsonStr = instruction.removePrefix("create_mission:")
            val json = JSONObject(jsonStr)
            val missionName = json.optString("mission_name", "strategist_mission")
            val goals = json.optJSONArray("goals")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?: emptyList()
            val agentsArray = json.optJSONArray("agents")

            // agents 배열에서 AgentRole 목록 생성 (MissionTemplateConfig에 직접 사용)
            val agentRoles = mutableListOf<AgentRole>()
            if (agentsArray != null) {
                for (i in 0 until agentsArray.length()) {
                    val agent = agentsArray.getJSONObject(i)
                    val roleName = agent.optString("role_name", "agent_$i")
                    val providerStr = agent.optString("provider", "GEMINI")
                    val systemPrompt = agent.optString("system_prompt", "")
                    val toolsList = agent.optJSONArray("tools")
                        ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                        ?: emptyList()
                    val rulesList = agent.optJSONArray("rules")
                        ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                        ?: emptyList()
                    val isProactive = agent.optBoolean("is_proactive", false)
                    val proactiveIntervalMs = agent.optLong("proactive_interval_ms", 5 * 60_000L)

                    val provider = try {
                        ProviderId.valueOf(providerStr.uppercase())
                    } catch (e: Exception) {
                        ProviderId.GEMINI
                    }

                    agentRoles.add(
                        AgentRole(
                            roleName = "${missionName}_$roleName",
                            providerId = provider,
                            systemPrompt = systemPrompt,
                            tools = toolsList,
                            rules = rulesList,
                            isProactive = isProactive,
                            proactiveIntervalMs = proactiveIntervalMs
                        )
                    )
                }
            }

            if (agentRoles.isEmpty()) {
                Log.w(TAG, "create_mission: 에이전트 없음 — 건너뜀")
                return false
            }

            val config = MissionTemplateConfig(
                type = MissionType.CUSTOM,
                agentRoles = agentRoles,
                initialPlanGoals = goals,
                maxDurationMs = json.optLong("max_duration_ms", 2 * 3600_000L)
            )

            Log.i(TAG, "✅ 커스텀 미션 생성: '$missionName' (에이전트 ${agentRoles.size}명, 목표: $goals)")

            // scope에서 비동기 실행 (activateCustomMission은 blocking이 아니지만 코루틴 컨텍스트 필요)
            scope.launch {
                missionConductor.activateCustomMission(config, mapOf("source" to "strategist", "mission_name" to missionName))
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "create_mission 파싱 실패: ${e.message}")
            false
        }
    }

    // =========================================================================
    // 디바이스 모드 전환
    // =========================================================================

    /**
     * 전략가가 운영 모드 전환을 지시.
     * 형식: "device_mode:FULL_AR" / "device_mode:HUD_ONLY" / etc.
     */
    private fun applyDeviceModeDirective(instruction: String, rationale: String): Boolean {
        if (deviceModeManager == null) {
            Log.w(TAG, "DeviceModeManager 없음 — 모드 전환 건너뜀")
            return false
        }
        return try {
            val modeName = instruction.removePrefix("device_mode:").trim().uppercase()
            val targetMode = DeviceMode.valueOf(modeName)
            deviceModeManager.switchMode(targetMode, "전략가 지시: $rationale")
            Log.i(TAG, "✅ 디바이스 모드 전환 지시 → $targetMode")
            true
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "알 수 없는 DeviceMode: '$instruction'")
            false
        } catch (e: Exception) {
            Log.e(TAG, "device_mode 파싱 실패: ${e.message}")
            false
        }
    }

    // =========================================================================
    // 리소스 제어
    // =========================================================================

    /**
     * 특정 비전 기능 비활성화 지시.
     * 형식: "resource:disable_DETECTION" / "resource:disable_OCR" / "resource:disable_POSE"
     *       "resource:enable_DETECTION" / "resource:reduce_vision"
     */
    private fun applyResourceDirective(instruction: String): Boolean {
        val payload = instruction.removePrefix("resource:").trim()
        return try {
            when {
                payload == "reduce_vision" -> {
                    // 모든 비전 간격을 현재의 2배로
                    cadenceConfig.update {
                        copy(
                            ocrIntervalMs    = (ocrIntervalMs * 2).coerceAtMost(30_000L),
                            detectIntervalMs = (detectIntervalMs * 2).coerceAtMost(30_000L),
                            poseIntervalMs   = (poseIntervalMs * 2).coerceAtMost(10_000L),
                            frameSkip        = (frameSkip + 1).coerceAtMost(10)
                        )
                    }
                    Log.i(TAG, "✅ 비전 파이프라인 전체 절감 적용")
                    true
                }
                payload.startsWith("disable_") -> {
                    val feature = payload.removePrefix("disable_")
                    // VisionToolExecutor의 control_vision_pipeline 도구를 통해 비활성화
                    // 이벤트를 통해 VisionCoordinator에 전달
                    eventBus?.let { bus ->
                        scope.launch {
                            bus.publish(XRealEvent.SystemEvent.DebugLog(
                                "[DirectiveConsumer] resource:disable_$feature 요청 — VisionCoordinator 처리 필요"
                            ))
                        }
                    }
                    // CadenceConfig에서 해당 기능 간격을 최대화하여 실질적 비활성화
                    when (feature.uppercase()) {
                        "OCR"           -> cadenceConfig.update { copy(ocrIntervalMs = 30_000L) }
                        "DETECTION"     -> cadenceConfig.update { copy(detectIntervalMs = 30_000L) }
                        "POSE"          -> cadenceConfig.update { copy(poseIntervalMs = 30_000L) }
                        "HAND_TRACKING" -> cadenceConfig.update { copy(frameSkip = 10) }
                    }
                    Log.i(TAG, "✅ 비전 기능 비활성화 적용: $feature")
                    true
                }
                payload.startsWith("enable_") -> {
                    val feature = payload.removePrefix("enable_")
                    // 해당 기능 간격을 기본값으로 복원
                    when (feature.uppercase()) {
                        "OCR"           -> cadenceConfig.update { copy(ocrIntervalMs = 2000L) }
                        "DETECTION"     -> cadenceConfig.update { copy(detectIntervalMs = 2000L) }
                        "POSE"          -> cadenceConfig.update { copy(poseIntervalMs = 500L) }
                        "HAND_TRACKING" -> cadenceConfig.update { copy(frameSkip = 2) }
                    }
                    Log.i(TAG, "✅ 비전 기능 재활성화: $feature")
                    true
                }
                else -> {
                    Log.w(TAG, "알 수 없는 resource 지시사항: '$payload'")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "resource 지시사항 처리 실패: ${e.message}")
            false
        }
    }

    // =========================================================================
    // ★ Phase 19: 전문가 팀 조합 최적화 지시사항
    // =========================================================================

    /**
     * ExpertTeamManager에게 팀 조합 우선순위 조정 지시 전달.
     *
     * 지원 명령:
     *   "boost_priority:domain_id:+N"   — 해당 도메인 우선순위 임시 증가
     *   "prefer:domain_id"              — 다음 팀 구성 시 우선 선택
     *   "demote:domain_id"              — 낮은 우선순위
     *   "revoke_peer_request:req_id"    — 승인된 피어 요청 효과 없음 → 취소
     */
    private fun applyExpertTeamDirective(instruction: String): Boolean {
        return when {
            instruction.startsWith("revoke_peer_request:") -> {
                val requestId = instruction.removePrefix("revoke_peer_request:")
                try {
                    org.koin.java.KoinJavaComponent.getKoin()
                        .getOrNull<com.xreal.nativear.expert.ExpertPeerRequestStore>()
                        ?.revokeRequest(requestId, "StrategistService: 효과 미검증 → 자동 취소")
                    org.koin.java.KoinJavaComponent.getKoin()
                        .getOrNull<com.xreal.nativear.expert.ExpertDynamicProfileStore>()
                        ?.revoke("", requestId)  // expertId 없이 requestId로 취소
                    Log.i(TAG, "★ 피어 요청 자동 취소: $requestId")
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "피어 요청 취소 실패: ${e.message}")
                    false
                }
            }
            else -> {
                expertTeamManager?.applyCompositionDirective(instruction) ?: run {
                    Log.w(TAG, "ExpertTeamManager 미등록 — expert_team Directive 건너뜀: $instruction")
                    false
                }
            }
        }
    }

    // =========================================================================
    // ★ Policy Department: 정책 영구 오버라이드
    // =========================================================================

    /**
     * 정책 지시사항을 PolicyRegistry에 영구 적용.
     * 형식: "policy:cadence.ocr_interval_ms=3000"
     */
    private fun applyPolicyDirective(instruction: String): Boolean {
        return try {
            val payload = instruction.removePrefix("policy:")
            val eqIdx = payload.indexOf('=')
            if (eqIdx <= 0) {
                Log.w(TAG, "잘못된 policy 지시 형식: '$instruction' (key=value 필요)")
                return false
            }
            val key = payload.substring(0, eqIdx).trim()
            val value = payload.substring(eqIdx + 1).trim()

            val registry = org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.policy.PolicyRegistry>()
            if (registry == null) {
                Log.w(TAG, "PolicyRegistry 미등록 — policy 지시 건너뜀: $key=$value")
                return false
            }

            val success = registry.set(key, value, source = "strategist")
            if (success) {
                Log.i(TAG, "✅ 정책 영구 오버라이드: $key = $value (strategist)")
            } else {
                Log.w(TAG, "정책 오버라이드 실패: $key=$value (유효성 검사 실패)")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "policy 지시 처리 실패: ${e.message}")
            false
        }
    }

    companion object {
        private val KNOWN_PERSONA_IDS = setOf(
            "vision_analyst", "context_predictor", "safety_monitor", "memory_curator",
            "behavior_analyst", "speech_lang_pathologist", "occupational_therapist",
            "educational_psychologist", "sped_coordinator",
            "emergency_commander",  // ★ 긴급 지휘자 페르소나
            "*"
        )
    }
}
