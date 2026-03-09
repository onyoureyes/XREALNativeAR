package com.xreal.nativear.running

import android.util.Log
import com.xreal.nativear.DrawCommand
import com.xreal.nativear.DrawElement
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.hud.HUDWidget
import com.xreal.nativear.hud.IHUDWidgetRenderer
import kotlinx.coroutines.*

/**
 * SpeedGraphOverlay -- HUD에 랩별 속도 그래프를 오버레이.
 *
 * ## 핵심 기능
 * 1. **실시간 속도 선**: 현재 랩의 속도를 Polyline으로 그래프 표시
 * 2. **이전 랩 비교**: 완료된 랩들을 반투명 Polyline으로 중첩 표시
 * 3. **점진적 불투명도**: 최근 랩일수록 진하게, 오래된 랩일수록 흐리게
 * 4. **페이스 라인**: 목표 페이스를 수평 기준선으로 표시
 *
 * ## 렌더링 영역
 * HUD 좌하단 (x: 2-52%, y: 55-95%) — 러닝 메트릭 패널 아래
 *
 * ## 속도→그래프 매핑
 * X축: 랩 내 거리 진행률 (0-100%)
 * Y축: 속도 (하한 MIN_SPEED ~ 상한 MAX_SPEED → 그래프 영역 95%→55%)
 * 빠를수록 위, 느릴수록 아래
 *
 * @param eventBus 전역 이벤트 버스
 */
class SpeedGraphOverlay(
    private val eventBus: GlobalEventBus
) : IHUDWidgetRenderer {

    override val supportedWidgets = setOf(HUDWidget.SPEED_GRAPH)

    override fun onWidgetActivated(widget: HUDWidget) {
        // SpeedGraphOverlay requires data sources — activation is handled by RunningCoachManager
        // This callback is for HUDTemplateEngine lifecycle awareness
    }

    override fun onWidgetDeactivated(widget: HUDWidget) {
        if (widget == HUDWidget.SPEED_GRAPH) hide()
    }
    companion object {
        private const val TAG = "SpeedGraph"

        // 그래프 영역 (퍼센트 좌표)
        const val GRAPH_LEFT = 2f
        const val GRAPH_RIGHT = 52f
        const val GRAPH_TOP = 55f
        const val GRAPH_BOTTOM = 95f

        // 속도 범위 (m/s) — 일반 러닝 속도
        const val MIN_SPEED_MPS = 1.5f   // ~10'/km (걷기)
        const val MAX_SPEED_MPS = 5.5f   // ~3'/km (빠른 러닝)

        // 그래프 요소 ID
        const val ID_BG = "sg_bg"
        const val ID_AXIS_BOTTOM = "sg_axis_bot"
        const val ID_AXIS_LEFT = "sg_axis_left"
        const val ID_PACE_LINE = "sg_pace"
        const val ID_PACE_LABEL = "sg_pace_lbl"
        const val ID_CURRENT = "sg_current"
        const val ID_LAP_PREFIX = "sg_lap_"
        const val ID_DISTANCE_LABEL = "sg_dist"

        // 최대 표시 이전 랩 수
        const val MAX_PREVIOUS_LAPS = 5

        // 랩 색상 (최근→오래됨 순서)
        val LAP_COLORS = listOf(
            "#FF4444",  // 빨강 (가장 최근 완료 랩)
            "#FF8844",  // 주황
            "#FFCC44",  // 노랑
            "#88CC44",  // 연두
            "#4488CC"   // 파랑 (가장 오래된 랩)
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var updateJob: Job? = null
    private var isVisible = false

    // 예상 랩 거리 (미터) — 첫 랩 완료 후 자동 보정
    private var estimatedLapDistanceM = 400f

    // 목표 페이스 (분/km) — 기본값, 사용자 설정 가능
    var targetPaceMinPerKm: Float = 6.0f

    /**
     * 그래프 표시 시작.
     *
     * @param trackAnchorService 속도 프로필 데이터 소스
     * @param routeTracker 현재 거리 데이터 소스
     */
    fun show(trackAnchorService: RunningTrackAnchorService, routeTracker: RunningRouteTracker) {
        if (isVisible) return
        isVisible = true

        // 배경 패널
        draw(DrawCommand.Add(DrawElement.Rect(
            id = ID_BG, x = GRAPH_LEFT, y = GRAPH_TOP,
            width = GRAPH_RIGHT - GRAPH_LEFT, height = GRAPH_BOTTOM - GRAPH_TOP,
            color = "#000000", opacity = 0.3f, filled = true
        )))

        // 하단 축선
        draw(DrawCommand.Add(DrawElement.Line(
            id = ID_AXIS_BOTTOM,
            x1 = GRAPH_LEFT, y1 = GRAPH_BOTTOM,
            x2 = GRAPH_RIGHT, y2 = GRAPH_BOTTOM,
            color = "#FFFFFF", opacity = 0.3f, strokeWidth = 1f
        )))

        // 좌측 축선
        draw(DrawCommand.Add(DrawElement.Line(
            id = ID_AXIS_LEFT,
            x1 = GRAPH_LEFT, y1 = GRAPH_TOP,
            x2 = GRAPH_LEFT, y2 = GRAPH_BOTTOM,
            color = "#FFFFFF", opacity = 0.3f, strokeWidth = 1f
        )))

        // 거리 라벨
        draw(DrawCommand.Add(DrawElement.Text(
            id = ID_DISTANCE_LABEL,
            x = GRAPH_LEFT + 1f, y = GRAPH_BOTTOM + 3f,
            text = "0m", size = 12f, color = "#888888"
        )))

        // 목표 페이스 라인
        drawTargetPaceLine()

        // 주기적 그래프 업데이트 (500ms)
        updateJob = scope.launch {
            while (isActive && isVisible) {
                try {
                    updateGraph(trackAnchorService, routeTracker)
                } catch (e: Exception) {
                    Log.w(TAG, "Graph update error: ${e.message}")
                }
                delay(500L)
            }
        }

        Log.i(TAG, "Speed graph overlay started")
    }

    /**
     * 그래프 숨기기.
     */
    fun hide() {
        isVisible = false
        updateJob?.cancel()

        // 모든 그래프 요소 제거
        val idsToRemove = mutableListOf(
            ID_BG, ID_AXIS_BOTTOM, ID_AXIS_LEFT, ID_PACE_LINE,
            ID_PACE_LABEL, ID_CURRENT, ID_DISTANCE_LABEL
        )
        for (i in 0 until MAX_PREVIOUS_LAPS) {
            idsToRemove.add("${ID_LAP_PREFIX}$i")
        }
        idsToRemove.forEach { draw(DrawCommand.Remove(it)) }

        Log.i(TAG, "Speed graph overlay hidden")
    }

    /**
     * 그래프 업데이트 — 현재 랩 + 이전 랩 Polyline 갱신.
     */
    private fun updateGraph(
        trackAnchorService: RunningTrackAnchorService,
        routeTracker: RunningRouteTracker
    ) {
        val completedProfiles = trackAnchorService.getCompletedLapProfiles()
        val currentProfile = trackAnchorService.getCurrentLapProfile()

        // 첫 랩 완료 후 랩 거리 보정
        if (completedProfiles.isNotEmpty()) {
            val lastProfile = completedProfiles.last()
            if (lastProfile.size >= 2) {
                val lapDist = lastProfile.last().distanceM - lastProfile.first().distanceM
                if (lapDist > 100f) {
                    estimatedLapDistanceM = estimatedLapDistanceM * 0.3f + lapDist * 0.7f
                }
            }
        }

        // 거리 라벨 업데이트
        draw(DrawCommand.Modify(ID_DISTANCE_LABEL, mapOf(
            "text" to "${estimatedLapDistanceM.toInt()}m"
        )))

        // 1. 이전 랩 Polyline 그리기 (오래된 순서로, 투명도 증가)
        val lapsToShow = completedProfiles.takeLast(MAX_PREVIOUS_LAPS)
        for (i in lapsToShow.indices) {
            val lapIndex = i
            val profile = lapsToShow[i]
            val points = profileToPoints(profile)
            if (points.size < 2) continue

            val opacity = 0.2f + 0.15f * ((lapsToShow.size - lapIndex).toFloat() / lapsToShow.size)
            val colorIndex = (lapsToShow.size - 1 - lapIndex).coerceIn(0, LAP_COLORS.size - 1)

            draw(DrawCommand.Remove("${ID_LAP_PREFIX}$lapIndex"))
            draw(DrawCommand.Add(DrawElement.Polyline(
                id = "${ID_LAP_PREFIX}$lapIndex",
                color = LAP_COLORS[colorIndex],
                opacity = opacity,
                points = points,
                strokeWidth = 2f
            )))
        }

        // 사용하지 않는 이전 랩 슬롯 제거
        for (i in lapsToShow.size until MAX_PREVIOUS_LAPS) {
            draw(DrawCommand.Remove("${ID_LAP_PREFIX}$i"))
        }

        // 2. 현재 랩 Polyline 그리기 (가장 밝게)
        if (currentProfile.size >= 2) {
            val points = profileToPoints(currentProfile)
            draw(DrawCommand.Remove(ID_CURRENT))
            draw(DrawCommand.Add(DrawElement.Polyline(
                id = ID_CURRENT,
                color = "#00FF00",  // 밝은 녹색
                opacity = 1.0f,
                points = points,
                strokeWidth = 3f
            )))
        }
    }

    /**
     * 속도 프로필 → 그래프 포인트 변환.
     *
     * X축: 랩 내 거리 진행률 → 그래프 X좌표
     * Y축: 속도 → 그래프 Y좌표 (빠를수록 위)
     */
    private fun profileToPoints(
        profile: List<RunningTrackAnchorService.SpeedSample>
    ): List<Pair<Float, Float>> {
        if (profile.isEmpty()) return emptyList()

        val startDist = profile.first().distanceM
        val graphWidth = GRAPH_RIGHT - GRAPH_LEFT
        val graphHeight = GRAPH_BOTTOM - GRAPH_TOP

        return profile.map { sample ->
            // X: 거리 진행률 (0 ~ estimatedLapDistanceM)
            val distProgress = ((sample.distanceM - startDist) / estimatedLapDistanceM)
                .coerceIn(0f, 1f)
            val x = GRAPH_LEFT + distProgress * graphWidth

            // Y: 속도 → 그래프 좌표 (빠를수록 위 = GRAPH_TOP)
            val speedNorm = ((sample.speedMps - MIN_SPEED_MPS) / (MAX_SPEED_MPS - MIN_SPEED_MPS))
                .coerceIn(0f, 1f)
            val y = GRAPH_BOTTOM - speedNorm * graphHeight

            Pair(x, y)
        }
    }

    /**
     * 목표 페이스 수평 기준선 그리기.
     */
    private fun drawTargetPaceLine() {
        // 목표 페이스 → 속도 변환 (분/km → m/s)
        val targetSpeedMps = if (targetPaceMinPerKm > 0) {
            1000f / (targetPaceMinPerKm * 60f)
        } else return

        val speedNorm = ((targetSpeedMps - MIN_SPEED_MPS) / (MAX_SPEED_MPS - MIN_SPEED_MPS))
            .coerceIn(0f, 1f)
        val graphHeight = GRAPH_BOTTOM - GRAPH_TOP
        val paceY = GRAPH_BOTTOM - speedNorm * graphHeight

        draw(DrawCommand.Remove(ID_PACE_LINE))
        draw(DrawCommand.Add(DrawElement.Line(
            id = ID_PACE_LINE,
            x1 = GRAPH_LEFT, y1 = paceY,
            x2 = GRAPH_RIGHT, y2 = paceY,
            color = "#FF6600", opacity = 0.5f, strokeWidth = 1f
        )))

        val paceMin = targetPaceMinPerKm.toInt()
        val paceSec = ((targetPaceMinPerKm - paceMin) * 60).toInt()
        draw(DrawCommand.Remove(ID_PACE_LABEL))
        draw(DrawCommand.Add(DrawElement.Text(
            id = ID_PACE_LABEL,
            x = GRAPH_RIGHT - 10f, y = paceY - 2f,
            text = "${paceMin}'${String.format("%02d", paceSec)}\"",
            size = 12f, color = "#FF6600"
        )))
    }

    /**
     * 목표 페이스 변경.
     */
    fun setTargetPace(paceMinPerKm: Float) {
        targetPaceMinPerKm = paceMinPerKm
        if (isVisible) {
            drawTargetPaceLine()
        }
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
