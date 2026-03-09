package com.xreal.nativear.cadence

/**
 * Inferred user activity state, each with a default cadence profile.
 */
enum class UserState {
    IDLE,
    WALKING_FAMILIAR,
    WALKING_UNFAMILIAR,
    EXPLORING,
    IN_CONVERSATION,
    FOCUSED_TASK,
    TRAVELING_TRANSIT,
    RUNNING;

    fun defaultProfile(): CadenceProfile = when (this) {
        IDLE -> CadenceProfile(
            pdrStepThreshold = 15,
            stabilityDurationMs = 3000L,
            ocrIntervalMs = 5000L,
            detectIntervalMs = 5000L,
            visualEmbeddingIntervalMs = 10000L,
            frameSkip = 4
        )
        WALKING_FAMILIAR -> CadenceProfile(
            pdrStepThreshold = 25,
            stabilityDurationMs = 2500L,
            ocrIntervalMs = 3000L,
            detectIntervalMs = 3000L,
            visualEmbeddingIntervalMs = 8000L,
            frameSkip = 3
        )
        WALKING_UNFAMILIAR -> CadenceProfile(
            pdrStepThreshold = 10,
            stabilityDurationMs = 1500L,
            ocrIntervalMs = 1500L,
            detectIntervalMs = 1500L,
            visualEmbeddingIntervalMs = 4000L,
            frameSkip = 2
        )
        EXPLORING -> CadenceProfile(
            pdrStepThreshold = 8,
            stabilityDurationMs = 1000L,
            ocrIntervalMs = 1000L,
            detectIntervalMs = 1000L,
            visualEmbeddingIntervalMs = 3000L,
            frameSkip = 1
        )
        IN_CONVERSATION -> CadenceProfile(
            pdrStepThreshold = 20,
            stabilityDurationMs = 3000L,
            ocrIntervalMs = 4000L,
            detectIntervalMs = 2000L,
            poseIntervalMs = 300L,
            visualEmbeddingIntervalMs = 5000L,
            frameSkip = 2
        )
        FOCUSED_TASK -> CadenceProfile(
            pdrStepThreshold = 15,
            stabilityDurationMs = 5000L,
            ocrIntervalMs = 8000L,
            detectIntervalMs = 8000L,
            visualEmbeddingIntervalMs = 15000L,
            frameSkip = 5
        )
        TRAVELING_TRANSIT -> CadenceProfile(
            pdrStepThreshold = 50,
            stabilityDurationMs = 2000L,
            ocrIntervalMs = 3000L,
            detectIntervalMs = 3000L,
            visualEmbeddingIntervalMs = 5000L,
            frameSkip = 3
        )
        RUNNING -> CadenceProfile() // Default — Running Coach manages its own pipeline
    }

    /** UserState → LifeSituation 변환 (core-models에서 순환 의존 방지를 위해 여기에 배치) */
    fun toLifeSituation(): com.xreal.nativear.context.LifeSituation = when (this) {
        IDLE -> com.xreal.nativear.context.LifeSituation.RELAXING_HOME
        WALKING_FAMILIAR -> com.xreal.nativear.context.LifeSituation.COMMUTING
        WALKING_UNFAMILIAR -> com.xreal.nativear.context.LifeSituation.TRAVELING_NEW_PLACE
        EXPLORING -> com.xreal.nativear.context.LifeSituation.TRAVELING_NEW_PLACE
        IN_CONVERSATION -> com.xreal.nativear.context.LifeSituation.SOCIAL_GATHERING
        FOCUSED_TASK -> com.xreal.nativear.context.LifeSituation.AT_DESK_WORKING
        TRAVELING_TRANSIT -> com.xreal.nativear.context.LifeSituation.TRAVELING_TRANSIT
        RUNNING -> com.xreal.nativear.context.LifeSituation.RUNNING
    }
}
