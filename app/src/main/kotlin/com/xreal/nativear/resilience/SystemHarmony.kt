package com.xreal.nativear.resilience

import com.xreal.nativear.core.CapabilityTier
import com.xreal.nativear.core.DeviceMode

/**
 * SystemHarmony — 시스템 하모니 아키텍처 규칙서 (악보).
 *
 * ## 팀 구성 및 역할 (4계층 오케스트라)
 *
 * ### LAYER 0 — 센서 섹션 (데이터 수집만, 판단 없음)
 *   ● DeviceHealthMonitor : 하드웨어 상태 센서
 *                           역할: XREAL 글래스, 갤럭시 워치, 배터리 30초 폴링
 *                           발행: DeviceHealthUpdated
 *   ● ResourceMonitor     : 계산 자원 센서
 *                           역할: CPU%, RAM, 온도 30초 폴링
 *                           발행: ResourceAlert (임계값 초과 시만)
 *   ● ConnectivityMonitor : 네트워크 상태 센서
 *                           역할: 네트워크 콜백 기반 즉각 감지
 *                           발행: NetworkStateChanged
 *
 * ### LAYER 1 — 지휘자 (단일 권한자, 충돌 해결)
 *   ★ SystemConductor    : 모든 센서 데이터 집계 → SystemState (단일 진실 공급원)
 *                           분석 섹션 제안 수집 → 충돌 해결 → HarmonyDecision 발행
 *                           30초 단일 루프 (OperationalDirector 루프 흡수)
 *
 * ### LAYER 2 — 분석 섹션 (제안만, 직접 발행 최소화)
 *   ● FailsafeController    : 하드웨어 위기 분석가 [우선순위: CRITICAL]
 *                             역할: 배터리/연결 위기 → CapabilityTier 하향 결정
 *                             발행: CapabilityTierChanged (그대로 유지 — 하위 호환)
 *   ● DeviceModeManager     : 파이프라인 효율 관리자 [우선순위: HIGH]
 *                             역할: CPU/열 급상승 → DeviceMode 강등 결정
 *                             발행: DeviceModeChanged (그대로 유지 — 하위 호환)
 *   ● OperationalDirector   : 상황 균형 조정가 [우선순위: NORMAL]
 *                             역할: 상황(RUNNING/MEETING) → 목표 등급 제안
 *                             발행: SystemConductor.submitProposal() 통해 간접 전달
 *
 * ### LAYER 3 — 반응 섹션 (HarmonyDecision 구독 후 전문 영역 반응)
 *   ● EmergencyOrchestrator : 즉각 에러 우회 집행관 [우선순위: CRITICAL]
 *                             역할: SystemEvent.Error → 3초 우회 규칙 적용
 *                             발행: DebugLog, SpeakTTS (에러 처리 액션)
 *   ● StrategistService     : 전략 AI 고문 [우선순위: ADVISORY]
 *                             역할: 5분 주기 반성 → 장기 지시사항 생성
 *                             발행: DirectiveStore 업데이트 (EventBus 없음)
 *
 * ## 충돌 해결 규칙 (우선순위 악보)
 * ```
 * Priority: CRITICAL(0) > HIGH(1) > NORMAL(2) > ADVISORY(3)
 *
 * Rule 1: CRITICAL 제안 있으면 tier는 그것이 결정 (배터리/글래스 위기 최우선)
 * Rule 2: 같은 Priority면 더 보수적(높은 ordinal = 더 제한적) tier가 이긴다 (안전 우선)
 * Rule 3: Emergency 에러 활성 중에는 tier 상향 불가 (30초 잠금)
 * Rule 4: ADVISORY(StrategistService) 지시사항은 tier 변경 불가, 동작 제안만 가능
 * ```
 */
object SystemHarmony {

    // ─── 섹션 우선순위 ────────────────────────────────────────────────────────

    enum class SectionPriority(val level: Int) {
        CRITICAL(0),  // FailsafeController, EmergencyOrchestrator
        HIGH(1),      // DeviceModeManager
        NORMAL(2),    // OperationalDirector
        ADVISORY(3)   // StrategistService (직접 제안 불가)
    }

    enum class SystemSection(val displayName: String, val priority: SectionPriority) {
        FAILSAFE("하드웨어 위기 분석가", SectionPriority.CRITICAL),
        PIPELINE("파이프라인 효율 관리자", SectionPriority.HIGH),
        SITUATION("상황 균형 조정가", SectionPriority.NORMAL),
        EMERGENCY("즉각 에러 우회 집행관", SectionPriority.CRITICAL),
        STRATEGIST("전략 AI 고문", SectionPriority.ADVISORY)
    }

    // ─── 시스템 통합 상태 스냅샷 ──────────────────────────────────────────────

    /**
     * SystemState — 단일 진실 공급원.
     * SystemConductor가 유지하며, AI 도구와 분석 섹션들이 조회.
     */
    data class SystemState(
        val timestamp: Long = System.currentTimeMillis(),
        // 하드웨어 (DeviceHealthMonitor 제공)
        val batteryPercent: Int = 100,
        val isCharging: Boolean = false,
        val isGlassesConnected: Boolean = true,
        val glassesFrameRateFps: Float = 30f,
        val isWatchConnected: Boolean = false,
        val thermalStatus: Int = 0,       // 0=NONE, 3=SEVERE, 5=SHUTDOWN
        // 계산 자원 (ResourceMonitor 제공)
        val cpuPercent: Int = 0,
        val ramUsedMb: Int = 0,
        val ramTotalMb: Int = 12288,
        val batteryTempC: Float = 36f,
        // 네트워크 (ConnectivityMonitor 제공)
        val isNetworkAvailable: Boolean = true,
        val networkType: String = "WIFI",
        val isEdgeLlmReady: Boolean = false,
        // 현재 결정 (SystemConductor 제공)
        val currentTier: CapabilityTier = CapabilityTier.TIER_0_FULL,
        val currentMode: DeviceMode = DeviceMode.FULL_AR,
        val activeEmergencies: Int = 0,
        val lastHarmonyReason: String = "초기화"
    ) {
        /** AI 도구용 요약 문자열 */
        fun toSummary(): String = buildString {
            append("배터리:${batteryPercent}%")
            if (isCharging) append("(충전중)")
            append(", 글래스:${if (isGlassesConnected) "ON" else "OFF"}")
            append(", 워치:${if (isWatchConnected) "ON" else "OFF"}")
            append(", CPU:${cpuPercent}%")
            append(", RAM:${ramUsedMb}/${ramTotalMb}MB")
            append(", 온도:${batteryTempC}°C")
            append(", 네트워크:${if (isNetworkAvailable) networkType else "NONE"}")
            append(", 엣지LLM:${if (isEdgeLlmReady) "READY" else "N/A"}")
            append(", 등급:${currentTier.name}")
            append(", 모드:${currentMode.name}")
            if (activeEmergencies > 0) append(", ⚠️ 에러:${activeEmergencies}건 활성")
            append(" [${lastHarmonyReason}]")
        }
    }

    // ─── 제안 및 결정 ─────────────────────────────────────────────────────────

    /**
     * SystemProposal — 분석 섹션이 SystemConductor에 제출하는 제안.
     * OperationalDirector가 submitProposal()로 전달.
     */
    data class SystemProposal(
        val section: SystemSection,
        val proposedTier: CapabilityTier? = null,
        val proposedMode: DeviceMode? = null,
        val reason: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * HarmonyDecision — SystemConductor가 발행하는 최종 결정.
     * 분석 섹션들의 충돌을 해결한 권위 있는 시스템 상태.
     */
    data class HarmonyDecision(
        val tier: CapabilityTier,
        val mode: DeviceMode,
        val winningSection: SystemSection,
        val reason: String,
        val goalTierHint: CapabilityTier? = null,  // OperationalDirector의 목표 (StrategistService 참고용)
        val overriddenSections: List<String> = emptyList(),
        val timestamp: Long = System.currentTimeMillis()
    )

    // ─── 충돌 해결 순수 함수 ──────────────────────────────────────────────────

    /**
     * 복수의 제안 중 하나를 채택.
     *
     * Rule 1: CRITICAL Priority 섹션 제안 우선.
     * Rule 2: 같은 Priority면 더 보수적인(높은 ordinal) tier가 이긴다.
     * Rule 3: proposedTier == null인 제안은 tier 집계에서 제외.
     *
     * @return 채택할 제안, 없으면 null
     */
    fun resolveConflicts(proposals: List<SystemProposal>): SystemProposal? {
        val tierProposals = proposals.filter { it.proposedTier != null }
        if (tierProposals.isEmpty()) return null

        return tierProposals.minWith(
            compareBy<SystemProposal> { it.section.priority.level }  // Priority 낮을수록(CRITICAL) 우선
                .thenByDescending { it.proposedTier!!.ordinal }       // 같은 Priority면 더 제한적 tier 우선
        )
    }

    /**
     * 제안 목록에서 "이겼지만 채택되지 않은" 섹션들의 이름 목록.
     */
    fun getOverridden(proposals: List<SystemProposal>, winner: SystemProposal): List<String> {
        return proposals
            .filter { it != winner && it.proposedTier != null && it.proposedTier != winner.proposedTier }
            .map { it.section.displayName }
    }
}
