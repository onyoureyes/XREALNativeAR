package com.xreal.nativear.context

import android.util.Log
import com.xreal.nativear.ai.AIMessage
import com.xreal.nativear.cadence.UserState
import com.xreal.nativear.core.ErrorReporter
import com.xreal.nativear.core.ErrorSeverity
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.policy.PolicyReader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SituationRecognizer: Classifies ContextSnapshot into LifeSituation.
 *
 * Uses a hybrid approach:
 * - Rule-based fast classification (~1ms) every 10 seconds
 * - Gemini deep classification every 5 minutes (for ambiguous cases)
 *
 * Publishes SituationChanged events to GlobalEventBus on transitions.
 */
class SituationRecognizer(
    private val contextAggregator: ContextAggregator,
    private val eventBus: GlobalEventBus,
    private val scope: CoroutineScope,
    private val userProfileManager: com.xreal.nativear.meeting.UserProfileManager? = null,
    private val aiRegistry: com.xreal.nativear.ai.IAICallService? = null,
    private val storyPhaseController: com.xreal.nativear.storyteller.IStoryPhaseGate? = null
) {
    companion object {
        private const val TAG = "SituationRecognizer"
        private const val CLASSIFY_INTERVAL_MS = 10_000L        // 10 seconds
        private const val DEEP_CLASSIFY_INTERVAL_MS = 300_000L  // 5 minutes
        private const val STABILITY_THRESHOLD = 3               // Need 3 consecutive same results
    }

    private val _currentSituation = MutableStateFlow(LifeSituation.UNKNOWN)
    val currentSituation: StateFlow<LifeSituation> = _currentSituation.asStateFlow()

    private var classifyJob: Job? = null
    private var deepClassifyJob: Job? = null
    private var consecutiveCount = 0
    private var candidateSituation = LifeSituation.UNKNOWN
    private var lastConfidence = 0f

    // Bayesian classifier (온라인 학습 + HMM 전이)
    val bayesianClassifier = BayesianClassifier()
    private val BAYESIAN_WEIGHT: Float get() =
        PolicyReader.getFloat("situation.bayesian_weight", 0.4f)

    fun start() {
        Log.i(TAG, "SituationRecognizer started")

        // Fast rule-based classification every 10 seconds
        classifyJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    val snapshot = contextAggregator.buildSnapshot()

                    // ★ 상태 머신: 센서 활동 감지 시 DORMANT → AWAKENING 전이
                    storyPhaseController?.let { ctrl ->
                        val hasActivity = snapshot.isMoving ||
                            snapshot.stepsLast5Min > 0 ||
                            snapshot.recentSpeechCount > 0 ||
                            snapshot.visiblePeople.isNotEmpty()
                        if (hasActivity) ctrl.onActivityDetected()

                        // AWAKENING → OBSERVING 자동 전이
                        ctrl.checkAwakeningComplete()
                    }

                    val result = classify(snapshot)
                    handleClassificationResult(result.first, result.second, snapshot)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ErrorReporter.report(TAG, "상황 분류 실패", e, ErrorSeverity.WARNING)
                }
                delay(CLASSIFY_INTERVAL_MS)
            }
        }

        // Deep classification every 5 minutes (AI-based)
        // ★ 수정: 상황 안정 시 스킵 + 정적 상태 시 간격 연장
        deepClassifyJob = scope.launch(Dispatchers.Default) {
            delay(60_000L) // Wait 1 minute before first deep classify
            var consecutiveStableSkips = 0
            while (isActive) {
                try {
                    // ★ 상태 머신 1차 게이트: deepClassify 허용 상태인지 확인
                    val phase = storyPhaseController?.currentPhase
                    if (phase != null && !phase.allowsDeepClassify) {
                        delay(DEEP_CLASSIFY_INTERVAL_MS)
                        continue
                    }

                    val snapshot = contextAggregator.buildSnapshot()

                    // ★ 정적 상태 감지: 이동 없음 + 사람 없음 + 소리 없음 → AI 호출 불필요
                    val isStationary = !snapshot.isMoving &&
                        snapshot.stepsLast5Min == 0 &&
                        snapshot.recentSpeechCount == 0 &&
                        snapshot.visiblePeople.isEmpty() &&
                        snapshot.ambientSounds.isEmpty()

                    // ★ 상황이 3회 연속 안정 + 정적이면 deepClassify 스킵
                    if (isStationary && consecutiveCount >= STABILITY_THRESHOLD) {
                        consecutiveStableSkips++
                        if (consecutiveStableSkips % 6 == 1) { // 30분마다 로그
                            Log.d(TAG, "deepClassify 스킵 (정적 상태, 상황 안정, ${consecutiveStableSkips}회 연속)")
                        }
                    } else {
                        consecutiveStableSkips = 0
                        val result = deepClassify(snapshot)
                        handleClassificationResult(result.first, result.second, snapshot)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ErrorReporter.report(TAG, "딥 상황 분류 실패", e, ErrorSeverity.WARNING)
                }
                delay(DEEP_CLASSIFY_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        classifyJob?.cancel()
        deepClassifyJob?.cancel()
        Log.i(TAG, "SituationRecognizer stopped")
    }

    /**
     * 하이브리드 분류: 규칙 기반 + Bayesian 앙상블.
     * BAYESIAN_WEIGHT=0 이면 순수 규칙, =1 이면 순수 베이지안.
     */
    fun classify(snapshot: ContextSnapshot): Pair<LifeSituation, Float> {
        val ruleResult = classifyRuleBased(snapshot)

        // 베이지안 앙상블 (관측 데이터가 충분할 때만)
        val bw = BAYESIAN_WEIGHT
        if (bw > 0f) {
            val bayesResult = bayesianClassifier.classifyTop(snapshot, _currentSituation.value)
            // 규칙이 0.9+ 신뢰도면 규칙 우선 (명확한 경우)
            if (ruleResult.second >= 0.90f) return ruleResult
            // 앙상블: 두 결과가 같으면 신뢰도 부스트, 다르면 가중 평균
            return if (ruleResult.first == bayesResult.first) {
                ruleResult.first to minOf(1.0f, ruleResult.second * (1 - bw) + bayesResult.second * bw + 0.1f)
            } else if (bayesResult.second > ruleResult.second + 0.15f) {
                bayesResult  // 베이지안이 확연히 높으면 베이지안 우선
            } else {
                ruleResult  // 그 외에는 규칙 우선 (안정성)
            }
        }
        return ruleResult
    }

    /**
     * Rule-based fast classification (~1ms).
     * Returns (LifeSituation, confidence).
     */
    private fun classifyRuleBased(snapshot: ContextSnapshot): Pair<LifeSituation, Float> {
        // Priority-ordered rules (most specific first)

        // ── Running ──
        if (snapshot.currentUserState == UserState.RUNNING ||
            (snapshot.speed != null && snapshot.speed > 2.5f &&
             snapshot.heartRate != null && snapshot.heartRate > 130 &&
             snapshot.stepsLast5Min > 50)) {
            return LifeSituation.RUNNING to 0.95f
        }

        // ── Guitar Practice ──
        if (snapshot.ambientSounds.any { it.contains("guitar", ignoreCase = true) } &&
            !snapshot.isMoving) {
            return LifeSituation.GUITAR_PRACTICE to 0.85f
        }

        // ── Phone Call ──
        if (snapshot.recentSpeechCount > 5 &&
            snapshot.visiblePeople.isEmpty() &&
            !snapshot.isMoving) {
            return LifeSituation.PHONE_CALL to 0.70f
        }

        // ── Teaching (수업/교실) ──
        // 조건: 교사 직업 + 학교 시간 + (사람 또는 대화) + 정지 상태
        val isTeacher = userProfileManager?.occupation?.contains("교사") == true
        if (isTeacher && snapshot.hourOfDay in 8..16 &&
            (snapshot.recentSpeechCount >= 1 || snapshot.visiblePeople.isNotEmpty()) &&
            !snapshot.isMoving) {
            return LifeSituation.TEACHING to 0.85f
        }

        // ── In Meeting (회의) ──
        // 조건: 문서/일정 관련 키워드 감지 + (사람 or 대화) + 정지 상태
        val meetingKeywords = listOf(
            "회의", "안건", "일정", "교직원", "결재", "공지", "계획",
            "발표", "보고", "의견", "협의", "참석", "진행", "논의",
            "agenda", "meeting", "schedule", "minutes"
        )
        val hasMeetingText = snapshot.visibleText.any { text ->
            meetingKeywords.any { keyword -> text.contains(keyword, ignoreCase = true) }
        }
        if (hasMeetingText && !snapshot.isMoving &&
            (snapshot.visiblePeople.isNotEmpty() || snapshot.recentSpeechCount > 2)) {
            return LifeSituation.IN_MEETING to 0.85f
        }
        // 폴백: 많은 사람 + 대화 + 텍스트 존재 + 정지
        if (snapshot.visiblePeople.size >= 2 && snapshot.recentSpeechCount > 3 &&
            snapshot.visibleText.isNotEmpty() && !snapshot.isMoving) {
            return LifeSituation.IN_MEETING to 0.70f
        }

        // ── Social Gathering ──
        if (snapshot.visiblePeople.size >= 2 && snapshot.recentSpeechCount > 3) {
            return LifeSituation.SOCIAL_GATHERING to 0.80f
        }

        // ── Language Learning / Foreign Text ──
        if (snapshot.foreignTextDetected && !snapshot.familiarLocation) {
            return LifeSituation.TRAVELING_NEW_PLACE to 0.75f
        }
        if (snapshot.foreignTextDetected && snapshot.familiarLocation && !snapshot.isMoving) {
            return LifeSituation.LANGUAGE_LEARNING to 0.65f
        }

        // ── Traveling Transit ──
        if (snapshot.currentUserState == UserState.TRAVELING_TRANSIT ||
            (snapshot.speed != null && snapshot.speed > 10f)) {
            return LifeSituation.TRAVELING_TRANSIT to 0.85f
        }

        // ── Gym Workout ──
        if (snapshot.heartRate != null && snapshot.heartRate > 120 &&
            snapshot.movementIntensity > 0.6f && !snapshot.isMoving) {
            return LifeSituation.GYM_WORKOUT to 0.70f
        }

        // ── Walking Exercise ──
        if (snapshot.isMoving && snapshot.speed != null && snapshot.speed in 0.8f..2.5f &&
            snapshot.stepsLast5Min > 30) {
            return if (snapshot.familiarLocation) {
                LifeSituation.WALKING_EXERCISE to 0.65f
            } else {
                LifeSituation.TRAVELING_NEW_PLACE to 0.60f
            }
        }

        // ── Cooking ──
        if (snapshot.ambientSounds.any { it.contains("sizzle", ignoreCase = true) ||
                    it.contains("water", ignoreCase = true) } &&
            snapshot.isIndoors == true && !snapshot.isMoving) {
            return LifeSituation.COOKING to 0.55f
        }

        // ── Shopping ──
        if (snapshot.visibleObjects.any { it.contains("product", ignoreCase = true) ||
                    it.contains("price", ignoreCase = true) } &&
            !snapshot.familiarLocation) {
            return LifeSituation.SHOPPING to 0.60f
        }

        // ── Time-based fallbacks ──
        if (snapshot.isIndoors != false && snapshot.familiarLocation) {
            return when (snapshot.hourOfDay) {
                in 5..7 -> LifeSituation.MORNING_ROUTINE to 0.50f
                in 8..11 -> {
                    if (snapshot.currentUserState == UserState.FOCUSED_TASK)
                        LifeSituation.AT_DESK_WORKING to 0.55f
                    else LifeSituation.RELAXING_HOME to 0.40f
                }
                in 12..13 -> LifeSituation.LUNCH_BREAK to 0.45f
                in 14..17 -> {
                    if (snapshot.currentUserState == UserState.FOCUSED_TASK)
                        LifeSituation.AT_DESK_WORKING to 0.55f
                    else LifeSituation.RELAXING_HOME to 0.40f
                }
                in 18..20 -> LifeSituation.EVENING_WIND_DOWN to 0.45f
                in 21..23 -> LifeSituation.SLEEPING_PREP to 0.40f
                else -> LifeSituation.SLEEPING to 0.60f  // hours 0-4: 심야 → 수면 중 (API 억제)
            }
        }

        // ── 수면 중 (SLEEPING) — 야간 + 무활동 + 무음성 + 아무것도 안 보임 ──
        // 0-5시 또는 22-23시: 움직임/음성/사람 없으면 자고 있을 가능성 높음
        val isDeepNight = snapshot.hourOfDay in 0..5
        val isLateNight = snapshot.hourOfDay in 22..23
        val isInactive = !snapshot.isMoving &&
            snapshot.stepsLast5Min == 0 &&
            snapshot.recentSpeechCount == 0 &&
            snapshot.visiblePeople.isEmpty() &&
            snapshot.visibleObjects.isEmpty() &&
            snapshot.visibleText.isEmpty()
        if (isDeepNight && isInactive) {
            return LifeSituation.SLEEPING to 0.80f
        }
        if (isLateNight && isInactive) {
            return LifeSituation.SLEEPING to 0.65f
        }

        // ── Commuting (walking, familiar area, typical commute hours) ──
        if (snapshot.isMoving && snapshot.familiarLocation &&
            (snapshot.hourOfDay in 7..9 || snapshot.hourOfDay in 17..19)) {
            return LifeSituation.COMMUTING to 0.55f
        }

        // ── Fallback: Map from UserState ──
        return snapshot.currentUserState.toLifeSituation() to 0.30f
    }

    /**
     * Deep classification using AI (Gemini).
     * 5분 주기 호출. rule-based 결과를 Gemini가 검증/보정.
     * 예산 부족 또는 AI 실패 시 rule-based fallback.
     */
    suspend fun deepClassify(snapshot: ContextSnapshot): Pair<LifeSituation, Float> {
        // rule-based 기본 결과 (항상 계산 — fallback 용)
        val (ruleSituation, ruleConfidence) = classify(snapshot)

        // 예산 게이트: TokenOptimizer가 SKIP이면 AI 호출 안 함
        val budgetAllowed = try {
            val tracker = org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.router.persona.TokenBudgetTracker>()
            tracker?.checkBudget(
                com.xreal.nativear.ai.ProviderId.GEMINI,
                isEssential = false,
                estimatedTokens = 200
            )?.allowed != false
        } catch (_: Exception) { true }

        if (!budgetAllowed) {
            Log.d(TAG, "deepClassify: 예산 부족 → rule-based fallback")
            return ruleSituation to minOf(ruleConfidence + 0.05f, 1.0f)
        }

        // AI 호출 (registry 라우팅: 리모트$0 → 서버 → 엣지)
        if (aiRegistry == null) {
            return ruleSituation to minOf(ruleConfidence + 0.05f, 1.0f)
        }

        return try {
            withContext(Dispatchers.IO) {
                val prompt = buildDeepClassifyPrompt(snapshot, ruleSituation, ruleConfidence)
                val msgs = listOf(AIMessage(role = "user", content = prompt))
                val sysPrompt = "You are a situation classifier. Respond ONLY with: SITUATION_NAME|confidence(0.0-1.0). No explanation."
                val response = aiRegistry.quickText(
                    msgs, sysPrompt, maxTokens = 30,
                    callPriority = com.xreal.nativear.ai.AICallGateway.CallPriority.PROACTIVE,
                    visibility = com.xreal.nativear.ai.AICallGateway.VisibilityIntent.INTERNAL_ONLY,
                    intent = "situation_deep_classify"
                )
                val responseText = response?.text
                if (responseText.isNullOrBlank()) {
                    ruleSituation to minOf(ruleConfidence + 0.05f, 1.0f)
                } else {
                    parseDeepClassifyResponse(responseText, ruleSituation, ruleConfidence)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "deepClassify AI 실패 → rule-based fallback: ${e.message}")
            ruleSituation to minOf(ruleConfidence + 0.05f, 1.0f)
        }
    }

    private fun buildDeepClassifyPrompt(
        snapshot: ContextSnapshot,
        ruleSituation: LifeSituation,
        ruleConfidence: Float
    ): String {
        val situations = LifeSituation.entries.joinToString(",") { it.name }
        return buildString {
            appendLine("Classify the current life situation from sensor data.")
            appendLine("Valid situations: $situations")
            appendLine()
            appendLine("Context: ${snapshot.toSummary()}")
            snapshot.currentUserState.let { appendLine("UserState: $it") }
            snapshot.heartRate?.let { appendLine("HR: $it bpm") }
            snapshot.stepsLast5Min.let { if (it > 0) appendLine("Steps(5min): $it") }
            if (snapshot.visibleObjects.isNotEmpty()) appendLine("Objects: ${snapshot.visibleObjects.take(5).joinToString(",")}")
            if (snapshot.visibleText.isNotEmpty()) appendLine("Text: ${snapshot.visibleText.take(3).joinToString(",").take(100)}")
            if (snapshot.ambientSounds.isNotEmpty()) appendLine("Sounds: ${snapshot.ambientSounds.take(3).joinToString(",")}")
            if (snapshot.visiblePeople.isNotEmpty()) appendLine("People: ${snapshot.visiblePeople.size}")
            snapshot.upcomingTodoTitles.takeIf { it.isNotEmpty() }?.let { appendLine("Todos: ${it.take(3).joinToString(",")}") }
            snapshot.currentScheduleBlock?.let { appendLine("Schedule: $it") }
            appendLine()
            appendLine("Rule-based guess: ${ruleSituation.name} (${String.format("%.2f", ruleConfidence)})")
            appendLine("Reply: SITUATION_NAME|confidence")
        }
    }

    private fun parseDeepClassifyResponse(
        text: String,
        fallbackSituation: LifeSituation,
        fallbackConfidence: Float
    ): Pair<LifeSituation, Float> {
        // 예상 형식: "RUNNING|0.92" 또는 "AT_DESK_WORKING|0.85"
        val cleaned = text.trim().uppercase().replace(" ", "_")
        val parts = cleaned.split("|")
        if (parts.size != 2) {
            Log.w(TAG, "deepClassify 파싱 실패: '$text' → fallback")
            return fallbackSituation to minOf(fallbackConfidence + 0.05f, 1.0f)
        }

        val situation = try {
            LifeSituation.valueOf(parts[0].trim())
        } catch (_: Exception) {
            // 부분 매칭 시도
            LifeSituation.entries.firstOrNull { it.name in parts[0].trim() }
        }

        val confidence = parts[1].trim().toFloatOrNull()?.coerceIn(0f, 1f)

        return if (situation != null && confidence != null) {
            Log.d(TAG, "deepClassify AI: ${situation.name} ($confidence) [rule-based: ${fallbackSituation.name} ($fallbackConfidence)]")
            situation to confidence
        } else {
            Log.w(TAG, "deepClassify 파싱 실패: '$text' → fallback")
            fallbackSituation to minOf(fallbackConfidence + 0.05f, 1.0f)
        }
    }

    /**
     * Handle classification result with stability filtering.
     * Requires STABILITY_THRESHOLD consecutive same results before switching.
     */
    private fun handleClassificationResult(
        newSituation: LifeSituation,
        confidence: Float,
        snapshot: ContextSnapshot
    ) {
        if (newSituation == candidateSituation) {
            consecutiveCount++
        } else {
            candidateSituation = newSituation
            consecutiveCount = 1
        }

        lastConfidence = confidence

        // Only switch if we have enough consecutive matches AND it's different
        if (consecutiveCount >= STABILITY_THRESHOLD && newSituation != _currentSituation.value) {
            val oldSituation = _currentSituation.value
            _currentSituation.value = newSituation

            // 베이지안 모델 온라인 업데이트 (확정된 상황으로 학습)
            try {
                bayesianClassifier.update(snapshot, newSituation, oldSituation)
            } catch (e: Exception) {
                Log.w(TAG, "Bayesian update 실패: ${e.message}")
            }

            Log.i(TAG, "Situation changed: ${oldSituation.displayName} -> ${newSituation.displayName} (conf: %.2f, bayes: %s)".format(confidence, bayesianClassifier.diagnostics()))

            // Publish event
            eventBus.publish(
                XRealEvent.SystemEvent.SituationChanged(
                    oldSituation = oldSituation,
                    newSituation = newSituation,
                    confidence = confidence,
                    timestamp = snapshot.timestamp
                )
            )

            // ★ 상태 머신: 상황 인식 성공 → OBSERVING → NARRATING 전이
            storyPhaseController?.onSituationRecognized()
        }
    }

    /** Get current confidence level */
    fun getCurrentConfidence(): Float = lastConfidence
}
