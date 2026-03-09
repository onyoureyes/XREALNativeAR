package com.xreal.nativear.meeting

import android.util.Log
import com.xreal.nativear.ai.AICallGateway
import com.xreal.nativear.ai.AIMessage
import com.xreal.nativear.context.IContextSnapshot
import com.xreal.nativear.context.LifeSituation
import com.xreal.nativear.context.SituationRecognizer
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.policy.PolicyReader
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.core.GestureType
import com.xreal.nativear.companion.TokenOptimizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.xreal.nativear.core.ErrorReporter
import com.xreal.nativear.ai.IPersonaService
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * MeetingContextService: 회의 중 TILT 제스처 → 맥락적 지식 HUD 표시.
 *
 * ## 핵심 동작
 * 1. 회의 상황(IN_MEETING) 감지 시 활성화
 * 2. OCR 텍스트 + Whisper 음성 텍스트를 버퍼에 수집
 * 3. 사용자 TILT(갸우뚱) 제스처 감지
 * 4. 현재 맥락(보고 있는 문서 + 최근 대화) → Gemini에 질의
 * 5. 응답을 HUD에 ShowMessage로 표시 (TTS 없음 — 회의 중이므로)
 * 6. NOD → 유용함 기록 (OutcomeTracker), SHAKE → 해제
 *
 * ## 예산
 * - TILT당 ~1000-2000 Gemini 토큰
 * - 회의 1시간에 TILT ~3-5회 예상 → ~5000-10000 토큰
 */
class MeetingContextService(
    private val aiRegistry: com.xreal.nativear.ai.IAICallService,
    private val eventBus: GlobalEventBus,
    private val contextAggregator: IContextSnapshot,
    private val situationRecognizer: SituationRecognizer,
    private val scheduleExtractor: ScheduleExtractor,
    private val userProfileManager: UserProfileManager,
    private val tokenOptimizer: TokenOptimizer? = null,
    private val tokenBudgetTracker: com.xreal.nativear.router.persona.TokenBudgetTracker? = null,
    // ★ Phase M: 사용자 컨텍스트(DNA·기억·지시사항) 주입용
    private val personaManager: IPersonaService? = null,
    private val scope: CoroutineScope,
    private val cadenceConfig: com.xreal.nativear.cadence.CadenceConfig? = null
) {
    companion object {
        private const val TAG = "MeetingContextService"
        private val MAX_OCR_BUFFER: Int get() = PolicyReader.getInt("meeting.max_ocr_buffer", 20)        // 최근 OCR 텍스트 유지 수
        private val MAX_SPEECH_BUFFER: Int get() = PolicyReader.getInt("meeting.max_speech_buffer", 30)     // 최근 음성 텍스트 유지 수
        private const val TILT_COOLDOWN_MS = 5000L   // TILT 처리 쿨다운 (Gemini 호출 제한)
        private val OCR_EXTRACT_INTERVAL_MS: Long get() = PolicyReader.getLong("meeting.ocr_extract_interval_ms", 60_000L)  // 일정 추출 주기
    }

    // ── 회의 중 수집 버퍼 ──
    private val ocrBuffer = ConcurrentLinkedDeque<TimestampedText>()
    private val speechBuffer = ConcurrentLinkedDeque<TimestampedText>()

    // ── 상태 ──
    private var isActive = false
    private var lastTiltProcessed = 0L
    private var lastOcrExtraction = 0L
    private var pendingResponse: String? = null  // NOD/SHAKE 대기 중인 응답
    private var subscriptionJob: Job? = null

    data class TimestampedText(val text: String, val timestamp: Long)

    /**
     * 서비스 시작 — EventBus 구독.
     */
    fun start() {
        Log.i(TAG, "MeetingContextService started")
        subscriptionJob = scope.launch(Dispatchers.Default) {
            eventBus.events.collect { event ->
                try {
                    processEvent(event)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing event: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        subscriptionJob?.cancel()
        isActive = false
        Log.i(TAG, "MeetingContextService stopped")
    }

    private fun processEvent(event: XRealEvent) {
        when (event) {
            // ── 상황 변화: IN_MEETING 진입/이탈 ──
            is XRealEvent.SystemEvent.SituationChanged -> {
                if (event.newSituation == LifeSituation.IN_MEETING) {
                    activateMeetingMode()
                } else if (event.oldSituation == LifeSituation.IN_MEETING) {
                    deactivateMeetingMode()
                }
            }

            // ── OCR 텍스트 수집 ──
            is XRealEvent.PerceptionEvent.OcrDetected -> {
                if (!isActive) return
                val texts = event.results.map { it.text }
                val combined = texts.joinToString(" ").trim()
                if (combined.length > 5) {
                    ocrBuffer.addLast(TimestampedText(combined, System.currentTimeMillis()))
                    while (ocrBuffer.size > MAX_OCR_BUFFER) ocrBuffer.removeFirst()

                    // 주기적으로 일정 추출 시도
                    val now = System.currentTimeMillis()
                    if (now - lastOcrExtraction > OCR_EXTRACT_INTERVAL_MS) {
                        lastOcrExtraction = now
                        scheduleExtractor.extractFromOcr(texts)
                    }
                }
            }

            // ── Whisper 음성 텍스트 수집 ──
            is XRealEvent.InputEvent.AudioEmbedding -> {
                if (!isActive) return
                val text = event.transcript.trim()
                if (text.length > 3) {
                    speechBuffer.addLast(TimestampedText(text, event.timestamp))
                    while (speechBuffer.size > MAX_SPEECH_BUFFER) speechBuffer.removeFirst()
                }
            }

            // ── TILT 제스처: 궁금증 → 맥락 질의 ──
            is XRealEvent.InputEvent.Gesture -> {
                if (event.type == GestureType.TILT && isActive) {
                    handleCuriosityTilt()
                }
                // NOD/SHAKE — 응답에 대한 피드백
                if (event.type == GestureType.NOD && pendingResponse != null) {
                    handleResponseFeedback(positive = true)
                }
                if (event.type == GestureType.SHAKE && pendingResponse != null) {
                    handleResponseFeedback(positive = false)
                }
            }

            else -> {}
        }
    }

    // ══════════════════════════════════════════════
    //  Meeting Mode Lifecycle
    // ══════════════════════════════════════════════

    private fun activateMeetingMode() {
        if (isActive) return
        isActive = true
        ocrBuffer.clear()
        speechBuffer.clear()
        pendingResponse = null
        Log.i(TAG, "🏢 Meeting mode ACTIVATED")

        // HUD에 회의 모드 알림
        scope.launch {
            eventBus.publish(XRealEvent.ActionRequest.ShowMessage("📋 회의 모드 활성화 — 고개를 기울이면 맥락 정보를 표시합니다"))
        }
    }

    private fun deactivateMeetingMode() {
        if (!isActive) return
        isActive = false

        // 회의 종료 시 남은 OCR에서 최종 일정 추출 시도
        val allOcrTexts = ocrBuffer.map { it.text }
        if (allOcrTexts.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                scheduleExtractor.extractFromOcr(allOcrTexts)
            }
        }

        val meetingDuration = if (ocrBuffer.isNotEmpty() && speechBuffer.isNotEmpty()) {
            val start = minOf(
                ocrBuffer.firstOrNull()?.timestamp ?: Long.MAX_VALUE,
                speechBuffer.firstOrNull()?.timestamp ?: Long.MAX_VALUE
            )
            (System.currentTimeMillis() - start) / 60_000L
        } else 0L

        Log.i(TAG, "🏢 Meeting mode DEACTIVATED (duration: ~${meetingDuration}min, ocr:${ocrBuffer.size}, speech:${speechBuffer.size})")
        ocrBuffer.clear()
        speechBuffer.clear()
        pendingResponse = null
    }

    // ══════════════════════════════════════════════
    //  Curiosity TILT Handler
    // ══════════════════════════════════════════════

    private fun handleCuriosityTilt() {
        val now = System.currentTimeMillis()
        val tiltCooldown = cadenceConfig?.current?.tiltCooldownMs ?: TILT_COOLDOWN_MS
        if (now - lastTiltProcessed < tiltCooldown) {
            Log.d(TAG, "TILT cooldown active, ignoring")
            return
        }
        lastTiltProcessed = now

        // 현재 맥락 수집
        val recentOcr = ocrBuffer.takeLast(5).joinToString("\n") { it.text }
        val recentSpeech = speechBuffer.takeLast(10).joinToString("\n") { it.text }

        if (recentOcr.isBlank() && recentSpeech.isBlank()) {
            scope.launch {
                eventBus.publish(XRealEvent.ActionRequest.ShowMessage("맥락 정보가 부족합니다"))
            }
            return
        }

        Log.i(TAG, "🤔 Curiosity TILT — querying Gemini with context")

        scope.launch(Dispatchers.IO) {
            queryGeminiForContext(recentOcr, recentSpeech)
        }
    }

    private suspend fun queryGeminiForContext(ocrContext: String, speechContext: String) {
        // ★ Phase M: 사용자 컨텍스트(DNA·기억·지시사항) 주입 — 회의 맥락 응답 맞춤화
        val contextAddendum = try {
            personaManager?.buildContextAddendum("meeting_assistant")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
        val userProfile = contextAddendum ?: userProfileManager.getProfileSummary()

        val prompt = """
당신은 AR 안경의 회의 보조 AI입니다. 사용자가 회의 중 "궁금증" 제스처를 했습니다.
현재 보고 있는 문서와 들리는 대화를 바탕으로, 사용자에게 도움이 될 맥락 정보를 제공하세요.

$userProfile

[현재 보고 있는 문서 내용]
${ocrContext.ifBlank { "(문서 감지 없음)" }}

[최근 대화 내용]
${speechContext.ifBlank { "(대화 감지 없음)" }}

규칙:
1. 짧고 핵심적으로 응답 (3줄 이내, HUD에 표시됨)
2. 문서에 언급된 용어, 날짜, 수치에 대한 설명 우선
3. 대화 맥락과 문서 맥락을 연결하여 설명
4. 한국어로 응답
5. 사용자의 직업/역할을 고려한 맞춤 설명
""".trimIndent()

        // Budget gate
        tokenBudgetTracker?.let { tracker ->
            val check = tracker.checkBudget(com.xreal.nativear.ai.ProviderId.GEMINI, estimatedTokens = 1500)
            if (!check.allowed) {
                Log.w(TAG, "Meeting context query blocked by budget: ${check.reason}")
                eventBus.publish(XRealEvent.ActionRequest.ShowMessage("⚠️ 예산 제한으로 조회 불가"))
                return
            }
        }

        try {
            val response = aiRegistry.quickText(
                messages = listOf(AIMessage(role = "user", content = prompt)),
                visibility = AICallGateway.VisibilityIntent.INTERNAL_ONLY,
                intent = "meeting_context_generation"
            ) ?: run {
                eventBus.publish(XRealEvent.ActionRequest.ShowMessage("맥락 정보를 생성할 수 없습니다"))
                return
            }
            val reply = response.text?.trim() ?: "맥락 정보를 생성할 수 없습니다"
            tokenBudgetTracker?.recordUsage(com.xreal.nativear.ai.ProviderId.GEMINI, (reply.length / 4).coerceAtLeast(200))

            // HUD에 표시 (TTS 없음 — 회의 중)
            pendingResponse = reply
            eventBus.publish(XRealEvent.ActionRequest.ShowMessage("💡 $reply"))

            Log.i(TAG, "Context response sent to HUD: ${reply.take(80)}")
            // ★ Phase M: 회의 맥락 응답을 personaMemory에 저장
            try {
                val memService = org.koin.java.KoinJavaComponent.getKoin()
                    .getOrNull<com.xreal.nativear.ai.PersonaMemoryService>()
                memService?.savePersonaMemory(
                    personaId = "meeting_assistant",
                    content = "회의 맥락 응답: ${reply.take(200)}",
                    role = "AI"
                )
            } catch (_: Exception) {}
        } catch (e: Exception) {
            ErrorReporter.report(TAG, "Gemini context query failed", e)
            eventBus.publish(XRealEvent.ActionRequest.ShowMessage("⚠️ 맥락 조회 실패"))
        }
    }

    // ══════════════════════════════════════════════
    //  Feedback (NOD/SHAKE)
    // ══════════════════════════════════════════════

    private fun handleResponseFeedback(positive: Boolean) {
        val response = pendingResponse ?: return
        pendingResponse = null

        if (positive) {
            Log.i(TAG, "👍 User confirmed response useful")
            // 유용한 응답 → 메모리에 저장
            scope.launch {
                eventBus.publish(XRealEvent.ActionRequest.SaveMemory(
                    content = "[회의 맥락 질의] $response",
                    role = "meeting_context",
                    metadata = "feedback=positive"
                ))
            }
        } else {
            Log.i(TAG, "👎 User dismissed response")
            // HUD에서 메시지 해제 (빈 메시지로 덮기)
            scope.launch {
                eventBus.publish(XRealEvent.ActionRequest.ShowMessage(""))
            }
        }
    }

    // ══════════════════════════════════════════════
    //  Utility
    // ══════════════════════════════════════════════

    /** ConcurrentLinkedDeque에서 마지막 N개 가져오기 */
    private fun <T> ConcurrentLinkedDeque<T>.takeLast(n: Int): List<T> {
        val list = this.toList()
        return if (list.size <= n) list else list.subList(list.size - n, list.size)
    }

    fun isInMeetingMode(): Boolean = isActive

    fun getDebugState(): String {
        return "MeetingContext: active=$isActive, ocr=${ocrBuffer.size}, speech=${speechBuffer.size}, pending=${pendingResponse != null}"
    }
}
