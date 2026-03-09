package com.xreal.nativear.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.xreal.nativear.DrawCommand
import com.xreal.nativear.DrawElement
import kotlinx.coroutines.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * ChaosMonkey — 카오스 엔지니어링 / 이벤트 폭풍 테스트 시스템 🤠
 *
 * ## 개념
 * "야생마를 타는 카우보이" — 다양한 이벤트 조합을 발사하여
 * 시스템의 각 구독자(Subscriber)가 어떻게 반응하는지 검증한다.
 *
 * ## 5가지 시나리오
 * - VOICE_STORM   : VoiceCommand 폭풍 — AI 대화 파이프라인 검증
 * - VISION_FLOOD  : TriggerSnapshot 홍수 — Vision 파이프라인 검증
 * - HEALTH_SHOCK  : 심박수 극단값 시퀀스 — 헬스 모니터링 검증
 * - ERROR_RAIN    : 다양한 SystemEvent.Error — EmergencyOrchestrator 반응 검증
 * - FULL_RODEO    : 모든 시나리오 동시 발사 🤠🐂 — 전체 시스템 내구성 검증
 *
 * ## ADB 트리거 (앱 실행 중 언제든지 발사)
 * ```bash
 * adb shell am broadcast -a com.xreal.nativear.CHAOS --es scenario VOICE_STORM
 * adb shell am broadcast -a com.xreal.nativear.CHAOS --es scenario VISION_FLOOD
 * adb shell am broadcast -a com.xreal.nativear.CHAOS --es scenario HEALTH_SHOCK
 * adb shell am broadcast -a com.xreal.nativear.CHAOS --es scenario ERROR_RAIN
 * adb shell am broadcast -a com.xreal.nativear.CHAOS --es scenario FULL_RODEO
 * adb shell am broadcast -a com.xreal.nativear.CHAOS --es scenario STOP
 *
 * # 실시간 관찰
 * adb logcat -s ChaosMonkey FlowMon SEQ
 * ```
 */
class ChaosMonkey(
    private val eventBus: GlobalEventBus,
    private val lifeTornado: LifeTornadoEngine
) {

    companion object {
        const val TAG = "ChaosMonkey"
        const val CHAOS_ACTION = "com.xreal.nativear.CHAOS"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var activeJob: Job? = null
    private var monitorJob: Job? = null

    @Volatile private var firedCount = 0
    @Volatile private var responseCount = 0  // TTS / DrawingCommand 수신 카운트
    @Volatile private var errorCount = 0     // 관측된 에러 이벤트 수

    // ── 흐름 추적기 ────────────────────────────────────────────────────────────
    /** 시나리오 실행 중 시간순 이벤트 흐름 (구독자 반응 포함) */
    private val flowTimeline = mutableListOf<String>()
    /** 자연 발생 에러 — ChaosMonkey가 의도적으로 주입하지 않은 에러 (디버깅 대상) */
    private val naturalErrors = mutableListOf<String>()
    /** ChaosMonkey가 fire()를 통해 직접 주입한 에러 코드 세트 */
    private val injectedErrorCodes = mutableSetOf<String>()

    enum class Scenario {
        // ── 원래 카오스 시나리오 ──
        VOICE_STORM,      // VoiceCommand × 10 연속
        VISION_FLOOD,     // TriggerSnapshot × 5 + HUD 드로잉
        HEALTH_SHOCK,     // 심박수 40→180→45 bpm 극단값 시퀀스
        ERROR_RAIN,       // SystemEvent.Error 5종 연속
        FULL_RODEO,       // 모든 시나리오 동시에 🤠🐂

        // ── 실생활 시나리오 (현실적 타이밍) ──
        // 속도는 --es speed FAST/NORMAL/SLOW/REALTIME으로 제어
        LIFE_TORNADO,     // 10년 인생 시뮬레이션 🌪️ (LifeTornadoEngine)
        MORNING_RUN,      // 아침 러닝 30분 시뮬레이션 (5km)
        CLASS_SESSION,    // 교실 수업 45분 시뮬레이션
        MARATHON_RACE,    // 마라톤 4시간30분 시뮬레이션
        TRAVEL_JAPAN,     // 일본 3일 여행 시뮬레이션
        WEEKLY_LIFE,      // 1주일 생활 시뮬레이션 (매일 루틴)
        STOP              // 중단
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────────

    // 현재 speed (LIFE_TORNADO 등에서 사용)
    private var speedFromIntent = "NORMAL"

    fun runScenario(scenario: Scenario, speed: String = "NORMAL") {
        speedFromIntent = speed
        if (scenario == Scenario.STOP) { stop(); return }

        activeJob?.cancel()
        monitorJob?.cancel()
        firedCount = 0; responseCount = 0; errorCount = 0
        flowTimeline.clear(); naturalErrors.clear(); injectedErrorCodes.clear()

        Log.w(TAG, "\n══════════════════════════════════════\n  🤠 CHAOS START: $scenario  \n══════════════════════════════════════")
        ExecutionFlowMonitor.record("ChaosMonkey", "🤠 START: $scenario", ExecutionFlowMonitor.Level.DANGER)
        SequenceTracer.log("ChaosMonkey", "🤠 시나리오 시작: $scenario")

        // ── 전 구독자 반응 추적 모니터 ─────────────────────────────────────────
        // 목적: 각 서비스가 이벤트를 받아 어떤 경로로 일하고 어떤 결과를 냈는지 추적.
        //        TTS만 세는 것이 아니라 전체 흐름 타임라인을 구성.
        //        ChaosMonkey가 주입한 에러와 구독자가 자연 발생시킨 에러를 구분.
        monitorJob = scope.launch {
            try {
                eventBus.events.collect { event ->
                    try {
                        when (event) {
                            // ── 구독자 출력: TTS (AIAgentManager / RunningCoachManager / EmergencyOrchestrator)
                            is XRealEvent.ActionRequest.SpeakTTS -> {
                                responseCount++
                                val line = "📢 TTS[$responseCount]: \"${event.text.take(55)}\""
                                flowTimeline.add(line)
                                Log.i(TAG, line)
                                ExecutionFlowMonitor.record("ChaosMonkey", line, ExecutionFlowMonitor.Level.INFO)
                            }

                            // ── 구독자 출력: HUD 드로잉 (AIAgentManager / RunningCoachManager)
                            is XRealEvent.ActionRequest.DrawingCommand -> {
                                responseCount++
                                val (cmdType, elemId) = when (val cmd = event.command) {
                                    is DrawCommand.Add    -> "Add"    to cmd.element.id
                                    is DrawCommand.Remove -> "Remove" to cmd.id
                                    is DrawCommand.Modify -> "Modify" to cmd.id
                                    else                  -> (cmd::class.simpleName ?: "?") to "*"
                                }
                                val line = "🎨 Draw[$cmdType:$elemId]"
                                flowTimeline.add(line)
                            }

                            // ── 에러 분류: 주입된 에러 vs 자연 발생 에러
                            is XRealEvent.SystemEvent.Error -> {
                                errorCount++
                                val isNatural = event.code !in injectedErrorCodes
                                val prefix = if (isNatural) "⚡ 자연에러" else "💉 주입에러"
                                val line = "$prefix [${event.severity}] ${event.code}: ${event.message.take(55)}"
                                flowTimeline.add(line)
                                if (isNatural) {
                                    naturalErrors.add(line)
                                    // 자연 발생 에러 = 구독자가 처리 중에 터진 버그 후보
                                    Log.e(TAG, "🔍 자연에러(조사필요): $line")
                                } else {
                                    Log.d(TAG, line)
                                }
                                ExecutionFlowMonitor.record("ChaosMonkey", line,
                                    if (isNatural) ExecutionFlowMonitor.Level.DANGER else ExecutionFlowMonitor.Level.INFO)
                            }

                            // ── 구독자 내부 동작: DebugLog (RunningCoachManager, FormRouter 등이 발행)
                            is XRealEvent.SystemEvent.DebugLog -> {
                                val msg = event.message
                                // 라우터 / 코치 / 미션 관련 로그만 타임라인에 기록
                                if (msg.contains("Router") || msg.contains("Coach") ||
                                    msg.contains("Intervention") || msg.contains("Mission") ||
                                    msg.contains("Persona") || msg.contains("FormRouter") ||
                                    msg.contains("Intensity")) {
                                    val line = "📝 SubLog: ${msg.take(65)}"
                                    flowTimeline.add(line)
                                    Log.d(TAG, line)
                                }
                            }

                            // ── 구독자 발생 상태 변화 (UserStateTracker, RunningCoachManager 등)
                            is XRealEvent.SystemEvent.UserStateChanged -> {
                                val line = "🔄 UserState: ${event.oldState} → ${event.newState}"
                                flowTimeline.add(line)
                                Log.d(TAG, line)
                            }

                            // ── 러닝 세션 상태 변화 (RunningCoachManager 출력)
                            is XRealEvent.SystemEvent.RunningSessionState -> {
                                val line = "🏃 RunSession: ${event.state} | ${event.totalDistanceMeters.toInt()}m | ${event.elapsedSeconds}s"
                                flowTimeline.add(line)
                                Log.d(TAG, line)
                            }

                            // ── 미션 상태 변화 (MissionConductor 출력)
                            is XRealEvent.SystemEvent.MissionStateChanged -> {
                                val line = "🎯 Mission[${event.missionType}]: ${event.oldState} → ${event.newState}"
                                flowTimeline.add(line)
                                Log.i(TAG, line)
                            }

                            // ── 투두 완료 (PlanManager 출력 — 목표 달성 추적)
                            is XRealEvent.SystemEvent.TodoCompleted -> {
                                val line = "✅ Todo완료: \"${event.todoTitle.take(40)}\""
                                flowTimeline.add(line)
                                Log.i(TAG, line)
                            }

                            else -> Unit
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "모니터 오류: ${e.message}")
                    }
                }
            } catch (e: CancellationException) { /* 정상 종료 */ }
        }

        // 시나리오 실행 코루틴
        activeJob = scope.launch {
            try {
                when (scenario) {
                    Scenario.VOICE_STORM  -> voiceStorm()
                    Scenario.VISION_FLOOD -> visionFlood()
                    Scenario.HEALTH_SHOCK -> healthShock()
                    Scenario.ERROR_RAIN   -> errorRain()
                    Scenario.FULL_RODEO   -> fullRodeo()
                    Scenario.LIFE_TORNADO -> lifeTornadoScenario(speedFromIntent)
                    Scenario.MORNING_RUN  -> morningRunScenario(speedFromIntent)
                    Scenario.CLASS_SESSION -> classSessionScenario(speedFromIntent)
                    Scenario.MARATHON_RACE -> marathonRaceScenario(speedFromIntent)
                    Scenario.TRAVEL_JAPAN -> travelJapanScenario(speedFromIntent)
                    Scenario.WEEKLY_LIFE  -> weeklyLifeScenario(speedFromIntent)
                    Scenario.STOP         -> Unit
                }
            } catch (e: CancellationException) {
                Log.w(TAG, "🛑 시나리오 취소됨: $scenario")
            } catch (e: Exception) {
                Log.e(TAG, "시나리오 오류: ${e.message}", e)
            } finally {
                report(scenario)
                monitorJob?.cancel()
            }
        }
    }

    fun stop() {
        activeJob?.cancel()
        monitorJob?.cancel()
        Log.w(TAG, "🤠 CHAOS MONKEY STOPPED — 발사: $firedCount, 응답: $responseCount, 에러: $errorCount")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  시나리오 구현
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 🌪️ VOICE_STORM: VoiceCommand 폭풍
     * 일반 명령 + "범블비" 웨이크워드 혼합 → AI 파이프라인 전체 검증
     */
    private suspend fun voiceStorm() {
        val commands = listOf(
            "날씨 알려줘",
            "오늘 일정이 뭐야?",
            "범블비",                          // 웨이크워드 → VoiceManager 대화 모드 진입
            "지금 몇 시야?",
            "배터리 얼마나 남았어?",
            "메모해줘: 카오스 테스트 실행 중",
            "범블비 안녕",                     // 두 번째 웨이크워드 → 재진입 검증
            "뭐가 보여?",
            "기분 어때?"
        )
        Log.i(TAG, "🌪️ VoiceStorm — ${commands.size}개 명령 × 800ms")
        for ((i, cmd) in commands.withIndex()) {
            fire(XRealEvent.InputEvent.VoiceCommand(text = cmd), "VoiceCmd[$i] \"$cmd\"")
            delay(800L)
        }
        // EnrichedVoiceCommand — 감정 태그 포함 (PersonaManager 반응 검증)
        fire(
            XRealEvent.InputEvent.EnrichedVoiceCommand(
                text = "나 지금 좀 힘들어, 도와줘",
                speaker = "user",
                emotion = "sad",
                emotionScore = 0.88f
            ),
            "EnrichedVoiceCmd: sad 0.88"
        )
        delay(500L)
    }

    /**
     * 👁️ VISION_FLOOD: TriggerSnapshot 홍수
     * TriggerSnapshot × 5 + LocationUpdated + HUD DrawingCommand 발사
     */
    private suspend fun visionFlood() {
        Log.i(TAG, "👁️ VisionFlood — TriggerSnapshot × 5 + DrawCmd")
        repeat(5) { i ->
            fire(XRealEvent.ActionRequest.TriggerSnapshot, "TriggerSnapshot[${i+1}/5]")
            delay(1200L)

            if (i == 1) {
                fire(
                    XRealEvent.PerceptionEvent.LocationUpdated(
                        lat = 37.5665,
                        lon = 126.9780,
                        address = "서울 중구 (카오스 테스트)"
                    ),
                    "LocationUpdated: 서울"
                )
                delay(200L)
            }
        }

        // HUD 드로잉 — OverlayView 렌더링 검증
        fire(
            XRealEvent.ActionRequest.DrawingCommand(
                DrawCommand.Add(
                    DrawElement.Text(
                        id = "chaos_hud_label",
                        x = 50f, y = 8f,
                        text = "🤠 CHAOS IN PROGRESS",
                        size = 22f,
                        color = "#FFFF00",   // 노란색
                        opacity = 1.0f
                    )
                )
            ),
            "DrawCmd: Text '🤠 CHAOS IN PROGRESS'"
        )
        delay(300L)

        fire(
            XRealEvent.ActionRequest.DrawingCommand(
                DrawCommand.Add(
                    DrawElement.Rect(
                        id = "chaos_hud_border",
                        x = 5f, y = 5f,
                        width = 90f, height = 90f,
                        color = "#FF4400",   // 주황-빨간
                        opacity = 0.4f,
                        filled = false,
                        strokeWidth = 4f
                    )
                )
            ),
            "DrawCmd: Rect border"
        )
        delay(2000L)

        // 정리
        fire(XRealEvent.ActionRequest.DrawingCommand(DrawCommand.Remove("chaos_hud_label")), "DrawCmd: Remove label")
        fire(XRealEvent.ActionRequest.DrawingCommand(DrawCommand.Remove("chaos_hud_border")), "DrawCmd: Remove border")
    }

    /**
     * 💓 HEALTH_SHOCK: 심박수 극단값 시퀀스
     * 정상 → 고강도 → 위험 → 극서맥 → 정상 복귀
     * RunningCoachManager, UserStateTracker, EmergencyOrchestrator 반응 검증
     */
    private suspend fun healthShock() {
        data class HeartSample(val bpm: Float, val label: String, val hrv: Float?)

        val sequence = listOf(
            HeartSample(72f,  "정상",              hrv = null),
            HeartSample(95f,  "가벼운 운동",        hrv = null),
            HeartSample(130f, "중강도",             hrv = 55f),
            HeartSample(158f, "고강도",             hrv = 28f),
            HeartSample(182f, "⚠️ 위험 고강도!",    hrv = 12f),   // 위험 구간
            HeartSample(165f, "고강도 지속",         hrv = 18f),
            HeartSample(115f, "회복",               hrv = 42f),
            HeartSample(65f,  "안정",               hrv = 68f),
            HeartSample(42f,  "⚠️ 극서맥!",         hrv = 85f),   // 극서맥
            HeartSample(74f,  "정상 복귀",           hrv = 62f)
        )

        Log.i(TAG, "💓 HealthShock — ${sequence.size}단계 시퀀스")
        for ((i, s) in sequence.withIndex()) {
            val now = System.currentTimeMillis()
            fire(
                XRealEvent.PerceptionEvent.WatchHeartRate(bpm = s.bpm, timestamp = now),
                "HeartRate[${i+1}] ${s.bpm.toInt()}bpm (${s.label})"
            )

            s.hrv?.let { hrv ->
                delay(80L)
                fire(
                    XRealEvent.PerceptionEvent.WatchHrv(
                        rmssd = hrv,
                        sdnn = hrv * 1.4f,
                        meanRR = 60000f / s.bpm,
                        timestamp = System.currentTimeMillis()
                    ),
                    "HRV rmssd=${hrv.toInt()}ms"
                )
            }

            // 위험 구간: UserStateChanged 발사
            if (s.bpm >= 175f || s.bpm <= 45f) {
                delay(50L)
                fire(
                    XRealEvent.SystemEvent.UserStateChanged(
                        oldState = "ACTIVE",
                        newState = if (s.bpm >= 175f) "HIGH_INTENSITY" else "REST"
                    ),
                    "UserState → ${if (s.bpm >= 175f) "HIGH_INTENSITY" else "REST"}"
                )
            }
            delay(1500L)
        }

        // SpO2 저하 시뮬레이션
        fire(
            XRealEvent.PerceptionEvent.WatchSpO2(spo2 = 91, timestamp = System.currentTimeMillis()),
            "SpO2 91% — 저산소 경고"
        )
        delay(2000L)
        fire(
            XRealEvent.PerceptionEvent.WatchSpO2(spo2 = 98, timestamp = System.currentTimeMillis()),
            "SpO2 98% — 정상 복귀"
        )
    }

    /**
     * ⛈️ ERROR_RAIN: SystemEvent.Error 5종 연속
     * EmergencyOrchestrator 우회 규칙 검증
     * 에러 홍수 후 VoiceCommand로 시스템 상태 쿼리
     */
    private suspend fun errorRain() {
        data class ErrCase(val code: String, val msg: String, val sev: ErrorSeverity)

        val errors = listOf(
            ErrCase("VISION_ERROR",    "카오스: Vision pipeline timeout 100ms",    ErrorSeverity.WARNING),
            ErrCase("SERVER_AI_ERROR", "카오스: Gemini 503 Service Unavailable",   ErrorSeverity.WARNING),
            ErrCase("EDGE_LLM_ERROR",  "카오스: ROUTER_270M ChildCancelledException", ErrorSeverity.WARNING),
            ErrCase("HARDWARE_ERROR",  "카오스: IMU data gap 500ms 감지",            ErrorSeverity.CRITICAL),
            ErrCase("MEMORY_ERROR",    "카오스: UnifiedMemoryDB write 실패",         ErrorSeverity.CRITICAL)
        )

        Log.i(TAG, "⛈️ ErrorRain — ${errors.size}종 × 1s 간격")
        for ((i, err) in errors.withIndex()) {
            fire(
                XRealEvent.SystemEvent.Error(
                    code = err.code,
                    message = err.msg,
                    throwable = RuntimeException("ChaosMonkey: ${err.code}"),
                    severity = err.sev
                ),
                "Error[${i+1}] ${err.code} [${err.sev}]"
            )
            delay(1000L)
        }

        // 에러 홍수 후 — 시스템 복구 확인 쿼리
        delay(500L)
        fire(
            XRealEvent.InputEvent.VoiceCommand(text = "시스템 상태 어때?"),
            "에러 홍수 후 시스템 상태 쿼리"
        )
    }

    /**
     * 🤠🐂 FULL_RODEO: 4개 시나리오 동시 발사
     * 300ms 엇박자로 시작 → GlobalEventBus 버퍼 포화 테스트
     */
    private suspend fun fullRodeo() {
        Log.w(TAG, "🤠🐂 FULL RODEO — 4 시나리오 동시 발사! 안전벨트!")
        ExecutionFlowMonitor.record("ChaosMonkey", "🐂 FULL RODEO 시작", ExecutionFlowMonitor.Level.DANGER)
        coroutineScope {
            launch                { voiceStorm()  }
            launch { delay(300L); visionFlood() }
            launch { delay(600L); healthShock() }
            launch { delay(900L); errorRain()   }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  내부 유틸
    // ─────────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────────
    //  실생활 시나리오 (LifeTornadoEngine 위임 또는 독립 실행)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 🌪️ LIFE_TORNADO — LifeTornadoEngine에 10년 시뮬레이션 위임
     * ```bash
     * # NORMAL: 1일=200ms, 10년≈12분
     * adb shell am broadcast -a com.xreal.nativear.CHAOS -p com.xreal.nativear --es scenario LIFE_TORNADO --es speed NORMAL
     * # SLOW: 1일=1000ms, 10년≈1시간
     * adb shell am broadcast -a com.xreal.nativear.CHAOS -p com.xreal.nativear --es scenario LIFE_TORNADO --es speed SLOW
     * ```
     */
    private suspend fun lifeTornadoScenario(speed: String) {
        Log.w(TAG, "🌪️ LIFE_TORNADO 시작 → LifeTornadoEngine (speed=$speed)")
        lifeTornado.startTornado(speed)
        // LifeTornadoEngine은 자체 코루틴으로 실행 → 여기서 대기하지 않음
        // ChaosMonkey 모니터 코루틴은 계속 응답 수신
        delay(10_000L)  // 10초 후 ChaosMonkey 리포트 (Tornado는 독립 실행 중)
    }

    /**
     * 🏃 MORNING_RUN — 아침 러닝 5km 30분 시뮬레이션
     * GPS + 심박수 + 동역학을 실제 러닝 속도에 맞게 발사
     */
    private suspend fun morningRunScenario(speed: String) {
        val intervalMs = when (speed.uppercase()) {
            "FAST" -> 200L; "SLOW" -> 5000L; "REALTIME" -> 10000L; else -> 1000L
        }
        val totalKm = 5
        val paceSecPerKm = 360L  // 6분/km
        Log.i(TAG, "🏃 MORNING_RUN — 5km 러닝 시뮬레이션 (interval=${intervalMs}ms/km)")

        // RunningCoachManager는 RunningVoiceCommand(START)를 받아야 isSessionActive=true가 됨
        // RunningSessionState(ACTIVE)는 RunningCoachManager가 내보내는 출력 이벤트이지 입력이 아님
        fire(XRealEvent.InputEvent.RunningVoiceCommand(RunningCommandType.START), "러닝 코치 시작 → RunningCoachManager 활성화")
        fire(XRealEvent.SystemEvent.UserStateChanged("SLEEP", "RUNNING"), "User: RUNNING")
        fire(XRealEvent.InputEvent.VoiceCommand("오늘 아침 러닝 시작!"), "러닝 시작 메모")

        var lat = 37.5326; var lon = 126.990
        for (km in 1..totalKm) {
            delay(intervalMs)
            lat += 0.005 * kotlin.math.sin(km.toDouble())
            lon += 0.005 * kotlin.math.cos(km.toDouble())
            val elapsed = km * paceSecPerKm
            val hr = 130f + km * 5f

            fire(XRealEvent.PerceptionEvent.RunningRouteUpdate(
                distanceMeters = km * 1000f,
                paceMinPerKm = 6.0f,
                currentSpeedMps = 2.78f,
                elevationGainMeters = km * 3f,
                elapsedSeconds = elapsed,
                latitude = lat, longitude = lon,
                timestamp = System.currentTimeMillis()
            ), "${km}km 통과")
            fire(XRealEvent.PerceptionEvent.WatchHeartRate(bpm = hr, timestamp = System.currentTimeMillis()), "HR ${hr.toInt()}bpm")
            fire(XRealEvent.PerceptionEvent.RunningDynamics(
                cadence = 172f, verticalOscillation = 9f,
                groundContactTime = 240f, groundReactionForce = 2.4f,
                timestamp = System.currentTimeMillis()
            ), "RunningDynamics km$km")

            if (km == 3) fire(XRealEvent.InputEvent.VoiceCommand("3km 중간 지점! 컨디션 어때?"), "3km 체크")
        }

        fire(XRealEvent.InputEvent.RunningVoiceCommand(RunningCommandType.STOP), "러닝 코치 종료 → RunningCoachManager 세션 종료")
        fire(XRealEvent.PerceptionEvent.WatchHrv(rmssd = 45f, sdnn = 55f, meanRR = 800f, timestamp = System.currentTimeMillis()), "완주 후 HRV")
        fire(XRealEvent.InputEvent.VoiceCommand("5km 완주! 오늘 페이스 6분/km"), "완주 메모")
        fire(XRealEvent.SystemEvent.UserStateChanged("RUNNING", "RECOVERY"), "회복 모드")
    }

    /**
     * 🏫 CLASS_SESSION — 교실 수업 45분 시뮬레이션
     * 학생 질문, 교사 응답, 판서 OCR, 특수 학생 케어
     */
    private suspend fun classSessionScenario(speed: String) {
        val intervalMs = when (speed.uppercase()) {
            "FAST" -> 300L; "SLOW" -> 10000L; "REALTIME" -> 60000L; else -> 3000L
        }
        Log.i(TAG, "🏫 CLASS_SESSION — 45분 수업 시뮬레이션 (interval=${intervalMs}ms/이벤트)")

        fire(XRealEvent.SystemEvent.UserStateChanged("COMMUTE", "TEACHING"), "수업 시작")
        fire(XRealEvent.PerceptionEvent.LocationUpdated(37.590, 126.993, "학교 교실"), "학교 위치")
        fire(XRealEvent.InputEvent.VoiceCommand("자 여러분 오늘 수업 시작합니다"), "수업 시작 선언")

        val students = listOf("지수", "민준", "서연", "태양", "하은", "준서", "특수반 나연")
        val questions = listOf(
            "선생님 이 문제 모르겠어요", "오늘 숙제 뭐예요?", "시험 범위 어디까지예요?",
            "이거 왜 이렇게 되는 거예요?", "발표 언제 해요?", "저 화장실 다녀와도 돼요?",
            "선생님 이 부분 다시 설명해주세요", "짝꿍이 괴롭혀요",
            "선생님 저 모르겠어요 도와주세요"  // 특수교육
        )
        val teacherResponses = listOf(
            "좋은 질문이야! 같이 생각해보자", "다들 여기 봐요 이게 핵심이야",
            "틀려도 괜찮아 용기가 중요해", "잘 했어! 계속해봐",
            "천천히 같이 해보자, 괜찮아", "오늘 정말 열심히 했어요"
        )

        var timeMinutes = 0
        repeat(15) { i ->  // 45분 수업 = 3분 간격 이벤트 × 15
            delay(intervalMs)
            timeMinutes += 3

            // 학생 질문 (랜덤 학생)
            val student = students.random()
            val question = questions.random()
            fire(XRealEvent.InputEvent.EnrichedVoiceCommand(
                text = question, speaker = student,
                emotion = listOf("curious","confused","anxious","motivated").random(),
                emotionScore = 0.6f + (0..40).random() / 100f
            ), "학생[$student]: $question")

            delay(intervalMs / 3)

            // 교사 응답
            fire(XRealEvent.InputEvent.VoiceCommand(teacherResponses.random()), "교사 응답")

            // 5분마다 판서/교재 스캔
            if (timeMinutes % 10 == 0) {
                fire(XRealEvent.ActionRequest.TriggerSnapshot, "판서 스캔 ${timeMinutes}분")
            }

            // 특수 학생 케어 (20분, 35분)
            if (timeMinutes == 20 || timeMinutes == 35) {
                fire(XRealEvent.InputEvent.EnrichedVoiceCommand(
                    text = "선생님 저 이거 너무 어려워요",
                    speaker = "특수반 나연", emotion = "confused", emotionScore = 0.85f
                ), "특수반 나연: 도움 요청")
                delay(intervalMs / 3)
                fire(XRealEvent.InputEvent.VoiceCommand("괜찮아 나연아, 천천히 같이 해보자"), "교사: 특수 케어")
            }
        }

        fire(XRealEvent.InputEvent.VoiceCommand("오늘 수업 여기까지 수고했어요"), "수업 종료")
        fire(XRealEvent.SystemEvent.UserStateChanged("TEACHING", "BREAK"), "휴식")
    }

    /**
     * 🏆 MARATHON_RACE — 풀마라톤 4시간30분 시뮬레이션
     * 5km마다 페이스, 심박, 동역학 이벤트 + 벽 (30km) 체험
     */
    private suspend fun marathonRaceScenario(speed: String) {
        val intervalMs = when (speed.uppercase()) {
            "FAST" -> 500L; "SLOW" -> 10000L; "REALTIME" -> 120000L; else -> 2000L
        }
        val totalKm = 42
        Log.i(TAG, "🏆 MARATHON_RACE — 42.195km 풀마라톤 시뮬레이션 (interval=${intervalMs}ms/5km)")

        fire(XRealEvent.InputEvent.RunningVoiceCommand(RunningCommandType.START), "마라톤 코치 시작 → RunningCoachManager 활성화")
        fire(XRealEvent.InputEvent.VoiceCommand("드디어 마라톤 스타트! 오늘 Sub-4:30 목표!"), "레이스 시작")

        val checkpoints = listOf(
            5 to Triple(155f, 6.2f, "초반 안정"),
            10 to Triple(158f, 6.0f, "10km 통과, 순항 중"),
            15 to Triple(162f, 6.1f, "하프 접근"),
            21 to Triple(165f, 6.2f, "하프 완주! 2:10, 좋아!"),
            25 to Triple(168f, 6.3f, "25km, 페이스 안정적"),
            30 to Triple(178f, 6.8f, "30km 마라톤의 벽!! 너무 힘들다"),  // 벽
            35 to Triple(175f, 7.0f, "35km 힘들지만 포기 못해"),
            38 to Triple(172f, 6.9f, "38km 이제 거의 다 왔어"),
            40 to Triple(168f, 6.5f, "40km! 2km만 더!"),
            42 to Triple(180f, 6.1f, "피니시! 완주!!!! 🏆")
        )

        var lat = 37.5665; var lon = 126.978
        for ((km, data) in checkpoints) {
            delay(intervalMs)
            lat += 0.01 * kotlin.math.sin(km.toDouble())
            lon += 0.01 * kotlin.math.cos(km.toDouble())
            val (hr, pace, message) = data
            val elapsed = (km * pace * 60).toLong()

            fire(XRealEvent.PerceptionEvent.RunningRouteUpdate(
                distanceMeters = km * 1000f, paceMinPerKm = pace,
                currentSpeedMps = 1000f / (pace * 60f),
                elevationGainMeters = km.toFloat(),
                elapsedSeconds = elapsed,
                latitude = lat, longitude = lon, timestamp = System.currentTimeMillis()
            ), "${km}km: $message")

            fire(XRealEvent.PerceptionEvent.WatchHeartRate(bpm = hr, timestamp = System.currentTimeMillis()), "HR ${hr.toInt()}bpm")
            fire(XRealEvent.InputEvent.VoiceCommand(message), "${km}km 상태 메모")

            // 30km 벽 — 건강 위기 이벤트
            if (km == 30) {
                fire(XRealEvent.PerceptionEvent.WatchHrv(rmssd = 18f, sdnn = 22f, meanRR = 60000f/178f, timestamp = System.currentTimeMillis()), "벽: HRV 급락")
                fire(XRealEvent.SystemEvent.Error("RUNNING_WALL", "마라톤 30km 벽 진입 — 페이스 저하", severity = ErrorSeverity.WARNING), "마라톤 벽!")
            }
        }

        fire(XRealEvent.InputEvent.RunningVoiceCommand(RunningCommandType.STOP), "마라톤 코치 종료")
        fire(XRealEvent.SystemEvent.TodoCompleted("marathon_goal", "풀마라톤 완주!", null, "RUNNING"), "마라톤 투두 완료")
        fire(XRealEvent.InputEvent.VoiceCommand("풀마라톤 완주!!!!! 4:29:00! Sub-4:30 달성!!!"), "완주 기록")
    }

    /**
     * ✈️ TRAVEL_JAPAN — 일본 3일 여행 시뮬레이션
     * 출발→공항→도쿄→교토→귀국
     */
    private suspend fun travelJapanScenario(speed: String) {
        val intervalMs = when (speed.uppercase()) {
            "FAST" -> 500L; "SLOW" -> 10000L; "REALTIME" -> 120000L; else -> 3000L
        }
        Log.i(TAG, "✈️ TRAVEL_JAPAN — 3일 일본 여행 시뮬레이션 (interval=${intervalMs}ms)")

        // Day 1: 출발 ~ 도쿄 도착
        fire(XRealEvent.PerceptionEvent.LocationUpdated(37.4602, 126.4407, "인천공항"), "인천공항 도착")
        fire(XRealEvent.InputEvent.VoiceCommand("일본 여행 드디어 출발! 설레서 심장 터질 것 같아"), "출발 멘트")
        delay(intervalMs)

        fire(XRealEvent.SystemEvent.NetworkStatus(isConnected = false), "비행 중 오프라인")
        fire(XRealEvent.PerceptionEvent.AudioEnvironment(listOf("aircraft_noise" to 0.95f, "speech" to 0.2f),
            ByteArray(1024) { (kotlin.random.Random.nextInt(256) - 128).toByte() },
            System.currentTimeMillis(), null, null), "비행기 소음")
        delay(intervalMs)

        fire(XRealEvent.SystemEvent.NetworkStatus(isConnected = true), "도쿄 착륙")
        fire(XRealEvent.PerceptionEvent.LocationUpdated(35.6762, 139.6503, "도쿄 시내"), "도쿄 도착!")
        fire(XRealEvent.InputEvent.VoiceCommand("도쿄 도착! 생각보다 더 크다!"), "도쿄 첫 인상")
        delay(intervalMs)

        val tokyoPlaces = listOf("아키하바라 전자상가", "시부야 스크램블", "아사쿠사 센소지", "신주쿠")
        for (place in tokyoPlaces) {
            fire(XRealEvent.InputEvent.VoiceCommand("$place 왔어! 사진 찍어줘"), "도쿄: $place")
            fire(XRealEvent.ActionRequest.TriggerSnapshot, "도쿄 사진: $place")
            fire(XRealEvent.PerceptionEvent.WatchHeartRate(bpm = 85f + (0..20).random(), timestamp = System.currentTimeMillis()), "여행 심박")
            delay(intervalMs)
        }

        // Day 2: 교토
        fire(XRealEvent.PerceptionEvent.LocationUpdated(35.0116, 135.7681, "교토"), "교토 이동")
        fire(XRealEvent.InputEvent.VoiceCommand("교토 너무 예쁘다 진짜 일본 같아"), "교토 감상")
        fire(XRealEvent.ActionRequest.TriggerSnapshot, "교토 사진")
        delay(intervalMs)

        val kyotoPlaces = listOf("금각사", "기온 거리", "아라시야마 대나무숲", "후시미 이나리")
        for (place in kyotoPlaces) {
            fire(XRealEvent.InputEvent.VoiceCommand("$place 정말 신기해! 기억해줘"), "교토: $place")
            fire(XRealEvent.ActionRequest.TriggerSnapshot, "교토 사진: $place")
            delay(intervalMs / 2)
        }

        // 여행 위기 (지갑 분실 시뮬레이션)
        fire(XRealEvent.InputEvent.VoiceCommand("어? 지갑이 없어! 어떡해!!"), "위기: 지갑 분실")
        fire(XRealEvent.PerceptionEvent.WatchHeartRate(bpm = 145f, timestamp = System.currentTimeMillis()), "위기 심박 급등")
        fire(XRealEvent.SystemEvent.Error("TRAVEL_CRISIS", "여행 위기: 지갑 분실 신고", severity = ErrorSeverity.WARNING), "여행 위기")
        delay(intervalMs)
        fire(XRealEvent.InputEvent.VoiceCommand("아 다행히 호텔에 있었네. 심장 쫄았다"), "위기 해결")
        fire(XRealEvent.PerceptionEvent.WatchHeartRate(bpm = 75f, timestamp = System.currentTimeMillis()), "안도 심박")
        delay(intervalMs)

        // Day 3: 귀국
        fire(XRealEvent.PerceptionEvent.LocationUpdated(37.4602, 126.4407, "인천공항 (귀국)"), "귀국")
        fire(XRealEvent.InputEvent.VoiceCommand("3일이 왜이렇게 빨라.. 또 오고 싶다! 최고의 여행이었어"), "여행 후기")
        fire(XRealEvent.SystemEvent.TodoCompleted("travel_japan", "일본 3일 여행 완료!", null, "TRAVEL"), "여행 투두 완료")
    }

    /**
     * 📅 WEEKLY_LIFE — 1주일 일상 생활 시뮬레이션
     * 월(수업) → 화(러닝) → 수(수업+사회) → 목(러닝) → 금(수업) → 토(장거리 런) → 일(휴식+계획)
     */
    private suspend fun weeklyLifeScenario(speed: String) {
        val dayMs = when (speed.uppercase()) {
            "FAST" -> 2000L; "SLOW" -> 30000L; "REALTIME" -> 300000L; else -> 8000L
        }
        Log.i(TAG, "📅 WEEKLY_LIFE — 1주일 생활 시뮬레이션 (${dayMs}ms/일)")

        val weekdays = listOf(
            "월요일" to listOf("교실", "수업", "특수반", "저녁 계획"),
            "화요일" to listOf("러닝 5km", "학교", "학부모 상담"),
            "수요일" to listOf("교실", "수업", "친구 저녁"),
            "목요일" to listOf("러닝 8km", "교실", "연구 회의"),
            "금요일" to listOf("교실", "수업 마무리", "주간 회고"),
            "토요일" to listOf("장거리 런 15km", "여행 계획", "친구 만남"),
            "일요일" to listOf("휴식", "독서", "다음주 계획", "수면 최고")
        )

        for ((day, activities) in weekdays) {
            Log.i(TAG, "📅 $day 시작")
            fire(XRealEvent.InputEvent.VoiceCommand("$day 시작! 오늘 할 일: ${activities.joinToString(", ")}"), "$day 시작")
            fire(XRealEvent.PerceptionEvent.WatchHeartRate(bpm = 58f + (0..15).random(), timestamp = System.currentTimeMillis()), "기상 심박")

            for (activity in activities) {
                delay(dayMs / activities.size)
                when {
                    "수업" in activity || "교실" in activity -> {
                        fire(XRealEvent.SystemEvent.UserStateChanged("COMMUTE", "TEACHING"), "수업 시작")
                        fire(XRealEvent.InputEvent.VoiceCommand("오늘 수업: $activity"), activity)
                        fire(XRealEvent.ActionRequest.TriggerSnapshot, "수업 스냅")
                    }
                    "러닝" in activity || "달리기" in activity || "런" in activity -> {
                        val km = activity.filter { it.isDigit() }.toIntOrNull() ?: 5
                        fire(XRealEvent.InputEvent.RunningVoiceCommand(RunningCommandType.START), "$activity 코치 시작")
                        fire(XRealEvent.PerceptionEvent.WatchHeartRate(bpm = 155f, timestamp = System.currentTimeMillis()), "러닝 심박")
                        // 러닝 구간: 심박 + 경로 업데이트로 라우터 체인 테스트
                        fire(XRealEvent.PerceptionEvent.RunningRouteUpdate(
                            distanceMeters = km * 1000f, paceMinPerKm = 6.0f,
                            currentSpeedMps = 2.78f, elevationGainMeters = km.toFloat(),
                            elapsedSeconds = km * 360L, latitude = 37.5326, longitude = 126.990,
                            timestamp = System.currentTimeMillis()
                        ), "${km}km 경로 업데이트")
                        fire(XRealEvent.InputEvent.VoiceCommand("${km}km 러닝 완료!"), "$activity 완료")
                        fire(XRealEvent.InputEvent.RunningVoiceCommand(RunningCommandType.STOP), "$activity 코치 종료")
                    }
                    "친구" in activity || "만남" in activity -> {
                        fire(XRealEvent.PerceptionEvent.PersonIdentified(1001L, "민준", 0.92f, ByteArray(128), System.currentTimeMillis(), 37.5665, 126.978), "친구 만남")
                        fire(XRealEvent.InputEvent.EnrichedVoiceCommand("오랜만이야! 잘 지냈어?", "friend", "happy", 0.9f), "친구 대화")
                    }
                    "계획" in activity || "목표" in activity -> {
                        fire(XRealEvent.InputEvent.VoiceCommand(activity + " 정리"), activity)
                        fire(XRealEvent.SystemEvent.TodoCompleted("weekly_${day}_${activity}", activity, null, "PLANNING"), "투두 완료")
                    }
                    else -> fire(XRealEvent.InputEvent.VoiceCommand(activity), activity)
                }
            }

            // 야간 회고
            delay(dayMs / 5)
            fire(XRealEvent.InputEvent.EnrichedVoiceCommand(
                "$day 하루도 잘 버텼어. 오늘 배운 것: ${activities.last()}",
                "self", listOf("grateful","tired_but_satisfied","peaceful").random(), 0.75f
            ), "$day 야간 회고")
            fire(XRealEvent.PerceptionEvent.WatchHrv(rmssd = 45f + (0..30).random(), sdnn = 55f, meanRR = 900f, timestamp = System.currentTimeMillis()), "수면 HRV")
        }
        fire(XRealEvent.InputEvent.VoiceCommand("1주일 시뮬레이션 완료! 다음 주도 파이팅!"), "주간 마무리")
    }

    /** 이벤트 발사 + 로깅.
     *  Error 이벤트를 발사하면 해당 코드를 injectedErrorCodes에 등록하여
     *  모니터가 이를 "자연 에러"가 아닌 "주입 에러"로 분류할 수 있게 함.
     */
    private fun fire(event: XRealEvent, label: String) {
        // 에러 이벤트면 코드를 주입 세트에 등록 (모니터에서 자연에러 구분용)
        if (event is XRealEvent.SystemEvent.Error) injectedErrorCodes.add(event.code)
        firedCount++
        Log.d(TAG, "🎯 FIRE[$firedCount] → $label")
        ExecutionFlowMonitor.record("ChaosMonkey", "FIRE[$firedCount] $label", ExecutionFlowMonitor.Level.INFO)
        SequenceTracer.log("ChaosMonkey", "FIRE[$firedCount] $label")
        eventBus.publish(event)
    }

    /**
     * 시나리오 종료 리포트.
     *
     * ## 출력 구성
     * 1. 요약 수치 (발사 / 응답 / 에러)
     * 2. 이벤트 흐름 타임라인 — 구독자들이 실제로 한 일을 시간순으로 표시
     * 3. 자연발생 에러 목록 — 디버깅 1순위 (ChaosMonkey가 주입하지 않았는데 터진 것)
     * 4. 진단 메시지 — 파이프라인이 제대로 흘렀는지 판단
     */
    private fun report(scenario: Scenario) {
        val d = "═".repeat(54)
        val sb = StringBuilder()
        sb.appendLine(d)
        sb.appendLine("🤠 CHAOS MONKEY FLOW REPORT: $scenario")
        sb.appendLine("  📤 발사된 이벤트:     $firedCount")
        sb.appendLine("  📢 구독자 반응:       $responseCount  (TTS + DrawCmd)")
        sb.appendLine("  ⚡ 에러 이벤트:       $errorCount  (주입: ${errorCount - naturalErrors.size}, 자연발생: ${naturalErrors.size})")

        // ── 흐름 타임라인
        val timelineToShow = flowTimeline.takeLast(60)
        sb.appendLine("─── 이벤트 흐름 타임라인 (총 ${flowTimeline.size}건, 최근 ${timelineToShow.size}건) ───")
        if (timelineToShow.isEmpty()) {
            sb.appendLine("  ⚠️  구독자 반응 없음 — 이벤트가 아무도 처리하지 않았을 수 있음")
        } else {
            timelineToShow.forEach { sb.appendLine("  $it") }
        }

        // ── 자연발생 에러 (가장 중요한 디버깅 단서)
        if (naturalErrors.isNotEmpty()) {
            sb.appendLine("─── ⚠️  자연발생 에러 (디버깅 대상) — ${naturalErrors.size}건 ────")
            naturalErrors.forEach { sb.appendLine("  $it") }
            val codes = naturalErrors.map { it.substringAfter("] ").substringBefore(":").trim() }.distinct()
            sb.appendLine("  → 조사 권장 태그: ${codes.joinToString(", ")}")
            sb.appendLine("  → adb logcat -s ${codes.joinToString(" ")} AIAgentManager RunningCoachManager")
        } else {
            sb.appendLine("─── ✅ 자연발생 에러 없음 — 파이프라인 안정적!")
        }

        // ── 파이프라인 진단
        sb.appendLine("─── 🔬 파이프라인 진단 ─────────────────────────────")
        val hasTTS = flowTimeline.any { it.startsWith("📢") }
        val hasRunningState = flowTimeline.any { it.startsWith("🏃") }
        val hasSubLog = flowTimeline.any { it.startsWith("📝") }
        val hasMission = flowTimeline.any { it.startsWith("🎯") }
        var diagIssues = 0
        if (!hasTTS && firedCount > 0) {
            sb.appendLine("  ⚠️  TTS 응답 없음 — AI 호출 결과가 없거나 음소거 상태"); diagIssues++
        }
        if (scenario in listOf(Scenario.MORNING_RUN, Scenario.MARATHON_RACE) && !hasRunningState) {
            sb.appendLine("  ⚠️  RunningSessionState 없음 — RunningCoachManager가 활성화되지 않았을 수 있음"); diagIssues++
        }
        if (!hasSubLog) {
            sb.appendLine("  ℹ️  구독자 DebugLog 없음 — FormRouter/Coach가 내부 로그를 안 냈거나 이벤트 미처리")
        }
        if (hasMission) sb.appendLine("  ✅ MissionConductor 반응 감지")
        if (diagIssues == 0 && hasTTS && hasSubLog) {
            sb.appendLine("  ✅ 파이프라인 정상 — TTS/RunSession/SubLog 모두 확인됨")
        } else if (diagIssues == 0) {
            sb.appendLine("  ✅ 파이프라인 이상 없음 (SubLog 미발행은 쿨다운/속도 정상)")
        }
        sb.appendLine(d)

        Log.w(TAG, sb.toString())
        ExecutionFlowMonitor.dump(last = 50, header = "🤠 CHAOS END: $scenario")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  BroadcastReceiver — ADB 트리거
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ADB 브로드캐스트로 ChaosMonkey를 트리거하는 리시버.
 *
 * ```bash
 * adb shell am broadcast -a com.xreal.nativear.CHAOS --es scenario FULL_RODEO
 * ```
 */
class ChaosReceiver : BroadcastReceiver(), KoinComponent {
    private val chaosMonkey: ChaosMonkey by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ChaosMonkey.CHAOS_ACTION) return
        val name = intent.getStringExtra("scenario")?.uppercase() ?: "FULL_RODEO"
        val scenario = runCatching { ChaosMonkey.Scenario.valueOf(name) }
            .getOrElse {
                Log.e(ChaosMonkey.TAG, "알 수 없는 시나리오: $name → FULL_RODEO 사용")
                ChaosMonkey.Scenario.FULL_RODEO
            }
        val speed = intent.getStringExtra("speed") ?: "NORMAL"
        Log.w(ChaosMonkey.TAG, "📡 ADB 브로드캐스트 수신: scenario=$scenario, speed=$speed")
        chaosMonkey.runScenario(scenario, speed)
    }
}
