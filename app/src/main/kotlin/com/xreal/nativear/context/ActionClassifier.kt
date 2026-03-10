package com.xreal.nativear.context

import android.util.Log
import com.xreal.nativear.PoseKeypoint
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.policy.PolicyReader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ActionClassifier — 스켈레톤 시퀀스 기반 행동 분류 (ST-GCN + 규칙 기반 폴백).
 *
 * ## 동작 흐름
 * 1. PoseDetected 이벤트 수신 → 스켈레톤 프레임 버퍼링
 * 2. 버퍼가 WINDOW_SIZE에 도달하면 분류 실행
 * 3. ST-GCN TFLite 모델 있으면 모델 추론, 없으면 규칙 기반 폴백
 * 4. ActionClassified 이벤트 발행
 *
 * ## 행동 클래스 (10개)
 * SITTING, STANDING, WALKING, RUNNING, REACHING, WRITING,
 * BENDING, WAVING, EATING, EXERCISING
 */
class ActionClassifier(
    private val eventBus: GlobalEventBus,
    private val scope: CoroutineScope,
    private val modelPath: String? = null  // ST-GCN TFLite 모델 경로 (없으면 규칙 기반)
) {
    companion object {
        private const val TAG = "ActionClassifier"

        // CenterNet 17 키포인트 인덱스
        const val NOSE = 0
        const val NECK = 1
        const val L_SHOULDER = 2; const val R_SHOULDER = 3
        const val L_ELBOW = 4;    const val R_ELBOW = 5
        const val L_WRIST = 6;    const val R_WRIST = 7
        const val L_HIP = 8;      const val R_HIP = 9
        const val L_KNEE = 10;    const val R_KNEE = 11
        const val L_ANKLE = 12;   const val R_ANKLE = 13
        const val L_EYE = 14;     const val R_EYE = 15
        const val EAR = 16

        val ACTION_LABELS = listOf(
            "SITTING", "STANDING", "WALKING", "RUNNING", "REACHING",
            "WRITING", "BENDING", "WAVING", "EATING", "EXERCISING"
        )

        private val WINDOW_SIZE: Int get() =
            PolicyReader.getInt("action.window_size", 15)  // 15프레임 (~0.5초 @30fps)
        private val STRIDE: Int get() =
            PolicyReader.getInt("action.stride", 5)        // 5프레임마다 분류
        private val MIN_CONFIDENCE: Float get() =
            PolicyReader.getFloat("action.min_confidence", 0.3f)
        private val MIN_KEYPOINT_SCORE: Float get() =
            PolicyReader.getFloat("action.min_keypoint_score", 0.2f)
    }

    // 스켈레톤 프레임 버퍼 (WINDOW_SIZE 크기 순환)
    private val frameBuffer = ArrayDeque<List<PoseKeypoint>>(WINDOW_SIZE + 5)
    private var frameCount = 0
    private var collectJob: Job? = null

    private val _currentAction = MutableStateFlow("UNKNOWN")
    val currentAction: StateFlow<String> = _currentAction.asStateFlow()

    private var previousAction: String? = null
    private var interpreter: Interpreter? = null

    fun start() {
        Log.i(TAG, "ActionClassifier 시작 (window=$WINDOW_SIZE, stride=$STRIDE)")

        // ST-GCN 모델 로드 시도
        loadModel()

        // PoseDetected 이벤트 구독
        collectJob = scope.launch(Dispatchers.Default) {
            eventBus.events.collect { event ->
                try {
                    if (event is XRealEvent.PerceptionEvent.PoseDetected) {
                        onPoseDetected(event.keypoints)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "포즈 처리 오류 (루프 유지됨): ${e.message}", e)
                }
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        interpreter?.close()
        interpreter = null
        frameBuffer.clear()
        Log.i(TAG, "ActionClassifier 중지")
    }

    private fun loadModel() {
        val path = modelPath ?: return
        try {
            val file = File(path)
            if (file.exists()) {
                interpreter = Interpreter(file)
                Log.i(TAG, "ST-GCN 모델 로드: $path")
            } else {
                Log.d(TAG, "ST-GCN 모델 없음 → 규칙 기반 폴백")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ST-GCN 모델 로드 실패: ${e.message}")
        }
    }

    private fun onPoseDetected(keypoints: List<PoseKeypoint>) {
        // 유효 키포인트 필터
        val validCount = keypoints.count { it.score >= MIN_KEYPOINT_SCORE }
        if (validCount < 5) return  // 키포인트가 너무 적으면 무시

        frameBuffer.addLast(keypoints)
        if (frameBuffer.size > WINDOW_SIZE) {
            frameBuffer.removeFirst()
        }
        frameCount++

        // STRIDE마다 분류 실행
        if (frameCount % STRIDE == 0 && frameBuffer.size >= WINDOW_SIZE) {
            val frames = frameBuffer.toList()
            val result = classifyAction(frames)

            if (result.second >= MIN_CONFIDENCE && result.first != _currentAction.value) {
                previousAction = _currentAction.value
                _currentAction.value = result.first

                eventBus.publish(
                    XRealEvent.PerceptionEvent.ActionClassified(
                        action = result.first,
                        confidence = result.second,
                        previousAction = previousAction
                    )
                )
                Log.d(TAG, "행동: ${previousAction ?: "?"} → ${result.first} (${String.format("%.2f", result.second)})")
            }
        }
    }

    /**
     * 프레임 시퀀스 → (action, confidence).
     * ST-GCN 모델이 있으면 모델 추론, 없으면 규칙 기반.
     */
    private fun classifyAction(frames: List<List<PoseKeypoint>>): Pair<String, Float> {
        return if (interpreter != null) {
            classifyWithModel(frames)
        } else {
            classifyRuleBased(frames)
        }
    }

    // ─── ST-GCN TFLite 추론 ───

    private fun classifyWithModel(frames: List<List<PoseKeypoint>>): Pair<String, Float> {
        val interp = interpreter ?: return classifyRuleBased(frames)
        return try {
            // 입력: [1, WINDOW_SIZE, 17, 2] (x,y 좌표, 정규화)
            val input = ByteBuffer.allocateDirect(1 * WINDOW_SIZE * 17 * 2 * 4)
            input.order(ByteOrder.nativeOrder())

            for (frame in frames.takeLast(WINDOW_SIZE)) {
                for (kpIdx in 0..16) {
                    val kp = frame.getOrNull(kpIdx)
                    input.putFloat(kp?.x ?: 0f)
                    input.putFloat(kp?.y ?: 0f)
                }
            }
            input.rewind()

            // 출력: [1, NUM_CLASSES]
            val output = Array(1) { FloatArray(ACTION_LABELS.size) }
            interp.run(input, output)

            val scores = output[0]
            val maxIdx = scores.indices.maxByOrNull { scores[it] } ?: 0
            val maxScore = scores[maxIdx]

            // softmax 정규화
            val expScores = scores.map { kotlin.math.exp((it - maxScore).toDouble()).toFloat() }
            val sumExp = expScores.sum()
            val confidence = expScores[maxIdx] / sumExp

            ACTION_LABELS[maxIdx] to confidence
        } catch (e: Exception) {
            Log.w(TAG, "ST-GCN 추론 실패 → 규칙 기반 폴백: ${e.message}")
            classifyRuleBased(frames)
        }
    }

    // ─── 규칙 기반 분류 (모델 없을 때 폴백) ───

    private fun classifyRuleBased(frames: List<List<PoseKeypoint>>): Pair<String, Float> {
        if (frames.size < 3) return "UNKNOWN" to 0.0f

        val latest = frames.last()
        val features = extractRuleFeatures(frames)

        // 1. WAVING: 손목이 머리 위 + 좌우 진동
        if (features.wristAboveHead && features.wristOscillation > 15f) {
            return "WAVING" to 0.75f
        }

        // 2. RUNNING: 큰 발목 이동 + 빠른 움직임
        if (features.ankleDisplacement > 30f && features.overallMotion > 25f) {
            return "RUNNING" to 0.70f
        }

        // 3. WALKING: 중간 발목 이동 + 중간 움직임
        if (features.ankleDisplacement > 10f && features.overallMotion > 8f) {
            return "WALKING" to 0.65f
        }

        // 4. REACHING: 한쪽 팔이 크게 뻗어 있음
        if (features.armExtension > 0.8f && features.overallMotion < 10f) {
            return "REACHING" to 0.60f
        }

        // 5. BENDING: 머리가 엉덩이 아래로 내려감
        if (features.headBelowHip) {
            return "BENDING" to 0.65f
        }

        // 6. WRITING: 손목이 몸 앞 + 미세 움직임
        if (features.wristNearBody && features.wristMicroMotion > 2f && features.overallMotion < 5f) {
            return "WRITING" to 0.55f
        }

        // 7. EATING: 손이 얼굴 근처 + 반복 동작
        if (features.handNearFace && features.handFaceOscillation > 5f) {
            return "EATING" to 0.50f
        }

        // 8. EXERCISING: 높은 전체 움직임 + 비이동
        if (features.overallMotion > 20f && features.ankleDisplacement < 15f) {
            return "EXERCISING" to 0.55f
        }

        // 9. SITTING: 엉덩이-무릎 각도가 작음 + 움직임 적음
        if (features.hipKneeAngleLow && features.overallMotion < 5f) {
            return "SITTING" to 0.60f
        }

        // 10. STANDING: 기본 (직립 + 움직임 적음)
        if (features.overallMotion < 5f) {
            return "STANDING" to 0.45f
        }

        return "UNKNOWN" to 0.2f
    }

    // ─── 규칙 기반 피처 추출 ───

    private data class RuleFeatures(
        val overallMotion: Float,        // 전체 키포인트 평균 이동량
        val ankleDisplacement: Float,    // 발목 누적 이동량
        val wristAboveHead: Boolean,     // 손목이 머리 위
        val wristOscillation: Float,     // 손목 좌우 진동량
        val armExtension: Float,         // 팔 뻗은 정도 (0~1)
        val headBelowHip: Boolean,       // 머리가 엉덩이 아래
        val wristNearBody: Boolean,      // 손목이 몸통 앞
        val wristMicroMotion: Float,     // 손목 미세 움직임
        val handNearFace: Boolean,       // 손이 얼굴 근처
        val handFaceOscillation: Float,  // 손-얼굴 거리 변동
        val hipKneeAngleLow: Boolean     // 앉은 자세 (무릎 굽힘)
    )

    private fun extractRuleFeatures(frames: List<List<PoseKeypoint>>): RuleFeatures {
        val latest = frames.last()
        val windowFrames = frames.takeLast(WINDOW_SIZE)

        // 키포인트 helper
        fun kp(frame: List<PoseKeypoint>, id: Int): PoseKeypoint? =
            frame.firstOrNull { it.id == id && it.score >= MIN_KEYPOINT_SCORE }

        // 전체 키포인트 평균 프레임간 이동량
        var totalMotion = 0f
        var motionCount = 0
        for (i in 1 until windowFrames.size) {
            val prev = windowFrames[i - 1]
            val curr = windowFrames[i]
            for (kpId in 0..16) {
                val p = kp(prev, kpId) ?: continue
                val c = kp(curr, kpId) ?: continue
                totalMotion += dist(p.x, p.y, c.x, c.y)
                motionCount++
            }
        }
        val overallMotion = if (motionCount > 0) totalMotion / motionCount * (windowFrames.size - 1) else 0f

        // 발목 누적 이동량
        var ankleDisp = 0f
        for (i in 1 until windowFrames.size) {
            for (ankleId in listOf(L_ANKLE, R_ANKLE)) {
                val p = kp(windowFrames[i - 1], ankleId) ?: continue
                val c = kp(windowFrames[i], ankleId) ?: continue
                ankleDisp += dist(p.x, p.y, c.x, c.y)
            }
        }

        // 손목 위치 (latest)
        val lWrist = kp(latest, L_WRIST)
        val rWrist = kp(latest, R_WRIST)
        val nose = kp(latest, NOSE)
        val lHip = kp(latest, L_HIP)
        val rHip = kp(latest, R_HIP)

        val hipY = if (lHip != null && rHip != null) (lHip.y + rHip.y) / 2 else Float.MAX_VALUE
        val noseY = nose?.y ?: 0f

        // 손목이 머리 위인지 (y좌표가 작을수록 위)
        val wristAboveHead = (lWrist != null && nose != null && lWrist.y < nose.y - 20f) ||
                             (rWrist != null && nose != null && rWrist.y < nose.y - 20f)

        // 손목 좌우 진동량
        var wristOsc = 0f
        for (i in 1 until windowFrames.size) {
            for (wId in listOf(L_WRIST, R_WRIST)) {
                val p = kp(windowFrames[i - 1], wId) ?: continue
                val c = kp(windowFrames[i], wId) ?: continue
                wristOsc += abs(c.x - p.x)
            }
        }

        // 팔 뻗은 정도 (어깨-손목 거리 / 어깨-엉덩이 거리)
        val lShoulder = kp(latest, L_SHOULDER)
        val rShoulder = kp(latest, R_SHOULDER)
        var maxArmExt = 0f
        if (lShoulder != null && lWrist != null && lHip != null) {
            val shoulderHip = dist(lShoulder.x, lShoulder.y, lHip.x, lHip.y).coerceAtLeast(1f)
            maxArmExt = maxOf(maxArmExt, dist(lShoulder.x, lShoulder.y, lWrist.x, lWrist.y) / shoulderHip)
        }
        if (rShoulder != null && rWrist != null && rHip != null) {
            val shoulderHip = dist(rShoulder.x, rShoulder.y, rHip.x, rHip.y).coerceAtLeast(1f)
            maxArmExt = maxOf(maxArmExt, dist(rShoulder.x, rShoulder.y, rWrist.x, rWrist.y) / shoulderHip)
        }

        // 머리가 엉덩이 아래
        val headBelowHip = nose != null && noseY > hipY + 20f

        // 손목이 몸통 앞 (어깨와 엉덩이 사이 x 범위 내)
        val bodyMinX = listOfNotNull(lShoulder?.x, rShoulder?.x, lHip?.x, rHip?.x).minOrNull() ?: 0f
        val bodyMaxX = listOfNotNull(lShoulder?.x, rShoulder?.x, lHip?.x, rHip?.x).maxOrNull() ?: 1000f
        val wristNearBody = (lWrist != null && lWrist.x in bodyMinX..bodyMaxX && lWrist.y > noseY) ||
                            (rWrist != null && rWrist.x in bodyMinX..bodyMaxX && rWrist.y > noseY)

        // 손목 미세 움직임
        var wristMicro = 0f
        for (i in 1 until windowFrames.size) {
            for (wId in listOf(L_WRIST, R_WRIST)) {
                val p = kp(windowFrames[i - 1], wId) ?: continue
                val c = kp(windowFrames[i], wId) ?: continue
                wristMicro += dist(p.x, p.y, c.x, c.y)
            }
        }
        wristMicro /= windowFrames.size.coerceAtLeast(1)

        // 손이 얼굴 근처
        val handNearFace = nose != null && (
            (lWrist != null && dist(lWrist.x, lWrist.y, nose.x, nose.y) < 50f) ||
            (rWrist != null && dist(rWrist.x, rWrist.y, nose.x, nose.y) < 50f)
        )

        // 손-얼굴 거리 변동
        var handFaceOsc = 0f
        if (nose != null) {
            for (i in 1 until windowFrames.size) {
                for (wId in listOf(L_WRIST, R_WRIST)) {
                    val pW = kp(windowFrames[i - 1], wId) ?: continue
                    val cW = kp(windowFrames[i], wId) ?: continue
                    val pN = kp(windowFrames[i - 1], NOSE) ?: continue
                    val cN = kp(windowFrames[i], NOSE) ?: continue
                    handFaceOsc += abs(dist(cW.x, cW.y, cN.x, cN.y) - dist(pW.x, pW.y, pN.x, pN.y))
                }
            }
        }

        // 앉은 자세 (무릎 굽힘 — 엉덩이-무릎-발목 각도)
        val lKnee = kp(latest, L_KNEE)
        val rKnee = kp(latest, R_KNEE)
        val lAnkle = kp(latest, L_ANKLE)
        val rAnkle = kp(latest, R_ANKLE)
        var hipKneeAngleLow = false
        if (lHip != null && lKnee != null && lAnkle != null) {
            val angle = angleBetween(lHip, lKnee, lAnkle)
            if (angle < 120f) hipKneeAngleLow = true
        }
        if (rHip != null && rKnee != null && rAnkle != null) {
            val angle = angleBetween(rHip, rKnee, rAnkle)
            if (angle < 120f) hipKneeAngleLow = true
        }

        return RuleFeatures(
            overallMotion = overallMotion,
            ankleDisplacement = ankleDisp,
            wristAboveHead = wristAboveHead,
            wristOscillation = wristOsc,
            armExtension = maxArmExt,
            headBelowHip = headBelowHip,
            wristNearBody = wristNearBody,
            wristMicroMotion = wristMicro,
            handNearFace = handNearFace,
            handFaceOscillation = handFaceOsc,
            hipKneeAngleLow = hipKneeAngleLow
        )
    }

    // ─── 유틸 ───

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    private fun angleBetween(a: PoseKeypoint, b: PoseKeypoint, c: PoseKeypoint): Float {
        val ba = Pair(a.x - b.x, a.y - b.y)
        val bc = Pair(c.x - b.x, c.y - b.y)
        val dot = ba.first * bc.first + ba.second * bc.second
        val magBA = sqrt(ba.first * ba.first + ba.second * ba.second)
        val magBC = sqrt(bc.first * bc.first + bc.second * bc.second)
        val cosAngle = (dot / (magBA * magBC).coerceAtLeast(1e-6f)).coerceIn(-1f, 1f)
        return Math.toDegrees(kotlin.math.acos(cosAngle).toDouble()).toFloat()
    }
}
