package com.xreal.nativear.memory

import android.util.Log
import com.xreal.nativear.ILocationService
import com.xreal.nativear.SceneDatabase
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.memory.api.IMemoryStore
import com.xreal.nativear.memory.api.MemoryRecord
import com.xreal.nativear.spatial.SpatialAnchorManager
import com.xreal.nativear.spatial.SpatialUIManager
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * ProactiveMemorySurfacer — 관련 과거 기억을 자동으로 추천하는 시스템.
 *
 * ## 트리거 소스 (5가지)
 *
 * | 트리거 | 설명 | 응답 속도 |
 * |--------|------|-----------|
 * | **DeepFocus** | 앵커 2초 이상 주시 | 즉시 (캐시) / 1-2초 (AI) |
 * | **장소 재방문** | PlaceRecognitionManager 매칭 | 2-3초 |
 * | **시각 유사성** | 현재 프레임 임베딩 ↔ 과거 씬 매칭 | 5-10초 간격 |
 * | **시간 패턴** | 같은 시간대 + 요일의 과거 기억 | 배경 (30초) |
 * | **대화 컨텍스트** | Whisper 전사문에서 키워드 추출 | 실시간 |
 *
 * ## 메모리 랭킹 공식
 * ```
 * score = 0.3 × spatial_relevance
 *       + 0.25 × temporal_relevance
 *       + 0.25 × semantic_relevance
 *       + 0.1 × emotional_weight
 *       + 0.1 × recency_decay
 * ```
 *
 * ## 표시 방식
 * - 앵커 포커스 시: SpatialUIManager.attachContentPanel() — 앵커 옆 패널
 * - 장소 재방문 시: HUD 상단 알림 (DrawElement)
 * - 배경 분석: DebugLog 이벤트 (Strategist에서 소비)
 *
 * ## 중복 방지
 * - 같은 메모리 ID → 30분 쿨다운
 * - 같은 앵커 → 60초 쿨다운
 * - 최대 동시 표시: 2개 패널
 */
class ProactiveMemorySurfacer(
    private val eventBus: GlobalEventBus,
    private val memoryStore: IMemoryStore,
    private val sceneDatabase: SceneDatabase,
    private val locationService: ILocationService,
    private val spatialUIManager: SpatialUIManager,
    private val spatialAnchorManager: SpatialAnchorManager
) {
    companion object {
        private const val TAG = "ProactiveMemory"

        // ── 쿨다운 ──
        private const val MEMORY_COOLDOWN_MS = 30 * 60 * 1000L  // 같은 메모리 30분
        private const val ANCHOR_COOLDOWN_MS = 60 * 1000L        // 같은 앵커 60초
        private const val PLACE_COOLDOWN_MS = 5 * 60 * 1000L     // 같은 장소 5분
        private const val VISUAL_SCAN_INTERVAL_MS = 10_000L       // 시각 스캔 10초

        // ── 랭킹 가중치 ──
        private const val W_SPATIAL = 0.30f
        private const val W_TEMPORAL = 0.25f
        private const val W_SEMANTIC = 0.25f
        private const val W_EMOTION = 0.10f
        private const val W_RECENCY = 0.10f

        // ── 표시 제한 ──
        private const val MAX_PANEL_DISPLAY = 2
        private const val MAX_RESULTS_PER_QUERY = 5
        private const val MIN_RELEVANCE_SCORE = 0.3f
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── 쿨다운 추적 ──
    private val surfacedMemoryIds = ConcurrentHashMap<Long, Long>()  // memoryId → lastSurfacedAt
    private val surfacedAnchorIds = ConcurrentHashMap<String, Long>() // anchorId → lastSurfacedAt
    private val surfacedPlaceIds = ConcurrentHashMap<Long, Long>()   // placeId → lastSurfacedAt

    // ── 시각 스캔 ──
    @Volatile private var lastVisualScanTime = 0L
    @Volatile private var latestVisualEmbedding: ByteArray? = null

    // ── 활성 상태 ──
    @Volatile var isActive = false
        private set

    // ══════════════════════════════════════════════
    //  Lifecycle
    // ══════════════════════════════════════════════

    fun start() {
        isActive = true
        subscribeToTriggers()
        startPeriodicScans()
        Log.i(TAG, "ProactiveMemorySurfacer started — 5 trigger sources active")
    }

    fun stop() {
        isActive = false
        scope.cancel()
        surfacedMemoryIds.clear()
        surfacedAnchorIds.clear()
        surfacedPlaceIds.clear()
        Log.i(TAG, "ProactiveMemorySurfacer stopped")
    }

    // ══════════════════════════════════════════════
    //  Trigger Subscriptions
    // ══════════════════════════════════════════════

    private fun subscribeToTriggers() {
        scope.launch {
            eventBus.events.collect { event ->
                try {
                    if (!isActive) return@collect

                    when (event) {
                        // 트리거 1: 딥 포커스 (2초 주시)
                        is XRealEvent.PerceptionEvent.DeepFocusTriggered -> {
                            handleDeepFocus(event.anchorId, event.timestamp)
                        }

                        // 트리거 2: 시각 임베딩 (배경 매칭용 캐시)
                        is XRealEvent.PerceptionEvent.VisualEmbedding -> {
                            latestVisualEmbedding = event.embedding
                        }

                        // 트리거 5: 음성 전사문 키워드 (Whisper → keyword extraction)
                        is XRealEvent.InputEvent.EnrichedVoiceCommand -> {
                            handleVoiceContext(event.text, event.emotion, System.currentTimeMillis())
                        }

                        else -> {}
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "트리거 처리 오류 (루프 유지됨): ${event::class.simpleName} — ${e.message}", e)
                }
            }
        }
    }

    // ══════════════════════════════════════════════
    //  Trigger 1: Deep Focus (앵커 주시)
    // ══════════════════════════════════════════════

    private fun handleDeepFocus(anchorId: String, timestamp: Long) {
        // 앵커 쿨다운 체크
        val lastSurfaced = surfacedAnchorIds[anchorId] ?: 0
        if (timestamp - lastSurfaced < ANCHOR_COOLDOWN_MS) return

        scope.launch {
            try {
                val anchor = spatialAnchorManager.getAnchorById(anchorId) ?: return@launch
                val label = anchor.label

                Log.d(TAG, "Deep focus on anchor '$label' — querying memories...")

                // 다차원 메모리 검색
                val memories = queryMultiDimensional(
                    label = label,
                    lat = null, lon = null,  // 앵커는 월드 좌표, GPS는 LocationService에서
                    visualEmbedding = latestVisualEmbedding
                )

                if (memories.isEmpty()) {
                    Log.d(TAG, "No relevant memories for '$label'")
                    return@launch
                }

                // 최고 랭크 메모리로 패널 생성
                val topMemory = memories.first()
                val summary = formatMemoryForDisplay(topMemory)

                spatialUIManager.attachContentPanel(
                    anchorId = anchorId,
                    title = "💡 ${topMemory.contextTitle}",
                    content = summary,
                    color = relevanceColor(topMemory.score)
                )

                // 쿨다운 기록
                surfacedAnchorIds[anchorId] = timestamp
                surfacedMemoryIds[topMemory.memoryId] = timestamp

                Log.i(TAG, "Memory surfaced for '$label': ${topMemory.contextTitle} (score=%.2f)".format(topMemory.score))

            } catch (e: Exception) {
                Log.e(TAG, "Deep focus memory surfacing failed", e)
            }
        }
    }

    // ══════════════════════════════════════════════
    //  Trigger 3: 시각 유사성 (주기적 스캔)
    // ══════════════════════════════════════════════

    private fun startPeriodicScans() {
        // 시각 유사성 스캔 (10초 간격)
        scope.launch {
            while (isActive) {
                delay(VISUAL_SCAN_INTERVAL_MS)
                try {
                    performVisualScan()
                } catch (e: Exception) {
                    Log.e(TAG, "Visual scan failed", e)
                }
            }
        }

        // 시간 패턴 스캔 (30초 간격)
        scope.launch {
            delay(5000L) // 초기 대기
            while (isActive) {
                try {
                    performTemporalPatternScan()
                } catch (e: Exception) {
                    Log.e(TAG, "Temporal scan failed", e)
                }
                delay(30_000L)
            }
        }
    }

    private suspend fun performVisualScan() {
        val embedding = latestVisualEmbedding ?: return
        val now = System.currentTimeMillis()
        if (now - lastVisualScanTime < VISUAL_SCAN_INTERVAL_MS) return
        lastVisualScanTime = now

        // 시각 임베딩으로 유사 씬 검색
        val similarScenes = sceneDatabase.findSimilarScenes(embedding, topK = 3)
        if (similarScenes.isEmpty()) return

        // 가장 유사한 씬의 라벨로 메모리 검색
        val topScene = similarScenes.first()
        val distance = topScene.second
        if (distance > 0.5f) return  // 너무 다르면 무시 (cosine distance)

        val sceneLabel = topScene.first.label
        val sceneTimestamp = topScene.first.timestamp
        val timeDiff = now - sceneTimestamp

        // 최근 10분 이내면 무시 (같은 씬)
        if (timeDiff < 10 * 60 * 1000L) return

        // 이 씬과 관련된 메모리 검색 (시간 기반)
        val windowMs = 5 * 60 * 1000L // ±5분
        val temporalMemories = memoryStore.searchTemporal(
            sceneTimestamp - windowMs,
            sceneTimestamp + windowMs
        )

        if (temporalMemories.isEmpty()) return

        // 가장 관련성 높은 메모리
        val bestMemory = temporalMemories
            .filter { !surfacedMemoryIds.containsKey(it.id) }
            .maxByOrNull { it.content.length }  // 가장 풍부한 내용
            ?: return

        // 포커스 앵커가 있으면 그 옆에 표시
        val focusedAnchor = spatialUIManager.getFocusedAnchorId()
        if (focusedAnchor != null) {
            val timeAgo = formatTimeAgo(timeDiff)
            spatialUIManager.attachContentPanel(
                anchorId = focusedAnchor,
                title = "🔍 $timeAgo",
                content = bestMemory.content.take(80),
                color = 0xFF88FF88.toInt()  // 연초록
            )
            surfacedMemoryIds[bestMemory.id] = now
        }
    }

    // ══════════════════════════════════════════════
    //  Trigger 4: 시간 패턴 (같은 요일/시간대)
    // ══════════════════════════════════════════════

    private suspend fun performTemporalPatternScan() {
        val now = System.currentTimeMillis()
        val location = locationService.getCurrentLocation()

        // 1주 전 같은 시간대 (±1시간)
        val oneWeekAgo = now - 7 * 24 * 60 * 60 * 1000L
        val oneHour = 60 * 60 * 1000L
        val weekAgoMemories = memoryStore.searchTemporal(
            oneWeekAgo - oneHour,
            oneWeekAgo + oneHour
        )

        // 공간 필터 (같은 위치 근처인 경우만)
        val spatiallyRelevant = if (location != null) {
            weekAgoMemories.filter { mem ->
                val memLat = mem.latitude
                val memLon = mem.longitude
                if (memLat == null || memLon == null) false
                else {
                    val dist = haversineDistance(
                        location.latitude, location.longitude,
                        memLat, memLon
                    )
                    dist < 500.0  // 500m 이내
                }
            }
        } else {
            weekAgoMemories.take(3)
        }

        if (spatiallyRelevant.isEmpty()) return

        // 이미 표시한 메모리 필터
        val fresh = spatiallyRelevant.filter { !surfacedMemoryIds.containsKey(it.id) }
        if (fresh.isEmpty()) return

        val bestMemory = fresh.first()

        // 배경 로그로 기록 (Strategist가 소비)
        scope.launch {
            eventBus.publish(XRealEvent.SystemEvent.DebugLog(
                "ProactiveMemory: 1주 전 같은 시간에 '${bestMemory.content.take(50)}…'"
            ))
        }
    }

    // ══════════════════════════════════════════════
    //  Trigger 5: 음성 컨텍스트
    // ══════════════════════════════════════════════

    private fun handleVoiceContext(transcript: String, emotion: String?, timestamp: Long) {
        // 짧은 문장은 무시
        if (transcript.length < 10) return

        scope.launch {
            try {
                // 키워드 추출 (간단한 방식: 명사 추출)
                val keywords = extractKeywords(transcript)
                if (keywords.isEmpty()) return@launch

                // 키워드로 메모리 검색
                for (keyword in keywords.take(2)) {
                    val results = memoryStore.searchKeyword(keyword)
                    val fresh = results.filter { !surfacedMemoryIds.containsKey(it.record.id) }
                    if (fresh.isNotEmpty()) {
                        val memory = fresh.first().record
                        val focusedAnchor = spatialUIManager.getFocusedAnchorId()
                        if (focusedAnchor != null) {
                            spatialUIManager.attachContentPanel(
                                anchorId = focusedAnchor,
                                title = "🗣️ 관련 기억",
                                content = memory.content.take(80),
                                color = 0xFFFFCC00.toInt()  // 노란색
                            )
                            surfacedMemoryIds[memory.id] = timestamp
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Voice context memory search failed", e)
            }
        }
    }

    // ══════════════════════════════════════════════
    //  Multi-Dimensional Memory Query
    // ══════════════════════════════════════════════

    /**
     * 다차원 메모리 검색 + 랭킹.
     * 공간, 시간, 의미, 감정, 최근성 5가지 축으로 점수 계산.
     */
    private suspend fun queryMultiDimensional(
        label: String,
        lat: Double? = null, lon: Double? = null,
        visualEmbedding: ByteArray? = null
    ): List<RankedMemory> {
        val now = System.currentTimeMillis()
        val location = locationService.getCurrentLocation()
        val useLat = lat ?: location?.latitude
        val useLon = lon ?: location?.longitude

        val allCandidates = mutableListOf<CandidateMemory>()

        // 차원 1: 키워드 매칭
        try {
            val keywordResults = memoryStore.searchKeyword(label)
            keywordResults.forEach { result ->
                allCandidates.add(CandidateMemory(result.record, source = "keyword"))
            }
        } catch (_: Exception) {}

        // 차원 2: 공간 매칭
        if (useLat != null && useLon != null) {
            try {
                val spatialResults = memoryStore.searchSpatial(useLat, useLon, 0.5)
                spatialResults.forEach { mem ->
                    allCandidates.add(CandidateMemory(mem, source = "spatial"))
                }
            } catch (_: Exception) {}
        }

        // 차원 3: 시간 매칭 (같은 시간대 1주 전)
        try {
            val weekAgo = now - 7 * 24 * 60 * 60 * 1000L
            val hourMs = 60 * 60 * 1000L
            val temporalResults = memoryStore.searchTemporal(weekAgo - hourMs, weekAgo + hourMs)
            temporalResults.forEach { mem ->
                allCandidates.add(CandidateMemory(mem, source = "temporal"))
            }
        } catch (_: Exception) {}

        // 차원 4: 시각 유사성 (씬 라벨로)
        if (visualEmbedding != null) {
            try {
                val similarScenes = sceneDatabase.findSimilarScenes(visualEmbedding, topK = 3)
                for ((scene, _) in similarScenes) {
                    val sceneMemories = memoryStore.searchTemporal(
                        scene.timestamp - 5 * 60 * 1000L,
                        scene.timestamp + 5 * 60 * 1000L
                    )
                    sceneMemories.forEach { mem ->
                        allCandidates.add(CandidateMemory(mem, source = "visual"))
                    }
                }
            } catch (_: Exception) {}
        }

        // 중복 제거 + 쿨다운 필터
        val unique = allCandidates
            .distinctBy { it.memory.id }
            .filter { !surfacedMemoryIds.containsKey(it.memory.id) }
            .filter { it.memory.content.length > 10 }  // 의미 있는 길이만

        // 랭킹
        return unique.map { candidate ->
            rankMemory(candidate, label, useLat, useLon, now)
        }.filter {
            it.score >= MIN_RELEVANCE_SCORE
        }.sortedByDescending {
            it.score
        }.take(MAX_RESULTS_PER_QUERY)
    }

    // ══════════════════════════════════════════════
    //  Ranking Engine
    // ══════════════════════════════════════════════

    private fun rankMemory(
        candidate: CandidateMemory,
        queryLabel: String,
        lat: Double?, lon: Double?,
        now: Long
    ): RankedMemory {
        val mem = candidate.memory

        // 공간 관련성 (0-1)
        val memLat = mem.latitude
        val memLon = mem.longitude
        val spatialScore = if (lat != null && lon != null && memLat != null && memLon != null) {
            val dist = haversineDistance(lat, lon, memLat, memLon)
            (1.0 - dist / 1000.0).coerceIn(0.0, 1.0).toFloat()
        } else 0f

        // 시간 관련성 (같은 요일/시간대면 높음)
        val temporalScore = computeTemporalRelevance(mem.timestamp, now)

        // 의미 관련성 (키워드 매칭 + 내용 길이)
        val semanticScore = if (candidate.source == "keyword") {
            0.8f + (mem.content.count { queryLabel.contains(it) } / queryLabel.length.toFloat()) * 0.2f
        } else if (candidate.source == "visual") {
            0.6f
        } else {
            0.3f
        }

        // 감정 가중치 (감정적 기억이 더 기억에 남음)
        val emotionScore = if (mem.metadata?.contains("emotion") == true) 0.7f else 0.3f

        // 최근성 감쇠 (1일=1.0, 30일=0.3, 365일=0.1)
        val ageMs = now - mem.timestamp
        val ageDays = ageMs / (24 * 60 * 60 * 1000.0)
        val recencyScore = (1.0 / (1.0 + ageDays / 30.0)).toFloat().coerceIn(0.1f, 1.0f)

        // 종합 점수
        val score = W_SPATIAL * spatialScore +
                W_TEMPORAL * temporalScore +
                W_SEMANTIC * semanticScore +
                W_EMOTION * emotionScore +
                W_RECENCY * recencyScore

        // 컨텍스트 타이틀 생성
        val contextTitle = when (candidate.source) {
            "spatial" -> "이 근처에서"
            "temporal" -> formatTimeAgo(now - mem.timestamp) + " 여기서"
            "keyword" -> "'${queryLabel}' 관련"
            "visual" -> "비슷한 장면에서"
            else -> "관련 기억"
        }

        return RankedMemory(
            memoryId = mem.id,
            content = mem.content,
            contextTitle = contextTitle,
            score = score,
            spatialScore = spatialScore,
            temporalScore = temporalScore,
            semanticScore = semanticScore,
            timestamp = mem.timestamp
        )
    }

    private fun computeTemporalRelevance(memoryTime: Long, now: Long): Float {
        val memCal = java.util.Calendar.getInstance().apply { timeInMillis = memoryTime }
        val nowCal = java.util.Calendar.getInstance().apply { timeInMillis = now }

        // 같은 요일 보너스
        val sameDayOfWeek = memCal.get(java.util.Calendar.DAY_OF_WEEK) == nowCal.get(java.util.Calendar.DAY_OF_WEEK)
        // 같은 시간대 보너스 (±2시간)
        val hourDiff = Math.abs(memCal.get(java.util.Calendar.HOUR_OF_DAY) - nowCal.get(java.util.Calendar.HOUR_OF_DAY))
        val sameTimeSlot = hourDiff <= 2

        return when {
            sameDayOfWeek && sameTimeSlot -> 1.0f
            sameDayOfWeek -> 0.6f
            sameTimeSlot -> 0.5f
            else -> 0.2f
        }
    }

    // ══════════════════════════════════════════════
    //  Utilities
    // ══════════════════════════════════════════════

    private data class CandidateMemory(
        val memory: MemoryRecord,
        val source: String  // "keyword", "spatial", "temporal", "visual"
    )

    data class RankedMemory(
        val memoryId: Long,
        val content: String,
        val contextTitle: String,
        val score: Float,
        val spatialScore: Float,
        val temporalScore: Float,
        val semanticScore: Float,
        val timestamp: Long
    )

    private fun formatMemoryForDisplay(memory: RankedMemory): String {
        val timeStr = formatTimeAgo(System.currentTimeMillis() - memory.timestamp)
        val contentPreview = memory.content.take(100).let {
            if (memory.content.length > 100) "$it…" else it
        }
        return "$contentPreview ($timeStr)"
    }

    private fun formatTimeAgo(diffMs: Long): String {
        val minutes = diffMs / (60 * 1000)
        val hours = minutes / 60
        val days = hours / 24

        return when {
            minutes < 60 -> "${minutes}분 전"
            hours < 24 -> "${hours}시간 전"
            days < 7 -> "${days}일 전"
            days < 30 -> "${days / 7}주 전"
            else -> "${days / 30}개월 전"
        }
    }

    private fun relevanceColor(score: Float): Int {
        return when {
            score >= 0.8f -> 0xFFFFD700.toInt()  // 금색 (매우 관련)
            score >= 0.6f -> 0xFF00FF88.toInt()  // 초록 (관련)
            score >= 0.4f -> 0xFF00CCFF.toInt()  // 청록 (약간 관련)
            else -> 0xFF888888.toInt()             // 회색
        }
    }

    private fun extractKeywords(text: String): List<String> {
        // 간단한 키워드 추출: 2자 이상 단어, 불용어 제외
        val stopWords = setOf(
            "이", "가", "을", "를", "은", "는", "에", "에서", "와", "과", "도", "로",
            "the", "a", "an", "is", "are", "was", "were", "in", "on", "at", "to",
            "그", "저", "이것", "그것", "여기", "거기", "아", "네", "예", "아니"
        )
        return text.split(Regex("[\\s,.]"))
            .map { it.trim() }
            .filter { it.length >= 2 && it !in stopWords }
            .distinct()
            .take(5)
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return R * c
    }

    /**
     * 디버그: 현재 서피싱 상태.
     */
    fun getDebugStatus(): String = buildString {
        append("Active=$isActive ")
        append("SurfacedMemories=${surfacedMemoryIds.size} ")
        append("SurfacedAnchors=${surfacedAnchorIds.size} ")
        append("HasVisualEmb=${latestVisualEmbedding != null}")
    }
}
