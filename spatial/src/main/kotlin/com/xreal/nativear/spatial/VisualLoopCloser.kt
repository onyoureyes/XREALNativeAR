package com.xreal.nativear.spatial

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.xreal.nativear.core.XRealLogger
import com.xreal.nativear.ImageEmbedder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * VisualLoopCloser — 시각 임베딩 기반 루프 클로저 (X,Z 드리프트 보정).
 *
 * ## 원리
 * 같은 장소를 재방문하면 시각 임베딩이 유사 (코사인 유사도 > 0.85).
 * 이때 저장된 VIO 포즈와 현재 VIO 포즈를 비교하면 누적 드리프트를 알 수 있음.
 *
 * ## 키프레임 저장
 * 10초마다 현재 프레임의 시각 임베딩 + VIO 포즈 + SLAM 썸네일을 저장.
 * 최대 360개 키프레임 (1시간 분량) 유지, FIFO 방식으로 교체.
 *
 * ## 루프 클로저 감지
 * 새 키프레임 저장 시 기존 키프레임들과 비교:
 * 1. 시간 간격 > 60초 (자기 자신 매칭 방지)
 * 2. 방위 차이 < 45° (같은 방향을 봐야 같은 씬)
 * 3. 임베딩 유사도 > 0.85 (시각적으로 같은 장소)
 * 4. → X,Z 차이 = 드리프트 추정
 *
 * ## 한계
 * - 임베딩 유사도만으로는 정확한 위치 일치를 보장하지 못함 (±2m 오차)
 * - ORB 특징점 기반 기하학적 검증이 추가되면 정밀도 향상 가능
 * - 현재는 보수적 보정률 (30%) 적용하여 안전하게 동작
 *
 * @param imageEmbedder 시각 임베딩 추출기 (MobileNetV3 1280-dim)
 * @param log 로깅 콜백
 *
 * @see DriftCorrectionManager 보정 소비자
 * @see VisualKeyframe 키프레임 데이터 구조
 */
class VisualLoopCloser(
    private val imageEmbedder: ImageEmbedder,
    private val log: (String) -> Unit
) {
    companion object {
        private const val TAG = "VisualLoopCloser"

        /** 키프레임 저장 최대 개수 (1시간 분량 @ 10초 간격) */
        const val MAX_KEYFRAMES = 360

        /** 루프 클로저 최소 시간 간격 (초). 이하면 자기 자신 매칭으로 무시 */
        const val MIN_TIME_GAP_SEC = 60L

        /** 루프 클로저 임베딩 유사도 임계값 */
        const val SIMILARITY_THRESHOLD = 0.85f

        /** 루프 클로저 방위 차이 허용 범위 (도) */
        const val HEADING_TOLERANCE_DEG = 45f

        /** 루프 클로저 최소 이동 거리 (미터). 이하면 드리프트가 아닌 정상 */
        const val MIN_DRIFT_DISTANCE_M = 1.0f

        /** 루프 클로저 최대 보정 거리 (미터). 초과하면 잘못된 매칭 의심 */
        const val MAX_CORRECTION_DISTANCE_M = 10.0f

        /** SLAM 썸네일 크기 */
        const val THUMBNAIL_WIDTH = 80
        const val THUMBNAIL_HEIGHT = 60
        const val THUMBNAIL_QUALITY = 50  // JPEG 압축 품질

        /** 키프레임 저장 간격 (ms) */
        const val KEYFRAME_INTERVAL_MS = 10_000L
    }

    // ── 키프레임 스토어 ──
    private val keyframes = ArrayList<VisualKeyframe>(MAX_KEYFRAMES)

    // ── 상태 ──
    private var lastKeyframeTime = 0L

    /** 저장된 키프레임 수 */
    val keyframeCount: Int get() = keyframes.size

    // ── Public API ──

    /**
     * 새 키프레임 저장 시도.
     *
     * 10초 간격으로 호출. 현재 시각 임베딩 + VIO 포즈를 저장하고,
     * 기존 키프레임과 비교하여 루프 클로저를 감지.
     *
     * @param embedding 현재 프레임의 시각 임베딩 (1280-dim ByteArray)
     * @param rawX,rawY,rawZ 보정 전 원본 VIO 좌표
     * @param correctedX,correctedY,correctedZ 현재 보정 후 좌표
     * @param headingDeg 현재 방위 (0-360°)
     * @param slamFrameBytes SLAM 좌안 프레임 (640×480 grayscale) — 썸네일 생성용
     * @return 루프 클로저 감지 시 (driftX, driftZ, confidence), null이면 미감지
     */
    fun tryAddKeyframeAndDetectLoop(
        embedding: ByteArray?,
        rawX: Float, rawY: Float, rawZ: Float,
        correctedX: Float, correctedY: Float, correctedZ: Float,
        headingDeg: Float,
        slamFrameBytes: ByteArray? = null
    ): LoopClosureResult? {
        if (embedding == null || embedding.isEmpty()) return null

        val now = System.currentTimeMillis()
        if (now - lastKeyframeTime < KEYFRAME_INTERVAL_MS) return null
        lastKeyframeTime = now

        // SLAM 썸네일 생성 (선택적)
        val thumbnail = slamFrameBytes?.let { createThumbnail(it) }

        // 키프레임 생성
        val keyframe = VisualKeyframe(
            embedding = embedding,
            rawVioX = rawX,
            rawVioY = rawY,
            rawVioZ = rawZ,
            correctedVioX = correctedX,
            correctedVioY = correctedY,
            correctedVioZ = correctedZ,
            headingDegrees = headingDeg,
            timestamp = now,
            slamThumbnail = thumbnail
        )

        // 루프 클로저 감지 (새 키프레임 vs 기존 키프레임)
        val loopResult = detectLoopClosure(keyframe)

        // 키프레임 저장 (FIFO)
        if (keyframes.size >= MAX_KEYFRAMES) {
            keyframes.removeAt(0)
        }
        keyframes.add(keyframe)

        return loopResult
    }

    /**
     * 현재 임베딩으로 루프 클로저 감지 (키프레임 저장 없이 쿼리만).
     */
    fun queryLoopClosure(
        embedding: ByteArray,
        currentRawX: Float, currentRawY: Float, currentRawZ: Float,
        headingDeg: Float
    ): LoopClosureResult? {
        val probeKeyframe = VisualKeyframe(
            embedding = embedding,
            rawVioX = currentRawX,
            rawVioY = currentRawY,
            rawVioZ = currentRawZ,
            correctedVioX = currentRawX,
            correctedVioY = currentRawY,
            correctedVioZ = currentRawZ,
            headingDegrees = headingDeg,
            timestamp = System.currentTimeMillis()
        )
        return detectLoopClosure(probeKeyframe)
    }

    /** 전체 키프레임 클리어 */
    fun clear() {
        keyframes.clear()
        lastKeyframeTime = 0L
        log("VisualLoopCloser: cleared all keyframes")
    }

    // ── 내부 루프 클로저 로직 ──

    /**
     * 루프 클로저 감지.
     *
     * 새 키프레임과 기존 키프레임들을 비교하여:
     * 1. 시간 간격 > 60초
     * 2. 방위 차이 < 45°
     * 3. 임베딩 유사도 > 0.85
     * → 가장 유사한 키프레임 선택 → X,Z 드리프트 추정
     */
    private fun detectLoopClosure(current: VisualKeyframe): LoopClosureResult? {
        if (keyframes.size < 3) return null  // 최소 3개 키프레임 필요

        var bestMatch: VisualKeyframe? = null
        var bestSimilarity = 0f

        for (kf in keyframes) {
            // 1. 시간 간격 확인
            val timeDiffSec = (current.timestamp - kf.timestamp) / 1000L
            if (timeDiffSec < MIN_TIME_GAP_SEC) continue

            // 2. 방위 차이 확인
            val headingDiff = abs(QuaternionUtils.angleDifference(current.headingDegrees, kf.headingDegrees))
            if (headingDiff > HEADING_TOLERANCE_DEG) continue

            // 3. 임베딩 유사도 (ByteArray → FloatArray 변환)
            val currentFloat = byteArrayToFloatArray(current.embedding)
            val kfFloat = byteArrayToFloatArray(kf.embedding)
            if (currentFloat == null || kfFloat == null) continue
            val similarity = imageEmbedder.calculateSimilarity(currentFloat, kfFloat)
            if (similarity > bestSimilarity && similarity >= SIMILARITY_THRESHOLD) {
                bestSimilarity = similarity
                bestMatch = kf
            }
        }

        if (bestMatch == null) return null

        // X,Z 드리프트 추정 (raw VIO 기준)
        // 같은 장소를 같은 방향에서 보고 있으므로 VIO 좌표가 동일해야 함
        // 차이 = 누적 드리프트
        val driftX = current.rawVioX - bestMatch.rawVioX
        val driftZ = current.rawVioZ - bestMatch.rawVioZ
        val driftDistance = sqrt(driftX * driftX + driftZ * driftZ)

        // 드리프트 거리 검증
        if (driftDistance < MIN_DRIFT_DISTANCE_M) {
            // 드리프트가 너무 작으면 보정 불필요
            return null
        }

        if (driftDistance > MAX_CORRECTION_DISTANCE_M) {
            // 드리프트가 너무 크면 잘못된 매칭 의심 → 무시
            XRealLogger.impl.w(TAG, "Loop closure rejected: drift ${driftDistance}m too large (max $MAX_CORRECTION_DISTANCE_M)")
            return null
        }

        val timeDiffMin = (current.timestamp - bestMatch.timestamp) / 60_000.0
        log("Loop closure detected! similarity=${"%.3f".format(bestSimilarity)}, " +
                "drift=(X=${"%.2f".format(driftX)}, Z=${"%.2f".format(driftZ)}), " +
                "dist=${"%.1f".format(driftDistance)}m, age=${"%.1f".format(timeDiffMin)}min")

        return LoopClosureResult(
            driftX = driftX,
            driftZ = driftZ,
            driftDistance = driftDistance,
            similarity = bestSimilarity,
            matchedKeyframe = bestMatch,
            timeDiffMs = current.timestamp - bestMatch.timestamp
        )
    }

    // ── SLAM 썸네일 생성 ──

    /**
     * ByteArray (Float32 encoded, 1280 × 4 = 5120 bytes) → FloatArray (1280-dim).
     */
    private fun byteArrayToFloatArray(bytes: ByteArray): FloatArray? {
        if (bytes.size < 4) return null
        return try {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val floatArray = FloatArray(bytes.size / 4)
            for (i in floatArray.indices) {
                floatArray[i] = buffer.getFloat()
            }
            floatArray
        } catch (e: Exception) {
            XRealLogger.impl.w(TAG, "ByteArray→FloatArray conversion failed: ${e.message}")
            null
        }
    }

    /**
     * 640×480 grayscale SLAM 프레임 → 80×60 JPEG 썸네일 (~2-3KB).
     */
    private fun createThumbnail(slamFrame: ByteArray): ByteArray? {
        return try {
            if (slamFrame.size < THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT) return null

            // Grayscale → ARGB Bitmap (640×480)
            val width = 640
            val height = 480
            if (slamFrame.size < width * height) return null

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)
            for (i in pixels.indices) {
                val gray = slamFrame[i].toInt() and 0xFF
                pixels[i] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

            // 다운스케일 → 80×60
            val thumbnail = Bitmap.createScaledBitmap(bitmap, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, true)
            bitmap.recycle()

            // JPEG 압축
            val baos = ByteArrayOutputStream()
            thumbnail.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, baos)
            thumbnail.recycle()

            val result = baos.toByteArray()
            result
        } catch (e: Exception) {
            XRealLogger.impl.w(TAG, "Thumbnail creation failed: ${e.message}")
            null
        }
    }
}

/**
 * LoopClosureResult — 루프 클로저 감지 결과.
 *
 * @param driftX X축 누적 드리프트 (미터, 현재 - 과거)
 * @param driftZ Z축 누적 드리프트 (미터)
 * @param driftDistance XZ 평면 드리프트 거리 (미터)
 * @param similarity 임베딩 유사도 (0-1)
 * @param matchedKeyframe 매칭된 과거 키프레임
 * @param timeDiffMs 매칭된 키프레임과의 시간 차이 (밀리초)
 */
data class LoopClosureResult(
    val driftX: Float,
    val driftZ: Float,
    val driftDistance: Float,
    val similarity: Float,
    val matchedKeyframe: VisualKeyframe,
    val timeDiffMs: Long
)
