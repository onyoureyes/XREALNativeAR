package com.xreal.nativear.meeting

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * ReminderScheduler: WorkManager 기반 일정 리마인더.
 *
 * ## 동작 방식
 * - 15분 주기 WorkManager PeriodicWork
 * - ReminderCheckWorker가 PlanManager에서 다가오는 일정 조회
 * - 리마인더 시간이 된 일정 → EventBus ShowMessage + TTS
 *
 * ## 알림 전략
 * - 15분 전: HUD 메시지 (조용한 알림)
 * - 5분 전: HUD + TTS (음성 알림)
 * - 시작 시: HUD 배경색 변경
 */
class ReminderScheduler(
    private val context: Context
) {
    companion object {
        private const val TAG = "ReminderScheduler"
        const val WORK_NAME = "xreal_reminder_check"
        const val CHECK_INTERVAL_MINUTES = 15L
    }

    /**
     * 리마인더 체크 스케줄 시작.
     */
    fun schedule() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(false)  // 배터리 낮아도 리마인더는 동작
            .build()

        val reminderRequest = PeriodicWorkRequestBuilder<ReminderCheckWorker>(
            CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            reminderRequest
        )

        Log.i(TAG, "Reminder checker scheduled: every ${CHECK_INTERVAL_MINUTES}min")
    }

    /**
     * 리마인더 즉시 체크 트리거.
     */
    fun checkNow() {
        val oneTimeRequest = OneTimeWorkRequestBuilder<ReminderCheckWorker>()
            .build()

        WorkManager.getInstance(context).enqueue(oneTimeRequest)
        Log.i(TAG, "Immediate reminder check triggered")
    }

    /**
     * 스케줄 취소.
     */
    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        Log.i(TAG, "Reminder checker cancelled")
    }
}
