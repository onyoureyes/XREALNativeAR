package com.xreal.nativear.context

import com.xreal.nativear.cadence.UserState

/**
 * ContextSnapshot: Immutable snapshot of all available real-time + historical data.
 *
 * Aggregates sensor data, user profile, environment, and system state
 * into a single point-in-time data class for situation classification
 * and expert team decision making.
 */
data class ContextSnapshot(
    val timestamp: Long = System.currentTimeMillis(),

    // ── Location ──
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speed: Float? = null,
    val altitude: Double? = null,
    val placeName: String? = null,
    val isIndoors: Boolean? = null,
    val floorLevel: Int? = null,

    // ── Time ──
    val hourOfDay: Int = 0,
    val dayOfWeek: Int = 1,
    val isWeekend: Boolean = false,
    val timeSlot: TimeSlot = TimeSlot.MORNING,

    // ── Body / Biometrics ──
    val heartRate: Int? = null,
    val hrv: Float? = null,
    val spo2: Int? = null,
    val skinTemperature: Float? = null,
    val stepsLast5Min: Int = 0,
    val isMoving: Boolean = false,
    val movementIntensity: Float = 0f,   // 0.0 ~ 1.0
    // ── Biometric Trends (Gap A: 5분 롤링 분석) ──
    val hrTrend: String? = null,         // "RISING", "FALLING", "STABLE"
    val hr5MinAvg: Int? = null,
    val hrv5MinAvg: Float? = null,
    val hrVariability: Float? = null,    // 변동 계수 (0~1, 높을수록 불안정)

    // ── Cognitive / Emotion ──
    val lastEmotion: String? = null,
    val lastEmotionScore: Float? = null,
    val headStabilityScore: Float? = null,  // 0.0 ~ 1.0 (focus proxy)
    val recentSpeechCount: Int = 0,

    // ── Environment ──
    val ambientSounds: List<String> = emptyList(),
    val visibleObjects: List<String> = emptyList(),
    val visibleText: List<String> = emptyList(),
    val visiblePeople: List<String> = emptyList(),
    val weather: String? = null,

    // ── Activity ──
    val currentUserState: UserState = UserState.IDLE,
    val activeMissions: List<String> = emptyList(),
    val lastActivity: String? = null,

    // ── Profile (from DigitalTwin) ──
    val routineForThisHour: String? = null,
    val familiarLocation: Boolean = false,
    // ── Spatial (Gap E: 공간 앵커 컨텍스트) ──
    val nearbyAnchorCount: Int = 0,
    val nearbyAnchorLabels: List<String> = emptyList(),

    // ── Schedule (Phase 3 — empty until then) ──
    val upcomingTodoTitles: List<String> = emptyList(),
    val currentScheduleBlock: String? = null,

    // ── Language ──
    val foreignTextDetected: Boolean = false,
    val detectedLanguage: String? = null,

    // ── AI System State (Phase 11) ──
    val availableTools: List<String> = emptyList(),
    val activeExperts: List<String> = emptyList(),
    val currentHudMode: String? = null
) {
    /**
     * Build a concise text summary (~150 chars) for AI context injection.
     */
    fun toSummary(): String {
        val parts = mutableListOf<String>()
        timeSlot.displayName.let { parts.add(it) }
        placeName?.let { parts.add(it) }
        if (isMoving) parts.add("이동중(${speed?.let { "%.1f".format(it) } ?: "?"}m/s)")
        heartRate?.let {
            val trend = hrTrend?.let { t -> when(t) { "RISING" -> "↑"; "FALLING" -> "↓"; else -> "→" } } ?: ""
            parts.add("HR:$it$trend")
        }
        if (visiblePeople.isNotEmpty()) parts.add("사람:${visiblePeople.size}명")
        if (ambientSounds.isNotEmpty()) parts.add("소리:${ambientSounds.take(2).joinToString(",")}")
        if (foreignTextDetected) parts.add("외국어감지")
        lastEmotion?.let { parts.add("감정:$it") }
        weather?.let { parts.add(it) }
        return parts.joinToString(" | ").take(200)
    }
}
