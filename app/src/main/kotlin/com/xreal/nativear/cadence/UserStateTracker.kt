package com.xreal.nativear.cadence

import android.util.Log
import com.xreal.nativear.ILocationService
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.RunningState
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.router.BaseRouter
import com.xreal.nativear.router.DecisionLogger
import com.xreal.nativear.router.RouterDecision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Infers the current user activity state from EventBus signals.
 * Publishes state changes as RouterDecisions and via StateFlow.
 */
class UserStateTracker(
    eventBus: GlobalEventBus,
    decisionLogger: DecisionLogger,
    private val locationService: ILocationService,
    private val cadenceConfig: CadenceConfig
) : BaseRouter("user_state", eventBus, decisionLogger) {

    private val _state = MutableStateFlow(UserState.IDLE)
    val state: StateFlow<UserState> = _state

    // Signal accumulators (windowed counters)
    // Public accessors for ContextAggregator (Phase 1)
    private var recentStepCount = 0
    val recentStepCountPublic: Int get() = recentStepCount

    private var lastStepTime = 0L
    private var recentSpeechCount = 0
    val recentSpeechCountPublic: Int get() = recentSpeechCount

    private var lastSpeechTime = 0L
    private var headMovementVariance = 0f
    val headMovementVariancePublic: Float get() = headMovementVariance

    private var lastLocationSpeed = 0f
    val lastLocationSpeedPublic: Float get() = lastLocationSpeed

    private var isRunningCoachActive = false
    private var lastOcrTextSeen = 0L
    private var lastStateChangeTime = 0L

    // Optional: location familiarity checker (set via setter after DigitalTwinBuilder Koin init)
    var locationFamiliarityChecker: ((lat: Double, lon: Double) -> Boolean)? = null

    companion object {
        // ★ Policy Department: PolicyRegistry shadow read
        private val STATE_HYSTERESIS_MS: Long get() =
            com.xreal.nativear.policy.PolicyReader.getLong("cadence.state_hysteresis_ms", 5000L)
        private val STEP_WINDOW_MS: Long get() =
            com.xreal.nativear.policy.PolicyReader.getLong("cadence.step_window_ms", 10000L)
        private val SPEECH_WINDOW_MS: Long get() =
            com.xreal.nativear.policy.PolicyReader.getLong("cadence.speech_window_ms", 15000L)
    }

    override fun shouldProcess(event: XRealEvent): Boolean = when (event) {
        is XRealEvent.PerceptionEvent.HeadPoseUpdated -> true
        is XRealEvent.PerceptionEvent.LocationUpdated -> true
        is XRealEvent.InputEvent.AudioEmbedding -> true
        is XRealEvent.SystemEvent.VoiceActivity -> true
        is XRealEvent.SystemEvent.RunningSessionState -> true
        is XRealEvent.PerceptionEvent.OcrDetected -> true
        else -> false
    }

    override fun evaluate(event: XRealEvent): RouterDecision? {
        val now = System.currentTimeMillis()

        when (event) {
            is XRealEvent.PerceptionEvent.LocationUpdated -> {
                val loc = locationService.getCurrentLocation()
                lastLocationSpeed = loc?.speed ?: 0f
            }
            is XRealEvent.InputEvent.AudioEmbedding -> {
                recentSpeechCount++
                lastSpeechTime = now
            }
            is XRealEvent.SystemEvent.VoiceActivity -> {
                if (event.isSpeaking) {
                    recentSpeechCount++
                    lastSpeechTime = now
                }
            }
            is XRealEvent.SystemEvent.RunningSessionState -> {
                isRunningCoachActive = event.state == RunningState.ACTIVE
            }
            is XRealEvent.PerceptionEvent.HeadPoseUpdated -> {
                val movement = Math.abs(event.qx) + Math.abs(event.qy)
                headMovementVariance = headMovementVariance * 0.9f + movement * 0.1f
            }
            is XRealEvent.PerceptionEvent.OcrDetected -> {
                if (event.results.isNotEmpty()) lastOcrTextSeen = now
            }
            else -> {}
        }

        // Decay windowed counters
        if (now - lastStepTime > STEP_WINDOW_MS) recentStepCount = 0
        if (now - lastSpeechTime > SPEECH_WINDOW_MS) recentSpeechCount = 0

        val newState = inferState(now)
        if (newState != _state.value && now - lastStateChangeTime > STATE_HYSTERESIS_MS) {
            val oldState = _state.value
            _state.value = newState
            lastStateChangeTime = now

            return RouterDecision(
                routerId = id,
                action = "STATE_CHANGE",
                confidence = 0.85f,
                reason = "UserState: $oldState -> $newState",
                metadata = mapOf(
                    "old_state" to oldState.name,
                    "new_state" to newState.name,
                    "speed" to lastLocationSpeed,
                    "speech_count" to recentSpeechCount,
                    "head_variance" to headMovementVariance
                )
            )
        }
        return null
    }

    private fun inferState(now: Long): UserState {
        if (isRunningCoachActive) return UserState.RUNNING
        if (lastLocationSpeed > 8f) return UserState.TRAVELING_TRANSIT
        if (recentSpeechCount >= 2 && now - lastSpeechTime < SPEECH_WINDOW_MS) return UserState.IN_CONVERSATION
        if (headMovementVariance > 0.3f && recentStepCount > 0) return UserState.EXPLORING

        val isWalking = recentStepCount > 3
        if (isWalking) {
            val loc = locationService.getCurrentLocation()
            if (loc != null) {
                val familiar = locationFamiliarityChecker?.invoke(loc.latitude, loc.longitude) ?: false
                if (familiar) return UserState.WALKING_FAMILIAR
            }
            return UserState.WALKING_UNFAMILIAR
        }

        if (now - lastOcrTextSeen < 10000L && headMovementVariance < 0.1f) return UserState.FOCUSED_TASK
        return UserState.IDLE
    }

    override fun act(decision: RouterDecision) {
        val newState = decision.metadata["new_state"] as? String ?: return
        Log.i(TAG, "State transition: ${decision.reason}")
        eventBus.publish(XRealEvent.SystemEvent.UserStateChanged(
            oldState = decision.metadata["old_state"] as? String ?: "",
            newState = newState
        ))
    }

    /** Called by HardwareManager when a step is detected. */
    fun onStepDetected() {
        recentStepCount++
        lastStepTime = System.currentTimeMillis()
    }
}
