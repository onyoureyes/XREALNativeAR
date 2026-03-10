package com.xreal.nativear.memory

import android.util.Log
import com.xreal.nativear.ILocationService
import com.xreal.nativear.TextEmbedder
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.core.ErrorReporter
import com.xreal.nativear.core.ErrorSeverity
import com.xreal.nativear.memory.impl.SqliteMemoryStore.Companion.toRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * MemorySaveHelper: Shared helper for memory insert operations.
 *
 * Eliminates duplication between MemoryRepository.saveMemory() and
 * PersonaMemoryService.savePersonaMemory() by extracting common:
 * - Text embedding generation
 * - Location resolution
 * - MemoryNode construction + vector insert
 * - Metadata enrichment
 */
class MemorySaveHelper(
    private val database: UnifiedMemoryDatabase,
    private val textEmbedder: TextEmbedder,
    private val locationService: ILocationService,
    private val importanceScorer: MemoryImportanceScorer? = null  // ★ Phase I
) : IMemoryAccess {
    companion object {
        private const val TAG = "MemorySaveHelper"
    }

    // ★ Phase C: LifeSessionManager lazy inject — circular dependency 방지
    private val sessionManager: com.xreal.nativear.session.LifeSessionManager? by lazy {
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull() } catch (e: Exception) { null }
    }

    /**
     * Generate text embedding from content.
     * Returns null if embedder is not ready or fails.
     */
    override suspend fun generateTextEmbedding(content: String): FloatArray? {
        return try {
            if (textEmbedder.isReady) textEmbedder.getEmbedding(content) else null
        } catch (e: Exception) {
            Log.w(TAG, "Text embedding failed: ${e.message}")
            null
        }
    }

    /**
     * Resolve location: use provided values or fall back to LocationService.
     */
    override suspend fun resolveLocation(lat: Double?, lon: Double?): Pair<Double?, Double?> {
        if (lat != null && lon != null) return lat to lon
        val currentLoc = locationService.getCurrentLocation()
        return if (currentLoc != null) {
            currentLoc.latitude to currentLoc.longitude
        } else {
            null to null
        }
    }

    /**
     * Insert a MemoryNode with optional text embedding into both main table and vec table.
     * Returns the inserted node ID (> 0 on success).
     * ★ Phase I: 저장 후 importance_score 자동 계산 및 업데이트.
     */
    override suspend fun insertMemoryWithEmbedding(
        node: UnifiedMemoryDatabase.MemoryNode,
        embedding: FloatArray?
    ): Long = withContext(Dispatchers.IO) {
        val nodeId = database.insertNode(node)
        if (nodeId > 0 && embedding != null) {
            database.insertTextEmbedding(nodeId, embedding)
        }
        // ★ Phase I: importance_score 자동 계산
        if (nodeId > 0 && importanceScorer != null) {
            try {
                val score = importanceScorer.score(node.toRecord())
                database.updateImportanceScore(nodeId, score)
            } catch (e: Exception) {
                Log.w(TAG, "importance_score 계산 실패 (무시됨): ${e.message}")
            }
        }
        nodeId
    }

    /**
     * Full save pipeline: resolve location → generate embedding → insert node + vector.
     * Returns inserted node ID.
     */
    override suspend fun saveMemory(
        content: String,
        role: String,
        metadata: String?,
        personaId: String?,
        lat: Double?,
        lon: Double?
    ): Long = withContext(Dispatchers.IO) {
        val (finalLat, finalLon) = resolveLocation(lat, lon)
        val embedding = generateTextEmbedding(content)

        // ★ Phase C: 현재 세션 ID 자동 주입
        val sessionId = sessionManager?.currentSessionId

        val enrichedMetadata = if (personaId != null && metadata != null) {
            enrichMetadata(metadata, "persona_id" to personaId)
        } else if (personaId != null) {
            enrichMetadata("{}", "persona_id" to personaId)
        } else {
            metadata
        }

        val node = UnifiedMemoryDatabase.MemoryNode(
            timestamp = System.currentTimeMillis(),
            role = role,
            content = content,
            metadata = enrichedMetadata,
            embedding = embedding?.let { UnifiedMemoryDatabase.floatArrayToBlob(it) },
            latitude = finalLat,
            longitude = finalLon,
            personaId = personaId,
            sessionId = sessionId  // ★ Phase C
        )

        val nodeId = try {
            insertMemoryWithEmbedding(node, embedding)
        } catch (e: Exception) {
            ErrorReporter.report(TAG, "메모리 저장 실패 (role=$role, len=${content.length})", e, ErrorSeverity.CRITICAL)
            -1L
        }
        if (nodeId <= 0) {
            ErrorReporter.report(TAG, "메모리 저장 실패 — insertNode returned $nodeId (role=$role)", severity = ErrorSeverity.CRITICAL)
        }
        // 세션 메모리 카운터 증가
        if (nodeId > 0 && sessionId != null) {
            database.incrementSessionMemoryCount(sessionId)
        }
        // 활동 갱신 (비활성 타이머 리셋)
        sessionManager?.updateLastActivity()
        nodeId
    }

    /**
     * Enrich metadata JSON string with additional key-value pairs.
     */
    override fun enrichMetadata(baseMetadata: String?, vararg pairs: Pair<String, Any>): String {
        val json = try {
            JSONObject(baseMetadata ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        for ((key, value) in pairs) {
            json.put(key, value)
        }
        return json.toString()
    }
}
