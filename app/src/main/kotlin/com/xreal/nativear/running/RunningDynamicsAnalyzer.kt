package com.xreal.nativear.running

import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.*

/**
 * RunningDynamicsAnalyzer — 폰 가속도계 + 걸음감지기에서 러닝 역학 메트릭 추출.
 *
 * ## 추출 메트릭
 * | 메트릭 | 소스 | 설명 |
 * |--------|------|------|
 * | 케이던스 (spm) | 걸음 타임스탬프 | 60초 윈도우에서 분당 걸음 수 |
 * | 수직진동 (cm) | 가속도계 Z | 피크 투 피크 바운스 (~2.5cm/g) |
 * | 접지시간 (ms) | 가속도계 | 9.0g 미만 비율 × 1000ms |
 * | 지면반발력 (G) | 가속도계 | 피크 가속도 |
 * | 머리안정도 | IMU 쿼터니언 | pitch/yaw 분산 → 0-100 점수 |
 *
 * ## 데이터 윈도우
 * - stepTimestamps: 최근 60걸음 (케이던스)
 * - accelZBuffer: 최근 200샘플 ~4초 @ 50Hz (VO, GCT, GRF)
 * - pitchHistory/yawHistory: 최근 90샘플 ~3초 @ 30Hz (안정도)
 *
 * ## 이벤트 발행
 * 2초 주기로 RunningDynamics + HeadStability 이벤트 발행 → FormRouter
 *
 * @see FormRouter 이 메트릭을 기반으로 폼 이상 감지
 * @see RunningCoachManager.onStepDetected HardwareManager에서 호출
 */
class RunningDynamicsAnalyzer(
    private val eventBus: GlobalEventBus
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Step detection from TYPE_STEP_DETECTOR events
    private val stepTimestamps = ArrayDeque<Long>(60)

    // Accelerometer window for vertical oscillation + GCT
    private val accelZBuffer = FloatArray(200) // ~4s at 50Hz SENSOR_DELAY_UI
    private var accelIdx = 0
    private var accelCount = 0

    // Head stability tracking (quaternion -> Euler variance)
    private val pitchHistory = FloatArray(90) // ~3s at 30Hz
    private val yawHistory = FloatArray(90)
    private var headIdx = 0
    private var headCount = 0

    private var dynamicsJob: Job? = null

    fun start() {
        subscribeToEvents()
        startPeriodicPublish()
    }

    private fun subscribeToEvents() {
        scope.launch {
            eventBus.events.collect { event ->
                try {
                    when (event) {
                        is XRealEvent.PerceptionEvent.HeadPoseUpdated -> processHeadPose(event)
                        else -> {}
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("RunDynamics", "이벤트 처리 오류 (루프 유지됨): ${event::class.simpleName} — ${e.message}", e)
                }
            }
        }
    }

    // Called directly by RunningCoachManager when HardwareManager fires step events
    fun onStepDetected(timestampMs: Long) {
        stepTimestamps.addLast(timestampMs)
        if (stepTimestamps.size > 60) stepTimestamps.removeFirst()
    }

    // Called directly by RunningCoachManager when HardwareManager fires accel data
    fun onAccelerometerData(x: Float, y: Float, z: Float) {
        accelZBuffer[accelIdx % accelZBuffer.size] = z
        accelIdx++
        accelCount++
    }

    private fun processHeadPose(pose: XRealEvent.PerceptionEvent.HeadPoseUpdated) {
        val pitch = Math.toDegrees(
            Math.asin((2.0 * (pose.qw * pose.qy - pose.qx * pose.qz)).coerceIn(-1.0, 1.0))
        ).toFloat()
        val yaw = Math.toDegrees(
            Math.atan2(
                2.0 * (pose.qw * pose.qz + pose.qx * pose.qy),
                1.0 - 2.0 * (pose.qy * pose.qy + pose.qz * pose.qz)
            )
        ).toFloat()

        pitchHistory[headIdx % pitchHistory.size] = pitch
        yawHistory[headIdx % yawHistory.size] = yaw
        headIdx++
        headCount++
    }

    fun computeCadence(): Float {
        if (stepTimestamps.size < 2) return 0f
        val recentSteps = stepTimestamps.toList()
        val windowMs = recentSteps.last() - recentSteps.first()
        if (windowMs <= 0) return 0f
        return (recentSteps.size - 1).toFloat() / (windowMs / 60000f)
    }

    fun computeVerticalOscillation(): Float {
        if (accelCount < 50) return 0f
        val count = minOf(accelCount, accelZBuffer.size)
        var min = Float.MAX_VALUE
        var max = Float.MIN_VALUE
        for (i in 0 until count) {
            val v = accelZBuffer[i]
            if (v < min) min = v
            if (v > max) max = v
        }
        // Convert g-force amplitude to cm (rough: 1g oscillation ~ 2.5cm at 180spm)
        return (max - min) * 2.5f
    }

    fun computeGroundContactTime(): Float {
        if (accelCount < 50 || stepTimestamps.size < 2) return 0f
        val cadence = computeCadence()
        if (cadence == 0f) return 0f
        val stridePeriodMs = 60000f / cadence
        val count = minOf(accelCount, accelZBuffer.size)
        var belowGravity = 0
        for (i in 0 until count) {
            if (accelZBuffer[i] < 9.0f) belowGravity++
        }
        val flightRatio = belowGravity.toFloat() / count
        return stridePeriodMs * (1f - flightRatio)
    }

    fun computeGroundReactionForce(): Float {
        if (accelCount < 20) return 0f
        val count = minOf(accelCount, accelZBuffer.size)
        var maxAccel = 0f
        for (i in 0 until count) {
            val v = Math.abs(accelZBuffer[i])
            if (v > maxAccel) maxAccel = v
        }
        return maxAccel / 9.81f
    }

    fun computeHeadStability(): XRealEvent.PerceptionEvent.HeadStability? {
        if (headCount < 30) return null
        val count = minOf(headCount, pitchHistory.size)

        var pitchMean = 0f
        var yawMean = 0f
        for (i in 0 until count) {
            pitchMean += pitchHistory[i]
            yawMean += yawHistory[i]
        }
        pitchMean /= count
        yawMean /= count

        var pitchVar = 0f
        var yawVar = 0f
        var leftSamples = 0
        var rightSamples = 0
        for (i in 0 until count) {
            pitchVar += (pitchHistory[i] - pitchMean) * (pitchHistory[i] - pitchMean)
            val yawDelta = yawHistory[i] - yawMean
            yawVar += yawDelta * yawDelta
            if (yawDelta > 0) rightSamples++ else leftSamples++
        }
        pitchVar /= count
        yawVar /= count

        val balance = (rightSamples - leftSamples).toFloat() / count
        val stabilityScore = (100f - (pitchVar + yawVar) * 2f).coerceIn(0f, 100f)

        return XRealEvent.PerceptionEvent.HeadStability(
            pitchVariance = pitchVar,
            yawVariance = yawVar,
            lateralBalance = balance,
            stabilityScore = stabilityScore,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun startPeriodicPublish() {
        dynamicsJob = scope.launch {
            while (isActive) {
                delay(2000L)
                val cadence = computeCadence()
                if (cadence > 0) {
                    eventBus.publish(XRealEvent.PerceptionEvent.RunningDynamics(
                        cadence = cadence,
                        verticalOscillation = computeVerticalOscillation(),
                        groundContactTime = computeGroundContactTime(),
                        groundReactionForce = computeGroundReactionForce(),
                        timestamp = System.currentTimeMillis()
                    ))
                }

                computeHeadStability()?.let {
                    eventBus.publish(it)
                }
            }
        }
    }

    fun stop() {
        dynamicsJob?.cancel()
    }

    fun release() {
        stop()
        scope.cancel()
    }
}
