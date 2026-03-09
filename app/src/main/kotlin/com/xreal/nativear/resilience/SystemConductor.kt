package com.xreal.nativear.resilience

import android.util.Log
import com.xreal.nativear.core.CapabilityTier
import com.xreal.nativear.core.DeviceMode
import com.xreal.nativear.core.ErrorReporter
import com.xreal.nativear.core.FocusMode
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.policy.PolicyReader
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.context.LifeSituation
import com.xreal.nativear.context.SituationRecognizer
import com.xreal.nativear.focus.FocusModeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * SystemConductor — 시스템 하모니 지휘자 (LAYER 1).
 *
 * ## 역할
 * 모든 모니터링 컴포넌트의 데이터를 집계하고 충돌하는 결정을
 * 하나의 [XRealEvent.SystemEvent.HarmonyDecision]으로 조율하는 단일 권한자.
 *
 * ## 데이터 흐름
 * ```
 * LAYER 0 센서 이벤트 (DeviceHealthUpdated / ResourceAlert / NetworkStateChanged)
 *     ↓  구독 후 SystemState 업데이트
 * LAYER 2 분석 섹션들이 제안 제출
 *   - FailsafeController: CapabilityTierChanged 이벤트 → 자동 수신
 *   - DeviceModeManager:  DeviceModeChanged 이벤트 → 자동 수신
 *   - OperationalDirector: submitProposal() API 직접 호출
 *     ↓  충돌 해결 (SystemHarmony.resolveConflicts)
 * HarmonyDecision 발행 (30초 루프 + 임계값 초과 즉각 발행)
 * ```
 *
 * ## 30초 루프
 * OperationalDirector의 독립 30s 루프를 흡수.
 * 총 30초 루프: DeviceHealthMonitor + ResourceMonitor + **SystemConductor** (3→2개)
 *
 * ## AI 도구 인터페이스
 * `getStatusSummary()` → "배터리:85%, 글래스:ON, CPU:43%, 등급:FULL"
 * → GeminiTools의 `get_system_health` 도구가 이것을 반환.
 */
class SystemConductor(
    private val eventBus: GlobalEventBus,
    private val situationRecognizer: SituationRecognizer? = null,
    private val focusModeManager: FocusModeManager? = null
) {
    companion object {
        private const val TAG = "SystemConductor"
        private val CONDUCTOR_LOOP_MS: Long get() = PolicyReader.getLong("resilience.conductor_loop_ms", 30_000L)      // 30초
        private val EMERGENCY_LOCK_MS: Long get() = PolicyReader.getLong("resilience.emergency_lock_ms", 30_000L)      // 에러 활성 시 tier 상향 잠금 30초
        private const val GAP_THRESHOLD = 1                // ordinal 허용 편차
        private val BATHROOM_KEYWORDS = listOf("화장실", "욕실", "toilet", "restroom", "bathroom")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var conductorJob: Job? = null

    // ─── 단일 진실: 현재 시스템 상태 ─────────────────────────────────────────

    private val _state = AtomicReference(SystemHarmony.SystemState())
    val currentState: SystemHarmony.SystemState get() = _state.get()

    // ─── 분석 섹션들의 최신 제안 ─────────────────────────────────────────────

    private val proposals = ConcurrentHashMap<SystemHarmony.SystemSection, SystemHarmony.SystemProposal>()

    // ─── 에러 잠금 ────────────────────────────────────────────────────────────

    private val activeEmergencies = AtomicInteger(0)
    @Volatile private var lastEmergencyMs = 0L

    // ─── 화장실 키워드 추적 (OperationalDirector에서 이전) ───────────────────

    @Volatile private var bathroomKeywordDetectedMs = 0L

    // ─── 현재 HarmonyDecision ─────────────────────────────────────────────────

    @Volatile private var lastDecision: SystemHarmony.HarmonyDecision? = null

    // =========================================================================
    // 생명주기
    // =========================================================================

    fun start() {
        Log.i(TAG, "SystemConductor 시작 (지휘자 활성화 — 역할: ${SystemHarmony.SystemSection.values().joinToString { it.displayName }})")

        // 센서 이벤트 + 분석 섹션 이벤트 구독
        scope.launch {
            eventBus.events.collect { event ->
                try {
                    when (event) {
                        // LAYER 0: 센서 데이터 수신
                        is XRealEvent.SystemEvent.DeviceHealthUpdated -> onDeviceHealthUpdated(event)
                        is XRealEvent.SystemEvent.ResourceAlert -> onResourceAlert(event)
                        is XRealEvent.SystemEvent.NetworkStateChanged -> onNetworkStateChanged(event)

                        // LAYER 2: 분석 섹션 결정 관찰 (FailsafeController, DeviceModeManager)
                        is XRealEvent.SystemEvent.CapabilityTierChanged -> onCapabilityTierChanged(event)
                        is XRealEvent.SystemEvent.DeviceModeChanged -> onDeviceModeChanged(event)

                        // LAYER 3: 에러 활성 추적 (EmergencyOrchestrator 지원)
                        is XRealEvent.SystemEvent.Error -> onSystemError(event)

                        // 화장실 키워드 (OperationalDirector에서 이전된 FocusMode 로직)
                        is XRealEvent.PerceptionEvent.OcrDetected -> {
                            val text = event.results.joinToString(" ") { it.text }.lowercase()
                            if (BATHROOM_KEYWORDS.any { text.contains(it) }) {
                                bathroomKeywordDetectedMs = System.currentTimeMillis()
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

        // 30초 제어 루프 (OperationalDirector 루프 통합)
        conductorJob = scope.launch {
            delay(5_000L)  // 초기 5초 대기 (다른 서비스 시작 대기)
            while (isActive) {
                try {
                    runConductorLoop()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ErrorReporter.report(TAG, "지휘자 루프 오류", e)
                }
                delay(CONDUCTOR_LOOP_MS)
            }
        }
    }

    fun stop() {
        conductorJob?.cancel()
        Log.i(TAG, "SystemConductor 종료")
    }

    // =========================================================================
    // 공개 API
    // =========================================================================

    /**
     * OperationalDirector가 상황 기반 tier 제안을 제출하는 API.
     * 30초 루프에서 즉각 반영됨.
     */
    fun submitProposal(proposal: SystemHarmony.SystemProposal) {
        proposals[proposal.section] = proposal
        Log.d(TAG, "제안 수신: ${proposal.section.displayName} → ${proposal.proposedTier?.name ?: "tier없음"} (${proposal.reason})")
    }

    /** AI 도구 `get_system_health` 응답용 문자열 */
    fun getStatusSummary(): String = currentState.toSummary()

    /** 마지막 HarmonyDecision 반환 */
    fun getLastDecision(): SystemHarmony.HarmonyDecision? = lastDecision

    // =========================================================================
    // 센서 이벤트 핸들러 (LAYER 0 → SystemState 업데이트)
    // =========================================================================

    private fun onDeviceHealthUpdated(event: XRealEvent.SystemEvent.DeviceHealthUpdated) {
        _state.updateAndGet { s -> s.copy(
            batteryPercent = event.batteryPercent,
            isCharging = event.isCharging,
            isGlassesConnected = event.glassesConnected,
            glassesFrameRateFps = event.glassesFrameRateFps,
            isWatchConnected = event.watchConnected,
            thermalStatus = event.thermalStatus,
            isNetworkAvailable = event.networkOnline,
            networkType = event.networkType,
            isEdgeLlmReady = event.edgeLlmReady,
            timestamp = event.timestamp
        )}
        Log.v(TAG, "하드웨어 상태 업데이트: 배터리=${event.batteryPercent}%, 글래스=${event.glassesConnected}, 워치=${event.watchConnected}")
    }

    private fun onResourceAlert(event: XRealEvent.SystemEvent.ResourceAlert) {
        _state.updateAndGet { s -> s.copy(
            cpuPercent = event.cpuPercent,
            ramUsedMb = event.ramUsedMb,
            ramTotalMb = event.ramTotalMb,
            batteryTempC = event.batteryTempC,
            timestamp = event.timestamp
        )}
    }

    private fun onNetworkStateChanged(event: XRealEvent.SystemEvent.NetworkStateChanged) {
        _state.updateAndGet { s -> s.copy(
            isNetworkAvailable = event.isOnline,
            networkType = event.networkType
        )}
    }

    // =========================================================================
    // 분석 섹션 이벤트 핸들러 (LAYER 2 관찰)
    // =========================================================================

    private fun onCapabilityTierChanged(event: XRealEvent.SystemEvent.CapabilityTierChanged) {
        // FailsafeController의 결정 → CRITICAL 제안으로 자동 등록
        val proposal = SystemHarmony.SystemProposal(
            section = SystemHarmony.SystemSection.FAILSAFE,
            proposedTier = event.tier,
            reason = event.reason
        )
        proposals[SystemHarmony.SystemSection.FAILSAFE] = proposal

        // 현재 상태의 tier도 즉각 업데이트 (CRITICAL이라 대기 없이)
        _state.updateAndGet { s -> s.copy(currentTier = event.tier) }
        Log.i(TAG, "FailsafeController 결정 수신 (CRITICAL): ${event.previousTier.name} → ${event.tier.name} (${event.reason})")
    }

    private fun onDeviceModeChanged(event: XRealEvent.SystemEvent.DeviceModeChanged) {
        // DeviceModeManager의 결정 → HIGH 제안으로 자동 등록
        val proposal = SystemHarmony.SystemProposal(
            section = SystemHarmony.SystemSection.PIPELINE,
            proposedMode = event.newMode,
            reason = event.reason
        )
        proposals[SystemHarmony.SystemSection.PIPELINE] = proposal

        _state.updateAndGet { s -> s.copy(currentMode = event.newMode) }
        Log.i(TAG, "DeviceModeManager 결정 수신 (HIGH): ${event.previousMode.name} → ${event.newMode.name} (${event.reason})")
    }

    private fun onSystemError(event: XRealEvent.SystemEvent.Error) {
        val count = activeEmergencies.incrementAndGet()
        lastEmergencyMs = System.currentTimeMillis()
        _state.updateAndGet { s -> s.copy(activeEmergencies = count) }
        Log.w(TAG, "에러 활성 카운트: $count (${event.code}: ${event.message})")

        // 30초 후 자동 해제
        scope.launch {
            delay(EMERGENCY_LOCK_MS)
            val remaining = activeEmergencies.decrementAndGet().coerceAtLeast(0)
            _state.updateAndGet { s -> s.copy(activeEmergencies = remaining) }
        }
    }

    // =========================================================================
    // 30초 제어 루프 (지휘 핵심)
    // =========================================================================

    private suspend fun runConductorLoop() {
        val situation = situationRecognizer?.currentSituation?.value ?: LifeSituation.UNKNOWN

        // 1. OperationalDirector의 상황 분석 로직 (흡수됨)
        val goalTier = getOperationalGoal(situation)
        val actualTier = currentState.currentTier
        val gap = goalTier.ordinal - actualTier.ordinal

        // 상황 기반 제안 생성 (NORMAL 우선순위)
        val situationProposal = SystemHarmony.SystemProposal(
            section = SystemHarmony.SystemSection.SITUATION,
            proposedTier = goalTier,
            reason = "상황=${situation.name}, 목표등급=${goalTier.name}"
        )
        proposals[SystemHarmony.SystemSection.SITUATION] = situationProposal

        // 2. 충돌 해결
        val allProposals = proposals.values.toList()
        val winnerProposal = SystemHarmony.resolveConflicts(allProposals)
        val finalTier = winnerProposal?.proposedTier ?: actualTier
        val finalMode = currentState.currentMode

        // 3. Emergency 잠금 확인 (에러 활성 중 tier 상향 불가)
        val isEmergencyLocked = activeEmergencies.get() > 0 &&
                (System.currentTimeMillis() - lastEmergencyMs) < EMERGENCY_LOCK_MS
        val resolvedTier = if (isEmergencyLocked && finalTier.ordinal < actualTier.ordinal) {
            Log.d(TAG, "Emergency 잠금 활성: tier 상향 차단 (요청=${finalTier.name}, 유지=${actualTier.name})")
            actualTier  // 상향 차단
        } else {
            finalTier
        }

        // 4. HarmonyDecision 발행
        val decision = SystemHarmony.HarmonyDecision(
            tier = resolvedTier,
            mode = finalMode,
            winningSection = winnerProposal?.section ?: SystemHarmony.SystemSection.FAILSAFE,
            reason = winnerProposal?.reason ?: "현재 상태 유지",
            goalTierHint = if (gap < -GAP_THRESHOLD) goalTier else null,
            overriddenSections = if (winnerProposal != null) {
                SystemHarmony.getOverridden(allProposals, winnerProposal)
            } else emptyList()
        )

        lastDecision = decision
        _state.updateAndGet { s -> s.copy(currentTier = resolvedTier, lastHarmonyReason = decision.reason) }

        eventBus.publish(XRealEvent.SystemEvent.HarmonyDecision(
            tier = decision.tier,
            mode = decision.mode,
            winningSection = decision.winningSection.name,
            winningReason = decision.reason,
            goalTierHint = decision.goalTierHint?.name,
            overriddenSections = decision.overriddenSections
        ))

        Log.d(TAG, "HarmonyDecision: 등급=${decision.tier.name}, 모드=${decision.mode.name}, " +
                "지휘자=${decision.winningSection.displayName}, gap=$gap" +
                if (decision.overriddenSections.isNotEmpty()) ", 무시됨=${decision.overriddenSections}" else "")

        // 5. FocusMode 자동 조정 (OperationalDirector에서 이전된 로직)
        adjustFocusMode(situation)
    }

    // =========================================================================
    // 상황 매핑 + FocusMode (OperationalDirector에서 이전)
    // =========================================================================

    private fun getOperationalGoal(situation: LifeSituation): CapabilityTier = when (situation) {
        LifeSituation.RUNNING,
        LifeSituation.GYM_WORKOUT,
        LifeSituation.WALKING_EXERCISE,
        LifeSituation.COMMUTING,
        LifeSituation.TRAVELING_NEW_PLACE,
        LifeSituation.TRAVELING_TRANSIT,
        LifeSituation.AT_DESK_WORKING,
        LifeSituation.GUITAR_PRACTICE,
        LifeSituation.READING,
        LifeSituation.COOKING,
        LifeSituation.SOCIAL_GATHERING,
        LifeSituation.SHOPPING,
        LifeSituation.DINING_OUT,
        LifeSituation.PHONE_CALL,
        LifeSituation.UNKNOWN,
        LifeSituation.CUSTOM -> CapabilityTier.TIER_0_FULL

        LifeSituation.IN_MEETING,
        LifeSituation.STUDYING,
        LifeSituation.LANGUAGE_LEARNING,
        LifeSituation.TEACHING -> CapabilityTier.TIER_3_NO_WATCH

        LifeSituation.RELAXING_HOME,
        LifeSituation.MORNING_ROUTINE,
        LifeSituation.EVENING_WIND_DOWN,
        LifeSituation.LUNCH_BREAK,
        LifeSituation.SLEEPING_PREP -> CapabilityTier.TIER_4_LOW_POWER

        // ★ Phase N: 수면 중 → 최소 전력 (API 거의 안 씀)
        LifeSituation.SLEEPING -> CapabilityTier.TIER_6_MINIMAL
    }

    private fun adjustFocusMode(situation: LifeSituation) {
        val fm = focusModeManager ?: return
        val currentMode = fm.currentMode
        val bathroomRecent = (System.currentTimeMillis() - bathroomKeywordDetectedMs) < 60_000L

        when {
            bathroomRecent && currentMode == FocusMode.NORMAL -> {
                Log.i(TAG, "화장실 상황 → PRIVATE 자동 전환")
                fm.setMode(FocusMode.PRIVATE, "situation_bathroom")
            }
            situation == LifeSituation.SLEEPING_PREP && currentMode == FocusMode.NORMAL -> {
                Log.i(TAG, "취침 준비 → DND 자동 전환")
                fm.setMode(FocusMode.DND, "situation_sleeping_prep")
            }
            situation == LifeSituation.SLEEPING && currentMode != FocusMode.DND -> {
                Log.i(TAG, "수면 중 → DND 자동 전환")
                fm.setMode(FocusMode.DND, "situation_sleeping")
            }
            (situation == LifeSituation.RUNNING || situation == LifeSituation.GYM_WORKOUT)
                    && currentMode == FocusMode.DND -> {
                Log.d(TAG, "운동 중 DND — 코칭 억제 상태 유지")
            }
        }
    }
}
