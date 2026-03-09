package com.xreal.nativear.meeting

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.plan.PlanManager
import org.koin.java.KoinJavaComponent.getKoin

/**
 * ReminderCheckWorker: 다가오는 일정/할일을 확인하고 알림 발행.
 *
 * ## 알림 규칙
 * - 일정 시작 15분 전: HUD ShowMessage (조용한 알림)
 * - 일정 시작 5분 전: HUD ShowMessage + TTS SpeakTTS (음성)
 * - 할일 마감 당일: 아침에 HUD ShowMessage
 *
 * ## WorkManager에서 실행
 * - 15분 주기로 실행
 * - Koin으로 PlanManager, GlobalEventBus 주입
 */
class ReminderCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ReminderCheckWorker"
        private const val REMINDER_WINDOW_MS = 16 * 60 * 1000L  // 16분 (15분 주기 + 1분 여유)
        private const val URGENT_WINDOW_MS = 6 * 60 * 1000L     // 6분 (5분 전 음성 알림)
    }

    override suspend fun doWork(): Result {
        val planManager: PlanManager
        val eventBus: GlobalEventBus

        try {
            planManager = getKoin().get()
            eventBus = getKoin().get()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get dependencies: ${e.message}")
            return Result.retry()
        }

        return try {
            checkScheduleReminders(planManager, eventBus)
            checkTodoReminders(planManager, eventBus)
            Log.d(TAG, "Reminder check completed")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Reminder check failed: ${e.message}")
            Result.retry()
        }
    }

    /**
     * 다가오는 일정 확인 및 알림.
     */
    private suspend fun checkScheduleReminders(planManager: PlanManager, eventBus: GlobalEventBus) {
        val now = System.currentTimeMillis()
        val todaySchedule = planManager.getTodaySchedule()

        for (block in todaySchedule) {
            val timeUntilStart = block.startTime - now

            when {
                // 5분 이내: 긴급 알림 (HUD + TTS)
                timeUntilStart in 0..URGENT_WINDOW_MS -> {
                    val minutesLeft = (timeUntilStart / 60_000L).toInt()
                    val message = "⏰ ${minutesLeft}분 후: ${block.title}"
                    eventBus.publish(XRealEvent.ActionRequest.ShowMessage(message))
                    eventBus.publish(XRealEvent.ActionRequest.SpeakTTS("${minutesLeft}분 후에 ${block.title} 일정이 있습니다"))
                    Log.i(TAG, "Urgent reminder: ${block.title} in ${minutesLeft}min")
                }

                // 15분 이내: 일반 알림 (HUD만)
                timeUntilStart in URGENT_WINDOW_MS..REMINDER_WINDOW_MS -> {
                    val minutesLeft = (timeUntilStart / 60_000L).toInt()
                    val message = "📋 ${minutesLeft}분 후: ${block.title}"
                    eventBus.publish(XRealEvent.ActionRequest.ShowMessage(message))
                    Log.i(TAG, "Reminder: ${block.title} in ${minutesLeft}min")
                }

                // 시작됨 (현재 진행 중)
                timeUntilStart < 0 && (now - block.startTime) < 60_000L -> {
                    eventBus.publish(XRealEvent.ActionRequest.ShowMessage("▶️ 지금: ${block.title}"))
                    Log.i(TAG, "Schedule started: ${block.title}")
                }
            }
        }
    }

    /**
     * 마감 임박 할일 확인.
     */
    private suspend fun checkTodoReminders(planManager: PlanManager, eventBus: GlobalEventBus) {
        val now = System.currentTimeMillis()
        val todayTodos = planManager.getTodayTodos()

        // 마감이 오늘인 할일 중 미완료 항목
        val urgentTodos = todayTodos.filter { todo ->
            todo.deadline != null &&
            todo.deadline - now < 24 * 60 * 60 * 1000L &&  // 24시간 이내
            todo.deadline > now  // 아직 마감 전
        }

        if (urgentTodos.isNotEmpty()) {
            val summary = urgentTodos.take(3).joinToString(", ") { it.title }
            val message = "📌 오늘 마감: $summary"

            // 하루에 한 번만 표시 (아침 시간대)
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            if (hour in 8..9) {
                eventBus.publish(XRealEvent.ActionRequest.ShowMessage(message))
                Log.i(TAG, "Todo deadline reminder: $summary")
            }
        }
    }
}
