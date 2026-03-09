package com.xreal.nativear

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MemorySearcher(private val database: UnifiedMemoryDatabase, private val imageEmbedder: ImageEmbedder) {
    private val TAG = "MemorySearcher"

    data class SearchResult(
        val node: UnifiedMemoryDatabase.MemoryNode,
        val similarity: Float
    )

    /**
     * Keyword-based search (LIKE). Used as fallback when semantic search unavailable.
     */
    suspend fun search(query: String, limit: Int = 5): List<SearchResult> {
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<SearchResult>()
            val db = database.readableDatabase

            val cursor = db.query(
                "memory_nodes", null,
                "content LIKE ? OR metadata LIKE ?",
                arrayOf("%$query%", "%$query%"),
                null, null, "timestamp DESC", limit.toString()
            )

            while (cursor.moveToNext()) {
                val node = cursorToNode(cursor)
                results.add(SearchResult(node, 1.0f))
            }
            cursor.close()

            results
        }
    }

    /**
     * Image-based search: embed bitmap then do KNN via vec0.
     */
    suspend fun searchByImage(bitmap: Bitmap, limit: Int = 5): List<SearchResult> {
        return withContext(Dispatchers.Default) {
            val queryEmbedding = imageEmbedder.embed(bitmap) ?: return@withContext emptyList()

            // Use vec0 KNN on text embeddings (image→text cross-modal search)
            val vecResults = database.searchByTextEmbedding(queryEmbedding, limit)
            if (vecResults.isNotEmpty()) {
                val nodes = database.getNodesByIds(vecResults.map { it.first })
                nodes.mapIndexed { index, node ->
                    SearchResult(node, 1.0f - (vecResults.getOrNull(index)?.second ?: 0f))
                }
            } else {
                emptyList()
            }
        }
    }

    private fun cursorToNode(cursor: android.database.Cursor): UnifiedMemoryDatabase.MemoryNode {
        return UnifiedMemoryDatabase.MemoryNode(
            cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
            cursor.getString(cursor.getColumnIndexOrThrow("role")),
            cursor.getString(cursor.getColumnIndexOrThrow("content")),
            cursor.getInt(cursor.getColumnIndexOrThrow("level")),
            if (cursor.isNull(cursor.getColumnIndexOrThrow("parent_id"))) null else cursor.getLong(cursor.getColumnIndexOrThrow("parent_id")),
            cursor.getBlob(cursor.getColumnIndexOrThrow("embedding")),
            if (cursor.isNull(cursor.getColumnIndexOrThrow("latitude"))) null else cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")),
            if (cursor.isNull(cursor.getColumnIndexOrThrow("longitude"))) null else cursor.getDouble(cursor.getColumnIndexOrThrow("longitude")),
            cursor.getString(cursor.getColumnIndexOrThrow("metadata"))
        )
    }
}
