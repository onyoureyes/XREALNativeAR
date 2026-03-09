package com.xreal.nativear.running

import android.util.Log
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.policy.PolicyReader
import com.xreal.nativear.core.PositionMode
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.spatial.AnchorType
import com.xreal.nativear.spatial.DepthSource
import com.xreal.nativear.spatial.PoseTransform
import com.xreal.nativear.spatial.SpatialAnchor
import com.xreal.nativear.spatial.SpatialAnchorManager
import kotlinx.coroutines.*

/**
 * RunningTrackAnchorService -- GPS 기반 자동 랩 감지 + 공간 앵커 생성.
 *
 * ## 핵심 기능
 * 1. **시작점 기록**: 러닝 시작 시 GPS 좌표를 "홈 포인트"로 저장
 * 2. **자동 랩 감지**: 시작점에서 멀어졌다가 다시 접근(< PROXIMITY_THRESHOLD_M) → 랩 자동 기록
 * 3. **공간 앵커 생성**: 시작점과 랩 완료 지점에 세계 고정 앵커 배치
 *    - 시작점 앵커: 현재 랩 수 + 누적 시간 표시
 *    - 랩 완료 앵커: 랩 번호 + 페이스 표시
 * 4. **속도 프로필 기록**: 각 랩의 RoutePoint별 속도 저장 (Phase 2 그래프용)
 *
 * ## 자동 랩 감지 로직
 * ```
 * 시작 → 멀어짐 (> DEPART_THRESHOLD_M) → departedFromHome = true
 *       → 다시 접근 (< PROXIMITY_THRESHOLD_M) → 랩 기록 + 앵커 갱신
 *       → departedFromHome 리셋 → 반복
 * ```
 *
 * ## 좌표 변환
 * GPS 앵커는 VIO 월드 좌표가 아닌 GPS 좌표(lat/lon)로 추적.
 * VIO 좌표 앵커 생성 시 현재 카메라 포즈 기준으로 방향벡터를 사용하여
 * 사용자 전방 2m 지점에 앵커를 배치 (정확한 GPS→VIO 매핑이 불가하므로).
 *
 * @param eventBus 전역 이벤트 버스
 * @param spatialAnchorManager 공간 앵커 관리자
 * @param session 러닝 세션 (랩 기록 위임)
 */
class RunningTrackAnchorService(
    private val eventBus: GlobalEventBus,
    private val spatialAnchorManager: SpatialAnchorManager,
    private val session: RunningSession
) {
    companion object {
        private const val TAG = "TrackAnchor"

        /** 시작점 복귀 판정 거리 (미터) — 표준 400m 트랙 기준 */
        val PROXIMITY_THRESHOLD_M: Double get() = PolicyReader.getFloat("running.lap_proximity_m", 20.0f).toDouble()

        /** 시작점에서 떠난 것으로 간주하는 최소 거리 (미터) */
        val DEPART_THRESHOLD_M: Double get() = PolicyReader.getFloat("running.lap_depart_m", 50.0f).toDouble()

        /** 랩 간 최소 시간 (밀리초) — 너무 짧은 랩 방지 (60초) */
        val MIN_LAP_DURATION_MS: Long get() = PolicyReader.getLong("running.min_lap_duration_ms", 60_000L)

        /** 랩 간 최소 거리 (미터) — 200m 미만 랩은 무시 */
        val MIN_LAP_DISTANCE_M: Float get() = PolicyReader.getFloat("running.min_lap_distance_m", 200f)

        /** 시작 앵커 ID 프리픽스 */
        const val HOME_ANCHOR_ID = "track_home"

        /** 랩 앵커 ID 프리픽스 */
        const val LAP_ANCHOR_PREFIX = "track_lap_"

        /** 앵커 만료 시간 — 러닝 중에는 만료되지 않도록 충분히 길게 (2시간) */
        const val ANCHOR_EXPIRY_S = 7200L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── 시작점 GPS ──
    private var homeLat = 0.0
    private var homeLon = 0.0
    private var homeSet = false

    // ── 출발 상태 추적 ──
    private var departedFromHome = false
    private var lastLapTimeMs = 0L
    private var lastLapDistance = 0f

    // ── 현재 위치 캐시 ──
    @Volatile private var currentLat = 0.0
    @Volatile private var currentLon = 0.0
    @Volatile private var currentSpeed = 0f
    @Volatile private var currentDistanceM = 0f

    // ── 랩별 속도 프로필 (Phase 2 그래프용) ──
    data class SpeedSample(val distanceM: Float, val speedMps: Float, val timestampMs: Long)
    private val currentLapSpeedProfile = mutableListOf<SpeedSample>()
    private val completedLapProfiles = mutableListOf<List<SpeedSample>>()

    // ── 구독 Job ──
    private var trackingJob: Job? = null
    private var isActive = false

    /**
     * 러닝 시작 시 호출 — 위치 이벤트 구독 시작.
     */
    fun start() {
        if (isActive) return
        isActive = true
        homeSet = false
        departedFromHome = false
        lastLapTimeMs = System.currentTimeMillis()
        lastLapDistance = 0f
        currentLapSpeedProfile.clear()
        completedLapProfiles.clear()

        trackingJob = scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is XRealEvent.PerceptionEvent.FusedPositionUpdate -> {
                        onPositionUpdate(event)
                    }
                    is XRealEvent.PerceptionEvent.RunningRouteUpdate -> {
                        currentDistanceM = event.distanceMeters
                        currentSpeed = event.currentSpeedMps

                        // 속도 프로필 기록 (10m 간격으로 샘플링)
                        val lastSample = currentLapSpeedProfile.lastOrNull()
                        if (lastSample == null || (event.distanceMeters - lastSample.distanceM) >= 10f) {
                            currentLapSpeedProfile.add(SpeedSample(
                                distanceM = event.distanceMeters,
                                speedMps = event.currentSpeedMps,
                                timestampMs = event.timestamp
                            ))
                        }
                    }
                    else -> {}
                }
            }
        }

        Log.i(TAG, "Track anchor service started (proximity=${PROXIMITY_THRESHOLD_M}m, depart=${DEPART_THRESHOLD_M}m)")
    }

    /**
     * 러닝 종료 시 호출 — 구독 중단 + 앵커 정리.
     */
    fun stop() {
        isActive = false
        trackingJob?.cancel()

        // 마지막 랩 프로필 저장
        if (currentLapSpeedProfile.isNotEmpty()) {
            completedLapProfiles.add(currentLapSpeedProfile.toList())
        }

        Log.i(TAG, "Track anchor service stopped (${completedLapProfiles.size} lap profiles recorded)")
    }

    /**
     * 완료된 모든 랩의 속도 프로필 반환 (Phase 2 그래프용).
     */
    fun getCompletedLapProfiles(): List<List<SpeedSample>> = completedLapProfiles.toList()

    /**
     * 현재 진행 중인 랩의 속도 프로필 반환.
     */
    fun getCurrentLapProfile(): List<SpeedSample> = currentLapSpeedProfile.toList()

    /**
     * 시작점 GPS 좌표 반환 (Phase 3 페이스메이커용).
     */
    fun getHomePosition(): Pair<Double, Double>? {
        return if (homeSet) Pair(homeLat, homeLon) else null
    }

    // ── 위치 업데이트 처리 ──

    private fun onPositionUpdate(event: XRealEvent.PerceptionEvent.FusedPositionUpdate) {
        currentLat = event.latitude
        currentLon = event.longitude
        currentSpeed = event.speed

        // 1. 시작점 설정 (첫 번째 유효 위치)
        if (!homeSet) {
            if (event.mode == PositionMode.GPS_GOOD || event.mode == PositionMode.GPS_DEGRADED) {
                setHomePoint(event.latitude, event.longitude)
            }
            return
        }

        // 2. 시작점으로부터 거리 계산
        val distFromHome = RunningRouteTracker.flatDistance(
            homeLat, homeLon, event.latitude, event.longitude
        )

        // 3. 출발 감지
        if (!departedFromHome && distFromHome > DEPART_THRESHOLD_M) {
            departedFromHome = true
            Log.d(TAG, "Departed from home (${String.format("%.1f", distFromHome)}m)")
        }

        // 4. 복귀 감지 → 자동 랩
        if (departedFromHome && distFromHome < PROXIMITY_THRESHOLD_M) {
            val now = System.currentTimeMillis()
            val timeSinceLap = now - lastLapTimeMs
            val distSinceLap = currentDistanceM - lastLapDistance

            // 최소 시간/거리 검증
            if (timeSinceLap >= MIN_LAP_DURATION_MS && distSinceLap >= MIN_LAP_DISTANCE_M) {
                onLapDetected(event.latitude, event.longitude, now)
            }
        }
    }

    /**
     * 시작점 설정 + 홈 앵커 생성.
     */
    private fun setHomePoint(lat: Double, lon: Double) {
        homeLat = lat
        homeLon = lon
        homeSet = true
        lastLapTimeMs = System.currentTimeMillis()
        lastLapDistance = 0f

        // 홈 앵커 생성 (사용자 전방 2m 지점)
        createAnchorAtCurrentPose(
            id = HOME_ANCHOR_ID,
            label = "START",
            distanceAhead = 2.0f
        )

        Log.i(TAG, "Home point set: ($lat, $lon)")
    }

    /**
     * 랩 감지 → 세션 랩 기록 + 앵커 생성/갱신.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun onLapDetected(lat: Double, lon: Double, now: Long) {
        // 1. 세션에 랩 기록 (RunningSession.lap())
        session.lap()
        val lapData = session.laps.lastOrNull() ?: return

        Log.i(TAG, "Auto-lap detected: Lap ${lapData.number}, " +
                "pace=${String.format("%.1f", lapData.avgPace)}'/km, " +
                "dist=${String.format("%.0f", lapData.distanceMeters)}m")

        // 2. 출발 상태 리셋
        departedFromHome = false
        lastLapTimeMs = now
        lastLapDistance = currentDistanceM

        // 3. 현재 랩 속도 프로필 저장 + 새 랩 시작
        if (currentLapSpeedProfile.isNotEmpty()) {
            completedLapProfiles.add(currentLapSpeedProfile.toList())
        }
        currentLapSpeedProfile.clear()

        // 4. 랩 완료 앵커 생성 (현재 위치)
        val paceMin = lapData.avgPace.toInt()
        val paceSec = ((lapData.avgPace - paceMin) * 60).toInt()
        val lapLabel = "L${lapData.number} ${paceMin}'${String.format("%02d", paceSec)}\""

        createAnchorAtCurrentPose(
            id = "${LAP_ANCHOR_PREFIX}${lapData.number}",
            label = lapLabel,
            distanceAhead = 3.0f
        )

        // 5. 홈 앵커 업데이트 (누적 랩 수 표시)
        val totalLaps = session.laps.size
        val elapsed = session.getElapsedSeconds()
        val elapsedMin = elapsed / 60
        val elapsedSec = elapsed % 60
        val homeLabel = "LAP $totalLaps | ${elapsedMin}:${String.format("%02d", elapsedSec)}"
        spatialAnchorManager.updateAnchorLabel(HOME_ANCHOR_ID, homeLabel)

        // 6. HUD에 랩 알림 발행
        scope.launch {
            eventBus.publish(XRealEvent.SystemEvent.DebugLog(
                message = "[TrackAnchor] Auto-lap ${lapData.number}: ${paceMin}'${String.format("%02d", paceSec)}\"/km"
            ))
        }
    }

    /**
     * 현재 VIO 포즈 기준으로 사용자 전방에 앵커 생성.
     *
     * GPS 좌표를 VIO 월드 좌표로 직접 변환할 수 없으므로,
     * 현재 카메라 포즈에서 전방 [distanceAhead]m 지점에 앵커를 배치.
     * VIO 기반 재투영으로 화면에 안정적으로 표시됨.
     */
    private fun createAnchorAtCurrentPose(id: String, label: String, distanceAhead: Float) {
        val pose = spatialAnchorManager.getCurrentPose()
        if (pose == null || !pose.is6DoF) {
            Log.w(TAG, "Cannot create anchor: no 6-DoF pose available")
            // 6-DoF 없이 대기, 다음 포즈에서 재시도하지는 않음 (트랙 환경은 대부분 야외)
            return
        }

        // 카메라 전방 벡터 계산 (카메라 좌표계 -Z 방향)
        val poseMatrix = PoseTransform.poseToMatrix(pose)
        val cameraForward = floatArrayOf(0f, 0f, -distanceAhead) // 카메라 좌표계: -Z = 전방
        val worldPos = PoseTransform.cameraToWorld(cameraForward, poseMatrix)

        val anchor = SpatialAnchor(
            id = id,
            label = label,
            type = AnchorType.PROGRAMMATIC,
            worldX = worldPos[0],
            worldY = worldPos[1],
            worldZ = worldPos[2],
            confidence = 1.0f,
            createdAt = System.currentTimeMillis(),
            lastSeenAt = System.currentTimeMillis(),
            seenCount = 10,  // 확정 앵커로 시작 (만료 방지)
            depthMeters = distanceAhead,
            depthSource = DepthSource.CATEGORY_PRIOR,
            metadata = mapOf("source" to "track_anchor", "gps_lat" to currentLat, "gps_lon" to currentLon)
        )

        spatialAnchorManager.addProgrammaticAnchor(anchor, mergeExisting = false)
        Log.d(TAG, "Anchor created: $label at world(${worldPos[0]}, ${worldPos[1]}, ${worldPos[2]})")
    }

    /**
     * 디버그 통계 문자열 반환.
     */
    fun getStats(): String {
        val distFromHome = if (homeSet) {
            RunningRouteTracker.flatDistance(homeLat, homeLon, currentLat, currentLon)
        } else 0.0
        return "TrackAnchor: home=${if (homeSet) "set" else "unset"}, " +
                "departed=$departedFromHome, " +
                "distFromHome=${String.format("%.1f", distFromHome)}m, " +
                "laps=${session.laps.size}, " +
                "profiles=${completedLapProfiles.size}"
    }

    fun release() {
        stop()
        scope.cancel()
    }
}
