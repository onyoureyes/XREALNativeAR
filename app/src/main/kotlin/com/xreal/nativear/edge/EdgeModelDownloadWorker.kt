package com.xreal.nativear.edge

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * EdgeModelDownloadWorker — 엣지 LLM 모델 파일 백그라운드 다운로드.
 *
 * RESEARCH.md §8 WorkManager 참조.
 *
 * ## 조건
 * - Wi-Fi only (Constraints.setRequiredNetworkType(NetworkType.UNMETERED))
 * - 파일이 이미 있으면 skip
 * - 다운로드 진행률 EdgeModelStateChanged 이벤트 발행
 *
 * ## 등록 위치
 * AppModule.kt에서 WorkManager.getInstance() 자동 사용.
 * EdgeModelManager.scheduleDownloadIfNeeded()에서 enqueue.
 */
class EdgeModelDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "EdgeModelDownloadWorker"
        const val KEY_TIER = "tier"
        const val KEY_URL = "url"
        const val KEY_FILENAME = "filename"
        private const val BUFFER_SIZE = 64 * 1024  // 64KB
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val tierName = inputData.getString(KEY_TIER) ?: return@withContext Result.failure()
        val urlStr = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val filename = inputData.getString(KEY_FILENAME) ?: return@withContext Result.failure()

        val outputDir = File(applicationContext.filesDir, "edge_models").also { it.mkdirs() }
        val outputFile = File(outputDir, filename)

        // 이미 완전한 파일이 있으면 skip
        if (outputFile.exists() && outputFile.length() > 1024 * 1024) {
            Log.i(TAG, "모델 파일 이미 존재, skip: $filename")
            publishState(tierName, "READY")
            return@withContext Result.success()
        }

        Log.i(TAG, "모델 다운로드 시작: $filename")
        publishState(tierName, "DOWNLOADING", 0)

        try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 120_000
                setRequestProperty("User-Agent", "XREAL-NativeAR/1.0")
            }
            connection.connect()

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L
            var lastProgressReport = 0

            val tempFile = File(outputDir, "$filename.tmp")
            try {
                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            // 5% 간격으로 진행률 업데이트
                            val progress = if (totalBytes > 0) {
                                ((downloadedBytes * 100) / totalBytes).toInt()
                            } else 0

                            if (progress >= lastProgressReport + 5) {
                                lastProgressReport = progress
                                publishState(tierName, "DOWNLOADING", progress)
                                Log.d(TAG, "$filename 다운로드: $progress% ($downloadedBytes/$totalBytes)")
                            }
                        }
                    }
                }
                // 다운로드 완료 후 rename
                tempFile.renameTo(outputFile)
                Log.i(TAG, "✅ 모델 다운로드 완료: $filename (${outputFile.length() / 1024 / 1024}MB)")
                publishState(tierName, "READY")
                Result.success()
            } catch (e: Exception) {
                tempFile.delete()
                throw e
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "모델 다운로드 실패: $filename — ${e.message}")
            publishState(tierName, "FAILED")
            // 재시도 (WorkManager가 자동 재시도)
            Result.retry()
        }
    }

    private fun publishState(tierName: String, state: String, progress: Int = 0) {
        try {
            val eventBus: GlobalEventBus = getKoin().get()
            kotlinx.coroutines.runBlocking {
                eventBus.publish(
                    XRealEvent.SystemEvent.EdgeModelStateChanged(
                        tier = tierName,
                        state = state,
                        progressPercent = progress
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "이벤트 발행 실패: ${e.message}")
        }
    }
}
