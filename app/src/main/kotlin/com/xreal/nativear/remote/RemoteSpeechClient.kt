package com.xreal.nativear.remote

import android.util.Log
import com.xreal.nativear.policy.PolicyReader
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * RemoteSpeechClient — PC 음성 처리 서버 WebSocket 클라이언트.
 *
 * 엣지 음성 엔진(SenseVoice/Whisper)이 죽어 있을 때 폴백으로 사용.
 * Galaxy Fold 마이크 오디오를 PC(RX570 + Whisper-medium Vulkan)로 스트리밍,
 * STT + 화자분리 + 감정분석 + 임베딩 결과를 수신.
 *
 * ## 서버 스펙 (Clova Note Clone API)
 * - WebSocket: ws://{host}:{port}/ws/stream
 * - POST: http://{host}:{port}/transcribe (파일 업로드)
 * - 모델: ggml-medium (Whisper Vulkan RX570)
 * - 임베딩: 256-dim float32
 *
 * ## 프로토콜 (WebSocket)
 * - Client → Server: PCM 16kHz 16-bit LE mono 바이너리 프레임
 * - Server → Client: JSON 텍스트 프레임
 *   - partial: {"type":"partial", "text":"...", "speaker":"...", "emotion":"..."}
 *   - final:   {"type":"final", "text":"...", "speaker":"...", "emotion":"...",
 *               "language":"...", "embedding":[float...], "confidence":0.95}
 *
 * ## 정책 키
 * - speech_server.host (기본 "100.121.84.80")
 * - speech_server.port (기본 7860)
 * - speech_server.enabled (기본 true)
 * - speech_server.auto_fallback (기본 true — 엣지 STT 실패 시 자동 전환)
 */
class RemoteSpeechClient(
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "RemoteSpeechClient"
        private const val RECONNECT_DELAY_MS = 3000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val HEALTH_CHECK_INTERVAL_MS = 30_000L
        private const val SEND_CHUNK_MS = 100 // 100ms 단위로 전송 (1600 samples)

        private val HOST: String get() =
            PolicyReader.getString("speech_server.host", "100.121.84.80")
        private val PORT: Int get() =
            PolicyReader.getInt("speech_server.port", 7860)
        private val ENABLED: Boolean get() =
            PolicyReader.getBoolean("speech_server.enabled", true)
    }

    // ── 상태 ──
    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isStreaming = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    @Volatile private var serverAvailable = false
    private var scope: CoroutineScope? = null
    private var healthJob: Job? = null

    // ── 콜백 ──
    private var onTranscript: ((RemoteSpeechResult) -> Unit)? = null
    private var onPartial: ((String, String?, String?) -> Unit)? = null // text, speaker, emotion
    private var onConnectionChanged: ((Boolean) -> Unit)? = null
    private var onEmbedding: ((String, FloatArray) -> Unit)? = null // speaker, 256-dim vec

    /** STT 결과 (final 메시지) */
    data class RemoteSpeechResult(
        val text: String,
        val speaker: String?,
        val emotion: String?,
        val language: String?,
        val embedding: FloatArray?,    // 256-dim speaker embedding
        val confidence: Float = 0f
    )

    // ── 설정 ──

    fun setOnTranscriptListener(l: (RemoteSpeechResult) -> Unit) { onTranscript = l }
    fun setOnPartialListener(l: (String, String?, String?) -> Unit) { onPartial = l }
    fun setOnConnectionChangedListener(l: (Boolean) -> Unit) { onConnectionChanged = l }
    fun setOnEmbeddingListener(l: (String, FloatArray) -> Unit) { onEmbedding = l }

    // ── 생명주기 ──

    fun start(coroutineScope: CoroutineScope) {
        if (!ENABLED) {
            Log.i(TAG, "음성 서버 비활성 (speech_server.enabled=false)")
            return
        }
        scope = coroutineScope
        checkServerHealth()
        startHealthCheckLoop()
    }

    fun stop() {
        healthJob?.cancel()
        healthJob = null
        disconnect()
        scope = null
    }

    /** 서버 사용 가능 여부 */
    fun isAvailable(): Boolean = serverAvailable && ENABLED

    /** WebSocket 연결 상태 */
    fun isConnected(): Boolean = isConnected.get()

    // ── WebSocket 연결 ──

    fun connect() {
        if (!ENABLED || isConnected.get()) return

        val wsUrl = "ws://$HOST:$PORT/ws/stream"
        Log.i(TAG, "WebSocket 연결 시도: $wsUrl")

        val wsClient = httpClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket은 무한 대기
            .pingInterval(15, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(wsUrl).build()

        webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket 연결됨")
                isConnected.set(true)
                reconnectAttempts.set(0)
                onConnectionChanged?.invoke(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // 바이너리 응답 (임베딩 등) — 현재 JSON 텍스트만 사용
                Log.d(TAG, "바이너리 메시지 수신: ${bytes.size} bytes")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket 닫힘: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleDisconnect("닫힘: $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handleDisconnect("실패: ${t.message}")
            }
        })
    }

    fun disconnect() {
        isStreaming.set(false)
        try {
            webSocket?.close(1000, "client disconnect")
        } catch (e: Exception) {
            Log.w(TAG, "WebSocket close 오류: ${e.message}")
        }
        webSocket = null
        if (isConnected.getAndSet(false)) {
            onConnectionChanged?.invoke(false)
        }
    }

    // ── 오디오 스트리밍 ──

    /**
     * PCM 오디오 청크를 서버로 전송.
     * AudioAnalysisService에서 VAD 감지 후 호출.
     *
     * @param pcm16kHz 16kHz, 16-bit signed LE, mono PCM
     */
    fun sendAudio(pcm16kHz: ShortArray) {
        if (!isConnected.get() || pcm16kHz.isEmpty()) return

        val buffer = ByteBuffer.allocate(pcm16kHz.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcm16kHz) {
            buffer.putShort(sample)
        }
        val bytes = buffer.array().toByteString()

        try {
            webSocket?.send(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "오디오 전송 실패: ${e.message}")
        }
    }

    /**
     * 스트리밍 세션 시작 신호.
     * JSON 텍스트 프레임으로 세션 메타데이터 전송.
     */
    fun startStreaming(language: String = "auto") {
        if (!isConnected.get()) {
            connect()
            // 연결 완료 후 재시도 필요 — 비동기
            return
        }
        isStreaming.set(true)
        val startMsg = JSONObject().apply {
            put("type", "start")
            put("language", language)
            put("sample_rate", 16000)
            put("encoding", "pcm_s16le")
        }
        webSocket?.send(startMsg.toString())
        Log.i(TAG, "스트리밍 시작 (lang=$language)")
    }

    /**
     * 스트리밍 세션 종료 신호.
     * 서버에 final 결과를 요청.
     */
    fun stopStreaming() {
        if (!isStreaming.getAndSet(false)) return
        val stopMsg = JSONObject().apply {
            put("type", "stop")
        }
        try {
            webSocket?.send(stopMsg.toString())
        } catch (_: Exception) { }
        Log.i(TAG, "스트리밍 종료")
    }

    // ── 파일 업로드 (비스트리밍) ──

    /**
     * 녹음된 오디오 파일을 POST /transcribe로 전송.
     * 긴 오디오 (>30초)이거나 배치 처리 시 사용.
     *
     * @return RemoteSpeechResult or null
     */
    suspend fun transcribeFile(audioBytes: ByteArray, language: String = "auto"): RemoteSpeechResult? {
        if (!serverAvailable) return null

        return withContext(Dispatchers.IO) {
            try {
                val url = "http://$HOST:$PORT/transcribe"
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file", "audio.wav",
                        audioBytes.toRequestBody("application/octet-stream".toMediaType())
                    )
                    .addFormDataPart("language", language)
                    .build()

                val request = Request.Builder().url(url).post(requestBody).build()
                val response = httpClient.newBuilder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS) // 긴 오디오 처리
                    .build()
                    .newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.w(TAG, "transcribe 실패: HTTP ${response.code}")
                    response.close()
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                response.close()
                parseTranscribeResponse(body)
            } catch (e: Exception) {
                Log.w(TAG, "transcribeFile 오류: ${e.message}")
                null
            }
        }
    }

    // ── 서버 메시지 처리 ──

    private fun handleServerMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "unknown")

            when (type) {
                "partial" -> {
                    val partialText = json.optString("text", "")
                    val speaker = json.optString("speaker", null)
                    val emotion = json.optString("emotion", null)
                    if (partialText.isNotBlank()) {
                        onPartial?.invoke(partialText, speaker, emotion)
                    }
                }
                "final" -> {
                    val result = RemoteSpeechResult(
                        text = json.optString("text", ""),
                        speaker = json.optString("speaker", null),
                        emotion = json.optString("emotion", null),
                        language = json.optString("language", null),
                        embedding = parseEmbedding(json),
                        confidence = json.optDouble("confidence", 0.0).toFloat()
                    )
                    if (result.text.isNotBlank()) {
                        Log.i(TAG, "[${result.speaker ?: "?"}] ${result.text} (${result.emotion}, ${result.language})")
                        onTranscript?.invoke(result)
                    }

                    // 임베딩 별도 콜백
                    val emb = result.embedding
                    val spk = result.speaker
                    if (emb != null && spk != null) {
                        onEmbedding?.invoke(spk, emb)
                    }
                }
                "error" -> {
                    Log.e(TAG, "서버 에러: ${json.optString("message", "unknown")}")
                }
                else -> {
                    Log.d(TAG, "알 수 없는 메시지 타입: $type")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "메시지 파싱 오류: ${e.message}, raw: ${text.take(200)}")
        }
    }

    private fun parseEmbedding(json: JSONObject): FloatArray? {
        val arr = json.optJSONArray("embedding") ?: return null
        return FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
    }

    private fun parseTranscribeResponse(body: String): RemoteSpeechResult? {
        return try {
            val json = JSONObject(body)
            // /transcribe 응답은 세션+세그먼트 구조일 수 있음
            val text = json.optString("text", "")
            if (text.isBlank()) return null
            RemoteSpeechResult(
                text = text,
                speaker = json.optString("speaker", null),
                emotion = json.optString("emotion", null),
                language = json.optString("language", null),
                embedding = parseEmbedding(json),
                confidence = json.optDouble("confidence", 0.0).toFloat()
            )
        } catch (e: Exception) {
            Log.w(TAG, "transcribe 응답 파싱 오류: ${e.message}")
            null
        }
    }

    // ── 서버 상태 체크 ──

    private fun checkServerHealth() {
        scope?.launch(Dispatchers.IO) {
            try {
                val url = "http://$HOST:$PORT/"
                val request = Request.Builder().url(url).build()
                val response = httpClient.newBuilder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()
                    .newCall(request).execute()

                val wasAvailable = serverAvailable
                serverAvailable = response.isSuccessful
                response.close()

                if (serverAvailable && !wasAvailable) {
                    Log.i(TAG, "음성 서버 연결됨: $HOST:$PORT")
                } else if (!serverAvailable && wasAvailable) {
                    Log.w(TAG, "음성 서버 연결 끊김")
                }
            } catch (e: Exception) {
                if (serverAvailable) {
                    Log.w(TAG, "음성 서버 헬스체크 실패: ${e.message}")
                }
                serverAvailable = false
            }
        }
    }

    private fun startHealthCheckLoop() {
        healthJob = scope?.launch(Dispatchers.IO) {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                checkServerHealth()
            }
        }
    }

    // ── 재연결 ──

    private fun handleDisconnect(reason: String) {
        val wasConnected = isConnected.getAndSet(false)
        isStreaming.set(false)

        if (wasConnected) {
            Log.w(TAG, "WebSocket 연결 끊김: $reason")
            onConnectionChanged?.invoke(false)
        }

        // 자동 재연결
        val attempts = reconnectAttempts.incrementAndGet()
        if (attempts <= MAX_RECONNECT_ATTEMPTS && serverAvailable) {
            scope?.launch {
                val delay = RECONNECT_DELAY_MS * attempts
                Log.i(TAG, "재연결 시도 $attempts/$MAX_RECONNECT_ATTEMPTS (${delay}ms 후)")
                delay(delay)
                if (!isConnected.get()) connect()
            }
        } else {
            Log.w(TAG, "재연결 포기 (${attempts}회 시도)")
        }
    }

    // ── 진단 ──

    fun getStatus(): String = buildString {
        append("SpeechServer: ")
        append(if (serverAvailable) "UP" else "DOWN")
        append(" ($HOST:$PORT), ")
        append(if (isConnected.get()) "WS:CONNECTED" else "WS:DISCONNECTED")
        append(if (isStreaming.get()) ", STREAMING" else "")
    }
}
