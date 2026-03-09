package com.xreal.nativear.expert

import android.content.ContentValues
import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.context.LifeSituation
import com.xreal.nativear.learning.IOutcomeRecorder
import org.json.JSONArray

/**
 * ExpertCompositionTracker — 어떤 전문가 조합이 어떤 상황에서 얼마나 효과적이었는지 추적.
 *
 * ExpertTeamManager가 팀 변경 시 호출 → expert_composition_records 테이블 기록.
 * StrategistService 반성 주기에서 getCompositionInsights()로 Gemini에 전달.
 * Gemini가 패턴 발견 → Directive(target="expert_team", "boost_priority:domain_id:+15")
 * → ExpertTeamManager가 해당 도메인 우선순위 조정.
 */
class ExpertCompositionTracker(
    private val database: UnifiedMemoryDatabase,
    private val outcomeTracker: IOutcomeRecorder? = null
) {
    private val TAG = "ExpertCompositionTracker"

    // 현재 활성 세션 ID (팀 변경 시 이전 세션 종료 → 새 세션 시작)
    private var currentSessionId: Long = -1L
    private var sessionStartMs: Long = 0L
    private var currentSituation: String = ""
    private var currentExpertIds: List<String> = emptyList()

    /**
     * ExpertTeamManager.activateDomain() / deactivateDomain() 이후 호출.
     * 이전 세션을 outcome_score와 함께 종료하고 새 세션 시작.
     */
    fun recordTeamChange(
        situation: LifeSituation,
        activeExpertIds: List<String>,
        activeDomainIds: List<String>
    ) {
        // 이전 세션 종료 (outcome 연결)
        if (currentSessionId >= 0) {
            closeCurrentSession()
        }

        if (activeExpertIds.isEmpty()) return

        // 새 세션 시작
        sessionStartMs = System.currentTimeMillis()
        currentSituation = situation.name
        currentExpertIds = activeExpertIds

        try {
            val db = database.writableDatabase
            val values = ContentValues().apply {
                put("situation", situation.name)
                put("expert_ids", JSONArray(activeExpertIds).toString())
                put("domain_ids", JSONArray(activeDomainIds).toString())
                put("session_start", sessionStartMs)
            }
            currentSessionId = db.insert("expert_composition_records", null, values)
            Log.d(TAG, "팀 조합 세션 시작: ${situation.name} | ${activeExpertIds.joinToString("+")}")
        } catch (e: Exception) {
            Log.w(TAG, "팀 조합 세션 시작 실패: ${e.message}")
            currentSessionId = -1L
        }
    }

    /**
     * 현재 세션 종료 — OutcomeTracker에서 가장 최근 개입 평균 효과 연결.
     */
    fun closeCurrentSession() {
        if (currentSessionId < 0) return

        val sessionEnd = System.currentTimeMillis()
        // 세션 중 OutcomeTracker 통계로 효과 측정
        val outcomeScore = try {
            val stats = outcomeTracker?.getOverallStats()
            stats?.let {
                if (it.totalInterventions > 0) it.acceptanceRate else null
            }
        } catch (_: Exception) { null }

        try {
            val db = database.writableDatabase
            val values = ContentValues().apply {
                put("session_end", sessionEnd)
                outcomeScore?.let { put("outcome_score", it) }
            }
            db.update("expert_composition_records", values, "id = ?", arrayOf(currentSessionId.toString()))
            Log.d(TAG, "팀 조합 세션 종료: id=$currentSessionId, score=${outcomeScore?.let { String.format("%.2f", it) } ?: "측정 불가"}")
        } catch (e: Exception) {
            Log.w(TAG, "팀 조합 세션 종료 실패: ${e.message}")
        }

        currentSessionId = -1L
        currentExpertIds = emptyList()
    }

    /**
     * StrategistService 반성 주기에서 읽는 조합 효율 요약.
     * Gemini가 어떤 조합이 효과적인지 파악해 Directive 생성에 활용.
     *
     * @param situation null이면 전체 상황 요약
     * @param limit 최근 N개 세션
     */
    fun getCompositionInsights(situation: LifeSituation? = null, limit: Int = 20): String? {
        return try {
            val db = database.readableDatabase
            val cutoff = System.currentTimeMillis() - 30 * 24 * 3600_000L  // 30일
            val baseQuery = if (situation != null)
                "SELECT situation, expert_ids, outcome_score FROM expert_composition_records WHERE session_start > ? AND session_end IS NOT NULL AND situation = ? ORDER BY session_start DESC LIMIT ?"
            else
                "SELECT situation, expert_ids, outcome_score FROM expert_composition_records WHERE session_start > ? AND session_end IS NOT NULL AND outcome_score IS NOT NULL ORDER BY session_start DESC LIMIT ?"

            val args = if (situation != null)
                arrayOf(cutoff.toString(), situation.name, limit.toString())
            else
                arrayOf(cutoff.toString(), limit.toString())

            val cursor = db.rawQuery(baseQuery, args)
            if (!cursor.moveToFirst()) { cursor.close(); return null }

            // 상황별 조합 효과 집계
            data class ComboKey(val situation: String, val expertIds: String)
            val comboScores = mutableMapOf<ComboKey, MutableList<Float>>()

            do {
                val sit = cursor.getString(0)
                val experts = cursor.getString(1)
                val score = if (cursor.isNull(2)) null else cursor.getFloat(2)
                score?.let {
                    comboScores.getOrPut(ComboKey(sit, experts)) { mutableListOf() }.add(it)
                }
            } while (cursor.moveToNext())
            cursor.close()

            if (comboScores.isEmpty()) return null

            val sb = StringBuilder()
            sb.appendLine("[전문가 팀 조합 효율 분석 — ExpertCompositionTracker]")

            comboScores.entries
                .sortedByDescending { (_, scores) -> scores.average() }
                .take(10)
                .forEach { (key, scores) ->
                    val avg = scores.average()
                    val count = scores.size
                    sb.appendLine("  [${key.situation}] ${key.expertIds} → 평균 효과: ${String.format("%.2f", avg)} (${count}회 측정)")
                }

            sb.toString()
        } catch (e: Exception) {
            Log.w(TAG, "getCompositionInsights 실패: ${e.message}")
            null
        }
    }

    /**
     * 앱 종료 시 마지막 세션 정리.
     */
    fun onDestroy() {
        closeCurrentSession()
    }
}
