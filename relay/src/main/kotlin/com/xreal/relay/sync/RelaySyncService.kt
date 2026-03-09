package com.xreal.relay.sync

import android.util.Log
import com.xreal.relay.health.HealthConnectReader
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Health Connect 데이터 → PC 백업 서버 동기화.
 *
 * POST /api/health-connect/sync
 *   Body: Health Connect JSON 스냅샷
 */
class RelaySyncService(
    private val healthConnectReader: HealthConnectReader,
    private val serverUrl: String = "http://100.101.127.124:8090"
) {
    private val TAG = "RelaySyncService"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    // 동기화 주기 (기본 10분)
    var syncIntervalMs = 10 * 60 * 1000L

    // 통계
    var syncCount = 0L
        private set
    var lastSyncSuccess = false
        private set

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun start() {
        if (running.getAndSet(true)) return

        scope.launch {
            // 첫 동기화 1분 대기
            delay(60_000)

            while (running.get()) {
                try {
                    syncToServer()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "동기화 오류: ${e.message}")
                    lastSyncSuccess = false
                }
                delay(syncIntervalMs)
            }
        }

        Log.i(TAG, "동기화 서비스 시작 (주기: ${syncIntervalMs / 60000}분)")
    }

    fun stop() {
        running.set(false)
        scope.cancel()
        Log.i(TAG, "동기화 서비스 종료 (총 ${syncCount}회)")
    }

    private suspend fun syncToServer() {
        val payload = healthConnectReader.getSyncPayload()
        if (!payload.has("timestamp")) {
            Log.d(TAG, "동기화할 데이터 없음")
            return
        }

        val body = payload.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$serverUrl/api/health-connect/sync")
            .post(body)
            .build()

        withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        syncCount++
                        lastSyncSuccess = true
                        Log.i(TAG, "Health Connect 동기화 성공 (#$syncCount)")
                    } else {
                        lastSyncSuccess = false
                        Log.w(TAG, "동기화 실패: ${response.code} ${response.message}")
                    }
                }
            } catch (e: Exception) {
                lastSyncSuccess = false
                Log.w(TAG, "서버 연결 실패: ${e.message}")
            }
        }
    }

    val isRunning: Boolean get() = running.get()
}
