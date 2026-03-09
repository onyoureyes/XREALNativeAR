package com.xreal.nativear.tools

import android.graphics.Bitmap
import com.xreal.nativear.CloudBackupManager
import com.xreal.nativear.IMemoryService

class MemoryToolExecutor(
    private val memoryService: IMemoryService,
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
                ToolResult(true, memoryService.queryTemporal(start, end))
            }
            "query_spatial_memory" -> {
                val lat = (args["latitude"] as? Number)?.toDouble() ?: 0.0
                val lon = (args["longitude"] as? Number)?.toDouble() ?: 0.0
                val radius = (args["radius_km"] as? Number)?.toDouble() ?: 0.5
                ToolResult(true, memoryService.querySpatial(lat, lon, radius))
            }
            "query_keyword_memory" -> {
                val keyword = args["keyword"] as? String ?: ""
                ToolResult(true, memoryService.queryKeyword(keyword))
            }
            "query_visual_memory" -> {
                val bitmap = bitmapProvider()
                if (bitmap != null) {
                    ToolResult(true, memoryService.queryVisual(bitmap))
                } else {
                    ToolResult(false, "Error: Could not capture current view.")
                }
            }
            "query_emotion_memory" -> {
                val emotion = args["emotion"] as? String ?: ""
                ToolResult(true, memoryService.queryEmotion(emotion))
            }
            "sync_memory" -> {
                cloudBackupManager.syncToCloud()
                ToolResult(true, "Sync triggered.")
            }
            else -> ToolResult(false, "Unsupported tool: $name")
        }
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
