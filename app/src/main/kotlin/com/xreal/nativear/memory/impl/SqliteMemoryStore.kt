package com.xreal.nativear.memory.impl

import com.xreal.nativear.MemorySearcher
import com.xreal.nativear.TextEmbedder
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.memory.MemorySaveHelper
import com.xreal.nativear.memory.api.IMemoryStore
import com.xreal.nativear.memory.api.MemoryRecord
import com.xreal.nativear.memory.api.SearchSource
import com.xreal.nativear.memory.api.SearchResult as ApiSearchResult

/**
 * IMemoryStore 구현: 기존 SQLite-vec 기반.
 *
 * 내부에서 UnifiedMemoryDatabase, MemorySaveHelper, MemorySearcher, TextEmbedder를 사용.
 * 소비자는 이 클래스를 직접 참조하지 않고 IMemoryStore 인터페이스만 의존한다.
 */
class SqliteMemoryStore(
    private val database: UnifiedMemoryDatabase,
    private val memorySaveHelper: MemorySaveHelper,
    private val memorySearcher: MemorySearcher,
    private val textEmbedder: TextEmbedder
) : IMemoryStore {

    // ── 쓰기 ──

    override suspend fun save(
        content: String,
        role: String,
        metadata: String?,
        personaId: String?,
        lat: Double?,
        lon: Double?
    ): Long {
        return memorySaveHelper.saveMemory(content, role, metadata, personaId, lat, lon)
    }

    // ── 읽기 ──

    override suspend fun getById(id: Long): MemoryRecord? {
        return database.getNodesByIds(listOf(id)).firstOrNull()?.toRecord()
    }

    override suspend fun getRecent(limit: Int, level: Int): List<MemoryRecord> {
        return database.getUnsummarizedNodes(level, limit).map { it.toRecord() }
    }

    override suspend fun getAll(): List<MemoryRecord> {
        return database.getAllNodes().map { it.toRecord() }
    }

    override fun getCount(level: Int): Int {
        return database.getCount(level)
    }

    // ── 검색 ──

    override suspend fun searchSemantic(query: String, limit: Int): List<ApiSearchResult> {
        return memorySearcher.search(query, limit).map { result ->
            ApiSearchResult(
                record = result.node.toRecord(),
                score = result.similarity,
                source = SearchSource.SEMANTIC
            )
        }
    }

    override suspend fun searchKeyword(keyword: String, limit: Int): List<ApiSearchResult> {
        return database.searchNodesByKeyword(keyword).take(limit).map { node ->
            ApiSearchResult(
                record = node.toRecord(),
                score = 1.0f,  // 키워드 매칭은 이진(있다/없다)이므로 1.0
                source = SearchSource.KEYWORD
            )
        }
    }

    override suspend fun searchTemporal(startTime: Long, endTime: Long): List<MemoryRecord> {
        return database.getNodesInTimeRange(startTime, endTime).map { it.toRecord() }
    }

    override suspend fun searchSpatial(lat: Double, lon: Double, radiusKm: Double): List<MemoryRecord> {
        return database.getNodesInSpatialRange(lat, lon, radiusKm).map { it.toRecord() }
    }

    // ── 정리 ──

    override suspend fun deleteOlderThan(cutoffTimestamp: Long, level: Int): Int {
        // UnifiedMemoryDatabase에 직접 삭제 메서드가 없으므로 raw query 사용
        val db = database.writableDatabase
        return db.delete(
            "memory_nodes",
            "timestamp < ? AND level = ?",
            arrayOf(cutoffTimestamp.toString(), level.toString())
        )
    }

    // ── 변환 함수 ──

    companion object {
        /** MemoryNode → MemoryRecord 변환 */
        fun UnifiedMemoryDatabase.MemoryNode.toRecord() = MemoryRecord(
            id = id,
            timestamp = timestamp,
            content = content,
            role = role,
            level = level,
            parentId = parentId,
            importance = importanceScore,
            metadata = metadata,
            personaId = personaId,
            sessionId = sessionId,
            latitude = latitude,
            longitude = longitude
        )

        /** MemoryRecord → MemoryNode 변환 (저장 시 필요) */
        fun MemoryRecord.toNode() = UnifiedMemoryDatabase.MemoryNode(
            id = id,
            timestamp = timestamp,
            content = content,
            role = role,
            level = level,
            parentId = parentId,
            importanceScore = importance,
            metadata = metadata,
            personaId = personaId,
            sessionId = sessionId,
            latitude = latitude,
            longitude = longitude
        )
    }
}
