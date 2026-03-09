package com.xreal.nativear.learning

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * DriveTrainingScheduler — 학습 파이프라인 WorkManager 스케줄러.
 *
 * ## BackupSyncScheduler.kt 패턴 재사용
 *
 * ## 스케줄
 * - DriveUploadWorker: 7일 주기, Wi-Fi + 배터리 정상
 * - ModelSyncWorker: 1일 주기, Wi-Fi
 *
 * ## 사용
 * AppBootstrapper.start()에서 schedule() 호출.
 * 인증 후 triggerUploadNow() / triggerModelCheckNow() 수동 트리거 가능.
 */
class DriveTrainingScheduler(private val context: Context) {

    companion object {
        private const val TAG = "DriveTrainingScheduler"
        const val WORK_UPLOAD = "xreal_drive_upload"
        const val WORK_MODEL_SYNC = "xreal_model_sync"
        const val RC_SIGN_IN = 9001  // Activity onActivityResult requestCode
    }

    /**
     * 주기적 작업 등록 (앱 시작 시 1회 호출).
     * ExistingPeriodicWorkPolicy.UPDATE: 이미 등록되어 있으면 갱신.
     */
    fun schedule() {
        scheduleUpload()
        scheduleModelSync()
        Log.i(TAG, "Drive 학습 파이프라인 스케줄 등록됨 (업로드 7일, 모델 체크 1일)")
    }

    /**
     * CSV 업로드 작업 지금 즉시 실행 (수동 테스트용).
     */
    fun triggerUploadNow() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<DriveUploadWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueue(request)
        Log.i(TAG, "즉시 업로드 트리거됨")
    }

    /**
     * 모델 체크 작업 지금 즉시 실행 (수동 테스트용).
     */
    fun triggerModelCheckNow() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<ModelSyncWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueue(request)
        Log.i(TAG, "즉시 모델 체크 트리거됨")
    }

    /**
     * 모든 스케줄 취소.
     */
    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_UPLOAD)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_MODEL_SYNC)
        Log.i(TAG, "Drive 학습 파이프라인 스케줄 취소됨")
    }

    // ─── 내부 구현 ───

    private fun scheduleUpload() {
        val constraints = Constraints.Builder()
            // UNMETERED(WiFi전용) → CONNECTED: WorkManager가 Samsung에 WiFi 요청하는 것 방지
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<DriveUploadWorker>(
            7, TimeUnit.DAYS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_UPLOAD,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun scheduleModelSync() {
        val constraints = Constraints.Builder()
            // UNMETERED(WiFi전용) → CONNECTED: WorkManager가 Samsung에 WiFi 요청하는 것 방지
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<ModelSyncWorker>(
            1, TimeUnit.DAYS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_MODEL_SYNC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
