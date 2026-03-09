package com.xreal.nativear.core

import android.util.Log
import com.xreal.nativear.DrawCommand
import com.xreal.nativear.DrawElement
import kotlinx.coroutines.*
import kotlin.math.*
import kotlin.random.Random

/**
 * LifeTornadoEngine — 10년 인생 시뮬레이션 토네이도 🌪️
 *
 * ## 개념
 * 10년(3,650일)의 인생 이벤트를 ~2분으로 압축해 발사.
 * 여행 · 러닝 · 교실 · 사회관계 · 건강 · 라이프로깅 · 계획 등
 * 수만 가지 조합이 EventBus를 통해 전체 시스템을 휩쓸어버림.
 *
 * ## 생애 타임라인 (10년)
 * | 기간     | 핵심 생애 단계                                      |
 * |---------|--------------------------------------------------|
 * | Year 1  | 러닝 입문 (5K), 교사 첫해, 국내 여행               |
 * | Year 2  | 10K 도전, 학생 관계 형성, 한강 정기 러닝            |
 * | Year 3  | 하프마라톤, 첫 해외여행(도쿄), 새 학생들             |
 * | Year 4  | 풀마라톤 도전, 특수교육 학생 케어, 유럽 여행 계획     |
 * | Year 5  | 풀마라톤 완주!, 파리 여행, 사회관계 확장             |
 * | Year 6  | 부상 위기 + 회복, 석사 과정 시작                   |
 * | Year 7  | 회복 후 복귀, 뉴욕 여행, 건강 관리 루틴              |
 * | Year 8  | 마라톤 기록 경신, 특수학생 졸업 성취               |
 * | Year 9  | 생애 건강 최고점, 학교 리더십, 일본 장기 여행        |
 * | Year 10 | 10년 회고 + 다음 10년 설계, 풀코스 sub-4 달성      |
 *
 * ## ADB 트리거
 * ```bash
 * adb shell am broadcast -a com.xreal.nativear.CHAOS -p com.xreal.nativear --es scenario LIFE_TORNADO
 * adb logcat -s LifeTornado FlowMon SEQ
 * ```
 */
class LifeTornadoEngine(private val eventBus: GlobalEventBus) {

    companion object {
        const val TAG = "LifeTornado"
        private const val STATS_INTERVAL = 30   // 30일마다 통계 출력

        /**
         * 속도 설정 (ADB --es speed 파라미터)
         * FAST     : 1일 = 15ms   → 10년 ≈ 55초  (빠른 빌드 검증)
         * NORMAL   : 1일 = 200ms  → 10년 ≈ 12분  (시스템 반응 관찰용) ← 기본값
         * SLOW     : 1일 = 1000ms → 10년 ≈ 1시간 (더 깊은 관찰)
         * REALTIME : 1일 = 2000ms → 10년 ≈ 2시간 (거의 실제 속도)
         */
        fun dayDelayMs(speed: String) = when (speed.uppercase()) {
            "FAST"     -> 15L
            "SLOW"     -> 1000L
            "REALTIME" -> 2000L
            else       -> 200L    // NORMAL (기본)
        }

        // GPS 좌표 상수
        private const val HOME_LAT    = 37.5665;  private const val HOME_LON    = 126.9780
        private const val HANGANG_LAT = 37.5326;  private const val HANGANG_LON = 126.9900
        private const val SCHOOL_LAT  = 37.5900;  private const val SCHOOL_LON  = 126.9930
        private const val AIRPORT_LAT = 37.4602;  private const val AIRPORT_LON = 126.4407
        private const val TOKYO_LAT   = 35.6762;  private const val TOKYO_LON   = 139.6503
        private const val PARIS_LAT   = 48.8566;  private const val PARIS_LON   = 2.3522
        private const val NYC_LAT     = 40.7128;  private const val NYC_LON     = -74.0060
        private const val KYOTO_LAT   = 35.0116;  private const val KYOTO_LON   = 135.7681
        private const val JEJU_LAT    = 33.4996;  private const val JEJU_LON    = 126.5312
    }

    @Volatile var speedMode = "NORMAL"

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var tornadoJob: Job? = null

    // ── 통계 ─────────────────────────────────────────────────────────────────────
    @Volatile private var totalFired = 0
    @Volatile private var totalResponses = 0
    @Volatile private var totalErrors = 0
    private val startMs get() = System.currentTimeMillis()
    @Volatile private var launchMs = 0L

    // ─────────────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────────────

    fun startTornado(speed: String = "NORMAL") {
        tornadoJob?.cancel()
        speedMode = speed
        totalFired = 0; totalResponses = 0; totalErrors = 0
        launchMs = System.currentTimeMillis()
        val dayMs = dayDelayMs(speed)
        val tenYearMin = dayMs * 3650 / 60000

        Log.w(TAG, """
╔══════════════════════════════════════════════════════╗
║  🌪️ LIFE TORNADO — 10년 인생 시뮬레이션 시작!         ║
║  3,650일 × ~20이벤트 ≈ 73,000개 이벤트               ║
║  속도: $speed | 1일=${dayMs}ms | 10년≈${tenYearMin}분              ║
╚══════════════════════════════════════════════════════╝""".trimIndent())

        ExecutionFlowMonitor.record("LifeTornado", "🌪️ 10년 시뮬레이션 시작", ExecutionFlowMonitor.Level.DANGER)

        // 응답 모니터
        val monitorJob = scope.launch {
            try {
                eventBus.events.collect { event ->
                    try {
                        when (event) {
                            is XRealEvent.ActionRequest.SpeakTTS -> {
                                totalResponses++
                                if (totalResponses % 10 == 0)
                                    Log.d(TAG, "💬 TTS응답 ×$totalResponses: \"${event.text.take(30)}\"")
                            }
                            is XRealEvent.SystemEvent.Error -> { totalErrors++ }
                            else -> Unit
                        }
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { /* ignore */ }
                }
            } catch (e: CancellationException) { /* normal */ }
        }

        tornadoJob = scope.launch {
            try {
                simulateTenYears()
            } finally {
                monitorJob.cancel()
                finalReport()
            }
        }
    }

    fun stop() {
        tornadoJob?.cancel()
        Log.w(TAG, "🌪️ TORNADO STOPPED — 발사: $totalFired, 응답: $totalResponses")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  10년 인생 시뮬레이터
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun simulateTenYears() {
        for (year in 1..10) {
            for (month in 1..12) {
                for (day in 1..30) {
                    val context = LifeContext(year, month, day)
                    val events = generateDayEvents(context)
                    for (event in events) {
                        fire(event)
                        // 아주 가끔 짧은 호흡 (GlobalEventBus 버퍼 압력 관리)
                        if (totalFired % 500 == 0) yield()
                    }
                    delay(dayDelayMs(speedMode))
                    if (day % STATS_INTERVAL == 0) printProgress(context)
                }
                // 월 전환: 상황 변화 발사
                fireMonthTransition(year, month)
            }
            // 연도 전환: 연간 회고 + 다음해 계획
            fireYearTransition(year)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  하루 이벤트 생성
    // ─────────────────────────────────────────────────────────────────────────────

    private fun generateDayEvents(ctx: LifeContext): List<XRealEvent> {
        val events = mutableListOf<XRealEvent>()
        val rng = Random(ctx.seed)                  // 날짜별 고정 시드 → 재현 가능

        // ── 1. 아침 루틴 (매일) ──────────────────────────────────────────────────
        events += morningRoutine(ctx, rng)

        // ── 2. 요일별 활동 ───────────────────────────────────────────────────────
        when (ctx.dayOfWeek) {
            0, 6 -> {   // 주말
                if (rng.nextFloat() < ctx.runningProbability) events += runningSession(ctx, rng)
                if (rng.nextFloat() < 0.3f) events += travelSnippet(ctx, rng)
                if (rng.nextFloat() < 0.4f) events += socialInteraction(ctx, rng)
            }
            else -> {   // 평일
                events += teachingSession(ctx, rng)
                if (rng.nextFloat() < ctx.runningProbability * 0.6f) events += runningSession(ctx, rng)
            }
        }

        // ── 3. 특수 이벤트 (확률 기반) ──────────────────────────────────────────
        if (rng.nextFloat() < 0.08f) events += healthCrisis(ctx, rng)
        if (rng.nextFloat() < 0.05f) events += travelAdventure(ctx, rng)
        if (rng.nextFloat() < 0.15f) events += planningSession(ctx, rng)
        if (rng.nextFloat() < 0.2f)  events += lifeLogEntry(ctx, rng)

        // ── 4. 생애 단계별 특별 이벤트 ──────────────────────────────────────────
        events += lifeMilestoneCheck(ctx, rng)

        // ── 5. 야간 회고 (매일) ──────────────────────────────────────────────────
        events += nightReflection(ctx, rng)

        return events
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  아침 루틴
    // ─────────────────────────────────────────────────────────────────────────────

    private fun morningRoutine(ctx: LifeContext, rng: Random): List<XRealEvent> {
        val now = ctx.timestamp
        val restingHr = 50 + (ctx.year * 2) + rng.nextInt(15)   // 연도 따라 변화
        return listOf(
            XRealEvent.PerceptionEvent.WatchHeartRate(bpm = restingHr.toFloat(), timestamp = now),
            XRealEvent.PerceptionEvent.WatchSkinTemperature(
                temperature = 35.5f + rng.nextFloat() * 1.5f,
                ambientTemperature = 18f + rng.nextFloat() * 10f,
                timestamp = now
            ),
            XRealEvent.InputEvent.VoiceCommand(text = morningCommands.random(rng)),
            XRealEvent.PerceptionEvent.LocationUpdated(
                lat = HOME_LAT + rng.nextDouble() * 0.001,
                lon = HOME_LON + rng.nextDouble() * 0.001,
                address = "서울 집"
            ),
            XRealEvent.SystemEvent.BatteryLevel(percent = 85 + rng.nextInt(15)),
            XRealEvent.InputEvent.AudioLevel(level = 0.1f + rng.nextFloat() * 0.3f)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  러닝 세션 — 연도별 거리 증가
    // ─────────────────────────────────────────────────────────────────────────────

    private fun runningSession(ctx: LifeContext, rng: Random): List<XRealEvent> {
        val events = mutableListOf<XRealEvent>()
        val targetKm = when (ctx.year) {
            1    -> 3f + rng.nextFloat() * 3f       // 3-6km (입문)
            2    -> 5f + rng.nextFloat() * 5f       // 5-10km
            3    -> 10f + rng.nextFloat() * 11f     // 10-21km (하프)
            4    -> 15f + rng.nextFloat() * 27f     // 15-42km (첫 풀)
            5, 6 -> 10f + rng.nextFloat() * 32f     // 다양한 거리
            7    -> 5f + rng.nextFloat() * 15f      // 부상 회복기 조심
            else -> 15f + rng.nextFloat() * 27f     // 성숙기
        }
        val targetKmInt = targetKm.toInt()
        val paceBase = 6.5f - ctx.year * 0.15f      // 연도별 페이스 향상
        val maxHr = 160f + rng.nextFloat() * 25f

        // 러닝 시작
        events += XRealEvent.SystemEvent.RunningSessionState(
            state = RunningState.ACTIVE, elapsedSeconds = 0L, totalDistanceMeters = 0f
        )
        events += XRealEvent.SystemEvent.UserStateChanged("IDLE", "RUNNING")

        // GPS 경로 발사 (1km마다)
        var distance = 0f
        var lat = HANGANG_LAT; var lon = HANGANG_LON
        val elapsedBase = 0L
        for (km in 1..targetKmInt) {
            distance = km * 1000f
            lat += 0.005 * sin(km.toDouble())
            lon += 0.005 * cos(km.toDouble())
            val elapsed = (km * paceBase * 60).toLong()
            val hr = 130f + (km.toFloat() / targetKmInt * (maxHr - 130f)) + rng.nextFloat() * 10f

            events += XRealEvent.PerceptionEvent.RunningRouteUpdate(
                distanceMeters = distance,
                paceMinPerKm = paceBase + rng.nextFloat() * 0.5f,
                currentSpeedMps = 1000f / (paceBase * 60f),
                elevationGainMeters = km * 2f + rng.nextFloat() * 5f,
                elapsedSeconds = elapsed,
                latitude = lat, longitude = lon,
                timestamp = ctx.timestamp + elapsed * 1000
            )
            events += XRealEvent.PerceptionEvent.WatchHeartRate(bpm = hr, timestamp = ctx.timestamp + elapsed * 1000)
            events += XRealEvent.PerceptionEvent.RunningDynamics(
                cadence = 160f + rng.nextFloat() * 20f,
                verticalOscillation = 8f + rng.nextFloat() * 4f,
                groundContactTime = 250f - ctx.year * 5f + rng.nextFloat() * 30f,
                groundReactionForce = 2.2f + rng.nextFloat() * 0.5f,
                timestamp = ctx.timestamp + elapsed * 1000
            )
            // 러닝 중 생각 → 음성 메모
            if (km % 5 == 0 && rng.nextFloat() < 0.5f) {
                events += XRealEvent.InputEvent.VoiceCommand(text = runningThoughts.random(rng))
            }
        }

        // 러닝 완료
        events += XRealEvent.SystemEvent.RunningSessionState(
            state = RunningState.STOPPED,
            elapsedSeconds = (targetKm * paceBase * 60).toLong(),
            totalDistanceMeters = targetKm * 1000f
        )
        events += XRealEvent.PerceptionEvent.WatchHrv(
            rmssd = 35f + rng.nextFloat() * 30f,
            sdnn = 45f + rng.nextFloat() * 30f,
            meanRR = 60000f / 75f,
            timestamp = ctx.timestamp
        )
        events += XRealEvent.SystemEvent.UserStateChanged("RUNNING", "RECOVERY")

        // 완주 메모
        events += XRealEvent.InputEvent.VoiceCommand(
            text = "오늘 ${targetKmInt}km 완주! 페이스 ${String.format("%.1f", paceBase)}/km"
        )
        return events
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  교실 수업 세션
    // ─────────────────────────────────────────────────────────────────────────────

    private fun teachingSession(ctx: LifeContext, rng: Random): List<XRealEvent> {
        val events = mutableListOf<XRealEvent>()
        val studentCount = 25 + rng.nextInt(10)

        events += XRealEvent.PerceptionEvent.LocationUpdated(
            lat = SCHOOL_LAT, lon = SCHOOL_LON, address = "학교"
        )
        events += XRealEvent.SystemEvent.UserStateChanged("COMMUTE", "TEACHING")
        events += XRealEvent.InputEvent.AudioLevel(level = 0.4f + rng.nextFloat() * 0.4f)

        // 학생들 목소리 처리
        val questionCount = rng.nextInt(5) + 2
        for (i in 0 until questionCount) {
            events += XRealEvent.InputEvent.EnrichedVoiceCommand(
                text = studentQuestions.random(rng),
                speaker = "student_${rng.nextInt(studentCount)}",
                emotion = studentEmotions.random(rng),
                emotionScore = 0.6f + rng.nextFloat() * 0.4f
            )
        }

        // 교사 응답
        events += XRealEvent.InputEvent.VoiceCommand(text = teacherResponses.random(rng))

        // 특수교육 학생 케어 (Year 3 이후 특수반 담당)
        if (ctx.year >= 3 && rng.nextFloat() < 0.4f) {
            events += specialEducationCare(ctx, rng)
        }

        // 수업 중 TriggerSnapshot (칠판, 교재 OCR)
        if (rng.nextFloat() < 0.3f) {
            events += XRealEvent.ActionRequest.TriggerSnapshot
        }

        events += XRealEvent.SystemEvent.UserStateChanged("TEACHING", "BREAK")
        return events
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  특수교육 케어
    // ─────────────────────────────────────────────────────────────────────────────

    private fun specialEducationCare(ctx: LifeContext, rng: Random): List<XRealEvent> {
        return listOf(
            XRealEvent.InputEvent.EnrichedVoiceCommand(
                text = specialCareTexts.random(rng),
                speaker = "special_student",
                emotion = "confused",
                emotionScore = 0.8f
            ),
            XRealEvent.InputEvent.VoiceCommand(text = specialTeacherResponses.random(rng)),
            // 학습 진도 메모
            XRealEvent.InputEvent.VoiceCommand(
                text = "특수반 ${rng.nextInt(5)+1}번 학생 오늘 ${specialAchievements.random(rng)}"
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  여행 스니펫 (일상 속 단기 여행)
    // ─────────────────────────────────────────────────────────────────────────────

    private fun travelSnippet(ctx: LifeContext, rng: Random): List<XRealEvent> {
        val dest = domesticDestinations.random(rng)
        return listOf(
            XRealEvent.PerceptionEvent.LocationUpdated(
                lat = dest.second + rng.nextDouble() * 0.01,
                lon = dest.third + rng.nextDouble() * 0.01,
                address = dest.first
            ),
            XRealEvent.InputEvent.VoiceCommand(text = travelSnippetTexts.random(rng) + dest.first),
            XRealEvent.ActionRequest.TriggerSnapshot,    // 풍경 사진
            XRealEvent.PerceptionEvent.WatchHeartRate(
                bpm = 75f + rng.nextFloat() * 20f, timestamp = ctx.timestamp
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  해외 여행 어드벤처 (확률 이벤트)
    // ─────────────────────────────────────────────────────────────────────────────

    private fun travelAdventure(ctx: LifeContext, rng: Random): List<XRealEvent> {
        val events = mutableListOf<XRealEvent>()
        val destination = when (ctx.year) {
            3    -> Triple("도쿄", TOKYO_LAT, TOKYO_LON)
            4, 5 -> Triple("파리", PARIS_LAT, PARIS_LON)
            7    -> Triple("뉴욕", NYC_LAT, NYC_LON)
            9    -> Triple("교토", KYOTO_LAT, KYOTO_LON)
            else -> Triple("제주도", JEJU_LAT, JEJU_LON)
        }

        // 출발 (공항)
        events += XRealEvent.PerceptionEvent.LocationUpdated(
            lat = AIRPORT_LAT, lon = AIRPORT_LON, address = "인천공항"
        )
        events += XRealEvent.InputEvent.VoiceCommand(text = "${destination.first} 여행 시작! 설레는데")
        events += XRealEvent.SystemEvent.UserStateChanged("HOME", "TRAVEL")
        events += XRealEvent.SystemEvent.NetworkStatus(isConnected = true)

        // 비행 중 (네트워크 단절)
        events += XRealEvent.SystemEvent.NetworkStatus(isConnected = false)
        events += XRealEvent.PerceptionEvent.AudioEnvironment(
            events = listOf("airplane_noise" to 0.95f, "speech" to 0.3f),
            embedding = ByteArray(1024) { (rng.nextInt(256) - 128).toByte() },
            timestamp = ctx.timestamp,
            latitude = null, longitude = null
        )

        // 목적지 도착
        events += XRealEvent.PerceptionEvent.LocationUpdated(
            lat = destination.second + rng.nextDouble() * 0.01,
            lon = destination.third + rng.nextDouble() * 0.01,
            address = "${destination.first} 시내"
        )
        events += XRealEvent.SystemEvent.NetworkStatus(isConnected = true)
        events += XRealEvent.InputEvent.VoiceCommand(text = "${destination.first} 도착! ${travelArrivalTexts.random(rng)}")
        events += XRealEvent.ActionRequest.TriggerSnapshot

        // 현지 활동 (랜덤 이벤트)
        repeat(3 + rng.nextInt(5)) {
            events += XRealEvent.InputEvent.VoiceCommand(text = travelActivityTexts.random(rng) + destination.first)
            events += XRealEvent.PerceptionEvent.WatchHeartRate(
                bpm = 80f + rng.nextFloat() * 30f, timestamp = ctx.timestamp
            )
            if (rng.nextFloat() < 0.3f) events += XRealEvent.ActionRequest.TriggerSnapshot
        }

        // 여행 위기 (15% 확률)
        if (rng.nextFloat() < 0.15f) {
            val crisisType = travelCrises.random(rng)
            events += XRealEvent.InputEvent.VoiceCommand(text = crisisType)
            events += XRealEvent.SystemEvent.Error(
                code = "TRAVEL_CRISIS",
                message = "여행 위기: $crisisType",
                severity = ErrorSeverity.WARNING
            )
            events += XRealEvent.PerceptionEvent.WatchHeartRate(
                bpm = 110f + rng.nextFloat() * 40f, timestamp = ctx.timestamp
            )
        }

        // 귀국
        events += XRealEvent.PerceptionEvent.LocationUpdated(
            lat = AIRPORT_LAT, lon = AIRPORT_LON, address = "인천공항 (귀국)"
        )
        events += XRealEvent.InputEvent.VoiceCommand(
            text = "${destination.first} 여행 끝. ${travelReturnTexts.random(rng)}"
        )
        events += XRealEvent.SystemEvent.UserStateChanged("TRAVEL", "HOME")
        return events
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  사회 관계 이벤트
    // ─────────────────────────────────────────────────────────────────────────────

    private fun socialInteraction(ctx: LifeContext, rng: Random): List<XRealEvent> {
        val events = mutableListOf<XRealEvent>()
        val scenario = rng.nextInt(6)

        when (scenario) {
            0 -> {  // 친구 만남
                events += XRealEvent.PerceptionEvent.PersonIdentified(
                    personId = 1001L + rng.nextInt(20),
                    personName = friendNames.random(rng),
                    confidence = 0.85f + rng.nextFloat() * 0.15f,
                    faceEmbedding = ByteArray(128) { rng.nextInt(256).toByte() },
                    timestamp = ctx.timestamp, latitude = ctx.lat, longitude = ctx.lon
                )
                events += XRealEvent.InputEvent.EnrichedVoiceCommand(
                    text = socialTexts.random(rng),
                    speaker = "friend",
                    emotion = "happy",
                    emotionScore = 0.8f + rng.nextFloat() * 0.2f
                )
            }
            1 -> {  // 갈등
                events += XRealEvent.InputEvent.EnrichedVoiceCommand(
                    text = conflictTexts.random(rng),
                    speaker = "colleague",
                    emotion = "angry",
                    emotionScore = 0.7f + rng.nextFloat() * 0.3f
                )
                events += XRealEvent.PerceptionEvent.WatchHeartRate(
                    bpm = 95f + rng.nextFloat() * 30f, timestamp = ctx.timestamp
                )
                events += XRealEvent.PerceptionEvent.WatchHrv(
                    rmssd = 20f + rng.nextFloat() * 15f,
                    sdnn = 25f + rng.nextFloat() * 15f,
                    meanRR = 700f, timestamp = ctx.timestamp
                )
            }
            2 -> {  // 새 인물 등장
                events += XRealEvent.PerceptionEvent.PersonIdentified(
                    personId = 2000L + ctx.year * 100 + rng.nextInt(50),
                    personName = null,   // 아직 이름 모름
                    confidence = 0.65f + rng.nextFloat() * 0.2f,
                    faceEmbedding = ByteArray(128) { rng.nextInt(256).toByte() },
                    timestamp = ctx.timestamp, latitude = ctx.lat, longitude = ctx.lon
                )
                events += XRealEvent.InputEvent.VoiceCommand(text = newPersonTexts.random(rng))
            }
            3 -> {  // 화해 / 감사
                events += XRealEvent.InputEvent.EnrichedVoiceCommand(
                    text = reconciliationTexts.random(rng),
                    speaker = "user",
                    emotion = "relief",
                    emotionScore = 0.75f
                )
                events += XRealEvent.PerceptionEvent.WatchHeartRate(
                    bpm = 68f + rng.nextFloat() * 10f, timestamp = ctx.timestamp
                )
            }
            4 -> {  // 모임/파티
                repeat(3 + rng.nextInt(5)) {
                    events += XRealEvent.PerceptionEvent.PersonIdentified(
                        personId = 1001L + rng.nextInt(30),
                        personName = friendNames.random(rng),
                        confidence = 0.7f + rng.nextFloat() * 0.3f,
                        faceEmbedding = ByteArray(128) { rng.nextInt(256).toByte() },
                        timestamp = ctx.timestamp, latitude = ctx.lat, longitude = ctx.lon
                    )
                }
                events += XRealEvent.InputEvent.AudioLevel(level = 0.6f + rng.nextFloat() * 0.4f)
                events += XRealEvent.InputEvent.VoiceCommand(text = socialGatheringTexts.random(rng))
            }
            else -> {   // 온라인 대화
                events += XRealEvent.InputEvent.VoiceCommand(text = onlineTexts.random(rng))
            }
        }
        return events
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  건강 위기
    // ─────────────────────────────────────────────────────────────────────────────

    private fun healthCrisis(ctx: LifeContext, rng: Random): List<XRealEvent> {
        val crisis = healthCrises[rng.nextInt(healthCrises.size)]
        val isMajor = ctx.year == 6 || rng.nextFloat() < 0.2f   // Year 6 = 부상 위기

        return if (isMajor) {
            listOf(
                XRealEvent.PerceptionEvent.WatchHeartRate(
                    bpm = 100f + rng.nextFloat() * 50f, timestamp = ctx.timestamp
                ),
                XRealEvent.PerceptionEvent.WatchSpO2(
                    spo2 = 90 + rng.nextInt(6), timestamp = ctx.timestamp
                ),
                XRealEvent.SystemEvent.Error(
                    code = "HEALTH_ALERT", message = crisis.first, severity = ErrorSeverity.CRITICAL
                ),
                XRealEvent.InputEvent.VoiceCommand(text = crisis.second),
                XRealEvent.SystemEvent.UserStateChanged("ACTIVE", "INJURED"),
                XRealEvent.PerceptionEvent.WatchHrv(
                    rmssd = 10f + rng.nextFloat() * 15f,
                    sdnn = 15f + rng.nextFloat() * 15f,
                    meanRR = 60000f / 110f, timestamp = ctx.timestamp
                )
            )
        } else {
            listOf(
                XRealEvent.PerceptionEvent.WatchHeartRate(
                    bpm = 95f + rng.nextFloat() * 25f, timestamp = ctx.timestamp
                ),
                XRealEvent.InputEvent.VoiceCommand(text = crisis.second),
                XRealEvent.SystemEvent.UserStateChanged("ACTIVE", "UNWELL")
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  계획/투두 세션
    // ─────────────────────────────────────────────────────────────────────────────

    private fun planningSession(ctx: LifeContext, rng: Random): List<XRealEvent> {
        val events = mutableListOf<XRealEvent>()

        // 투두 완료
        val completedCount = 1 + rng.nextInt(4)
        for (i in 0 until completedCount) {
            events += XRealEvent.SystemEvent.TodoCompleted(
                todoId = "todo_${ctx.year}_${ctx.month}_${ctx.day}_$i",
                todoTitle = todoTitles.random(rng),
                parentGoalId = yearlyGoals[minOf(ctx.year - 1, 9)],
                category = todoCategories.random(rng),
                timestamp = ctx.timestamp
            )
        }

        // HUD 캘린더 뷰 드로잉
        events += XRealEvent.ActionRequest.DrawingCommand(
            DrawCommand.Add(
                DrawElement.Text(
                    id = "plan_date_${ctx.day}",
                    x = 80f, y = 5f,
                    text = "Year${ctx.year} M${ctx.month} D${ctx.day}",
                    size = 16f,
                    color = "#00FFAA",
                    opacity = 0.8f
                )
            )
        )

        // 다음 일정 계획
        events += XRealEvent.InputEvent.VoiceCommand(text = planningTexts.random(rng))
        return events
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  라이프로깅 엔트리 (디지털 트윈 데이터)
    // ─────────────────────────────────────────────────────────────────────────────

    private fun lifeLogEntry(ctx: LifeContext, rng: Random): List<XRealEvent> {
        return listOf(
            // 음성 메모
            XRealEvent.InputEvent.AudioEmbedding(
                transcript = lifeLogTexts.random(rng),
                audioEmbedding = ByteArray(256) { rng.nextInt(256).toByte() },
                timestamp = ctx.timestamp,
                latitude = ctx.lat, longitude = ctx.lon
            ),
            // 시각적 기억 캡처
            XRealEvent.ActionRequest.TriggerSnapshot,
            // 감정 태그
            XRealEvent.InputEvent.EnrichedVoiceCommand(
                text = lifeLogTexts.random(rng),
                speaker = "self",
                emotion = lifeEmotions.random(rng),
                emotionScore = 0.5f + rng.nextFloat() * 0.5f
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  생애 마일스톤 체크
    // ─────────────────────────────────────────────────────────────────────────────

    private fun lifeMilestoneCheck(ctx: LifeContext, rng: Random): List<XRealEvent> {
        val events = mutableListOf<XRealEvent>()

        // 특별한 날
        val specialDay = when {
            ctx.month == 3 && ctx.day == 1 -> "마라톤 시즌 시작"
            ctx.month == 3 && ctx.day == 15 && ctx.year == 4 -> "첫 풀마라톤 도전!"
            ctx.month == 3 && ctx.day == 15 && ctx.year == 5 -> "풀마라톤 완주! Sub-4:30 달성! 🏆"
            ctx.month == 3 && ctx.day == 15 && ctx.year == 10 -> "10년 기념 마라톤 Sub-4:00 달성! 🏆"
            ctx.month == 9 && ctx.day == 1 -> "새 학기 시작"
            ctx.month == 12 && ctx.day == 30 -> "연말 정리"
            ctx.month == 1 && ctx.day == 1 -> "Year ${ctx.year} 시작!"
            else -> null
        }

        specialDay?.let { milestone ->
            events += XRealEvent.InputEvent.VoiceCommand(text = milestone)
            events += XRealEvent.SystemEvent.TodoCompleted(
                todoId = "milestone_${ctx.year}_${ctx.month}_${ctx.day}",
                todoTitle = milestone,
                parentGoalId = yearlyGoals[minOf(ctx.year - 1, 9)],
                category = "MILESTONE",
                timestamp = ctx.timestamp
            )
            events += XRealEvent.ActionRequest.DrawingCommand(
                DrawCommand.Add(
                    DrawElement.Text(
                        id = "milestone_${ctx.year}_${ctx.month}",
                        x = 50f, y = 50f,
                        text = "🏆 $milestone",
                        size = 28f,
                        color = "#FFD700",
                        opacity = 1.0f
                    )
                )
            )
            Log.w(TAG, "🏆 MILESTONE: Year${ctx.year} — $milestone")
        }

        return events
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  야간 회고
    // ─────────────────────────────────────────────────────────────────────────────

    private fun nightReflection(ctx: LifeContext, rng: Random): List<XRealEvent> {
        return listOf(
            XRealEvent.PerceptionEvent.WatchHeartRate(
                bpm = 55f + rng.nextFloat() * 15f, timestamp = ctx.timestamp
            ),
            XRealEvent.PerceptionEvent.WatchHrv(
                rmssd = 40f + rng.nextFloat() * 40f,
                sdnn = 50f + rng.nextFloat() * 30f,
                meanRR = 60000f / 62f, timestamp = ctx.timestamp
            ),
            XRealEvent.InputEvent.EnrichedVoiceCommand(
                text = nightReflectionTexts.random(rng),
                speaker = "self",
                emotion = nightEmotions.random(rng),
                emotionScore = 0.5f + rng.nextFloat() * 0.5f
            ),
            XRealEvent.InputEvent.AudioLevel(level = 0.05f + rng.nextFloat() * 0.1f)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  월/연도 전환 이벤트
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun fireMonthTransition(year: Int, month: Int) {
        val seasonalSituation = when (month) {
            3, 4, 5 -> "SPRING_ACTIVE"
            6, 7, 8 -> "SUMMER_INTENSE"
            9, 10, 11 -> "AUTUMN_REFLECTIVE"
            else -> "WINTER_RESTORATIVE"
        }
        fire(XRealEvent.SystemEvent.UserStateChanged("MONTHLY_RESET", seasonalSituation))
        fire(XRealEvent.InputEvent.VoiceCommand(text = "${year}년 ${month}월 돌아보기: ${monthSummaries[month-1]}"))
        delay(5L)
    }

    private suspend fun fireYearTransition(year: Int) {
        val summary = yearlyNarratives[minOf(year - 1, 9)]
        Log.w(TAG, "\n🗓️ ═══ Year $year 완료: $summary")
        fire(XRealEvent.SystemEvent.TodoCompleted(
            todoId = "year_${year}_complete",
            todoTitle = "${year}년 완주: $summary",
            parentGoalId = null,
            category = "YEARLY_REVIEW",
            timestamp = System.currentTimeMillis()
        ))
        fire(XRealEvent.InputEvent.VoiceCommand(text = "${year}년 회고: $summary"))
        fire(XRealEvent.SystemEvent.BatteryLevel(percent = 100))   // 새해 = 풀 에너지
        delay(30L)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  내부 유틸
    // ─────────────────────────────────────────────────────────────────────────────

    private fun fire(event: XRealEvent) {
        totalFired++
        if (totalFired % 1000 == 0) {
            val elapsedS = (System.currentTimeMillis() - launchMs) / 1000.0
            Log.d(TAG, "⚡ FIRED $totalFired events (${elapsedS.toInt()}s elapsed)")
        }
        eventBus.publish(event)
    }

    private fun printProgress(ctx: LifeContext) {
        val elapsedS = (System.currentTimeMillis() - launchMs) / 1000.0
        val totalDays = ctx.year * 360 + ctx.month * 30 + ctx.day
        val pct = totalDays * 100 / 3600
        Log.i(TAG, "📅 Y${ctx.year}/M${ctx.month}/D${ctx.day} | 발사: $totalFired | 응답: $totalResponses | 에러: $totalErrors | ${elapsedS.toInt()}s ($pct% 완료)")
        ExecutionFlowMonitor.record("LifeTornado", "Y${ctx.year}M${ctx.month} FIRED=$totalFired RESP=$totalResponses ERR=$totalErrors", ExecutionFlowMonitor.Level.INFO)
    }

    private fun finalReport() {
        val totalSec = (System.currentTimeMillis() - launchMs) / 1000.0
        val sb = StringBuilder()
        val d = "═".repeat(56)
        sb.appendLine(d)
        sb.appendLine("🌪️ LIFE TORNADO FINAL REPORT")
        sb.appendLine("  📅 시뮬레이션 기간:   10년 (3,650일)")
        sb.appendLine("  ⚡ 총 발사 이벤트:    $totalFired")
        sb.appendLine("  📢 총 시스템 응답:    $totalResponses")
        sb.appendLine("  ❌ 총 에러 발생:      $totalErrors")
        sb.appendLine("  ⏱️ 소요 시간:         ${totalSec.toInt()}초")
        sb.appendLine("  🚀 발사 속도:         ${"%.0f".format(totalFired / totalSec)}/초")
        sb.appendLine("  📊 응답률:            ${"%.1f".format(totalResponses * 100.0 / maxOf(totalFired, 1))}%")
        sb.appendLine(d)
        Log.w(TAG, sb.toString())
        ExecutionFlowMonitor.dump(last = 80, header = "🌪️ LIFE TORNADO END")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    //  데이터 풀 (텍스트, 좌표 등)
    // ─────────────────────────────────────────────────────────────────────────────

    private val morningCommands = listOf(
        "오늘 날씨 어때?", "오늘 일정 알려줘", "아침 할일 보여줘", "어제 기억 정리해줘",
        "몸 상태 체크해줘", "오늘 러닝 날씨야?", "수면 품질 어땠어?", "오늘 뭐 먹을까?",
        "범블비 좋은 아침", "오늘 학교 수업 몇시야?"
    )
    private val runningThoughts = listOf(
        "지금 심박 좀 높다", "이 페이스 유지할게", "10km 지점 통과!", "남은 거리 알려줘",
        "다음 주 대회 준비 됐나?", "오늘 컨디션 좋다", "힘드네 페이스 좀 줄이자",
        "뒤꿈치 좀 아프다 주의"
    )
    private val studentQuestions = listOf(
        "선생님 이 문제 모르겠어요", "오늘 숙제가 뭐예요?", "시험 범위 어디까지예요?",
        "이거 왜 이렇게 되는 거예요?", "발표 언제 해요?", "이 공식 외워야 해요?",
        "짝꿍이랑 싸웠어요", "화장실 다녀와도 돼요?", "선생님 머리 잘랐어요?",
        "이 개념 다시 설명해주세요", "모둠 활동 언제 해요?"
    )
    private val studentEmotions = listOf("curious", "confused", "happy", "anxious", "excited", "bored", "motivated")
    private val teacherResponses = listOf(
        "좋은 질문이야, 같이 생각해보자", "잘 했어! 계속해봐", "다들 여기 봐요",
        "오늘 수업 목표는 이거야", "틀려도 괜찮아 용기가 중요해", "이 부분이 핵심이야",
        "모둠별로 토론해봐", "훌륭해! 박수!", "숙제는 교과서 읽어오는 거야"
    )
    private val specialCareTexts = listOf(
        "선생님 저 이거 어떻게 해요", "이거 너무 어려워요", "저 오늘 기분이 안 좋아요",
        "선생님 저랑 같이 해줘요", "이거 언제 할 수 있게 돼요?"
    )
    private val specialTeacherResponses = listOf(
        "괜찮아, 천천히 같이 해보자", "잘 하고 있어 포기하지 마", "오늘 이만큼만 해도 충분해",
        "네 속도에 맞춰가면 돼", "선생님이 항상 여기 있어"
    )
    private val specialAchievements = listOf(
        "처음으로 혼자 읽기 성공!", "수업 끝까지 앉아있기 성공", "친구에게 인사하기 성공",
        "집중력 10분 달성", "자기표현 처음으로 함"
    )
    private val domesticDestinations = listOf(
        Triple("부산", 35.1796, 129.0756), Triple("제주도", 33.4996, 126.5312),
        Triple("경주", 35.8562, 129.2247), Triple("강릉", 37.7519, 128.8761),
        Triple("전주", 35.8242, 127.1480), Triple("여수", 34.7604, 127.6622)
    )
    private val travelSnippetTexts = listOf(
        "여기 진짜 좋다 ", "사진 찍어줘 ", "이 음식 뭔지 알아? ",
        "여기 다음에도 오고 싶다 ", "이 장소 기억해줘 "
    )
    private val travelArrivalTexts = listOf(
        "진짜 왔네!", "드디어!", "생각보다 더 좋다", "설레서 심장 터질 것 같아", "사진부터 찍자!"
    )
    private val travelActivityTexts = listOf(
        "이 식당 어때? ", "이 박물관 볼게 좀 있어? ", "길 알려줘 ",
        "현지어로 고마워요가 뭐야? ", "여기 유명한 게 뭐야? "
    )
    private val travelCrises = listOf(
        "지갑 잃어버렸어!!", "비행기 놓쳤어!", "갑자기 배탈났어", "호텔 예약이 없다고 해!",
        "언어 때문에 길을 잃었어"
    )
    private val travelReturnTexts = listOf(
        "역시 집이 제일 좋아", "좋은 추억 가득!", "다음엔 더 오래 있을게", "이런 여행 또 하고 싶어"
    )
    private val friendNames = listOf(
        "지수", "민준", "서연", "태양", "하은", "준서", "나연", "동현",
        "유진", "성민", "채원", "재원"
    )
    private val socialTexts = listOf(
        "오랜만이야! 잘 지냈어?", "같이 밥 먹자", "너 요즘 어때?", "고마워 정말로",
        "이거 너한테 꼭 말하고 싶었어", "다음에 같이 달리기 하자"
    )
    private val conflictTexts = listOf(
        "왜 그렇게 했어?", "그건 아닌 것 같아", "실망했어", "이해가 안 돼",
        "그때 그 말이 상처됐어"
    )
    private val newPersonTexts = listOf(
        "처음 보는 얼굴인데 저장해줘", "이 사람 누구인지 기억해줘", "오늘 새로운 사람 만났어"
    )
    private val reconciliationTexts = listOf(
        "내가 잘못했어 미안해", "그때는 내가 예민했어", "이제 괜찮아 잘 해결됐어",
        "화해하고 나니 시원해"
    )
    private val socialGatheringTexts = listOf(
        "다들 모였다!", "오늘 파티 시작!", "오랜만에 다 같이!", "건배!"
    )
    private val onlineTexts = listOf(
        "영상통화 중이야", "메시지 답장 보내줘", "줌 미팅 5분 후야"
    )
    private val healthCrises: List<Pair<String, String>> = listOf(
        "발목 삠" to "발목 삐었어 응급처치 알려줘",
        "무릎 통증" to "무릎이 너무 아파 병원 가야겠다",
        "감기" to "오늘 컨디션 최악이야 쉬어야겠어",
        "과호흡" to "너무 힘들어 잠깐 멈춰야겠어",
        "장염" to "배가 너무 아파 오늘 러닝 못하겠어",
        "두통" to "머리가 너무 아파 집에 있을게",
        "피로 누적" to "번아웃인 것 같아 좀 쉬어야겠어"
    )
    private val todoTitles = listOf(
        "마라톤 훈련 계획 수립", "수업 지도안 작성", "학생 개별 평가 완료", "독서 30분",
        "체력 측정", "주간 회고 작성", "여행 준비물 체크", "식단 기록", "감사 일기 쓰기",
        "동료 피드백 제공", "부모 상담 준비", "논문 읽기", "언어 공부 30분"
    )
    private val todoCategories = listOf("HEALTH", "EDUCATION", "RUNNING", "SOCIAL", "LEARNING", "PLANNING")
    private val yearlyGoals = listOf(
        "goal_y1_5K_running", "goal_y2_10K_running", "goal_y3_halfmarathon",
        "goal_y4_fullmarathon_attempt", "goal_y5_fullmarathon_complete",
        "goal_y6_recovery_health", "goal_y7_comeback_stronger",
        "goal_y8_personal_best", "goal_y9_teach_mastery", "goal_y10_decade_review"
    )
    private val planningTexts = listOf(
        "다음 달 계획 세우자", "이번 주 할일 정리해줘", "목표 점검 해줘",
        "이번 달 어떻게 됐나 정리해줘", "내년 계획 잡아보자"
    )
    private val lifeLogTexts = listOf(
        "오늘 이 순간 기억하고 싶어", "이거 기록해줘", "오늘 느낀 점 저장",
        "이 감정 잊지 않게 메모", "오늘 최고의 순간이었어",
        "힘들었지만 배운 게 많았어", "이 만남이 소중해"
    )
    private val lifeEmotions = listOf("joy", "gratitude", "nostalgic", "hopeful", "proud", "peaceful", "excited")
    private val nightReflectionTexts = listOf(
        "오늘 하루도 잘 버텼어", "오늘 정말 좋은 날이었어", "내일은 더 잘할 수 있어",
        "감사한 하루였어", "힘들었지만 괜찮아", "오늘 배운 거 정리해줘",
        "내일 할 일 목록 만들어줘"
    )
    private val nightEmotions = listOf("tired_but_satisfied", "peaceful", "reflective", "grateful", "anticipating")
    private val monthSummaries = listOf(
        "새해 시작 에너지!", "밸런타인 추억", "봄 시작 러닝 시즌",
        "벚꽃 달리기", "가정의 달 활동", "더워지는 훈련",
        "무더위 극복", "휴가 + 전지훈련", "새 학기 + 가을 시즌",
        "단풍 마라톤", "겨울 준비", "연말 회고"
    )
    private val yearlyNarratives = listOf(
        "러닝 입문, 교사 1년차 — 두 날개를 얻다",
        "10K 정복, 학생들과 유대감 형성 — 성장의 해",
        "하프마라톤 완주, 첫 해외여행 — 경계를 넘다",
        "풀코스 도전, 특수교육 첫걸음 — 용기의 해",
        "풀마라톤 Sub-4:30 완주!, 파리 여행 — 꿈이 현실이 되다",
        "부상과 회복, 재발견 — 느리게 가는 것도 전진이다",
        "컴백, 뉴욕 여행, 석사 진학 — 더 넓어지다",
        "개인기록 경신, 특수학생 졸업 감동 — 보람의 해",
        "인생 건강 최고점, 일본 장기 여행 — 원숙함",
        "10년 마라톤 Sub-4:00 달성!, 다음 10년 설계 — 새로운 챕터"
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  LifeContext — 날짜/위치/시드 컨텍스트
// ─────────────────────────────────────────────────────────────────────────────

private data class LifeContext(val year: Int, val month: Int, val day: Int) {
    val seed: Long = year * 100000L + month * 1000L + day
    val dayOfWeek: Int = ((year * 365 + month * 30 + day) % 7)
    val timestamp: Long = System.currentTimeMillis() - (10 - year) * 365L * 86400000L + month * 30L * 86400000L + day * 86400000L
    val lat: Double = 37.5665 + (day % 10) * 0.001
    val lon: Double = 126.978 + (month % 5) * 0.001

    // 러닝 확률: 연도별 증가
    val runningProbability: Float = when (year) {
        1    -> 0.3f
        2, 3 -> 0.4f
        4, 5 -> 0.5f
        6    -> 0.2f   // 부상 회복기
        7    -> 0.4f
        else -> 0.55f
    }
}

private fun <T> List<T>.random(rng: Random): T = this[rng.nextInt(this.size)]
