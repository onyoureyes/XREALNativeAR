package com.xreal.nativear.spatial

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * MagneticHeadingProvider — 폰 자기 센서 기반 절대 방위 제공자.
 *
 * ## 역할
 * TYPE_ROTATION_VECTOR 센서 (가속도계 + 자이로 + 자기장 퓨전)에서
 * 절대 방위각(azimuth)을 추출하여 VIO yaw 드리프트 보정에 사용.
 *
 * ## VIO Yaw 드리프트 문제
 * VIO (Visual-Inertial Odometry)의 yaw는 자이로 적분 기반이므로
 * 시간이 지나면 실제 방위와 어긋남 (보통 ~0.1-0.5°/min 드리프트).
 * 자기 방위는 지자기 기준 절대값이므로 이를 참조 신호로 활용.
 *
 * ## TYPE_ROTATION_VECTOR 선택 이유
 * - TYPE_MAGNETIC_FIELD (raw 자기장): 소프트아이언/하드아이언 보정 필요
 * - TYPE_ROTATION_VECTOR: 구글 센서 퓨전 알고리즘이 이미 보정 완료
 * - 정확도: ~3-5° (실내), ~1-2° (실외, 자기 간섭 적음)
 *
 * ## 한계
 * - 실내 자기 간섭 (철골 구조, 전자기기)으로 ±10-20° 오류 가능
 * - DriftCorrectionManager에서 low-pass 필터 + 이상치 제거로 완화
 * - accuracy 필드로 센서 자체 신뢰도 확인 가능
 *
 * @param context Android Context (SensorManager 접근용)
 *
 * @see DriftCorrectionManager Yaw 보정 소비자
 */
class MagneticHeadingProvider(
    context: Context
) : SensorEventListener {

    companion object {
        private const val TAG = "MagneticHeading"

        /** 방위 이력 버퍼 크기 (중앙값 필터용, ~2초 @SENSOR_DELAY_UI) */
        const val HEADING_BUFFER_SIZE = 10

        /** 이상치 제거 임계값 (도). 이전 값 대비 이 이상 변하면 무시 */
        const val OUTLIER_THRESHOLD_DEG = 30f
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    // ── Ring buffer for median filtering ──
    private val headingBuffer = FloatArray(HEADING_BUFFER_SIZE)
    private var bufferIdx = 0
    private var bufferCount = 0

    // ── Public state ──

    /** 절대 방위각 (0-360°, 0=북, 90=동, 180=남, 270=서). 중앙값 필터 적용. */
    @Volatile
    var absoluteHeadingDegrees: Float = 0f
        private set

    /** raw 방위각 (필터 미적용, 디버그용) */
    @Volatile
    var rawHeadingDegrees: Float = 0f
        private set

    /** 센서 가용 여부 (첫 유효 데이터 수신 후 true) */
    @Volatile
    var isAvailable: Boolean = false
        private set

    /** 센서 정확도 (SensorManager.SENSOR_STATUS_xxx) */
    @Volatile
    var accuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE
        private set

    /** 마지막 유효 업데이트 시각 */
    @Volatile
    var lastUpdateTimestamp: Long = 0
        private set

    // ── Lifecycle ──

    fun start() {
        if (rotationVectorSensor == null) {
            Log.w(TAG, "TYPE_ROTATION_VECTOR sensor not available on this device")
            return
        }

        bufferIdx = 0
        bufferCount = 0
        isAvailable = false

        sensorManager.registerListener(
            this,
            rotationVectorSensor,
            SensorManager.SENSOR_DELAY_UI  // ~60ms, 충분한 갱신 빈도
        )
        Log.i(TAG, "Magnetic heading provider started (ROTATION_VECTOR)")
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        isAvailable = false
        Log.i(TAG, "Magnetic heading provider stopped (last heading: ${"%.1f".format(absoluteHeadingDegrees)}°)")
    }

    // ── SensorEventListener ──

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        // ROTATION_VECTOR → 3×3 rotation matrix → orientation (azimuth, pitch, roll)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)

        // orientation[0] = azimuth (radians), 범위 -π ~ +π
        // 0 = North, π/2 = East, π/-π = South, -π/2 = West
        val azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
        val normalizedDeg = ((azimuthDeg % 360f) + 360f) % 360f

        rawHeadingDegrees = normalizedDeg

        // 이상치 제거: 이전 값 대비 급격한 변화 무시 (자기 간섭 스파이크 방어)
        if (isAvailable && bufferCount > 3) {
            val diff = QuaternionUtils.angleDifference(normalizedDeg, absoluteHeadingDegrees)
            if (kotlin.math.abs(diff) > OUTLIER_THRESHOLD_DEG) {
                // 이상치 — 무시하되, 연속 3회 이상 같은 방향이면 실제 변화로 판단
                return
            }
        }

        // Ring buffer에 추가
        headingBuffer[bufferIdx % HEADING_BUFFER_SIZE] = normalizedDeg
        bufferIdx++
        bufferCount++

        // 중앙값 필터 (이상치 제거 후)
        absoluteHeadingDegrees = computeCircularMedian()
        lastUpdateTimestamp = System.currentTimeMillis()

        if (!isAvailable && bufferCount >= 3) {
            isAvailable = true
            Log.i(TAG, "First valid magnetic heading: ${"%.1f".format(absoluteHeadingDegrees)}° (accuracy=$accuracy)")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, acc: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val oldAcc = accuracy
            accuracy = acc
            if (acc != oldAcc) {
                val label = when (acc) {
                    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "HIGH"
                    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "MEDIUM"
                    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "LOW"
                    else -> "UNRELIABLE"
                }
                Log.d(TAG, "Magnetic sensor accuracy changed: $label")
            }
        }
    }

    // ── 내부 유틸리티 ──

    /**
     * 원형(circular) 중앙값 계산.
     *
     * 각도는 원형 데이터이므로 단순 정렬 불가 (359° 와 1°의 중앙값 = 0°).
     * 참조 각도 기준으로 signed difference 계산 후 중앙값 적용.
     */
    private fun computeCircularMedian(): Float {
        val size = minOf(bufferCount, HEADING_BUFFER_SIZE)
        if (size == 0) return 0f
        if (size == 1) return headingBuffer[0]

        // 참조 각도 = 가장 최근 값
        val reference = headingBuffer[(bufferIdx - 1 + HEADING_BUFFER_SIZE) % HEADING_BUFFER_SIZE]

        // 참조 기준 signed difference 배열
        val diffs = FloatArray(size)
        for (i in 0 until size) {
            val idx = ((bufferIdx - 1 - i) % HEADING_BUFFER_SIZE + HEADING_BUFFER_SIZE) % HEADING_BUFFER_SIZE
            diffs[i] = QuaternionUtils.angleDifference(headingBuffer[idx], reference)
        }
        diffs.sort()

        // 중앙값
        val medianDiff = if (size % 2 == 0) {
            (diffs[size / 2 - 1] + diffs[size / 2]) / 2f
        } else {
            diffs[size / 2]
        }

        // 참조 + 중앙값 차이 → 절대 방위
        return ((reference + medianDiff) % 360f + 360f) % 360f
    }

    /**
     * 현재 센서 신뢰도 (0.0-1.0).
     *
     * accuracy + 데이터 신선도 기반.
     * DriftCorrectionManager가 yaw 보정 가중치 결정에 사용.
     */
    fun getReliability(): Float {
        if (!isAvailable) return 0f

        // 센서 정확도 기반 (0.3-1.0)
        val accScore = when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 1.0f
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 0.7f
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 0.4f
            else -> 0.2f
        }

        // 데이터 신선도 (3초 이내 = 1.0, 이후 감소)
        val age = System.currentTimeMillis() - lastUpdateTimestamp
        val freshnessScore = (1.0f - (age / 5000f)).coerceIn(0f, 1f)

        return accScore * freshnessScore
    }
}
