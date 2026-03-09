package com.xreal.nativear.session

import android.util.Log
import com.xreal.nativear.ai.AICallGateway
import com.xreal.nativear.ai.AIMessage
import com.xreal.nativear.ai.IPersonaService
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.monitoring.DailyValueReporter
import com.xreal.nativear.profile.UserDNAManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * FeedbackSessionManager — 음성 트리거 구조화 피드백 세션.
 *
 * ## 역할 (Phase H)
 * - 음성 트리거 감지: "피드백 시작", "오늘 리뷰", "피드백 세션"
 *   → AIAgentManager.processWithGemini() 전처리 블록에서 호출
 * - 3문항 구조화 대화 → Gemini로 감성 분석 → UserDNA 업데이트
 * - 세션 당 최대 1회 동시 실행 (sessionActive 플래그)
 * - 피드백 점수 → DailyValueReporter.updateFeedbackScore()
 *
 * ## 음성 응답 수집 전략
 * - TTS 질문 발행 → VoiceCommand 이벤트 구독 (timeout 15초)
 * - 피드백 세션 중 들어온 첫 번째 VoiceCommand = 답변
 *
 * ## Koin 등록
 * AppModule.kt: single { FeedbackSessionManager(get<AIResourceRegistry>(), get(), get(), get()) }
 */
class FeedbackSessionManager(
    private val aiRegistry: com.xreal.nativear.ai.IAICallService,
    private val userDnaManager: UserDNAManager,
    private val dailyValueReporter: DailyValueReporter?,
    private val eventBus: GlobalEventBus,
    // ★ Phase M: 사용자 DNA·성향 컨텍스트 주입용
    private val personaManager: IPersonaService? = null
) {
    private val TAG = "FeedbackSessionManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var sessionActive = false
    @Volatile private var pendingAnswerDeferred: CompletableDeferred<String>? = null

    // 이벤트 구독 (start/stop 생명주기 관리 — AppBootstrapper가 관리)
    fun start() {
        scope.launch {
            eventBus.events.collect { event ->
                try {
                    if (event is XRealEvent.InputEvent.VoiceCommand && sessionActive) {
                        // 피드백 세션 중 음성 입력 → 대기 중인 답변 deferred 완료
                        pendingAnswerDeferred?.complete(event.text)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "이벤트 처리 오류: ${e.message}")
                }
            }
        }
        Log.i(TAG, "FeedbackSessionManager started")
    }

    fun stop() {
        scope.cancel()
        Log.i(TAG, "FeedbackSessionManager stopped")
    }

    /**
     * 피드백 세션 시작 (AIAgentManager에서 호출).
     * 이미 세션 진행 중이면 무시.
     */
    fun startFeedbackSession() {
        if (sessionActive) {
            Log.d(TAG, "피드백 세션 이미 진행 중 — 중복 시작 무시")
            return
        }
        scope.launch {
            sessionActive = true
            try {
                runStructuredSession()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "피드백 세션 오류: ${e.message}", e)
                eventBus.publish(XRealEvent.ActionRequest.SpeakTTS("피드백 세션 중 오류가 발생했습니다."))
            } finally {
                sessionActive = false
                pendingAnswerDeferred = null
            }
        }
    }

    /** 현재 피드백 세션 진행 중 여부 */
    fun isSessionActive(): Boolean = sessionActive

    // ─── 세션 핵심 로직 ───

    private suspend fun runStructuredSession() {
        Log.i(TAG, "피드백 세션 시작")
        eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(
            "오늘 피드백 세션을 시작합니다. 각 질문에 짧게 답해주세요."
        ))
        delay(2000L)

        val questions = listOf(
            "오늘 AI 어시스턴트가 가장 도움이 됐던 순간은 언제인가요?",
            "오늘 가장 불편하거나 아쉬웠던 점은 무엇인가요?",
            "내일 어시스턴트에게 가장 원하는 것이 있다면요?"
        )

        val answers = mutableListOf<String>()
        for (question in questions) {
            eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(question))
            val answer = waitForVoiceResponse(timeoutMs = 15_000L) ?: "(응답 없음)"
            answers.add(answer)
            delay(500L)
            Log.d(TAG, "Q: $question → A: $answer")
        }

        // Gemini로 분석
        val analysis = analyzeFeedbackWithAI(questions, answers)

        // UserDNA 업데이트
        userDnaManager.updateFromFeedback(
            expertSignal   = analysis.expertNeedSignal,
            dataSignal     = analysis.dataNeedSignal,
            autonomySignal = analysis.autonomySignal
        )

        // DailyValueReport 피드백 점수 업데이트
        dailyValueReporter?.updateFeedbackScore(analysis.sentimentScore)

        // 마무리 TTS
        eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(
            "피드백 감사합니다. ${analysis.summary}"
        ))

        Log.i(TAG, "피드백 세션 완료: sentiment=${analysis.sentimentScore}")
    }

    private suspend fun waitForVoiceResponse(timeoutMs: Long): String? {
        val deferred = CompletableDeferred<String>()
        pendingAnswerDeferred = deferred
        return withTimeoutOrNull(timeoutMs) {
            deferred.await()
        }.also {
            pendingAnswerDeferred = null
        }
    }

    private suspend fun analyzeFeedbackWithAI(
        questions: List<String>,
        answers: List<String>
    ): FeedbackAnalysis {
        return try {
            // ★ Phase M: 사용자 DNA·성향 컨텍스트 주입 — 피드백 감성 분석 정확도 향상
            val contextAddendum = try {
                personaManager?.buildContextAddendum("feedback_analyzer")?.takeIf { it.isNotBlank() }
            } catch (_: Exception) { null }
            val contextPrefix = if (contextAddendum != null) "[사용자 컨텍스트]\n$contextAddendum\n\n[피드백 내용]\n" else ""
            val qaText = contextPrefix + questions.zip(answers).joinToString("\n") { (q, a) ->
                "Q: $q\nA: $a"
            }
            val systemPrompt = """
                사용자의 피드백을 분석해 JSON 형식으로 반환하세요.
                {
                  "sentimentScore": 0.0~1.0 (0=매우 부정, 1=매우 긍정),
                  "summary": "1문장 요약",
                  "expertNeedSignal": 0.0~1.0 (전문가 의견 필요도),
                  "dataNeedSignal": 0.0~1.0 (데이터 기반 결정 필요도),
                  "autonomySignal": 0.0~1.0 (AI 자율성 선호도)
                }
                JSON만 반환. 다른 텍스트 없이.
            """.trimIndent()

            val messages = listOf(AIMessage(role = "user", content = qaText))
            val response = aiRegistry.quickText(
                messages, systemPrompt,
                callPriority = AICallGateway.CallPriority.PROACTIVE,
                visibility = AICallGateway.VisibilityIntent.INTERNAL_ONLY,
                intent = "feedback_analysis"
            ) ?: return FeedbackAnalysis(0.5f, "피드백을 기록했습니다.", 0.5f, 0.5f, 0.5f)
            val result = parseFeedbackJson(response.text ?: "")
            // ★ Phase M: 피드백 분석 결과를 personaMemory에 저장
            try {
                val memService = org.koin.java.KoinJavaComponent.getKoin()
                    .getOrNull<com.xreal.nativear.ai.PersonaMemoryService>()
                memService?.savePersonaMemory(
                    personaId = "feedback_analyzer",
                    content = "피드백 분석: sentiment=${result.sentimentScore} — ${result.summary}",
                    role = "AI"
                )
            } catch (_: Exception) {}
            result
        } catch (e: Exception) {
            Log.w(TAG, "AI 분석 실패, 기본값 반환: ${e.message}")
            FeedbackAnalysis(
                sentimentScore = 0.5f,
                summary = "피드백을 기록했습니다.",
                expertNeedSignal = 0.5f,
                dataNeedSignal = 0.5f,
                autonomySignal = 0.5f
            )
        }
    }

    private fun parseFeedbackJson(json: String): FeedbackAnalysis {
        return try {
            val cleaned = json.trim().removePrefix("```json").removeSuffix("```").trim()
            val sentiment = Regex(""""sentimentScore"\s*:\s*([\d.]+)""").find(cleaned)?.groupValues?.get(1)?.toFloatOrNull() ?: 0.5f
            val summary = Regex(""""summary"\s*:\s*"([^"]+)"""").find(cleaned)?.groupValues?.get(1) ?: "피드백 기록됨"
            val expert = Regex(""""expertNeedSignal"\s*:\s*([\d.]+)""").find(cleaned)?.groupValues?.get(1)?.toFloatOrNull() ?: 0.5f
            val data = Regex(""""dataNeedSignal"\s*:\s*([\d.]+)""").find(cleaned)?.groupValues?.get(1)?.toFloatOrNull() ?: 0.5f
            val autonomy = Regex(""""autonomySignal"\s*:\s*([\d.]+)""").find(cleaned)?.groupValues?.get(1)?.toFloatOrNull() ?: 0.5f
            FeedbackAnalysis(sentiment, summary, expert, data, autonomy)
        } catch (e: Exception) {
            Log.w(TAG, "JSON 파싱 실패: ${e.message}")
            FeedbackAnalysis(0.5f, "피드백 기록됨", 0.5f, 0.5f, 0.5f)
        }
    }

    // ─── 데이터 클래스 ───

    data class FeedbackAnalysis(
        val sentimentScore: Float,      // 0.0~1.0 (전체 만족도)
        val summary: String,            // 1문장 요약
        val expertNeedSignal: Float,    // 전문가 의견 필요도
        val dataNeedSignal: Float,      // 데이터 기반 필요도
        val autonomySignal: Float       // AI 자율성 선호도
    )
}
