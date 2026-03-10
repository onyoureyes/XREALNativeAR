package com.xreal.nativear.sync

import android.util.Log
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException

/**
 * OrchestratorClient — Fold ↔ PC Orchestrator(:8091) 양방향 통신
 *
 * ## Push (Fold → PC)
 * - 에피소드 이벤트 (vision, speech, sensor, interaction)
 * - 메모리 저장 요청
 * - Mem0 사실 추가
 *
 * ## Pull (PC → Fold)
 * - 이야기꾼 통합 컨텍스트 (에피소드+기억+예측+인사이트)
 * - 행동 예측 (NeuralProphet/TFT)
 * - 디지털 트윈 예측 (회복/페이스/부상)
 *
 * ## 데이터 흐름
 * 1. EventBus에서 PerceptionEvent/InputEvent 수신
 * 2. 에피소드로 변환 → POST /v1/episode
 * 3. 2시간분 L1 버퍼는 Fold 로컬에 유지
 * 4. 주기적으로 /v1/storyteller/context pull → 이야기꾼에 공급
 */
class OrchestratorClient(
    private val httpClient: OkHttpClient,
    private val eventBus: GlobalEventBus,
    private val syncConfig: BackupSyncConfig
) {
    companion object {
        private const val TAG = "OrchestratorClient"
        // Orchestrator URL (Tailscale, port 8091)
        private const val DEFAULT_URL = "http://100.101.127.124:8091"
        private const val API_KEY = "3ztg3rzTith-vViCK7FLe5SQNUqqACKTojyDX1oiNe0"

        // 주기
        private const val CONTEXT_PULL_INTERVAL_MS = 5 * 60 * 1000L  // 5분마다 컨텍스트 갱신
        private const val FORECAST_PULL_INTERVAL_MS = 30 * 60 * 1000L  // 30분마다 예측 갱신
        private const val INITIAL_DELAY_MS = 30_000L  // 30초 (앱 초기화 후)

        // 에피소드 배치 전송
        private const val EPISODE_BATCH_SIZE = 10
        private const val EPISODE_FLUSH_INTERVAL_MS = 60_000L  // 1분마다 플러시
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // 에피소드 버퍼 (배치 전송용)
    private val episodeBuffer = mutableListOf<JSONObject>()
    private val bufferLock = Any()

    // 캐싱된 컨텍스트 (이야기꾼이 참조)
    @Volatile var storytellerContext: JSONObject? = null
        private set
    @Volatile var latestForecast: JSONObject? = null
        private set
    @Volatile var isConnected: Boolean = false
        private set

    private var eventJob: Job? = null
    private var contextPullJob: Job? = null
    private var forecastPullJob: Job? = null
    private var flushJob: Job? = null

    private val serverUrl: String
        get() = syncConfig.serverUrl.takeIf { it.isNotBlank() }
            ?.replace(":8090", ":8091")  // backup → orchestrator
            ?: DEFAULT_URL

    // ─── Lifecycle ───

    fun start() {
        // 1. EventBus 구독 — 에피소드 수집
        eventJob = scope.launch {
            eventBus.events.collect { event ->
                try {
                    processEvent(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "이벤트 처리 실패: ${e.message}")
                }
            }
        }

        // 2. 주기적 에피소드 플러시
        flushJob = scope.launch {
            delay(INITIAL_DELAY_MS)
            while (isActive) {
                try {
                    flushEpisodes()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "에피소드 플러시 실패: ${e.message}")
                }
                delay(EPISODE_FLUSH_INTERVAL_MS)
            }
        }

        // 3. 주기적 컨텍스트 pull
        contextPullJob = scope.launch {
            delay(INITIAL_DELAY_MS)
            while (isActive) {
                try {
                    pullStorytellerContext()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "컨텍스트 pull 실패: ${e.message}")
                    isConnected = false
                }
                delay(CONTEXT_PULL_INTERVAL_MS)
            }
        }

        // 4. 주기적 예측 pull
        forecastPullJob = scope.launch {
            delay(INITIAL_DELAY_MS + 10_000)  // 컨텍스트보다 10초 늦게
            while (isActive) {
                try {
                    pullForecast()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "예측 pull 실패: ${e.message}")
                }
                delay(FORECAST_PULL_INTERVAL_MS)
            }
        }

        Log.i(TAG, "OrchestratorClient 시작 (서버: $serverUrl)")
    }

    fun stop() {
        flushEpisodesSync()  // 남은 에피소드 전송
        eventJob?.cancel()
        flushJob?.cancel()
        contextPullJob?.cancel()
        forecastPullJob?.cancel()
        scope.cancel()
        Log.i(TAG, "OrchestratorClient 중지")
    }

    // ─── Push: 에피소드 전송 ───

    private fun processEvent(event: XRealEvent) {
        val episode = when (event) {
            is XRealEvent.PerceptionEvent.ObjectsDetected -> JSONObject().apply {
                put("event_type", "vision")
                put("content", "객체 감지: ${event.results.joinToString { "${it.label}(${(it.confidence * 100).toInt()}%)" }}")
                put("timestamp", System.currentTimeMillis() / 1000.0)
            }
            is XRealEvent.PerceptionEvent.OcrDetected -> JSONObject().apply {
                put("event_type", "vision")
                put("content", "OCR: ${event.results.joinToString { it.text }.take(500)}")
                put("timestamp", System.currentTimeMillis() / 1000.0)
                put("metadata", JSONObject().put("source", "ocr"))
            }
            is XRealEvent.InputEvent.VoiceCommand -> JSONObject().apply {
                put("event_type", "speech")
                put("content", event.text)
                put("timestamp", System.currentTimeMillis() / 1000.0)
            }
            is XRealEvent.InputEvent.EnrichedVoiceCommand -> JSONObject().apply {
                put("event_type", "speech")
                put("content", event.text)
                put("timestamp", System.currentTimeMillis() / 1000.0)
                put("metadata", JSONObject().apply {
                    put("speaker", event.speaker)
                    put("emotion", event.emotion)
                    put("emotion_score", event.emotionScore)
                })
            }
            is XRealEvent.PerceptionEvent.PersonIdentified -> JSONObject().apply {
                put("event_type", "interaction")
                put("content", "사람 인식: ${event.personName ?: "미등록"} (${(event.confidence * 100).toInt()}%)")
                put("timestamp", System.currentTimeMillis() / 1000.0)
                put("metadata", JSONObject().apply {
                    put("person", event.personName)
                    put("person_id", event.personId)
                })
            }
            is XRealEvent.PerceptionEvent.LocationUpdated -> JSONObject().apply {
                put("event_type", "sensor")
                put("content", "위치: ${event.lat},${event.lon}")
                put("timestamp", System.currentTimeMillis() / 1000.0)
                put("metadata", JSONObject().apply {
                    put("latitude", event.lat)
                    put("longitude", event.lon)
                    put("address", event.address)
                })
            }
            is XRealEvent.PerceptionEvent.WatchHeartRate -> JSONObject().apply {
                put("event_type", "sensor")
                put("content", "심박수: ${event.bpm.toInt()}bpm")
                put("timestamp", System.currentTimeMillis() / 1000.0)
                put("metadata", JSONObject().put("hr_bpm", event.bpm))
            }
            is XRealEvent.SystemEvent.SituationChanged -> JSONObject().apply {
                put("event_type", "context")
                put("content", "상황 전환: ${event.oldSituation.name} → ${event.newSituation.name}")
                put("timestamp", System.currentTimeMillis() / 1000.0)
                put("metadata", JSONObject().apply {
                    put("old_situation", event.oldSituation.name)
                    put("new_situation", event.newSituation.name)
                    put("confidence", event.confidence)
                })
            }
            else -> null
        } ?: return

        synchronized(bufferLock) {
            episodeBuffer.add(episode)
            if (episodeBuffer.size >= EPISODE_BATCH_SIZE) {
                scope.launch { flushEpisodes() }
            }
        }
    }

    private fun flushEpisodes() {
        val batch: List<JSONObject>
        synchronized(bufferLock) {
            if (episodeBuffer.isEmpty()) return
            batch = episodeBuffer.toList()
            episodeBuffer.clear()
        }

        for (episode in batch) {
            try {
                val request = Request.Builder()
                    .url("$serverUrl/v1/episode")
                    .addHeader("Authorization", "Bearer $API_KEY")
                    .post(episode.toString().toRequestBody(jsonMedia))
                    .build()

                val response = httpClient.newCall(request).execute()
                response.use {
                    if (!it.isSuccessful) {
                        Log.w(TAG, "에피소드 전송 실패: ${it.code}")
                    }
                }
                isConnected = true
            } catch (e: Exception) {
                Log.w(TAG, "에피소드 전송 에러: ${e.message}")
                isConnected = false
                // 실패한 에피소드를 다시 버퍼에 넣지 않음 (L1 로컬에 이미 있으므로)
            }
        }

        if (batch.isNotEmpty()) {
            Log.d(TAG, "에피소드 ${batch.size}건 전송 완료")
        }
    }

    private fun flushEpisodesSync() {
        try {
            flushEpisodes()
        } catch (e: Exception) {
            Log.w(TAG, "종료 시 플러시 실패: ${e.message}")
        }
    }

    // ─── Push: Mem0 사실 추가 ───

    fun addMemory(content: String, userId: String = "teacher", eventType: String = "observation") {
        scope.launch {
            try {
                val payload = JSONObject().apply {
                    put("content", content)
                    put("user_id", userId)
                    put("event_type", eventType)
                }
                val request = Request.Builder()
                    .url("$serverUrl/v1/mem0/add")
                    .addHeader("Authorization", "Bearer $API_KEY")
                    .post(payload.toString().toRequestBody(jsonMedia))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "Mem0 사실 추가: ${content.take(50)}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Mem0 추가 실패: ${e.message}")
            }
        }
    }

    // ─── Pull: 이야기꾼 컨텍스트 ───

    private fun pullStorytellerContext() {
        val request = Request.Builder()
            .url("$serverUrl/v1/storyteller/context")
            .addHeader("Authorization", "Bearer $API_KEY")
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        response.use {
            if (it.isSuccessful) {
                val body = it.body?.string() ?: return
                storytellerContext = JSONObject(body)
                isConnected = true
                Log.d(TAG, "이야기꾼 컨텍스트 갱신 (${body.length}B)")
            } else {
                Log.w(TAG, "컨텍스트 pull 실패: ${it.code}")
            }
        }
    }

    // ─── Pull: 행동 예측 ───

    private fun pullForecast() {
        val request = Request.Builder()
            .url("$serverUrl/v1/predict/forecast")
            .addHeader("Authorization", "Bearer $API_KEY")
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        response.use {
            if (it.isSuccessful) {
                val body = it.body?.string() ?: return
                latestForecast = JSONObject(body)
                Log.d(TAG, "예측 갱신 완료")
            }
        }
    }

    // ─── Pull: Mem0 관련 기억 검색 ───

    fun searchMemory(query: String, limit: Int = 5): JSONArray? {
        return try {
            val payload = JSONObject().apply {
                put("query", query)
                put("user_id", "teacher")
                put("limit", limit)
            }
            val request = Request.Builder()
                .url("$serverUrl/v1/mem0/search")
                .addHeader("Authorization", "Bearer $API_KEY")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()

            val response = httpClient.newCall(request).execute()
            response.use {
                if (it.isSuccessful) {
                    val body = it.body?.string() ?: return null
                    JSONObject(body).optJSONArray("results")
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Mem0 검색 실패: ${e.message}")
            null
        }
    }

    // ─── 상태 조회 ───

    fun checkHealth(): JSONObject? {
        return try {
            val request = Request.Builder()
                .url("$serverUrl/health")
                .addHeader("Authorization", "Bearer $API_KEY")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            response.use {
                if (it.isSuccessful) {
                    val body = it.body?.string() ?: return null
                    isConnected = true
                    JSONObject(body)
                } else {
                    isConnected = false
                    null
                }
            }
        } catch (e: Exception) {
            isConnected = false
            null
        }
    }

    /**
     * 이야기꾼이 참조할 통합 요약 텍스트
     * storytellerContext에서 핵심 정보만 추출
     */
    fun getContextSummary(): String {
        val ctx = storytellerContext ?: return "Orchestrator 연결 대기 중"
        val sections = ctx.optJSONObject("sections") ?: return "컨텍스트 섹션 없음"

        val parts = mutableListOf<String>()

        // 예측 인사이트
        val forecast = sections.optJSONObject("forecast")
        if (forecast != null) {
            val insights = forecast.optJSONArray("insights")
            if (insights != null && insights.length() > 0) {
                for (i in 0 until insights.length()) {
                    parts.add(insights.getString(i))
                }
            }
        }

        // 아침 브리핑
        val briefing = sections.optString("morning_briefing", "")
        if (briefing.isNotBlank()) {
            parts.add("[아침 브리핑] ${briefing.take(300)}")
        }

        // 관련 기억
        val memories = sections.optJSONArray("relevant_memories")
        if (memories != null && memories.length() > 0) {
            val memSummary = (0 until memories.length().coerceAtMost(3))
                .map { memories.getJSONObject(it).optString("memory", "") }
                .filter { it.isNotBlank() }
                .joinToString("; ")
            if (memSummary.isNotBlank()) {
                parts.add("[관련 기억] $memSummary")
            }
        }

        return if (parts.isEmpty()) "인사이트 없음" else parts.joinToString("\n")
    }
}
