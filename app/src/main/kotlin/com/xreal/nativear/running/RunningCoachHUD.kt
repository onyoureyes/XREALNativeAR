package com.xreal.nativear.running

import com.xreal.nativear.DrawElement
import com.xreal.nativear.DrawCommand
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.PositionMode
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.hud.HUDWidget
import com.xreal.nativear.hud.IHUDWidgetRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * RunningCoachHUD — AR 글래스 위에 러닝 메트릭을 표시하는 HUD 렌더러.
 *
 * ## 설계 패턴: Dumb View
 * 이 HUD는 **자체 EventBus 구독을 하지 않는다**. 모든 데이터 업데이트는
 * [RunningCoachManager]의 routerProcessingLoop 또는 hudUpdateLoop에서
 * 직접 메서드 호출(updateHr, updatePace 등)로 전달됨.
 *
 * ### 왜 Dumb View인가?
 * 이전에 HUD가 독립적으로 EventBus를 구독하면서 두 가지 문제 발생:
 * 1. **이중 처리**: RunningCoachManager와 HUD가 같은 이벤트를 각각 처리
 * 2. **순서 보장 불가**: 라우터 결정이 HUD에 반영되기 전에 HUD가 먼저 업데이트
 *
 * ## 렌더링
 * DrawingCommand 이벤트를 EventBus로 발행 → OutputCoordinator → OverlayView.
 * 각 요소는 고유 ID로 식별되어 개별 업데이트 가능.
 *
 * ## 레이아웃 (우상단)
 * ```
 * y=5  : ⏱ 00:00  |  5'30"/km
 * y=12 : 📏 0.00 km
 * y=19 : 🏃 180 spm
 * y=26 : ❤ 150 bpm
 * y=31 : ──────────
 * y=35 : 🧘 안정도 85
 * y=42 : 🫁 호흡 18/m
 * y=90 : [코치 메시지 3초 표시]
 * ```
 */
class RunningCoachHUD(
    private val eventBus: GlobalEventBus
) : IHUDWidgetRenderer {

    override val supportedWidgets = setOf(HUDWidget.RUNNING_STATS)

    override fun onWidgetActivated(widget: HUDWidget) {
        if (widget == HUDWidget.RUNNING_STATS) show()
    }

    override fun onWidgetDeactivated(widget: HUDWidget) {
        if (widget == HUDWidget.RUNNING_STATS) hide()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        const val ID_BG_PANEL = "rc_bg"
        const val ID_TIMER = "rc_timer"
        const val ID_PACE = "rc_pace"
        const val ID_DISTANCE = "rc_dist"
        const val ID_CADENCE = "rc_cadence"
        const val ID_HR_VAL = "rc_hr_val"
        const val ID_SPO2_VAL = "rc_spo2"
        const val ID_HR_LINE = "rc_hr"
        const val ID_STABILITY = "rc_stability"
        const val ID_BREATHING = "rc_breathing"
        const val ID_COACH_MSG = "rc_coach"
        const val ID_LAP_LABEL = "rc_lap"
        const val ID_FLOOR = "rc_floor"
        const val ID_POS_MODE = "rc_posmode"
    }

    private var isVisible = false

    // HUD Layout (percentage coords, top-right quadrant):
    //  ┌────────────────────┐
    //  │  ⏱ 12:34    5'20" │  y=5  (timer + pace)
    //  │  📏 3.24 km        │  y=12 (distance)
    //  │  👟 178 spm        │  y=19 (cadence)
    //  │  ❤️ 155 bpm        │  y=26 (heart rate from watch)
    //  │  ─────────────────  │  y=31 (separator)
    //  │  🧠 Stability: 85  │  y=35 (head stability)
    //  │  🫁 BPM: 24        │  y=42 (breathing)
    //  │                     │
    //  │  "Great form!"      │  y=90 (coach message, bottom)
    //  └────────────────────┘

    fun show() {
        isVisible = true
        // Background panel (expanded height for HR row)
        draw(DrawCommand.Add(DrawElement.Rect(
            id = ID_BG_PANEL, x = 55f, y = 2f, width = 44f, height = 44f,
            color = "#000000", opacity = 0.4f, filled = true
        )))
        // Initial labels
        drawText(ID_TIMER, 57f, 7f, "00:00", 20f, bold = true, color = "#FFFFFF")
        drawText(ID_PACE, 80f, 7f, "--'--\"", 18f, color = "#00FF00")
        drawText(ID_DISTANCE, 57f, 14f, "0.00 km", 18f, color = "#00FFFF")
        drawText(ID_CADENCE, 57f, 21f, "-- spm", 18f, color = "#FFD700")
        drawText(ID_HR_VAL, 57f, 28f, "-- bpm", 18f, color = "#FF6666")
        draw(DrawCommand.Add(DrawElement.Line(
            id = ID_HR_LINE, x1 = 57f, y1 = 32f, x2 = 97f, y2 = 32f,
            color = "#FFFFFF", opacity = 0.3f, strokeWidth = 1f
        )))
        drawText(ID_STABILITY, 57f, 36f, "Stability: --", 16f, color = "#FF6600")
        drawText(ID_BREATHING, 57f, 43f, "BPM: --", 16f, color = "#FF6600")
        // HUD is a "dumb view" — all updates come from RunningCoachManager's
        // router processing loop and HUD update loop via direct method calls.
        // No independent EventBus subscription to avoid duplicate processing.
    }

    fun hide() {
        isVisible = false
        val ids = listOf(ID_BG_PANEL, ID_TIMER, ID_PACE, ID_DISTANCE, ID_CADENCE,
            ID_HR_VAL, ID_SPO2_VAL, ID_HR_LINE, ID_STABILITY, ID_BREATHING,
            ID_COACH_MSG, ID_LAP_LABEL, ID_FLOOR, ID_POS_MODE)
        ids.forEach { draw(DrawCommand.Remove(it)) }
    }

    fun updateTimer(elapsedSeconds: Long) {
        if (!isVisible) return
        val min = elapsedSeconds / 60
        val sec = elapsedSeconds % 60
        modify(ID_TIMER, mapOf("text" to String.format("%02d:%02d", min, sec)))
    }

    fun updatePace(paceMinPerKm: Float) {
        if (!isVisible) return
        if (paceMinPerKm <= 0 || paceMinPerKm > 30) {
            modify(ID_PACE, mapOf("text" to "--'--\""))
            return
        }
        val min = paceMinPerKm.toInt()
        val sec = ((paceMinPerKm - min) * 60).toInt()
        modify(ID_PACE, mapOf("text" to "${min}'${String.format("%02d", sec)}\""))
    }

    fun updateDistance(meters: Float) {
        if (!isVisible) return
        val km = meters / 1000f
        modify(ID_DISTANCE, mapOf("text" to String.format("%.2f km", km)))
    }

    fun updateCadence(spm: Float) {
        if (!isVisible) return
        val color = when {
            spm < 160 -> "#FF4444"   // Red: too slow
            spm < 170 -> "#FFD700"   // Yellow: could be better
            spm <= 185 -> "#00FF00"  // Green: optimal range
            else -> "#FFD700"        // Yellow: very high
        }
        modify(ID_CADENCE, mapOf("text" to "${spm.toInt()} spm", "color" to color))
    }

    fun updateCadenceWithColor(spm: Float, color: String) {
        if (!isVisible) return
        modify(ID_CADENCE, mapOf("text" to "${spm.toInt()} spm", "color" to color))
    }

    fun updateStability(score: Float) {
        if (!isVisible) return
        val color = when {
            score >= 80 -> "#00FF00"
            score >= 60 -> "#FFD700"
            else -> "#FF4444"
        }
        modify(ID_STABILITY, mapOf("text" to "Stability: ${score.toInt()}", "color" to color))
    }

    fun updateHr(bpm: Float) {
        if (!isVisible) return
        if (bpm <= 0) return
        // Color based on HR zone (default maxHR=190)
        val color = when {
            bpm < 114 -> "#00FF00"   // Zone 1-2 (< 60% maxHR): green
            bpm < 152 -> "#FFD700"   // Zone 3 (60-80%): yellow
            bpm < 171 -> "#FF8C00"   // Zone 4 (80-90%): orange
            else -> "#FF4444"        // Zone 5 (90%+): red
        }
        modify(ID_HR_VAL, mapOf("text" to "\u2665 ${bpm.toInt()} bpm", "color" to color))
    }

    fun updateSpO2(spo2: Int) {
        if (!isVisible) return
        if (spo2 <= 0) {
            draw(DrawCommand.Remove(ID_SPO2_VAL))
            return
        }
        // Only show SpO2 when below 95% (noteworthy)
        if (spo2 < 95) {
            val color = if (spo2 < 92) "#FF4444" else "#FFD700"
            drawText(ID_SPO2_VAL, 80f, 28f, "O\u2082 $spo2%", 16f, color = color)
        } else {
            draw(DrawCommand.Remove(ID_SPO2_VAL))
        }
    }

    fun updateBreathing(bpm: Float, isRegular: Boolean) {
        if (!isVisible) return
        val regularText = if (isRegular) "" else " !"
        modify(ID_BREATHING, mapOf("text" to "BPM: ${bpm.toInt()}$regularText"))
    }

    fun showFloorChange(deltaFloors: Int, direction: com.xreal.nativear.core.FloorDirection) {
        if (!isVisible) return
        val arrow = if (direction == com.xreal.nativear.core.FloorDirection.UP) "\u2191" else "\u2193"
        val text = "${arrow}${deltaFloors}F"
        draw(DrawCommand.Remove(ID_FLOOR))
        drawText(ID_FLOOR, 90f, 43f, text, 16f, bold = true, color = "#00FFFF")
        scope.launch {
            delay(5000L)
            draw(DrawCommand.Remove(ID_FLOOR))
        }
    }

    fun updatePositionMode(mode: PositionMode) {
        if (!isVisible) return
        val (text, color) = when (mode) {
            PositionMode.GPS_GOOD -> Pair("GPS", "#00FF00")
            PositionMode.GPS_DEGRADED -> Pair("GPS", "#FFD700")
            PositionMode.GPS_RECOVERED -> Pair("GPS", "#FFD700")
            PositionMode.PDR_ONLY -> Pair("PDR", "#FF4444")
        }
        drawText(ID_POS_MODE, 93f, 7f, text, 14f, color = color)
    }

    fun showCoachMessage(message: String) {
        if (!isVisible) return
        draw(DrawCommand.Remove(ID_COACH_MSG))
        drawText(ID_COACH_MSG, 50f, 92f, message, 22f, bold = true, color = "#00FFFF")
        scope.launch {
            delay(8000L)
            draw(DrawCommand.Remove(ID_COACH_MSG))
        }
    }

    fun showLapNotification(lapNumber: Int, lapPace: Float) {
        val min = lapPace.toInt()
        val sec = ((lapPace - min) * 60).toInt()
        draw(DrawCommand.Remove(ID_LAP_LABEL))
        drawText(ID_LAP_LABEL, 50f, 50f, "Lap $lapNumber: ${min}'${String.format("%02d", sec)}\"",
            28f, bold = true, color = "#FFD700")
        scope.launch {
            delay(5000L)
            draw(DrawCommand.Remove(ID_LAP_LABEL))
        }
    }

    private fun drawText(id: String, x: Float, y: Float, text: String,
                         size: Float, bold: Boolean = false, color: String = "#FFFFFF") {
        draw(DrawCommand.Add(DrawElement.Text(
            id = id, x = x, y = y, text = text, size = size, bold = bold, color = color, opacity = 1f
        )))
    }

    private fun modify(id: String, updates: Map<String, Any>) {
        draw(DrawCommand.Modify(id, updates))
    }

    private fun draw(command: DrawCommand) {
        scope.launch {
            eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(command))
        }
    }

    fun release() {
        hide()
        scope.cancel()
    }
}
