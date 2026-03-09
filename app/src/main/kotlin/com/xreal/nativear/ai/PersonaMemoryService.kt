package com.xreal.nativear.ai

import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.MemoryCompressor
import com.xreal.nativear.memory.IMemoryAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PersonaMemoryService(
    private val database: UnifiedMemoryDatabase,
    private val memorySaveHelper: IMemoryAccess,
    private val memoryCompressor: MemoryCompressor
) {
    private val TAG = "PersonaMemoryService"

    // Hit rate tracking: personaId -> (hits, total)
    private val hitRateMap = mutableMapOf<String, Pair<Int, Int>>()

    /**
     * Save a memory node tagged with persona_id.
     */
    suspend fun savePersonaMemory(
        personaId: String,
        content: String,
        role: String,
        metadata: String? = null
    ) = withContext(Dispatchers.IO) {
        val nodeId = memorySaveHelper.saveMemory(
            content = content,
            role = role,
            metadata = metadata,
            personaId = personaId
        )
        memoryCompressor.checkAndCompressPersona(personaId)
        Log.d(TAG, "Saved persona memory: $personaId (nodeId=$nodeId)")
    }

    /**
     * Get recent memories for a specific persona.
     * ★ Phase I: importance × recency 복합 정렬 (순수 시간 순 → 중요도 반영).
     * 폴백: getNodesByPersonaId() (시간 순)
     */
    suspend fun getRecentMemories(personaId: String, limit: Int = 10): List<UnifiedMemoryDatabase.MemoryNode> =
        withContext(Dispatchers.IO) {
            try {
                database.getMemoriesByImportanceRecency(personaId, limit)
            } catch (e: Exception) {
                Log.w(TAG, "importance 정렬 실패, 시간 순 폴백: ${e.message}")
                database.getNodesByPersonaId(personaId, limit)
            }
        }

    /**
     * Get insight/reflection nodes for a persona (level > 0 summaries).
     */
    suspend fun getInsights(personaId: String, limit: Int = 5): List<UnifiedMemoryDatabase.MemoryNode> =
        withContext(Dispatchers.IO) {
            database.getPersonaInsights(personaId, limit)
        }

    /**
     * Record a prediction hit or miss.
     */
    fun recordHit(personaId: String, wasCorrect: Boolean) {
        synchronized(hitRateMap) {
            val (hits, total) = hitRateMap[personaId] ?: (0 to 0)
            hitRateMap[personaId] = (if (wasCorrect) hits + 1 else hits) to (total + 1)
        }
    }

    /**
     * Get hit rate (0.0 to 1.0) for a persona. Null if no data.
     */
    fun getHitRate(personaId: String): Float? {
        synchronized(hitRateMap) {
            val (hits, total) = hitRateMap[personaId] ?: return null
            return if (total > 0) hits.toFloat() / total else null
        }
    }
}
