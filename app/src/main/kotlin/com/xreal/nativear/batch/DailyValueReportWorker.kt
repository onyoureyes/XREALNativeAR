package com.xreal.nativear.batch

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xreal.nativear.monitoring.DailyValueReporter
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * DailyValueReportWorker — 매일 22:00 일일 가치 리포트 생성.
 *
 * ## 동작
 * - DailyValueReporter.generateAndSaveDailyReport() 호출
 * - 실패 시 1회 재시도 (Result.retry())
 * - AppBootstrapper에서 schedule() 호출 → 매일 22:00 자동 재예약
 *
 * ## WorkManager 설정
 * - UniqueWork: "DailyValueReport" (REPLACE 정책)
 * - 충전 중 실행 권장 (CONNECTED 아님 — 오프라인도 OK)
 * - 완료 후 다음날 22:00 재예약 (스스로 연쇄 예약)
 */
class DailyValueReportWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val reporter = try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<DailyValueReporter>()
        } catch (e: Exception) {
            Log.w(TAG, "DailyValueReporter DI 실패: ${e.message}")
            null
        }

        if (reporter == null) {
            Log.w(TAG, "DailyValueReporter 없음 — 스킵")
            rescheduleForTomorrow(applicationContext)
            return Result.success()
        }

        return try {
            val report = reporter.generateAndSaveDailyReport()
            Log.i(TAG, "✅ 일일 가치 리포트 완료: ${report.reportDate}")
            rescheduleForTomorrow(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "리포트 생성 실패 — 재시도: ${e.message}")
            if (runAttemptCount < 1) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val TAG = "DailyValueReportWorker"
        private const val WORK_NAME = "DailyValueReport"

        /**
         * 오늘 22:00 (이미 지났으면 내일 22:00) 까지 delay 계산 후 예약.
         * AppBootstrapper에서 호출.
         */
        fun schedule(context: Context) {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 22)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now.timeInMillis) {
                    add(Calendar.DATE, 1)
                }
            }
            val delayMs = target.timeInMillis - now.timeInMillis

            val request = OneTimeWorkRequestBuilder<DailyValueReportWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(false)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)

            Log.i(TAG, "DailyValueReportWorker 예약: ${delayMs / 60_000}분 후 (22:00)")
        }

        private fun rescheduleForTomorrow(context: Context) {
            try {
                val tomorrow2200 = Calendar.getInstance().apply {
                    add(Calendar.DATE, 1)
                    set(Calendar.HOUR_OF_DAY, 22)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val delayMs = tomorrow2200.timeInMillis - System.currentTimeMillis()
                val request = OneTimeWorkRequestBuilder<DailyValueReportWorker>()
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .build()
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
                Log.d(TAG, "내일 22:00 재예약 완료 (${delayMs / 3_600_000}시간 후)")
            } catch (e: Exception) {
                Log.w(TAG, "rescheduleForTomorrow 실패: ${e.message}")
            }
        }
    }
}
