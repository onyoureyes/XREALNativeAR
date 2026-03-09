package com.xreal.nativear.companion

import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.context.LifeSituation
import com.xreal.nativear.core.ErrorReporter
import com.xreal.nativear.policy.PolicyReader
import com.xreal.nativear.core.FeedbackSentiment
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * SituationLifecycleManager — 상황별 숙련도 사다리 관리자.
 *
 * ## 역할 (Phase F-1 — Predictive Agent Intelligence 핵심 라우터)
 *
 * 각 LifeSituation이 거치는 4단계 숙련도를 추적하고,
 * 현재 숙련도와 참신도(noveltyScore)를 기반으로 AI 처리 링(ProcessingRing)을 결정한다.
 *
 * ```
 * UNKNOWN  → MISSION_TEAM  (새 상황, 풀 멀티에이전트 + 관찰 시작)
 * LEARNING → API_SINGLE    (패턴 학습 중, 단일 AI + OutcomeTracker)
 * ROUTINE  → WARMUP_CACHE  (패턴 확립, 사전 생성된 캐시 사용)
 * MASTERED → LOCAL_ML      (완전 자동화, TFLite 온디바이스 추론)
 * ```
 *
 * ## 승급 조건 (Mastery Ladder)
 * ```
 * UNKNOWN  → LEARNING : 첫 관찰 즉시 (totalObservations >= 1)
 * LEARNING → ROUTINE  : 연속 성공 3회 + 총 관찰 5회 이상
 * ROUTINE  → MASTERED : 연속 성공 10회 + 총 관찰 20회 이상
 * ```
 *
 * ## 강급 조건 (이탈 감지)
 * ```
 * 최근 실패율 > 30% AND 총 관찰 10회 이상 → 한 단계 강급
 * noveltyScore > 0.75f                     → 임시 링 상향 (레코드 변경 없음)
 * ```
 *
 * ## 데이터 흐름
 * ```
 * SituationChanged 이벤트  → onSituationObserved() → 승급 체크 → SituationMasteryChanged 발행
 * VoiceFeedback(POSITIVE)  → recordOutcome(success=true) → consecutiveSuccesses++
 * VoiceFeedback(NEGATIVE)  → recordOutcome(success=false) → consecutiveSuccesses=0 → 강급 체크
 * getProcessingRing()      → ProcessingRing 반환 → VisionCoordinator/AIAgentManager 라우팅
 * ```
 *
 * ## 연결 (Phase F 전체)
 * - F-2 SituationPredictor: ROUTINE/MASTERED 상황을 예측 대상으로 우선 선정
 * - F-3 AgentWarmupWorker: ROUTINE 승급 이벤트 → 워밍업 자동 예약
 * - F-6 RoutineClassifier: MASTERED 상황 → TFLite 모델 학습 데이터 제공
 */
class SituationLifecycleManager(
    private val eventBus: GlobalEventBus,
    private val database: UnifiedMemoryDatabase
) {
    companion object {
        private const val TAG = "SituationLifecycleMgr"
        private const val DB_DOMAIN = "situation_lifecycle"
        private val PERSIST_EVERY_N: Int get() = PolicyReader.getInt("companion.persist_every_n", 5)
        private val DOWNGRADE_FAILURE_RATE: Float get() = PolicyReader.getFloat("companion.downgrade_failure_rate", 0.30f)
        private val DOWNGRADE_MIN_OBSERVATIONS: Int get() = PolicyReader.getInt("companion.downgrade_min_observations", 10)
    }

    // ─── 숙련도 4단계 ─────────────────────────────────────────────────────────

    /**
     * MasteryLevel — 상황 숙련도 4단계.
     *
     * [minConsecutiveSuccesses]: 해당 레벨로 승급하기 위한 연속 성공 횟수
     * [minTotalObservations]: 해당 레벨로 승급하기 위한 최소 총 관찰 횟수
     */
    enum class MasteryLevel(
        val displayName: String,
        val minConsecutiveSuccesses: Int,
        val minTotalObservations: Int
    ) {
        UNKNOWN("처음 접하는 상황", 0, 0),
        LEARNING("패턴 학습 중", 0, 1),      // 첫 관찰 즉시
        ROUTINE("패턴 확립됨", 3, 5),         // 연속 3회 성공 + 5회 관찰
        MASTERED("완전 자동화", 10, 20);      // 연속 10회 성공 + 20회 관찰

        /** 다음 단계 (없으면 null) */
        val next: MasteryLevel? get() = values().getOrNull(ordinal + 1)

        /** 이전 단계 (없으면 null) */
        val prev: MasteryLevel? get() = values().getOrNull(ordinal - 1)
    }

    // ─── 처리 링 ──────────────────────────────────────────────────────────────

    /**
     * ProcessingRing — 숙련도에 따른 AI 처리 방식 결정.
     *
     * [estimatedTokens]: 예상 토큰 소비량 (0 = 무료)
     */
    enum class ProcessingRing(val displayName: String, val estimatedTokens: Int) {
        LOCAL_ML("온디바이스 ML 추론", 0),        // MASTERED: TFLite, 즉시, 0 토큰
        WARMUP_CACHE("워밍업 캐시 조회", 0),      // ROUTINE: DB 캐시, ~0 토큰
        API_SINGLE("단일 AI 호출", 500),          // LEARNING: 표준 AI 호출
        MISSION_TEAM("미션 팀 구성", 2000)        // UNKNOWN/COMPLEX: 풀 멀티에이전트
    }

    // ─── 상황 레코드 ──────────────────────────────────────────────────────────

    /**
     * SituationRecord — 상황별 숙련도 추적 데이터.
     * ConcurrentHashMap에 인메모리 유지, structured_data 테이블에 영구 저장.
     */
    data class SituationRecord(
        val situation: LifeSituation,
        val masteryLevel: MasteryLevel = MasteryLevel.UNKNOWN,
        val totalObservations: Int = 0,
        val consecutiveSuccesses: Int = 0,
        val totalSuccesses: Int = 0,
        val totalFailures: Int = 0,
        val lastObservedAt: Long = 0L,
        val lastMasteryChangedAt: Long = System.currentTimeMillis(),
        val warmupCacheKey: String? = null,    // F-3 AgentWarmupWorker가 등록
        val localModelPath: String? = null,    // F-6 RoutineClassifier가 등록
        val averageNoveltyScore: Float = 0f
    ) {
        /** 최근 실패율 (총 관찰 대비) */
        val failureRate: Float
            get() = if (totalObservations == 0) 0f
                    else totalFailures.toFloat() / totalObservations

        /** JSON 직렬화 */
        fun toJson(): String = org.json.JSONObject().apply {
            put("situation", situation.name)
            put("masteryLevel", masteryLevel.name)
            put("totalObservations", totalObservations)
            put("consecutiveSuccesses", consecutiveSuccesses)
            put("totalSuccesses", totalSuccesses)
            put("totalFailures", totalFailures)
            put("lastObservedAt", lastObservedAt)
            put("lastMasteryChangedAt", lastMasteryChangedAt)
            put("warmupCacheKey", warmupCacheKey ?: "")
            put("localModelPath", localModelPath ?: "")
            put("averageNoveltyScore", averageNoveltyScore.toDouble())
        }.toString()

        companion object {
            /** JSON 역직렬화 */
            fun fromJson(situation: LifeSituation, json: String): SituationRecord? = try {
                val o = org.json.JSONObject(json)
                SituationRecord(
                    situation = situation,
                    masteryLevel = try {
                        MasteryLevel.valueOf(o.optString("masteryLevel", "UNKNOWN"))
                    } catch (_: Exception) { MasteryLevel.UNKNOWN },
                    totalObservations = o.optInt("totalObservations", 0),
                    consecutiveSuccesses = o.optInt("consecutiveSuccesses", 0),
                    totalSuccesses = o.optInt("totalSuccesses", 0),
                    totalFailures = o.optInt("totalFailures", 0),
                    lastObservedAt = o.optLong("lastObservedAt", 0L),
                    lastMasteryChangedAt = o.optLong("lastMasteryChangedAt", System.currentTimeMillis()),
                    warmupCacheKey = o.optString("warmupCacheKey").takeIf { it.isNotBlank() },
                    localModelPath = o.optString("localModelPath").takeIf { it.isNotBlank() },
                    averageNoveltyScore = o.optDouble("averageNoveltyScore", 0.0).toFloat()
                )
            } catch (e: Exception) {
                Log.w(TAG, "SituationRecord 파싱 실패: ${e.message}")
                null
            }
        }
    }

    // ─── 내부 상태 ────────────────────────────────────────────────────────────

    /** 모든 상황의 숙련도 레코드 (인메모리 캐시) */
    private val records = ConcurrentHashMap<LifeSituation, SituationRecord>()

    /** 현재 활성 상황 (SituationChanged 이벤트로 갱신) */
    @Volatile
    private var currentSituation: LifeSituation = LifeSituation.UNKNOWN

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var collectJob: Job? = null

    // ─── 생명주기 ─────────────────────────────────────────────────────────────

    fun start() {
        loadAllFromDatabase()

        collectJob = scope.launch {
            eventBus.events.collect { event ->
                try {
                    when (event) {
                        // 상황 변화 감지 → 관찰 기록 + 승급 체크
                        is XRealEvent.SystemEvent.SituationChanged -> {
                            currentSituation = event.newSituation
                            onSituationObserved(event.newSituation, event.confidence)
                        }
                        // 음성 피드백 → 성공/실패 기록 → 승/강급 체크
                        is XRealEvent.InputEvent.VoiceFeedback -> {
                            when (event.sentiment) {
                                FeedbackSentiment.POSITIVE ->
                                    recordOutcome(currentSituation, success = true)
                                FeedbackSentiment.NEGATIVE ->
                                    recordOutcome(currentSituation, success = false)
                                FeedbackSentiment.NEUTRAL -> { /* 관찰만, 성공/실패 미기록 */ }
                            }
                        }
                        else -> {}
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ErrorReporter.report(TAG, "이벤트 처리 오류", e)
                }
            }
        }

        Log.i(TAG, "SituationLifecycleManager 시작 — ${records.size}개 상황 레코드 로드됨")
    }

    fun stop() {
        collectJob?.cancel()
        // 종료 시 전체 저장 (동기적)
        runBlocking(Dispatchers.IO) {
            records.forEach { (sit, rec) -> persistRecord(sit, rec) }
        }
        scope.cancel()
        Log.i(TAG, "SituationLifecycleManager 종료 — ${records.size}개 레코드 저장 완료")
    }

    // ─── 핵심 공개 API ────────────────────────────────────────────────────────

    /**
     * 현재 상황의 처리 링 결정.
     *
     * 호출처: VisionCoordinator, AIAgentManager, MissionDetectorRouter
     *
     * @param situation 감지된 상황
     * @param noveltyScore 참신도 0.0~1.0 (높을수록 새로운 상황, NoveltyEngine 제공)
     * @return ProcessingRing — 실제 AI 처리 방식
     */
    fun getProcessingRing(
        situation: LifeSituation,
        noveltyScore: Float = 0f
    ): ProcessingRing {
        val record = records[situation] ?: SituationRecord(situation)

        // ── 높은 참신도 → 임시 링 상향 (숙련도 레코드는 변경하지 않음) ──
        if (noveltyScore > 0.75f) {
            return when (record.masteryLevel) {
                MasteryLevel.MASTERED -> ProcessingRing.WARMUP_CACHE  // MASTERED도 이탈 시 캐시 확인
                MasteryLevel.ROUTINE  -> ProcessingRing.API_SINGLE    // 패턴 이탈 → AI 재분석
                else                  -> ProcessingRing.MISSION_TEAM  // 처음 + 이탈 → 팀 투입
            }
        }
        if (noveltyScore > 0.55f && record.masteryLevel == MasteryLevel.LEARNING) {
            return ProcessingRing.MISSION_TEAM  // 학습 중 + 중간 이탈 → 팀 투입
        }

        // ── 숙련도 기반 정상 라우팅 ──
        return when (record.masteryLevel) {
            MasteryLevel.UNKNOWN  -> ProcessingRing.MISSION_TEAM
            MasteryLevel.LEARNING -> ProcessingRing.API_SINGLE
            MasteryLevel.ROUTINE  -> ProcessingRing.WARMUP_CACHE
            MasteryLevel.MASTERED -> {
                // 로컬 모델이 아직 없으면 캐시로 폴백
                if (record.localModelPath != null) ProcessingRing.LOCAL_ML
                else ProcessingRing.WARMUP_CACHE
            }
        }
    }

    /** 특정 상황의 현재 숙련도 조회 */
    fun getMasteryLevel(situation: LifeSituation): MasteryLevel =
        records[situation]?.masteryLevel ?: MasteryLevel.UNKNOWN

    /** 특정 상황의 전체 레코드 조회 */
    fun getRecord(situation: LifeSituation): SituationRecord? = records[situation]

    /** 전체 레코드 조회 (StrategistService, SituationPredictor 용) */
    fun getAllRecords(): Map<LifeSituation, SituationRecord> = records.toMap()

    /** 현재 활성 상황 */
    fun getCurrentSituation(): LifeSituation = currentSituation

    /**
     * 워밍업 캐시 키 등록.
     * F-3 AgentWarmupWorker가 캐시 생성 완료 후 호출.
     */
    fun setWarmupCacheKey(situation: LifeSituation, cacheKey: String) {
        val current = records.getOrPut(situation) { SituationRecord(situation) }
        val updated = current.copy(warmupCacheKey = cacheKey)
        records[situation] = updated
        scope.launch(Dispatchers.IO) { persistRecord(situation, updated) }
        Log.d(TAG, "워밍업 캐시 키 등록: ${situation.displayName} → $cacheKey")
    }

    /**
     * 로컬 ML 모델 경로 등록.
     * F-6 RoutineClassifier가 TFLite 모델 학습 완료 후 호출.
     */
    fun setLocalModelPath(situation: LifeSituation, modelPath: String) {
        val current = records.getOrPut(situation) { SituationRecord(situation) }
        val updated = current.copy(localModelPath = modelPath)
        records[situation] = updated
        scope.launch(Dispatchers.IO) { persistRecord(situation, updated) }
        Log.i(TAG, "로컬 ML 모델 등록: ${situation.displayName} → $modelPath")
    }

    /**
     * 외부 성공 기록 (VoiceFeedback 외 직접 호출 경로).
     * 예: MissionConductor 미션 성공 완료 후 호출.
     */
    fun recordSuccess(situation: LifeSituation) = recordOutcome(situation, success = true)

    /**
     * 외부 실패 기록.
     * 예: AI 응답 타임아웃, 오류 발생 후 호출.
     */
    fun recordFailure(situation: LifeSituation) = recordOutcome(situation, success = false)

    /** 현황 요약 문자열 (get_system_health 툴, StrategistService 용) */
    fun getMasteryStatus(): String = buildString {
        appendLine("=== 상황 숙련도 현황 (SituationLifecycleMgr) ===")
        appendLine("현재 상황: ${currentSituation.displayName}")
        appendLine()
        MasteryLevel.values().forEach { level ->
            val sits = records.values.filter { it.masteryLevel == level }
            if (sits.isNotEmpty()) {
                appendLine("[${level.displayName}] ${sits.size}개:")
                sits.sortedBy { it.situation.displayName }.forEach { r ->
                    val ring = getProcessingRing(r.situation)
                    val cacheStatus = when {
                        r.localModelPath != null -> "🤖 ML모델"
                        r.warmupCacheKey != null -> "⚡ 캐시"
                        else -> "—"
                    }
                    appendLine(
                        "  • ${r.situation.displayName} " +
                        "(관찰:${r.totalObservations}, 연속성공:${r.consecutiveSuccesses}, " +
                        "실패율:${(r.failureRate * 100).toInt()}%, " +
                        "링:${ring.displayName}, $cacheStatus)"
                    )
                }
            }
        }
        val untracked = LifeSituation.values()
            .filter { it != LifeSituation.UNKNOWN && it != LifeSituation.CUSTOM }
            .count { !records.containsKey(it) }
        if (untracked > 0) appendLine("[미관찰] $untracked 개 상황")
    }

    // ─── 내부 로직 ────────────────────────────────────────────────────────────

    /** 상황 관찰 기록 */
    private fun onSituationObserved(situation: LifeSituation, confidence: Float) {
        val current = records.getOrPut(situation) { SituationRecord(situation) }
        val observed = current.copy(
            totalObservations = current.totalObservations + 1,
            lastObservedAt = System.currentTimeMillis()
        )
        val transitioned = checkMasteryUpgrade(current, observed)
        records[situation] = transitioned

        // N회마다 DB 저장 (매 관찰마다 저장하면 I/O 과도)
        if (transitioned.totalObservations % PERSIST_EVERY_N == 0) {
            scope.launch(Dispatchers.IO) { persistRecord(situation, transitioned) }
        }
    }

    /** 성공/실패 결과 기록 */
    private fun recordOutcome(situation: LifeSituation, success: Boolean) {
        val current = records.getOrPut(situation) { SituationRecord(situation) }

        val updated = if (success) {
            current.copy(
                totalSuccesses = current.totalSuccesses + 1,
                consecutiveSuccesses = current.consecutiveSuccesses + 1
            )
        } else {
            current.copy(
                totalFailures = current.totalFailures + 1,
                consecutiveSuccesses = 0  // 연속 성공 초기화
            )
        }

        // 강급 검사: 실패율 초과 + 충분한 관찰 횟수
        val downgraded = if (!success &&
            updated.failureRate > DOWNGRADE_FAILURE_RATE &&
            updated.totalObservations >= DOWNGRADE_MIN_OBSERVATIONS &&
            updated.masteryLevel.prev != null) {

            val prevLevel = updated.masteryLevel.prev!!
            Log.i(TAG, "⬇ 강급: ${situation.displayName} " +
                "${updated.masteryLevel} → $prevLevel " +
                "(실패율: ${(updated.failureRate * 100).toInt()}%)")
            updated.copy(
                masteryLevel = prevLevel,
                consecutiveSuccesses = 0,
                lastMasteryChangedAt = System.currentTimeMillis()
            ).also { r ->
                notifyMasteryChanged(situation, updated.masteryLevel, prevLevel, r)
            }
        } else updated

        val transitioned = checkMasteryUpgrade(current, downgraded)
        records[situation] = transitioned
        scope.launch(Dispatchers.IO) { persistRecord(situation, transitioned) }
    }

    /**
     * 승급 조건 검사.
     * 현재 레코드의 consecutiveSuccesses / totalObservations 기반으로 다음 단계 가능 여부 확인.
     */
    private fun checkMasteryUpgrade(old: SituationRecord, new: SituationRecord): SituationRecord {
        val nextLevel = new.masteryLevel.next ?: return new  // MASTERED → 더 이상 없음

        val canUpgrade = new.consecutiveSuccesses >= nextLevel.minConsecutiveSuccesses &&
            new.totalObservations >= nextLevel.minTotalObservations

        return if (canUpgrade) {
            val upgraded = new.copy(
                masteryLevel = nextLevel,
                lastMasteryChangedAt = System.currentTimeMillis()
            )
            Log.i(TAG, "⬆ 승급: ${new.situation.displayName} " +
                "${new.masteryLevel} → $nextLevel " +
                "(연속:${new.consecutiveSuccesses}, 총관찰:${new.totalObservations})")
            notifyMasteryChanged(new.situation, new.masteryLevel, nextLevel, upgraded)
            upgraded
        } else new
    }

    /** 숙련도 변화 이벤트 발행 */
    private fun notifyMasteryChanged(
        situation: LifeSituation,
        oldLevel: MasteryLevel,
        newLevel: MasteryLevel,
        record: SituationRecord
    ) {
        scope.launch {
            eventBus.publish(
                XRealEvent.SystemEvent.SituationMasteryChanged(
                    situation = situation,
                    oldLevel = oldLevel.name,
                    newLevel = newLevel.name,
                    totalObservations = record.totalObservations,
                    processingRing = getProcessingRing(situation).name
                )
            )
        }

        // ROUTINE 이상 도달 시 워밍업 준비 권장 로그
        if (newLevel.ordinal >= MasteryLevel.ROUTINE.ordinal) {
            Log.i(TAG, "💡 ${situation.displayName} → $newLevel: " +
                "AgentWarmupWorker 예약 권장 (F-3에서 자동 처리)")
        }
        // MASTERED 도달 시 ML 학습 권장 로그
        if (newLevel == MasteryLevel.MASTERED) {
            Log.i(TAG, "🤖 ${situation.displayName} → MASTERED: " +
                "RoutineClassifier 학습 트리거 권장 (F-6에서 자동 처리)")
        }
    }

    // ─── DB 영속성 ────────────────────────────────────────────────────────────

    /** 앱 시작 시 모든 상황 레코드를 DB에서 로드 */
    private fun loadAllFromDatabase() {
        try {
            LifeSituation.values().forEach { situation ->
                val results = database.queryStructuredData(
                    domain = DB_DOMAIN,
                    dataKey = situation.name,
                    limit = 1
                )
                val json = results.firstOrNull()?.value ?: return@forEach
                SituationRecord.fromJson(situation, json)?.let { record ->
                    records[situation] = record
                }
            }
            Log.i(TAG, "DB 로드 완료: ${records.size}개 상황 레코드")
        } catch (e: Exception) {
            ErrorReporter.report(TAG, "DB 로드 실패", e)
        }
    }

    /** 레코드를 structured_data 테이블에 저장 */
    private fun persistRecord(situation: LifeSituation, record: SituationRecord) {
        try {
            database.upsertStructuredData(
                domain = DB_DOMAIN,
                dataKey = situation.name,
                value = record.toJson(),
                tags = "lifecycle,mastery,${record.masteryLevel.name.lowercase()}"
            )
        } catch (e: Exception) {
            ErrorReporter.report(TAG, "레코드 저장 실패: ${situation.name}", e)
        }
    }
}
