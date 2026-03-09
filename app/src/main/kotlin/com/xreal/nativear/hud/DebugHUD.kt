package com.xreal.nativear.hud

import android.util.Log
import com.xreal.nativear.DrawCommand
import com.xreal.nativear.DrawElement
import com.xreal.nativear.context.SituationRecognizer
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.GestureType
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.deliberation.DeliberationManager
import com.xreal.nativear.expert.IExpertService
import com.xreal.nativear.learning.IOutcomeRecorder
import com.xreal.nativear.monitoring.TokenEconomyManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.*

/**
 * DebugHUD: Developer-facing AR overlay for real-time AI monitoring.
 *
 * Displays token gauges, expert stats, activity feed, situation, deliberations.
 * Toggle: QUAD_TAP gesture.
 */
class DebugHUD(
    private val eventBus: GlobalEventBus,
    private val tokenEconomy: TokenEconomyManager,
    private val expertTeamManager: IExpertService,
    private val deliberationManager: DeliberationManager,
    private val situationRecognizer: SituationRecognizer,
    private val outcomeTracker: IOutcomeRecorder,
    private val scope: CoroutineScope
) : IHUDWidgetRenderer {

    override val supportedWidgets = setOf(HUDWidget.DEBUG_PANELS)

    override fun onWidgetActivated(widget: HUDWidget) {
        if (widget == HUDWidget.DEBUG_PANELS && !isVisible) {
            toggle()
        }
    }

    override fun onWidgetDeactivated(widget: HUDWidget) {
        if (widget == HUDWidget.DEBUG_PANELS && isVisible) {
            toggle()
        }
    }
    companion object {
        private const val TAG = "DebugHUD"
        private const val PREFIX = "dbg_"
        private const val UPDATE_INTERVAL_MS = 3000L
    }

    private var isVisible = false
    private var updateJob: Job? = null
    private var gestureJob: Job? = null
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.KOREA)
    private val activityFeed = mutableListOf<String>()
    // ★ 에러 피드: 최근 5개 에러 표시 (코드, 요약, 시간)
    private val errorFeed = mutableListOf<String>()
    private val renderedIds = mutableSetOf<String>()

    fun start() {
        Log.i(TAG, "DebugHUD started (toggle: QUAD_TAP)")
        gestureJob = scope.launch(Dispatchers.Default) {
            eventBus.events.collectLatest { event ->
                when (event) {
                    is XRealEvent.InputEvent.Gesture -> {
                        if (event.type == GestureType.QUAD_TAP) toggle()
                    }
                    is XRealEvent.SystemEvent.DebugLog -> {
                        addToFeed(event.message)
                    }
                    // ★ SystemEvent.Error 구독 — DebugHUD 에러 피드에 추가
                    is XRealEvent.SystemEvent.Error -> {
                        addToErrorFeed(event.code, event.message)
                    }
                    else -> {}
                }
            }
        }
    }

    fun stop() {
        hide()
        gestureJob?.cancel()
        Log.i(TAG, "DebugHUD stopped")
    }

    fun toggle() {
        if (isVisible) hide() else show()
    }

    fun show() {
        isVisible = true
        Log.i(TAG, "Debug HUD ON")
        updateJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    renderDebugOverlay()
                } catch (e: Exception) {
                    Log.e(TAG, "Render error: ${e.message}")
                }
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    fun hide() {
        isVisible = false
        updateJob?.cancel()
        // Remove all debug elements
        scope.launch {
            renderedIds.forEach { id ->
                eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(
                    DrawCommand.Remove(id)
                ))
            }
            renderedIds.clear()
        }
        Log.i(TAG, "Debug HUD OFF")
    }

    private fun addToFeed(message: String) {
        val time = timeFormat.format(Date())
        synchronized(activityFeed) {
            activityFeed.add(0, "[$time] ${message.take(28)}")
            if (activityFeed.size > 6) activityFeed.removeAt(activityFeed.lastIndex)
        }
    }

    private fun addToErrorFeed(code: String, message: String) {
        val time = timeFormat.format(Date())
        Log.w(TAG, "⚠️ Error: $code — $message")
        synchronized(errorFeed) {
            errorFeed.add(0, "[$time] $code: ${message.take(22)}")
            if (errorFeed.size > 5) errorFeed.removeAt(errorFeed.lastIndex)
        }
    }

    private suspend fun renderDebugOverlay() {
        // ─── Background panel (에러 피드 추가로 높이 확장: 50→62%) ───
        val hasErrors = synchronized(errorFeed) { errorFeed.isNotEmpty() }
        val panelHeight = if (hasErrors) 62f else 50f
        drawRect("${PREFIX}bg", 1f, 1f, 98f, panelHeight, "#000000", 0.5f)

        // ─── Row 1: Token Gauges + Cost ───
        val gauges = tokenEconomy.getTokenGauges()
        val totalCost = gauges.sumOf { it.costUsd.toDouble() }.toFloat()

        val gaugeText = buildString {
            append("$${String.format("%.3f", totalCost)}  ")
            gauges.sortedByDescending { it.usedTokens }.forEach { g ->
                val initial = when {
                    g.modelName.contains("gemini") -> "G"
                    g.modelName.contains("gpt") -> "O"
                    g.modelName.contains("claude") -> "C"
                    g.modelName.contains("grok") -> "X"
                    else -> "?"
                }
                val filled = (g.usagePercent / 20).toInt().coerceIn(0, 5)
                val bar = "█".repeat(filled) + "░".repeat(5 - filled)
                append("$initial:$bar ${formatTokens(g.usedTokens)}  ")
            }
        }
        drawText("${PREFIX}gauge", gaugeText, 2f, 3f, 11f, "#00FF00")

        // ─── Left: Activity Feed ───
        val feedLines = synchronized(activityFeed) { activityFeed.toList() }
        for (i in 0..5) {
            val text = feedLines.getOrElse(i) { "" }
            drawText("${PREFIX}f$i", text, 2f, 9f + i * 5f, 10f, "#88FF88")
        }

        // ─── Right: Expert Stats ───
        val activeExperts = expertTeamManager.getActiveExperts()
        val expertLines = activeExperts.take(5).map { expert ->
            val eff = (outcomeTracker.getExpertEffectiveness(expert.id) * 100).toInt()
            "${expert.id.take(10)} ${eff}%"
        }
        for (i in 0..4) {
            val text = expertLines.getOrElse(i) { "" }
            drawText("${PREFIX}e$i", text, 55f, 9f + i * 5f, 10f, "#88CCFF")
        }

        // ─── Bottom: Situation + Deliberation ───
        val sit = situationRecognizer.currentSituation.value
        drawText("${PREFIX}sit", "상황: ${sit.displayName} (${sit.name})", 2f, 40f, 11f, "#FFCC00")

        val delibs = deliberationManager.getRecentDeliberations(2)
        val delibText = if (delibs.isNotEmpty()) {
            "토론: " + delibs.joinToString(" | ") { "[${it.status}] ${it.topic.take(12)}" }
        } else "토론: 없음"
        drawText("${PREFIX}dlb", delibText, 2f, 45f, 10f, "#FF88FF")

        // ─── Overall acceptance rate ───
        val stats = outcomeTracker.getOverallStats()
        val rateText = "채택률: ${(stats.acceptanceRate * 100).toInt()}% (${stats.followed}/${stats.totalInterventions})"
        drawText("${PREFIX}rate", rateText, 55f, 40f, 10f, "#FFAA00")

        // ─── ★ 에러 피드 (최근 5건, 빨간색) ───
        // SystemEvent.Error가 발행될 때만 표시. 에러 없으면 섹션 숨김.
        val errorLines = synchronized(errorFeed) { errorFeed.toList() }
        if (errorLines.isNotEmpty()) {
            drawText("${PREFIX}err_title", "⚠ 에러 로그", 2f, 51f, 11f, "#FF4444")
            for (i in 0..4) {
                val text = errorLines.getOrElse(i) { "" }
                drawText("${PREFIX}err$i", text, 2f, 56f + i * 4f, 10f, "#FF8888")
            }
        } else {
            // 에러 없을 땐 이전 에러 HUD 요소 제거
            for (i in 0..4) {
                val id = "${PREFIX}err$i"
                if (id in renderedIds) {
                    eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(DrawCommand.Remove(id)))
                    renderedIds.remove(id)
                }
            }
            val titleId = "${PREFIX}err_title"
            if (titleId in renderedIds) {
                eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(DrawCommand.Remove(titleId)))
                renderedIds.remove(titleId)
            }
        }
    }

    private suspend fun drawText(id: String, text: String, x: Float, y: Float, size: Float, color: String) {
        renderedIds.add(id)
        eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(
            DrawCommand.Add(DrawElement.Text(
                id = id, x = x, y = y, text = text, size = size, color = color, opacity = 0.9f
            ))
        ))
    }

    private suspend fun drawRect(id: String, x: Float, y: Float, w: Float, h: Float, color: String, opacity: Float) {
        renderedIds.add(id)
        eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(
            DrawCommand.Add(DrawElement.Rect(
                id = id, x = x, y = y, width = w, height = h,
                color = color, opacity = opacity, filled = true
            ))
        ))
    }

    private fun formatTokens(tokens: Long): String = when {
        tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
        tokens >= 1_000 -> "${tokens / 1_000}K"
        else -> "$tokens"
    }
}
