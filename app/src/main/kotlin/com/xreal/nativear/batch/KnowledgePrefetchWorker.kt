package com.xreal.nativear.batch

import android.content.Context
import android.util.Log
import androidx.work.*
import com.xreal.nativear.companion.KnowledgePrefetcher
import org.koin.java.KoinJavaComponent
import java.util.concurrent.TimeUnit

/**
 * KnowledgePrefetchWorker — 매일 02:00 에이전트 도메인 지식 갱신 Worker (Phase F-4).
 *
 * KnowledgePrefetcher.runPrefetchCycle()을 실행하여
 * 각 에이전트의 knowledgeRefreshIntervalDays 주기에 따라
 * 도메인 지식을 사전 적재하고 MemorySaveHelper(role="KNOWLEDGE")에 저장.
 *
 * ## 스케줄
 * - 유형: PeriodicWork (24시간 주기)
 * - 네트워크: 필요 (Gemini 호출)
 * - 배터리: 부족 시 실행 안 함
 * - 재시도: LinearBackoffPolicy 30분
 */
class KnowledgePrefetchWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "KnowledgePrefetchWorker"
        const val WORK_NAME = "knowledge_prefetch_daily"

        /**
         * WorkManager에 일일 지식 사전 적재 작업 등록.
         * AppBootstrapper.start()에서 호출.
         *
         * @param context Application context
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<KnowledgePrefetchWorker>(
                repeatInterval = 24,
                repeatIntervalTimeUnit = TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.i(TAG, "일일 도메인 지식 사전 적재 작업 등록 완료 (24시간 주기, 네트워크 필요)")
        }
    }

    override suspend fun doWork(): Result {
        return try {
            Log.i(TAG, "도메인 지식 사전 적재 사이클 시작...")
            val prefetcher = KoinJavaComponent.getKoin()
                .getOrNull<KnowledgePrefetcher>()

            if (prefetcher == null) {
                Log.w(TAG, "KnowledgePrefetcher 없음 — 작업 건너뜀")
                return Result.success()
            }

            val refreshedCount = prefetcher.runPrefetchCycle()
            Log.i(TAG, "도메인 지식 사전 적재 완료: $refreshedCount 개 에이전트 갱신")

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "도메인 지식 사전 적재 실패: ${e.message}", e)
            Result.retry()
        }
    }
}
