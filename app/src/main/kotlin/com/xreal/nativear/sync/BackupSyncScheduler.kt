package com.xreal.nativear.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Schedules periodic backup sync using WorkManager.
 */
class BackupSyncScheduler(
    private val context: Context,
    private val config: BackupSyncConfig
) {
    private val TAG = "BackupSyncScheduler"

    companion object {
        const val WORK_NAME = "xreal_backup_sync"
    }

    /**
     * Schedule periodic sync based on config.
     */
    fun schedule() {
        if (!config.isConfigured) {
            Log.d(TAG, "Backup not configured, cancelling existing schedule")
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (config.requireWifi) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .setRequiresBatteryNotLow(true)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<BackupSyncWorker>(
            config.syncIntervalMinutes, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            syncRequest
        )

        Log.i(TAG, "Backup sync scheduled: every ${config.syncIntervalMinutes}min, wifi=${config.requireWifi}")
    }

    /**
     * Trigger an immediate one-time sync.
     */
    fun triggerNow() {
        if (!config.isConfigured) {
            Log.w(TAG, "Cannot trigger sync: not configured")
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val oneTimeRequest = OneTimeWorkRequestBuilder<BackupSyncWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueue(oneTimeRequest)
        Log.i(TAG, "Immediate backup sync triggered")
    }

    /**
     * Cancel all scheduled syncs.
     */
    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        Log.i(TAG, "Backup sync cancelled")
    }
}
