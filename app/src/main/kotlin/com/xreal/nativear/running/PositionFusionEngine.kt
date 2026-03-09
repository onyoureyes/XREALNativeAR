package com.xreal.nativear.running

import android.util.Log
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.policy.PolicyReader
import com.xreal.nativear.core.PositionMode
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.*

/**
 * PositionFusionEngine — GPS+PDR 위치 융합 엔진 (4-state Kalman Filter).
 *
 * ## 해결하는 문제
 * 대구 밀집 아파트 단지에서 GPS 멀티패스 오류로 위치가 텔레포트되는 현상.
 * 실내(지하주차장, 건물 내부)에서 GPS 신호 완전 손실 시 위치 추적 불가.
 *
 * ## 핵심 기능
 * - **GPS 아웃라이어 거부**: 6m/s 초과 이동속도(텔레포트) 또는 50m 초과 정확도 → 무시
 * - **듀얼 GPS 융합**: Phone GPS + Watch GPS를 같은 Kalman 필터에 공급, 정확도로 자동 가중치
 * - **PDR 통합**: GPS 열화/손실 시 IMU 방위각 + 걸음 감지로 Dead Reckoning
 * - **모드 전환**: GPS_GOOD → GPS_DEGRADED(15초) → PDR_ONLY(25초) → GPS_RECOVERED
 * - **보폭 자동보정**: GPS 양호 시 GPS 거리/걸음수로 보폭 재계산 (α=0.2 지수평활)
 *
 * ## 좌표계
 * 로컬 ENU (East-North-Up) 미터 좌표. 첫 번째 유효 GPS를 원점으로 설정.
 * Kalman 필터 내부는 모두 미터 단위로 동작하여 수치 안정성 확보.
 *
 * ## 라이프사이클
 * RunningCoachManager.startRun()에서 start(), stopRun()에서 stop().
 * 세션 외에는 비활성 상태 (배터리 절약).
 *
 * Subscribes to: PhoneGps, WatchGps, PdrStepUpdate, FloorChange
 * Publishes: FusedPositionUpdate
 *
 * @see KalmanFilter2D 순수 수학 엔진 (Android 의존성 없음)
 * @see RunningRouteTracker FusedPositionUpdate를 구독하여 거리/페이스 누적
 */
class PositionFusionEngine(
    private val eventBus: GlobalEventBus
) {
    private val TAG = "PositionFusion"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val kalman = KalmanFilter2D()

    // Anchor point for local ENU frame (first good GPS fix)
    private var anchorLat = 0.0
    private var anchorLon = 0.0
    private var anchorSet = false

    // GPS state machine
    var positionMode = PositionMode.GPS_GOOD
        private set
    var lastGpsAccuracy = Float.MAX_VALUE
        private set
    private var lastGpsFixTimeMs = 0L

    // Last accepted position (for speed-based outlier rejection)
    private var lastAcceptedLat = 0.0
    private var lastAcceptedLon = 0.0
    private var lastAcceptedTimeMs = 0L

    // PDR tracking
    private var lastPdrX = 0.0
    private var lastPdrY = 0.0
    private var lastPdrTimeMs = 0L

    // Stride calibration
    private var gpsDistanceAccumM = 0.0
    private var stepCountSinceCalib = 0
    private var calibratedStride = 0.65
    private var lastCalibLat = 0.0
    private var lastCalibLon = 0.0

    // Last altitude from GPS
    private var lastAltitude = 0.0

    // Floor delta (updated externally via FloorChange events)
    var cumulativeFloorDelta = 0

    companion object {
        val GPS_DEGRADED_TIMEOUT_MS: Long get() = PolicyReader.getLong("running.gps_degraded_timeout_ms", 15_000L)
        val GPS_LOST_TIMEOUT_MS: Long get() = PolicyReader.getLong("running.gps_lost_timeout_ms", 25_000L)
        val MAX_RUNNING_SPEED_MPS: Double get() = PolicyReader.getFloat("running.max_running_speed_mps", 6.0f).toDouble()     // ~21.6 km/h
        val MAX_ACCURACY_ACCEPT: Float get() = PolicyReader.getFloat("running.max_gps_accuracy_accept", 50.0f)      // reject > 50m accuracy
        val GOOD_ACCURACY_THRESHOLD: Float get() = PolicyReader.getFloat("running.good_accuracy_threshold", 15.0f)
        val PDR_RESET_UNCERTAINTY_M: Double get() = PolicyReader.getFloat("running.pdr_reset_uncertainty_m", 50.0f).toDouble()    // reset to GPS if PDR drifted > 50m
        val STRIDE_CALIB_MIN_STEPS: Int get() = PolicyReader.getInt("running.stride_calib_min_steps", 50)       // need at least 50 steps for calibration
        val STRIDE_CALIB_MIN_DIST_M: Double get() = PolicyReader.getFloat("running.stride_calib_min_dist_m", 20.0f).toDouble()    // need at least 20m GPS distance
        const val R_EARTH = 6_371_000.0
    }

    fun start() {
        Log.i(TAG, "Position fusion engine started")
        anchorSet = false
        positionMode = PositionMode.GPS_GOOD
        lastGpsFixTimeMs = 0L
        lastAcceptedTimeMs = 0L
        lastPdrX = 0.0
        lastPdrY = 0.0
        lastPdrTimeMs = 0L
        gpsDistanceAccumM = 0.0
        stepCountSinceCalib = 0
        cumulativeFloorDelta = 0

        subscribeToEvents()
        startModeCheckLoop()
    }

    private fun subscribeToEvents() {
        scope.launch {
            eventBus.events.collect { event ->
                try {
                    when (event) {
                        is XRealEvent.PerceptionEvent.PhoneGps -> handleGpsFix(
                            event.latitude, event.longitude, event.altitude,
                            event.accuracy, event.speed, event.timestamp, "phone"
                        )
                        is XRealEvent.PerceptionEvent.WatchGps -> handleGpsFix(
                            event.latitude, event.longitude, event.altitude,
                            event.accuracy, event.speed, event.timestamp, "watch"
                        )
                        is XRealEvent.PerceptionEvent.PdrStepUpdate -> {
                            handlePdrUpdate(event.pdrX, event.pdrY, event.timestamp)
                        }
                        is XRealEvent.PerceptionEvent.FloorChange -> {
                            cumulativeFloorDelta = event.cumulativeFloors
                        }
                        else -> {}
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "이벤트 처리 오류 (루프 유지됨): ${event::class.simpleName} — ${e.message}", e)
                }
            }
        }
    }

    private fun handleGpsFix(
        lat: Double, lon: Double, alt: Double,
        accuracy: Float, @Suppress("UNUSED_PARAMETER") speed: Float, timestamp: Long, source: String
    ) {
        // 1. Accuracy-based rejection
        if (accuracy > MAX_ACCURACY_ACCEPT) {
            Log.d(TAG, "GPS rejected ($source): accuracy ${accuracy}m > ${MAX_ACCURACY_ACCEPT}m")
            return
        }

        // 2. Speed-based outlier rejection (teleport detection)
        if (lastAcceptedTimeMs > 0) {
            val dtSec = (timestamp - lastAcceptedTimeMs) / 1000.0
            if (dtSec > 0.5) {
                val dist = flatDistance(lastAcceptedLat, lastAcceptedLon, lat, lon)
                val impliedSpeed = dist / dtSec
                if (impliedSpeed > MAX_RUNNING_SPEED_MPS) {
                    Log.w(TAG, "GPS outlier rejected ($source): implied speed ${String.format("%.1f", impliedSpeed)} m/s > $MAX_RUNNING_SPEED_MPS")
                    return
                }
            }
        }

        // 3. Set anchor on first accepted fix
        if (!anchorSet) {
            anchorLat = lat
            anchorLon = lon
            anchorSet = true
            kalman.reset(0.0, 0.0)
            lastCalibLat = lat
            lastCalibLon = lon
            Log.i(TAG, "Anchor set at ($lat, $lon)")
        }

        // 4. Convert GPS to local ENU meters
        val (northM, eastM) = geoToEnu(lat, lon)

        // 5. Kalman predict + GPS update
        val dtSec = if (lastGpsFixTimeMs > 0) (timestamp - lastGpsFixTimeMs) / 1000.0 else 0.1
        kalman.predict(dtSec.coerceIn(0.01, 30.0))

        // 6. Bound innovation on GPS recovery (inflate noise temporarily)
        if (positionMode == PositionMode.PDR_ONLY) {
            val uncertainty = kalman.getPositionUncertainty()
            if (uncertainty > PDR_RESET_UNCERTAINTY_M) {
                // PDR drifted too far — hard reset to GPS
                kalman.reset(northM, eastM)
                Log.i(TAG, "GPS recovered: hard reset (PDR uncertainty ${String.format("%.1f", uncertainty)}m)")
            } else {
                // Soft recovery: inflate measurement noise
                kalman.updateGPS(northM, eastM, accuracy.coerceAtLeast(20f))
                Log.i(TAG, "GPS recovered: soft merge (PDR uncertainty ${String.format("%.1f", uncertainty)}m)")
            }
            positionMode = PositionMode.GPS_RECOVERED
        } else {
            kalman.updateGPS(northM, eastM, accuracy)
        }

        // 7. Update GPS state
        lastGpsFixTimeMs = timestamp
        lastGpsAccuracy = accuracy
        lastAcceptedLat = lat
        lastAcceptedLon = lon
        lastAcceptedTimeMs = timestamp
        lastAltitude = alt

        // 8. Update position mode
        positionMode = if (accuracy < GOOD_ACCURACY_THRESHOLD) PositionMode.GPS_GOOD else PositionMode.GPS_DEGRADED

        // 9. Stride calibration (when GPS is good)
        if (positionMode == PositionMode.GPS_GOOD) {
            val calibDist = flatDistance(lastCalibLat, lastCalibLon, lat, lon)
            gpsDistanceAccumM += calibDist
            lastCalibLat = lat
            lastCalibLon = lon

            if (stepCountSinceCalib >= STRIDE_CALIB_MIN_STEPS && gpsDistanceAccumM >= STRIDE_CALIB_MIN_DIST_M) {
                val newStride = gpsDistanceAccumM / stepCountSinceCalib
                if (newStride in 0.4..1.2) {
                    calibratedStride = calibratedStride * 0.8 + newStride * 0.2 // exponential smoothing
                    Log.d(TAG, "Stride calibrated: ${String.format("%.3f", calibratedStride)}m")
                }
                gpsDistanceAccumM = 0.0
                stepCountSinceCalib = 0
            }
        }

        // 10. Publish fused position
        publishFusedPosition(timestamp)
    }

    private fun handlePdrUpdate(pdrX: Double, pdrY: Double, timestamp: Long) {
        // HardwareManager publishes absolute pdrX, pdrY in local meters
        // Compute delta since last PDR update
        val dxFromLast = pdrX - lastPdrX
        val dyFromLast = pdrY - lastPdrY

        if (lastPdrTimeMs == 0L) {
            // First PDR update: just record position, no delta yet
            lastPdrX = pdrX
            lastPdrY = pdrY
            lastPdrTimeMs = timestamp
            return
        }

        lastPdrX = pdrX
        lastPdrY = pdrY

        stepCountSinceCalib++

        // Only apply PDR to Kalman when GPS is not good
        if (positionMode == PositionMode.GPS_GOOD) {
            lastPdrTimeMs = timestamp
            return
        }

        if (!kalman.initialized) {
            lastPdrTimeMs = timestamp
            return
        }

        val dtSec = ((timestamp - lastPdrTimeMs) / 1000.0).coerceIn(0.01, 5.0)
        lastPdrTimeMs = timestamp

        // PDR step: HardwareManager uses cos(yaw) for dx, sin(yaw) for dy
        // In our ENU frame: North = dy (positive y from PDR), East = dx (positive x from PDR)
        // HardwareManager: dx = stride * cos(yaw), dy = stride * sin(yaw)
        // Assuming yaw=0 is East (math convention), then:
        //   East component = dx = stride * cos(yaw)
        //   North component = dy = stride * sin(yaw)
        val vNorth = dyFromLast / dtSec
        val vEast = dxFromLast / dtSec

        kalman.predict(dtSec)
        kalman.updatePDR(vNorth, vEast, calibratedStride * 0.3)

        publishFusedPosition(timestamp)
    }

    private fun publishFusedPosition(timestamp: Long) {
        if (!kalman.initialized || !anchorSet) return

        val (northM, eastM) = kalman.getPosition()
        val (vN, vE) = kalman.getVelocity()
        val (lat, lon) = enuToGeo(northM, eastM)
        val speed = Math.sqrt(vN * vN + vE * vE).toFloat()
        val heading = Math.atan2(vE, vN).toFloat()
        val accuracy = kalman.getPositionUncertainty().toFloat()

        scope.launch {
            eventBus.publish(XRealEvent.PerceptionEvent.FusedPositionUpdate(
                latitude = lat,
                longitude = lon,
                altitude = lastAltitude,
                speed = speed,
                heading = heading,
                accuracy = accuracy,
                mode = positionMode,
                floorDelta = cumulativeFloorDelta,
                timestamp = timestamp
            ))
        }
    }

    private fun startModeCheckLoop() {
        scope.launch {
            while (isActive) {
                delay(5000L)
                if (lastGpsFixTimeMs > 0) {
                    val sinceLastFix = System.currentTimeMillis() - lastGpsFixTimeMs
                    when {
                        sinceLastFix > GPS_LOST_TIMEOUT_MS && positionMode != PositionMode.PDR_ONLY -> {
                            positionMode = PositionMode.PDR_ONLY
                            Log.w(TAG, "GPS lost: no fix for ${sinceLastFix / 1000}s → PDR_ONLY mode")
                        }
                        sinceLastFix > GPS_DEGRADED_TIMEOUT_MS && positionMode == PositionMode.GPS_GOOD -> {
                            positionMode = PositionMode.GPS_DEGRADED
                            Log.d(TAG, "GPS degraded: no fix for ${sinceLastFix / 1000}s")
                        }
                    }
                }
            }
        }
    }

    // ── Coordinate conversions ──

    private fun geoToEnu(lat: Double, lon: Double): Pair<Double, Double> {
        val dLat = Math.toRadians(lat - anchorLat)
        val dLon = Math.toRadians(lon - anchorLon)
        val northM = dLat * R_EARTH
        val eastM = dLon * R_EARTH * Math.cos(Math.toRadians(anchorLat))
        return Pair(northM, eastM)
    }

    private fun enuToGeo(northM: Double, eastM: Double): Pair<Double, Double> {
        val lat = anchorLat + Math.toDegrees(northM / R_EARTH)
        val lon = anchorLon + Math.toDegrees(eastM / (R_EARTH * Math.cos(Math.toRadians(anchorLat))))
        return Pair(lat, lon)
    }

    private fun flatDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dN = Math.toRadians(lat2 - lat1) * R_EARTH
        val dE = Math.toRadians(lon2 - lon1) * R_EARTH * Math.cos(Math.toRadians(lat1))
        return Math.sqrt(dN * dN + dE * dE)
    }

    fun stop() {
        scope.cancel()
        Log.i(TAG, "Position fusion engine stopped")
    }

    fun release() {
        stop()
    }
}
