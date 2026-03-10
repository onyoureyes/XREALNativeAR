package com.xreal.nativear.memory.impl

import com.xreal.nativear.MemoryCompressor
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.memory.api.IMemoryCompaction

/**
 * IMemoryCompaction 구현: 기존 MemoryCompressor 래핑.
 *
 * 50개 level-N 노드 → AI 요약 → 1개 level-(N+1) 노드 생성.
 */
class SqliteMemoryCompaction(
    private val database: UnifiedMemoryDatabase,
    private val memoryCompressor: MemoryCompressor
) : IMemoryCompaction {

    override suspend fun compress(level: Int): Long? {
        // MemoryCompressor.checkAndCompress는 내부에서 비동기 처리
        // 생성된 요약 노드 ID를 반환하려면 DB 카운트 비교 방식 사용
        val beforeCount = database.getCount(level + 1)
        memoryCompressor.checkAndCompress(level)
        val afterCount = database.getCount(level + 1)

        // 새 요약 노드가 생성된 경우 가장 최근 노드 반환
        return if (afterCount > beforeCount) {
            database.getUnsummarizedNodes(level + 1, 1).firstOrNull()?.id
        } else {
            null
        }
    }

    override fun needsCompression(level: Int): Boolean {
        // MemoryCompressor 규칙: 50개 이상의 미요약 노드가 있으면 압축 필요
        return database.getUnsummarizedNodes(level, 1).isNotEmpty() &&
                database.getCount(level) >= 50
    }
}
