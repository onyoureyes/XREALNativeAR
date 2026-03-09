package com.xreal.nativear.running

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.xreal.nativear.core.FloorDirection
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.policy.PolicyReader
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.*

/**
 * BarometricFloorDetector — 기압 센서 기반 층간 이동 감지기.
 *
 * ## 해결하는 문제
 * 아파트 단지에서 계단/엘리베이터 사용 시 고도 변화 감지.
 * GPS 고도는 ±10m 오차로 층 구분 불가 → 기압계가 유일한 수단.
 *
 * ## 알고리즘
 * 1. TYPE_PRESSURE 센서에서 ~5Hz로 기압 수집
 * 2. Ring buffer (300샘플 = 60초) 유지
 * 3. 30초 중앙값(median)으로 기준선(baseline) 계산 (기상 기압 변화 추적)
 * 4. 최근 5초 평균 vs 기준선: Δ ≥ 1.2 hPa → 1층 이동
 * 5. 5초 디바운스로 진동 방지
 * 6. 기압 상승 = 하강 (DOWN), 기압 하강 = 상승 (UP)
 *
 * ## 설계 결정
 * - 절대 고도 대신 **상대 기압 변화** 사용 → 기상 드리프트 영향 최소화
 * - median 기준선 → 이상치(문 열림, 바람 등)에 강건
 * - 1.2 hPa/층은 표준 대기 기준 (~3.6m/층)
 *
 * Publishes: FloorChange → RunningRouteTracker + RunningCoachHUD
 */
class BarometricFloorDetector(
    context: Context,
    private val eventBus: GlobalEventBus
) {
    private val TAG = "BaroFloorDetector"
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Ring buffer for pressure history (~5Hz × 60s = 300 samples)
    private val HISTORY_SIZE = 300
    private val pressureHistory = FloatArray(HISTORY_SIZE)
    private var historyIdx = 0
    private var sampleCount = 0

    // Floor detection state
    private var baselinePressure = 0f
    private var baselineSet = false
    private var cumulativeFloors = 0
    private var lastFloorChangeTimeMs = 0L

    companion object {
        val HPA_PER_FLOOR: Float get() = PolicyReader.getFloat("running.floor_hpa_per_floor", 1.2f)      // ~3.6m per floor at sea level
        const val DETECTION_WINDOW = 25      // 5 seconds at ~5Hz
        const val BASELINE_WINDOW = 150      // 30 seconds for baseline median
        val DEBOUNCE_MS: Long get() = PolicyReader.getLong("running.floor_debounce_ms", 5000L)        // minimum 5s between floor change events
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_PRESSURE) return
            processPressure(event.values[0])
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start() {
        if (pressureSensor == null) {
            Log.w(TAG, "No pressure sensor available on this device")
            return
        }
        sampleCount = 0
        historyIdx = 0
        cumulativeFloors = 0
        baselineSet = false
        lastFloorChangeTimeMs = 0L
        sensorManager.registerListener(listener, pressureSensor, SensorManager.SENSOR_DELAY_UI)
        Log.i(TAG, "Barometric floor detector started")
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        Log.i(TAG, "Barometric floor detector stopped (cumulative floors: $cumulativeFloors)")
    }

    private fun processPressure(pressureHpa: Float) {
        // Store in ring buffer
        pressureHistory[historyIdx % HISTORY_SIZE] = pressureHpa
        historyIdx++
        sampleCount++

        // Need at least detection window samples
        if (sampleCount < DETECTION_WINDOW) return

        // Set initial baseline after collecting enough samples
        if (!baselineSet && sampleCount >= BASELINE_WINDOW) {
            baselinePressure = computeMedian(BASELINE_WINDOW)
            baselineSet = true
            Log.d(TAG, "Baseline pressure set: ${String.format("%.2f", baselinePressure)} hPa")
        }

        if (!baselineSet) return

        // Compute short-term average (recent 5 seconds)
        val recentAvg = computeAverage(DETECTION_WINDOW)

        // Compute longer-term baseline (30s median, continuously updated)
        val windowSize = minOf(sampleCount, BASELINE_WINDOW)
        val longMedian = computeMedian(windowSize)

        // Pressure delta: positive = pressure increased = went down
        val delta = recentAvg - longMedian

        // Debounce check
        val now = System.currentTimeMillis()
        if (now - lastFloorChangeTimeMs < DEBOUNCE_MS) return

        // Detect floor change
        if (Math.abs(delta) >= HPA_PER_FLOOR) {
            val floors = (delta / HPA_PER_FLOOR).toInt()
            if (floors != 0) {
                val direction = if (delta < 0) FloorDirection.UP else FloorDirection.DOWN
                val absFloors = Math.abs(floors)
                cumulativeFloors += if (direction == FloorDirection.UP) absFloors else -absFloors
                lastFloorChangeTimeMs = now

                Log.i(TAG, "Floor change detected: ${if (direction == FloorDirection.UP) "UP" else "DOWN"} $absFloors floor(s), cumulative: $cumulativeFloors")

                scope.launch {
                    eventBus.publish(XRealEvent.PerceptionEvent.FloorChange(
                        deltaFloors = absFloors,
                        direction = direction,
                        pressureHpa = pressureHpa,
                        cumulativeFloors = cumulativeFloors,
                        timestamp = now
                    ))
                }

                // Reset baseline after floor change to detect next transition
                baselinePressure = pressureHpa
            }
        }
    }

    private fun computeMedian(windowSize: Int): Float {
        val size = minOf(windowSize, sampleCount, HISTORY_SIZE)
        if (size == 0) return 0f

        val values = FloatArray(size)
        for (i in 0 until size) {
            val idx = ((historyIdx - 1 - i) % HISTORY_SIZE + HISTORY_SIZE) % HISTORY_SIZE
            values[i] = pressureHistory[idx]
        }
        values.sort()
        return if (size % 2 == 0) {
            (values[size / 2 - 1] + values[size / 2]) / 2f
        } else {
            values[size / 2]
        }
    }

    private fun computeAverage(windowSize: Int): Float {
        val size = minOf(windowSize, sampleCount, HISTORY_SIZE)
        if (size == 0) return 0f

        var sum = 0f
        for (i in 0 until size) {
            val idx = ((historyIdx - 1 - i) % HISTORY_SIZE + HISTORY_SIZE) % HISTORY_SIZE
            sum += pressureHistory[idx]
        }
        return sum / size
    }
}
