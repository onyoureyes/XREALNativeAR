package com.xreal.nativear

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MemoryToolHandler(private val database: UnifiedMemoryDatabase) {
    private val TAG = "MemoryToolHandler"
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun getDatabaseCount(): Int {
        return database.getCount(0) // Level 0 nodes (raw input)
    }

    /**
     * Dispatches the function call to the appropriate database query.
     * Returns a JSON string of the results.
     */
    fun handle(functionName: String, arguments: Map<String, Any?>): String {
        Log.i(TAG, "Handling Tool: $functionName with args: $arguments")
        
        return try {
            when (functionName) {
                "query_temporal_memory" -> {
                    val startTimeStr = arguments["start_time"] as? String ?: return "Error: Missing start_time"
                    val endTimeStr = arguments["end_time"] as? String ?: return "Error: Missing end_time"
                    
                    val startTime = parseTimeToLong(startTimeStr)
                    val endTime = parseTimeToLong(endTimeStr)
                    
                    val nodes = database.getNodesInTimeRange(startTime, endTime)
                    formatNodesAsJson(nodes)
                }
                
                "query_spatial_memory" -> {
                    val lat = toDoubleOrNull(arguments["latitude"]) ?: return "Error: Missing or invalid latitude"
                    val lon = toDoubleOrNull(arguments["longitude"]) ?: return "Error: Missing or invalid longitude"
                    val radius = toDoubleOrNull(arguments["radius_km"]) ?: 0.5
                    
                    val nodes = database.getNodesInSpatialRange(lat, lon, radius)
                    formatNodesAsJson(nodes)
                }
                
                "query_keyword_memory" -> {
                    val keyword = arguments["keyword"] as? String ?: return "Error: Missing keyword"
                    val nodes = database.searchNodesByKeyword(keyword)
                    formatNodesAsJson(nodes)
                }
                
                else -> "Error: Unknown function $functionName"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution failed", e)
            "Error: ${e.message}"
        }
    }

    private fun parseTimeToLong(timeStr: String): Long {
        return try {
            val lower = timeStr.lowercase()
            val calendar = Calendar.getInstance()
            
            when {
                lower.contains("now") -> System.currentTimeMillis()
                lower.contains("today") -> {
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.timeInMillis
                }
                lower.contains("yesterday") -> {
                    calendar.add(Calendar.DAY_OF_YEAR, -1)
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.timeInMillis
                }
                lower.contains("morning") -> {
                    calendar.set(Calendar.HOUR_OF_DAY, 6)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.timeInMillis
                }
                lower.contains("afternoon") -> {
                    calendar.set(Calendar.HOUR_OF_DAY, 12)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.timeInMillis
                }
                lower.contains("evening") -> {
                    calendar.set(Calendar.HOUR_OF_DAY, 18)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.timeInMillis
                }
                else -> {
                    // Try standard ISO
                     try {
                        sdf.parse(timeStr)?.time ?: System.currentTimeMillis()
                     } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse time: $timeStr, defaulting to NOW")
                        System.currentTimeMillis()
                     }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Time parse error", e)
            System.currentTimeMillis()
        }
    }

    private fun formatNodesAsJson(nodes: List<UnifiedMemoryDatabase.MemoryNode>): String {
        val array = JSONArray()
        for (node in nodes) {
            val obj = JSONObject()
            obj.put("id", node.id)
            obj.put("time", sdf.format(Date(node.timestamp)))
            obj.put("role", node.role)
            obj.put("content", node.content)
            obj.put("lat", node.latitude)
            obj.put("lon", node.longitude)
            array.put(obj)
        }
        return array.toString()
    }

    private fun toDoubleOrNull(value: Any?): Double? {
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }
}
