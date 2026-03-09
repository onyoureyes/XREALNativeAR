package com.xreal.nativear.router.running

import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.router.*

/**
 * FormRouter — 러닝 폼(자세) + 생체 안전 검사 라우터.
 *
 * ## 검사 우선순위 (위에서 아래로, 먼저 매칭되면 리턴)
 * 1. **생체 안전 (최우선)**: HR>190 위험, SpO2<92% 저산소, 피부온도>38.5° 과열, HRV<20ms 피로
 * 2. **호흡 불규칙**: lastDynamics 없이도 독립적으로 검사 가능 (v2에서 수정)
 * 3. **역학 이상 (dynamics 필요)**: 케이던스<160, 수직진동>8cm, 접지시간>300ms
 * 4. **안정도 이상**: 머리안정도<60, 좌우밸런스>0.3
 *
 * ## 설계 변경
 * v1: 호흡 체크가 `val dynamics = lastDynamics ?: return null` 뒤에 위치 → dynamics 없으면 불가.
 * v2: 호흡 체크를 dynamics 의존 영역 앞으로 이동 → 독립 실행 가능.
 *
 * ## 역할 분리
 * FormRouter는 **수동적 분류기** — RouterDecision을 생성할 뿐 실행하지 않음.
 * 실행: [InterventionRouter.gate] → [RunningCoachManager.executeIntervention]
 *
 * @see InterventionRouter 과잉 코칭 방지 게이트키퍼
 * @see CoachMessages 라우터 결정 → HUD/TTS 메시지 매핑
 */
class FormRouter(
    eventBus: GlobalEventBus,
    decisionLogger: DecisionLogger
) : BaseRouter("running.form", eventBus, decisionLogger) {

    private var lastDynamics: XRealEvent.PerceptionEvent.RunningDynamics? = null
    private var lastStability: XRealEvent.PerceptionEvent.HeadStability? = null
    private var lastBreathing: XRealEvent.PerceptionEvent.BreathingMetrics? = null

    // Watch biometrics
    private var lastWatchHr: Float = 0f
    private var lastWatchSpO2: Int = 0
    private var lastWatchSkinTemp: Float = 0f
    private var lastWatchHrv: Float = 0f

    init {
        // Biomechanical thresholds
        config.thresholds["cadence_low"] = 160f
        config.thresholds["cadence_optimal_low"] = 170f
        config.thresholds["cadence_optimal_hi"] = 185f
        config.thresholds["vo_high"] = 8.0f
        config.thresholds["gct_high"] = 300f
        config.thresholds["stability_low"] = 60f
        config.thresholds["stability_good"] = 80f
        config.thresholds["balance_threshold"] = 0.3f
        config.flags["breathing_irregular"] = true

        // Biometric thresholds (watch)
        config.thresholds["hr_very_high"] = 190f     // Danger: slow down immediately
        config.thresholds["spo2_low"] = 92f           // SpO2 warning
        config.thresholds["skin_temp_high"] = 38.5f   // Overheating warning (°C)
        config.thresholds["hrv_fatigue"] = 20f         // RMSSD < 20ms = fatigue
    }

    override fun shouldProcess(event: XRealEvent): Boolean = when (event) {
        is XRealEvent.PerceptionEvent.RunningDynamics -> true
        is XRealEvent.PerceptionEvent.HeadStability -> true
        is XRealEvent.PerceptionEvent.BreathingMetrics -> true
        is XRealEvent.PerceptionEvent.WatchHeartRate -> true
        is XRealEvent.PerceptionEvent.WatchHrv -> true
        is XRealEvent.PerceptionEvent.WatchSpO2 -> true
        is XRealEvent.PerceptionEvent.WatchSkinTemperature -> true
        else -> false
    }

    override fun evaluate(event: XRealEvent): RouterDecision? {
        when (event) {
            is XRealEvent.PerceptionEvent.RunningDynamics -> lastDynamics = event
            is XRealEvent.PerceptionEvent.HeadStability -> lastStability = event
            is XRealEvent.PerceptionEvent.BreathingMetrics -> lastBreathing = event
            is XRealEvent.PerceptionEvent.WatchHeartRate -> lastWatchHr = event.bpm
            is XRealEvent.PerceptionEvent.WatchHrv -> lastWatchHrv = event.rmssd
            is XRealEvent.PerceptionEvent.WatchSpO2 -> lastWatchSpO2 = event.spo2
            is XRealEvent.PerceptionEvent.WatchSkinTemperature -> lastWatchSkinTemp = event.temperature
            else -> return null
        }

        // --- Biometric safety checks (highest priority) ---

        // 0a. HR danger check
        if (lastWatchHr > config.thresholds["hr_very_high"]!!) {
            return RouterDecision(
                routerId = id,
                action = "COACH_HR_DANGER",
                confidence = 0.95f,
                reason = "심박수 ${lastWatchHr.toInt()}bpm > ${config.thresholds["hr_very_high"]!!.toInt()}bpm 위험",
                priority = 3,
                metadata = mapOf(
                    "hr_bpm" to lastWatchHr,
                    "threshold" to config.thresholds["hr_very_high"]!!
                )
            )
        }

        // 0b. SpO2 low check
        if (lastWatchSpO2 in 1 until config.thresholds["spo2_low"]!!.toInt()) {
            return RouterDecision(
                routerId = id,
                action = "COACH_SPO2_LOW",
                confidence = 0.9f,
                reason = "SpO2 ${lastWatchSpO2}% < ${config.thresholds["spo2_low"]!!.toInt()}% 기준",
                priority = 3,
                metadata = mapOf(
                    "spo2" to lastWatchSpO2,
                    "threshold" to config.thresholds["spo2_low"]!!
                )
            )
        }

        // 0c. Overheating check
        if (lastWatchSkinTemp > config.thresholds["skin_temp_high"]!!) {
            return RouterDecision(
                routerId = id,
                action = "COACH_OVERHEAT",
                confidence = 0.85f,
                reason = "피부온도 ${String.format("%.1f", lastWatchSkinTemp)}°C > ${config.thresholds["skin_temp_high"]!!}°C",
                priority = 2,
                metadata = mapOf(
                    "skin_temp" to lastWatchSkinTemp,
                    "threshold" to config.thresholds["skin_temp_high"]!!
                )
            )
        }

        // 0d. HRV fatigue check (low RMSSD = sympathetic dominance)
        if (lastWatchHrv > 0 && lastWatchHrv < config.thresholds["hrv_fatigue"]!!) {
            return RouterDecision(
                routerId = id,
                action = "COACH_FATIGUE",
                confidence = 0.8f,
                reason = "HRV(RMSSD) ${String.format("%.1f", lastWatchHrv)}ms < ${config.thresholds["hrv_fatigue"]!!.toInt()}ms 피로",
                priority = 1,
                metadata = mapOf(
                    "hrv_rmssd" to lastWatchHrv,
                    "threshold" to config.thresholds["hrv_fatigue"]!!
                )
            )
        }

        // --- Breathing check (independent of dynamics) ---

        // 5b. Breathing regularity (can fire even before RunningDynamics arrives)
        if (config.flags["breathing_irregular"] == true) {
            lastBreathing?.let { breathing ->
                if (!breathing.isRegular && breathing.confidence > 0.4f) {
                    return RouterDecision(
                        routerId = id,
                        action = "COACH_BREATHING",
                        confidence = breathing.confidence,
                        reason = "호흡 불규칙 (BPM: ${breathing.breathsPerMinute.toInt()}, confidence: ${String.format("%.2f", breathing.confidence)})",
                        priority = 1,
                        metadata = mapOf(
                            "breathing_bpm" to breathing.breathsPerMinute,
                            "breathing_confidence" to breathing.confidence
                        )
                    )
                }
            }
        }

        // --- Biomechanical form checks (require dynamics data) ---

        val dynamics = lastDynamics ?: return null

        // 1. Cadence check
        if (dynamics.cadence > 0 && dynamics.cadence < config.thresholds["cadence_low"]!!) {
            return RouterDecision(
                routerId = id,
                action = "COACH_CADENCE",
                confidence = 0.9f,
                reason = "케이던스 ${dynamics.cadence.toInt()}spm < ${config.thresholds["cadence_low"]!!.toInt()}spm 기준",
                priority = 2,
                metadata = mapOf(
                    "cadence" to dynamics.cadence,
                    "threshold" to config.thresholds["cadence_low"]!!
                )
            )
        }

        // 2. Vertical oscillation
        if (dynamics.verticalOscillation > config.thresholds["vo_high"]!!) {
            return RouterDecision(
                routerId = id,
                action = "COACH_VO",
                confidence = 0.85f,
                reason = "수직진동 ${String.format("%.1f", dynamics.verticalOscillation)}cm > ${config.thresholds["vo_high"]!!}cm 기준",
                priority = 2,
                metadata = mapOf(
                    "vertical_oscillation" to dynamics.verticalOscillation,
                    "threshold" to config.thresholds["vo_high"]!!
                )
            )
        }

        // 3. Ground contact time
        if (dynamics.groundContactTime > config.thresholds["gct_high"]!!) {
            return RouterDecision(
                routerId = id,
                action = "COACH_GCT",
                confidence = 0.8f,
                reason = "접지시간 ${dynamics.groundContactTime.toInt()}ms > ${config.thresholds["gct_high"]!!.toInt()}ms 기준",
                priority = 1,
                metadata = mapOf(
                    "gct" to dynamics.groundContactTime,
                    "threshold" to config.thresholds["gct_high"]!!
                )
            )
        }

        // 4. Head stability
        lastStability?.let { stability ->
            if (stability.stabilityScore < config.thresholds["stability_low"]!!) {
                return RouterDecision(
                    routerId = id,
                    action = "COACH_STABILITY",
                    confidence = 0.75f,
                    reason = "머리안정도 ${stability.stabilityScore.toInt()} < ${config.thresholds["stability_low"]!!.toInt()} 기준",
                    priority = 1,
                    metadata = mapOf(
                        "stability_score" to stability.stabilityScore,
                        "threshold" to config.thresholds["stability_low"]!!
                    )
                )
            }

            // 5. Lateral balance
            if (Math.abs(stability.lateralBalance) > config.thresholds["balance_threshold"]!!) {
                val side = if (stability.lateralBalance > 0) "오른쪽" else "왼쪽"
                return RouterDecision(
                    routerId = id,
                    action = "COACH_BALANCE",
                    confidence = 0.7f,
                    reason = "좌우밸런스 ${String.format("%.2f", stability.lateralBalance)} ($side 치우침)",
                    priority = 0,
                    metadata = mapOf(
                        "lateral_balance" to stability.lateralBalance,
                        "threshold" to config.thresholds["balance_threshold"]!!
                    )
                )
            }
        }

        return RouterDecision(
            routerId = id,
            action = "FORM_OK",
            confidence = 0.95f,
            reason = "모든 지표 정상 범위",
            priority = -1
        )
    }

    override fun act(decision: RouterDecision) {
        // FormRouter is a passive classifier. Decisions consumed by InterventionRouter.
    }

    fun getCadenceColor(spm: Float): String = when {
        spm < config.thresholds["cadence_low"]!! -> "#FF4444"
        spm < config.thresholds["cadence_optimal_low"]!! -> "#FFD700"
        spm <= config.thresholds["cadence_optimal_hi"]!! -> "#00FF00"
        else -> "#FFD700"
    }

    fun getStabilityColor(score: Float): String = when {
        score >= config.thresholds["stability_good"]!! -> "#00FF00"
        score >= config.thresholds["stability_low"]!! -> "#FFD700"
        else -> "#FF4444"
    }
}
