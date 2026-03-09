package com.xreal.nativear.cadence

import android.util.Log
import com.xreal.nativear.strategist.DirectiveStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Bridges UserStateTracker → CadenceConfig.
 * When user state changes, applies the state's default CadenceProfile.
 * Also reads Strategist directives targeting "cadence_controller" for fine-tuning.
 *
 * Directive format examples:
 *   cadence:ocr_interval=1000
 *   cadence:increase_capture_rate
 *   cadence:decrease_capture_rate
 *   cadence:frame_skip=1
 */
class AdaptiveCadenceController(
    private val userStateTracker: UserStateTracker,
    private val cadenceConfig: CadenceConfig,
    private val directiveStore: DirectiveStore
) {
    private val TAG = "AdaptiveCadenceCtrl"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var stateJob: Job? = null
    private var directiveJob: Job? = null

    companion object {
        // ★ Policy Department: PolicyRegistry shadow read
        private val DIRECTIVE_CHECK_INTERVAL_MS: Long get() =
            com.xreal.nativear.policy.PolicyReader.getLong("cadence.directive_check_interval_ms", 60_000L)
    }

    fun start() {
        // 1. React to user state changes
        stateJob = scope.launch {
            userStateTracker.state.collectLatest { newState ->
                if (newState == UserState.RUNNING) {
                    Log.d(TAG, "User is RUNNING — skipping cadence override (Running Coach controls)")
                    return@collectLatest
                }

                val baseProfile = newState.defaultProfile()
                val tuned = applyDirectiveTuning(baseProfile)
                cadenceConfig.applyProfile(tuned)
                Log.i(TAG, "Applied profile for $newState: step=${tuned.pdrStepThreshold}, ocr=${tuned.ocrIntervalMs}ms, detect=${tuned.detectIntervalMs}ms, skip=${tuned.frameSkip}")
            }
        }

        // 2. Periodically check for Strategist directives
        directiveJob = scope.launch {
            delay(30_000) // Initial delay
            while (isActive) {
                try {
                    val directives = directiveStore.getDirectivesForPersona("cadence_controller")
                    if (directives.isNotEmpty()) {
                        val currentProfile = cadenceConfig.current
                        val tuned = applyDirectiveTuning(currentProfile)
                        if (tuned != currentProfile) {
                            cadenceConfig.applyProfile(tuned)
                            Log.i(TAG, "Applied directive tuning: ocr=${tuned.ocrIntervalMs}ms, detect=${tuned.detectIntervalMs}ms")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Directive check failed: ${e.message}")
                }
                delay(DIRECTIVE_CHECK_INTERVAL_MS)
            }
        }

        Log.i(TAG, "AdaptiveCadenceController started")
    }

    /**
     * Apply Strategist-generated fine-tuning to a base profile.
     */
    private fun applyDirectiveTuning(baseProfile: CadenceProfile): CadenceProfile {
        val directives = directiveStore.getDirectivesForPersona("cadence_controller")
        if (directives.isEmpty()) return baseProfile

        var profile = baseProfile

        for (directive in directives) {
            val instruction = directive.instruction.trim()
            try {
                when {
                    instruction.startsWith("cadence:") -> {
                        val cmd = instruction.removePrefix("cadence:")
                        profile = parseCadenceCommand(profile, cmd)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply directive: $instruction — ${e.message}")
            }
        }

        return profile
    }

    private fun parseCadenceCommand(profile: CadenceProfile, cmd: String): CadenceProfile {
        return when {
            cmd.startsWith("ocr_interval=") -> {
                val value = cmd.substringAfter("=").toLong()
                profile.copy(ocrIntervalMs = value.coerceIn(500, 30000))
            }
            cmd.startsWith("detect_interval=") -> {
                val value = cmd.substringAfter("=").toLong()
                profile.copy(detectIntervalMs = value.coerceIn(500, 30000))
            }
            cmd.startsWith("pose_interval=") -> {
                val value = cmd.substringAfter("=").toLong()
                profile.copy(poseIntervalMs = value.coerceIn(200, 5000))
            }
            cmd.startsWith("step_threshold=") -> {
                val value = cmd.substringAfter("=").toInt()
                profile.copy(pdrStepThreshold = value.coerceIn(3, 100))
            }
            cmd.startsWith("frame_skip=") -> {
                val value = cmd.substringAfter("=").toInt()
                profile.copy(frameSkip = value.coerceIn(1, 10))
            }
            cmd.startsWith("embedding_interval=") -> {
                val value = cmd.substringAfter("=").toLong()
                profile.copy(visualEmbeddingIntervalMs = value.coerceIn(1000, 60000))
            }
            cmd == "increase_capture_rate" -> {
                profile.copy(
                    ocrIntervalMs = (profile.ocrIntervalMs * 0.6).toLong().coerceAtLeast(500),
                    detectIntervalMs = (profile.detectIntervalMs * 0.6).toLong().coerceAtLeast(500),
                    frameSkip = (profile.frameSkip - 1).coerceAtLeast(1)
                )
            }
            cmd == "decrease_capture_rate" -> {
                profile.copy(
                    ocrIntervalMs = (profile.ocrIntervalMs * 1.5).toLong().coerceAtMost(30000),
                    detectIntervalMs = (profile.detectIntervalMs * 1.5).toLong().coerceAtMost(30000),
                    frameSkip = (profile.frameSkip + 1).coerceAtMost(10)
                )
            }
            else -> {
                Log.w(TAG, "Unknown cadence command: $cmd")
                profile
            }
        }
    }

    fun release() {
        stateJob?.cancel()
        directiveJob?.cancel()
        scope.cancel()
        Log.i(TAG, "AdaptiveCadenceController released")
    }
}
