package com.xreal.nativear.mission

import android.util.Log
import com.xreal.nativear.cadence.UserState
import com.xreal.nativear.cadence.UserStateTracker
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.policy.PolicyReader
import com.xreal.nativear.core.RunningState
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.router.BaseRouter
import com.xreal.nativear.router.DecisionLogger
import com.xreal.nativear.router.RouterDecision

/**
 * Detects situations that warrant activating a Mission team.
 *
 * Trigger rules:
 * | Mission          | Trigger                                           | Cooldown  |
 * |------------------|---------------------------------------------------|-----------|
 * | RUNNING_COACH    | RunningSessionState(ACTIVE)                       | per-session |
 * | TRAVEL_GUIDE     | WALKING_UNFAMILIAR + foreignText >= 3 in 30s     | 1 hour    |
 * | EXPLORATION      | UserState → EXPLORING (unfamiliar location)       | 30 min    |
 * | SOCIAL_ENCOUNTER | PersonIdentified confidence > 0.7 + IN_CONVERSATION | 10 min |
 */
class MissionDetectorRouter(
    eventBus: GlobalEventBus,
    decisionLogger: DecisionLogger,
    private val userStateTracker: UserStateTracker,
    private val conductor: MissionConductor
) : BaseRouter("mission_detector", eventBus, decisionLogger) {

    companion object {
        private const val TRAVEL_FOREIGN_WINDOW_MS = 30_000L
        private const val TRAVEL_FOREIGN_THRESHOLD = 3
        private val TRAVEL_COOLDOWN_MS: Long get() = PolicyReader.getLong("mission.travel_cooldown_ms", 3_600_000L)       // 1시간
        private val EXPLORATION_COOLDOWN_MS: Long get() = PolicyReader.getLong("mission.exploration_cooldown_ms", 1_800_000L)  // 30분
        private val SOCIAL_COOLDOWN_MS: Long get() = PolicyReader.getLong("mission.social_cooldown_ms", 600_000L)        // 10분
    }

    // Cooldown tracking
    private var lastTravelTrigger = 0L
    private var lastExplorationTrigger = 0L
    private var lastSocialTrigger = 0L
    private var runningMissionActive = false

    // Foreign text frequency tracking (for TRAVEL_GUIDE)
    private val foreignTextTimestamps = mutableListOf<Long>()

    override fun shouldProcess(event: XRealEvent): Boolean = when (event) {
        is XRealEvent.SystemEvent.RunningSessionState -> true
        is XRealEvent.SystemEvent.UserStateChanged -> true
        is XRealEvent.PerceptionEvent.OcrDetected -> true
        is XRealEvent.PerceptionEvent.PersonIdentified -> true
        else -> false
    }

    override fun evaluate(event: XRealEvent): RouterDecision? {
        val now = System.currentTimeMillis()

        when (event) {
            // ── RUNNING_COACH: running session started ──
            is XRealEvent.SystemEvent.RunningSessionState -> {
                if (event.state == RunningState.ACTIVE && !runningMissionActive) {
                    runningMissionActive = true
                    return RouterDecision(
                        routerId = id,
                        action = "ACTIVATE_MISSION",
                        confidence = 0.95f,
                        reason = "Running session started",
                        metadata = mapOf(
                            "missionType" to MissionType.RUNNING_COACH.name,
                            "triggerEvent" to "RunningSessionActive"
                        )
                    )
                }
                if (event.state == RunningState.STOPPED && runningMissionActive) {
                    runningMissionActive = false
                    return RouterDecision(
                        routerId = id,
                        action = "DEACTIVATE_MISSION",
                        confidence = 0.95f,
                        reason = "Running session stopped",
                        metadata = mapOf("missionType" to MissionType.RUNNING_COACH.name)
                    )
                }
            }

            // ── EXPLORATION: user enters EXPLORING state ──
            is XRealEvent.SystemEvent.UserStateChanged -> {
                if (event.newState == UserState.EXPLORING.name &&
                    now - lastExplorationTrigger > EXPLORATION_COOLDOWN_MS &&
                    !conductor.isMissionActive(MissionType.EXPLORATION) &&
                    !conductor.isMissionActive(MissionType.TRAVEL_GUIDE)
                ) {
                    lastExplorationTrigger = now
                    return RouterDecision(
                        routerId = id,
                        action = "ACTIVATE_MISSION",
                        confidence = 0.7f,
                        reason = "User entered EXPLORING state",
                        metadata = mapOf(
                            "missionType" to MissionType.EXPLORATION.name,
                            "triggerEvent" to "UserState→EXPLORING"
                        )
                    )
                }
            }

            // ── TRAVEL_GUIDE: frequent foreign text in OCR ──
            is XRealEvent.PerceptionEvent.OcrDetected -> {
                if (event.results.isEmpty()) return null
                val fullText = event.results.joinToString(" ") { it.text }
                if (fullText.isBlank()) return null

                // Simple heuristic: check for non-Korean text
                val koreanRatio = fullText.count { it in '\uAC00'..'\uD7A3' || it in '\u3131'..'\u3163' }
                    .toFloat() / fullText.length.coerceAtLeast(1)

                if (koreanRatio < 0.3f && fullText.length > 3) {
                    foreignTextTimestamps.add(now)
                    foreignTextTimestamps.removeAll { now - it > TRAVEL_FOREIGN_WINDOW_MS }

                    val userState = userStateTracker.state.value
                    if (foreignTextTimestamps.size >= TRAVEL_FOREIGN_THRESHOLD &&
                        (userState == UserState.WALKING_UNFAMILIAR || userState == UserState.EXPLORING) &&
                        now - lastTravelTrigger > TRAVEL_COOLDOWN_MS &&
                        !conductor.isMissionActive(MissionType.TRAVEL_GUIDE)
                    ) {
                        lastTravelTrigger = now
                        foreignTextTimestamps.clear()
                        return RouterDecision(
                            routerId = id,
                            action = "ACTIVATE_MISSION",
                            confidence = 0.85f,
                            reason = "Frequent foreign text detected while in unfamiliar area",
                            metadata = mapOf(
                                "missionType" to MissionType.TRAVEL_GUIDE.name,
                                "triggerEvent" to "ForeignTextFrequency",
                                "foreignTextCount" to TRAVEL_FOREIGN_THRESHOLD.toString()
                            )
                        )
                    }
                }
            }

            // ── SOCIAL_ENCOUNTER: person identified with high confidence ──
            is XRealEvent.PerceptionEvent.PersonIdentified -> {
                val userState = userStateTracker.state.value
                if (event.confidence > 0.7f &&
                    userState == UserState.IN_CONVERSATION &&
                    now - lastSocialTrigger > SOCIAL_COOLDOWN_MS &&
                    !conductor.isMissionActive(MissionType.SOCIAL_ENCOUNTER)
                ) {
                    lastSocialTrigger = now
                    return RouterDecision(
                        routerId = id,
                        action = "ACTIVATE_MISSION",
                        confidence = 0.75f,
                        reason = "Known person identified during conversation: ${event.personName ?: "Person#${event.personId}"}",
                        metadata = mapOf(
                            "missionType" to MissionType.SOCIAL_ENCOUNTER.name,
                            "triggerEvent" to "PersonIdentified",
                            "personId" to event.personId.toString(),
                            "personName" to (event.personName ?: "")
                        )
                    )
                }
            }

            else -> {}
        }

        return null
    }

    override fun act(decision: RouterDecision) {
        when (decision.action) {
            "ACTIVATE_MISSION" -> {
                val typeName = decision.metadata["missionType"] as? String ?: return
                val type = try { MissionType.valueOf(typeName) } catch (_: Exception) { return }
                val meta = decision.metadata.mapValues { it.value.toString() }

                Log.i(TAG, "Triggering mission: $type (${decision.reason})")
                conductor.activateMission(type, meta)
            }

            "DEACTIVATE_MISSION" -> {
                val typeName = decision.metadata["missionType"] as? String ?: return
                val type = try { MissionType.valueOf(typeName) } catch (_: Exception) { return }

                Log.i(TAG, "Deactivating mission type: $type")
                conductor.deactivateMissionsByType(type)
            }
        }
    }
}
