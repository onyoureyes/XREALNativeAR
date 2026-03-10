package com.xreal.nativear.spatial

import com.xreal.nativear.core.XRealLogger
import com.xreal.nativear.core.FloorDirection
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlin.math.abs

/**
 * DriftCorrectionManager — VIO 드리프트 보정 오케스트레이터.
 *
 * ## 3축 드리프트 보정
 *
 * ### 1. Y축 (수직) — 기압계 보정
 * ```
 * 기압계 FloorChange → barometricFloors × 3.0m = expectedY
 * VIO Y - expectedY = drift → offsetY 보정
 * ```
 * - 보정 주기: FloorChange 이벤트 수신 시 + 5초마다 (층 내 드리프트)
 * - 보정률: drift × 0.1 (10% 점진적 보정, 갑작스러운 점프 방지)
 * - 임계값: drift > 1.0m (층 내 머리 움직임 ±0.5m 은 무시)
 *
 * ### 2. Yaw (방위) — 자기 센서 보정
 * ```
 * 초기 보정: magneticHeading - vioYaw = yawOffset (1회)
 * 이후: expectedVioYaw = magneticHeading - yawOffset
 *       drift = actualVioYaw - expectedVioYaw
 *       → yawCorrectionRad 보정
 * ```
 * - 보정 주기: 5초마다
 * - 보정률: drift × reliability × 0.05 (센서 신뢰도에 비례)
 * - 임계값: drift > 2° (센서 노이즈 무시)
 *
 * ### 3. X,Z (수평) — 시각 루프 클로저 보정
 * ```
 * 10초마다 키프레임 저장 (임베딩 + VIO 포즈)
 * 재방문 감지 (유사도 > 0.85) → driftXZ 계산
 * → offsetX/Z 보정
 * ```
 * - 보정률: drift × 0.3 (보수적, 임베딩만으로는 정확한 위치 보장 불가)
 * - 임계값: driftDistance > 1.0m, < 10.0m
 *
 * ## 적용 지점
 * HardwareManager.vioPoseListener에서 HeadPoseUpdated 발행 전 호출:
 * ```kotlin
 * val corrected = driftCorrectionManager.applyCorrection(x, y, z, qx, qy, qz, qw)
 * eventBus.publish(HeadPoseUpdated(corrected.x, corrected.y, ...))
 * ```
 *
 * @param eventBus 이벤트 버스 (FloorChange, VisualEmbedding 구독)
 * @param magneticHeadingProvider 자기 방위 제공자
 * @param visualLoopCloser 시각 루프 클로저
 * @param log 로깅 콜백
 *
 * @see MagneticHeadingProvider 자기 센서 래퍼
 * @see VisualLoopCloser 키프레임 기반 루프 클로저
 */
class DriftCorrectionManager(
    private val eventBus: GlobalEventBus,
    private val magneticHeadingProvider: MagneticHeadingProvider,
    private val visualLoopCloser: VisualLoopCloser,
    private val log: (String) -> Unit
) {
    companion object {
        private const val TAG = "DriftCorrection"

        // ── 기압계 Y축 보정 ──
        /** 층 높이 (미터) */
        const val FLOOR_HEIGHT_M = 3.0f
        /** Y축 보정 임계값 (미터). 이하 무시 (머리 움직임 범위) */
        const val BARO_DRIFT_THRESHOLD_M = 1.0f
        /** Y축 보정률 (0-1). 매 적용 시 drift × rate 만큼 보정 */
        const val BARO_CORRECTION_RATE = 0.10f
        /** Y축 보정 주기 (ms) */
        const val BARO_CORRECTION_INTERVAL_MS = 5_000L

        // ── 자기 Yaw 보정 ──
        /** Yaw 보정 임계값 (도). 이하 무시 (센서 노이즈) */
        const val YAW_DRIFT_THRESHOLD_DEG = 2.0f
        /** Yaw 보정률 (0-1). 매 적용 시 drift × rate × reliability 만큼 보정 */
        const val YAW_CORRECTION_RATE = 0.05f
        /** Yaw 보정 주기 (ms) */
        const val YAW_CORRECTION_INTERVAL_MS = 5_000L
        /** Yaw 초기 캘리브레이션 필요 샘플 수 */
        const val YAW_CALIBRATION_SAMPLES = 10

        // ── 루프 클로저 X,Z 보정 ──
        /** XZ 보정률 (보수적 — 임베딩만으로는 ±2m 오차) */
        const val LOOP_CLOSURE_CORRECTION_RATE = 0.30f

        // ── 통계 로깅 ──
        /** 통계 로깅 주기 (ms) */
        const val STATS_LOG_INTERVAL_MS = 60_000L
    }

    // ── 보정 상태 ──
    val state = DriftCorrectionState()

    // ── 기압계 추적 ──
    private var barometricCumulativeFloors = 0
    private var initialVioY: Float? = null  // 첫 VIO Y 기록 (기준점)
    private var lastBaroCheckTime = 0L

    // ── 자기 Yaw 추적 ──
    /** 초기 캘리브레이션: magneticHeading - vioYaw (자기-VIO 오프셋) */
    private var yawCalibrationOffset: Float? = null
    private var yawCalibrationSamples = 0
    private var yawCalibrationSum = 0f
    private var lastYawCheckTime = 0L

    // ── 최신 VIO 포즈 (raw, 보정 전) ──
    @Volatile
    private var lastRawX = 0f
    @Volatile
    private var lastRawY = 0f
    @Volatile
    private var lastRawZ = 0f

    // ── 최신 시각 임베딩 (루프 클로저용) ──
    @Volatile
    private var latestVisualEmbedding: ByteArray? = null

    // ── 최신 SLAM 프레임 (썸네일용) ──
    @Volatile
    private var latestSlamFrame: ByteArray? = null

    // ── 활성 여부 ──
    @Volatile
    var isActive = false
        private set

    // ── 코루틴 ──
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var eventJob: Job? = null
    private var statsJob: Job? = null

    // ── Lifecycle ──

    /**
     * 드리프트 보정 시스템 시작.
     * 1. MagneticHeadingProvider 시작
     * 2. EventBus 구독 (FloorChange, VisualEmbedding)
     * 3. 통계 로깅 루프 시작
     */
    fun start() {
        if (isActive) return
        isActive = true

        // 자기 센서 시작
        magneticHeadingProvider.start()

        // 이벤트 구독
        eventJob = scope.launch {
            // 기압계 FloorChange 구독
            launch {
                eventBus.events.filterIsInstance<XRealEvent.PerceptionEvent.FloorChange>()
                    .collect { onFloorChange(it) }
            }

            // 시각 임베딩 수신 (루프 클로저 키프레임 후보)
            launch {
                eventBus.events.filterIsInstance<XRealEvent.PerceptionEvent.VisualEmbedding>()
                    .collect { onVisualEmbedding(it) }
            }
        }

        // 통계 로깅 루프
        statsJob = scope.launch {
            while (isActive) {
                delay(STATS_LOG_INTERVAL_MS)
                logStats()
            }
        }

        log("DriftCorrectionManager started")
        XRealLogger.impl.i(TAG, "Drift correction system active — baro Y + magnetic yaw + visual loop closure XZ")
    }

    /**
     * 드리프트 보정 시스템 정지.
     */
    fun stop() {
        isActive = false
        eventJob?.cancel()
        statsJob?.cancel()
        magneticHeadingProvider.stop()
        logStats()
        log("DriftCorrectionManager stopped")
    }

    // ── 핵심: 포즈 보정 적용 ──

    /**
     * VIO raw 포즈에 드리프트 보정 적용.
     *
     * HardwareManager.vioPoseListener에서 HeadPoseUpdated 발행 전에 호출.
     * 모든 보정 오프셋을 동기적으로 적용.
     *
     * @param x,y,z VIO raw 위치 (미터)
     * @param qx,qy,qz,qw VIO raw Hamilton 쿼터니언
     * @return 보정된 포즈
     */
    fun applyCorrection(
        x: Float, y: Float, z: Float,
        qx: Float, qy: Float, qz: Float, qw: Float
    ): CorrectedPose {
        // raw 포즈 기록 (보정 계산용)
        lastRawX = x
        lastRawY = y
        lastRawZ = z

        // 첫 VIO Y 기록
        if (initialVioY == null) {
            initialVioY = y
        }

        // 1. 기압계 Y축 보정 업데이트 (주기적)
        val now = System.currentTimeMillis()
        if (now - lastBaroCheckTime >= BARO_CORRECTION_INTERVAL_MS) {
            updateBarometricCorrection(y)
            lastBaroCheckTime = now
        }

        // 2. 자기 Yaw 보정 업데이트 (주기적)
        if (now - lastYawCheckTime >= YAW_CORRECTION_INTERVAL_MS) {
            updateYawCorrection(qx, qy, qz, qw)
            lastYawCheckTime = now
        }

        // 3. 시각 루프 클로저 (키프레임 저장 + 매칭은 비동기, 여기서는 적용만)
        // → onVisualEmbedding()에서 별도 처리

        // ── 보정 적용 ──

        // 위치 보정 (가산)
        val correctedX = x + state.offsetX
        val correctedY = y + state.offsetY
        val correctedZ = z + state.offsetZ

        // Yaw 보정 (쿼터니언 회전)
        val correctedQ = QuaternionUtils.applyYawCorrection(qx, qy, qz, qw, state.yawCorrectionRad)

        return CorrectedPose(
            x = correctedX,
            y = correctedY,
            z = correctedZ,
            qx = correctedQ[0],
            qy = correctedQ[1],
            qz = correctedQ[2],
            qw = correctedQ[3]
        )
    }

    // ── SLAM 프레임 피드 (HardwareManager에서 호출) ──

    /**
     * SLAM 프레임 수신 (루프 클로저 썸네일용).
     * HardwareManager에서 주기적으로 호출 (매 프레임이 아닌, 10초에 1번 정도).
     */
    fun feedSlamFrame(leftFrame: ByteArray) {
        latestSlamFrame = leftFrame
    }

    // ── 기압계 Y축 보정 ──

    private fun onFloorChange(event: XRealEvent.PerceptionEvent.FloorChange) {
        barometricCumulativeFloors = event.cumulativeFloors
        val expectedY = barometricCumulativeFloors * FLOOR_HEIGHT_M

        val rawYDisp = lastRawY - (initialVioY ?: lastRawY)
        val correctedYDisp = rawYDisp + state.offsetY
        val drift = correctedYDisp - expectedY

        if (abs(drift) > BARO_DRIFT_THRESHOLD_M) {
            val correction = -drift * BARO_CORRECTION_RATE * 3f // 플로어 변화 시 강한 보정
            state.offsetY += correction
            state.baroCorrectionsApplied++
            state.totalBaroDriftCorrected += abs(correction)
            state.lastBaroCorrection = System.currentTimeMillis()

            log("Baro Y correction: floor=${barometricCumulativeFloors}, " +
                    "expectedY=${"%.2f".format(expectedY)}, " +
                    "actualY=${"%.2f".format(correctedYDisp)}, " +
                    "drift=${"%.2f".format(drift)}m, " +
                    "correction=${"%.3f".format(correction)}m")
        }
    }

    /**
     * 기압계 기반 주기적 Y축 보정 (층 내 드리프트 감지).
     *
     * FloorChange 이벤트 사이에도 Y축이 드리프트할 수 있음.
     * 기압계가 층 변화 없음을 확인하는 동안 VIO Y가 1m 이상 벗어나면 보정.
     */
    private fun updateBarometricCorrection(rawVioY: Float) {
        val initY = initialVioY ?: return

        // 기압계 기준 예상 Y 변위
        val expectedYDisp = barometricCumulativeFloors * FLOOR_HEIGHT_M

        // VIO 기준 실제 Y 변위 (보정 후)
        val correctedYDisp = (rawVioY - initY) + state.offsetY

        // 드리프트 = 보정 후 Y - 기압 예상 Y
        val drift = correctedYDisp - expectedYDisp

        if (abs(drift) > BARO_DRIFT_THRESHOLD_M) {
            val correction = -drift * BARO_CORRECTION_RATE
            state.offsetY += correction
            state.baroCorrectionsApplied++
            state.totalBaroDriftCorrected += abs(correction)
            state.lastBaroCorrection = System.currentTimeMillis()
        }
    }

    // ── 자기 Yaw 보정 ──

    /**
     * 자기 센서 기반 Yaw 보정.
     *
     * 1. 초기 캘리브레이션 (10샘플): 자기 heading - VIO yaw 오프셋 결정
     * 2. 이후 주기적: 자기 heading - 오프셋 = 예상 VIO yaw, 실제와 차이 = drift
     * 3. drift × reliability × rate 만큼 보정 쿼터니언 회전
     */
    private fun updateYawCorrection(qx: Float, qy: Float, qz: Float, qw: Float) {
        if (!magneticHeadingProvider.isAvailable) return

        val magHeading = magneticHeadingProvider.absoluteHeadingDegrees
        val vioYaw = QuaternionUtils.extractYawDegrees(qx, qy, qz, qw)
        // 보정 후 VIO yaw (현재 보정 적용된 상태)
        val correctedYaw = vioYaw + Math.toDegrees(state.yawCorrectionRad.toDouble()).toFloat()

        // 초기 캘리브레이션 단계
        if (yawCalibrationOffset == null) {
            val diff = QuaternionUtils.angleDifference(magHeading, vioYaw)
            yawCalibrationSum += diff
            yawCalibrationSamples++

            if (yawCalibrationSamples >= YAW_CALIBRATION_SAMPLES) {
                yawCalibrationOffset = yawCalibrationSum / yawCalibrationSamples
                log("Yaw calibration complete: mag-VIO offset = ${"%.1f".format(yawCalibrationOffset)}°")
            }
            return
        }

        // 보정 단계
        val offset = yawCalibrationOffset!!
        val expectedVioYaw = magHeading - offset
        val drift = QuaternionUtils.angleDifference(correctedYaw, expectedVioYaw)

        if (abs(drift) > YAW_DRIFT_THRESHOLD_DEG) {
            val reliability = magneticHeadingProvider.getReliability()
            val correctionDeg = -drift * YAW_CORRECTION_RATE * reliability
            val correctionRad = Math.toRadians(correctionDeg.toDouble()).toFloat()

            state.yawCorrectionRad += correctionRad
            state.yawCorrectionsApplied++
            state.totalYawDriftCorrected += abs(correctionDeg)
            state.lastYawCorrection = System.currentTimeMillis()
        }
    }

    // ── 시각 루프 클로저 ──

    /**
     * VisualEmbedding 이벤트 수신 → 키프레임 저장 + 루프 클로저 감지.
     */
    private fun onVisualEmbedding(event: XRealEvent.PerceptionEvent.VisualEmbedding) {
        latestVisualEmbedding = event.embedding

        val headingDeg = if (magneticHeadingProvider.isAvailable) {
            magneticHeadingProvider.absoluteHeadingDegrees
        } else {
            0f  // 자기 센서 없으면 방위 비교 비활성화
        }

        // 키프레임 저장 + 루프 클로저 감지
        val result = visualLoopCloser.tryAddKeyframeAndDetectLoop(
            embedding = event.embedding,
            rawX = lastRawX,
            rawY = lastRawY,
            rawZ = lastRawZ,
            correctedX = lastRawX + state.offsetX,
            correctedY = lastRawY + state.offsetY,
            correctedZ = lastRawZ + state.offsetZ,
            headingDeg = headingDeg,
            slamFrameBytes = latestSlamFrame
        )

        if (result != null) {
            applyLoopClosureCorrection(result)
        }
    }

    /**
     * 루프 클로저 보정 적용.
     *
     * 감지된 X,Z 드리프트에 보수적 보정률 (30%) 적용.
     * 임베딩만으로는 정확한 위치 일치 보장 불가 → 보수적 접근.
     */
    private fun applyLoopClosureCorrection(result: LoopClosureResult) {
        val correctionX = -result.driftX * LOOP_CLOSURE_CORRECTION_RATE
        val correctionZ = -result.driftZ * LOOP_CLOSURE_CORRECTION_RATE

        state.offsetX += correctionX
        state.offsetZ += correctionZ
        state.loopClosuresDetected++
        state.totalXZDriftCorrected += result.driftDistance * LOOP_CLOSURE_CORRECTION_RATE
        state.lastLoopClosureCorrection = System.currentTimeMillis()

        log("Loop closure correction: " +
                "dX=${"%.2f".format(correctionX)}m, " +
                "dZ=${"%.2f".format(correctionZ)}m, " +
                "total offsets=(X=${"%.2f".format(state.offsetX)}, " +
                "Y=${"%.2f".format(state.offsetY)}, " +
                "Z=${"%.2f".format(state.offsetZ)})")

        // 이벤트 발행 (디버그/HUD용)
        scope.launch {
            eventBus.publish(XRealEvent.PerceptionEvent.DriftCorrectionApplied(
                source = "LOOP_CLOSURE",
                correctionX = correctionX,
                correctionY = 0f,
                correctionZ = correctionZ,
                correctionYawDeg = 0f,
                totalOffsetX = state.offsetX,
                totalOffsetY = state.offsetY,
                totalOffsetZ = state.offsetZ,
                totalYawCorrectionDeg = Math.toDegrees(state.yawCorrectionRad.toDouble()).toFloat(),
                timestamp = System.currentTimeMillis()
            ))
        }
    }

    // ── 통계 ──

    /**
     * 현재 드리프트 보정 통계 조회.
     */
    fun getStats(): DriftStats {
        val initY = initialVioY ?: 0f
        val expectedY = barometricCumulativeFloors * FLOOR_HEIGHT_M
        val currentYDisp = (lastRawY - initY) + state.offsetY
        val yDrift = currentYDisp - expectedY

        val yawOffset = yawCalibrationOffset
        val yawDrift = if (yawOffset != null && magneticHeadingProvider.isAvailable) {
            val magH = magneticHeadingProvider.absoluteHeadingDegrees
            val vioY = QuaternionUtils.extractYawDegrees(0f, 0f, 0f, 1f) // 기본값
            abs(QuaternionUtils.angleDifference(magH - yawOffset, vioY))
        } else 0f

        return DriftStats(
            baroYDrift = yDrift,
            yawDrift = yawDrift,
            totalCorrections = state.baroCorrectionsApplied + state.yawCorrectionsApplied + state.loopClosuresDetected,
            keyframeCount = visualLoopCloser.keyframeCount,
            loopClosuresDetected = state.loopClosuresDetected,
            isBaroActive = initialVioY != null,
            isMagActive = magneticHeadingProvider.isAvailable,
            isLoopClosureActive = visualLoopCloser.keyframeCount > 3
        )
    }

    /**
     * 통계 로깅.
     */
    private fun logStats() {
        val s = state
        XRealLogger.impl.d(TAG, "DriftCorrection stats: " +
                "offsets=(X=${"%.3f".format(s.offsetX)}, Y=${"%.3f".format(s.offsetY)}, Z=${"%.3f".format(s.offsetZ)})m, " +
                "yaw=${"%.1f".format(Math.toDegrees(s.yawCorrectionRad.toDouble()))}°, " +
                "baro=${s.baroCorrectionsApplied} fixes/${s.totalBaroDriftCorrected.let { "%.2f".format(it) }}m, " +
                "yaw=${s.yawCorrectionsApplied} fixes/${s.totalYawDriftCorrected.let { "%.1f".format(it) }}°, " +
                "loops=${s.loopClosuresDetected}, " +
                "keyframes=${visualLoopCloser.keyframeCount}, " +
                "mag=${if (magneticHeadingProvider.isAvailable) "${"%.0f".format(magneticHeadingProvider.absoluteHeadingDegrees)}°" else "N/A"}")
    }

    /**
     * 보정 상태 리셋 (캘리브레이션 초기화).
     */
    fun reset() {
        state.offsetX = 0f
        state.offsetY = 0f
        state.offsetZ = 0f
        state.yawCorrectionRad = 0f
        initialVioY = null
        yawCalibrationOffset = null
        yawCalibrationSamples = 0
        yawCalibrationSum = 0f
        barometricCumulativeFloors = 0
        visualLoopCloser.clear()
        log("DriftCorrectionManager reset")
    }
}
