package com.xreal.nativear.cadence

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Immutable snapshot of all recording cadence parameters.
 * Default values match the previously hardcoded constants.
 */
data class CadenceProfile(
    // HardwareManager thresholds
    // ★ Policy Department: 기본값을 PolicyRegistry에서 읽음 (fallback = 기존 하드코딩)
    val pdrStepThreshold: Int = com.xreal.nativear.policy.PolicyReader.getInt("cadence.pdr_step_threshold", 15),
    val stabilityDurationMs: Long = com.xreal.nativear.policy.PolicyReader.getLong("cadence.stability_duration_ms", 2000L),
    val stabilityAccelThreshold: Float = com.xreal.nativear.policy.PolicyReader.getFloat("cadence.stability_accel_threshold", 0.5f),
    val slamFrameInterval: Int = com.xreal.nativear.policy.PolicyReader.getInt("cadence.slam_frame_interval", 3),
    val rgbFrameInterval: Int = com.xreal.nativear.policy.PolicyReader.getInt("cadence.rgb_frame_interval", 3),

    // VisionManager intervals
    val ocrIntervalMs: Long = com.xreal.nativear.policy.PolicyReader.getLong("cadence.ocr_interval_ms", 2000L),
    val detectIntervalMs: Long = com.xreal.nativear.policy.PolicyReader.getLong("cadence.detect_interval_ms", 2000L),
    val poseIntervalMs: Long = com.xreal.nativear.policy.PolicyReader.getLong("cadence.pose_interval_ms", 500L),
    val visualEmbeddingIntervalMs: Long = com.xreal.nativear.policy.PolicyReader.getLong("cadence.visual_embedding_interval_ms", 5000L),
    val frameSkip: Int = com.xreal.nativear.policy.PolicyReader.getInt("cadence.frame_skip", 2),
    val handTrackingIntervalMs: Long = com.xreal.nativear.policy.PolicyReader.getLong("cadence.hand_tracking_interval_ms", 33L),

    // Meeting intervals
    val tiltCooldownMs: Long = com.xreal.nativear.policy.PolicyReader.getLong("cadence.tilt_cooldown_ms", 5000L),
    val scheduleExtractCooldownMs: Long = com.xreal.nativear.policy.PolicyReader.getLong("cadence.schedule_extract_cooldown_ms", 30_000L)
)

/**
 * Thread-safe, reactive configuration for all recording cadence parameters.
 * Koin singleton — HardwareManager and VisionManager read from [current].
 */
class CadenceConfig {
    private val _profile = MutableStateFlow(CadenceProfile())
    val profile: StateFlow<CadenceProfile> = _profile.asStateFlow()

    /** Hot-path accessor — no coroutine context needed. */
    val current: CadenceProfile get() = _profile.value

    fun update(transform: CadenceProfile.() -> CadenceProfile) {
        _profile.value = _profile.value.transform()
    }

    fun applyProfile(newProfile: CadenceProfile) {
        _profile.value = newProfile
    }
}
