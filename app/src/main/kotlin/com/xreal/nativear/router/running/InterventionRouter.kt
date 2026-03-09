package com.xreal.nativear.router.running

import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.policy.PolicyReader
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.router.*

class InterventionRouter(
    eventBus: GlobalEventBus,
    decisionLogger: DecisionLogger
) : BaseRouter("running.intervention", eventBus, decisionLogger) {

    private var lastInterventionTime: Long = 0L
    private var currentZone: String = "ZONE_EASY"
    private var consecutiveOkCount: Int = 0
    private val issueConsecutiveCount = mutableMapOf<String, Int>()

    init {
        config.thresholds["cooldown_ms"] = PolicyReader.getFloat("running.intervention_cooldown_ms", 45_000f)
        config.thresholds["escalation_count"] = PolicyReader.getFloat("running.intervention_escalation_count", 3f)
        config.thresholds["deescalation_ok_count"] = 6f
        config.thresholds["deescalation_multiplier"] = 2.0f
        config.flags["suppress_during_sprint"] = true
        config.flags["suppress_during_warmup"] = true
    }

    fun onSessionStart() {
        lastInterventionTime = 0L
        currentZone = "ZONE_EASY"
        consecutiveOkCount = 0
        issueConsecutiveCount.clear()
    }

    fun updateZone(intensityDecision: RouterDecision) {
        currentZone = intensityDecision.action
    }

    fun gate(formDecision: RouterDecision): RouterDecision? {
        val startNs = System.nanoTime()

        // FORM_OK handling
        if (formDecision.action == "FORM_OK") {
            consecutiveOkCount++
            issueConsecutiveCount.clear()

            val latencyNs = System.nanoTime() - startNs
            metrics.record(1.0f, latencyNs, true)
            decisionLogger.log(RouterDecision(
                routerId = id, action = "SUPPRESS",
                confidence = 1.0f,
                reason = "FORM_OK (연속 ${consecutiveOkCount}회)",
                priority = -1
            ))
            return null
        }

        // Issue detected
        consecutiveOkCount = 0
        val issueCount = issueConsecutiveCount.merge(formDecision.action, 1) { old, _ -> old + 1 } ?: 1
        issueConsecutiveCount.keys.filter { it != formDecision.action }.forEach {
            issueConsecutiveCount.remove(it)
        }

        val now = System.currentTimeMillis()
        val baseCooldown = config.thresholds["cooldown_ms"]!!.toLong()

        // Warmup suppression
        if (config.flags["suppress_during_warmup"] == true && currentZone == "ZONE_WARMUP") {
            return suppress(startNs, "워밍업 구간, 폼 코칭 보류")
        }

        // Sprint suppression (unless escalated)
        val escalationThreshold = config.thresholds["escalation_count"]!!.toInt()
        if (config.flags["suppress_during_sprint"] == true
            && currentZone == "ZONE_SPRINT"
            && issueCount < escalationThreshold
        ) {
            return suppress(startNs, "스프린트 구간, 비긴급 코칭 보류 (${issueCount}/${escalationThreshold})")
        }

        // Cooldown check
        val timeSinceLastMs = now - lastInterventionTime
        if (timeSinceLastMs < baseCooldown) {
            return suppress(startNs, "쿨다운 중 (${timeSinceLastMs / 1000}s < ${baseCooldown / 1000}s)")
        }

        // Decide intervention type
        lastInterventionTime = now

        val isEscalated = issueCount >= escalationThreshold
        val interventionType = if (isEscalated) "TTS" else "HUD"

        val result = RouterDecision(
            routerId = id,
            action = "INTERVENE_$interventionType",
            confidence = formDecision.confidence,
            reason = "${formDecision.action} (연속 ${issueCount}회): ${formDecision.reason}",
            priority = if (isEscalated) 3 else 1,
            metadata = formDecision.metadata + mapOf(
                "intervention_type" to interventionType,
                "original_action" to formDecision.action,
                "issue_consecutive_count" to issueCount,
                "is_escalated" to isEscalated,
                "current_zone" to currentZone
            )
        )

        val latencyNs = System.nanoTime() - startNs
        metrics.record(result.confidence, latencyNs, false)
        decisionLogger.log(result)
        return result
    }

    private fun suppress(startNs: Long, reason: String): RouterDecision? {
        val latencyNs = System.nanoTime() - startNs
        val decision = RouterDecision(
            routerId = id, action = "SUPPRESS",
            confidence = 0.5f,
            reason = reason,
            priority = -1
        )
        metrics.record(decision.confidence, latencyNs, true)
        decisionLogger.log(decision)
        return null
    }

    // Not EventBus-driven — called directly by RunningCoachManager
    override fun shouldProcess(event: XRealEvent): Boolean = false
    override fun evaluate(event: XRealEvent): RouterDecision? = null
    override fun act(decision: RouterDecision) {}
}
