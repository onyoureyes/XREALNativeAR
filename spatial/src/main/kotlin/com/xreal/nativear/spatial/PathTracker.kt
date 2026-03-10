package com.xreal.nativear.spatial

import com.xreal.nativear.core.XRealLogger
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * PathTracker — GPS 끊긴 후 VIO 이동 경로 추적.
 *
 * ## 역할
 * 마지막 유효 GPS 지점부터 VIO(6-DoF) 포즈 변위를 누적 기록하여
 * 실내 이동 경로를 시그니처로 변환. GPS가 없는 실내 공간에서
 * "입구에서 계단→3층→복도 30m" 같은 경로 패턴을 식별.
 *
 * ## 데이터 흐름
 * ```
 * PhoneGps → gpsAnchor 갱신 (GPS 유효 시 경로 리셋)
 * HeadPoseUpdated (6-DoF) → gpsAnchor 대비 변위 누적 기록
 * ```
 *
 * ## 이동 경로 요약 (PathSummary)
 * ```json
 * {
 *   "segments": [
 *     {"dx": 5.0, "dy": 0.0, "dz": 10.0, "dist": 11.2},
 *     {"dx": 0.0, "dy": 3.0, "dz": 0.0, "dist": 3.0},
 *     {"dx": 15.0, "dy": 0.0, "dz": 5.0, "dist": 15.8}
 *   ],
 *   "totalDistance": 30.0,
 *   "netDisplacement": 18.5,
 *   "floors": 1,
 *   "heading": 45.0
 * }
 * ```
 *
 * @param eventBus 이벤트 버스 (PhoneGps, HeadPoseUpdated 구독)
 * @param log 로깅 콜백
 *
 * @see PlaceSignature.pathSummaryJson
 * @see PlaceSignature.pathDistanceFromGps
 */
class PathTracker(
    private val eventBus: GlobalEventBus,
    private val log: (String) -> Unit
) {
    companion object {
        private const val TAG = "PathTracker"

        /** GPS 유효 판정 정확도 (미터). 이하면 GPS 유효로 간주 */
        const val GPS_VALID_ACCURACY_M = 30f

        /** 경로 세그먼트 기록 최소 이동 거리 (미터) */
        const val SEGMENT_MIN_DISTANCE_M = 2.0f

        /** 최대 경로 세그먼트 수 (메모리 제한) */
        const val MAX_SEGMENTS = 50

        /** 층 높이 (미터). 추정 층수 = Y 변위 / FLOOR_HEIGHT */
        const val FLOOR_HEIGHT_M = 3.0f

        /** 경로 비교 허용 오차 (비율). 0.2 = 20% 차이까지 허용 */
        const val PATH_SIMILARITY_TOLERANCE = 0.25f
    }

    // ── 상태 ──

    /** 마지막 유효 GPS 시점의 VIO 포즈 (경로의 원점) */
    @Volatile
    private var gpsAnchorPose: PoseState? = null

    /** 마지막 유효 GPS 좌표 */
    @Volatile
    var lastGpsLatitude: Double? = null
        private set
    @Volatile
    var lastGpsLongitude: Double? = null
        private set
    @Volatile
    var lastGpsAltitude: Double = 0.0
        private set
    @Volatile
    var lastGpsAccuracy: Float = 999f
        private set

    /** 현재 VIO 포즈 (GPS 앵커 대비 변위 계산용) */
    @Volatile
    private var currentPose: PoseState? = null

    /** 경로 세그먼트 (GPS 끊긴 후 이동 구간) */
    private val segments = mutableListOf<PathSegment>()

    /** 마지막 세그먼트 시작 시 VIO 위치 */
    private var lastSegmentPos = floatArrayOf(0f, 0f, 0f)

    /** GPS 끊긴 후 누적 이동 거리 (미터) */
    @Volatile
    var totalPathDistance: Float = 0f
        private set

    /** GPS가 실내에서 끊겼는지 여부 */
    @Volatile
    var isIndoor: Boolean = false
        private set

    /** VIO 시작점(앱 시작 시) 기준 Y 변위 → 층수 추정 */
    @Volatile
    private var vioStartY: Float? = null

    // ── 코루틴 ──
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var eventJob: Job? = null

    // ── Public API ──

    /** EventBus 구독 시작 */
    fun start() {
        eventJob = scope.launch {
            launch {
                eventBus.events.filterIsInstance<XRealEvent.PerceptionEvent.PhoneGps>()
                    .collect { onGpsUpdate(it) }
            }
            launch {
                eventBus.events.filterIsInstance<XRealEvent.PerceptionEvent.HeadPoseUpdated>()
                    .collect { onPoseUpdate(it) }
            }
        }
        log("PathTracker started")
    }

    /** 정지 */
    fun stop() {
        eventJob?.cancel()
        log("PathTracker stopped")
    }

    /**
     * 현재 이동 경로 상태 조회.
     *
     * @return GPS 끊긴 후 총 이동 거리, VIO Y 변위, 추정 층수
     */
    fun getCurrentPathState(): PathState {
        val pose = currentPose
        val anchor = gpsAnchorPose
        val yDisplacement = if (pose != null && anchor != null) {
            pose.y - anchor.y
        } else 0f
        val floor = (yDisplacement / FLOOR_HEIGHT_M).roundToInt()

        return PathState(
            totalDistance = totalPathDistance,
            vioYDisplacement = yDisplacement,
            estimatedFloor = floor,
            isIndoor = isIndoor,
            segmentCount = segments.size
        )
    }

    /**
     * VIO 시작점 대비 현재 절대 Y 변위 (층수 추정용).
     */
    fun getAbsoluteYDisplacement(): Float {
        val startY = vioStartY ?: return 0f
        val pose = currentPose ?: return 0f
        return pose.y - startY
    }

    /**
     * 현재 heading (방위각) 추출.
     * VIO 쿼터니언에서 yaw 성분 추출 (0-360°, 상대 방위).
     * 절대 방위가 필요하면 GPS heading으로 초기 보정 필요.
     */
    fun getCurrentHeading(): Float {
        val pose = currentPose ?: return 0f
        return quaternionToYaw(pose.qx, pose.qy, pose.qz, pose.qw)
    }

    /**
     * 경로 요약 JSON 생성 (PlaceSignature.pathSummaryJson용).
     */
    fun buildPathSummaryJson(): String? {
        if (segments.isEmpty()) return null

        val segArray = JSONArray()
        for (seg in segments) {
            segArray.put(JSONObject().apply {
                put("dx", "%.1f".format(seg.dx))
                put("dy", "%.1f".format(seg.dy))
                put("dz", "%.1f".format(seg.dz))
                put("dist", "%.1f".format(seg.distance))
            })
        }

        val pose = currentPose
        val anchor = gpsAnchorPose
        val netX = if (pose != null && anchor != null) pose.x - anchor.x else 0f
        val netY = if (pose != null && anchor != null) pose.y - anchor.y else 0f
        val netZ = if (pose != null && anchor != null) pose.z - anchor.z else 0f
        val netDisplacement = sqrt(netX * netX + netY * netY + netZ * netZ)

        return JSONObject().apply {
            put("segments", segArray)
            put("totalDistance", "%.1f".format(totalPathDistance))
            put("netDisplacement", "%.1f".format(netDisplacement))
            put("floors", getCurrentPathState().estimatedFloor)
            put("heading", "%.0f".format(getCurrentHeading()))
        }.toString()
    }

    /**
     * 두 경로 요약의 유사도 계산 (0-1).
     *
     * 총 이동 거리 + 순 변위 + 층수 비교.
     */
    fun comparePathSummaries(json1: String?, json2: String?): Float {
        if (json1 == null || json2 == null) return 0.5f // 경로 정보 없으면 중립

        return try {
            val obj1 = JSONObject(json1)
            val obj2 = JSONObject(json2)

            val dist1 = obj1.optDouble("totalDistance", 0.0).toFloat()
            val dist2 = obj2.optDouble("totalDistance", 0.0).toFloat()
            val net1 = obj1.optDouble("netDisplacement", 0.0).toFloat()
            val net2 = obj2.optDouble("netDisplacement", 0.0).toFloat()
            val floor1 = obj1.optInt("floors", 0)
            val floor2 = obj2.optInt("floors", 0)

            // 총 거리 유사도 (20% 오차 허용)
            val distScore = if (dist1 + dist2 < 1f) 1f else {
                val ratio = abs(dist1 - dist2) / maxOf(dist1, dist2, 1f)
                (1f - ratio / PATH_SIMILARITY_TOLERANCE).coerceIn(0f, 1f)
            }

            // 순 변위 유사도
            val netScore = if (net1 + net2 < 1f) 1f else {
                val ratio = abs(net1 - net2) / maxOf(net1, net2, 1f)
                (1f - ratio / PATH_SIMILARITY_TOLERANCE).coerceIn(0f, 1f)
            }

            // 층수 일치 (완전 일치=1, 1층 차이=0.5, 2+층 차이=0)
            val floorScore = when (abs(floor1 - floor2)) {
                0 -> 1.0f
                1 -> 0.5f
                else -> 0.0f
            }

            // 가중 합산
            (distScore * 0.4f + netScore * 0.3f + floorScore * 0.3f).coerceIn(0f, 1f)
        } catch (_: Exception) {
            0.5f
        }
    }

    /** 경로 리셋 (GPS 유효해지면 호출) */
    fun resetPath() {
        segments.clear()
        totalPathDistance = 0f
        lastSegmentPos = floatArrayOf(0f, 0f, 0f)
        isIndoor = false
    }

    // ── 이벤트 핸들러 ──

    private fun onGpsUpdate(event: XRealEvent.PerceptionEvent.PhoneGps) {
        lastGpsLatitude = event.latitude
        lastGpsLongitude = event.longitude
        lastGpsAltitude = event.altitude
        lastGpsAccuracy = event.accuracy

        if (event.accuracy <= GPS_VALID_ACCURACY_M) {
            // GPS 유효 → 경로 원점 갱신
            val pose = currentPose
            if (pose != null) {
                gpsAnchorPose = pose
                lastSegmentPos = floatArrayOf(pose.x, pose.y, pose.z)
            }

            if (isIndoor && segments.isNotEmpty()) {
                log("GPS recovered (accuracy=${event.accuracy}m). Path: ${segments.size} segments, ${totalPathDistance}m")
            }

            // GPS 유효하면 실내가 아님 → 경로 리셋
            resetPath()
        } else {
            // GPS 정확도 저하 → 실내 진입 추정
            if (!isIndoor) {
                isIndoor = true
                val pose = currentPose
                if (pose != null && gpsAnchorPose == null) {
                    gpsAnchorPose = pose
                    lastSegmentPos = floatArrayOf(pose.x, pose.y, pose.z)
                }
                log("Indoor mode: GPS accuracy=${event.accuracy}m, tracking VIO path")
            }
        }
    }

    private fun onPoseUpdate(event: XRealEvent.PerceptionEvent.HeadPoseUpdated) {
        if (!event.is6DoF) return

        val pose = PoseState(
            x = event.x, y = event.y, z = event.z,
            qx = event.qx, qy = event.qy, qz = event.qz, qw = event.qw,
            is6DoF = true,
            timestamp = System.currentTimeMillis()
        )
        currentPose = pose

        // VIO 시작점 기록 (첫 6-DoF 포즈)
        if (vioStartY == null) {
            vioStartY = event.y
        }

        // GPS 앵커가 없으면 첫 포즈를 앵커로
        if (gpsAnchorPose == null) {
            gpsAnchorPose = pose
            lastSegmentPos = floatArrayOf(event.x, event.y, event.z)
            return
        }

        // 실내 모드에서만 세그먼트 기록
        if (!isIndoor) return

        // 마지막 세그먼트 시작점 대비 이동 거리
        val dx = event.x - lastSegmentPos[0]
        val dy = event.y - lastSegmentPos[1]
        val dz = event.z - lastSegmentPos[2]
        val dist = sqrt(dx * dx + dy * dy + dz * dz)

        if (dist >= SEGMENT_MIN_DISTANCE_M) {
            if (segments.size < MAX_SEGMENTS) {
                segments.add(PathSegment(dx, dy, dz, dist))
            }
            totalPathDistance += dist
            lastSegmentPos = floatArrayOf(event.x, event.y, event.z)
        }
    }

    // ── 유틸리티 ──

    /**
     * 쿼터니언 → Yaw (방위각) 변환.
     * @return 0-360° (Hamilton convention: Y-up, Z-forward 기준)
     */
    private fun quaternionToYaw(qx: Float, qy: Float, qz: Float, qw: Float): Float {
        val yaw = atan2(
            2f * (qw * qy + qx * qz),
            1f - 2f * (qy * qy + qz * qz)
        )
        val degrees = Math.toDegrees(yaw.toDouble()).toFloat()
        return ((degrees % 360f) + 360f) % 360f
    }

    /**
     * PathSegment — 이동 경로의 단일 구간.
     */
    private data class PathSegment(
        val dx: Float,
        val dy: Float,
        val dz: Float,
        val distance: Float
    )
}

/**
 * PathState — 현재 이동 경로 상태.
 */
data class PathState(
    val totalDistance: Float,
    val vioYDisplacement: Float,
    val estimatedFloor: Int,
    val isIndoor: Boolean,
    val segmentCount: Int
)
