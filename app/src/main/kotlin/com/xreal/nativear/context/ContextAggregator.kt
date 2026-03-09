package com.xreal.nativear.context

import android.util.Log
import com.xreal.nativear.cadence.DigitalTwinBuilder
import com.xreal.nativear.cadence.UserState
import com.xreal.nativear.cadence.UserStateTracker
import com.xreal.nativear.core.ErrorReporter
import com.xreal.nativear.core.ErrorSeverity
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.LocationManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.util.Calendar
import java.util.concurrent.atomic.AtomicReference

/**
 * ContextAggregator: Subscribes to EventBus and caches latest sensor values,
 * then assembles a complete ContextSnapshot on demand.
 *
 * This is the single source of truth for "what's happening right now"
 * across all sensors, AI models, and user profile data.
 */
class ContextAggregator(
    private val eventBus: GlobalEventBus,
    private val locationManager: LocationManager,
    private val userStateTracker: UserStateTracker,
    private val digitalTwinBuilder: DigitalTwinBuilder,
    private val scope: CoroutineScope,
    private val planManager: com.xreal.nativear.plan.IPlanService? = null,
    private val userProfileManager: com.xreal.nativear.meeting.UserProfileManager? = null,
    private val naverService: com.xreal.nativear.NaverService? = null
) : IContextSnapshot {
    companion object {
        private const val TAG = "ContextAggregator"
    }

    // ── Cached sensor values (updated via EventBus) ──

    // Location
    @Volatile private var latestLatitude: Double? = null
    @Volatile private var latestLongitude: Double? = null
    @Volatile private var latestSpeed: Float? = null
    @Volatile private var latestAltitude: Double? = null

    // Biometrics (from Watch)
    @Volatile private var latestHeartRate: Int? = null
    @Volatile private var latestHrv: Float? = null
    @Volatile private var latestSpO2: Int? = null
    @Volatile private var latestSkinTemperature: Float? = null

    // Emotion
    @Volatile private var latestEmotion: String? = null
    @Volatile private var latestEmotionScore: Float? = null

    // Environment
    private val latestAmbientSounds = AtomicReference<List<String>>(emptyList())
    private val latestVisibleObjects = AtomicReference<List<String>>(emptyList())
    private val latestVisibleText = AtomicReference<List<String>>(emptyList())
    private val latestVisiblePeople = AtomicReference<List<String>>(emptyList())

    // Activity
    @Volatile private var latestUserState: UserState = UserState.IDLE
    private val latestActiveMissions = AtomicReference<List<String>>(emptyList())

    // Language
    @Volatile private var latestForeignTextDetected: Boolean = false
    @Volatile private var latestDetectedLanguage: String? = null

    // Head stability
    @Volatile private var latestHeadStability: Float? = null

    // Weather
    @Volatile private var latestWeather: String? = null

    // Place name (reverse geocoded, cached per 500m movement)
    @Volatile private var cachedPlaceName: String? = null
    @Volatile private var placeNameLat: Double = 0.0
    @Volatile private var placeNameLon: Double = 0.0

    // Speech count (recent 5 min window)
    @Volatile private var recentSpeechCount: Int = 0
    private var lastSpeechCountReset = System.currentTimeMillis()

    // Steps count (recent 5 min)
    @Volatile private var stepsLast5Min: Int = 0

    // ── Biometric Trend Tracker (Gap A) ──
    private val bioTrendTracker = BiometricTrendTracker()

    // ── Spatial Anchor (Gap E: lazy inject) ──
    private val spatialAnchorManager: com.xreal.nativear.spatial.SpatialAnchorManager? by lazy {
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull() } catch (_: Exception) { null }
    }

    private var subscriptionJob: Job? = null
    private var bioTimeseriesJob: Job? = null
    @Volatile private var lastBioSaveTime = 0L
    private val BIO_SAVE_INTERVAL_MS = 5 * 60 * 1000L  // 5분마다 바이오 시계열 저장

    // 바이오 시계열 저장용 DB (lazy inject)
    private val structuredDataDb: com.xreal.nativear.UnifiedMemoryDatabase? by lazy {
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull() } catch (_: Exception) { null }
    }

    override fun start() {
        Log.i(TAG, "ContextAggregator started")
        startBioTimeseriesSaver()
        subscriptionJob = scope.launch(Dispatchers.Default) {
            eventBus.events.collectLatest { event ->
                try {
                    processEvent(event)
                } catch (e: CancellationException) {
                    throw e  // scope 취소 신호 반드시 재발행 (Rule 11 패턴 A)
                } catch (e: Exception) {
                    ErrorReporter.report(TAG, "이벤트 처리 실패 (루프 유지됨)", e, ErrorSeverity.WARNING)
                }
            }
        }
    }

    override fun stop() {
        subscriptionJob?.cancel()
        bioTimeseriesJob?.cancel()
        Log.i(TAG, "ContextAggregator stopped")
    }

    /**
     * 바이오메트릭 시계열을 structured_data에 5분 간격으로 저장.
     * 건강 전문가가 query_structured_data(domain="biometrics")로 트렌드 조회 가능.
     */
    private fun startBioTimeseriesSaver() {
        bioTimeseriesJob = scope.launch(Dispatchers.IO) {
            delay(60_000L) // 1분 대기 (초기화 완료 후)
            while (true) {
                try {
                    saveBioTimeseries()
                } catch (e: Exception) {
                    Log.w(TAG, "바이오 시계열 저장 실패: ${e.message}")
                }
                delay(BIO_SAVE_INTERVAL_MS)
            }
        }
    }

    private fun saveBioTimeseries() {
        val db = structuredDataDb ?: return
        val hr = latestHeartRate ?: return  // HR 없으면 Watch 미연결 → 저장 불필요
        val now = System.currentTimeMillis()

        val value = org.json.JSONObject().apply {
            put("hr", hr)
            latestHrv?.let { put("hrv", it) }
            latestSpO2?.let { put("spo2", it) }
            latestSkinTemperature?.let { put("skin_temp", it) }
            put("steps_5min", stepsLast5Min)
            put("is_moving", (latestSpeed ?: 0f) > 0.5f)
            put("ts", now)
        }.toString()

        val timeKey = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(now))
        val dateKey = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date(now))

        db.upsertStructuredData(
            domain = "biometrics",
            dataKey = "${dateKey}_$timeKey",
            value = value,
            tags = "timeseries,health,$dateKey"
        )
    }

    /**
     * Build an immutable ContextSnapshot from all cached values.
     * This is called by SituationRecognizer every 10 seconds.
     */
    override fun buildSnapshot(): ContextSnapshot {
        val now = Calendar.getInstance()
        val hourOfDay = now.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = now.get(Calendar.DAY_OF_WEEK)
        val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY

        // Check location familiarity via DigitalTwin
        val isFamiliar = try {
            val lat = latestLatitude
            val lon = latestLongitude
            if (lat != null && lon != null) {
                digitalTwinBuilder.isLocationFamiliar(lat, lon)
            } else false
        } catch (_: Exception) { false }

        // Get routine from DigitalTwin profile
        val routine = try {
            val profile = digitalTwinBuilder.profile.value
            profile.practical.dailyRoutine[hourOfDay]
        } catch (_: Exception) { null }

        // Estimate indoor/outdoor from GPS accuracy + speed
        val isIndoors = latestSpeed?.let { it < 0.5f } ?: null

        // Reset speech count if window expired
        val now_ms = System.currentTimeMillis()
        if (now_ms - lastSpeechCountReset > 300_000L) { // 5 minutes
            recentSpeechCount = 0
            lastSpeechCountReset = now_ms
        }

        // Movement intensity from head stability + steps
        val movementIntensity = when {
            stepsLast5Min > 100 -> 0.9f
            stepsLast5Min > 50 -> 0.6f
            stepsLast5Min > 20 -> 0.3f
            else -> latestHeadStability?.let { 1.0f - it } ?: 0.0f
        }

        return ContextSnapshot(
            timestamp = now_ms,
            // Location
            latitude = latestLatitude,
            longitude = latestLongitude,
            speed = latestSpeed,
            altitude = latestAltitude,
            placeName = cachedPlaceName,
            isIndoors = isIndoors,
            floorLevel = null,
            // Time
            hourOfDay = hourOfDay,
            dayOfWeek = dayOfWeek,
            isWeekend = isWeekend,
            timeSlot = TimeSlot.fromHour(hourOfDay),
            // Body
            heartRate = latestHeartRate,
            hrv = latestHrv,
            spo2 = latestSpO2,
            skinTemperature = latestSkinTemperature,
            stepsLast5Min = stepsLast5Min,
            isMoving = (latestSpeed ?: 0f) > 0.5f || stepsLast5Min > 10,
            movementIntensity = movementIntensity,
            // Biometric trends (Gap A)
            hrTrend = bioTrendTracker.getHrTrend(),
            hr5MinAvg = bioTrendTracker.getHrAvg(),
            hrv5MinAvg = bioTrendTracker.getHrvAvg(),
            hrVariability = bioTrendTracker.getHrVariability(),
            // Cognitive
            lastEmotion = latestEmotion,
            lastEmotionScore = latestEmotionScore,
            headStabilityScore = latestHeadStability,
            recentSpeechCount = recentSpeechCount,
            // Environment
            ambientSounds = latestAmbientSounds.get(),
            visibleObjects = latestVisibleObjects.get(),
            visibleText = latestVisibleText.get(),
            visiblePeople = latestVisiblePeople.get(),
            weather = latestWeather,
            // Activity
            currentUserState = latestUserState,
            activeMissions = latestActiveMissions.get(),
            lastActivity = null,
            // Profile
            routineForThisHour = routine,
            familiarLocation = isFamiliar,
            // Spatial anchors (Gap E)
            nearbyAnchorCount = try { spatialAnchorManager?.getAnchorCount() ?: 0 } catch (_: Exception) { 0 },
            nearbyAnchorLabels = try {
                spatialAnchorManager?.getActiveAnchors()
                    ?.sortedByDescending { it.lastSeenAt }
                    ?.take(5)
                    ?.map { it.label }
                    ?: emptyList()
            } catch (_: Exception) { emptyList() },
            // Schedule (populated via PlanManager)
            upcomingTodoTitles = try { planManager?.getUpcomingTodoTitles(5) ?: emptyList() } catch (_: Exception) { emptyList() },
            currentScheduleBlock = try { planManager?.getCurrentScheduleBlock()?.title } catch (_: Exception) { null },
            // Language
            foreignTextDetected = latestForeignTextDetected,
            detectedLanguage = latestDetectedLanguage
        )
    }

    /**
     * Process incoming EventBus events and update cached values.
     */
    private fun processEvent(event: XRealEvent) {
        when (event) {
            // ── Location ──
            is XRealEvent.PerceptionEvent.PhoneGps -> {
                latestLatitude = event.latitude
                latestLongitude = event.longitude
                latestSpeed = event.speed
                latestAltitude = event.altitude
                // 500m 이상 이동 시 역지오코딩 갱신
                updatePlaceNameIfNeeded(event.latitude, event.longitude)
            }

            // ── Watch Biometrics ──
            is XRealEvent.PerceptionEvent.WatchHeartRate -> {
                latestHeartRate = event.bpm.toInt()
                bioTrendTracker.addHeartRate(event.bpm)
            }
            is XRealEvent.PerceptionEvent.WatchHrv -> {
                latestHrv = event.rmssd
                bioTrendTracker.addHrv(event.rmssd)
            }
            is XRealEvent.PerceptionEvent.WatchSpO2 -> {
                latestSpO2 = event.spo2
            }
            is XRealEvent.PerceptionEvent.WatchSkinTemperature -> {
                latestSkinTemperature = event.temperature
            }

            // ── Head Pose (stability) ──
            is XRealEvent.PerceptionEvent.HeadPoseUpdated -> {
                // Calculate stability from head variance (exposed by UserStateTracker)
                val variance = userStateTracker.headMovementVariancePublic
                latestHeadStability = maxOf(0f, 1f - variance / 50f) // Normalize
            }

            // ── Vision ──
            is XRealEvent.PerceptionEvent.ObjectsDetected -> {
                latestVisibleObjects.set(event.results.map { it.label })
            }
            is XRealEvent.PerceptionEvent.OcrDetected -> {
                val texts = event.results.map { it.text }
                latestVisibleText.set(texts)
                // Check for foreign text
                val hasNonKorean = texts.any { text ->
                    val koreanRatio = text.count { c -> c in '\uAC00'..'\uD7A3' }.toFloat() / maxOf(text.length, 1)
                    koreanRatio < 0.3f && text.length > 3
                }
                latestForeignTextDetected = hasNonKorean
                if (hasNonKorean) {
                    latestDetectedLanguage = detectLanguage(texts.firstOrNull { it.length > 3 } ?: "")
                }
            }
            is XRealEvent.PerceptionEvent.PersonIdentified -> {
                val name = event.personName ?: return
                val currentPeople = latestVisiblePeople.get().toMutableList()
                if (name !in currentPeople) {
                    currentPeople.add(name)
                    if (currentPeople.size > 10) currentPeople.removeAt(0)
                    latestVisiblePeople.set(currentPeople)
                }
            }

            // ── Audio (YAMNet-based ambient sound classification) ──
            is XRealEvent.PerceptionEvent.AudioEnvironment -> {
                latestAmbientSounds.set(event.events.map { it.first })
            }

            // ── Voice Activity ──
            is XRealEvent.InputEvent.VoiceCommand,
            is XRealEvent.InputEvent.EnrichedVoiceCommand -> {
                recentSpeechCount++
            }

            // ── User State ──
            is XRealEvent.SystemEvent.UserStateChanged -> {
                latestUserState = try {
                    UserState.valueOf(event.newState)
                } catch (_: Exception) { UserState.IDLE }
            }

            // ── Mission State ──
            is XRealEvent.SystemEvent.MissionStateChanged -> {
                // Update active missions list
                // The event contains mission ID and new state
            }

            // ── Steps (from UserStateTracker) ──
            // Steps are tracked via UserStateTracker.recentStepCountPublic
            else -> { /* Ignore other events */ }
        }

        // Update steps from UserStateTracker
        stepsLast5Min = userStateTracker.recentStepCountPublic
    }

    /**
     * Simple language detection based on Unicode character ranges.
     */
    private fun updatePlaceNameIfNeeded(lat: Double, lon: Double) {
        // 500m 이상 이동했을 때만 역지오코딩 (API 호출 절약)
        val distMoved = distanceMeters(placeNameLat, placeNameLon, lat, lon)
        if (cachedPlaceName != null && distMoved < 500.0) return
        placeNameLat = lat
        placeNameLon = lon
        scope.launch(Dispatchers.IO) {
            try {
                cachedPlaceName = naverService?.reverseGeocode(lat, lon)
            } catch (e: Exception) {
                Log.w(TAG, "Reverse geocode failed: ${e.message}")
            }
        }
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (lat1 == 0.0 && lon1 == 0.0) return Double.MAX_VALUE
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    private fun detectLanguage(text: String): String {
        if (text.isEmpty()) return "unknown"
        val counts = mutableMapOf<String, Int>()
        for (c in text) {
            when {
                c in '\uAC00'..'\uD7A3' -> counts["ko"] = (counts["ko"] ?: 0) + 1
                c in '\u3040'..'\u309F' || c in '\u30A0'..'\u30FF' -> counts["ja"] = (counts["ja"] ?: 0) + 1
                c in '\u4E00'..'\u9FFF' -> counts["zh"] = (counts["zh"] ?: 0) + 1
                c in 'A'..'Z' || c in 'a'..'z' -> counts["en"] = (counts["en"] ?: 0) + 1
            }
        }
        return counts.maxByOrNull { it.value }?.key ?: "unknown"
    }
}
