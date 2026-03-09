package com.xreal.nativear.learning

import android.content.Context
import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ConversationDataExporter — ai_interventions + memory_nodes → ChatML JSONL 변환.
 *
 * ## LoRA 학습용 대화 데이터 내보내기
 *
 * Qwen3 ChatML 형식으로 변환:
 * ```json
 * {"messages": [
 *   {"role": "system", "content": "..."},
 *   {"role": "user", "content": "..."},
 *   {"role": "assistant", "content": "..."}
 * ]}
 * ```
 *
 * ## 데이터 소스
 * 1. ai_interventions (OutcomeTracker) — expert_id, situation, action, outcome
 *    → outcome=FOLLOWED인 것만 (사용자가 수용한 = 좋은 응답)
 * 2. memory_nodes (MemoryRepository) — role=user/assistant 대화 기록
 *    → level=0 (원본 메모리)만
 *
 * ## 라벨링 전략
 * - FOLLOWED: 학습 데이터로 사용 (좋은 응답)
 * - DISMISSED: 네거티브로 DPO 가능하지만 현재는 제외
 * - IGNORED: 제외
 *
 * ## 출력
 * - JSONL 파일 (한 줄에 하나의 대화)
 * - Drive "xreal_training_data/" 폴더에 업로드 (DriveUploadWorker)
 */
class ConversationDataExporter(
    private val context: Context,
    private val database: UnifiedMemoryDatabase
) {
    companion object {
        private const val TAG = "ConversationDataExporter"
        const val MIN_ROWS_FOR_UPLOAD = 30      // LoRA는 소량으로도 효과 있음
        private const val WINDOW_DAYS = 90
    }

    /**
     * 대화 데이터를 ChatML JSONL로 내보내기.
     * @return JSONL 파일 (캐시 디렉터리), 실패 시 null
     */
    fun exportToJsonl(): File? {
        return try {
            val conversations = buildConversations()
            if (conversations.isEmpty()) {
                Log.w(TAG, "학습 가능한 대화 데이터 없음")
                return null
            }

            val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val jsonlFile = File(context.cacheDir, "lora_conversations_$dateStr.jsonl")

            jsonlFile.bufferedWriter().use { writer ->
                conversations.forEach { conv ->
                    writer.write(conv.toString())
                    writer.newLine()
                }
            }

            Log.i(TAG, "JSONL 생성 완료: ${conversations.size}개 대화 → ${jsonlFile.absolutePath} (${jsonlFile.length() / 1024}KB)")
            jsonlFile

        } catch (e: Exception) {
            Log.e(TAG, "exportToJsonl 예외: ${e.message}")
            null
        }
    }

    /**
     * 내보낼 수 있는 대화 수 확인.
     */
    fun getExportableConversationCount(): Int {
        return try {
            getFollowedInterventionCount() + getConversationMemoryPairCount()
        } catch (e: Exception) {
            Log.e(TAG, "대화 수 조회 실패: ${e.message}")
            0
        }
    }

    // ─── 내부 구현 ───

    /**
     * 두 소스에서 ChatML 대화 데이터 구축:
     * 1. ai_interventions (FOLLOWED) → system: situation context, user: implicit query, assistant: action
     * 2. memory_nodes (user→assistant 쌍) → 직접 대화 기록
     */
    private fun buildConversations(): List<JSONObject> {
        val conversations = mutableListOf<JSONObject>()

        // 소스 1: FOLLOWED 개입 → 학습 데이터
        conversations.addAll(buildFromInterventions())

        // 소스 2: 대화 메모리 쌍
        conversations.addAll(buildFromMemoryPairs())

        Log.d(TAG, "대화 데이터 구축: 개입=${conversations.size - getConversationMemoryPairCount()}, 메모리 쌍=${getConversationMemoryPairCount()}")
        return conversations
    }

    /**
     * ai_interventions 테이블에서 FOLLOWED 결과를 ChatML 대화로 변환.
     *
     * 컨텍스트: situation + expert_id → system prompt
     * 사용자 입력: context_summary (없으면 situation 기반 생성)
     * 어시스턴트 응답: action
     */
    private fun buildFromInterventions(): List<JSONObject> {
        val cutoffMs = System.currentTimeMillis() - WINDOW_DAYS * 24 * 60 * 60 * 1000L
        val results = mutableListOf<JSONObject>()

        try {
            val db = database.readableDatabase
            val cursor = db.rawQuery(
                """
                SELECT expert_id, situation, action, context_summary
                FROM ai_interventions
                WHERE outcome = 1
                  AND timestamp > ?
                  AND action IS NOT NULL
                  AND LENGTH(action) > 10
                ORDER BY timestamp DESC
                LIMIT 500
                """.trimIndent(),
                arrayOf(cutoffMs.toString())
            )

            cursor.use {
                while (it.moveToNext()) {
                    val expertId = it.getString(0) ?: "assistant"
                    val situation = it.getString(1) ?: "UNKNOWN"
                    val action = it.getString(2) ?: continue
                    val contextSummary = it.getString(3)

                    // ChatML 대화 구성
                    val messages = JSONArray()

                    // system: 상황 컨텍스트
                    messages.put(JSONObject().apply {
                        put("role", "system")
                        put("content", "당신은 AR 글래스 AI 어시스턴트입니다. 현재 상황: $situation. 전문 역할: $expertId.")
                    })

                    // user: 컨텍스트 기반 질의 (context_summary가 있으면 활용)
                    val userContent = if (!contextSummary.isNullOrBlank()) {
                        contextSummary
                    } else {
                        situationToQuery(situation)
                    }
                    messages.put(JSONObject().apply {
                        put("role", "user")
                        put("content", userContent)
                    })

                    // assistant: 사용자가 수용한 응답
                    messages.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", action)
                    })

                    results.add(JSONObject().apply {
                        put("messages", messages)
                    })
                }
            }
            Log.d(TAG, "개입 데이터: ${results.size}개 FOLLOWED 대화")
        } catch (e: Exception) {
            Log.e(TAG, "개입 데이터 쿼리 실패: ${e.message}")
        }

        return results
    }

    /**
     * memory_nodes에서 user→assistant 연속 쌍을 ChatML로 변환.
     * level=0 (원본), role='user' 바로 다음 role='assistant' 매칭.
     */
    private fun buildFromMemoryPairs(): List<JSONObject> {
        val cutoffMs = System.currentTimeMillis() - WINDOW_DAYS * 24 * 60 * 60 * 1000L
        val results = mutableListOf<JSONObject>()

        try {
            val db = database.readableDatabase
            // user→assistant 연속 쌍 조회
            val cursor = db.rawQuery(
                """
                SELECT m1.content AS user_content,
                       m2.content AS assistant_content,
                       m1.metadata AS user_meta
                FROM memory_nodes m1
                INNER JOIN memory_nodes m2
                  ON m2.id = (
                    SELECT id FROM memory_nodes
                    WHERE role = 'assistant'
                      AND timestamp > m1.timestamp
                      AND level = 0
                    ORDER BY timestamp ASC
                    LIMIT 1
                  )
                WHERE m1.role = 'user'
                  AND m1.level = 0
                  AND m1.timestamp > ?
                  AND LENGTH(m1.content) > 5
                  AND LENGTH(m2.content) > 10
                ORDER BY m1.timestamp DESC
                LIMIT 300
                """.trimIndent(),
                arrayOf(cutoffMs.toString())
            )

            cursor.use {
                while (it.moveToNext()) {
                    val userContent = it.getString(0) ?: continue
                    val assistantContent = it.getString(1) ?: continue

                    val messages = JSONArray()
                    messages.put(JSONObject().apply {
                        put("role", "system")
                        put("content", "당신은 AR 글래스 AI 어시스턴트입니다. 사용자의 질문에 한국어로 간결하게 답하세요.")
                    })
                    messages.put(JSONObject().apply {
                        put("role", "user")
                        put("content", userContent)
                    })
                    messages.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", assistantContent)
                    })

                    results.add(JSONObject().apply {
                        put("messages", messages)
                    })
                }
            }
            Log.d(TAG, "메모리 쌍 데이터: ${results.size}개 대화")
        } catch (e: Exception) {
            Log.e(TAG, "메모리 쌍 쿼리 실패: ${e.message}")
        }

        return results
    }

    private fun getFollowedInterventionCount(): Int {
        return try {
            val cutoffMs = System.currentTimeMillis() - WINDOW_DAYS * 24 * 60 * 60 * 1000L
            val cursor = database.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM ai_interventions WHERE outcome = 1 AND timestamp > ? AND LENGTH(action) > 10",
                arrayOf(cutoffMs.toString())
            )
            cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (_: Exception) { 0 }
    }

    private fun getConversationMemoryPairCount(): Int {
        return try {
            val cutoffMs = System.currentTimeMillis() - WINDOW_DAYS * 24 * 60 * 60 * 1000L
            val cursor = database.readableDatabase.rawQuery(
                """SELECT COUNT(*) FROM memory_nodes
                   WHERE role = 'user' AND level = 0 AND timestamp > ? AND LENGTH(content) > 5""",
                arrayOf(cutoffMs.toString())
            )
            cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (_: Exception) { 0 }
    }

    // ─── DPO (Direct Preference Optimization) 내보내기 ───

    /**
     * DPO 학습용 선호도 쌍 데이터를 JSONL로 내보내기.
     *
     * 형식: {"preferred": [...messages], "rejected": [...messages]}
     * - preferred: FOLLOWED 결과 (사용자 수용)
     * - rejected: DISMISSED 결과 (사용자 거부)
     * - 같은 situation + expert_id 조합에서 쌍을 구성
     *
     * @return JSONL 파일, 실패 시 null
     */
    fun exportDPOJsonl(): File? {
        return try {
            val pairs = buildDPOPairs()
            if (pairs.isEmpty()) {
                Log.w(TAG, "DPO 쌍 데이터 없음")
                return null
            }

            val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val dpoFile = File(context.cacheDir, "dpo_pairs_$dateStr.jsonl")

            dpoFile.bufferedWriter().use { writer ->
                pairs.forEach { pair ->
                    writer.write(pair.toString())
                    writer.newLine()
                }
            }

            Log.i(TAG, "DPO JSONL 생성: ${pairs.size}개 선호도 쌍 → ${dpoFile.absolutePath}")
            dpoFile
        } catch (e: Exception) {
            Log.e(TAG, "exportDPOJsonl 예외: ${e.message}")
            null
        }
    }

    private fun buildDPOPairs(): List<JSONObject> {
        val cutoffMs = System.currentTimeMillis() - WINDOW_DAYS * 24 * 60 * 60 * 1000L
        val pairs = mutableListOf<JSONObject>()

        try {
            val db = database.readableDatabase

            // FOLLOWED 기록 (preferred)
            val followedMap = mutableMapOf<String, MutableList<Pair<String, String>>>() // key → (context, action)
            db.rawQuery(
                """SELECT expert_id, situation, action, context_summary
                   FROM ai_interventions
                   WHERE outcome = 1 AND timestamp > ? AND LENGTH(action) > 10
                   ORDER BY timestamp DESC LIMIT 300""",
                arrayOf(cutoffMs.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val key = "${c.getString(0)}_${c.getString(1)}"
                    val ctx = c.getString(3) ?: situationToQuery(c.getString(1) ?: "UNKNOWN")
                    val action = c.getString(2) ?: continue
                    followedMap.getOrPut(key) { mutableListOf() }.add(ctx to action)
                }
            }

            // DISMISSED 기록 (rejected) — 같은 expert+situation에서 쌍 찾기
            db.rawQuery(
                """SELECT expert_id, situation, action, context_summary
                   FROM ai_interventions
                   WHERE outcome = 2 AND timestamp > ? AND LENGTH(action) > 10
                   ORDER BY timestamp DESC LIMIT 300""",
                arrayOf(cutoffMs.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val key = "${c.getString(0)}_${c.getString(1)}"
                    val preferred = followedMap[key]?.firstOrNull() ?: continue
                    val rejectedCtx = c.getString(3) ?: situationToQuery(c.getString(1) ?: "UNKNOWN")
                    val rejectedAction = c.getString(2) ?: continue

                    val expertId = c.getString(0) ?: "assistant"
                    val situation = c.getString(1) ?: "UNKNOWN"
                    val systemMsg = "당신은 AR 글래스 AI 어시스턴트입니다. 현재 상황: $situation. 전문 역할: $expertId."

                    fun buildMessages(ctx: String, action: String) = JSONArray().apply {
                        put(JSONObject().apply { put("role", "system"); put("content", systemMsg) })
                        put(JSONObject().apply { put("role", "user"); put("content", ctx) })
                        put(JSONObject().apply { put("role", "assistant"); put("content", action) })
                    }

                    pairs.add(JSONObject().apply {
                        put("preferred", buildMessages(preferred.first, preferred.second))
                        put("rejected", buildMessages(rejectedCtx, rejectedAction))
                    })
                }
            }

            Log.d(TAG, "DPO 쌍: ${pairs.size}개 (FOLLOWED↔DISMISSED)")
        } catch (e: Exception) {
            Log.e(TAG, "DPO 쌍 쿼리 실패: ${e.message}")
        }

        return pairs
    }

    // ─── 감정 연결 대화 내보내기 ───

    /**
     * SceneDatabase의 interactions (감정 + 대화) → ChatML JSONL.
     * 감정 인식 반응 학습용: {expression, audio_emotion, transcript} → AI 반응
     */
    fun exportEmotionLinkedJsonl(): File? {
        return try {
            val sceneDb: com.xreal.nativear.SceneDatabase? = try {
                org.koin.java.KoinJavaComponent.getKoin().getOrNull()
            } catch (_: Exception) { null }
            if (sceneDb == null) return null

            val conversations = mutableListOf<JSONObject>()
            val cutoffMs = System.currentTimeMillis() - WINDOW_DAYS * 24 * 60 * 60 * 1000L

            sceneDb.readableDatabase.rawQuery(
                """SELECT i.transcript, i.expression, i.expression_score, i.audio_emotion,
                          p.name
                   FROM interactions i
                   LEFT JOIN persons p ON i.person_id = p.id
                   WHERE i.timestamp > ? AND i.transcript IS NOT NULL AND LENGTH(i.transcript) > 10
                   ORDER BY i.timestamp DESC LIMIT 200""",
                arrayOf(cutoffMs.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val transcript = c.getString(0) ?: continue
                    val expression = c.getString(1)
                    val expressionScore = if (!c.isNull(2)) c.getFloat(2) else null
                    val audioEmotion = c.getString(3)
                    val personName = c.getString(4) ?: "상대방"

                    // 감정 컨텍스트 구성
                    val emotionCtx = buildString {
                        expression?.let { append("표정: $it") }
                        expressionScore?.let { append(" (${String.format("%.0f", it * 100)}%)") }
                        audioEmotion?.let {
                            if (isNotEmpty()) append(", ")
                            append("음성 감정: $it")
                        }
                    }
                    if (emotionCtx.isBlank()) continue

                    val messages = JSONArray()
                    messages.put(JSONObject().apply {
                        put("role", "system")
                        put("content", "당신은 AR 글래스 AI 어시스턴트입니다. 대화 상대의 감정 상태를 인지하고 공감적으로 반응하세요.")
                    })
                    messages.put(JSONObject().apply {
                        put("role", "user")
                        put("content", "[$personName] $transcript\n[감정 분석] $emotionCtx")
                    })
                    // 이상적 응답은 없으므로 감정 메타데이터만 기록 (SFT보다 분류 학습용)
                    messages.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", "${personName}의 감정: ${expression ?: audioEmotion ?: "알수없음"}. 대화 내용을 고려하여 공감적으로 지원합니다.")
                    })

                    conversations.add(JSONObject().apply {
                        put("messages", messages)
                        put("emotion_meta", JSONObject().apply {
                            expression?.let { put("expression", it) }
                            audioEmotion?.let { put("audio_emotion", it) }
                            expressionScore?.let { put("confidence", it) }
                        })
                    })
                }
            }

            if (conversations.isEmpty()) return null

            val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val emotionFile = File(context.cacheDir, "emotion_conversations_$dateStr.jsonl")
            emotionFile.bufferedWriter().use { writer ->
                conversations.forEach { conv ->
                    writer.write(conv.toString())
                    writer.newLine()
                }
            }

            Log.i(TAG, "감정 대화 JSONL: ${conversations.size}개 → ${emotionFile.absolutePath}")
            emotionFile
        } catch (e: Exception) {
            Log.e(TAG, "exportEmotionLinkedJsonl 예외: ${e.message}")
            null
        }
    }

    // ─── 대화 일지 (relationship) 내보내기 ───

    /**
     * conversation_journal의 관계 맥락 대화 → ChatML JSONL.
     * 관계 인식 반응 학습용.
     */
    fun exportConversationJournalJsonl(): File? {
        return try {
            val conversations = mutableListOf<JSONObject>()
            val cutoffMs = System.currentTimeMillis() - WINDOW_DAYS * 24 * 60 * 60 * 1000L
            val db = database.readableDatabase

            db.rawQuery(
                """SELECT cj.topics, cj.key_points, cj.ai_summary, cj.emotion_observed,
                          cj.my_emotion, cj.situation,
                          rp.person_name, rp.relationship_type
                   FROM conversation_journal cj
                   LEFT JOIN relationship_profiles rp ON cj.person_id = rp.person_id
                   WHERE cj.timestamp > ? AND cj.ai_summary IS NOT NULL AND LENGTH(cj.ai_summary) > 20
                   ORDER BY cj.timestamp DESC LIMIT 200""",
                arrayOf(cutoffMs.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val topics = c.getString(0) ?: ""
                    val keyPoints = c.getString(1) ?: ""
                    val aiSummary = c.getString(2) ?: continue
                    val emotionObserved = c.getString(3)
                    val myEmotion = c.getString(4)
                    val situation = c.getString(5) ?: "SOCIAL_GATHERING"
                    val personName = c.getString(6) ?: "상대방"
                    val relType = c.getString(7) ?: "지인"

                    val messages = JSONArray()
                    messages.put(JSONObject().apply {
                        put("role", "system")
                        put("content", "당신은 AR 글래스 AI 어시스턴트입니다. 사용자의 대인 관계와 대화 맥락을 이해하고 지원합니다. 현재 상황: $situation.")
                    })
                    messages.put(JSONObject().apply {
                        put("role", "user")
                        put("content", buildString {
                            append("$personName ($relType)과의 대화에서 ")
                            if (topics.isNotBlank()) append("주제: $topics. ")
                            if (keyPoints.isNotBlank()) append("핵심: $keyPoints. ")
                            emotionObserved?.let { append("상대 감정: $it. ") }
                            myEmotion?.let { append("내 감정: $it. ") }
                            append("요약해줘.")
                        })
                    })
                    messages.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", aiSummary)
                    })

                    conversations.add(JSONObject().apply { put("messages", messages) })
                }
            }

            if (conversations.isEmpty()) return null

            val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val journalFile = File(context.cacheDir, "journal_conversations_$dateStr.jsonl")
            journalFile.bufferedWriter().use { writer ->
                conversations.forEach { conv ->
                    writer.write(conv.toString())
                    writer.newLine()
                }
            }

            Log.i(TAG, "대화 일지 JSONL: ${conversations.size}개 → ${journalFile.absolutePath}")
            journalFile
        } catch (e: Exception) {
            Log.e(TAG, "exportConversationJournalJsonl 예외: ${e.message}")
            null
        }
    }

    /**
     * 상황명 → 대표 질의 생성 (context_summary 없을 때 폴백).
     */
    private fun situationToQuery(situation: String): String {
        return when (situation) {
            "RUNNING" -> "러닝 중인데 지금 페이스가 어때?"
            "GYM_WORKOUT" -> "운동 자세 확인해줘"
            "WALKING_EXERCISE" -> "산책하면서 주변에 뭐가 보여?"
            "COMMUTING" -> "출퇴근 중인데 도움이 필요해"
            "IN_MEETING" -> "회의 중인데 요약해줘"
            "STUDYING" -> "공부 중인데 도움이 필요해"
            "COOKING" -> "요리 중인데 다음 단계는?"
            "RELAXING_HOME" -> "집에서 쉬고 있는데 뭐 할까?"
            "MORNING_ROUTINE" -> "오늘 일정 알려줘"
            else -> "현재 상황에서 도움이 필요해"
        }
    }
}
