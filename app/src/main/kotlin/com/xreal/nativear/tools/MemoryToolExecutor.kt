package com.xreal.nativear.tools

import android.graphics.Bitmap
import com.xreal.nativear.CloudBackupManager
import com.xreal.nativear.MemorySearcher
import com.xreal.nativear.SceneDatabase
import com.xreal.nativear.memory.api.IMemoryStore

class MemoryToolExecutor(
    private val memoryStore: IMemoryStore,
    private val memorySearcher: MemorySearcher,
    private val sceneDatabase: SceneDatabase,
    private val cloudBackupManager: CloudBackupManager,
    private val bitmapProvider: () -> Bitmap?
) : IToolExecutor {

    override val supportedTools = setOf(
        "query_temporal_memory", "query_spatial_memory",
        "query_keyword_memory", "query_visual_memory",
        "query_emotion_memory", "sync_memory"
    )

    override suspend fun execute(name: String, args: Map<String, Any?>): ToolResult {
        return when (name) {
            "query_temporal_memory" -> {
                val start = parseTimeToLong(args["start_time"] as? String ?: "")
                val end = parseTimeToLong(args["end_time"] as? String ?: "")
                val results = memoryStore.searchTemporal(start, end)
                val formatted = results.joinToString("\n") { "[${it.role}] ${it.content}" }
                ToolResult(true, formatted.ifEmpty { "No memories found in the specified time range." })
            }
            "query_spatial_memory" -> {
                val lat = (args["latitude"] as? Number)?.toDouble() ?: 0.0
                val lon = (args["longitude"] as? Number)?.toDouble() ?: 0.0
                val radius = (args["radius_km"] as? Number)?.toDouble() ?: 0.5
                val results = memoryStore.searchSpatial(lat, lon, radius)
                val formatted = results.joinToString("\n") { "[${it.role}] ${it.content}" }
                ToolResult(true, formatted.ifEmpty { "No memories found near the specified location." })
            }
            "query_keyword_memory" -> {
                val keyword = args["keyword"] as? String ?: ""
                val results = memoryStore.searchKeyword(keyword)
                val formatted = results.joinToString("\n") { "[${it.record.role}] ${it.record.content}" }
                ToolResult(true, formatted.ifEmpty { "No memories found for keyword '$keyword'." })
            }
            "query_visual_memory" -> {
                val bitmap = bitmapProvider()
                if (bitmap != null) {
                    val results = memorySearcher.searchByImage(bitmap)
                    val formatted = formatSearchResults(results)
                    ToolResult(true, formatted.ifEmpty { "No visually similar memories found." })
                } else {
                    ToolResult(false, "Error: Could not capture current view.")
                }
            }
            "query_emotion_memory" -> {
                val emotion = args["emotion"] as? String ?: ""
                val logs = sceneDatabase.getVoiceLogsByEmotion(emotion)
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                val formatted = logs.joinToString("\n") { log ->
                    "[${sdf.format(java.util.Date(log.timestamp))} | ${log.emotion}] ${log.transcript}"
                }
                ToolResult(true, formatted.ifEmpty { "No memories found with emotion '$emotion'." })
            }
            "sync_memory" -> {
                cloudBackupManager.syncToCloud()
                ToolResult(true, "Sync triggered.")
            }
            else -> ToolResult(false, "Unsupported tool: $name")
        }
    }

    private fun formatSearchResults(results: List<MemorySearcher.SearchResult>): String {
        return results.joinToString("\n") { "[${it.node.role}] ${it.node.content}" }
    }

    private fun parseTimeToLong(timeStr: String): Long {
        return try {
            when {
                timeStr.contains("today", ignoreCase = true) -> System.currentTimeMillis()
                timeStr.contains("now", ignoreCase = true) -> System.currentTimeMillis()
                else -> {
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    sdf.parse(timeStr)?.time ?: System.currentTimeMillis()
                }
            }
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}
