package com.xreal.nativear.expert

import android.util.Log
import com.xreal.nativear.context.IContextSnapshot
import com.xreal.nativear.context.LifeSituation
import com.xreal.nativear.context.SituationRecognizer
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.policy.PolicyReader
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.goal.IGoalService
import com.xreal.nativear.learning.IOutcomeRecorder
import com.xreal.nativear.plan.IPlanService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * BriefingService: Generates morning and evening briefings.
 *
 * Morning briefing (triggered when MORNING_ROUTINE detected):
 * - Today's schedule and todos
 * - Weather (if available)
 * - Health summary (last night sleep, current vitals)
 * - Yesterday's unfinished items
 * - Goal progress
 *
 * Evening review (triggered when EVENING_WIND_DOWN detected):
 * - Today's completed/missed items
 * - Emotion summary
 * - Health stats
 * - Goal progress update
 * - Tomorrow preview
 *
 * Briefings are delivered via TTS + HUD overlay.
 */
class BriefingService(
    private val eventBus: GlobalEventBus,
    private val planManager: IPlanService,
    private val goalTracker: IGoalService,
    private val outcomeTracker: IOutcomeRecorder,
    private val contextAggregator: IContextSnapshot,
    private val situationRecognizer: SituationRecognizer,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "BriefingService"
        private val MIN_BRIEFING_INTERVAL_MS: Long get() = PolicyReader.getLong("expert.min_briefing_interval_ms", 14_400_000L) // Min 4 hours between briefings
    }

    private var eventJob: Job? = null
    private var lastMorningBriefing: Long = 0
    private var lastEveningReview: Long = 0

    fun start() {
        Log.i(TAG, "BriefingService started")
        eventJob = scope.launch(Dispatchers.Default) {
            eventBus.events.collectLatest { event ->
                if (event is XRealEvent.SystemEvent.SituationChanged) {
                    try {
                        onSituationChanged(event)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in briefing: ${e.message}")
                    }
                }
            }
        }
    }

    fun stop() {
        eventJob?.cancel()
        Log.i(TAG, "BriefingService stopped")
    }

    private suspend fun onSituationChanged(event: XRealEvent.SystemEvent.SituationChanged) {
        val now = System.currentTimeMillis()
        when (event.newSituation) {
            LifeSituation.MORNING_ROUTINE -> {
                if (now - lastMorningBriefing > MIN_BRIEFING_INTERVAL_MS) {
                    lastMorningBriefing = now
                    deliverMorningBriefing()
                }
            }
            LifeSituation.EVENING_WIND_DOWN, LifeSituation.SLEEPING_PREP -> {
                if (now - lastEveningReview > MIN_BRIEFING_INTERVAL_MS) {
                    lastEveningReview = now
                    deliverEveningReview()
                }
            }
            else -> { /* no briefing */ }
        }
    }

    // ─── Morning Briefing ───

    private suspend fun deliverMorningBriefing() {
        Log.i(TAG, "☀️ Generating morning briefing...")

        val briefing = buildMorningBriefing()

        // Publish as TTS
        eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(briefing))

        // Also log for debug
        eventBus.publish(XRealEvent.SystemEvent.DebugLog(
            "☀️ 아침 브리핑 전달됨"
        ))
    }

    fun buildMorningBriefing(): String {
        val sb = StringBuilder()
        sb.appendLine("좋은 아침이에요!")

        // 1. Today's schedule
        try {
            val todaySchedule = planManager.getScheduleForDay(System.currentTimeMillis())
            if (todaySchedule.isNotEmpty()) {
                sb.appendLine("오늘 일정 ${todaySchedule.size}개가 있어요.")
                todaySchedule.take(3).forEach { block ->
                    val hour = java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA)
                        .format(java.util.Date(block.startTime))
                    sb.appendLine("  $hour ${block.title}")
                }
                if (todaySchedule.size > 3) {
                    sb.appendLine("  외 ${todaySchedule.size - 3}개 일정")
                }
            } else {
                sb.appendLine("오늘 등록된 일정은 없어요.")
            }
        } catch (_: Exception) { }

        // 2. Pending todos
        try {
            val pendingTodos = planManager.getPendingTodos()
            if (pendingTodos.isNotEmpty()) {
                sb.appendLine("할일 ${pendingTodos.size}개가 남아있어요.")
                pendingTodos.take(3).forEach { todo ->
                    sb.appendLine("  • ${todo.title}")
                }
            }
        } catch (_: Exception) { }

        // 3. Goal progress
        try {
            val goalSummary = goalTracker.getTodaySummary()
            if (goalSummary.isNotBlank()) {
                sb.appendLine(goalSummary)
            }
        } catch (_: Exception) { }

        // 4. Health snapshot
        try {
            val snapshot = contextAggregator.buildSnapshot()
            if (snapshot.heartRate != null) {
                sb.appendLine("현재 심박수 ${snapshot.heartRate}bpm")
            }
            if (snapshot.hrv != null) {
                val hrvStatus = when {
                    snapshot.hrv > 50f -> "양호"
                    snapshot.hrv > 30f -> "보통"
                    else -> "낮음"
                }
                sb.appendLine("HRV: ${snapshot.hrv.toInt()}ms ($hrvStatus)")
            }
        } catch (_: Exception) { }

        sb.appendLine("오늘도 좋은 하루 보내세요!")
        return sb.toString()
    }

    // ─── Evening Review ───

    private suspend fun deliverEveningReview() {
        Log.i(TAG, "🌙 Generating evening review...")

        val review = buildEveningReview()

        // Publish as TTS
        eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(review))

        eventBus.publish(XRealEvent.SystemEvent.DebugLog(
            "🌙 저녁 리뷰 전달됨"
        ))
    }

    fun buildEveningReview(): String {
        val sb = StringBuilder()
        sb.appendLine("오늘 하루 수고하셨어요!")

        // 1. Daily summary from PlanManager
        try {
            val summary = planManager.buildDailySummary()
            sb.appendLine(summary)
        } catch (_: Exception) { }

        // 2. AI effectiveness stats
        try {
            val stats = outcomeTracker.getOverallStats()
            if (stats.totalInterventions > 0) {
                val rate = (stats.acceptanceRate * 100).toInt()
                sb.appendLine("AI 어시스턴트 채택률: ${rate}% (${stats.followed}/${stats.totalInterventions})")
            }
        } catch (_: Exception) { }

        // 3. Goal check
        try {
            val goalSummary = goalTracker.getTodaySummary()
            if (goalSummary.isNotBlank()) {
                sb.appendLine(goalSummary)
            }
        } catch (_: Exception) { }

        // 4. Health
        try {
            val snapshot = contextAggregator.buildSnapshot()
            if (snapshot.stepsLast5Min > 0 || snapshot.heartRate != null) {
                sb.appendLine("건강 상태:")
                snapshot.heartRate?.let { sb.appendLine("  심박: ${it}bpm") }
                snapshot.hrv?.let { sb.appendLine("  HRV: ${it.toInt()}ms") }
            }
        } catch (_: Exception) { }

        sb.appendLine("편안한 밤 되세요. 내일도 응원할게요!")
        return sb.toString()
    }

    // ─── Manual Trigger ───

    fun triggerMorningBriefing() {
        scope.launch {
            deliverMorningBriefing()
        }
    }

    fun triggerEveningReview() {
        scope.launch {
            deliverEveningReview()
        }
    }
}
