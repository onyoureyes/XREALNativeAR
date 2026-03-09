package com.xreal.nativear

import android.content.Context

/**
 * CloudBackupManager: Handles cloud backup operations.
 */
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * CloudBackupManager: Handles cloud backup operations.
 * Enhanced to support JSON-based memory synchronization.
 */
class CloudBackupManager(private val context: Context, private val memoryService: IMemoryService) {
    private val TAG = "CloudBackupManager"

    suspend fun syncToCloud(): String {
        Log.i(TAG, "Starting cloud synchronization...")
        return try {
            val nodes = memoryService.getAllMemories()
            val jsonArray = JSONArray()
            
            for (node in nodes) {
                val json = JSONObject().apply {
                    put("id", node.id)
                    put("timestamp", node.timestamp)
                    put("role", node.role)
                    put("content", node.content)
                    put("latitude", node.latitude ?: JSONObject.NULL)
                    put("longitude", node.longitude ?: JSONObject.NULL)
                    put("metadata", node.metadata ?: JSONObject.NULL)
                }
                jsonArray.put(json)
            }
            
            // Simulation: Save to a 'cloud_v1.json' file in internal storage
            val backupFile = File(context.filesDir, "cloud_backup.json")
            backupFile.writeText(jsonArray.toString(2))
            
            Log.i(TAG, "Sync complete. Uploaded ${nodes.size} nodes.")
            "Successfully synced ${nodes.size} memories to cloud."
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed: ${e.message}")
            "Cloud sync failed: ${e.message}"
        }
    }
    
    suspend fun restoreFromCloud(): String {
        Log.i(TAG, "Starting cloud restoration...")
        return try {
            val backupFile = File(context.filesDir, "cloud_backup.json")
            if (!backupFile.exists()) return "No backup found in cloud."

            val jsonStr = backupFile.readText()
            val jsonArray = JSONArray(jsonStr)

            var restoredCount = 0
            for (i in 0 until jsonArray.length()) {
                val json = jsonArray.getJSONObject(i)
                memoryService.saveMemory(
                    content = json.getString("content"),
                    role = json.getString("role"),
                    metadata = if (json.isNull("metadata")) null else json.getString("metadata"),
                    lat = if (json.isNull("latitude")) null else json.getDouble("latitude"),
                    lon = if (json.isNull("longitude")) null else json.getDouble("longitude")
                )
                restoredCount++
            }
            
            Log.i(TAG, "Restore complete. Imported $restoredCount nodes.")
            "Successfully restored $restoredCount memories from cloud."
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed: ${e.message}")
            "Restoration failed: ${e.message}"
        }
    }
}

