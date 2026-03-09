package com.xreal.nativear.router.running

import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.router.*

class IntensityRouter(
    eventBus: GlobalEventBus,
    decisionLogger: DecisionLogger
) : BaseRouter("running.intensity", eventBus, decisionLogger) {

    private var lastPace: Float = 0f
    private var lastCadence: Float = 0f
    private var lastHr: Float = 0f
    private var sessionStartTime: Long = 0L
    private var previousZone: String = ""

    // HR drift detection: track HR at similar paces
    private var hrAtPaceBuffer = mutableListOf<Pair<Float, Float>>() // (pace, hr)

    init {
        // Pace zones
        config.thresholds["pace_warmup"] = 8.0f
        config.thresholds["pace_easy"] = 7.0f
        config.thresholds["pace_moderate"] = 5.5f
        config.thresholds["pace_hard"] = 4.5f
        config.thresholds["warmup_duration"] = 180f
        config.flags["warmup_enabled"] = true

        // HR zones (percentage of max HR)
        config.thresholds["max_hr"] = 190f
        config.thresholds["hr_zone1_pct"] = 0.50f   // Zone 1: 50-60% recovery
        config.thresholds["hr_zone2_pct"] = 0.60f   // Zone 2: 60-70% aerobic base
        config.thresholds["hr_zone3_pct"] = 0.70f   // Zone 3: 70-80% aerobic
        config.thresholds["hr_zone4_pct"] = 0.80f   // Zone 4: 80-90% threshold
        config.thresholds["hr_zone5_pct"] = 0.90f   // Zone 5: 90-100% max
        config.flags["hr_zone_enabled"] = true
    }

    fun onSessionStart() {
        sessionStartTime = System.currentTimeMillis()
        previousZone = ""
        lastHr = 0f
        hrAtPaceBuffer.clear()
    }

    override fun shouldProcess(event: XRealEvent): Boolean = when (event) {
        is XRealEvent.PerceptionEvent.RunningRouteUpdate -> true
        is XRealEvent.PerceptionEvent.RunningDynamics -> true
        is XRealEvent.PerceptionEvent.WatchHeartRate -> true
        else -> false
    }

    override fun evaluate(event: XRealEvent): RouterDecision? {
        when (event) {
            is XRealEvent.PerceptionEvent.RunningRouteUpdate -> lastPace = event.paceMinPerKm
            is XRealEvent.PerceptionEvent.RunningDynamics -> {
                lastCadence = event.cadence
                return null
            }
            is XRealEvent.PerceptionEvent.WatchHeartRate -> {
                lastHr = event.bpm
                // Track HR drift: record (pace, hr) when pace is available
                if (lastPace > 0 && lastPace < 30) {
                    hrAtPaceBuffer.add(lastPace to lastHr)
                    if (hrAtPaceBuffer.size > 60) hrAtPaceBuffer.removeAt(0)
                }
                // Re-evaluate zone with updated HR
                if (lastPace <= 0 || lastPace > 30) return null
            }
            else -> return null
        }

        if (lastPace <= 0 || lastPace > 30) return null

        val elapsedSec = (System.currentTimeMillis() - sessionStartTime) / 1000f
        val isWarmup = config.flags["warmup_enabled"] == true
                && elapsedSec < config.thresholds["warmup_duration"]!!

        // Pace-based zone
        val paceZone = when {
            isWarmup -> "ZONE_WARMUP"
            lastPace > config.thresholds["pace_warmup"]!! -> "ZONE_WARMUP"
            lastPace > config.thresholds["pace_easy"]!! -> "ZONE_EASY"
            lastPace > config.thresholds["pace_moderate"]!! -> "ZONE_MODERATE"
            lastPace > config.thresholds["pace_hard"]!! -> "ZONE_HARD"
            else -> "ZONE_SPRINT"
        }

        // HR-based zone (if HR data available)
        val hrZone = if (config.flags["hr_zone_enabled"] == true && lastHr > 0) {
            classifyHrZone(lastHr)
        } else null

        // Hybrid: take the higher-intensity zone for safety
        val combinedZone = if (hrZone != null) {
            maxZone(paceZone, hrZone)
        } else paceZone

        // HR drift detection: at similar pace, is HR trending up?
        val isDrifting = detectHrDrift()

        val zoneChanged = combinedZone != previousZone
        previousZone = combinedZone

        return RouterDecision(
            routerId = id,
            action = combinedZone,
            confidence = if (zoneChanged) 0.9f else 0.7f,
            reason = buildString {
                append("페이스 ${String.format("%.1f", lastPace)}분/km")
                if (lastHr > 0) append(", HR ${lastHr.toInt()}bpm")
                append(", 케이던스 ${lastCadence.toInt()}spm")
                if (isWarmup) append(" (워밍업 ${elapsedSec.toInt()}초)")
                if (isDrifting) append(" [HR 드리프트]")
            },
            priority = if (zoneChanged) 1 else -1,
            metadata = mapOf(
                "pace" to lastPace,
                "cadence" to lastCadence,
                "hr_bpm" to lastHr,
                "hr_zone" to (hrZone ?: ""),
                "pace_zone" to paceZone,
                "combined_zone" to combinedZone,
                "zone_changed" to zoneChanged,
                "elapsed_sec" to elapsedSec,
                "hr_drift" to isDrifting
            )
        )
    }

    private fun classifyHrZone(hr: Float): String {
        val maxHr = config.thresholds["max_hr"]!!
        val pct = hr / maxHr
        return when {
            pct < config.thresholds["hr_zone1_pct"]!! -> "ZONE_WARMUP"
            pct < config.thresholds["hr_zone2_pct"]!! -> "ZONE_WARMUP"
            pct < config.thresholds["hr_zone3_pct"]!! -> "ZONE_EASY"
            pct < config.thresholds["hr_zone4_pct"]!! -> "ZONE_MODERATE"
            pct < config.thresholds["hr_zone5_pct"]!! -> "ZONE_HARD"
            else -> "ZONE_SPRINT"
        }
    }

    private fun maxZone(a: String, b: String): String {
        val order = listOf("ZONE_WARMUP", "ZONE_EASY", "ZONE_MODERATE", "ZONE_HARD", "ZONE_SPRINT")
        val ia = order.indexOf(a).coerceAtLeast(0)
        val ib = order.indexOf(b).coerceAtLeast(0)
        return order[maxOf(ia, ib)]
    }

    /** Detect cardiac drift: same pace range but HR trending upward over last 30 samples. */
    private fun detectHrDrift(): Boolean {
        if (hrAtPaceBuffer.size < 20) return false
        val recent = hrAtPaceBuffer.takeLast(30)
        // Split into first and second half
        val half = recent.size / 2
        val firstHalf = recent.subList(0, half)
        val secondHalf = recent.subList(half, recent.size)
        // Only compare if pace is similar (within 1 min/km)
        val avgPace1 = firstHalf.map { it.first }.average()
        val avgPace2 = secondHalf.map { it.first }.average()
        if (Math.abs(avgPace1 - avgPace2) > 1.0) return false
        val avgHr1 = firstHalf.map { it.second }.average()
        val avgHr2 = secondHalf.map { it.second }.average()
        // HR drift: >5 bpm increase at similar pace
        return avgHr2 - avgHr1 > 5.0
    }

    override fun act(decision: RouterDecision) {
        // IntensityRouter is a classifier. Decisions consumed by InterventionRouter.
    }
}
