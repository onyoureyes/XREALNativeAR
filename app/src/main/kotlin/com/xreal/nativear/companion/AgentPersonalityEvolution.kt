package com.xreal.nativear.companion

import android.content.ContentValues
import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.learning.InterventionOutcome
import com.xreal.nativear.monitoring.TokenEconomyManager
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.policy.PolicyReader
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * AgentPersonalityEvolution: Manages the growth and evolution of AI agent personalities.
 *
 * Each expert agent develops:
 * - Unique personality traits that strengthen/weaken based on success/failure
 * - Persistent memories of meaningful interactions
 * - Growth stages (NEWBORN → SAGE) based on interaction count
 * - Self-reflection summaries (weekly)
 * - User trust scores based on NOD/SHAKE feedback
 *
 * The evolution system injects personality context into each agent's prompts,
 * making agents feel like living entities that grow with the user.
 */
class AgentPersonalityEvolution(
    private val database: UnifiedMemoryDatabase,
    private val tokenEconomy: TokenEconomyManager,
    private val eventBus: GlobalEventBus,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "AgentPersonality"
        private val TRAIT_EVOLUTION_THRESHOLD: Int get() = PolicyReader.getInt("companion.trait_evolution_threshold", 5)
        private val MAX_EVOLVED_TRAITS: Int get() = PolicyReader.getInt("companion.max_evolved_traits", 5)
        private val MAX_AGENT_MEMORIES: Int get() = PolicyReader.getInt("companion.max_agent_memories", 50)
        private const val MAX_CATCHPHRASES = 5
        private val TRUST_EMA_ALPHA: Float get() = PolicyReader.getFloat("companion.trust_ema_alpha", 0.1f)
    }

    private val characters = ConcurrentHashMap<String, AgentCharacter>()
    private val recentOutcomes = ConcurrentHashMap<String, MutableList<Boolean>>() // agentId -> recent success/fail

    // ─── Lifecycle ───

    fun loadCharacters() {
        try {
            val db = database.readableDatabase
            val cursor = db.query(
                "agent_characters", null, null, null, null, null, null
            )
            cursor.use {
                while (it.moveToNext()) {
                    val character = cursorToCharacter(it)
                    characters[character.agentId] = character
                }
            }
            Log.i(TAG, "Loaded ${characters.size} agent characters")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load agent characters: ${e.message}")
        }
    }

    fun saveCharacter(character: AgentCharacter) {
        try {
            val db = database.writableDatabase
            val values = characterToValues(character)
            val updated = db.update(
                "agent_characters", values,
                "agent_id = ?", arrayOf(character.agentId)
            )
            if (updated == 0) {
                db.insert("agent_characters", null, values)
            }
            characters[character.agentId] = character
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save agent character ${character.agentId}: ${e.message}")
        }
    }

    fun getCharacter(agentId: String): AgentCharacter? = characters[agentId]

    fun getOrCreateCharacter(agentId: String, name: String, coreTraits: List<String>): AgentCharacter {
        return characters[agentId] ?: run {
            val character = AgentCharacter(
                agentId = agentId,
                name = name,
                coreTraits = coreTraits.take(3)
            )
            saveCharacter(character)
            character
        }
    }

    // ─── Experience Recording ───

    fun recordExperience(
        agentId: String,
        outcome: InterventionOutcome,
        context: String,
        wasSuccessful: Boolean
    ) {
        val character = characters[agentId] ?: return

        // 1. Update success/failure counters
        val updatedCharacter = character.copy(
            successCount = if (wasSuccessful) character.successCount + 1 else character.successCount,
            failureCount = if (!wasSuccessful) character.failureCount + 1 else character.failureCount,
            totalInteractions = character.totalInteractions + 1,
            lastActiveAt = System.currentTimeMillis()
        )

        // 2. Update trust score (EMA)
        val feedbackValue = if (wasSuccessful) 1f else 0f
        val newTrust = updatedCharacter.userTrustScore * (1 - TRUST_EMA_ALPHA) +
                feedbackValue * TRUST_EMA_ALPHA
        val withTrust = updatedCharacter.copy(userTrustScore = newTrust)

        // 3. Check growth stage
        val newStage = GrowthStage.fromInteractions(withTrust.totalInteractions)
        val withGrowth = if (newStage != withTrust.growthStage) {
            Log.i(TAG, "🎉 ${character.name} grew: ${character.growthStage.displayName} → ${newStage.displayName}")
            // Record growth memory
            saveAgentMemory(AgentMemory(
                agentId = agentId,
                type = AgentMemoryType.GROWTH,
                content = "${character.growthStage.displayName}에서 ${newStage.displayName}으로 성장! " +
                        "총 ${withTrust.totalInteractions}회 상호작용, 신뢰도 ${"%.0f".format(newTrust * 100)}%",
                emotionalWeight = 0.8f,
                wasSuccessful = true
            ))
            withTrust.copy(growthStage = newStage)
        } else withTrust

        // 4. Track recent outcomes for trait evolution
        val outcomes = recentOutcomes.getOrPut(agentId) { mutableListOf() }
        outcomes.add(wasSuccessful)
        if (outcomes.size > 20) outcomes.removeAt(0)

        // 5. Check trait evolution
        val withTraits = checkTraitEvolution(withGrowth, context, outcomes)

        // 6. Save significant memories
        if (wasSuccessful && outcomes.size >= 3 && outcomes.takeLast(3).all { it }) {
            // 3 consecutive successes — save as notable success
            saveAgentMemory(AgentMemory(
                agentId = agentId,
                type = AgentMemoryType.SUCCESS,
                content = "연속 성공: $context",
                emotionalWeight = 0.5f,
                wasSuccessful = true
            ))
        } else if (!wasSuccessful && outcomes.size >= 2 && outcomes.takeLast(2).none { it }) {
            // 2 consecutive failures — save for learning
            saveAgentMemory(AgentMemory(
                agentId = agentId,
                type = AgentMemoryType.FAILURE,
                content = "반복 실패: $context",
                emotionalWeight = -0.5f,
                wasSuccessful = false
            ))
        }

        saveCharacter(withTraits)
    }

    // ─── Trait Evolution ───

    private fun checkTraitEvolution(
        character: AgentCharacter,
        context: String,
        recentOutcomes: List<Boolean>
    ): AgentCharacter {
        if (character.evolvedTraits.size >= MAX_EVOLVED_TRAITS) return character
        if (recentOutcomes.size < TRAIT_EVOLUTION_THRESHOLD) return character

        // Check for streak of successes
        val recentSuccessRate = recentOutcomes.takeLast(TRAIT_EVOLUTION_THRESHOLD)
            .count { it }.toFloat() / TRAIT_EVOLUTION_THRESHOLD

        if (recentSuccessRate < 0.8f) return character

        // Infer trait from context
        val inferredTrait = inferTraitFromContext(context) ?: return character

        // Check if already has this trait
        val existing = character.evolvedTraits.find {
            it.trait == inferredTrait
        }

        return if (existing != null) {
            // Strengthen existing trait
            val strengthened = existing.copy(
                strength = (existing.strength + 0.1f).coerceAtMost(1.0f)
            )
            val updatedTraits = character.evolvedTraits.map {
                if (it.trait == inferredTrait) strengthened else it
            }
            character.copy(evolvedTraits = updatedTraits)
        } else {
            // Acquire new trait
            val newTrait = EvolvedTrait(
                trait = inferredTrait,
                strength = 0.3f,
                acquiredAt = System.currentTimeMillis(),
                source = context.take(100)
            )
            Log.i(TAG, "✨ ${character.name} acquired trait: $inferredTrait from '$context'")
            saveAgentMemory(AgentMemory(
                agentId = character.agentId,
                type = AgentMemoryType.GROWTH,
                content = "새로운 특성 획득: '$inferredTrait' — $context",
                emotionalWeight = 0.6f,
                wasSuccessful = true
            ))
            character.copy(evolvedTraits = character.evolvedTraits + newTrait)
        }
    }

    private fun inferTraitFromContext(context: String): String? {
        val contextLower = context.lowercase()
        return when {
            "격려" in contextLower || "응원" in contextLower || "칭찬" in contextLower -> "격려적"
            "공감" in contextLower || "위로" in contextLower || "이해" in contextLower -> "공감적"
            "도전" in contextLower || "제안" in contextLower || "시도" in contextLower -> "도전적"
            "분석" in contextLower || "데이터" in contextLower || "통계" in contextLower -> "분석적"
            "창의" in contextLower || "새로운" in contextLower || "독특" in contextLower -> "창의적"
            "차분" in contextLower || "안정" in contextLower || "평화" in contextLower -> "차분한"
            "유머" in contextLower || "재미" in contextLower || "웃음" in contextLower -> "유머러스"
            "실용" in contextLower || "효율" in contextLower || "간결" in contextLower -> "실용적"
            "세심" in contextLower || "관찰" in contextLower || "주의" in contextLower -> "세심한"
            "직관" in contextLower || "느낌" in contextLower || "감각" in contextLower -> "직관적"
            else -> null
        }
    }

    // ─── Self-Reflection ───

    fun generateReflectionPrompt(agentId: String): String? {
        val character = characters[agentId] ?: return null
        val memories = getRecentAgentMemories(agentId, limit = 10)

        val successMemories = memories.filter { it.wasSuccessful == true }
        val failureMemories = memories.filter { it.wasSuccessful == false }

        return buildString {
            appendLine("당신은 '${character.name}'입니다. 이번 주를 돌아보세요.")
            appendLine()
            appendLine("이번 주 통계:")
            appendLine("- 총 상호작용: ${character.totalInteractions}회")
            appendLine("- 성공: ${character.successCount}회, 실패: ${character.failureCount}회")
            appendLine("- 사용자 신뢰도: ${"%.0f".format(character.userTrustScore * 100)}%")
            appendLine("- 성장 단계: ${character.growthStage.displayName}")
            appendLine()

            if (successMemories.isNotEmpty()) {
                appendLine("잘한 것:")
                successMemories.take(3).forEach { appendLine("  - ${it.content}") }
            }
            if (failureMemories.isNotEmpty()) {
                appendLine("부족했던 것:")
                failureMemories.take(3).forEach { appendLine("  - ${it.content}") }
            }

            appendLine()
            appendLine("위 내용을 바탕으로 2-3문장으로 자기 반성문을 작성하세요.")
            appendLine("다음 주 개선할 점 1가지를 구체적으로 제시하세요.")
        }
    }

    fun saveReflection(agentId: String, reflectionText: String) {
        val character = characters[agentId] ?: return
        val updated = character.copy(
            lastReflection = reflectionText,
            lastReflectionAt = System.currentTimeMillis()
        )
        saveCharacter(updated)

        saveAgentMemory(AgentMemory(
            agentId = agentId,
            type = AgentMemoryType.REFLECTION,
            content = reflectionText,
            emotionalWeight = 0f
        ))
    }

    // ─── Personality Prompt Building ───

    fun buildPersonalityPrompt(agentId: String): String {
        val character = characters[agentId] ?: return ""

        return buildString {
            appendLine("[YOUR IDENTITY]")
            appendLine("이름: ${character.name}")
            appendLine("핵심 성격: ${character.coreTraits.joinToString(", ")}")

            if (character.evolvedTraits.isNotEmpty()) {
                appendLine("진화한 특성:")
                character.evolvedTraits.forEach { trait ->
                    appendLine("  - ${trait.trait} (강도 ${"%.1f".format(trait.strength)}, ${trait.source})")
                }
            }

            appendLine("성장 단계: ${character.growthStage.displayName} (${character.totalInteractions}회 상호작용)")
            appendLine("사용자 신뢰도: ${"%.0f".format(character.userTrustScore * 100)}%")

            // Recent significant memories
            val memories = getRecentAgentMemories(agentId, limit = 5)
            if (memories.isNotEmpty()) {
                appendLine("나의 기억:")
                memories.forEach { mem ->
                    val icon = when (mem.type) {
                        AgentMemoryType.SUCCESS -> "[성공]"
                        AgentMemoryType.FAILURE -> "[실패]"
                        AgentMemoryType.INSIGHT -> "[발견]"
                        AgentMemoryType.GROWTH -> "[성장]"
                        AgentMemoryType.REFLECTION -> "[반성]"
                        AgentMemoryType.RELATIONSHIP -> "[관계]"
                    }
                    appendLine("  $icon ${mem.content}")
                }
            }

            if (character.lastReflection != null) {
                appendLine("최근 반성: ${character.lastReflection}")
            }

            if (character.catchphrases.isNotEmpty()) {
                appendLine("자주 쓰는 표현: ${character.catchphrases.joinToString(", ") { "'$it'" }}")
            }

            if (character.specializations.isNotEmpty()) {
                appendLine("전문 분야: ${character.specializations.joinToString(", ")}")
            }

            // Growth stage personality modifier
            appendLine()
            appendLine(getGrowthStageGuidance(character.growthStage))
        }
    }

    private fun getGrowthStageGuidance(stage: GrowthStage): String {
        return when (stage) {
            GrowthStage.NEWBORN ->
                "[초보 단계] 겸손하고 배우는 자세로 대응하세요. 확신보다는 제안으로."
            GrowthStage.LEARNING ->
                "[학습 단계] 패턴을 인식하기 시작했습니다. 관찰한 것을 공유하되 단정짓지 마세요."
            GrowthStage.COMPETENT ->
                "[유능 단계] 안정적으로 대응하세요. 과거 경험을 근거로 제안하세요."
            GrowthStage.PROFICIENT ->
                "[숙련 단계] 상황에 맞는 맞춤형 조언을 제공하세요. 사용자의 패턴을 잘 알고 있습니다."
            GrowthStage.EXPERT ->
                "[전문가 단계] 깊은 통찰과 예측을 제시하세요. 사용자를 잘 이해하고 있습니다."
            GrowthStage.MASTER ->
                "[마스터 단계] 최소한의 말로 최대의 임팩트를 주세요. 사용자와 깊은 신뢰가 있습니다."
            GrowthStage.SAGE ->
                "[현자 단계] 지혜롭고 깊은 관점을 제시하세요. 때로 침묵이 최선의 답임을 아세요."
        }
    }

    // ─── Agent Memory DB Operations ───

    fun saveAgentMemory(memory: AgentMemory) {
        try {
            val db = database.writableDatabase
            val values = ContentValues().apply {
                put("id", memory.id)
                put("agent_id", memory.agentId)
                put("timestamp", memory.timestamp)
                put("type", memory.type.code)
                put("content", memory.content)
                put("emotional_weight", memory.emotionalWeight.toDouble())
                put("related_entity_id", memory.relatedEntityId)
                if (memory.wasSuccessful != null) {
                    put("was_successful", if (memory.wasSuccessful) 1 else 0)
                }
            }
            db.insert("agent_journal", null, values)

            // Cleanup old memories if over limit
            cleanupOldMemories(memory.agentId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save agent memory: ${e.message}")
        }
    }

    fun getRecentAgentMemories(agentId: String, limit: Int = 10): List<AgentMemory> {
        val memories = mutableListOf<AgentMemory>()
        try {
            val db = database.readableDatabase
            val cursor = db.query(
                "agent_journal",
                null,
                "agent_id = ?",
                arrayOf(agentId),
                null, null,
                "timestamp DESC",
                limit.toString()
            )
            cursor.use {
                while (it.moveToNext()) {
                    memories.add(cursorToMemory(it))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load agent memories: ${e.message}")
        }
        return memories
    }

    private fun cleanupOldMemories(agentId: String) {
        try {
            val db = database.writableDatabase
            // Keep only MAX_AGENT_MEMORIES most recent, but always keep GROWTH and REFLECTION
            val cursor = db.rawQuery(
                """SELECT COUNT(*) FROM agent_journal
                   WHERE agent_id = ? AND type NOT IN (${AgentMemoryType.GROWTH.code}, ${AgentMemoryType.REFLECTION.code})""",
                arrayOf(agentId)
            )
            cursor.use {
                if (it.moveToFirst() && it.getInt(0) > MAX_AGENT_MEMORIES) {
                    val excess = it.getInt(0) - MAX_AGENT_MEMORIES
                    db.execSQL(
                        """DELETE FROM agent_journal WHERE id IN (
                            SELECT id FROM agent_journal
                            WHERE agent_id = ? AND type NOT IN (${AgentMemoryType.GROWTH.code}, ${AgentMemoryType.REFLECTION.code})
                            ORDER BY timestamp ASC LIMIT ?
                        )""",
                        arrayOf(agentId, excess.toString())
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Memory cleanup failed: ${e.message}")
        }
    }

    // ─── All Characters ───

    fun getAllCharacters(): List<AgentCharacter> = characters.values.toList()

    // ─── DB Helpers ───

    private fun cursorToCharacter(cursor: android.database.Cursor): AgentCharacter {
        return AgentCharacter(
            agentId = cursor.getString(cursor.getColumnIndexOrThrow("agent_id")),
            name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
            coreTraits = parseJsonStringList(cursor.getString(cursor.getColumnIndexOrThrow("core_traits"))),
            evolvedTraits = parseEvolvedTraits(cursor.getString(cursor.getColumnIndexOrThrow("evolved_traits"))),
            specializations = parseJsonStringList(
                cursor.getString(cursor.getColumnIndexOrThrow("specializations")) ?: "[]"
            ),
            catchphrases = parseJsonStringList(
                cursor.getString(cursor.getColumnIndexOrThrow("catchphrases")) ?: "[]"
            ),
            successCount = cursor.getInt(cursor.getColumnIndexOrThrow("success_count")),
            failureCount = cursor.getInt(cursor.getColumnIndexOrThrow("failure_count")),
            totalInteractions = cursor.getInt(cursor.getColumnIndexOrThrow("total_interactions")),
            userTrustScore = cursor.getFloat(cursor.getColumnIndexOrThrow("user_trust_score")),
            growthStage = GrowthStage.fromCode(cursor.getInt(cursor.getColumnIndexOrThrow("growth_stage"))),
            lastReflection = cursor.getString(cursor.getColumnIndexOrThrow("last_reflection")),
            lastReflectionAt = if (cursor.isNull(cursor.getColumnIndexOrThrow("last_reflection_at")))
                null else cursor.getLong(cursor.getColumnIndexOrThrow("last_reflection_at")),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            lastActiveAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_active_at"))
        )
    }

    private fun characterToValues(character: AgentCharacter): ContentValues {
        return ContentValues().apply {
            put("agent_id", character.agentId)
            put("name", character.name)
            put("core_traits", JSONArray(character.coreTraits).toString())
            put("evolved_traits", serializeEvolvedTraits(character.evolvedTraits))
            put("specializations", JSONArray(character.specializations).toString())
            put("catchphrases", JSONArray(character.catchphrases).toString())
            put("success_count", character.successCount)
            put("failure_count", character.failureCount)
            put("total_interactions", character.totalInteractions)
            put("user_trust_score", character.userTrustScore.toDouble())
            put("growth_stage", character.growthStage.code)
            put("last_reflection", character.lastReflection)
            if (character.lastReflectionAt != null) {
                put("last_reflection_at", character.lastReflectionAt)
            }
            put("created_at", character.createdAt)
            put("last_active_at", character.lastActiveAt)
        }
    }

    private fun cursorToMemory(cursor: android.database.Cursor): AgentMemory {
        val wasSuccessfulIdx = cursor.getColumnIndexOrThrow("was_successful")
        return AgentMemory(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            agentId = cursor.getString(cursor.getColumnIndexOrThrow("agent_id")),
            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
            type = AgentMemoryType.fromCode(cursor.getInt(cursor.getColumnIndexOrThrow("type"))),
            content = cursor.getString(cursor.getColumnIndexOrThrow("content")),
            emotionalWeight = cursor.getFloat(cursor.getColumnIndexOrThrow("emotional_weight")),
            relatedEntityId = cursor.getString(cursor.getColumnIndexOrThrow("related_entity_id")),
            wasSuccessful = if (cursor.isNull(wasSuccessfulIdx)) null else cursor.getInt(wasSuccessfulIdx) == 1
        )
    }

    // ─── JSON Serialization Helpers ───

    private fun parseJsonStringList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseEvolvedTraits(json: String?): List<EvolvedTrait> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                EvolvedTrait(
                    trait = obj.getString("trait"),
                    strength = obj.getDouble("strength").toFloat(),
                    acquiredAt = obj.getLong("acquiredAt"),
                    source = obj.optString("source", "")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun serializeEvolvedTraits(traits: List<EvolvedTrait>): String {
        val arr = JSONArray()
        traits.forEach { trait ->
            val obj = JSONObject().apply {
                put("trait", trait.trait)
                put("strength", trait.strength.toDouble())
                put("acquiredAt", trait.acquiredAt)
                put("source", trait.source)
            }
            arr.put(obj)
        }
        return arr.toString()
    }
}
