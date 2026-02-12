package com.xreal.nativear

import android.content.Context
import android.util.Log

/**
 * MemoryRepository: High-level repository for managing memories using UnifiedMemoryDatabase.
 */
class MemoryRepository(
    private val context: Context,
    private val memorySearcher: MemorySearcher,
    private val memoryCompressor: MemoryCompressor,
    private val locationService: ILocationService,
    private val textEmbedder: TextEmbedder
) : IMemoryService {
    private val TAG = "MemoryRepository"
    val database = UnifiedMemoryDatabase(context)
    
    override suspend fun saveMemory(content: String, role: String, metadata: String?, lat: Double?, lon: Double?) {
        Log.i(TAG, "Saving memory: $content")
        
        // 1. Resolve Location if missing
        var finalLat = lat
        var finalLon = lon
        if (finalLat == null || finalLon == null) {
            val currentLoc = locationService.getCurrentLocation()
            if (currentLoc != null) {
                finalLat = currentLoc.latitude
                finalLon = currentLoc.longitude
            }
        }

        // 2. Generate Text Embedding (Future/Partial)
        // val embedding = textEmbedder.getEmbedding(content) 
        // For now, let's keep embedding optional/null to avoid blocking on main thread if textEmbedder is heavy
        
        val node = UnifiedMemoryDatabase.MemoryNode(
            timestamp = System.currentTimeMillis(),
            role = role,
            content = content,
            metadata = metadata,
            latitude = finalLat,
            longitude = finalLon
        )
        database.insertNode(node)
        
        // Trigger hierarchical compression
        memoryCompressor.checkAndCompress()
    }



    override suspend fun queryTemporal(startTime: Long, endTime: Long): String {
        val nodes = database.getNodesInTimeRange(startTime, endTime)
        return formatNodesAsJson(nodes)
    }

    override suspend fun querySpatial(lat: Double, lon: Double, radiusKm: Double): String {
        val nodes = database.getNodesInSpatialRange(lat, lon, radiusKm)
        return formatNodesAsJson(nodes)
    }

    override suspend fun queryKeyword(keyword: String): String {
        val results = memorySearcher.search(keyword)
        return formatNodesAsJson(results.map { it.node })
    }

    override suspend fun queryVisual(bitmap: android.graphics.Bitmap): String {
        val results = memorySearcher.searchByImage(bitmap)
        return formatNodesAsJson(results.map { it.node })
    }

    private fun formatNodesAsJson(nodes: List<UnifiedMemoryDatabase.MemoryNode>): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val array = org.json.JSONArray()
        for (node in nodes) {
            val obj = org.json.JSONObject()
            obj.put("id", node.id)
            obj.put("time", sdf.format(java.util.Date(node.timestamp)))
            obj.put("role", node.role)
            obj.put("content", node.content)
            obj.put("lat", node.latitude)
            obj.put("lon", node.longitude)
            array.put(obj)
        }
        return array.toString()
    }

    fun getMemories(limit: Int = 10): List<UnifiedMemoryDatabase.MemoryNode> {
        return database.getUnsummarizedNodes(0, limit)
    }
}

