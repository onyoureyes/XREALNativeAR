package com.xreal.nativear.sync

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * REST client for uploading structured_data and memory_nodes to a backup server.
 */
class SyncApiClient(
    private val httpClient: OkHttpClient,
    private val config: BackupSyncConfig
) {
    private val TAG = "SyncApiClient"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * Upload a batch of structured data records.
     * @return list of successfully uploaded record IDs
     */
    fun uploadStructuredData(records: List<StructuredDataPayload>): List<Long> {
        if (!config.isConfigured) return emptyList()

        val payload = JSONObject().apply {
            put("type", "structured_data")
            put("records", JSONArray().apply {
                records.forEach { record ->
                    put(JSONObject().apply {
                        put("id", record.id)
                        put("domain", record.domain)
                        put("data_key", record.dataKey)
                        put("value", record.value)
                        put("tags", record.tags)
                        put("created_at", record.createdAt)
                        put("updated_at", record.updatedAt)
                    })
                }
            })
        }

        return try {
            val request = Request.Builder()
                .url("${config.serverUrl}/api/sync/structured-data")
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                Log.i(TAG, "Uploaded ${records.size} structured data records")
                records.map { it.id }
            } else {
                Log.w(TAG, "Upload failed: ${response.code} ${response.message}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload error: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Upload memory nodes.
     * @return true if successful
     */
    fun uploadMemoryNodes(nodes: List<MemoryNodePayload>): Boolean {
        if (!config.isConfigured) return false

        val payload = JSONObject().apply {
            put("type", "memory_nodes")
            put("records", JSONArray().apply {
                nodes.forEach { node ->
                    put(JSONObject().apply {
                        put("id", node.id)
                        put("timestamp", node.timestamp)
                        put("role", node.role)
                        put("content", node.content)
                        put("level", node.level)
                        put("latitude", node.latitude)
                        put("longitude", node.longitude)
                        put("metadata", node.metadata)
                        put("persona_id", node.personaId)
                    })
                }
            })
        }

        return try {
            val request = Request.Builder()
                .url("${config.serverUrl}/api/sync/memory-nodes")
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                Log.i(TAG, "Uploaded ${nodes.size} memory nodes")
                true
            } else {
                Log.w(TAG, "Memory upload failed: ${response.code} ${response.message}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Memory upload error: ${e.message}", e)
            false
        }
    }

    data class StructuredDataPayload(
        val id: Long, val domain: String, val dataKey: String,
        val value: String, val tags: String?,
        val createdAt: Long, val updatedAt: Long
    )

    data class MemoryNodePayload(
        val id: Long, val timestamp: Long, val role: String,
        val content: String, val level: Int,
        val latitude: Double?, val longitude: Double?,
        val metadata: String?, val personaId: String?
    )
}
