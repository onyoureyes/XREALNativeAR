package com.xreal.nativear.running

import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.PositionMode
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.*

/**
 * RunningRouteTracker — FusedPositionUpdate를 구독하여 러닝 경로를 누적.
 *
 * ## 설계 변경 이력
 * v1: LocationManager에 직접 의존, 5초 GPS 폴링, 2m 지터 필터.
 * v2 (현재): LocationManager 의존 제거. PositionFusionEngine → EventBus → 구독.
 *           Kalman 필터가 지터를 처리하므로 자체 필터 불필요.
 *
 * ## 역할
 * - GPS/PDR 융합 위치를 RoutePoint 리스트로 누적
 * - 거리, 고도 차이, 페이스 계산
 * - 위치 모드(GPS_GOOD/DEGRADED/PDR_ONLY) 및 층간 이동 추적
 * - RunningRouteUpdate 이벤트 발행 → IntensityRouter + RunningCoachManager
 *
 * @see PositionFusionEngine 위치 공급원
 * @see RunningCoachManager.persistRouteData 세션 종료 시 경로 저장
 */
class RunningRouteTracker(
    private val eventBus: GlobalEventBus
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    data class RoutePoint(val lat: Double, val lon: Double, val altitude: Double,
                          val speed: Float, val timestampMs: Long,
                          val mode: PositionMode = PositionMode.GPS_GOOD)
    val routePoints = mutableListOf<RoutePoint>()

    var totalDistanceMeters: Float = 0f
        private set
    var elevationGainMeters: Float = 0f
        private set
    var elevationLossMeters: Float = 0f
        private set
    var currentPositionMode: PositionMode = PositionMode.GPS_GOOD
        private set
    var currentFloorDelta: Int = 0
        private set

    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastAltitude: Double? = null
    private var hasLastPosition = false

    private val speedWindow = ArrayDeque<Float>(30)
    private var trackingJob: Job? = null

    fun start() {
        routePoints.clear()
        totalDistanceMeters = 0f
        elevationGainMeters = 0f
        elevationLossMeters = 0f
        currentPositionMode = PositionMode.GPS_GOOD
        currentFloorDelta = 0
        hasLastPosition = false
        lastAltitude = null
        speedWindow.clear()

        trackingJob = scope.launch {
            eventBus.events.collect { event ->
                try {
                    when (event) {
                        is XRealEvent.PerceptionEvent.FusedPositionUpdate -> {
                            onFusedPosition(event)
                        }
                        is XRealEvent.PerceptionEvent.FloorChange -> {
                            currentFloorDelta = event.cumulativeFloors
                        }
                        else -> {}
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("RunningRouteTracker", "이벤트 처리 오류 (루프 유지됨): ${event::class.simpleName} — ${e.message}", e)
                }
            }
        }
    }

    private suspend fun onFusedPosition(event: XRealEvent.PerceptionEvent.FusedPositionUpdate) {
        currentPositionMode = event.mode
        currentFloorDelta = event.floorDelta

        if (hasLastPosition) {
            val dist = flatDistance(lastLat, lastLon, event.latitude, event.longitude).toFloat()
            if (dist < 1f) return // Minimal movement filter
            totalDistanceMeters += dist

            // Elevation tracking
            val alt = event.altitude
            val prevAlt = lastAltitude
            if (prevAlt != null) {
                val elevDelta = alt - prevAlt
                if (elevDelta > 1.0) elevationGainMeters += elevDelta.toFloat()
                else if (elevDelta < -1.0) elevationLossMeters += Math.abs(elevDelta).toFloat()
            }
            lastAltitude = alt
        }

        lastLat = event.latitude
        lastLon = event.longitude
        hasLastPosition = true
        if (lastAltitude == null) lastAltitude = event.altitude

        // Speed window
        if (event.speed > 0.3f) {
            speedWindow.addLast(event.speed)
            if (speedWindow.size > 30) speedWindow.removeFirst()
        }

        routePoints.add(RoutePoint(
            event.latitude, event.longitude, event.altitude,
            event.speed, event.timestamp, event.mode
        ))

        val avgSpeed = if (speedWindow.isNotEmpty()) speedWindow.average().toFloat() else event.speed
        val pace = if (avgSpeed > 0.3f) (1000f / avgSpeed) / 60f else 0f

        eventBus.publish(XRealEvent.PerceptionEvent.RunningRouteUpdate(
            distanceMeters = totalDistanceMeters,
            paceMinPerKm = pace,
            currentSpeedMps = event.speed,
            elevationGainMeters = elevationGainMeters,
            elapsedSeconds = if (routePoints.size > 1)
                (routePoints.last().timestampMs - routePoints.first().timestampMs) / 1000 else 0,
            latitude = event.latitude,
            longitude = event.longitude,
            timestamp = event.timestamp,
            positionMode = event.mode,
            floorDelta = currentFloorDelta
        ))
    }

    fun stop() {
        trackingJob?.cancel()
    }

    fun getRouteGeoJSON(): String {
        val coords = routePoints.joinToString(",") { "[${it.lon},${it.lat},${it.altitude}]" }
        return """{"type":"LineString","coordinates":[$coords]}"""
    }

    fun release() {
        stop()
        scope.cancel()
    }

    companion object {
        private const val R_EARTH = 6_371_000.0

        fun flatDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dN = Math.toRadians(lat2 - lat1) * R_EARTH
            val dE = Math.toRadians(lon2 - lon1) * R_EARTH * Math.cos(Math.toRadians(lat1))
            return Math.sqrt(dN * dN + dE * dE)
        }
    }
}
