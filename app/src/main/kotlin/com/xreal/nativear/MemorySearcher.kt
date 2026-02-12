package com.xreal.nativear

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MemorySearcher(private val context: Context, private val imageEmbedder: ImageEmbedder) {
    private val TAG = "MemorySearcher"
    private val database = UnifiedMemoryDatabase(context)

    data class SearchResult(
        val node: UnifiedMemoryDatabase.MemoryNode,
        val similarity: Float
    )

    /**
     * Performs a semantic search for a query string.
     * 1. Embeds the query.
     * 2. Scans the database for similar embeddings.
     * 3. (Future) leverages the accordion hierarchy for speed.
     */
    suspend fun search(query: String, limit: Int = 5): List<SearchResult> {
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<SearchResult>()
            val db = database.readableDatabase
            
            val cursor = db.query(
                "memory_nodes", null,
                "content LIKE ? OR metadata LIKE ?",
                arrayOf("%$query%", "%$query%"),
                null, null, "timestamp DESC", "100" // Scan more to filter 
            )

            while (cursor.moveToNext()) {
                val node = cursorToNode(cursor)
                results.add(SearchResult(node, 1.0f))
            }
            cursor.close()
            
            results.sortedByDescending { it.node.timestamp }.take(limit)
        }
    }

    suspend fun searchByImage(bitmap: Bitmap, limit: Int = 5): List<SearchResult> {
        return withContext(Dispatchers.Default) {
            val queryEmbedding = imageEmbedder.embed(bitmap) ?: return@withContext emptyList()
            searchByVector(queryEmbedding, limit)
        }
    }

    suspend fun searchByVector(targetEmbedding: FloatArray, limit: Int = 5): List<SearchResult> {
        return withContext(Dispatchers.IO) {
            val allNodes = database.getAllNodes() // Potential bottleneck, but fine for local DB < 10k nodes
            val results = mutableListOf<SearchResult>()
            
            for (node in allNodes) {
                val nodeEmbedding = node.embedding ?: continue
                val floatEmbedding = bytesToFloats(nodeEmbedding)
                
                val sim = imageEmbedder.calculateSimilarity(targetEmbedding, floatEmbedding)
                if (sim > 0.5f) { // Threshold for relevance
                    results.add(SearchResult(node, sim))
                }
            }
            
            results.sortedByDescending { it.similarity }.take(limit)
        }
    }

    private fun bytesToFloats(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) {
            floats[i] = buffer.float
        }
        return floats
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


    private fun calculateCosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
            normA += vec1[i] * vec1[i]
            normB += vec2[i] * vec2[i]
        }
        return (dotProduct / (Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble()))).toFloat()
    }
}
