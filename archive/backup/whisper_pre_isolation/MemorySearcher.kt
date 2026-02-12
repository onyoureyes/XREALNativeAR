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
            // Ideally we'd have a text-to-image/text-to-embedding model. 
            // For now, if we're using visual embeddings, we rely on metadata/content for text search
            // or we use the image embedder if the query is an image.
            
            // Placeholder: Hybrid search using SQL LIKE for text and manual Dot Product for vectors.
            val results = mutableListOf<SearchResult>()
            val db = database.readableDatabase
            
            // Search Level 0 for exact or similar content
            val cursor = db.query(
                "memory_nodes", null,
                "content LIKE ? OR metadata LIKE ?",
                arrayOf("%$query%", "%$query%"),
                null, null, "timestamp DESC", "20"
            )

            while (cursor.moveToNext()) {
                val node = UnifiedMemoryDatabase.MemoryNode(
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
                results.add(SearchResult(node, 1.0f)) // Text match has high heuristic similarity for now.
            }
            cursor.close()
            
            // Sort by relevance (Time and match strength)
            results.sortedByDescending { it.node.timestamp }.take(limit)
        }
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
