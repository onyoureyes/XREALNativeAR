package com.xreal.nativear

import android.util.Log
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.GestureType
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * GestureManager: IMU 기반 머리 제스처 감지 + 멀티탭 제스처.
 *
 * ## 제스처 감지 원리
 *
 * ### NOD (고개 끄덕임) — "네" 의미
 * - 피치(pitch) 축의 **방향 전환(reversal)** 패턴 감지
 * - 아래→위 또는 위→아래로 확실한 진폭(amplitude) 필요
 * - 최소 1회 방향 전환, 300-1000ms 이내 완료
 *
 * ### SHAKE (고개 젓기) — "아니오" 의미
 * - 요(yaw) 축의 **방향 전환** 패턴 감지
 * - 좌→우 또는 우→좌로 확실한 진폭 필요
 * - 최소 2회 방향 전환 (좌→우→좌), 400-1200ms 이내 완료
 *
 * ### HeadStability (머리 안정성) — 운동/걷기 분석용
 * - 100ms 윈도우의 pitch/yaw 분산 + lateralBalance 계산
 * - CadenceSystem, RunningCoach 등에서 소비
 *
 * ### 오탐(False Positive) 방지
 * 1. **속도 게이트**: 피크 각속도가 최소 임계값(60°/s) 이상이어야 의도적 제스처
 * 2. **정적(Static) 게이트**: 제스처 시작 전 200ms 동안 머리가 충분히 안정적이어야 함
 * 3. **방향 전환 카운트**: NOD ≥ 1, SHAKE ≥ 2 (실수로 한 번 고개 돌리면 무시)
 * 4. **쿨다운**: NOD 1.5초, SHAKE 2초
 */
class GestureManager(private val eventBus: GlobalEventBus) {
    private val TAG = "GestureManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Multi-tap ──
    private var tapCount = 0
    private var lastTapTime = 0L
    private val TAP_TIMEOUT = 500L

    // ══════════════════════════════════════════════
    //  IMU Head Gesture Detection
    // ══════════════════════════════════════════════

    companion object {
        // ── 시간 윈도우 ──
        private const val HISTORY_SIZE = 80          // 80 samples ≈ ~1.3s at 60Hz / 80ms at 1kHz
        private const val STABILITY_WINDOW_MS = 200L // 제스처 시작 전 안정성 검증 윈도우

        // ── NOD 파라미터 ──
        private const val NOD_MIN_AMPLITUDE_DEG = 10.0f   // 피치 최소 진폭
        private const val NOD_MIN_REVERSALS = 1            // 최소 방향 전환 횟수
        private const val NOD_WINDOW_MIN_MS = 200L         // 패턴 최소 지속시간
        private const val NOD_WINDOW_MAX_MS = 1000L        // 패턴 최대 지속시간
        private const val NOD_COOLDOWN_MS = 1500L          // 감지 후 쿨다운

        // ── SHAKE 파라미터 ──
        private const val SHAKE_MIN_AMPLITUDE_DEG = 12.0f  // 요 최소 진폭
        private const val SHAKE_MIN_REVERSALS = 2           // 최소 방향 전환 (좌→우→좌)
        private const val SHAKE_WINDOW_MIN_MS = 300L
        private const val SHAKE_WINDOW_MAX_MS = 1200L
        private const val SHAKE_COOLDOWN_MS = 2000L

        // ── TILT 파라미터 (궁금증 제스처 — 롤 축) ──
        private const val TILT_MIN_AMPLITUDE_DEG = 8.0f    // 롤 최소 진폭 (고개 갸우뚱)
        private const val TILT_WINDOW_MIN_MS = 200L         // 최소 유지 시간
        private const val TILT_WINDOW_MAX_MS = 1500L        // 최대 윈도우
        private const val TILT_COOLDOWN_MS = 3000L           // 3초 쿨다운 (회의 중 빈번 방지)
        private const val TILT_MIN_VELOCITY_DEG_S = 25.0f   // 의도적 틸트 최소 각속도

        // ── 공통 임계값 ──
        private const val MIN_ANGULAR_VELOCITY_DEG_S = 50.0f  // 의도적 제스처 최소 각속도
        private const val STABLE_VARIANCE_THRESHOLD = 4.0f     // 안정 상태 분산 임계 (°²)

        // ── HeadStability 발행 간격 ──
        private const val STABILITY_PUBLISH_INTERVAL_MS = 500L
    }

    // ── 오일러 각도 히스토리 (ring buffer) ──
    data class PoseSample(
        val pitch: Float,    // 피치 (°) — 위아래
        val yaw: Float,      // 요 (°) — 좌우
        val roll: Float,     // 롤 (°)
        val timestamp: Long
    )

    private val poseHistory = ArrayDeque<PoseSample>(HISTORY_SIZE + 10)

    // ── 방향 전환 추적 ──
    private var lastPitchDirection = 0  // +1 = up, -1 = down, 0 = initial
    private var lastYawDirection = 0

    // NOD 상태
    private var nodState = GestureState.IDLE
    private var nodStartTime = 0L
    private var nodReversalCount = 0
    private var nodPeakAmplitude = 0.0f
    private var nodBasePitch = 0.0f
    private var nodPeakVelocity = 0.0f

    // SHAKE 상태
    private var shakeState = GestureState.IDLE
    private var shakeStartTime = 0L
    private var shakeReversalCount = 0
    private var shakePeakAmplitude = 0.0f
    private var shakeBaseYaw = 0.0f
    private var shakePeakVelocity = 0.0f

    // TILT 상태
    private var tiltState = GestureState.IDLE
    private var tiltStartTime = 0L
    private var tiltPeakAmplitude = 0.0f
    private var tiltBaseRoll = 0.0f
    private var tiltPeakVelocity = 0.0f

    // ── 쿨다운 ──
    private var lastNodDetected = 0L
    private var lastShakeDetected = 0L
    private var lastTiltDetected = 0L
    private var lastStabilityPublish = 0L

    // ── 이전 값 (미분용) ──
    private var prevPitch = 0.0f
    private var prevYaw = 0.0f
    private var prevRoll = 0.0f
    private var prevTimestamp = 0L
    private var isInitialized = false

    private enum class GestureState {
        IDLE,       // 대기 — 안정 상태
        DETECTING,  // 움직임 시작됨 — 패턴 수집 중
        COMPLETED   // 제스처 확인 → publish 후 쿨다운
    }

    init {
        subscribeToEvents()
        Log.i(TAG, "GestureManager initialized — NOD/SHAKE temporal pattern detection active")
    }

    private fun subscribeToEvents() {
        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is XRealEvent.PerceptionEvent.HeadPoseUpdated -> {
                        processHeadPose(event.qx, event.qy, event.qz, event.qw)
                    }
                    else -> {}
                }
            }
        }
    }

    // ══════════════════════════════════════════════
    //  Core Processing
    // ══════════════════════════════════════════════

    /**
     * 쿼터니언 → 오일러 변환 + 패턴 감지.
     * HardwareManager에서 1kHz (또는 VIO에서 30Hz)로 호출됨.
     */
    fun processHeadPose(qx: Float, qy: Float, qz: Float, qw: Float, timestamp: Long = System.currentTimeMillis()) {
        // ── 쿼터니언 → 오일러 ──
        val sinPitch = 2.0 * (qw * qy - qx * qz)
        val pitch = Math.toDegrees(
            if (Math.abs(sinPitch) >= 1.0) Math.copySign(Math.PI / 2, sinPitch)
            else Math.asin(sinPitch)
        ).toFloat()
        val yaw = Math.toDegrees(
            Math.atan2(2.0 * (qw * qz + qx * qy), 1.0 - 2.0 * (qy * qy + qz * qz))
        ).toFloat()
        val roll = Math.toDegrees(
            Math.atan2(2.0 * (qw * qx + qy * qz), 1.0 - 2.0 * (qx * qx + qy * qy))
        ).toFloat()

        val now = if (timestamp > 0) timestamp else System.currentTimeMillis()

        // ── 히스토리에 추가 ──
        val sample = PoseSample(pitch, yaw, roll, now)
        poseHistory.addLast(sample)
        while (poseHistory.size > HISTORY_SIZE) poseHistory.removeFirst()

        if (!isInitialized) {
            prevPitch = pitch
            prevYaw = yaw
            prevRoll = roll
            prevTimestamp = now
            isInitialized = true
            return
        }

        // ── 각속도 계산 ──
        val dt = (now - prevTimestamp).coerceAtLeast(1L) / 1000.0f  // seconds
        val pitchVelocity = (pitch - prevPitch) / dt  // °/s
        val yawVelocity = (yaw - prevYaw) / dt        // °/s
        val rollVelocity = (roll - prevRoll) / dt     // °/s

        // ── NOD 감지 ──
        detectNod(pitch, pitchVelocity, now)

        // ── SHAKE 감지 ──
        detectShake(yaw, yawVelocity, now)

        // ── TILT 감지 (궁금증 — 롤 축) ──
        detectTilt(roll, rollVelocity, now)

        // ── HeadStability 발행 ──
        if (now - lastStabilityPublish >= STABILITY_PUBLISH_INTERVAL_MS && poseHistory.size >= 10) {
            publishStability(now)
            lastStabilityPublish = now
        }

        prevPitch = pitch
        prevYaw = yaw
        prevRoll = roll
        prevTimestamp = now
    }

    // ══════════════════════════════════════════════
    //  NOD Detection (피치 축 패턴)
    // ══════════════════════════════════════════════

    private fun detectNod(pitch: Float, pitchVelocity: Float, now: Long) {
        // 쿨다운 체크
        if (now - lastNodDetected < NOD_COOLDOWN_MS) return

        val absPitchVel = Math.abs(pitchVelocity)

        when (nodState) {
            GestureState.IDLE -> {
                // 의도적 피치 움직임 감지 → DETECTING으로 전환
                if (absPitchVel > MIN_ANGULAR_VELOCITY_DEG_S && wasStableBefore(now, isPitch = true)) {
                    nodState = GestureState.DETECTING
                    nodStartTime = now
                    nodReversalCount = 0
                    nodPeakAmplitude = 0.0f
                    nodBasePitch = pitch
                    nodPeakVelocity = absPitchVel
                    lastPitchDirection = if (pitchVelocity > 0) 1 else -1
                }
            }

            GestureState.DETECTING -> {
                val elapsed = now - nodStartTime

                // 타임아웃 → 리셋
                if (elapsed > NOD_WINDOW_MAX_MS) {
                    nodState = GestureState.IDLE
                    return
                }

                // 진폭 업데이트
                val amplitude = Math.abs(pitch - nodBasePitch)
                if (amplitude > nodPeakAmplitude) nodPeakAmplitude = amplitude
                if (absPitchVel > nodPeakVelocity) nodPeakVelocity = absPitchVel

                // 방향 전환 감지
                val currentDir = when {
                    pitchVelocity > MIN_ANGULAR_VELOCITY_DEG_S * 0.3f -> 1
                    pitchVelocity < -MIN_ANGULAR_VELOCITY_DEG_S * 0.3f -> -1
                    else -> lastPitchDirection  // 느린 구간은 이전 방향 유지
                }
                if (currentDir != 0 && currentDir != lastPitchDirection && lastPitchDirection != 0) {
                    nodReversalCount++
                }
                if (currentDir != 0) lastPitchDirection = currentDir

                // 제스처 완성 체크
                if (elapsed >= NOD_WINDOW_MIN_MS &&
                    nodReversalCount >= NOD_MIN_REVERSALS &&
                    nodPeakAmplitude >= NOD_MIN_AMPLITUDE_DEG) {

                    // 확인: 원위치 복귀 (제스처 끝에 속도 감소)
                    if (absPitchVel < nodPeakVelocity * 0.5f) {
                        Log.i(TAG, "✅ NOD detected | amp=%.1f° rev=%d vel=%.0f°/s elapsed=%dms"
                            .format(nodPeakAmplitude, nodReversalCount, nodPeakVelocity, elapsed))
                        publishGesture(GestureType.NOD)
                        lastNodDetected = now
                        nodState = GestureState.IDLE
                    }
                }
            }

            GestureState.COMPLETED -> {
                nodState = GestureState.IDLE
            }
        }
    }

    // ══════════════════════════════════════════════
    //  SHAKE Detection (요 축 패턴)
    // ══════════════════════════════════════════════

    private fun detectShake(yaw: Float, yawVelocity: Float, now: Long) {
        // 쿨다운 체크
        if (now - lastShakeDetected < SHAKE_COOLDOWN_MS) return

        val absYawVel = Math.abs(yawVelocity)

        when (shakeState) {
            GestureState.IDLE -> {
                if (absYawVel > MIN_ANGULAR_VELOCITY_DEG_S && wasStableBefore(now, isPitch = false)) {
                    shakeState = GestureState.DETECTING
                    shakeStartTime = now
                    shakeReversalCount = 0
                    shakePeakAmplitude = 0.0f
                    shakeBaseYaw = yaw
                    shakePeakVelocity = absYawVel
                    lastYawDirection = if (yawVelocity > 0) 1 else -1
                }
            }

            GestureState.DETECTING -> {
                val elapsed = now - shakeStartTime

                if (elapsed > SHAKE_WINDOW_MAX_MS) {
                    shakeState = GestureState.IDLE
                    return
                }

                val amplitude = Math.abs(yaw - shakeBaseYaw)
                if (amplitude > shakePeakAmplitude) shakePeakAmplitude = amplitude
                if (absYawVel > shakePeakVelocity) shakePeakVelocity = absYawVel

                // 방향 전환 감지 — 요 축은 래핑(wrapping) 주의
                val currentDir = when {
                    yawVelocity > MIN_ANGULAR_VELOCITY_DEG_S * 0.3f -> 1
                    yawVelocity < -MIN_ANGULAR_VELOCITY_DEG_S * 0.3f -> -1
                    else -> lastYawDirection
                }
                if (currentDir != 0 && currentDir != lastYawDirection && lastYawDirection != 0) {
                    shakeReversalCount++
                }
                if (currentDir != 0) lastYawDirection = currentDir

                // SHAKE = 최소 2회 방향 전환 (좌→우→좌 또는 우→좌→우)
                if (elapsed >= SHAKE_WINDOW_MIN_MS &&
                    shakeReversalCount >= SHAKE_MIN_REVERSALS &&
                    shakePeakAmplitude >= SHAKE_MIN_AMPLITUDE_DEG) {

                    if (absYawVel < shakePeakVelocity * 0.5f) {
                        Log.i(TAG, "✅ SHAKE detected | amp=%.1f° rev=%d vel=%.0f°/s elapsed=%dms"
                            .format(shakePeakAmplitude, shakeReversalCount, shakePeakVelocity, elapsed))
                        publishGesture(GestureType.SHAKE)
                        lastShakeDetected = now
                        shakeState = GestureState.IDLE
                    }
                }
            }

            GestureState.COMPLETED -> {
                shakeState = GestureState.IDLE
            }
        }
    }

    // ══════════════════════════════════════════════
    //  TILT Detection (롤 축 — 궁금증 제스처)
    // ══════════════════════════════════════════════

    /**
     * TILT: 고개를 한쪽으로 기울이는 "갸우뚱" 제스처.
     * NOD/SHAKE와 다르게 방향 전환(reversal) 불필요 — 한쪽 기울임 후 유지.
     * 회의 중 "궁금할 때" 트리거로 사용.
     */
    private fun detectTilt(roll: Float, rollVelocity: Float, now: Long) {
        // 쿨다운 체크
        if (now - lastTiltDetected < TILT_COOLDOWN_MS) return

        val absRollVel = Math.abs(rollVelocity)

        when (tiltState) {
            GestureState.IDLE -> {
                // 의도적 롤 움직임 감지 → DETECTING
                if (absRollVel > TILT_MIN_VELOCITY_DEG_S && wasRollStableBefore(now)) {
                    tiltState = GestureState.DETECTING
                    tiltStartTime = now
                    tiltPeakAmplitude = 0.0f
                    tiltBaseRoll = roll
                    tiltPeakVelocity = absRollVel
                }
            }

            GestureState.DETECTING -> {
                val elapsed = now - tiltStartTime

                // 타임아웃 → 리셋
                if (elapsed > TILT_WINDOW_MAX_MS) {
                    tiltState = GestureState.IDLE
                    return
                }

                // 진폭 업데이트
                val amplitude = Math.abs(roll - tiltBaseRoll)
                if (amplitude > tiltPeakAmplitude) tiltPeakAmplitude = amplitude
                if (absRollVel > tiltPeakVelocity) tiltPeakVelocity = absRollVel

                // TILT 완성 조건: 충분한 기울임 + 최소 유지시간 + 속도 감소 (기울인 채 유지)
                if (elapsed >= TILT_WINDOW_MIN_MS &&
                    tiltPeakAmplitude >= TILT_MIN_AMPLITUDE_DEG &&
                    absRollVel < tiltPeakVelocity * 0.4f) {

                    Log.i(TAG, "✅ TILT detected | amp=%.1f° vel=%.0f°/s elapsed=%dms"
                        .format(tiltPeakAmplitude, tiltPeakVelocity, elapsed))
                    publishGesture(GestureType.TILT)
                    lastTiltDetected = now
                    tiltState = GestureState.IDLE
                }
            }

            GestureState.COMPLETED -> {
                tiltState = GestureState.IDLE
            }
        }
    }

    /**
     * 틸트 시작 전 롤 축 안정성 검증.
     */
    private fun wasRollStableBefore(now: Long): Boolean {
        val cutoff = now - STABILITY_WINDOW_MS
        val recentSamples = poseHistory.filter { it.timestamp in cutoff..now }
        if (recentSamples.size < 3) return true

        val values = recentSamples.map { it.roll }
        val mean = values.average().toFloat()
        val variance = values.map { (it - mean) * (it - mean) }.average().toFloat()
        return variance < STABLE_VARIANCE_THRESHOLD
    }

    // ══════════════════════════════════════════════
    //  Stability Analysis
    // ══════════════════════════════════════════════

    /**
     * 제스처 시작 전 안정 상태 검증.
     * 최근 STABILITY_WINDOW_MS 동안 분산이 낮아야 의도적 제스처로 인정.
     * 걷기 중 자연스러운 머리 흔들림을 필터링.
     */
    private fun wasStableBefore(now: Long, isPitch: Boolean): Boolean {
        val cutoff = now - STABILITY_WINDOW_MS
        val recentSamples = poseHistory.filter { it.timestamp in cutoff..now }
        if (recentSamples.size < 3) return true  // 데이터 부족 시 통과 (최초)

        val values = if (isPitch) recentSamples.map { it.pitch } else recentSamples.map { it.yaw }
        val mean = values.average().toFloat()
        val variance = values.map { (it - mean) * (it - mean) }.average().toFloat()

        return variance < STABLE_VARIANCE_THRESHOLD
    }

    /**
     * HeadStability 이벤트 발행.
     * 최근 500ms의 pitch/yaw 분산 + 좌우 균형 + 종합 점수.
     */
    private fun publishStability(now: Long) {
        val cutoff = now - 500L
        val recent = poseHistory.filter { it.timestamp > cutoff }
        if (recent.size < 5) return

        val pitches = recent.map { it.pitch }
        val yaws = recent.map { it.yaw }
        val rolls = recent.map { it.roll }

        val pitchMean = pitches.average().toFloat()
        val yawMean = yaws.average().toFloat()
        val pitchVar = pitches.map { (it - pitchMean) * (it - pitchMean) }.average().toFloat()
        val yawVar = yaws.map { (it - yawMean) * (it - yawMean) }.average().toFloat()

        // 좌우 균형 (-1.0 = 왼쪽 기울기, +1.0 = 오른쪽 기울기)
        val rollMean = rolls.average().toFloat()
        val lateralBalance = (rollMean / 30.0f).coerceIn(-1.0f, 1.0f)

        // 종합 안정성 점수 (0-100, 높을수록 안정)
        val totalVar = pitchVar + yawVar
        val stabilityScore = (100.0f - totalVar * 5.0f).coerceIn(0.0f, 100.0f)

        scope.launch {
            eventBus.publish(XRealEvent.PerceptionEvent.HeadStability(
                pitchVariance = pitchVar,
                yawVariance = yawVar,
                lateralBalance = lateralBalance,
                stabilityScore = stabilityScore,
                timestamp = now
            ))
        }
    }

    // ══════════════════════════════════════════════
    //  Multi-Tap Gesture
    // ══════════════════════════════════════════════

    fun onTap() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastTapTime > TAP_TIMEOUT) {
            tapCount = 1
        } else {
            tapCount++
        }
        lastTapTime = currentTime

        Log.d(TAG, "Tap Count: $tapCount")
        when (tapCount) {
            2 -> publishGesture(GestureType.DOUBLE_TAP)
            3 -> publishGesture(GestureType.TRIPLE_TAP)
            4 -> publishGesture(GestureType.QUAD_TAP)
        }
    }

    // ══════════════════════════════════════════════
    //  Event Publishing
    // ══════════════════════════════════════════════

    private fun publishGesture(type: GestureType) {
        scope.launch {
            eventBus.publish(XRealEvent.InputEvent.Gesture(type))
            Log.d(TAG, "Published Gesture Event: $type")
        }
    }

    /**
     * 디버그용: 현재 제스처 감지 상태.
     */
    fun getDebugState(): String {
        return buildString {
            append("NOD: $nodState (rev=$nodReversalCount amp=%.1f°) ".format(nodPeakAmplitude))
            append("SHAKE: $shakeState (rev=$shakeReversalCount amp=%.1f°) ".format(shakePeakAmplitude))
            append("TILT: $tiltState (amp=%.1f°) ".format(tiltPeakAmplitude))
            append("history=${poseHistory.size}")
        }
    }
}
