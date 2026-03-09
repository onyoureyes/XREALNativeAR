package com.xreal.nativear

import android.content.Context
import android.util.Log
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.whisper.SpeakerEmbeddingAdapter
import com.xreal.whisper.SpeechResult
import com.xreal.whisper.WhisperEngine
import kotlinx.coroutines.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.tensorflow.lite.Interpreter

/**
 * VoiceManager: 활성 대화 ASR + TTS 관리.
 *
 * ## 3-Layer Audio Architecture (Layer 1: 활성 대화)
 * - Android SpeechRecognizer 제거 → SenseVoice (sherpa-onnx) 사용
 * - SenseVoice 내장 감정 인식 (WavLM + EmotionClassifier 대체)
 * - 다국어 지원: ko/en/zh/ja/yue 자동 감지
 *
 * ## 동작 흐름
 * startListening() → AudioRecord 캡처 → SenseVoice 전사+감정
 *   → EnrichedVoiceCommand 이벤트 발행
 *
 * ## AudioAnalysisService 연동
 * - isActiveConversation=true 구간: AudioAnalysisService VAD 일시 중지
 *   (마이크 동시 접근 충돌 방지)
 */
class VoiceManager(
    private val context: Context,
    private val ttsAdapter: SystemTTSAdapter,
    private val eventBus: GlobalEventBus
) : IVoiceService, KoinComponent {

    private val TAG = "VoiceManager"

    // 화자 임베딩 (3D-Speaker ECAPA-TDNN, 192-dim)
    private val speakerEmbedding: SpeakerEmbeddingAdapter by inject()

    companion object {
        /**
         * true이면 VoiceManager가 활성 대화 ASR 진행 중 → AudioAnalysisService VAD 일시 중지
         * false이면 AudioAnalysisService가 라이프로깅 재개
         */
        @Volatile
        var isActiveConversation: Boolean = false
    }

    private var isConversing = false
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 활성 대화 전용 WhisperEngine (SenseVoice 백엔드)
    private var conversationEngine: WhisperEngine? = null
    @Volatile private var engineReady = false

    /** isActiveConversation=true 가 stuck 되는 것을 방지하는 60초 안전 타임아웃 */
    private var activeConversationTimeoutJob: Job? = null

    private fun setActiveConversation(active: Boolean) {
        if (active) {
            isActiveConversation = true
            activeConversationTimeoutJob?.cancel()
            activeConversationTimeoutJob = scope.launch {
                delay(60_000L)
                if (isActiveConversation) {
                    Log.w(TAG, "isActiveConversation 60초 타임아웃 — 강제 리셋")
                    isActiveConversation = false
                    isConversing = false
                }
            }
        } else {
            isActiveConversation = false
            activeConversationTimeoutJob?.cancel()
            activeConversationTimeoutJob = null
        }
    }

    init {
        initConversationEngine()
        eventBus.publish(XRealEvent.SystemEvent.DebugLog("VoiceManager Ready (SenseVoice ASR + ECAPA-TDNN + TTS)"))
    }

    /**
     * 활성 대화 전용 WhisperEngine 초기화 (SenseVoice 백엔드 강제).
     * AudioAnalysisService의 라이프로깅 엔진과 별도 인스턴스.
     */
    private fun initConversationEngine() {
        scope.launch {
            try {
                val engine = WhisperEngine(context)
                // SenseVoice 우선, 없으면 TFLite 폴백 (AUTO 모드)
                WhisperEngine.setBackendPreference(context, WhisperEngine.BACKEND_AUTO)
                engine.initialize(Interpreter.Options().apply { setNumThreads(4) })

                engine.setOnSpeechResultListener { result ->
                    handleSpeechResult(result)
                }
                engine.setOnResultListener { text ->
                    // SpeechResult 콜백에서 처리하므로 여기서는 fallback만
                    if (text.isNotBlank() && text.length >= 2) {
                        Log.d(TAG, "결과 (fallback): $text")
                    }
                }

                conversationEngine = engine
                engineReady = true
                Log.i(TAG, "활성 대화 엔진 초기화 완료 (백엔드: ${engine.getCurrentBackendName()})")
            } catch (e: Throwable) {
                // NoClassDefFoundError(libonnxruntime.so 미로드) 등 Error도 잡아야 함
                Log.e(TAG, "활성 대화 엔진 초기화 실패: ${e.message}", e)
            }
        }
    }

    /**
     * SenseVoice 전사 결과 처리.
     * 감정+언어 포함 → EnrichedVoiceCommand 발행.
     */
    private fun handleSpeechResult(result: SpeechResult) {
        val text = result.text.trim()
        if (text.length < 2) return

        scope.launch {
            // 화자 임베딩은 별도 AudioRecord에서 추출 (캡처 중인 버퍼 사용)
            val emotion = result.emotion ?: "NEUTRAL"
            val emotionScore = when (emotion.uppercase()) {
                "NEUTRAL" -> 0.6f
                "HAPPY", "SURPRISED" -> 0.85f
                "SAD", "ANGRY", "FEARFUL", "DISGUSTED" -> 0.8f
                else -> 0.5f
            }
            val lang = result.lang ?: "unknown"

            Log.i(TAG, "대화 ASR: \"$text\" [lang=$lang, emotion=$emotion]")

            eventBus.publish(XRealEvent.InputEvent.EnrichedVoiceCommand(
                text = text,
                speaker = "USER",
                emotion = emotion.lowercase(),
                emotionScore = emotionScore
            ))
        }

        // 활성 대화 완료 (단일 발화 인식 후 종료)
        isConversing = false
        setActiveConversation(false)
        Log.d(TAG, "대화 ASR 완료 — isActiveConversation=false")
    }

    override fun startListening() {
        if (isConversing) return
        if (!engineReady) {
            Log.w(TAG, "SenseVoice 엔진 미준비 — 무시")
            return
        }
        isConversing = true
        setActiveConversation(true)
        Log.d(TAG, "startListening() — SenseVoice 활성 대화 시작")

        // AudioRecord 캡처 시작 → SenseVoice 엔진에 오디오 공급
        captureJob = scope.launch {
            try {
                val sampleRate = 16000
                val bufferSize = android.media.AudioRecord.getMinBufferSize(
                    sampleRate,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT
                )

                val audioRecord = android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (audioRecord.state != android.media.AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord 초기화 실패")
                    isConversing = false
                    setActiveConversation(false)
                    return@launch
                }

                audioRecord.startRecording()
                val audioBuffer = mutableListOf<Short>()
                val tempBuffer = ShortArray(1024)

                // 최대 15초 캡처 (활성 대화 단일 발화 기준)
                val maxDurationMs = 15_000L
                val startTime = System.currentTimeMillis()
                var silenceCount = 0

                while (isActive && isConversing) {
                    val read = audioRecord.read(tempBuffer, 0, tempBuffer.size)
                    if (read > 0) {
                        for (i in 0 until read) audioBuffer.add(tempBuffer[i])

                        // RMS 기반 무음 감지 (2초 이상 무음 → 발화 종료)
                        val rms = kotlin.math.sqrt(
                            tempBuffer.take(read).fold(0L) { acc, s -> acc + s.toLong() * s }.toDouble() / read
                        )
                        if (rms < 200) silenceCount++ else silenceCount = 0
                        if (silenceCount > 30) break  // ~2초 무음 (1024 samples @ 16kHz ≈ 64ms/chunk)

                        // AudioLevel 이벤트 (UI 피드백)
                        val level = ((rms / 5000.0).coerceIn(0.0, 1.0)).toFloat()
                        eventBus.publish(XRealEvent.InputEvent.AudioLevel(level))
                    }

                    if (System.currentTimeMillis() - startTime > maxDurationMs) break
                }

                audioRecord.stop()
                audioRecord.release()

                // 캡처된 오디오를 SenseVoice로 전사
                if (audioBuffer.size > 8000) {  // 최소 0.5초
                    val audioData = audioBuffer.toShortArray()
                    val engine = conversationEngine ?: return@launch

                    // 화자 임베딩 추출 (ECAPA-TDNN, 비동기)
                    if (speakerEmbedding.isReady) {
                        launch {
                            val embedding = speakerEmbedding.extractEmbeddingAsBytes(audioData)
                            if (embedding != null) {
                                eventBus.publish(XRealEvent.InputEvent.AudioEmbedding(
                                    transcript = "",
                                    audioEmbedding = embedding,
                                    timestamp = System.currentTimeMillis(),
                                    latitude = null,
                                    longitude = null
                                ))
                            }
                        }
                    }

                    // SenseVoice 직접 전사
                    val speechResult = engine.transcribeDirect(audioData)
                    if (speechResult != null && speechResult.text.isNotBlank()) {
                        handleSpeechResult(speechResult)
                    } else {
                        Log.d(TAG, "SenseVoice 결과 없음 — 발화 미감지")
                        isConversing = false
                        setActiveConversation(false)
                    }
                } else {
                    Log.d(TAG, "오디오 너무 짧음 (${audioBuffer.size} samples)")
                    isConversing = false
                    setActiveConversation(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "대화 캡처 오류: ${e.message}", e)
                isConversing = false
                setActiveConversation(false)
            }
        }
    }

    override fun stopListening() {
        captureJob?.cancel()
        captureJob = null
        isConversing = false
        setActiveConversation(false)
    }

    override fun speak(text: String, isResponse: Boolean) {
        if (isResponse) {
            isConversing = true
            setActiveConversation(true)
            eventBus.publish(XRealEvent.SystemEvent.VoiceActivity(true))
        }

        ttsAdapter.setProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (isResponse) {
                    isConversing = false
                    setActiveConversation(false)
                    eventBus.publish(XRealEvent.SystemEvent.VoiceActivity(false))
                    Log.d(TAG, "TTS onDone — isActiveConversation=false")
                }
            }
            override fun onError(utteranceId: String?) {
                if (isResponse) {
                    isConversing = false
                    setActiveConversation(false)
                    eventBus.publish(XRealEvent.SystemEvent.VoiceActivity(false))
                }
            }
        })

        ttsAdapter.speak(text, "CONVERSATION_REPLY")
    }

    // Wake word detection — AudioAnalysisService에서 SenseVoice로 처리
    override fun startWakeWordDetection() { /* no-op */ }
    override fun stopWakeWordDetection() { /* no-op */ }

    override fun setConversing(conversing: Boolean) {
        isConversing = conversing
    }
}
