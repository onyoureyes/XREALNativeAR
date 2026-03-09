package com.xreal.nativear.running

import android.util.Log
import com.xreal.nativear.DrawCommand
import com.xreal.nativear.DrawElement
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.policy.PolicyReader
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.spatial.AnchorType
import com.xreal.nativear.spatial.DepthSource
import com.xreal.nativear.spatial.PoseTransform
import com.xreal.nativear.spatial.SpatialAnchor
import com.xreal.nativear.spatial.SpatialAnchorManager
import kotlinx.coroutines.*

/**
 * PacemakerService -- 목표 페이스 기반 가상 페이스메이커 표시.
 *
 * ## 핵심 개념
 * 사용자가 설정한 목표 페이스(예: 5'30"/km)로 달리는 **가상의 러너**를 나타내는
 * 공간 앵커를 생성. 이 앵커는:
 * - 사용자보다 **빠르면** 전방에 위치 (따라가야 함)
 * - 사용자보다 **느리면** 후방에 위치 (앞서고 있음)
 * - **동일 페이스**면 바로 옆에 위치
 *
 * ## 거리 차이 계산
 * ```
 * 목표 거리 = 경과시간 × 목표속도(m/s)
 * 실제 거리 = routeTracker.totalDistanceMeters
 * 차이(m) = 목표 거리 - 실제 거리
 *   양수 → 페이스메이커가 앞에 있음 (사용자가 느림)
 *   음수 → 페이스메이커가 뒤에 있음 (사용자가 빠름)
 * ```
 *
 * ## VIO 앵커 배치
 * 거리 차이를 카메라 전방(+)/후방(-) 오프셋으로 변환하여 앵커 배치.
 * 단, 최대 30m 범위로 제한 (너무 멀면 보이지 않음).
 *
 * ## HUD 텍스트 보조
 * 공간 앵커 외에 HUD 텍스트로도 차이를 표시:
 * "▶ +12m (빠름)" 또는 "◀ -8m (느림)"
 *
 * @param eventBus 전역 이벤트 버스
 * @param spatialAnchorManager 공간 앵커 관리자
 */
class PacemakerService(
    private val eventBus: GlobalEventBus,
    private val spatialAnchorManager: SpatialAnchorManager
) {
    companion object {
        private const val TAG = "Pacemaker"

        /** 페이스메이커 앵커 ID */
        const val PACER_ANCHOR_ID = "pacemaker_dot"

        /** HUD 차이 텍스트 ID */
        const val ID_PACER_HUD = "pm_diff"

        /** HUD 목표 페이스 텍스트 ID */
        const val ID_PACER_TARGET = "pm_target"

        /** 앵커 최대 전방 거리 (미터) — 너무 멀면 안 보임 */
        val MAX_AHEAD_M: Float get() = PolicyReader.getFloat("running.pacemaker_max_ahead_m", 30f)

        /** 앵커 최대 후방 거리 (미터, 음수) */
        val MAX_BEHIND_M: Float get() = PolicyReader.getFloat("running.pacemaker_max_behind_m", -15f)

        /** 업데이트 간격 (ms) */
        val UPDATE_INTERVAL_MS: Long get() = PolicyReader.getLong("running.pacemaker_update_interval_ms", 1000L)

        /** 기본 목표 페이스 (분/km) */
        val DEFAULT_TARGET_PACE: Float get() = PolicyReader.getFloat("running.default_target_pace", 6.0f)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var updateJob: Job? = null
    private var isActive = false

    /** 목표 페이스 (분/km) */
    var targetPaceMinPerKm: Float = DEFAULT_TARGET_PACE
        set(value) {
            field = value
            targetSpeedMps = if (value > 0) 1000f / (value * 60f) else 0f
            // HUD 목표 표시 갱신
            updateTargetLabel()
        }

    /** 목표 속도 (m/s) — 페이스에서 자동 계산 */
    private var targetSpeedMps: Float = 1000f / (DEFAULT_TARGET_PACE * 60f)

    /** 세션 시작 시간 */
    private var sessionStartTimeMs = 0L

    /**
     * 페이스메이커 시작.
     */
    fun start(routeTracker: RunningRouteTracker, session: RunningSession) {
        if (isActive) return
        isActive = true
        sessionStartTimeMs = System.currentTimeMillis()

        // 목표 페이스 HUD 표시
        updateTargetLabel()

        // 주기적 업데이트
        updateJob = scope.launch {
            while (isActive && isActive) {
                try {
                    updatePacemaker(routeTracker, session)
                } catch (e: Exception) {
                    Log.w(TAG, "Pacemaker update error: ${e.message}")
                }
                delay(UPDATE_INTERVAL_MS)
            }
        }

        Log.i(TAG, "Pacemaker started (target: ${targetPaceMinPerKm}'/km = ${String.format("%.2f", targetSpeedMps)} m/s)")
    }

    /**
     * 페이스메이커 중단.
     */
    fun stop() {
        isActive = false
        updateJob?.cancel()

        // HUD 요소 제거
        scope.launch {
            eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(DrawCommand.Remove(ID_PACER_HUD)))
            eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(DrawCommand.Remove(ID_PACER_TARGET)))
        }

        Log.i(TAG, "Pacemaker stopped")
    }

    /**
     * 페이스메이커 위치 및 HUD 업데이트.
     */
    private fun updatePacemaker(routeTracker: RunningRouteTracker, session: RunningSession) {
        if (session.state != com.xreal.nativear.core.RunningState.ACTIVE) return

        val elapsedMs = session.elapsedMs
        if (elapsedMs <= 0) return

        // 목표 거리 계산
        val elapsedSec = elapsedMs / 1000f
        val targetDistanceM = targetSpeedMps * elapsedSec

        // 실제 거리
        val actualDistanceM = routeTracker.totalDistanceMeters

        // 차이 (양수 = 페이스메이커가 앞, 음수 = 사용자가 앞)
        val diffM = targetDistanceM - actualDistanceM

        // 1. HUD 텍스트 업데이트
        updateDiffHud(diffM)

        // 2. 공간 앵커 업데이트
        updatePacerAnchor(diffM)
    }

    /**
     * 거리 차이 HUD 텍스트 표시.
     */
    private fun updateDiffHud(diffM: Float) {
        val absDiff = Math.abs(diffM)
        val (text, color) = when {
            diffM > 5f -> {
                // 페이스메이커가 앞에 (사용자 느림)
                val arrow = "\u25B6"  // ▶
                Pair("$arrow +${absDiff.toInt()}m", "#FF4444")  // 빨강
            }
            diffM < -5f -> {
                // 사용자가 앞에 (사용자 빠름)
                val arrow = "\u25C0"  // ◀
                Pair("$arrow -${absDiff.toInt()}m", "#00FF00")  // 초록
            }
            else -> {
                // 비슷한 페이스
                Pair("\u2713 ON PACE", "#FFFFFF")  // ✓
            }
        }

        scope.launch {
            eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(
                DrawCommand.Add(DrawElement.Text(
                    id = ID_PACER_HUD,
                    x = 2f, y = 50f,
                    text = text,
                    size = 18f, bold = true, color = color
                ))
            ))
        }
    }

    /**
     * 페이스메이커 공간 앵커 업데이트.
     *
     * 거리 차이를 카메라 전방/후방 오프셋으로 변환.
     * - diffM > 0: 전방 (사용자보다 앞서 있음)
     * - diffM < 0: 후방 (사용자보다 뒤처져 있음 — 카메라 뒤, 안 보임)
     */
    private fun updatePacerAnchor(diffM: Float) {
        // 범위 제한
        val clampedDiff = diffM.coerceIn(MAX_BEHIND_M, MAX_AHEAD_M)

        // 후방은 앵커 안 보이므로 삭제
        if (clampedDiff < 0.5f) {
            // 사용자가 앞서가거나 거의 같은 속도 — 전방 최소 거리에 표시
            val showDist = 2f.coerceAtLeast(clampedDiff + 3f)  // 최소 2m 전방
            placeAnchorAhead(showDist, diffM)
            return
        }

        // 전방에 앵커 배치 (차이만큼 앞에, 최소 3m)
        val aheadDistance = clampedDiff.coerceAtLeast(3f)
        placeAnchorAhead(aheadDistance, diffM)
    }

    /**
     * 카메라 전방에 페이스메이커 앵커 배치.
     */
    private fun placeAnchorAhead(distanceAhead: Float, diffM: Float) {
        val pose = spatialAnchorManager.getCurrentPose()
        if (pose == null || !pose.is6DoF) return

        val poseMatrix = PoseTransform.poseToMatrix(pose)
        // 카메라 전방 -Z, 약간 위에 (+Y 0.5m) 배치
        val cameraPos = floatArrayOf(0f, 0.5f, -distanceAhead)
        val worldPos = PoseTransform.cameraToWorld(cameraPos, poseMatrix)

        // 라벨: 차이 표시
        val label = when {
            diffM > 5f -> "\uD83C\uDFC3 +${diffM.toInt()}m"    // 🏃 +Xm
            diffM < -5f -> "\u2713 -${Math.abs(diffM).toInt()}m" // ✓ -Xm
            else -> "\uD83C\uDFC3 PACE"                          // 🏃 PACE
        }

        val anchor = SpatialAnchor(
            id = PACER_ANCHOR_ID,
            label = label,
            type = AnchorType.PROGRAMMATIC,
            worldX = worldPos[0],
            worldY = worldPos[1],
            worldZ = worldPos[2],
            confidence = 0.9f,
            createdAt = System.currentTimeMillis(),
            lastSeenAt = System.currentTimeMillis(),
            seenCount = 10,  // 만료 방지
            depthMeters = distanceAhead,
            depthSource = DepthSource.CATEGORY_PRIOR,
            metadata = mapOf("source" to "pacemaker", "diff_m" to diffM)
        )

        // 기존 앵커 교체
        spatialAnchorManager.addProgrammaticAnchor(anchor, mergeExisting = false)
    }

    /**
     * 목표 페이스 HUD 라벨 갱신.
     */
    private fun updateTargetLabel() {
        val paceMin = targetPaceMinPerKm.toInt()
        val paceSec = ((targetPaceMinPerKm - paceMin) * 60).toInt()
        scope.launch {
            eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(
                DrawCommand.Add(DrawElement.Text(
                    id = ID_PACER_TARGET,
                    x = 2f, y = 46f,
                    text = "TARGET ${paceMin}'${String.format("%02d", paceSec)}\"/km",
                    size = 14f, color = "#FF6600"
                ))
            ))
        }
    }

    fun release() {
        stop()
        scope.cancel()
    }
}
