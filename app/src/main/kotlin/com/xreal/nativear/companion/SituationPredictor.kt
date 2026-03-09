package com.xreal.nativear.companion

import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.cadence.DigitalTwinBuilder
import com.xreal.nativear.context.LifeSituation
import com.xreal.nativear.core.ErrorReporter
import com.xreal.nativear.policy.PolicyReader
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * SituationPredictor — 24시간 상황 예측기 (Phase F-2).
 *
 * ## 역할
 * 사용자의 반복 행동 패턴(요일+시간대별 상황 발생 빈도)을 학습하여
 * 다음 24시간 동안 어떤 상황이 언제 발생할지 예측한다.
 *
 * ## 동작 원리
 * ```
 * ACCUMULATE: SituationChanged 이벤트 수신
 *    → "RUNNING이 화요일 07시에 발생" 기록
 *    → structured_data(domain=situation_observations) 영구 저장
 *
 * PREDICT: generatePredictions() (WorkManager 매일 01:00 호출)
 *    → 요일+시간별 관찰 빈도 → 확률 계산
 *    → 확률 35% 이상인 상황만 포함
 *    → SituationLifecycleManager에서 처리링 결정
 *    → structured_data(domain=situation_predictions) 저장
 *    → F-3 AgentWarmupScheduler가 조회하여 워밍업 예약
 * ```
 *
 * ## 예시 출력
 * ```
 * 07:00 - RUNNING (확률 87%, 처리링 WARMUP_CACHE, 워밍업 15분 전)
 * 14:00 - IN_MEETING (확률 72%, 처리링 API_SINGLE, 워밍업 30분 전)
 * 22:00 - RELAXING_HOME (확률 91%, 처리링 WARMUP_CACHE, 워밍업 15분 전)
 * ```
 *
 * ## 연결
 * - SituationLifecycleManager: 예측 대상 상황의 숙련도 및 처리링 조회
 * - DigitalTwinBuilder: dailyRoutine 보조 패턴 (데이터 부족 시 보완)
 * - F-3 AgentWarmupScheduler: 예측 결과 소비 → 워밍업 WorkManager 예약
 */
class SituationPredictor(
    private val eventBus: GlobalEventBus,
    private val database: UnifiedMemoryDatabase,
    private val lifecycleManager: SituationLifecycleManager,
    private val digitalTwinBuilder: DigitalTwinBuilder
) {
    companion object {
        private const val TAG = "SituationPredictor"
        private const val DB_DOMAIN_OBS  = "situation_observations"  // 요일+시간별 관찰 빈도
        private const val DB_DOMAIN_PRED = "situation_predictions"    // 오늘 생성된 예측 목록
        private val MIN_PROBABILITY: Float get() = PolicyReader.getFloat("companion.prediction_min_probability", 0.35f)
        private const val PERSIST_EVERY_N = 10                       // N회 관찰마다 DB 저장
        private val DEFAULT_WARMUP_LEAD_MS: Long get() = PolicyReader.getLong("companion.prediction_warmup_lead_ms", 900_000L)
        private const val DATE_FORMAT = "yyyy-MM-dd"
    }

    // ─── 예측 결과 데이터 클래스 ──────────────────────────────────────────────

    /**
     * PredictedSituation — 하나의 예측 항목.
     *
     * [predictedTimeMs]: 상황 시작 예상 시각 (Unix ms)
     * [warmupLeadTimeMs]: 워밍업을 몇 ms 전에 시작해야 하는지
     * [warmupTimeMs]: 실제 워밍업 실행 시각 = predictedTimeMs - warmupLeadTimeMs
     */
    data class PredictedSituation(
        val situation: LifeSituation,
        val predictedTimeMs: Long,
        val durationEstimateMs: Long = 60 * 60 * 1000L,  // 기본 1시간
        val probability: Float,
        val masteryLevel: String,                         // MasteryLevel.name
        val processingRing: String,                       // ProcessingRing.name
        val warmupLeadTimeMs: Long,
        val reason: String,
        val dayOfWeek: Int,                               // Calendar.DAY_OF_WEEK
        val hourOfDay: Int
    ) {
        val warmupTimeMs: Long get() = predictedTimeMs - warmupLeadTimeMs

        fun toJson(): JSONObject = JSONObject().apply {
            put("situation", situation.name)
            put("predictedTimeMs", predictedTimeMs)
            put("durationEstimateMs", durationEstimateMs)
            put("probability", probability.toDouble())
            put("masteryLevel", masteryLevel)
            put("processingRing", processingRing)
            put("warmupLeadTimeMs", warmupLeadTimeMs)
            put("reason", reason)
            put("dayOfWeek", dayOfWeek)
            put("hourOfDay", hourOfDay)
        }

        companion object {
            fun fromJson(json: JSONObject): PredictedSituation? = try {
                val situation = LifeSituation.valueOf(json.getString("situation"))
                PredictedSituation(
                    situation = situation,
                    predictedTimeMs = json.getLong("predictedTimeMs"),
                    durationEstimateMs = json.optLong("durationEstimateMs", 3600000L),
                    probability = json.getDouble("probability").toFloat(),
                    masteryLevel = json.optString("masteryLevel", "UNKNOWN"),
                    processingRing = json.optString("processingRing", "API_SINGLE"),
                    warmupLeadTimeMs = json.optLong("warmupLeadTimeMs", DEFAULT_WARMUP_LEAD_MS),
                    reason = json.optString("reason", ""),
                    dayOfWeek = json.optInt("dayOfWeek", 1),
                    hourOfDay = json.optInt("hourOfDay", 0)
                )
            } catch (e: Exception) { null }
        }
    }

    // ─── 내부 상태 ────────────────────────────────────────────────────────────

    /**
     * 관찰 카운터.
     * key = "${situation.name}_${dayOfWeek}_${hour}"
     *       예) "RUNNING_3_7" = 화요일(3) 07시 러닝
     */
    private val observations = ConcurrentHashMap<String, AtomicInteger>()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var collectJob: Job? = null

    // ─── 생명주기 ─────────────────────────────────────────────────────────────

    fun start() {
        loadObservationsFromDatabase()

        collectJob = scope.launch {
            eventBus.events.collect { event ->
                try {
                    if (event is XRealEvent.SystemEvent.SituationChanged) {
                        recordObservation(event.newSituation)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ErrorReporter.report(TAG, "관찰 기록 오류", e)
                }
            }
        }

        Log.i(TAG, "SituationPredictor 시작 — ${observations.size}개 관찰 패턴 로드됨")
    }

    fun stop() {
        collectJob?.cancel()
        // 종료 시 미저장 관찰 전체 플러시
        scope.launch(Dispatchers.IO) {
            observations.forEach { (key, counter) ->
                persistObservation(key, counter.get())
            }
        }
        scope.cancel()
        Log.i(TAG, "SituationPredictor 종료")
    }

    // ─── 핵심 공개 API ────────────────────────────────────────────────────────

    /**
     * 다음 24시간 상황 예측 목록 생성.
     *
     * WorkManager(SituationPredictionWorker)에서 매일 01:00 호출.
     * 결과는 structured_data(situation_predictions)에 저장.
     * F-3 AgentWarmupScheduler가 이 결과를 조회하여 워밍업 WorkManager 작업을 예약.
     *
     * @return 확률 35% 이상, 시간순 정렬된 예측 목록
     */
    fun generatePredictions(): List<PredictedSituation> {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val predictions = mutableListOf<PredictedSituation>()

        // ── DigitalTwinBuilder의 dailyRoutine 보조 데이터 ──
        val twinRoutine = try {
            digitalTwinBuilder.profile.value.practical.dailyRoutine
        } catch (_: Exception) { emptyMap() }

        // ── 다음 24시간 각 시간대별 예측 ──
        for (hourOffset in 1..24) {
            cal.timeInMillis = now
            cal.add(Calendar.HOUR_OF_DAY, hourOffset)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)

            val targetHour = cal.get(Calendar.HOUR_OF_DAY)
            val targetDow  = cal.get(Calendar.DAY_OF_WEEK)
            val targetMs   = cal.timeInMillis

            // 해당 시간대의 총 관찰 수 (분모)
            val totalForSlot = getTotalObservationsForSlot(targetHour, targetDow)

            // 각 상황별 확률 계산
            val candidates = LifeSituation.values()
                .filter { it != LifeSituation.UNKNOWN && it != LifeSituation.CUSTOM }
                .mapNotNull { situation ->
                    val count = getObservationCount(situation, targetDow, targetHour)

                    // 관찰 데이터가 없으면 DigitalTwinBuilder dailyRoutine으로 보완
                    val probability = if (totalForSlot > 0 && count > 0) {
                        count.toFloat() / totalForSlot
                    } else if (twinRoutine[targetHour] != null) {
                        // dailyRoutine 기반 휴리스틱
                        estimateFromDailyRoutine(situation, twinRoutine[targetHour]!!, targetHour)
                    } else {
                        0f
                    }

                    if (probability < MIN_PROBABILITY) return@mapNotNull null

                    val record = lifecycleManager.getRecord(situation)
                    val mastery = record?.masteryLevel ?: SituationLifecycleManager.MasteryLevel.UNKNOWN
                    val ring = lifecycleManager.getProcessingRing(situation)
                    val leadTime = getWarmupLeadTime(mastery, situation)

                    PredictedSituation(
                        situation = situation,
                        predictedTimeMs = targetMs,
                        probability = probability,
                        masteryLevel = mastery.name,
                        processingRing = ring.name,
                        warmupLeadTimeMs = leadTime,
                        reason = buildReason(situation, targetDow, targetHour, count, totalForSlot, probability),
                        dayOfWeek = targetDow,
                        hourOfDay = targetHour
                    )
                }
                .sortedByDescending { it.probability }

            // 시간대별 최대 확률 1개만 포함 (중복 방지)
            candidates.firstOrNull()?.let { predictions.add(it) }
        }

        val sorted = predictions.sortedBy { it.predictedTimeMs }
        savePredictions(sorted)

        Log.i(TAG, "예측 생성 완료: ${sorted.size}개 (확률${(MIN_PROBABILITY * 100).toInt()}% 이상)")
        sorted.forEach { p ->
            Log.d(TAG, "  → ${p.situation.displayName} " +
                "${p.hourOfDay}시 (${(p.probability * 100).toInt()}%, 링:${p.processingRing})")
        }
        return sorted
    }

    /**
     * 오늘의 예측 목록 반환.
     * DB에 저장된 것이 있으면 반환, 없으면 즉시 생성.
     */
    fun getTodayPredictions(): List<PredictedSituation> {
        val stored = loadPredictionsFromDatabase()
        return if (stored.isNotEmpty()) {
            Log.d(TAG, "오늘 예측 DB 로드: ${stored.size}개")
            stored
        } else {
            Log.d(TAG, "오늘 예측 없음 → 즉시 생성")
            generatePredictions()
        }
    }

    /**
     * 특정 상황이 오늘 예측되었는지 확인.
     * F-3 AgentWarmupScheduler에서 워밍업 스케줄링 전 조회.
     */
    fun getPredictionFor(situation: LifeSituation): PredictedSituation? =
        getTodayPredictions().find { it.situation == situation }

    /**
     * 전체 관찰 통계 요약.
     */
    fun getObservationSummary(): String = buildString {
        appendLine("=== 상황 관찰 통계 (SituationPredictor) ===")
        appendLine("총 패턴 수: ${observations.size}개")
        val topPatterns = observations.entries
            .sortedByDescending { it.value.get() }
            .take(10)
        if (topPatterns.isNotEmpty()) {
            appendLine("상위 10 패턴:")
            topPatterns.forEach { (key, count) ->
                val parts = key.split("_")
                if (parts.size >= 3) {
                    val situation = try { LifeSituation.valueOf(parts[0]) } catch (_: Exception) { null }
                    val dow = parts[parts.size - 2].toIntOrNull()
                    val hour = parts[parts.size - 1].toIntOrNull()
                    val dayName = when (dow) {
                        1 -> "일" 2 -> "월" 3 -> "화" 4 -> "수" 5 -> "목" 6 -> "금" 7 -> "토" else -> "?"
                    }
                    appendLine("  • ${situation?.displayName ?: key} ${dayName}요일 ${hour}시: ${count.get()}회")
                }
            }
        }
    }

    // ─── 내부 로직 ────────────────────────────────────────────────────────────

    /** 상황 관찰 기록 */
    private fun recordObservation(situation: LifeSituation) {
        val cal = Calendar.getInstance()
        val key = buildObsKey(situation, cal.get(Calendar.DAY_OF_WEEK), cal.get(Calendar.HOUR_OF_DAY))
        val count = observations.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()

        if (count % PERSIST_EVERY_N == 0) {
            scope.launch(Dispatchers.IO) { persistObservation(key, count) }
        }
    }

    private fun buildObsKey(situation: LifeSituation, dayOfWeek: Int, hour: Int) =
        "${situation.name}_${dayOfWeek}_${hour}"

    private fun getObservationCount(situation: LifeSituation, dayOfWeek: Int, hour: Int): Int =
        observations[buildObsKey(situation, dayOfWeek, hour)]?.get() ?: 0

    private fun getTotalObservationsForSlot(hour: Int, dayOfWeek: Int): Int =
        LifeSituation.values().sumOf { getObservationCount(it, dayOfWeek, hour) }

    /**
     * 상황별 워밍업 리드타임.
     * 복잡한 준비가 필요한 상황(회의, 러닝)은 더 일찍 시작.
     */
    private fun getWarmupLeadTime(
        mastery: SituationLifecycleManager.MasteryLevel,
        situation: LifeSituation
    ): Long {
        if (mastery.ordinal < SituationLifecycleManager.MasteryLevel.ROUTINE.ordinal) {
            return 0L  // UNKNOWN/LEARNING은 워밍업 불필요 (실시간 AI 호출)
        }
        return when (situation) {
            LifeSituation.IN_MEETING     -> 30 * 60 * 1000L  // 회의: 30분 전
            LifeSituation.RUNNING        -> 15 * 60 * 1000L  // 러닝: 15분 전
            LifeSituation.GYM_WORKOUT    -> 15 * 60 * 1000L  // 헬스: 15분 전
            LifeSituation.TEACHING       -> 25 * 60 * 1000L  // 수업: 25분 전
            LifeSituation.COMMUTING      -> 10 * 60 * 1000L  // 출퇴근: 10분 전
            LifeSituation.MORNING_ROUTINE -> 20 * 60 * 1000L // 아침: 20분 전 (하루 브리핑)
            else                         -> DEFAULT_WARMUP_LEAD_MS
        }
    }

    /**
     * DigitalTwinBuilder dailyRoutine 기반 상황 확률 추정.
     * 관찰 데이터가 부족할 때 휴리스틱으로 보완.
     */
    private fun estimateFromDailyRoutine(
        situation: LifeSituation,
        routineActivity: String,
        hour: Int
    ): Float {
        // "대화" → 회의/소셜, "시각기록" → 활동, "사용자요청" → 집중 업무
        return when {
            routineActivity == "대화" && situation == LifeSituation.IN_MEETING && hour in 9..17 -> 0.40f
            routineActivity == "대화" && situation == LifeSituation.SOCIAL_GATHERING             -> 0.35f
            routineActivity == "시각기록" && situation == LifeSituation.RUNNING && hour in 5..9  -> 0.40f
            routineActivity == "사용자요청" && situation == LifeSituation.AT_DESK_WORKING        -> 0.45f
            else -> 0f
        }
    }

    private fun buildReason(
        situation: LifeSituation,
        dayOfWeek: Int,
        hour: Int,
        count: Int,
        total: Int,
        probability: Float
    ): String {
        val dayName = when (dayOfWeek) {
            1 -> "일요일" 2 -> "월요일" 3 -> "화요일" 4 -> "수요일"
            5 -> "목요일" 6 -> "금요일" 7 -> "토요일" else -> "?"
        }
        return "${situation.displayName}: " +
            "${dayName} ${hour}시에 ${(probability * 100).toInt()}% 확률 " +
            "(${count}/${total}회 관찰)"
    }

    // ─── DB 영속성 ────────────────────────────────────────────────────────────

    private fun loadObservationsFromDatabase() {
        try {
            val records = database.queryStructuredData(domain = DB_DOMAIN_OBS, limit = 5000)
            records.forEach { record ->
                val count = record.value.toIntOrNull() ?: return@forEach
                observations.getOrPut(record.dataKey) { AtomicInteger(0) }.set(count)
            }
            Log.i(TAG, "관찰 DB 로드: ${observations.size}개 패턴")
        } catch (e: Exception) {
            ErrorReporter.report(TAG, "관찰 DB 로드 실패", e)
        }
    }

    private fun persistObservation(key: String, count: Int) {
        try {
            database.upsertStructuredData(
                domain = DB_DOMAIN_OBS,
                dataKey = key,
                value = count.toString(),
                tags = "prediction,observation"
            )
        } catch (e: Exception) {
            ErrorReporter.report(TAG, "관찰 저장 실패: $key", e)
        }
    }

    private fun savePredictions(predictions: List<PredictedSituation>) {
        try {
            val dateKey = SimpleDateFormat(DATE_FORMAT, Locale.getDefault())
                .format(Date())
            val arr = JSONArray().apply {
                predictions.forEach { put(it.toJson()) }
            }
            database.upsertStructuredData(
                domain = DB_DOMAIN_PRED,
                dataKey = dateKey,
                value = arr.toString(),
                tags = "prediction,daily"
            )
            Log.d(TAG, "예측 저장: $dateKey (${predictions.size}개)")
        } catch (e: Exception) {
            ErrorReporter.report(TAG, "예측 저장 실패", e)
        }
    }

    private fun loadPredictionsFromDatabase(): List<PredictedSituation> {
        return try {
            val dateKey = SimpleDateFormat(DATE_FORMAT, Locale.getDefault())
                .format(Date())
            val records = database.queryStructuredData(
                domain = DB_DOMAIN_PRED,
                dataKey = dateKey,
                limit = 1
            )
            val json = records.firstOrNull()?.value ?: return emptyList()
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { PredictedSituation.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            ErrorReporter.report(TAG, "예측 DB 로드 실패", e)
            emptyList()
        }
    }
}
