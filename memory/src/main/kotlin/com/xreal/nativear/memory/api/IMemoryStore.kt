package com.xreal.nativear.memory.api

/**
 * 메모리 저장소 통합 인터페이스.
 *
 * 소비자는 이 인터페이스만 의존한다.
 * 구현이 SQLite든, ChromaDB든, 리모트 서버든 소비자는 모른다.
 *
 * 구현체:
 *  - SqliteMemoryStore: 현재 (로컬 SQLite-vec)
 *  - HybridMemoryStore: 다음 (SQLite-vec + ChromaDB + Graphiti, PC)
 *  - RemoteMemoryStore: 최종 (PC 서버 API, Fold는 캐시만)
 */
interface IMemoryStore {

    // ── 쓰기 ──

    /** 메모리 저장. 임베딩/위치 해석/중요도 계산은 구현 내부에서 처리. */
    suspend fun save(
        content: String,
        role: String,
        metadata: String? = null,
        personaId: String? = null,
        lat: Double? = null,
        lon: Double? = null
    ): Long

    // ── 읽기 ──

    suspend fun getById(id: Long): MemoryRecord?
    suspend fun getRecent(limit: Int = 10, level: Int = 0): List<MemoryRecord>
    suspend fun getAll(): List<MemoryRecord>
    fun getCount(level: Int = 0): Int

    // ── 검색 ──

    /** 시맨틱 벡터 검색 (가장 의미적으로 유사한 기억) */
    suspend fun searchSemantic(query: String, limit: Int = 5): List<SearchResult>

    /** 키워드 텍스트 검색 */
    suspend fun searchKeyword(keyword: String, limit: Int = 10): List<SearchResult>

    /** 시간 범위 검색 */
    suspend fun searchTemporal(startTime: Long, endTime: Long): List<MemoryRecord>

    /** 공간 범위 검색 */
    suspend fun searchSpatial(lat: Double, lon: Double, radiusKm: Double): List<MemoryRecord>

    // ── 정리 ──

    /** 특정 시간 이전의 level N 메모리 삭제 */
    suspend fun deleteOlderThan(cutoffTimestamp: Long, level: Int = 0): Int
}

/**
 * 메모리 압축 인터페이스.
 * 하위 레벨 메모리를 AI로 요약하여 상위 레벨로 올린다.
 */
interface IMemoryCompaction {
    suspend fun compress(level: Int = 0): Long?
    fun needsCompression(level: Int = 0): Boolean
}

/**
 * AI 기반 사실 추출 인터페이스.
 * 원본 텍스트에서 구조화된 사실을 뽑고, 기존 기억과 충돌을 판단한다.
 * 구현: Mem0Extractor (향후)
 */
interface IMemoryExtractor {
    suspend fun extractFacts(content: String, context: String = ""): List<ExtractedFact>
    suspend fun resolveConflict(newFact: ExtractedFact, existing: List<MemoryRecord>): ConflictResolution
}
