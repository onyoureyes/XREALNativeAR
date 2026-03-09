package com.xreal.nativear

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.whisper.SpeechResult

/**
 * AudioAnalysisService: 3-Layer Audio Architecture — Layer 1 (Always-On).
 *
 * ## Architecture
 *   [Mic] → [SileroVAD/Energy] → speech detected → [SenseVoice STT] → transcript (다국어)
 *     ├── Save to memory (lifelog)
 *     ├── Publish AudioEmbedding event (→ MemoryRepository → SceneDB)
 *     ├── SenseVoice 감정 인식 → VoiceFeedback 이벤트
 *     ├── SenseVoice 언어 감지 → ko/en/zh/ja/yue 자동 전환
 *     ├── Show transcript in HUD overlay (4초 표시)
 *     └── Wake word detection ("범블비" / "bumblebee")
 *
 *   [Mic] → [YAMNet 3초 루프] → ambient sound classification
 *
 * ## SenseVoice 장점 (vs 이전 Whisper TFLite + Korean SpeechRecognizer)
 * - 다국어 자동 감지: 한국어 wake word "범블비" 직접 인식 가능
 * - 감정 인식 내장: 별도 EmotionClassifier 불필요
 * - 오디오 이벤트 감지: BGM, 웃음, 박수 등 자동
 * - Android SpeechRecognizer 제거 → 마이크 충돌 제거
 */
class AudioAnalysisService : Service() {
    private val TAG = "AudioAnalysisService"
    private val CHANNEL_ID = "AudioAnalysisChannel"
    private val NOTIFICATION_ID = 101

    companion object {
        // Static audio provider for BreathingAnalyzer (set when engine starts)
        @Volatile var audioProvider: ((Int) -> ShortArray?)? = null
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Injected dependencies
    private val memoryService: IMemoryService by inject()
    private val locationManager: LocationManager by inject()
    private val eventBus: GlobalEventBus by inject()
    private val yamnetClassifier: AudioEventClassifier by inject()
    private val connectivityMonitor: com.xreal.nativear.edge.ConnectivityMonitor by inject()
    private val edgeAgentProvider: com.xreal.nativear.ai.IAIProvider by inject(qualifier = named("edge_agent"))

    // VAD-based WhisperEngine from whisper-standalone module (SenseVoice 백엔드)
    private var vadWhisperEngine: com.xreal.whisper.WhisperEngine? = null

    // ★ 원격 음성 서버 폴백 (엣지 STT 실패 시)
    private var remoteSpeechClient: com.xreal.nativear.remote.RemoteSpeechClient? = null
    @Volatile private var useRemoteFallback = false

    // YAMNet periodic classification job
    private var yamnetJob: Job? = null

    // Coordination: embedding extracted from audio segment, paired with next transcript
    @Volatile private var pendingEmbedding: FloatArray? = null
    @Volatile private var pendingTimestamp: Long = 0L
    @Volatile private var pendingEmotion: String? = null  // SenseVoice 감정 캐시

    // HUD transcript 자동 제거 job
    private var hudClearJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initializeVadSenseVoice()
    }

    /**
     * VAD + SenseVoice 기반 라이프로깅 엔진 초기화.
     * SenseVoice가 기본 백엔드 (다국어 + 감정 + 오디오 이벤트).
     */
    private fun initializeVadSenseVoice() {
        try {
            val engine = com.xreal.whisper.WhisperEngine(this)
            // SenseVoice 백엔드 우선 선택 (다국어 + 감정 인식)
            com.xreal.whisper.WhisperEngine.setBackendPreference(this, com.xreal.whisper.WhisperEngine.BACKEND_AUTO)
            val options = org.tensorflow.lite.Interpreter.Options().apply {
                setNumThreads(4)
            }
            engine.initialize(options, com.xreal.whisper.ModelType.BASE)

            // Audio segment listener: extract embedding when VAD detects speech
            engine.setOnAudioSegmentListener { audioData ->
                val embedding = engine.extractEmbedding(audioData)
                if (embedding != null) {
                    pendingEmbedding = embedding
                    pendingTimestamp = System.currentTimeMillis()
                }
            }

            // SenseVoice 감정/언어 결과 콜백
            engine.setOnSpeechResultListener { result ->
                handleSenseVoiceResult(result)
            }

            // Transcript result listener: HUD 표시 + 메모리 저장 + wake word + events
            engine.setOnResultListener { text ->
                val cleanText = text.trim()
                if (cleanText.length < 2) return@setOnResultListener

                Log.i(TAG, "VAD+SenseVoice: $cleanText")
                processTranscript(cleanText)
            }

            // VAD status listener for UI feedback
            engine.setOnVadStatusListener { isSpeech ->
                serviceScope.launch {
                    eventBus.publish(XRealEvent.SystemEvent.VoiceActivity(isSpeech))
                }
            }

            vadWhisperEngine = engine
            audioProvider = { numSamples -> engine.getRecentAudio(numSamples) }
            engine.startListening()
            Log.i(TAG, "VAD+SenseVoice started (backend: ${engine.getCurrentBackendName()}, 다국어+감정)")

            serviceScope.launch {
                eventBus.publish(XRealEvent.SystemEvent.DebugLog(
                    "Audio Layer 1 Active: ${engine.getCurrentBackendName()} (ko/en/zh/ja/yue)"
                ))
            }

            // Start YAMNet periodic ambient sound classification (every 3 seconds)
            startYamnetLoop()

        } catch (e: Throwable) {
            // NoClassDefFoundError(libonnxruntime.so 미로드) 등 Error도 반드시 잡아야 함
            Log.e(TAG, "Failed to init VAD+SenseVoice: ${e.message}", e)
            serviceScope.launch {
                eventBus.publish(XRealEvent.SystemEvent.DebugLog("VAD+SenseVoice FAILED: ${e.message}"))
            }
            // ★ 엣지 STT 실패 → 원격 음성 서버 폴백 활성화
            initRemoteSpeechFallback()
        }
    }

    /**
     * 원격 음성 서버 폴백 초기화.
     * 엣지 STT(SenseVoice/Whisper)가 실패했을 때 PC 서버로 마이크 스트리밍.
     * 서버: Whisper-medium Vulkan (RX570) + 화자분리 + 감정분석 + 256-dim 임베딩.
     */
    private fun initRemoteSpeechFallback() {
        try {
            val httpClient: okhttp3.OkHttpClient = org.koin.java.KoinJavaComponent.getKoin().get()
            val client = com.xreal.nativear.remote.RemoteSpeechClient(httpClient)

            // STT 결과 수신 → 기존 processTranscript 파이프라인에 합류
            client.setOnTranscriptListener { result ->
                val cleanText = result.text.trim()
                if (cleanText.length < 2) return@setOnTranscriptListener

                Log.i(TAG, "[RemoteSTT] [${result.speaker ?: "?"}] $cleanText (${result.emotion}, ${result.language})")

                // 감정 캐시 (기존 SenseVoice 호환)
                pendingEmotion = result.emotion

                // 임베딩 캐시 (256-dim → ByteArray)
                result.embedding?.let { emb ->
                    pendingEmbedding = emb  // 256-dim (서버) vs 192-dim (엣지) — 크기 다름 OK
                    pendingTimestamp = System.currentTimeMillis()
                }

                // 감정 이벤트 발행
                val emotionStr = result.emotion
                if (!emotionStr.isNullOrBlank() && emotionStr.uppercase() != "NEUTRAL") {
                    val sentiment = when (emotionStr.uppercase()) {
                        "HAPPY", "SURPRISED" -> com.xreal.nativear.core.FeedbackSentiment.POSITIVE
                        "SAD", "ANGRY", "FEARFUL", "DISGUSTED" -> com.xreal.nativear.core.FeedbackSentiment.NEGATIVE
                        else -> null
                    }
                    if (sentiment != null && cleanText.length > 3) {
                        serviceScope.launch {
                            eventBus.publish(XRealEvent.InputEvent.VoiceFeedback(cleanText, sentiment))
                        }
                    }
                }

                // 기존 전사 파이프라인에 합류 (HUD, 메모리, wake word, 키워드)
                processTranscript(cleanText)
            }

            // 부분 결과 수신 → HUD 표시
            client.setOnPartialListener { text, speaker, _ ->
                val display = if (speaker != null) "[$speaker] $text" else text
                showTranscriptInHud(display)
            }

            // 연결 상태 이벤트
            client.setOnConnectionChangedListener { connected ->
                serviceScope.launch {
                    eventBus.publish(XRealEvent.SystemEvent.DebugLog(
                        if (connected) "음성 서버 WebSocket 연결됨" else "음성 서버 WebSocket 끊김"
                    ))
                }
            }

            // 화자 임베딩 수신 → SpeakerDiarizer 연동
            client.setOnEmbeddingListener { speaker, embedding ->
                serviceScope.launch {
                    eventBus.publish(XRealEvent.InputEvent.AudioEmbedding(
                        transcript = "[speaker:$speaker]",
                        audioEmbedding = floatArrayToByteArray(embedding),
                        timestamp = System.currentTimeMillis(),
                        latitude = null, longitude = null,
                        emotion = null
                    ))
                }
            }

            client.start(serviceScope)
            remoteSpeechClient = client
            useRemoteFallback = true

            // 마이크 캡처 시작 (VAD 없이, 서버로 직접 스트리밍)
            startRemoteMicStreaming(client)

            Log.i(TAG, "★ 원격 음성 서버 폴백 활성화")
            serviceScope.launch {
                eventBus.publish(XRealEvent.SystemEvent.DebugLog(
                    "Audio Layer 1: 원격 서버 폴백 (Whisper-medium Vulkan)"
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "원격 음성 폴백 초기화 실패: ${e.message}", e)
        }
    }

    /**
     * 마이크 → 원격 서버 스트리밍.
     * VAD 기반: SileroVAD가 speech 감지 시 WebSocket으로 오디오 전송.
     */
    private fun startRemoteMicStreaming(client: com.xreal.nativear.remote.RemoteSpeechClient) {
        serviceScope.launch(Dispatchers.IO) {
            // 서버 연결 대기
            var waitCount = 0
            while (!client.isAvailable() && waitCount < 30) {
                delay(1000L)
                waitCount++
            }
            if (!client.isAvailable()) {
                Log.w(TAG, "음성 서버 사용 불가 — 원격 스트리밍 중단")
                return@launch
            }

            // AudioRecord 직접 캡처 (WhisperEngine 없이)
            val sampleRate = 16000
            val bufferSize = android.media.AudioRecord.getMinBufferSize(
                sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200) * 2

            val recorder = try {
                android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "마이크 권한 없음: ${e.message}")
                return@launch
            }

            if (recorder.state != android.media.AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 초기화 실패")
                return@launch
            }

            // SileroVAD for speech detection
            val vad = try {
                com.xreal.whisper.SileroVAD(this@AudioAnalysisService)
            } catch (e: Throwable) {
                Log.w(TAG, "SileroVAD 초기화 실패, RMS 폴백 사용: ${e.message}")
                null
            }

            recorder.startRecording()
            Log.i(TAG, "원격 스트리밍 마이크 시작 (16kHz, buffer=$bufferSize)")

            client.connect()

            val frameSize = 512  // VAD 프레임 크기
            val frame = ShortArray(frameSize)
            val speechBuffer = mutableListOf<ShortArray>()
            var isSpeaking = false
            var silenceFrames = 0
            val silenceThreshold = 62 // ~2초 (512 samples * 62 / 16000)
            var sessionStarted = false

            try {
                while (isActive && useRemoteFallback) {
                    val read = recorder.read(frame, 0, frameSize)
                    if (read <= 0) continue

                    val chunk = frame.copyOf(read)

                    // VAD 판정
                    val isSpeech = if (vad != null) {
                        vad.isSpeech(chunk)
                    } else {
                        // RMS 폴백
                        val rms = Math.sqrt(chunk.map { it.toLong() * it.toLong() }.average()).toFloat()
                        rms > 1800f
                    }

                    if (isSpeech) {
                        silenceFrames = 0
                        if (!isSpeaking) {
                            isSpeaking = true
                            if (!sessionStarted) {
                                client.startStreaming("auto")
                                sessionStarted = true
                            }
                        }
                        // 서버로 전송
                        client.sendAudio(chunk)
                    } else if (isSpeaking) {
                        silenceFrames++
                        // 묵음도 전송 (서버가 경계 판단)
                        client.sendAudio(chunk)
                        if (silenceFrames >= silenceThreshold) {
                            isSpeaking = false
                            client.stopStreaming()
                            sessionStarted = false
                        }
                    }

                    // VoiceActivity 이벤트 (UI 피드백)
                    if (isSpeech != isSpeaking) {
                        eventBus.publish(XRealEvent.SystemEvent.VoiceActivity(isSpeech))
                    }
                }
            } finally {
                recorder.stop()
                recorder.release()
                vad?.close()
                client.stopStreaming()
                Log.i(TAG, "원격 스트리밍 마이크 종료")
            }
        }
    }

    /**
     * SenseVoice 감정/오디오 이벤트 처리.
     */
    private fun handleSenseVoiceResult(result: SpeechResult) {
        val emotionStr = result.emotion
        val langStr = result.lang
        // 감정 캐시 (AudioEmbedding 이벤트에 포함시키기 위해)
        pendingEmotion = emotionStr
        if (!emotionStr.isNullOrBlank() && emotionStr != "NEUTRAL") {
            Log.i(TAG, "SenseVoice 감정: $emotionStr, 언어: $langStr, 텍스트: ${result.text.take(30)}")
            val sentiment = when (emotionStr.uppercase()) {
                "HAPPY", "SURPRISED" -> com.xreal.nativear.core.FeedbackSentiment.POSITIVE
                "SAD", "ANGRY", "FEARFUL", "DISGUSTED" -> com.xreal.nativear.core.FeedbackSentiment.NEGATIVE
                else -> null
            }
            if (sentiment != null && result.text.length > 3) {
                serviceScope.launch {
                    eventBus.publish(XRealEvent.InputEvent.VoiceFeedback(
                        text = result.text,
                        sentiment = sentiment
                    ))
                }
            }
        }
        // 오디오 이벤트 감지 (BGM, 웃음, 박수 등)
        val audioEvent = result.audioEvent
        if (!audioEvent.isNullOrBlank() && audioEvent != "speech") {
            Log.i(TAG, "SenseVoice 오디오 이벤트: $audioEvent")
            serviceScope.launch {
                eventBus.publish(XRealEvent.SystemEvent.DebugLog(
                    "오디오 이벤트: $audioEvent (${result.text.take(20)})"
                ))
            }
        }
    }

    /**
     * 전사 결과 처리: HUD + 메모리 + wake word + 키워드 감지.
     */
    private fun processTranscript(cleanText: String) {
        // 0. HUD 오버레이에 전사 결과 표시
        showTranscriptInHud(cleanText)

        // 1. Save to memory
        serviceScope.launch {
            memoryService.saveMemory(cleanText, "PASSIVE_VOICE")
        }

        // 2. Publish AudioEmbedding event
        val embedding = pendingEmbedding
        if (embedding != null) {
            serviceScope.launch {
                val loc = locationManager.getCurrentLocation()
                val cachedEmotion = pendingEmotion
                pendingEmotion = null
                eventBus.publish(XRealEvent.InputEvent.AudioEmbedding(
                    transcript = cleanText,
                    audioEmbedding = floatArrayToByteArray(embedding),
                    timestamp = pendingTimestamp,
                    latitude = loc?.latitude,
                    longitude = loc?.longitude,
                    emotion = cachedEmotion
                ))
            }
            pendingEmbedding = null
        }

        // 3. Broadcast for legacy UI overlay
        val intent = Intent("com.xreal.nativear.TRANSCRIPT_UPDATE")
        intent.putExtra("type", "PASSIVE_STT")
        intent.putExtra("text", cleanText)
        sendBroadcast(intent)

        // 4. Wake word detection: "범블비" (SenseVoice가 한국어 직접 인식)
        val lower = cleanText.lowercase()
        val isOnline = connectivityMonitor.isOnline
        if (lower.contains("범블비") || lower.contains("bumblebee") || lower.contains("bumble bee")) {
            Log.i(TAG, "Wake word '범블비' detected via SenseVoice! (online=$isOnline)")
            serviceScope.launch {
                eventBus.publish(XRealEvent.SystemEvent.DebugLog("범블비 detected (SenseVoice, online=$isOnline)"))
            }
            val command = cleanText
                .replace("범블비", "", ignoreCase = true)
                .replace("bumblebee", "", ignoreCase = true)
                .replace("bumble bee", "", ignoreCase = true)
                .trim()
            if (!isOnline) {
                processWithLocalLLM(if (command.isNotBlank()) command else cleanText)
            } else if (command.isNotBlank()) {
                serviceScope.launch {
                    eventBus.publish(XRealEvent.InputEvent.VoiceCommand(command))
                }
            }
        }

        // 5. Direct Gemini keywords
        if (lower.contains("gemini") || lower.contains("제미나이") || lower.contains("제미니")) {
            val command = cleanText
                .replace("gemini", "", ignoreCase = true)
                .replace("제미나이", "")
                .replace("제미니", "")
                .trim()
            if (!isOnline) {
                processWithLocalLLM(if (command.isNotBlank()) command else cleanText)
            } else if (command.isNotBlank()) {
                serviceScope.launch {
                    eventBus.publish(XRealEvent.InputEvent.VoiceCommand(command))
                }
            }
        }

        // 6. Running voice commands
        val runningKeywords = mapOf(
            "달리기 시작" to com.xreal.nativear.core.RunningCommandType.START,
            "러닝 시작" to com.xreal.nativear.core.RunningCommandType.START,
            "start run" to com.xreal.nativear.core.RunningCommandType.START,
            "달리기 중지" to com.xreal.nativear.core.RunningCommandType.STOP,
            "러닝 끝" to com.xreal.nativear.core.RunningCommandType.STOP,
            "stop run" to com.xreal.nativear.core.RunningCommandType.STOP,
            "일시 정지" to com.xreal.nativear.core.RunningCommandType.PAUSE,
            "pause" to com.xreal.nativear.core.RunningCommandType.PAUSE,
            "다시 시작" to com.xreal.nativear.core.RunningCommandType.RESUME,
            "resume" to com.xreal.nativear.core.RunningCommandType.RESUME,
            "랩" to com.xreal.nativear.core.RunningCommandType.LAP,
            "lap" to com.xreal.nativear.core.RunningCommandType.LAP,
            "현재 상태" to com.xreal.nativear.core.RunningCommandType.STATUS,
            "status" to com.xreal.nativear.core.RunningCommandType.STATUS
        )
        for ((keyword, cmdType) in runningKeywords) {
            if (lower.contains(keyword)) {
                Log.i(TAG, "Running command detected: $keyword -> $cmdType")
                serviceScope.launch {
                    eventBus.publish(XRealEvent.InputEvent.RunningVoiceCommand(cmdType))
                }
                break
            }
        }

        // 7. Voice feedback detection
        val positiveFeedback = listOf(
            "좋아", "좋았어", "좋다", "좋은데", "나이스", "괜찮아", "고마워", "감사",
            "맞아", "그래", "도움", "유용", "재밌", "흥미",
            "good", "great", "nice", "thanks", "helpful", "useful", "interesting"
        )
        val negativeFeedback = listOf(
            "싫어", "별로", "짜증", "그만", "필요없", "쓸데없", "지루",
            "피곤", "힘들", "안돼", "아니", "됐어", "몰라",
            "bad", "annoying", "stop", "useless", "boring", "no", "wrong"
        )
        val sentiment = when {
            positiveFeedback.any { lower.contains(it) } ->
                com.xreal.nativear.core.FeedbackSentiment.POSITIVE
            negativeFeedback.any { lower.contains(it) } ->
                com.xreal.nativear.core.FeedbackSentiment.NEGATIVE
            else -> null
        }
        if (sentiment != null) {
            Log.i(TAG, "Voice feedback detected: $cleanText → $sentiment")
            serviceScope.launch {
                eventBus.publish(XRealEvent.InputEvent.VoiceFeedback(
                    text = cleanText,
                    sentiment = sentiment
                ))
            }
        }
    }

    /**
     * Whisper 전사 결과를 HUD 오버레이에 표시.
     */
    private fun showTranscriptInHud(text: String) {
        hudClearJob?.cancel()

        val displayText = if (text.length > 50) "🎤 ${text.take(47)}…" else "🎤 $text"

        serviceScope.launch {
            eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(
                DrawCommand.Remove("stt_transcript")
            ))
            eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(
                DrawCommand.Add(DrawElement.Text(
                    id = "stt_transcript",
                    color = "#FFFFFF",
                    opacity = 0.85f,
                    x = 3f,
                    y = 82f,
                    text = displayText,
                    size = 17f,
                    bold = false
                ))
            ))
        }

        hudClearJob = serviceScope.launch {
            delay(4000L)
            eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(
                DrawCommand.Remove("stt_transcript")
            ))
        }
    }

    /**
     * 오프라인 모드: 전사 결과를 로컬 EdgeLLM으로 직접 처리.
     */
    private fun processWithLocalLLM(userText: String) {
        Log.i(TAG, "로컬 LLM 직접 처리: $userText")

        val failsafe: com.xreal.nativear.resilience.FailsafeController? = try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull()
        } catch (_: Exception) { null }
        val currentTier = failsafe?.currentTier ?: com.xreal.nativear.core.CapabilityTier.TIER_0_FULL
        if (currentTier == com.xreal.nativear.core.CapabilityTier.TIER_6_MINIMAL) {
            Log.w(TAG, "CapabilityTier=$currentTier — 엣지 AI 호출 차단")
            return
        }

        if (!edgeAgentProvider.isAvailable) {
            Log.w(TAG, "EdgeAgent 미준비 — TTS 안내")
            serviceScope.launch {
                eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(
                    "로컬 AI 로딩 중입니다. 잠시 후 다시 말씀해주세요.",
                    important = false
                ))
            }
            return
        }
        serviceScope.launch {
            try {
                val response = edgeAgentProvider.sendMessage(
                    messages = listOf(com.xreal.nativear.ai.AIMessage(role = "user", content = userText)),
                    systemPrompt = "당신은 XREAL AR 안경을 위한 AI 어시스턴트입니다. 오프라인 모드입니다. 한국어로 2문장 이내로 간결하게 답하세요."
                )
                val reply = response.text?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("[") }
                    ?: return@launch
                Log.i(TAG, "로컬 LLM 응답: $reply")
                eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(reply, important = true))
            } catch (e: Exception) {
                Log.e(TAG, "로컬 LLM 처리 실패: ${e.message}", e)
            }
        }
    }

    private fun startYamnetLoop() {
        if (!yamnetClassifier.isReady) {
            Log.w(TAG, "YAMNet not ready — skipping ambient sound classification")
            return
        }

        yamnetJob = serviceScope.launch(Dispatchers.Default) {
            Log.i(TAG, "YAMNet 3s ambient classification loop started")
            while (isActive) {
                delay(3000L)
                try {
                    val audio = vadWhisperEngine?.getRecentAudio(15600) ?: continue
                    val (events, embedding) = yamnetClassifier.classifyWithEmbedding(audio)
                    if (events.isEmpty()) continue

                    val loc = locationManager.getCurrentLocation()
                    val eventPairs = events.map { it.label to it.score }
                    val embeddingBytes = floatArrayToByteArray(embedding)

                    Log.d(TAG, "AudioEnvironment: ${eventPairs.joinToString { "${it.first}:${String.format("%.2f", it.second)}" }}")

                    eventBus.publish(XRealEvent.PerceptionEvent.AudioEnvironment(
                        events = eventPairs,
                        embedding = embeddingBytes,
                        timestamp = System.currentTimeMillis(),
                        latitude = loc?.latitude,
                        longitude = loc?.longitude
                    ))
                } catch (e: Exception) {
                    Log.e(TAG, "YAMNet classification error: ${e.message}")
                }
            }
        }
    }

    private fun floatArrayToByteArray(floats: FloatArray): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(floats.size * 4)
        buffer.order(java.nio.ByteOrder.nativeOrder())
        for (f in floats) buffer.putFloat(f)
        return buffer.array()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("XREAL AI Life-Logger")
            .setContentText("SenseVoice listening (ko/en/zh/ja)...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Audio Analysis Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Shutting down AudioAnalysisService...")
        hudClearJob?.cancel()
        yamnetJob?.cancel()
        yamnetJob = null
        useRemoteFallback = false
        remoteSpeechClient?.stop()
        remoteSpeechClient = null
        audioProvider = null
        vadWhisperEngine?.close()
        vadWhisperEngine = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
