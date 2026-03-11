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

        // 서버 다운 감지 — 3회 연속 실패 시 2분간 호출 억제
        private const val BACKOFF_DURATION_MS = 2 * 60 * 1000L
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

    // 서버 다운 감지 — 연속 실패 시 호출 억제 (불필요한 타임아웃 방지)
    @Volatile private var consecutiveFailures: Int = 0
    @Volatile private var lastFailureTime: Long = 0
    private val isServerDown: Boolean
        get() = consecutiveFailures >= 3 &&
                (System.currentTimeMillis() - lastFailureTime) < BACKOFF_DURATION_MS

    private var eventJob: Job? = null
    private var contextPullJob: Job? = null
    private var forecastPullJob: Job? = null
    private var flushJob: Job? = null

    private val serverUrl: String
        get() = syncConfig.serverUrl.takeIf { it.isNotBlank() }
            ?.replace(":8090", ":8091")  // backup → orchestrator
            ?: DEFAULT_URL

    /** 서버 호출 성공 시 — 연속 실패 카운터 리셋 */
    private fun onServerSuccess() {
        if (consecutiveFailures > 0) {
            Log.i(TAG, "서버 복구 감지 (이전 연속 실패: $consecutiveFailures)")
        }
        consecutiveFailures = 0
        isConnected = true
    }

    /** 서버 호출 실패 시 — 연속 실패 카운터 증가, 3회 이상이면 2분간 억제 */
    private fun onServerFailure(endpoint: String, code: Int = 0, error: String = "") {
        consecutiveFailures++
        lastFailureTime = System.currentTimeMillis()
        isConnected = false
        val reason = if (code > 0) "HTTP $code" else error.take(80)
        if (consecutiveFailures == 3) {
            Log.w(TAG, "서버 다운 판정: $endpoint ($reason) — ${BACKOFF_DURATION_MS / 1000}초간 호출 억제")
        } else {
            Log.w(TAG, "서버 실패 [$consecutiveFailures]: $endpoint ($reason)")
        }
    }

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
        if (isServerDown) return  // 서버 다운 시 불필요한 타임아웃 방지

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
                    if (it.isSuccessful) {
                        onServerSuccess()
                    } else {
                        onServerFailure("/v1/episode", it.code)
                    }
                }
            } catch (e: Exception) {
                onServerFailure("/v1/episode", error = e.message ?: "unknown")
                break  // 첫 실패 시 나머지 배치 중단 (서버 다운이면 계속 시도 무의미)
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
        if (isServerDown) return
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
        if (isServerDown) return

        val request = Request.Builder()
            .url("$serverUrl/v1/storyteller/context")
            .addHeader("Authorization", "Bearer $API_KEY")
            .get()
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            response.use {
                if (it.isSuccessful) {
                    val body = it.body?.string() ?: return
                    storytellerContext = JSONObject(body)
                    onServerSuccess()
                    Log.d(TAG, "이야기꾼 컨텍스트 갱신 (${body.length}B)")
                } else {
                    onServerFailure("/v1/storyteller/context", it.code)
                }
            }
        } catch (e: Exception) {
            onServerFailure("/v1/storyteller/context", error = e.message ?: "unknown")
        }
    }

    // ─── Pull: 행동 예측 ───

    private fun pullForecast() {
        if (isServerDown) return

        val request = Request.Builder()
            .url("$serverUrl/v1/predict/forecast")
            .addHeader("Authorization", "Bearer $API_KEY")
            .get()
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            response.use {
                if (it.isSuccessful) {
                    val body = it.body?.string() ?: return
                    latestForecast = JSONObject(body)
                    onServerSuccess()
                    Log.d(TAG, "예측 갱신 완료")
                } else {
                    onServerFailure("/v1/predict/forecast", it.code)
                }
            }
        } catch (e: Exception) {
            onServerFailure("/v1/predict/forecast", error = e.message ?: "unknown")
        }
    }

    // ─── Pull: Mem0 관련 기억 검색 ───

    fun searchMemory(query: String, limit: Int = 5): JSONArray? {
        if (isServerDown) return null
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

    // ─── v2: 이야기꾼 Tick ───

    /**
     * 이야기꾼 한 사이클 실행 — 서버 LangGraph 그래프
     * 에피소드 + 상황 정보 → 내러티브/질문/전문가 위임 결과 반환
     *
     * @return JSONObject { action, narrative_beat?, question? }
     */
    fun storytellerTick(
        situation: String,
        heartRate: Float? = null,
        visiblePeople: List<String> = emptyList(),
        currentEmotion: String? = null,
        userSpeech: String? = null,
    ): JSONObject? {
        if (isServerDown) return null

        val recentEpisodes: List<JSONObject>
        synchronized(bufferLock) {
            recentEpisodes = episodeBuffer.toList()
        }

        val payload = JSONObject().apply {
            put("situation", situation)
            put("episodes", JSONArray(recentEpisodes.map { it }))
            if (heartRate != null) put("heart_rate", heartRate.toDouble())
            put("visible_people", JSONArray(visiblePeople))
            if (currentEmotion != null) put("current_emotion", currentEmotion)
            if (userSpeech != null) put("user_speech", userSpeech)
        }

        return try {
            val request = Request.Builder()
                .url("$serverUrl/v2/storyteller/tick")
                .addHeader("Authorization", "Bearer $API_KEY")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    onServerSuccess()
                    val body = response.body?.string() ?: return null
                    JSONObject(body)
                } else {
                    onServerFailure("/v2/storyteller/tick", response.code)
                    null
                }
            }
        } catch (e: Exception) {
            onServerFailure("/v2/storyteller/tick", error = e.message ?: "unknown")
            null
        }
    }

    // ─── v2: 전문가 위임 ───

    /**
     * 단일 전문가에게 분석 위임
     *
     * @param domain 전문가 도메인 (behavior/lesson/health/social/planning/crisis)
     * @param query 요청 내용
     * @param context 추가 컨텍스트
     * @return JSONObject { expert_name, domain, insight, latency_ms }
     */
    fun delegateExpert(
        domain: String,
        query: String,
        context: Map<String, String>? = null,
    ): JSONObject? {
        if (isServerDown) return null

        val payload = JSONObject().apply {
            put("domain", domain)
            put("query", query)
            if (context != null) put("context", JSONObject(context))
        }

        return try {
            val request = Request.Builder()
                .url("$serverUrl/v2/expert/delegate")
                .addHeader("Authorization", "Bearer $API_KEY")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    onServerSuccess()
                    val body = response.body?.string() ?: return null
                    JSONObject(body)
                } else {
                    onServerFailure("/v2/expert/delegate", response.code)
                    null
                }
            }
        } catch (e: Exception) {
            onServerFailure("/v2/expert/delegate", error = e.message ?: "unknown")
            null
        }
    }

    // ─── v2: Multi-Agent Debate ───

    /**
     * 전문가 토론 실행 — 중요한 결정에서 사용
     * 2~4명 전문가가 라운드 기반 토론 → 합의 도출
     *
     * @param topic 토론 주제
     * @param domains 참여 전문가 도메인 (최소 2, 최대 4)
     * @param context 추가 컨텍스트
     * @param maxRounds 최대 라운드 (1=독립분석, 2=+비판, 3=+합의)
     * @return JSONObject { consensus_level, summary, action_plan, dissent, rounds }
     */
    fun runDebate(
        topic: String,
        domains: List<String>,
        context: Map<String, String>? = null,
        maxRounds: Int = 3,
    ): JSONObject? {
        if (isServerDown) return null

        val payload = JSONObject().apply {
            put("topic", topic)
            put("domains", JSONArray(domains))
            if (context != null) put("context", JSONObject(context))
            put("max_rounds", maxRounds)
        }

        return try {
            val request = Request.Builder()
                .url("$serverUrl/v2/debate")
                .addHeader("Authorization", "Bearer $API_KEY")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()

            // 토론은 오래 걸릴 수 있음 (30~120초)
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    onServerSuccess()
                    val body = response.body?.string() ?: return null
                    JSONObject(body)
                } else {
                    onServerFailure("/v2/debate", response.code)
                    null
                }
            }
        } catch (e: Exception) {
            onServerFailure("/v2/debate", error = e.message ?: "unknown")
            null
        }
    }

    // ─── v2: LLM 풀 상태 ───

    /**
     * 분산 LLM 서버 풀 상태 확인
     * @return JSONObject { total_servers, healthy, servers: [...] }
     */
    fun getLlmPoolStatus(): JSONObject? {
        if (isServerDown) return null
        return try {
            val request = Request.Builder()
                .url("$serverUrl/v2/llm/status")
                .addHeader("Authorization", "Bearer $API_KEY")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return null
                    JSONObject(body)
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "LLM 풀 상태 조회 실패: ${e.message}")
            null
        }
    }

    // ─── 상태 조회 ───

    fun checkHealth(): JSONObject? {
        // health 체크는 서버 다운 시에도 복구 확인을 위해 항상 실행
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
                    onServerSuccess()
                    JSONObject(body)
                } else {
                    onServerFailure("/health", it.code)
                    null
                }
            }
        } catch (e: Exception) {
            onServerFailure("/health", error = e.message ?: "unknown")
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
