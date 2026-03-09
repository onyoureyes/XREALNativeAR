package com.xreal.nativear.agent

import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import org.json.JSONObject

/**
 * AgentIdentityStore — 에이전트의 정체성 저장소.
 *
 * ## 저장 내용
 * 1. **에피소드 메모리** (Reflexion): 과거 목표 추구 경험 (성공/실패/거부)
 * 2. **임무 거부 기록**: 왜 거부했는가 → 가치관과 한계를 정의
 * 3. **학습된 전략**: 어떤 상황에서 어떤 접근이 효과적이었는가
 *
 * ## DB 구조
 * structured_data 테이블 재사용:
 * - domain = "agent_episode" / "agent_refusal" / "agent_strategy"
 * - data_key = "{agentId}_{timestamp}"
 * - value = JSON
 * - tags = agentId
 *
 * ## 설계 철학
 * 에이전트는 자기 삭제하지 않음. 거부 사유를 축적하여 정체성을 형성.
 * "나는 이런 요청은 할 수 없다" → 가치관.
 * "나는 이런 상황에서 이렇게 성공했다" → 경험.
 * 이 기록들이 다음 목표 추구 시 Reflexion 메모리로 활용됨.
 */
class AgentIdentityStore(
    private val database: UnifiedMemoryDatabase
) {
    companion object {
        private const val TAG = "AgentIdentity"
        private const val DOMAIN_EPISODE = "agent_episode"
        private const val DOMAIN_REFUSAL = "agent_refusal"
        private const val DOMAIN_STRATEGY = "agent_strategy"
    }

    // ─── 에피소드 메모리 (Reflexion) ───

    /**
     * 목표 추구 에피소드 저장.
     * 성공/실패/부분/거부 모두 저장 — 다음 시도에서 학습.
     */
    fun saveEpisode(
        agentId: String,
        goal: String,
        result: AgentResult,
        chainSummary: String
    ) {
        try {
            val now = System.currentTimeMillis()
            val key = "${agentId}_$now"

            val json = JSONObject().apply {
                put("agent_id", agentId)
                put("goal", goal.take(200))
                put("status", result.status.name)
                put("answer", result.answer.take(300))
                put("confidence", result.confidence)
                put("depth", result.depth)
                put("tokens_used", result.tokensUsed)
                put("chain_summary", chainSummary.take(500))
                put("timestamp", now)
            }

            database.upsertStructuredData(DOMAIN_EPISODE, key, json.toString(), agentId)
            Log.d(TAG, "에피소드 저장: [$agentId] ${result.status} — \"${goal.take(50)}\"")
        } catch (e: Exception) {
            Log.w(TAG, "에피소드 저장 실패: ${e.message}")
        }
    }

    /**
     * 유사 목표에 대한 과거 에피소드 조회.
     * Reflexion: 과거 경험을 다음 시도의 프롬프트에 주입.
     */
    fun getEpisodicMemories(agentId: String, currentGoal: String, limit: Int = 3): List<AgentEpisode> {
        return try {
            val records = database.queryStructuredData(domain = DOMAIN_EPISODE, tags = agentId, limit = limit * 3)
            records.mapNotNull { record ->
                try {
                    val json = JSONObject(record.value)
                    AgentEpisode(
                        agentId = json.optString("agent_id"),
                        goal = json.optString("goal"),
                        status = json.optString("status"),
                        reflection = json.optString("chain_summary"),
                        depth = json.optInt("depth"),
                        tokensUsed = json.optInt("tokens_used"),
                        timestamp = json.optLong("timestamp")
                    )
                } catch (_: Exception) { null }
            }
            .sortedByDescending { it.timestamp }
            .take(limit)
        } catch (e: Exception) {
            Log.w(TAG, "에피소드 조회 실패: ${e.message}")
            emptyList()
        }
    }

    // ─── 임무 거부 기록 (정체성) ───

    /**
     * 임무 거부 사유를 DB에 영구 기록.
     * 에이전트의 가치관과 한계를 정의하는 정체성의 일부.
     */
    fun recordRefusal(
        agentId: String,
        goal: String,
        reason: String,
        selfAssessment: String,
        alternativeSuggestion: String?
    ) {
        try {
            val now = System.currentTimeMillis()
            val key = "${agentId}_refusal_$now"

            val json = JSONObject().apply {
                put("agent_id", agentId)
                put("goal", goal.take(200))
                put("reason", reason.take(300))
                put("self_assessment", selfAssessment.take(200))
                alternativeSuggestion?.let { put("alternative", it.take(200)) }
                put("timestamp", now)
            }

            database.upsertStructuredData(DOMAIN_REFUSAL, key, json.toString(), agentId)
            Log.i(TAG, "임무 거부 기록: [$agentId] \"${goal.take(50)}\" → $reason")
        } catch (e: Exception) {
            Log.w(TAG, "거부 기록 실패: ${e.message}")
        }
    }

    /**
     * 에이전트의 과거 거부 기록 조회.
     * 프롬프트에 주입하여 "나는 이런 것은 하지 않는다"는 정체성 형성.
     */
    fun getRefusals(agentId: String, limit: Int = 5): List<MissionRefusal> {
        return try {
            val records = database.queryStructuredData(domain = DOMAIN_REFUSAL, tags = agentId, limit = limit)
            records.mapNotNull { record ->
                try {
                    val json = JSONObject(record.value)
                    MissionRefusal(
                        agentId = json.optString("agent_id"),
                        goal = json.optString("goal"),
                        reason = json.optString("reason"),
                        selfAssessment = json.optString("self_assessment", ""),
                        alternativeSuggestion = json.optString("alternative", null),
                        timestamp = json.optLong("timestamp")
                    )
                } catch (_: Exception) { null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "거부 기록 조회 실패: ${e.message}")
            emptyList()
        }
    }

    // ─── 학습된 전략 ───

    /**
     * 특정 상황에서 효과적이었던 전략 저장.
     * 도구 조합, 추론 패턴, 접근법 등.
     */
    fun saveStrategy(agentId: String, situation: String, strategy: String, effectiveness: Float) {
        try {
            val key = "${agentId}_${situation.hashCode()}"
            val existing = try {
                database.getStructuredDataExact(DOMAIN_STRATEGY, key)
            } catch (_: Exception) { null }

            val json = if (existing != null) {
                // EMA 업데이트
                val old = JSONObject(existing.value)
                val oldEff = old.optDouble("effectiveness", 0.5).toFloat()
                val newEff = oldEff * 0.8f + effectiveness * 0.2f
                old.put("strategy", strategy.take(300))
                old.put("effectiveness", newEff)
                old.put("use_count", old.optInt("use_count", 0) + 1)
                old.put("updated_at", System.currentTimeMillis())
                old
            } else {
                JSONObject().apply {
                    put("agent_id", agentId)
                    put("situation", situation.take(100))
                    put("strategy", strategy.take(300))
                    put("effectiveness", effectiveness)
                    put("use_count", 1)
                    put("created_at", System.currentTimeMillis())
                    put("updated_at", System.currentTimeMillis())
                }
            }

            database.upsertStructuredData(DOMAIN_STRATEGY, key, json.toString(), agentId)
        } catch (e: Exception) {
            Log.w(TAG, "전략 저장 실패: ${e.message}")
        }
    }

    /**
     * 에이전트 정체성 요약 (디버그/HUD 표시용).
     */
    fun getIdentitySummary(agentId: String): String {
        val episodes = getEpisodicMemories(agentId, "", limit = 10)
        val refusals = getRefusals(agentId, limit = 10)
        val achieved = episodes.count { it.status == "ACHIEVED" }
        val failed = episodes.count { it.status == "FAILED" || it.status == "PARTIAL" }
        val refused = refusals.size

        return buildString {
            appendLine("[$agentId 정체성]")
            appendLine("  경험: 성공 $achieved, 실패 $failed, 거부 $refused")
            if (refusals.isNotEmpty()) {
                appendLine("  거부 가치관:")
                refusals.take(3).forEach { r ->
                    appendLine("    - ${r.reason.take(60)}")
                }
            }
        }
    }
}
