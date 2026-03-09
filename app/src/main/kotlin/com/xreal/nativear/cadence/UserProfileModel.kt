package com.xreal.nativear.cadence

import org.json.JSONArray
import org.json.JSONObject

/**
 * Three-axis user personality model (Digital Twin).
 *
 * - DesireAxis (이드/Id): interests, preferred places, conversation topics, emotional tendencies
 * - EthicsAxis (초자아/Superego): safety awareness, privacy behavior, social responsiveness
 * - PracticalAxis (자아/Ego): daily routines, walking speed, tool usage frequency
 */
data class UserProfileModel(
    val desire: DesireAxis = DesireAxis(),
    val ethics: EthicsAxis = EthicsAxis(),
    val practical: PracticalAxis = PracticalAxis(),
    val physiology: PhysiologyAxis = PhysiologyAxis(),
    val locationPatterns: List<LocationPattern> = emptyList(),
    val interactionPatterns: List<InteractionPattern> = emptyList(),
    val lastUpdated: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("desire", desire.toJson())
        put("ethics", ethics.toJson())
        put("practical", practical.toJson())
        put("physiology", physiology.toJson())
        put("locationPatterns", JSONArray().apply {
            locationPatterns.forEach { put(it.toJson()) }
        })
        put("interactionPatterns", JSONArray().apply {
            interactionPatterns.forEach { put(it.toJson()) }
        })
        put("lastUpdated", lastUpdated)
    }

    fun toSummaryString(): String = buildString {
        appendLine("[욕구(이드)]")
        appendLine("- 관심 주제: ${desire.topTopics.take(5).joinToString()}")
        appendLine("- 자주 가는 장소: ${locationPatterns.filter { it.isFamiliar }.take(3).joinToString { it.label }}")
        appendLine("- 감정 분포: ${desire.emotionDistribution.entries.sortedByDescending { it.value }.take(3).joinToString { "${it.key}=${String.format("%.0f", it.value * 100)}%" }}")
        appendLine()
        appendLine("[윤리(초자아)]")
        appendLine("- 안전 인식도: ${String.format("%.0f", ethics.safetyAwareness * 100)}%")
        appendLine("- 사회적 반응성: ${String.format("%.0f", ethics.socialResponsiveness * 100)}%")
        appendLine()
        appendLine("[실용(자아)]")
        appendLine("- 평균 보행 속도: ${String.format("%.1f", practical.avgWalkingSpeedMs)}m/s")
        appendLine("- 일과 패턴: ${practical.dailyRoutine.entries.take(5).joinToString { "${it.key}시→${it.value}" }}")
        appendLine("- 대화 빈도: ${interactionPatterns.take(3).joinToString { "${it.personName}(${it.meetCount}회)" }}")
        appendLine()
        appendLine("[생리(디지털트윈)]")
        appendLine("- ${physiology.toSummaryString()}")
    }

    companion object {
        fun fromJson(json: JSONObject): UserProfileModel = UserProfileModel(
            desire = DesireAxis.fromJson(json.optJSONObject("desire") ?: JSONObject()),
            ethics = EthicsAxis.fromJson(json.optJSONObject("ethics") ?: JSONObject()),
            practical = PracticalAxis.fromJson(json.optJSONObject("practical") ?: JSONObject()),
            physiology = PhysiologyAxis.fromJson(json.optJSONObject("physiology") ?: JSONObject()),
            locationPatterns = json.optJSONArray("locationPatterns")?.let { arr ->
                (0 until arr.length()).map { LocationPattern.fromJson(arr.getJSONObject(it)) }
            } ?: emptyList(),
            interactionPatterns = json.optJSONArray("interactionPatterns")?.let { arr ->
                (0 until arr.length()).map { InteractionPattern.fromJson(arr.getJSONObject(it)) }
            } ?: emptyList(),
            lastUpdated = json.optLong("lastUpdated", System.currentTimeMillis())
        )
    }
}

data class DesireAxis(
    val topTopics: List<String> = emptyList(),
    val emotionDistribution: Map<String, Float> = emptyMap(),
    val preferredActivities: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("topTopics", JSONArray(topTopics))
        put("emotionDistribution", JSONObject(emotionDistribution))
        put("preferredActivities", JSONArray(preferredActivities))
    }

    companion object {
        fun fromJson(json: JSONObject): DesireAxis = DesireAxis(
            topTopics = json.optJSONArray("topTopics")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            emotionDistribution = json.optJSONObject("emotionDistribution")?.let { obj ->
                obj.keys().asSequence().associateWith { obj.getDouble(it).toFloat() }
            } ?: emptyMap(),
            preferredActivities = json.optJSONArray("preferredActivities")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
        )
    }
}

data class EthicsAxis(
    val safetyAwareness: Float = 0.5f,
    val socialResponsiveness: Float = 0.5f,
    val privacyBehavior: Float = 0.5f
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("safetyAwareness", safetyAwareness)
        put("socialResponsiveness", socialResponsiveness)
        put("privacyBehavior", privacyBehavior)
    }

    companion object {
        fun fromJson(json: JSONObject): EthicsAxis = EthicsAxis(
            safetyAwareness = json.optDouble("safetyAwareness", 0.5).toFloat(),
            socialResponsiveness = json.optDouble("socialResponsiveness", 0.5).toFloat(),
            privacyBehavior = json.optDouble("privacyBehavior", 0.5).toFloat()
        )
    }
}

data class PracticalAxis(
    val dailyRoutine: Map<Int, String> = emptyMap(), // hour -> activity
    val avgWalkingSpeedMs: Float = 1.2f,
    val toolUsageFrequency: Map<String, Int> = emptyMap()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("dailyRoutine", JSONObject().also { obj ->
            dailyRoutine.forEach { (k, v) -> obj.put(k.toString(), v) }
        })
        put("avgWalkingSpeedMs", avgWalkingSpeedMs)
        put("toolUsageFrequency", JSONObject(toolUsageFrequency))
    }

    companion object {
        fun fromJson(json: JSONObject): PracticalAxis = PracticalAxis(
            dailyRoutine = json.optJSONObject("dailyRoutine")?.let { obj ->
                obj.keys().asSequence().associate { it.toInt() to obj.getString(it) }
            } ?: emptyMap(),
            avgWalkingSpeedMs = json.optDouble("avgWalkingSpeedMs", 1.2).toFloat(),
            toolUsageFrequency = json.optJSONObject("toolUsageFrequency")?.let { obj ->
                obj.keys().asSequence().associate { it to obj.getInt(it) }
            } ?: emptyMap()
        )
    }
}

data class LocationPattern(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val visitCount: Int,
    val isFamiliar: Boolean = visitCount >= 3
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("label", label)
        put("latitude", latitude)
        put("longitude", longitude)
        put("visitCount", visitCount)
        put("isFamiliar", isFamiliar)
    }

    companion object {
        fun fromJson(json: JSONObject): LocationPattern = LocationPattern(
            label = json.optString("label", ""),
            latitude = json.optDouble("latitude", 0.0),
            longitude = json.optDouble("longitude", 0.0),
            visitCount = json.optInt("visitCount", 0)
        )
    }
}

data class InteractionPattern(
    val personId: Long,
    val personName: String,
    val meetCount: Int,
    val dominantEmotion: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("personId", personId)
        put("personName", personName)
        put("meetCount", meetCount)
        if (dominantEmotion != null) put("dominantEmotion", dominantEmotion)
    }

    companion object {
        fun fromJson(json: JSONObject): InteractionPattern = InteractionPattern(
            personId = json.optLong("personId", 0),
            personName = json.optString("personName", "Unknown"),
            meetCount = json.optInt("meetCount", 0),
            dominantEmotion = if (json.has("dominantEmotion")) json.getString("dominantEmotion") else null
        )
    }
}

/**
 * PhysiologyAxis — 생리적 기저선 (PC 서버 예측 엔진에서 계산, 앱으로 동기화).
 *
 * 10년 DuckDB 이력 기반:
 * - 안정시 HR, LTHR, Critical Speed, 수면 효율
 * - 러닝 역학 시그니처 (stiffness, GCT, 러너 타입)
 * - 회복/부상 위험 (일일 갱신)
 */
data class PhysiologyAxis(
    // 심혈관 기저선
    val restingHr: Int = 60,
    val lthrBpm: Int = 165,
    val criticalSpeedMps: Float = 3.0f,
    val criticalSpeedPace: Float = 5.56f, // min/km
    // 수면 기저선
    val avgSleepEfficiency: Float = 0.78f,
    val avgSleepHours: Float = 7.0f,
    // 러닝 역학 시그니처
    val runnerType: String = "unknown",    // elastic/grinder/balanced
    val avgStiffnessKn: Float = 0f,
    val avgGctMs: Int = 0,
    val avgVerticalOscCm: Float = 0f,
    // 일일 예측 (최신)
    val recoveryScore: Float = 0.7f,       // 0~1
    val recommendation: String = "moderate", // rest/easy/moderate/hard
    val injuryRiskLevel: String = "low",    // low/moderate/high
    val acwr: Float = 1.0f,
    val hrTrend: String = "STABLE",         // RISING/FALLING/STABLE
    // 메타
    val lastSyncedAt: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("restingHr", restingHr)
        put("lthrBpm", lthrBpm)
        put("criticalSpeedMps", criticalSpeedMps)
        put("criticalSpeedPace", criticalSpeedPace)
        put("avgSleepEfficiency", avgSleepEfficiency)
        put("avgSleepHours", avgSleepHours)
        put("runnerType", runnerType)
        put("avgStiffnessKn", avgStiffnessKn)
        put("avgGctMs", avgGctMs)
        put("avgVerticalOscCm", avgVerticalOscCm)
        put("recoveryScore", recoveryScore)
        put("recommendation", recommendation)
        put("injuryRiskLevel", injuryRiskLevel)
        put("acwr", acwr)
        put("hrTrend", hrTrend)
        put("lastSyncedAt", lastSyncedAt)
    }

    fun toSummaryString(): String = buildString {
        append("HR기저:${restingHr}bpm, LTHR:${lthrBpm}, CS:${String.format("%.2f", criticalSpeedPace)}min/km")
        if (runnerType != "unknown") append(", 타입:$runnerType")
        append(", 회복:${String.format("%.0f", recoveryScore * 100)}%($recommendation)")
        append(", 부상:$injuryRiskLevel")
    }

    companion object {
        fun fromJson(json: JSONObject): PhysiologyAxis = PhysiologyAxis(
            restingHr = json.optInt("restingHr", 60),
            lthrBpm = json.optInt("lthrBpm", 165),
            criticalSpeedMps = json.optDouble("criticalSpeedMps", 3.0).toFloat(),
            criticalSpeedPace = json.optDouble("criticalSpeedPace", 5.56).toFloat(),
            avgSleepEfficiency = json.optDouble("avgSleepEfficiency", 0.78).toFloat(),
            avgSleepHours = json.optDouble("avgSleepHours", 7.0).toFloat(),
            runnerType = json.optString("runnerType", "unknown"),
            avgStiffnessKn = json.optDouble("avgStiffnessKn", 0.0).toFloat(),
            avgGctMs = json.optInt("avgGctMs", 0),
            avgVerticalOscCm = json.optDouble("avgVerticalOscCm", 0.0).toFloat(),
            recoveryScore = json.optDouble("recoveryScore", 0.7).toFloat(),
            recommendation = json.optString("recommendation", "moderate"),
            injuryRiskLevel = json.optString("injuryRiskLevel", "low"),
            acwr = json.optDouble("acwr", 1.0).toFloat(),
            hrTrend = json.optString("hrTrend", "STABLE"),
            lastSyncedAt = json.optLong("lastSyncedAt", 0L)
        )
    }
}
