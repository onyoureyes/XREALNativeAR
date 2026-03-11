package com.xreal.nativear.storyteller

import android.util.Log
import com.xreal.nativear.ai.AICallGateway
import com.xreal.nativear.ai.AIMessage
import com.xreal.nativear.ai.IAICallService
import com.xreal.nativear.ai.ProactiveScheduler
import com.xreal.nativear.context.IContextSnapshot
import com.xreal.nativear.context.ContextSnapshot
import com.xreal.nativear.context.LifeSituation
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.goal.IGoalService
import com.xreal.nativear.learning.IOutcomeRecorder
import com.xreal.nativear.memory.api.IMemoryStore
import com.xreal.nativear.MemorySearcher
import com.xreal.nativear.plan.IPlanService
import com.xreal.nativear.policy.PolicyReader
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * StorytellerOrchestrator — 하루를 하나의 이야기로 엮는 내러티브 엔진.
 *
 * ## 통합 역할 (방안 B)
 * - **내러티브**: SituationChanged → Chapter + beat 생성
 * - **브리핑**: 아침(MORNING_ROUTINE) / 저녁(EVENING_WIND_DOWN) 자동 브리핑 (BriefingService 흡수)
 * - **세션 API**: startSession/endSession/currentSessionId (LifeSessionManager 대체)
 *   → AIAgentManager, PersonaManager, MemorySaveHelper가 계속 사용
 *
 * ## 정책 키
 * - storyteller.enabled (기본 true)
 * - storyteller.reflection_interval_ms (기본 300000 = 5분)
 * - storyteller.max_beats_per_chapter (기본 20)
 * - storyteller.max_tokens_per_beat (기본 300)
 * - storyteller.end_of_day_hour (기본 22 = 밤 10시)
 * - storyteller.min_chapter_duration_ms (기본 120000 = 2분)
 * - expert.min_briefing_interval_ms (기본 14400000 = 4시간, 기존 정책 유지)
 */
class StorytellerOrchestrator(
    private val eventBus: GlobalEventBus,
    private val contextAggregator: IContextSnapshot,
    private val aiRegistry: IAICallService,
    private val memoryStore: IMemoryStore,
    private val memorySearcher: MemorySearcher,
    private val proactiveScheduler: ProactiveScheduler,
    private val planManager: IPlanService,
    private val goalTracker: IGoalService,
    private val outcomeTracker: IOutcomeRecorder,
    val phaseController: StoryPhaseController
) {
    companion object {
        private const val TAG = "StorytellerOrchestrator"
        private const val TASK_ID = "storyteller_reflection"

        // PolicyReader shadow reads
        private val ENABLED: Boolean get() =
            PolicyReader.getBoolean("storyteller.enabled", true)
        private val REFLECTION_INTERVAL_MS: Long get() =
            PolicyReader.getLong("storyteller.reflection_interval_ms", 300_000L)
        private val MAX_BEATS_PER_CHAPTER: Int get() =
            PolicyReader.getInt("storyteller.max_beats_per_chapter", 20)
        private val MAX_TOKENS_PER_BEAT: Int get() =
            PolicyReader.getInt("storyteller.max_tokens_per_beat", 300)
        private val END_OF_DAY_HOUR: Int get() =
            PolicyReader.getInt("storyteller.end_of_day_hour", 22)
        private val MIN_CHAPTER_DURATION_MS: Long get() =
            PolicyReader.getLong("storyteller.min_chapter_duration_ms", 120_000L)
        private val MIN_BRIEFING_INTERVAL_MS: Long get() =
            PolicyReader.getLong("expert.min_briefing_interval_ms", 14_400_000L)

        // Multi-Agent Debate 트리거 도메인 (이 키워드가 missionType에 포함되면 토론 실행)
        private val DEBATE_TRIGGER_DOMAINS = setOf("crisis", "behavior", "health_alert")
    }

    // ── 상태 ──

    private var dayStory: DayStory = createNewDayStory()
    private var previousEmotion: String? = null
    private var eventJob: Job? = null
    private var endOfDayScheduled = false
    @Volatile private var isStarted = false

    // 브리핑 쿨다운 (BriefingService 흡수)
    private var lastMorningBriefing: Long = 0
    private var lastEveningReview: Long = 0

    // Phase B: 능동적 질문 엔진
    private val questionEngine = ProactiveQuestionEngine()
    private var previousSituation: LifeSituation? = null

    // 세션 API (LifeSessionManager 대체)
    @Volatile
    var currentChapterSituation: String? = null
        private set

    /** 현재 챕터 ID — LifeSessionManager.currentSessionId 대체 */
    val currentSessionId: String?
        get() = dayStory.currentChapter?.id

    /** 현재 챕터의 진행 시간 (분) */
    val currentSessionMinutes: Long
        get() = dayStory.currentChapter?.let {
            (System.currentTimeMillis() - it.startedAt) / 60_000L
        } ?: 0L

    // ── 생명주기 ──

    fun start() {
        if (isStarted) return
        if (!ENABLED) {
            Log.i(TAG, "Storyteller 비활성 (storyteller.enabled=false)")
            return
        }
        isStarted = true

        // ★ 상태 머신 리스너 등록
        phaseController.setPhaseListener { old, new ->
            Log.i(TAG, "StoryPhase: $old → $new")
        }

        // EventBus 구독 (SituationChanged + 감정 이벤트 + 음성 명령)
        eventJob = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            eventBus.events.collect { event ->
                try {
                    when (event) {
                        is XRealEvent.SystemEvent.SituationChanged -> {
                            // ★ 상태 머신: NARRATING에서만 챕터 전환
                            if (phaseController.currentPhase.allowsAICalls) {
                                onSituationChanged(event.newSituation)
                            }
                        }
                        is XRealEvent.PerceptionEvent.FacesDetected -> {
                            if (phaseController.currentPhase == StoryPhase.NARRATING) {
                                checkEmotionalShift()
                            }
                        }
                        is XRealEvent.InputEvent.VoiceCommand -> {
                            // ★ 사용자 음성 → 강제 NARRATING 전이
                            phaseController.onUserCommand()
                        }
                        is XRealEvent.InputEvent.EnrichedVoiceCommand -> {
                            // Phase B: 사용자 음성 응답 → 대화 맥락에 기록
                            if (phaseController.currentPhase.allowsAICalls) {
                                onUserSpeech(event.text, event.emotion)
                            }
                        }
                        is XRealEvent.SystemEvent.MissionStateChanged -> {
                            // Phase B: 전문가 미션 결과 → 내러티브 재통합
                            if (phaseController.currentPhase.allowsAICalls) {
                                onMissionResult(event)
                            }
                        }
                        else -> { /* 무시 */ }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "이벤트 처리 오류 (루프 유지됨): ${event::class.simpleName} — ${e.message}", e)
                }
            }
        }

        // ProactiveScheduler에 리플렉션 태스크 등록
        proactiveScheduler.register(
            ProactiveScheduler.ProactiveTask(
                id = TASK_ID,
                intervalMs = REFLECTION_INTERVAL_MS,
                priority = ProactiveScheduler.TaskPriority.BACKGROUND,
                isUserFacing = false,
                estimatedTokens = MAX_TOKENS_PER_BEAT,
                action = { runReflectionBeat() }
            )
        )

        Log.i(TAG, "Storyteller 시작 (리플렉션 간격: ${REFLECTION_INTERVAL_MS / 1000}초, 브리핑 통합)")
    }

    fun stop() {
        isStarted = false
        eventJob?.cancel()
        eventJob = null
        proactiveScheduler.unregister(TASK_ID)
        Log.i(TAG, "Storyteller 정지")
    }

    // ══════════════════════════════════════════════════════
    // 세션 API (LifeSessionManager 호환)
    // AIAgentManager, PersonaManager, MemorySaveHelper에서 사용
    // ══════════════════════════════════════════════════════

    /**
     * 음성 명령 "세션 시작 [상황]" → 새 챕터 시작.
     * LifeSessionManager.startSession() 대체.
     */
    fun startSession(situation: String? = null) {
        CoroutineScope(Dispatchers.Default).launch {
            val chapterTitle = situation ?: "수동 세션"
            onSituationChanged(chapterTitle)
            eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(
                text = if (situation != null) "새 챕터 시작 — $situation" else "새 챕터를 시작했습니다"
            ))
        }
    }

    /**
     * 음성 명령 "세션 종료" → 현재 챕터 닫기.
     * LifeSessionManager.endSession() 대체.
     */
    fun endSession(generateSummary: Boolean = false) {
        val chapter = dayStory.currentChapter ?: return
        chapter.endedAt = System.currentTimeMillis()
        Log.i(TAG, "챕터 수동 종료: ${chapter.title} (${chapter.duration / 60_000}분)")
    }

    /**
     * 활동 업데이트 — MemorySaveHelper에서 비활성 타이머 리셋 용.
     * LifeSessionManager.updateLastActivity() 대체.
     * Storyteller는 ProactiveScheduler 기반이므로 별도 타이머 불필요.
     */
    fun updateLastActivity() {
        // 챕터가 없으면 기본 챕터 자동 생성
        if (dayStory.currentChapter == null && isStarted) {
            val snapshot = contextAggregator.buildSnapshot()
            val situation = snapshot.currentUserState.name
            dayStory.chapters.add(Chapter(
                id = "ch_${dayStory.chapters.size + 1}",
                title = situation,
                situation = situation
            ))
            currentChapterSituation = situation
        }
    }

    /**
     * 세션 요약 프롬프트 — AIAgentManager "세션 요약" 음성 명령용.
     * LifeSessionManager.getSessionSummaryPrompt() 대체.
     */
    fun getSessionSummaryPrompt(): String? {
        val chapter = dayStory.currentChapter ?: return null
        val elapsed = (System.currentTimeMillis() - chapter.startedAt) / 60_000L
        val beatsText = chapter.beats.takeLast(5).joinToString("\n") { "- ${it.narrative}" }
        return "현재 챕터 '${chapter.title}'은 ${elapsed}분 동안 진행되었습니다.\n" +
            "최근 기록:\n$beatsText\n이 챕터를 요약해주세요."
    }

    // ══════════════════════════════════════════════════════
    // 이벤트 핸들러
    // ══════════════════════════════════════════════════════

    /**
     * 상황 전환 → 이전 챕터 닫고 새 챕터 시작.
     * 아침/저녁 상황이면 브리핑도 함께 처리.
     */
    private suspend fun onSituationChanged(situation: LifeSituation) {
        val prevSit = previousSituation
        previousSituation = situation
        onSituationChanged(situation.name)

        // Phase B: 상황 전환 시 능동적 질문
        val snapshot = contextAggregator.buildSnapshot()
        val question = questionEngine.tryGenerateQuestion(
            snapshot = snapshot,
            currentSituation = situation,
            previousSituation = prevSit,
            recentBeats = dayStory.currentChapter?.beats?.takeLast(3) ?: emptyList(),
            conversationHistory = dayStory.currentChapter?.conversationHistory ?: emptyList()
        )
        if (question != null) {
            dayStory.currentChapter?.conversationHistory?.add(ConversationTurn(
                speaker = Speaker.SYSTEM,
                text = question.text,
                topic = question.topic,
                questionType = question.type
            ))
            eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(text = question.text))
            Log.d(TAG, "상황 전환 질문: ${question.text}")
        }

        // 브리핑 트리거 (BriefingService 흡수)
        val now = System.currentTimeMillis()
        when (situation) {
            LifeSituation.MORNING_ROUTINE -> {
                if (now - lastMorningBriefing > MIN_BRIEFING_INTERVAL_MS) {
                    lastMorningBriefing = now
                    deliverMorningBriefing()
                }
            }
            LifeSituation.EVENING_WIND_DOWN, LifeSituation.SLEEPING_PREP -> {
                if (now - lastEveningReview > MIN_BRIEFING_INTERVAL_MS) {
                    lastEveningReview = now
                    deliverEveningReview()
                }
            }
            else -> { /* 브리핑 없음 */ }
        }
    }

    /**
     * 상황 전환 (문자열) → 챕터 전환 + 전환 beat.
     */
    private suspend fun onSituationChanged(situation: String) {
        if (!ENABLED) return

        val now = System.currentTimeMillis()
        val current = dayStory.currentChapter

        // 너무 짧은 챕터 방지
        if (current != null && (now - current.startedAt) < MIN_CHAPTER_DURATION_MS) {
            Log.d(TAG, "챕터 전환 무시 (${(now - current.startedAt) / 1000}초 < 최소 ${MIN_CHAPTER_DURATION_MS / 1000}초)")
            return
        }

        // 이전 챕터 닫기
        current?.endedAt = now

        // 새 챕터 시작
        val chapterId = "ch_${dayStory.chapters.size + 1}"
        val newChapter = Chapter(
            id = chapterId,
            title = situation,
            situation = situation
        )
        dayStory.chapters.add(newChapter)
        currentChapterSituation = situation

        Log.i(TAG, "새 챕터: $situation (총 ${dayStory.chapters.size}개)")

        // AI로 전환 내러티브 생성
        val snapshot = contextAggregator.buildSnapshot()
        val prompt = NarrativeBuilder.buildSceneTransitionPrompt(snapshot, current, situation)
        generateBeat(BeatType.SCENE_TRANSITION, prompt, snapshot)
    }

    private suspend fun checkEmotionalShift() {
        if (!ENABLED) return
        val snapshot = contextAggregator.buildSnapshot()
        val currentEmotion = snapshot.lastEmotion ?: return

        if (previousEmotion != null && previousEmotion != currentEmotion) {
            val prompt = NarrativeBuilder.buildEmotionalShiftPrompt(snapshot, previousEmotion, currentEmotion)
            generateBeat(BeatType.EMOTIONAL_SHIFT, prompt, snapshot)
        }
        previousEmotion = currentEmotion
    }

    private suspend fun runReflectionBeat() {
        if (!ENABLED) return

        // ★ 상태 머신: NARRATING에서만 리플렉션 허용
        if (!phaseController.currentPhase.allowsProactiveTasks) return

        checkDayRollover()

        val snapshot = contextAggregator.buildSnapshot()
        if (snapshot.hourOfDay >= END_OF_DAY_HOUR && !endOfDayScheduled) {
            endOfDayScheduled = true
            phaseController.enterWindingDown()
            runEndOfDaySummary()
            phaseController.enterSleeping()
            return
        }

        phaseController.enterReflecting()

        // 현재 챕터가 없으면 기본 챕터 생성
        if (dayStory.currentChapter == null) {
            val situation = snapshot.currentUserState.name
            dayStory.chapters.add(Chapter(
                id = "ch_${dayStory.chapters.size + 1}",
                title = situation,
                situation = situation
            ))
            currentChapterSituation = situation
        }

        val chapter = dayStory.currentChapter ?: return
        if (chapter.beats.size >= MAX_BEATS_PER_CHAPTER) {
            Log.d(TAG, "챕터 beat 상한 도달 (${MAX_BEATS_PER_CHAPTER})")
            return
        }

        val searchQuery = snapshot.toSummary()
        val memories = try {
            memorySearcher.search(searchQuery, limit = 3).map { it.node.content }
        } catch (e: Exception) {
            emptyList()
        }

        // PC 서버 이야기꾼 연동 — 서버가 살아있으면 서버 beat 우선 사용
        val orchestratorClient = try {
            org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.sync.OrchestratorClient>()
        } catch (_: Exception) { null }

        val serverBeat = if (orchestratorClient?.isConnected == true) {
            try {
                orchestratorClient.storytellerTick(
                    situation = snapshot.currentUserState.name,
                    heartRate = snapshot.heartRate?.toFloat(),
                    visiblePeople = snapshot.visiblePeople,
                    currentEmotion = snapshot.lastEmotion,
                    userSpeech = null
                )
            } catch (e: Exception) {
                Log.w(TAG, "서버 storyteller tick 실패, 로컬 폴백: ${e.message}")
                null
            }
        } else null

        // 서버가 narrative_beat를 반환했으면 서버 beat 사용
        val serverNarrative = serverBeat?.optJSONObject("narrative_beat")
        if (serverNarrative != null) {
            val beat = StoryBeat(
                type = BeatType.PERIODIC_REFLECTION,
                narrative = serverNarrative.optString("narrative", ""),
                contextSummary = snapshot.toSummary(),
                emotionalTone = serverNarrative.optString("tone", "calm"),
                placeName = snapshot.placeName
            )
            dayStory.currentChapter?.beats?.add(beat)
            Log.d(TAG, "[SERVER_BEAT] ${beat.narrative.take(80)} (tone: ${beat.emotionalTone})")
        } else {
            // 서버 미응답 → 로컬 AI로 폴백
            val serverInsights = orchestratorClient?.getContextSummary()
            val prompt = NarrativeBuilder.buildReflectionPrompt(snapshot, chapter, memories, serverInsights)
            generateBeat(BeatType.PERIODIC_REFLECTION, prompt, snapshot)
        }

        // Phase B: 리플렉션 후 능동적 질문 시도
        tryAskProactiveQuestion(snapshot)

        // ★ 리플렉션 완료 → NARRATING 복귀
        phaseController.exitReflecting()
    }

    // ══════════════════════════════════════════════════════
    // 하루 마무리 (Storyteller 요약 + BriefingService 저녁 리뷰 통합)
    // ══════════════════════════════════════════════════════

    private suspend fun runEndOfDaySummary() {
        // 1. 저녁 리뷰 (데이터 기반 — BriefingService에서 흡수)
        deliverEveningReview()

        // 2. 내러티브 요약 (AI 기반)
        if (dayStory.totalBeats == 0) {
            Log.d(TAG, "오늘 기록된 beat 없음 — 내러티브 요약 스킵")
            return
        }

        Log.i(TAG, "하루 마무리 요약 생성 (챕터: ${dayStory.chapters.size}, beat: ${dayStory.totalBeats})")

        val prompt = NarrativeBuilder.buildEndOfDaySummaryPrompt(dayStory)
        val messages = listOf(AIMessage(role = "user", content = prompt))

        val response = try {
            aiRegistry.quickText(
                messages = messages,
                systemPrompt = NarrativeBuilder.SYSTEM_PROMPT,
                maxTokens = 500,
                callPriority = AICallGateway.CallPriority.PROACTIVE,
                visibility = AICallGateway.VisibilityIntent.INTERNAL_ONLY,
                intent = "storyteller_end_of_day"
            )
        } catch (e: Exception) {
            Log.e(TAG, "하루 마무리 요약 AI 호출 실패: ${e.message}", e)
            return
        }

        val summaryText = response?.text ?: return
        dayStory.eveningSummary = summaryText

        try {
            memoryStore.save(
                content = "[하루 이야기 — ${dayStory.date}]\n$summaryText",
                role = "storyteller",
                metadata = "type=day_story,chapters=${dayStory.chapters.size},beats=${dayStory.totalBeats}"
            )
            Log.i(TAG, "하루 이야기 메모리 저장 완료")
        } catch (e: Exception) {
            Log.e(TAG, "하루 이야기 메모리 저장 실패: ${e.message}", e)
        }

        eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(
            text = "오늘의 이야기가 완성되었습니다."
        ))
    }

    // ══════════════════════════════════════════════════════
    // 브리핑 (BriefingService에서 흡수)
    // ══════════════════════════════════════════════════════

    private suspend fun deliverMorningBriefing() {
        Log.i(TAG, "아침 브리핑 생성...")
        val briefing = buildMorningBriefing()
        eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(text = briefing))
        eventBus.publish(XRealEvent.SystemEvent.DebugLog("아침 브리핑 전달됨"))
    }

    fun buildMorningBriefing(): String {
        val sb = StringBuilder()
        sb.appendLine("좋은 아침이에요!")

        try {
            val todaySchedule = planManager.getScheduleForDay(System.currentTimeMillis())
            if (todaySchedule.isNotEmpty()) {
                sb.appendLine("오늘 일정 ${todaySchedule.size}개가 있어요.")
                todaySchedule.take(3).forEach { block ->
                    val hour = SimpleDateFormat("HH:mm", Locale.KOREA)
                        .format(java.util.Date(block.startTime))
                    sb.appendLine("  $hour ${block.title}")
                }
                if (todaySchedule.size > 3) {
                    sb.appendLine("  외 ${todaySchedule.size - 3}개 일정")
                }
            } else {
                sb.appendLine("오늘 등록된 일정은 없어요.")
            }
        } catch (_: Exception) { }

        try {
            val pendingTodos = planManager.getPendingTodos()
            if (pendingTodos.isNotEmpty()) {
                sb.appendLine("할일 ${pendingTodos.size}개가 남아있어요.")
                pendingTodos.take(3).forEach { todo ->
                    sb.appendLine("  • ${todo.title}")
                }
            }
        } catch (_: Exception) { }

        try {
            val goalSummary = goalTracker.getTodaySummary()
            if (goalSummary.isNotBlank()) sb.appendLine(goalSummary)
        } catch (_: Exception) { }

        try {
            val snapshot = contextAggregator.buildSnapshot()
            snapshot.heartRate?.let { sb.appendLine("현재 심박수 ${it}bpm") }
            snapshot.hrv?.let {
                val status = when { it > 50f -> "양호"; it > 30f -> "보통"; else -> "낮음" }
                sb.appendLine("HRV: ${it.toInt()}ms ($status)")
            }
        } catch (_: Exception) { }

        sb.appendLine("오늘도 좋은 하루 보내세요!")
        return sb.toString()
    }

    private suspend fun deliverEveningReview() {
        Log.i(TAG, "저녁 리뷰 생성...")
        val review = buildEveningReview()
        eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(text = review))
        eventBus.publish(XRealEvent.SystemEvent.DebugLog("저녁 리뷰 전달됨"))
    }

    fun buildEveningReview(): String {
        val sb = StringBuilder()
        sb.appendLine("오늘 하루 수고하셨어요!")

        try {
            sb.appendLine(planManager.buildDailySummary())
        } catch (_: Exception) { }

        try {
            val stats = outcomeTracker.getOverallStats()
            if (stats.totalInterventions > 0) {
                val rate = (stats.acceptanceRate * 100).toInt()
                sb.appendLine("AI 어시스턴트 채택률: ${rate}% (${stats.followed}/${stats.totalInterventions})")
            }
        } catch (_: Exception) { }

        try {
            val goalSummary = goalTracker.getTodaySummary()
            if (goalSummary.isNotBlank()) sb.appendLine(goalSummary)
        } catch (_: Exception) { }

        try {
            val snapshot = contextAggregator.buildSnapshot()
            if (snapshot.heartRate != null) {
                sb.appendLine("건강 상태:")
                snapshot.heartRate?.let { sb.appendLine("  심박: ${it}bpm") }
                snapshot.hrv?.let { sb.appendLine("  HRV: ${it.toInt()}ms") }
            }
        } catch (_: Exception) { }

        // 챕터 요약 추가 (Storyteller 고유)
        if (dayStory.chapters.isNotEmpty()) {
            sb.appendLine("오늘 ${dayStory.chapters.size}개의 장면을 지나왔어요.")
        }

        sb.appendLine("편안한 밤 되세요. 내일도 응원할게요!")
        return sb.toString()
    }

    /** 수동 트리거 — BriefingService.triggerMorningBriefing() 대체 */
    fun triggerMorningBriefing() {
        CoroutineScope(Dispatchers.Default).launch { deliverMorningBriefing() }
    }

    /** 수동 트리거 — BriefingService.triggerEveningReview() 대체 */
    fun triggerEveningReview() {
        CoroutineScope(Dispatchers.Default).launch { deliverEveningReview() }
    }

    // ══════════════════════════════════════════════════════
    // Phase B: 능동적 질문 + 대화 맥락 + 전문가 재통합
    // ══════════════════════════════════════════════════════

    /**
     * 능동적 질문 시도 — 리플렉션 beat 이후에 호출.
     * 타이밍/상황 조건 충족 시 TTS로 질문 전달.
     */
    private fun tryAskProactiveQuestion(snapshot: ContextSnapshot) {
        val chapter = dayStory.currentChapter ?: return
        val currentSit = try {
            LifeSituation.valueOf(chapter.situation)
        } catch (_: Exception) { return }

        val question = questionEngine.tryGenerateQuestion(
            snapshot = snapshot,
            currentSituation = currentSit,
            previousSituation = previousSituation,
            recentBeats = chapter.beats.takeLast(5),
            conversationHistory = chapter.conversationHistory
        ) ?: return

        // 대화 이력에 시스템 질문 기록
        chapter.conversationHistory.add(ConversationTurn(
            speaker = Speaker.SYSTEM,
            text = question.text,
            topic = question.topic,
            questionType = question.type
        ))

        // TTS로 질문 전달
        eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(text = question.text))
        Log.d(TAG, "능동적 질문: [${question.type}] ${question.text}")
    }

    /**
     * 사용자 음성 응답 처리.
     * 대화 맥락에 기록 + 대화 beat 생성.
     */
    private suspend fun onUserSpeech(text: String, emotion: String?) {
        val chapter = dayStory.currentChapter ?: return

        // 대화 이력에 사용자 응답 기록
        chapter.conversationHistory.add(ConversationTurn(
            speaker = Speaker.USER,
            text = text,
            topic = chapter.conversationHistory.lastOrNull()?.topic
        ))

        // 최근 시스템 질문이 있었으면 대화 beat 생성
        val recentQuestion = chapter.conversationHistory
            .lastOrNull { it.speaker == Speaker.SYSTEM && it.questionType != null }
        if (recentQuestion != null &&
            System.currentTimeMillis() - recentQuestion.timestamp < 120_000L) {

            val snapshot = contextAggregator.buildSnapshot()
            val questionResult = QuestionResult(
                text = recentQuestion.text,
                type = recentQuestion.questionType!!,
                topic = recentQuestion.topic ?: ""
            )
            val prompt = NarrativeBuilder.buildConversationBeatPrompt(
                snapshot, questionResult, text, chapter.conversationHistory
            )
            generateBeat(BeatType.CONVERSATION_MOMENT, prompt, snapshot)
        }

        // 대화 이력 상한 (최대 30턴)
        while (chapter.conversationHistory.size > 30) {
            chapter.conversationHistory.removeFirst()
        }
    }

    /**
     * 전문가 미션 결과 → 내러티브 재통합.
     * MissionStateChanged에서 COMPLETED 상태일 때 호출.
     */
    private suspend fun onMissionResult(event: XRealEvent.SystemEvent.MissionStateChanged) {
        if (event.newState != "COMPLETED" && event.newState != "EXECUTING") return

        val chapter = dayStory.currentChapter ?: return

        // 전문가 결과를 ExpertInsight로 기록
        val insight = ExpertInsight(
            domainId = event.missionType,
            expertName = event.missionType.replace("_", " "),
            insight = "미션 ${event.missionId} 상태: ${event.oldState} → ${event.newState}"
        )
        chapter.expertInsights.add(insight)

        // COMPLETED일 때만 내러티브 beat 생성
        if (event.newState == "COMPLETED") {
            val snapshot = contextAggregator.buildSnapshot()

            // 위기/행동 도메인이면 Multi-Agent Debate로 심층 분석 시도
            val debateDomains = DEBATE_TRIGGER_DOMAINS
            val missionDomain = event.missionType.lowercase()
            if (debateDomains.any { missionDomain.contains(it) }) {
                tryRunDebate(event, snapshot, insight, chapter)
            }

            val prompt = NarrativeBuilder.buildExpertInsightPrompt(snapshot, insight, chapter)
            generateBeat(BeatType.VISUAL_HIGHLIGHT, prompt, snapshot)

            // 전문가 결과를 대화에서 공유할 수 있도록 대화 이력에도 기록
            chapter.conversationHistory.add(ConversationTurn(
                speaker = Speaker.EXPERT,
                text = insight.insight.take(100),
                topic = "전문가_${insight.domainId}"
            ))

            Log.d(TAG, "전문가 결과 재통합: ${insight.domainId} — ${insight.insight.take(80)}")
        }
    }

    /**
     * 위기/행동 관련 미션 완료 시 Multi-Agent Debate 트리거.
     * 서버에서 2~3명 전문가가 토론 → 합의된 행동 계획 도출.
     * 비동기 — 토론 결과는 나중에 내러티브에 반영.
     */
    private fun tryRunDebate(
        event: XRealEvent.SystemEvent.MissionStateChanged,
        snapshot: ContextSnapshot,
        insight: ExpertInsight,
        chapter: Chapter
    ) {
        val orchestratorClient = try {
            org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.sync.OrchestratorClient>()
        } catch (_: Exception) { return }

        if (orchestratorClient?.isConnected != true) return

        // 토론은 오래 걸리므로 (30~120초) 별도 코루틴에서 비동기 실행
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val topic = "${insight.domainId} 관련: ${insight.insight.take(200)}"
                val domains = selectDebateDomains(event.missionType)
                val context = mapOf(
                    "situation" to snapshot.currentUserState.name,
                    "mission_id" to event.missionId,
                    "mission_type" to event.missionType
                )

                Log.i(TAG, "Multi-Agent Debate 시작: $topic (전문가: $domains)")
                val result = orchestratorClient.runDebate(topic, domains, context)

                if (result != null) {
                    val consensusLevel = result.optString("consensus_level", "unknown")
                    val summary = result.optString("summary", "")
                    val latency = result.optInt("latency_ms", 0)

                    // 토론 결과를 ExpertInsight + 대화 이력에 기록
                    val debateInsight = ExpertInsight(
                        domainId = "debate_${insight.domainId}",
                        expertName = "전문가 토론 ($consensusLevel)",
                        insight = summary.take(500)
                    )
                    chapter.expertInsights.add(debateInsight)
                    chapter.conversationHistory.add(ConversationTurn(
                        speaker = Speaker.EXPERT,
                        text = "[토론 합의] $summary".take(200),
                        topic = "토론_${insight.domainId}"
                    ))

                    Log.i(TAG, "Debate 완료: $consensusLevel, ${latency}ms, 요약: ${summary.take(80)}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Debate 실행 실패: ${e.message}")
            }
        }
    }

    /**
     * 미션 타입에 따라 토론 참여 전문가 선택.
     * 항상 관련 도메인 + 보완 도메인 2~3명.
     */
    private fun selectDebateDomains(missionType: String): List<String> {
        val domain = missionType.lowercase()
        return when {
            domain.contains("crisis") -> listOf("crisis", "behavior", "health")
            domain.contains("behavior") -> listOf("behavior", "health", "social")
            domain.contains("health") -> listOf("health", "behavior", "planning")
            domain.contains("social") -> listOf("social", "behavior", "planning")
            else -> listOf("behavior", "health")  // 기본 2명
        }
    }

    // ── AI beat 생성 공통 ──

    private suspend fun generateBeat(type: BeatType, prompt: String, snapshot: ContextSnapshot) {
        val messages = listOf(AIMessage(role = "user", content = prompt))

        val response = try {
            aiRegistry.quickText(
                messages = messages,
                systemPrompt = NarrativeBuilder.SYSTEM_PROMPT,
                maxTokens = MAX_TOKENS_PER_BEAT,
                callPriority = AICallGateway.CallPriority.PROACTIVE,
                visibility = AICallGateway.VisibilityIntent.INTERNAL_ONLY,
                intent = "storyteller_${type.name.lowercase()}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Beat 생성 AI 호출 실패: ${e.message}", e)
            return
        }

        val responseText = response?.text ?: return
        val (narrative, tone) = NarrativeBuilder.parseResponse(responseText)

        val beat = StoryBeat(
            type = type,
            narrative = narrative,
            contextSummary = snapshot.toSummary(),
            emotionalTone = tone,
            placeName = snapshot.placeName
        )

        dayStory.currentChapter?.beats?.add(beat)
        Log.d(TAG, "[${type.name}] $narrative (tone: $tone)")
    }

    // ── 유틸 ──

    private fun checkDayRollover() {
        val today = todayString()
        if (dayStory.date != today) {
            Log.i(TAG, "날짜 변경: ${dayStory.date} → $today")
            dayStory = createNewDayStory()
            endOfDayScheduled = false
            currentChapterSituation = null
        }
    }

    private fun createNewDayStory(): DayStory = DayStory(date = todayString())

    private fun todayString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(System.currentTimeMillis())

    fun getStatus(): String {
        val chapter = dayStory.currentChapter
        return "Storyteller: ${if (isStarted) "ON" else "OFF"}, " +
                "phase=${phaseController.currentPhase}, " +
                "date=${dayStory.date}, chapters=${dayStory.chapters.size}, " +
                "beats=${dayStory.totalBeats}, " +
                "current=${chapter?.title ?: "none"} (${chapter?.beats?.size ?: 0} beats)"
    }
}
