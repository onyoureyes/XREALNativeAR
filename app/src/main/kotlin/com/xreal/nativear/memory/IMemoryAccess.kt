package com.xreal.nativear.memory

import com.xreal.nativear.UnifiedMemoryDatabase

/**
 * IMemoryAccess: 메모리 저장 파이프라인 인터페이스.
 * 구현체: MemorySaveHelper
 */
interface IMemoryAccess {
    suspend fun saveMemory(
        content: String,
        role: String,
        metadata: String? = null,
        personaId: String? = null,
        lat: Double? = null,
        lon: Double? = null
    ): Long
    suspend fun generateTextEmbedding(content: String): FloatArray?
    suspend fun resolveLocation(lat: Double? = null, lon: Double? = null): Pair<Double?, Double?>
    suspend fun insertMemoryWithEmbedding(
        node: UnifiedMemoryDatabase.MemoryNode,
        embedding: FloatArray?
    ): Long
    fun enrichMetadata(baseMetadata: String?, vararg pairs: Pair<String, Any>): String
}
