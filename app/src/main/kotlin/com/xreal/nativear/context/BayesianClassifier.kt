package com.xreal.nativear.context

import android.util.Log
import kotlin.math.ln
import kotlin.math.exp

/**
 * BayesianClassifier — 센서 데이터 기반 상황 분류 (Naive Bayes + HMM 전이).
 *
 * ## 구조
 * - Naive Bayes: P(situation | features) ∝ P(features | situation) × P(situation)
 * - HMM 전이: P(next_situation | current_situation) — 시간적 연속성 반영
 * - 시간대별 사전확률: P(situation | hour, dayOfWeek) — time-based prior
 *
 * ## 온라인 학습
 * - SituationChanged 이벤트 발생 시 가능도/전이/사전확률 갱신
 * - 지수이동평균(EMA)로 최신 데이터에 가중치
 *
 * ## 피처 이산화
 * - 연속 값(HR, speed 등)은 범주형으로 이산화
 * - 이진 피처(isMoving, hasText 등)는 직접 사용
 */
class BayesianClassifier {
    companion object {
        private const val TAG = "BayesianClassifier"
        private const val SMOOTHING_ALPHA = 1.0  // Laplace smoothing
        private const val TRANSITION_WEIGHT = 0.3  // HMM 전이 확률 가중치 (0=무시, 1=전이만)
        private const val EMA_ALPHA = 0.1  // 온라인 학습 EMA 계수
    }

    // ─── 확률 테이블 ───

    // 사전확률: P(situation | hour, dow) — [hour * 7 + dow] → Map<situation, count>
    private val timePrior = Array(24 * 7) { mutableMapOf<LifeSituation, Double>() }

    // 가능도: P(feature_value | situation) — feature_name → Map<(situation, value), count>
    private val likelihood = mutableMapOf<String, MutableMap<Pair<LifeSituation, String>, Double>>()

    // 전이확률: P(next | current) — Map<(current, next), count>
    private val transitionCounts = mutableMapOf<Pair<LifeSituation, LifeSituation>, Double>()
    private val transitionTotals = mutableMapOf<LifeSituation, Double>()

    // 전체 관측 수
    private var totalObservations = 0.0

    // 활성 상황 목록 (UNKNOWN, CUSTOM 제외)
    private val activeSituations = LifeSituation.entries.filter {
        it != LifeSituation.UNKNOWN && it != LifeSituation.CUSTOM
    }

    init {
        initializeDefaultPriors()
    }

    // ─── 분류 ───

    /**
     * 베이지안 분류. ContextSnapshot → (LifeSituation, confidence) 리스트 (확률 내림차순).
     * @param previousSituation HMM 전이 확률에 사용할 이전 상황 (null이면 전이 무시)
     */
    fun classify(
        snapshot: ContextSnapshot,
        previousSituation: LifeSituation? = null
    ): List<Pair<LifeSituation, Float>> {
        val features = extractFeatures(snapshot)
        val hour = snapshot.hourOfDay
        val dow = snapshot.dayOfWeek  // 1=일 7=토 (Calendar convention)
        val timeIdx = hour * 7 + (dow - 1).coerceIn(0, 6)

        // 각 상황에 대해 log-posterior 계산
        val logPosteriors = mutableMapOf<LifeSituation, Double>()

        for (situation in activeSituations) {
            var logP = 0.0

            // 1. 시간대별 사전확률 (log)
            val priorMap = timePrior[timeIdx]
            val priorTotal = priorMap.values.sum().coerceAtLeast(1.0)
            val prior = (priorMap[situation] ?: 0.0 + SMOOTHING_ALPHA) / (priorTotal + SMOOTHING_ALPHA * activeSituations.size)
            logP += ln(prior.coerceAtLeast(1e-10))

            // 2. 각 피처의 가능도 (Naive Bayes: 독립 가정)
            for ((featureName, featureValue) in features) {
                val featureMap = likelihood[featureName] ?: continue
                val key = situation to featureValue
                val count = featureMap[key] ?: 0.0

                // 해당 피처에서 이 상황의 전체 관측 수
                val totalForSituation = featureMap.entries
                    .filter { it.key.first == situation }
                    .sumOf { it.value }
                    .coerceAtLeast(1.0)

                // 해당 피처의 고유 값 수 (smoothing용)
                val uniqueValues = featureMap.keys.map { it.second }.toSet().size.coerceAtLeast(2)

                val p = (count + SMOOTHING_ALPHA) / (totalForSituation + SMOOTHING_ALPHA * uniqueValues)
                logP += ln(p.coerceAtLeast(1e-10))
            }

            // 3. HMM 전이 확률
            if (previousSituation != null) {
                val transKey = previousSituation to situation
                val transCount = transitionCounts[transKey] ?: 0.0
                val transTotal = transitionTotals[previousSituation] ?: 1.0
                val transProb = (transCount + SMOOTHING_ALPHA) / (transTotal + SMOOTHING_ALPHA * activeSituations.size)
                logP += TRANSITION_WEIGHT * ln(transProb.coerceAtLeast(1e-10))
            }

            logPosteriors[situation] = logP
        }

        // log → probability (softmax)
        val maxLogP = logPosteriors.values.maxOrNull() ?: 0.0
        val expScores = logPosteriors.mapValues { exp(it.value - maxLogP) }
        val sumExp = expScores.values.sum().coerceAtLeast(1e-10)

        return expScores.map { (situation, score) ->
            situation to (score / sumExp).toFloat()
        }.sortedByDescending { it.second }
    }

    /**
     * 최종 분류 결과 (상위 1개 + 신뢰도).
     * 규칙 기반 결과와 동일한 인터페이스.
     */
    fun classifyTop(
        snapshot: ContextSnapshot,
        previousSituation: LifeSituation? = null
    ): Pair<LifeSituation, Float> {
        val results = classify(snapshot, previousSituation)
        return if (results.isEmpty()) {
            LifeSituation.UNKNOWN to 0.0f
        } else {
            results.first()
        }
    }

    // ─── 온라인 학습 ───

    /**
     * 관측 데이터로 모델 업데이트 (SituationChanged 이벤트 발생 시 호출).
     */
    fun update(
        snapshot: ContextSnapshot,
        confirmedSituation: LifeSituation,
        previousSituation: LifeSituation? = null
    ) {
        totalObservations++
        val features = extractFeatures(snapshot)
        val hour = snapshot.hourOfDay
        val dow = snapshot.dayOfWeek
        val timeIdx = hour * 7 + (dow - 1).coerceIn(0, 6)

        // 1. 시간대별 사전확률 업데이트
        val priorMap = timePrior[timeIdx]
        priorMap[confirmedSituation] = (priorMap[confirmedSituation] ?: 0.0) + 1.0

        // 2. 피처 가능도 업데이트
        for ((featureName, featureValue) in features) {
            val featureMap = likelihood.getOrPut(featureName) { mutableMapOf() }
            val key = confirmedSituation to featureValue
            featureMap[key] = (featureMap[key] ?: 0.0) + 1.0
        }

        // 3. 전이 확률 업데이트
        if (previousSituation != null) {
            val transKey = previousSituation to confirmedSituation
            transitionCounts[transKey] = (transitionCounts[transKey] ?: 0.0) + 1.0
            transitionTotals[previousSituation] = (transitionTotals[previousSituation] ?: 0.0) + 1.0
        }

        if (totalObservations.toInt() % 100 == 0) {
            Log.d(TAG, "모델 업데이트: ${totalObservations.toInt()}건 관측, " +
                  "${likelihood.size}개 피처, " +
                  "${transitionCounts.size}개 전이 기록")
        }
    }

    // ─── 피처 추출 ───

    /**
     * ContextSnapshot에서 이산화된 피처 맵 추출.
     * 키=피처이름, 값=이산화된 범주 문자열
     */
    private fun extractFeatures(snapshot: ContextSnapshot): Map<String, String> {
        val features = mutableMapOf<String, String>()

        // 이동 상태
        features["is_moving"] = snapshot.isMoving.toString()

        // 속도 범주화
        features["speed_cat"] = when {
            snapshot.speed == null || snapshot.speed < 0.3f -> "stationary"
            snapshot.speed < 1.5f -> "slow_walk"
            snapshot.speed < 3.0f -> "fast_walk"
            snapshot.speed < 10f -> "running"
            else -> "vehicle"
        }

        // 심박수 범주화
        features["hr_cat"] = when {
            snapshot.heartRate == null -> "unknown"
            snapshot.heartRate < 60 -> "resting"
            snapshot.heartRate < 80 -> "calm"
            snapshot.heartRate < 100 -> "light_activity"
            snapshot.heartRate < 130 -> "moderate"
            snapshot.heartRate < 160 -> "vigorous"
            else -> "max_effort"
        }

        // HR 트렌드
        features["hr_trend"] = snapshot.hrTrend ?: "STABLE"

        // 사람 수 범주화
        features["people_cat"] = when (snapshot.visiblePeople.size) {
            0 -> "none"
            1 -> "one"
            in 2..4 -> "small_group"
            else -> "large_group"
        }

        // 대화량 범주화
        features["speech_cat"] = when {
            snapshot.recentSpeechCount == 0 -> "silent"
            snapshot.recentSpeechCount <= 2 -> "quiet"
            snapshot.recentSpeechCount <= 5 -> "moderate"
            else -> "active"
        }

        // 실내/외
        features["indoor"] = (snapshot.isIndoors ?: true).toString()

        // 익숙한 장소
        features["familiar_loc"] = snapshot.familiarLocation.toString()

        // 시야 객체 존재 여부 (카테고리)
        features["has_objects"] = snapshot.visibleObjects.isNotEmpty().toString()
        features["has_text"] = snapshot.visibleText.isNotEmpty().toString()

        // 외국어 감지
        features["foreign_text"] = snapshot.foreignTextDetected.toString()

        // 움직임 강도 범주화
        features["movement_cat"] = when {
            snapshot.movementIntensity < 0.1f -> "still"
            snapshot.movementIntensity < 0.3f -> "slight"
            snapshot.movementIntensity < 0.6f -> "moderate"
            else -> "intense"
        }

        // 주변 소리 유형 (주요 카테고리)
        val sounds = snapshot.ambientSounds
        features["ambient_sound"] = when {
            sounds.any { it.contains("music", true) || it.contains("guitar", true) } -> "music"
            sounds.any { it.contains("speech", true) || it.contains("talk", true) } -> "speech"
            sounds.any { it.contains("traffic", true) || it.contains("car", true) } -> "traffic"
            sounds.any { it.contains("sizzle", true) || it.contains("water", true) } -> "kitchen"
            sounds.isEmpty() -> "silent"
            else -> "other"
        }

        // UserState
        features["user_state"] = snapshot.currentUserState.name

        // 걸음수 범주화
        features["steps_cat"] = when {
            snapshot.stepsLast5Min == 0 -> "zero"
            snapshot.stepsLast5Min < 30 -> "few"
            snapshot.stepsLast5Min < 100 -> "moderate"
            else -> "many"
        }

        // 시간대 (TimeSlot)
        features["time_slot"] = snapshot.timeSlot.name

        // 주중/주말
        features["is_weekend"] = snapshot.isWeekend.toString()

        return features
    }

    // ─── 기본 사전확률 초기화 ───

    private fun initializeDefaultPriors() {
        // 교사 일과 기반 기본 사전확률 (데이터 없을 때 폴백)
        for (dow in 0..6) {
            val isWeekday = dow < 5  // 0=일 → Calendar 변환 후 0=월~4=금
            for (hour in 0..23) {
                val timeIdx = hour * 7 + dow
                val priorMap = timePrior[timeIdx]

                if (isWeekday) {
                    when (hour) {
                        in 0..4 -> priorMap[LifeSituation.SLEEPING] = 5.0
                        in 5..6 -> {
                            priorMap[LifeSituation.MORNING_ROUTINE] = 3.0
                            priorMap[LifeSituation.SLEEPING] = 1.0
                        }
                        7 -> {
                            priorMap[LifeSituation.COMMUTING] = 3.0
                            priorMap[LifeSituation.MORNING_ROUTINE] = 1.0
                        }
                        in 8..11 -> {
                            priorMap[LifeSituation.TEACHING] = 4.0
                            priorMap[LifeSituation.AT_DESK_WORKING] = 2.0
                            priorMap[LifeSituation.IN_MEETING] = 1.0
                        }
                        in 12..13 -> {
                            priorMap[LifeSituation.LUNCH_BREAK] = 3.0
                            priorMap[LifeSituation.SOCIAL_GATHERING] = 1.0
                        }
                        in 14..16 -> {
                            priorMap[LifeSituation.TEACHING] = 4.0
                            priorMap[LifeSituation.AT_DESK_WORKING] = 2.0
                        }
                        17 -> {
                            priorMap[LifeSituation.COMMUTING] = 3.0
                            priorMap[LifeSituation.RUNNING] = 1.0
                        }
                        in 18..20 -> {
                            priorMap[LifeSituation.EVENING_WIND_DOWN] = 2.0
                            priorMap[LifeSituation.RUNNING] = 1.0
                            priorMap[LifeSituation.RELAXING_HOME] = 2.0
                        }
                        in 21..23 -> {
                            priorMap[LifeSituation.SLEEPING_PREP] = 2.0
                            priorMap[LifeSituation.RELAXING_HOME] = 2.0
                        }
                    }
                } else {
                    // 주말
                    when (hour) {
                        in 0..6 -> priorMap[LifeSituation.SLEEPING] = 5.0
                        in 7..8 -> priorMap[LifeSituation.MORNING_ROUTINE] = 3.0
                        in 9..11 -> {
                            priorMap[LifeSituation.RELAXING_HOME] = 2.0
                            priorMap[LifeSituation.RUNNING] = 1.0
                        }
                        in 12..13 -> priorMap[LifeSituation.LUNCH_BREAK] = 2.0
                        in 14..17 -> {
                            priorMap[LifeSituation.RELAXING_HOME] = 2.0
                            priorMap[LifeSituation.SOCIAL_GATHERING] = 1.0
                            priorMap[LifeSituation.SHOPPING] = 1.0
                        }
                        in 18..20 -> {
                            priorMap[LifeSituation.EVENING_WIND_DOWN] = 2.0
                            priorMap[LifeSituation.DINING_OUT] = 1.0
                        }
                        in 21..23 -> priorMap[LifeSituation.SLEEPING_PREP] = 3.0
                    }
                }
            }
        }

        // 기본 전이 확률 (자기 전이가 가장 높음)
        for (sit in activeSituations) {
            transitionCounts[sit to sit] = 10.0  // 자기 전이 (상황 유지)
            transitionTotals[sit] = 10.0
        }
        // 일반적 전이 패턴
        addDefaultTransition(LifeSituation.SLEEPING, LifeSituation.MORNING_ROUTINE, 3.0)
        addDefaultTransition(LifeSituation.MORNING_ROUTINE, LifeSituation.COMMUTING, 3.0)
        addDefaultTransition(LifeSituation.COMMUTING, LifeSituation.TEACHING, 3.0)
        addDefaultTransition(LifeSituation.TEACHING, LifeSituation.LUNCH_BREAK, 2.0)
        addDefaultTransition(LifeSituation.LUNCH_BREAK, LifeSituation.TEACHING, 2.0)
        addDefaultTransition(LifeSituation.TEACHING, LifeSituation.IN_MEETING, 1.0)
        addDefaultTransition(LifeSituation.IN_MEETING, LifeSituation.TEACHING, 1.0)
        addDefaultTransition(LifeSituation.TEACHING, LifeSituation.COMMUTING, 2.0)
        addDefaultTransition(LifeSituation.COMMUTING, LifeSituation.RELAXING_HOME, 2.0)
        addDefaultTransition(LifeSituation.RELAXING_HOME, LifeSituation.RUNNING, 1.0)
        addDefaultTransition(LifeSituation.RUNNING, LifeSituation.RELAXING_HOME, 2.0)
        addDefaultTransition(LifeSituation.RELAXING_HOME, LifeSituation.SLEEPING_PREP, 2.0)
        addDefaultTransition(LifeSituation.SLEEPING_PREP, LifeSituation.SLEEPING, 3.0)
        addDefaultTransition(LifeSituation.EVENING_WIND_DOWN, LifeSituation.SLEEPING_PREP, 2.0)
    }

    private fun addDefaultTransition(from: LifeSituation, to: LifeSituation, count: Double) {
        transitionCounts[from to to] = (transitionCounts[from to to] ?: 0.0) + count
        transitionTotals[from] = (transitionTotals[from] ?: 0.0) + count
    }

    // ─── 직렬화 (영속화용) ───

    /**
     * 모델 상태를 JSON 문자열로 직렬화 (DB 저장용).
     */
    fun serialize(): String {
        val sb = StringBuilder()
        sb.append("obs:${totalObservations.toInt()}\n")

        // 사전확률
        for (i in timePrior.indices) {
            val map = timePrior[i]
            if (map.isNotEmpty()) {
                val entries = map.entries.joinToString(",") { "${it.key.name}:${it.value}" }
                sb.append("tp:$i:$entries\n")
            }
        }

        // 가능도 (상위 1000개만)
        var likeCount = 0
        for ((feature, featureMap) in likelihood) {
            for ((key, count) in featureMap) {
                if (likeCount++ > 1000) break
                sb.append("lk:$feature:${key.first.name}:${key.second}:$count\n")
            }
        }

        // 전이
        for ((key, count) in transitionCounts) {
            sb.append("tr:${key.first.name}:${key.second.name}:$count\n")
        }

        return sb.toString()
    }

    /**
     * 직렬화된 문자열에서 모델 복원.
     */
    fun deserialize(data: String) {
        try {
            for (line in data.lines()) {
                val parts = line.split(":")
                when (parts.getOrNull(0)) {
                    "obs" -> totalObservations = parts[1].toDouble()
                    "tp" -> {
                        val idx = parts[1].toInt()
                        val entries = parts.drop(2).joinToString(":").split(",")
                        for (entry in entries) {
                            val (sitName, count) = entry.split(":")
                            val sit = LifeSituation.entries.find { it.name == sitName } ?: continue
                            timePrior[idx][sit] = count.toDouble()
                        }
                    }
                    "lk" -> {
                        if (parts.size >= 5) {
                            val feature = parts[1]
                            val sit = LifeSituation.entries.find { it.name == parts[2] } ?: continue
                            val value = parts[3]
                            val count = parts[4].toDouble()
                            val featureMap = likelihood.getOrPut(feature) { mutableMapOf() }
                            featureMap[sit to value] = count
                        }
                    }
                    "tr" -> {
                        if (parts.size >= 4) {
                            val from = LifeSituation.entries.find { it.name == parts[1] } ?: continue
                            val to = LifeSituation.entries.find { it.name == parts[2] } ?: continue
                            val count = parts[3].toDouble()
                            transitionCounts[from to to] = count
                            transitionTotals[from] = (transitionTotals[from] ?: 0.0) + count
                        }
                    }
                }
            }
            Log.i(TAG, "모델 복원: ${totalObservations.toInt()}건 관측")
        } catch (e: Exception) {
            Log.w(TAG, "모델 복원 실패: ${e.message}")
        }
    }

    /** 진단 정보 */
    fun diagnostics(): String {
        return "BayesianClassifier: ${totalObservations.toInt()}건 관측, " +
               "${likelihood.size}개 피처, ${transitionCounts.size}개 전이"
    }
}
