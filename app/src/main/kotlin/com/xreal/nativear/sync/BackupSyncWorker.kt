package com.xreal.nativear.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.memory.api.IMemoryStore
import org.koin.java.KoinJavaComponent.getKoin

/**
 * WorkManager CoroutineWorker that syncs unsynced structured_data
 * and recent memory_nodes to the configured backup server.
 */
class BackupSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val TAG = "BackupSyncWorker"

    override suspend fun doWork(): Result {
        val config: BackupSyncConfig
        val apiClient: SyncApiClient
        val memoryStore: IMemoryStore
        val database: UnifiedMemoryDatabase

        try {
            config = getKoin().get()
            apiClient = getKoin().get()
            memoryStore = getKoin().get()
            database = getKoin().get()  // structured_data 전용 (IMemoryStore 미포함)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get dependencies: ${e.message}")
            return Result.failure()
        }

        if (!config.isConfigured) {
            Log.d(TAG, "Backup sync not configured, skipping")
            return Result.success()
        }

        Log.i(TAG, "Starting backup sync...")
        var syncedCount = 0

        // 1. Upload unsynced structured_data
        try {
            val unsyncedRecords = database.getUnsyncedStructuredData(limit = 200)
            if (unsyncedRecords.isNotEmpty()) {
                val payloads = unsyncedRecords.map { record ->
                    SyncApiClient.StructuredDataPayload(
                        id = record.id,
                        domain = record.domain,
                        dataKey = record.dataKey,
                        value = record.value,
                        tags = record.tags,
                        createdAt = record.createdAt,
                        updatedAt = record.updatedAt
                    )
                }

                val syncedIds = apiClient.uploadStructuredData(payloads)
                if (syncedIds.isNotEmpty()) {
                    database.markStructuredDataSynced(syncedIds)
                    syncedCount += syncedIds.size
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Structured data sync failed: ${e.message}", e)
        }

        // 2. Upload recent memory_nodes (last 24 hours)
        try {
            val now = System.currentTimeMillis()
            val oneDayAgo = now - 24 * 3600_000L
            val recentRecords = memoryStore.searchTemporal(oneDayAgo, now)

            if (recentRecords.isNotEmpty()) {
                val payloads = recentRecords.map { record ->
                    SyncApiClient.MemoryNodePayload(
                        id = record.id,
                        timestamp = record.timestamp,
                        role = record.role,
                        content = record.content,
                        level = record.level,
                        latitude = record.latitude,
                        longitude = record.longitude,
                        metadata = record.metadata,
                        personaId = record.personaId
                    )
                }

                if (apiClient.uploadMemoryNodes(payloads)) {
                    syncedCount += payloads.size
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Memory nodes sync failed: ${e.message}", e)
        }

        Log.i(TAG, "Backup sync complete: $syncedCount records synced")

        // 백업 완료 후 서버 예측 캐시 무효화 (새 데이터 반영)
        if (syncedCount > 0) {
            try {
                val serverUrl = config.serverUrl
                val apiKey = config.apiKey
                if (serverUrl.isNotBlank() && apiKey.isNotBlank()) {
                    val url = java.net.URL("$serverUrl/api/digital-twin/invalidate-cache")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Authorization", "Bearer $apiKey")
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 5_000
                    val code = conn.responseCode
                    conn.disconnect()
                    Log.i(TAG, "Prediction cache invalidated (HTTP $code)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cache invalidation failed (non-fatal): ${e.message}")
            }
        }

        return Result.success()
    }
}
