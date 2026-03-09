package com.xreal.nativear.running

import android.content.Context
import android.util.Log
import com.xreal.nativear.LocationManager
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.WeatherService
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.RunningCommandType
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.router.RouterDecision
import com.xreal.nativear.router.RouterMetrics
import com.xreal.nativear.router.running.CoachMessages
import com.xreal.nativear.router.running.FormRouter
import com.xreal.nativear.router.running.IntensityRouter
import com.xreal.nativear.router.running.InterventionRouter
import com.xreal.nativear.core.ErrorReporter
import kotlinx.coroutines.*

/**
 * RunningCoachManager — 러닝 코치 시스템의 중앙 오케스트레이터.
 *
 * ## 설계 의도
 * 6개 서브컴포넌트와 3개 라우터를 조율하여 실시간 러닝 코칭을 제공.
 * 모든 센서 데이터가 EventBus를 통해 이 매니저로 흘러오며, 라우터 체인을 거쳐
 * HUD 표시 또는 TTS 음성 코칭으로 변환됨.
 *
 * ## 서브컴포넌트
 * | 컴포넌트 | 역할 |
 * |---------|------|
 * | [RunningSession] | 세션 상태 (ACTIVE/PAUSED/STOPPED), 타이머, 랩 |
 * | [RunningDynamicsAnalyzer] | 케이던스, 수직진동, 접지시간, GRF, 머리안정도 |
 * | [RunningCoachHUD] | AR HUD 렌더링 (Dumb View — 자체 EventBus 구독 없음) |
 * | [RunningRouteTracker] | FusedPosition → 거리/페이스/고도 누적 |
 * | [BreathingAnalyzer] | 오디오 기반 호흡률 검출 |
 * | [RunningCoachPersona] | Gemini AI 보조 코칭 (5분 간격, 보충적) |
 *
 * ## 라우터 체인 (실시간 코칭의 핵심)
 * ```
 * Event → FormRouter.evaluate()     → RouterDecision (COACH_CADENCE, COACH_HR_DANGER, etc.)
 *       → InterventionRouter.gate() → INTERVENE_HUD / INTERVENE_TTS / SUPPRESS
 *       → executeIntervention()     → HUD 메시지 또는 TTS 음성
 *       (3회 반복 시 escalation → RunningCoachPersona.generateTargetedAdvice())
 * ```
 *
 * ## 위치 융합 (GPS 텔레포트 해결)
 * - [PositionFusionEngine]: Phone GPS + Watch GPS + PDR → 4-state Kalman filter
 * - [BarometricFloorDetector]: 기압계 → 층간 이동 감지
 * - 세션 시작/종료 시 start/stop 호출 (세션 외에는 배터리 절약을 위해 비활성)
 *
 * ## HUD 업데이트 패턴
 * HUD는 **Dumb View** — 자체 EventBus 구독이 없으며, 모든 업데이트는
 * 이 매니저의 routerProcessingLoop 또는 hudUpdateLoop에서 직접 메서드 호출로 전달.
 * 이전에 HUD가 독립적으로 EventBus를 구독하면서 이벤트 이중 처리 문제가 있었음.
 *
 * @see FormRouter 생체역학 + 바이오메트릭 안전 검사
 * @see IntensityRouter HR 기반 운동 강도 존 분류
 * @see InterventionRouter 과잉 코칭 방지 (45초 쿨다운, 3회 에스컬레이션)
 */
class RunningCoachManager(
    private val context: Context,
    private val eventBus: GlobalEventBus,
    private val locationManager: LocationManager,
    private val weatherService: WeatherService,
    private val formRouter: FormRouter,
    private val intensityRouter: IntensityRouter,
    private val interventionRouter: InterventionRouter,
    private val database: UnifiedMemoryDatabase
) {
    private val TAG = "RunningCoachManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val session = RunningSession(eventBus)
    val dynamicsAnalyzer = RunningDynamicsAnalyzer(eventBus)
    val hud = RunningCoachHUD(eventBus)
    val routeTracker = RunningRouteTracker(eventBus)
    val breathingAnalyzer = BreathingAnalyzer(eventBus)
    val speedGraphOverlay = SpeedGraphOverlay(eventBus)

    // Position fusion engine (GPS+PDR Kalman filter) - lazy Koin inject
    private val positionFusionEngine: PositionFusionEngine? by lazy {
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<PositionFusionEngine>() } catch (e: Exception) { null }
    }

    // Barometric floor detector - lazy Koin inject
    private val barometricFloorDetector: BarometricFloorDetector? by lazy {
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<BarometricFloorDetector>() } catch (e: Exception) { null }
    }

    // Track anchor service (GPS lap detection + spatial anchors) - lazy Koin inject
    private val trackAnchorService: RunningTrackAnchorService? by lazy {
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<RunningTrackAnchorService>() } catch (e: Exception) { null }
    }

    // Pacemaker service (target pace ghost runner) - lazy Koin inject
    private val pacemakerService: PacemakerService? by lazy {
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<PacemakerService>() } catch (e: Exception) { null }
    }

    // Digital Twin prediction data (lazy Koin inject)
    private val predictionSyncService: com.xreal.nativear.sync.PredictionSyncService? by lazy {
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.sync.PredictionSyncService>() } catch (e: Exception) { null }
    }

    // AI coaching persona (lazy init via Koin to avoid circular dependency)
    private val coachPersona: RunningCoachPersona? by lazy {
        try {
            val aiAgentManager: com.xreal.nativear.AIAgentManager = org.koin.java.KoinJavaComponent.getKoin().get()
            RunningCoachPersona(eventBus, aiAgentManager, weatherService, this)
        } catch (e: Exception) {
            Log.w(TAG, "AI coaching not available: ${e.message}")
            null
        }
    }

    // Watch biometrics (latest values, updated from GlobalEventBus)
    var lastWatchHr: Float = 0f
    var lastWatchHrv: Float = 0f         // RMSSD (ms)
    var lastWatchHrvSdnn: Float = 0f     // SDNN (ms)
    var lastWatchSpO2: Int = 0
    var lastWatchSkinTemp: Float = 0f    // °C
    var lastWatchAmbientTemp: Float = 0f // °C

    // Biometric samples for session persistence
    private val hrSamples = mutableListOf<Float>()
    private val hrvSamples = mutableListOf<Float>()
    private val spo2Samples = mutableListOf<Int>()
    private val skinTempSamples = mutableListOf<Float>()

    private var hudUpdateJob: Job? = null
    private var routerProcessingJob: Job? = null
    private var isRunning = false

    init {
        subscribeToRunningCommands()
        // Wire breathing analyzer audio provider to AudioAnalysisService's WhisperEngine
        breathingAnalyzer.audioProvider = {
            com.xreal.nativear.AudioAnalysisService.audioProvider?.invoke(BreathingAnalyzer.SAMPLE_RATE * 10) // 10 seconds
        }
    }

    private fun subscribeToRunningCommands() {
        scope.launch {
            eventBus.events.collect { event ->
                try {
                    when (event) {
                        is XRealEvent.InputEvent.RunningVoiceCommand -> {
                            handleRunningCommand(event.command)
                        }
                        else -> {}
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "음성 명령 처리 오류 (루프 유지됨): ${event::class.simpleName} — ${e.message}", e)
                }
            }
        }
    }

    fun startRun() {
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "Starting running session")

        // Clear biometric samples for new session
        hrSamples.clear()
        hrvSamples.clear()
        spo2Samples.clear()
        skinTempSamples.clear()
        lastWatchHr = 0f
        lastWatchHrv = 0f
        lastWatchHrvSdnn = 0f
        lastWatchSpO2 = 0
        lastWatchSkinTemp = 0f
        lastWatchAmbientTemp = 0f

        // Start position fusion engine (GPS+PDR Kalman filter)
        positionFusionEngine?.start()
        // Start barometric floor detector
        barometricFloorDetector?.start()

        session.start()
        dynamicsAnalyzer.start()
        routeTracker.start()
        breathingAnalyzer.start()
        trackAnchorService?.start()
        pacemakerService?.start(routeTracker, session)
        hud.show()
        // 속도 그래프: trackAnchorService 필요 (없으면 표시 안함)
        trackAnchorService?.let { tas ->
            speedGraphOverlay.show(tas, routeTracker)
        }
        startHUDUpdateLoop()
        checkWeatherConditions()

        // Start router framework
        intensityRouter.onSessionStart()
        interventionRouter.onSessionStart()
        startRouterProcessingLoop()

        // AI persona now at 5-min interval (supplementary)
        coachPersona?.startCoaching()

        // Digital Twin 예측 데이터로 라우터 초기화
        applyPredictionBasedConfig()

        scope.launch {
            eventBus.publish(XRealEvent.SystemEvent.DebugLog("Running Coach: Session started (router-enhanced)"))
        }
    }

    fun pauseRun() {
        session.pause()
        hud.showCoachMessage("Paused")
    }

    fun resumeRun() {
        session.resume()
        hud.showCoachMessage("Resumed!")
    }

    fun stopRun(): RunningSession.SessionSummary {
        isRunning = false
        hudUpdateJob?.cancel()
        routerProcessingJob?.cancel()
        positionFusionEngine?.stop()
        barometricFloorDetector?.stop()
        trackAnchorService?.stop()
        pacemakerService?.stop()
        speedGraphOverlay.hide()
        dynamicsAnalyzer.stop()
        routeTracker.stop()
        breathingAnalyzer.stop()
        coachPersona?.stopCoaching()
        val summary = session.stop()
        hud.hide()

        // Sync distance from route tracker
        session.totalDistanceMeters = routeTracker.totalDistanceMeters

        // Generate post-run analysis via AI
        coachPersona?.generatePostRunAnalysis(summary)

        Log.i(TAG, "Session ended: ${summary.totalDistanceMeters}m in ${summary.totalDurationMs}ms")

        // Persist session and route data
        persistSessionData(summary)
        persistRouteData()

        scope.launch {
            eventBus.publish(XRealEvent.SystemEvent.DebugLog(
                "Running Coach: ${String.format("%.2f", summary.totalDistanceMeters / 1000f)}km in ${summary.totalDurationMs / 60000}min"
            ))
        }
        return summary
    }

    fun recordLap() {
        session.lap()
        val lastLap = session.laps.lastOrNull()
        if (lastLap != null) {
            hud.showLapNotification(lastLap.number, lastLap.avgPace)
        }
    }

    // Called by HardwareManager (via lazy Koin inject) when step detector fires
    fun onStepDetected() {
        if (!isRunning) return
        dynamicsAnalyzer.onStepDetected(System.currentTimeMillis())
    }

    // Called by HardwareManager (via lazy Koin inject) when accelerometer fires
    fun onAccelerometerData(x: Float, y: Float, z: Float) {
        if (!isRunning) return
        dynamicsAnalyzer.onAccelerometerData(x, y, z)
    }

    // Expose router metrics for RunningCoachPersona's Gemini context
    fun getFormRouterMetrics(): RouterMetrics = formRouter.metrics
    fun getInterventionRouterMetrics(): RouterMetrics = interventionRouter.metrics
    fun getFormRouter(): FormRouter = formRouter

    /**
     * Central router processing loop.
     * Subscribes to EventBus and feeds events through the router chain:
     *   1. FormRouter.evaluate(event) → form decision
     *   2. IntensityRouter.evaluate(event) → zone decision
     *   3. InterventionRouter.gate(formDecision) → intervene or suppress
     *   4. If INTERVENE: execute via HUD/TTS
     */
    private fun startRouterProcessingLoop() {
        routerProcessingJob = scope.launch {
            eventBus.events.collect { event ->
                try {
                    if (!isRunning) return@collect

                    when (event) {
                        is XRealEvent.PerceptionEvent.RunningDynamics -> {
                            val formDecision = formRouter.evaluate(event)
                            if (formDecision != null) routeFormDecision(formDecision)
                        }
                        is XRealEvent.PerceptionEvent.HeadStability -> {
                            hud.updateStability(event.stabilityScore)
                            val formDecision = formRouter.evaluate(event)
                            if (formDecision != null) routeFormDecision(formDecision)
                        }
                        is XRealEvent.PerceptionEvent.BreathingMetrics -> {
                            hud.updateBreathing(event.breathsPerMinute, event.isRegular)
                            val formDecision = formRouter.evaluate(event)
                            if (formDecision != null) routeFormDecision(formDecision)
                        }
                        is XRealEvent.PerceptionEvent.RunningRouteUpdate -> {
                            hud.updatePace(event.paceMinPerKm)
                            val zoneDecision = intensityRouter.evaluate(event)
                            if (zoneDecision != null) interventionRouter.updateZone(zoneDecision)
                        }
                        is XRealEvent.PerceptionEvent.WatchHeartRate -> {
                            lastWatchHr = event.bpm
                            hrSamples.add(event.bpm)
                            hud.updateHr(event.bpm)
                            val zoneDecision = intensityRouter.evaluate(event)
                            if (zoneDecision != null) interventionRouter.updateZone(zoneDecision)
                            val formDecision = formRouter.evaluate(event)
                            if (formDecision != null) routeFormDecision(formDecision)
                        }
                        is XRealEvent.PerceptionEvent.WatchHrv -> {
                            lastWatchHrv = event.rmssd
                            lastWatchHrvSdnn = event.sdnn
                            hrvSamples.add(event.rmssd)
                            val formDecision = formRouter.evaluate(event)
                            if (formDecision != null) routeFormDecision(formDecision)
                        }
                        is XRealEvent.PerceptionEvent.WatchSpO2 -> {
                            lastWatchSpO2 = event.spo2
                            if (event.spo2 > 0) spo2Samples.add(event.spo2)
                            hud.updateSpO2(event.spo2)
                            val formDecision = formRouter.evaluate(event)
                            if (formDecision != null) routeFormDecision(formDecision)
                        }
                        is XRealEvent.PerceptionEvent.WatchSkinTemperature -> {
                            lastWatchSkinTemp = event.temperature
                            lastWatchAmbientTemp = event.ambientTemperature
                            if (event.temperature > 0) skinTempSamples.add(event.temperature)
                            val formDecision = formRouter.evaluate(event)
                            if (formDecision != null) routeFormDecision(formDecision)
                        }
                        is XRealEvent.PerceptionEvent.FloorChange -> {
                            hud.showFloorChange(event.deltaFloors, event.direction)
                        }
                        is XRealEvent.PerceptionEvent.FusedPositionUpdate -> {
                            hud.updatePositionMode(event.mode)
                        }
                        else -> {}
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "라우터 루프 오류 (루프 유지됨): ${event::class.simpleName} — ${e.message}", e)
                }
            }
        }
    }

    private fun routeFormDecision(formDecision: RouterDecision) {
        val intervention = interventionRouter.gate(formDecision)
        if (intervention != null) executeIntervention(intervention)
    }

    private fun executeIntervention(decision: RouterDecision) {
        val originalAction = decision.metadata["original_action"] as? String ?: return
        val isEscalated = decision.metadata["is_escalated"] as? Boolean ?: false

        when (decision.action) {
            "INTERVENE_HUD" -> {
                val msg = CoachMessages.getHudMessage(originalAction)
                if (msg != null) {
                    hud.showCoachMessage(msg)
                    Log.d(TAG, "Router HUD: $msg")
                }
            }
            "INTERVENE_TTS" -> {
                val msg = CoachMessages.getTtsMessage(originalAction)
                if (msg != null) {
                    scope.launch {
                        eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(msg))
                    }
                    Log.d(TAG, "Router TTS: $msg")
                }
                if (isEscalated) {
                    coachPersona?.generateTargetedAdvice(originalAction, decision.metadata)
                }
            }
        }
    }

    private fun startHUDUpdateLoop() {
        hudUpdateJob = scope.launch {
            while (isActive && isRunning) {
                delay(1000L)

                // Update timer
                hud.updateTimer(session.getElapsedSeconds())

                // Sync distance from route tracker
                session.totalDistanceMeters = routeTracker.totalDistanceMeters
                hud.updateDistance(session.totalDistanceMeters)

                // Update cadence with router-sourced color
                val cadence = dynamicsAnalyzer.computeCadence()
                if (cadence > 0) {
                    hud.updateCadenceWithColor(cadence, formRouter.getCadenceColor(cadence))
                }
            }
        }
    }

    private fun checkWeatherConditions() {
        scope.launch(Dispatchers.IO) {
            try {
                val loc = locationManager.getCurrentLocation()
                val weather = weatherService.getWeather(loc?.let { "${it.latitude},${it.longitude}" } ?: "서울")
                if (weather.contains("비") || weather.contains("눈") || weather.contains("rain") || weather.contains("snow")) {
                    hud.showCoachMessage("Weather: Precipitation!")
                    eventBus.publish(XRealEvent.ActionRequest.SpeakTTS("비가 오고 있어요. 미끄러짐에 주의하세요."))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Weather check failed: ${e.message}")
            }
        }
    }

    private fun handleRunningCommand(command: RunningCommandType) {
        when (command) {
            RunningCommandType.START -> startRun()
            RunningCommandType.STOP -> stopRun()
            RunningCommandType.PAUSE -> pauseRun()
            RunningCommandType.RESUME -> resumeRun()
            RunningCommandType.LAP -> recordLap()
            RunningCommandType.STATUS -> {
                val cadence = dynamicsAnalyzer.computeCadence()
                val elapsed = session.getElapsedSeconds()
                val dist = session.totalDistanceMeters / 1000f
                val statusMsg = "${elapsed / 60}분 ${elapsed % 60}초, ${String.format("%.1f", dist)}km, 케이던스 ${cadence.toInt()}"
                scope.launch {
                    eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(statusMsg))
                }
            }
        }
    }

    private fun persistSessionData(summary: RunningSession.SessionSummary) {
        try {
            val now = System.currentTimeMillis()
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd_HH:mm", java.util.Locale.getDefault())
            val dateKey = sdf.format(java.util.Date(now))

            val avgCadence = dynamicsAnalyzer.computeCadence()
            val json = org.json.JSONObject().apply {
                put("start_time", now - summary.totalDurationMs)
                put("end_time", now)
                put("duration_ms", summary.totalDurationMs)
                put("distance_meters", summary.totalDistanceMeters)
                put("avg_pace_min_per_km", summary.avgPaceMinPerKm)
                put("laps", summary.laps.size)
                put("avg_cadence", avgCadence)
                put("elevation_gain_m", routeTracker.elevationGainMeters)
                put("elevation_loss_m", routeTracker.elevationLossMeters)
                put("floor_changes", routeTracker.currentFloorDelta)
                put("position_mode", routeTracker.currentPositionMode.name)
                // Watch biometric summary
                if (hrSamples.isNotEmpty()) {
                    put("avg_hr", hrSamples.average())
                    put("max_hr", hrSamples.max())
                    put("min_hr", hrSamples.min())
                    put("hr_samples_count", hrSamples.size)
                }
                if (hrvSamples.isNotEmpty()) {
                    put("avg_hrv_rmssd", hrvSamples.average())
                }
                if (spo2Samples.isNotEmpty()) {
                    put("avg_spo2", spo2Samples.average())
                    put("min_spo2", spo2Samples.min())
                }
                if (skinTempSamples.isNotEmpty()) {
                    put("avg_skin_temp", skinTempSamples.average())
                    put("max_skin_temp", skinTempSamples.max())
                }
            }

            database.upsertStructuredData(
                domain = "running_session",
                dataKey = dateKey,
                value = json.toString(),
                tags = "running,session"
            )
            Log.i(TAG, "Session data persisted: running_session/$dateKey")
        } catch (e: Exception) {
            ErrorReporter.report(TAG, "Failed to persist session data", e)
        }
    }

    private fun persistRouteData() {
        try {
            val points = routeTracker.routePoints
            if (points.isEmpty()) return

            val now = System.currentTimeMillis()
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd_HH:mm", java.util.Locale.getDefault())
            val dateKey = sdf.format(java.util.Date(now))

            // Store simplified route (every 5th point to save space)
            val routeArray = org.json.JSONArray()
            points.filterIndexed { index, _ -> index % 5 == 0 || index == points.lastIndex }.forEach { pt ->
                routeArray.put(org.json.JSONObject().apply {
                    put("lat", pt.lat)
                    put("lon", pt.lon)
                    put("alt", pt.altitude)
                    put("spd", pt.speed)
                    put("ts", pt.timestampMs)
                })
            }

            val json = org.json.JSONObject().apply {
                put("total_points", points.size)
                put("stored_points", routeArray.length())
                put("route", routeArray)
            }

            database.upsertStructuredData(
                domain = "running_route",
                dataKey = dateKey,
                value = json.toString(),
                tags = "running,route,gps"
            )
            Log.i(TAG, "Route data persisted: running_route/$dateKey (${routeArray.length()} points)")
        } catch (e: Exception) {
            ErrorReporter.report(TAG, "Failed to persist route data", e)
        }
    }

    /**
     * Digital Twin 예측 데이터를 라우터에 반영.
     * - IntensityRouter: max_hr → LTHR 기반으로 교체
     * - PacemakerService: 최적 페이스 설정
     * - 회복/부상 상태 로깅
     */
    private fun applyPredictionBasedConfig() {
        val sync = predictionSyncService ?: return
        try {
            // LTHR 기반 HR 존 재설정 (기본 190 → 실측 LTHR)
            val lthr = sync.getLthr()
            if (lthr in 120..200) {
                // IntensityRouter는 max_hr% 기반 → LTHR ≈ Zone4 상한이므로 max_hr ≈ lthr / 0.95
                val estimatedMaxHr = (lthr / 0.95f).toInt()
                intensityRouter.config.thresholds["max_hr"] = estimatedMaxHr.toFloat()
                Log.i(TAG, "IntensityRouter max_hr updated: $estimatedMaxHr (LTHR=$lthr)")
            }

            // 회복 상태 + 부상 위험 로깅
            val recovery = sync.getRecoveryScore()
            val recommendation = sync.getRecommendation()
            val injuryRisk = sync.getInjuryRiskLevel()

            if (recovery != null) {
                val pct = (recovery * 100).toInt()
                Log.i(TAG, "Session start — 회복: ${pct}%($recommendation), 부상위험: $injuryRisk")

                // 부상 위험이 high면 시작 시 경고
                if (injuryRisk == "high") {
                    hud.showCoachMessage("⚠ 부상 위험 높음")
                    scope.launch {
                        eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(
                            "부상 위험이 높습니다. 가벼운 운동을 권장합니다."
                        ))
                    }
                }
                // 회복 부족 시 경고
                if (recommendation == "rest") {
                    hud.showCoachMessage("회복 필요 — 휴식 권장")
                    scope.launch {
                        eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(
                            "회복 점수가 낮습니다. 오늘은 휴식을 권장합니다."
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Prediction config failed (non-fatal): ${e.message}")
        }
    }

    /** Digital Twin 예측 요약 — RunningCoachPersona AI 컨텍스트 주입용 */
    fun getPredictionContext(): String? = predictionSyncService?.getProfileSummary()

    /** Track anchor service — 속도 프로필 접근 (Phase 2 그래프용) */
    fun getTrackAnchors(): RunningTrackAnchorService? = trackAnchorService

    fun release() {
        hudUpdateJob?.cancel()
        routerProcessingJob?.cancel()
        positionFusionEngine?.release()
        barometricFloorDetector?.stop()
        trackAnchorService?.release()
        pacemakerService?.release()
        speedGraphOverlay.release()
        coachPersona?.release()
        dynamicsAnalyzer.release()
        routeTracker.release()
        breathingAnalyzer.release()
        session.release()
        hud.release()
        scope.cancel()
    }
}
