package com.xreal.nativear.hand

import android.graphics.Bitmap
import android.graphics.RectF
import com.xreal.nativear.core.IAssetLoader
import com.xreal.nativear.core.XRealLogger
import com.xreal.ai.IAIModel
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * HandTrackingModel — MediaPipe 기반 2단계 손 추적 모델.
 *
 * ## 파이프라인
 * 1. **Palm Detection** (palm_detection_lite.tflite, 192×192)
 *    - SSD 앵커 기반 손바닥 위치 감지
 *    - 출력: 바운딩박스 + 7개 팜 키포인트 + 스코어
 *
 * 2. **Hand Landmark** (hand_landmark_lite.tflite, 224×224)
 *    - 손 크롭 영역에서 21개 관절 키포인트 추출
 *    - 출력: 21×(x,y,z) + 손 존재도 + 좌/우 판별
 *
 * ## 모델 파일 설치
 * assets/ 폴더에 다음 파일 필요:
 * - palm_detection_lite.tflite (~1.2MB)
 * - hand_landmark_lite.tflite (~3.3MB)
 *
 * MediaPipe GitHub에서 다운로드:
 * https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/
 *
 * @param assetLoader IAssetLoader (assets 접근용)
 */
class HandTrackingModel(private val assetLoader: IAssetLoader) : IAIModel {

    companion object {
        private const val TAG = "HandTrackingModel"
        private const val PALM_MODEL = "palm_detection_lite.tflite"
        private const val LANDMARK_MODEL = "hand_landmark_lite.tflite"

        // Palm detection 입력 크기
        const val PALM_INPUT_SIZE = 192

        // Hand landmark 입력 크기
        const val LANDMARK_INPUT_SIZE = 224

        // Palm detection 임계값
        const val PALM_SCORE_THRESHOLD = 0.5f
        const val PALM_NMS_IOU_THRESHOLD = 0.3f

        // Hand landmark 임계값
        const val HAND_PRESENCE_THRESHOLD = 0.5f

        // 최대 동시 감지 손 수
        const val MAX_HANDS = 2

        // 크롭 패딩 비율 (바운딩박스 확장)
        const val CROP_PADDING = 0.3f
    }

    private var palmInterpreter: Interpreter? = null
    private var landmarkInterpreter: Interpreter? = null

    // Palm detection 버퍼
    private var palmInputBuffer: ByteBuffer? = null
    private var palmRegressorOutput: ByteBuffer? = null
    private var palmClassificatorOutput: ByteBuffer? = null

    // Landmark 버퍼
    private var landmarkInputBuffer: ByteBuffer? = null
    private var landmarkOutput: ByteBuffer? = null
    private var handFlagOutput: ByteBuffer? = null
    private var handednessOutput: ByteBuffer? = null

    // Pre-generated SSD 앵커 (palm detection용)
    private val palmAnchors = mutableListOf<Pair<Float, Float>>()

    // Image processor (resize)
    private var palmImageProcessor: ImageProcessor? = null
    private var landmarkImageProcessor: ImageProcessor? = null
    private var tensorImage: TensorImage? = null

    // IAIModel 구현
    override var isReady: Boolean = false
        private set
    override var isLoaded: Boolean = false
        private set
    override val priority: Int = 7  // YOLO(8)보다 약간 낮음

    override suspend fun prepare(interpreterOptions: Interpreter.Options): Boolean {
        if (isLoaded) return true
        try {
            // Palm detection 모델 로드
            val palmBuffer = loadModelFile(PALM_MODEL)
            if (palmBuffer == null) {
                XRealLogger.impl.w(TAG, "Palm detection model not found: $PALM_MODEL")
                return false
            }
            palmInterpreter = Interpreter(palmBuffer, interpreterOptions)

            // Hand landmark 모델 로드
            val landmarkBuffer = loadModelFile(LANDMARK_MODEL)
            if (landmarkBuffer == null) {
                XRealLogger.impl.w(TAG, "Hand landmark model not found: $LANDMARK_MODEL")
                palmInterpreter?.close()
                palmInterpreter = null
                return false
            }
            landmarkInterpreter = Interpreter(landmarkBuffer, interpreterOptions)

            // Palm detection 버퍼 초기화
            val palmInputSize = PALM_INPUT_SIZE * PALM_INPUT_SIZE * 3 * 4  // float32
            palmInputBuffer = ByteBuffer.allocateDirect(palmInputSize).apply {
                order(ByteOrder.nativeOrder())
            }

            // Palm detection 출력 shape 확인
            val palmOutTensors = palmInterpreter!!.outputTensorCount
            XRealLogger.impl.i(TAG, "Palm model output tensors: $palmOutTensors")

            // 출력 텐서 0: regressors [1, NUM_ANCHORS, 18]
            // 출력 텐서 1: classificators [1, NUM_ANCHORS, 1]
            val regShape = palmInterpreter!!.getOutputTensor(0).shape()
            val clsShape = palmInterpreter!!.getOutputTensor(1).shape()
            val numAnchors = regShape[1]
            XRealLogger.impl.i(TAG, "Palm anchors: $numAnchors, regressor shape: ${regShape.toList()}, " +
                    "classificator shape: ${clsShape.toList()}")

            palmRegressorOutput = ByteBuffer.allocateDirect(numAnchors * 18 * 4).apply {
                order(ByteOrder.nativeOrder())
            }
            palmClassificatorOutput = ByteBuffer.allocateDirect(numAnchors * 1 * 4).apply {
                order(ByteOrder.nativeOrder())
            }

            // SSD 앵커 생성
            generatePalmAnchors(numAnchors)

            // Landmark 버퍼 초기화
            val lmInputSize = LANDMARK_INPUT_SIZE * LANDMARK_INPUT_SIZE * 3 * 4
            landmarkInputBuffer = ByteBuffer.allocateDirect(lmInputSize).apply {
                order(ByteOrder.nativeOrder())
            }

            // Landmark 출력: [1, 63], [1, 1], [1, 1]
            landmarkOutput = ByteBuffer.allocateDirect(63 * 4).apply {
                order(ByteOrder.nativeOrder())
            }
            handFlagOutput = ByteBuffer.allocateDirect(1 * 4).apply {
                order(ByteOrder.nativeOrder())
            }
            handednessOutput = ByteBuffer.allocateDirect(1 * 4).apply {
                order(ByteOrder.nativeOrder())
            }

            // Image processors
            palmImageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(PALM_INPUT_SIZE, PALM_INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
                .build()
            landmarkImageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(LANDMARK_INPUT_SIZE, LANDMARK_INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
                .build()
            tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)

            isLoaded = true
            isReady = true
            XRealLogger.impl.i(TAG, "Hand tracking model ready (anchors=$numAnchors)")
            return true

        } catch (e: Exception) {
            XRealLogger.impl.e(TAG, "Failed to prepare hand tracking model", e)
            release()
            return false
        }
    }

    override fun release() {
        palmInterpreter?.close()
        landmarkInterpreter?.close()
        palmInterpreter = null
        landmarkInterpreter = null
        isLoaded = false
        isReady = false
    }

    // ── Public API ──

    /**
     * 비트맵에서 손 감지 + 21개 키포인트 추출.
     *
     * @param bitmap RGB 비트맵 (임의 크기, 내부에서 리사이즈)
     * @return 감지된 손 목록 (최대 MAX_HANDS)
     */
    fun detect(bitmap: Bitmap): List<HandData> {
        if (!isReady) return emptyList()

        try {
            // 1단계: 손바닥 감지
            val palms = detectPalms(bitmap)
            if (palms.isEmpty()) return emptyList()

            // 2단계: 각 손에 대해 랜드마크 추출
            val results = mutableListOf<HandData>()
            for (palm in palms.take(MAX_HANDS)) {
                val handData = detectLandmarks(bitmap, palm)
                if (handData != null) {
                    results.add(handData)
                }
            }
            return results

        } catch (e: Exception) {
            XRealLogger.impl.e(TAG, "Hand detection failed", e)
            return emptyList()
        }
    }

    // ── Palm Detection ──

    private fun detectPalms(bitmap: Bitmap): List<PalmDetection> {
        val interpreter = palmInterpreter ?: return emptyList()
        val inputBuf = palmInputBuffer ?: return emptyList()
        val regBuf = palmRegressorOutput ?: return emptyList()
        val clsBuf = palmClassificatorOutput ?: return emptyList()

        // 비트맵 → 192×192 텐서 (0~1 정규화)
        inputBuf.rewind()
        val scaled = Bitmap.createScaledBitmap(bitmap, PALM_INPUT_SIZE, PALM_INPUT_SIZE, true)
        val pixels = IntArray(PALM_INPUT_SIZE * PALM_INPUT_SIZE)
        scaled.getPixels(pixels, 0, PALM_INPUT_SIZE, 0, 0, PALM_INPUT_SIZE, PALM_INPUT_SIZE)
        if (scaled !== bitmap) scaled.recycle()

        for (pixel in pixels) {
            inputBuf.putFloat(((pixel shr 16) and 0xFF) / 255.0f)  // R
            inputBuf.putFloat(((pixel shr 8) and 0xFF) / 255.0f)   // G
            inputBuf.putFloat((pixel and 0xFF) / 255.0f)            // B
        }
        inputBuf.rewind()

        // 추론
        regBuf.rewind()
        clsBuf.rewind()
        val outputs = mapOf(0 to regBuf, 1 to clsBuf)
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuf), outputs)

        // 디코딩
        regBuf.rewind()
        clsBuf.rewind()
        val numAnchors = palmAnchors.size
        val detections = mutableListOf<PalmDetection>()

        for (i in 0 until numAnchors) {
            val score = sigmoid(clsBuf.getFloat())
            if (score < PALM_SCORE_THRESHOLD) {
                // 스킵하되 regressor 포인터는 전진
                regBuf.position(regBuf.position() + 18 * 4)
                continue
            }

            val anchor = palmAnchors[i]

            // 박스 디코딩
            val cx = anchor.first + regBuf.getFloat() / PALM_INPUT_SIZE
            val cy = anchor.second + regBuf.getFloat() / PALM_INPUT_SIZE
            val w = regBuf.getFloat() / PALM_INPUT_SIZE
            val h = regBuf.getFloat() / PALM_INPUT_SIZE

            // 7개 팜 키포인트
            val keypoints = mutableListOf<Pair<Float, Float>>()
            for (j in 0 until 7) {
                val kpx = anchor.first + regBuf.getFloat() / PALM_INPUT_SIZE
                val kpy = anchor.second + regBuf.getFloat() / PALM_INPUT_SIZE
                keypoints.add(kpx to kpy)
            }

            detections.add(PalmDetection(cx, cy, w, h, score, keypoints))
        }

        // NMS
        return nms(detections)
    }

    // ── Hand Landmark ──

    private fun detectLandmarks(bitmap: Bitmap, palm: PalmDetection): HandData? {
        val interpreter = landmarkInterpreter ?: return null
        val inputBuf = landmarkInputBuffer ?: return null
        val lmBuf = landmarkOutput ?: return null
        val flagBuf = handFlagOutput ?: return null
        val handBuf = handednessOutput ?: return null

        // 크롭 영역 계산 (패딩 포함)
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val padW = palm.width * CROP_PADDING
        val padH = palm.height * CROP_PADDING
        val cropLeft = max(0f, (palm.centerX - palm.width / 2 - padW) * bw).toInt()
        val cropTop = max(0f, (palm.centerY - palm.height / 2 - padH) * bh).toInt()
        val cropRight = min(bw, (palm.centerX + palm.width / 2 + padW) * bw).toInt()
        val cropBottom = min(bh, (palm.centerY + palm.height / 2 + padH) * bh).toInt()
        val cropW = cropRight - cropLeft
        val cropH = cropBottom - cropTop
        if (cropW < 20 || cropH < 20) return null

        // 크롭 → 224×224 리사이즈
        val cropped = try {
            Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropW, cropH)
        } catch (e: Exception) {
            return null
        }
        val scaled = Bitmap.createScaledBitmap(cropped, LANDMARK_INPUT_SIZE, LANDMARK_INPUT_SIZE, true)
        if (cropped !== bitmap) cropped.recycle()

        // 비트맵 → 텐서 (0~1 정규화)
        inputBuf.rewind()
        val pixels = IntArray(LANDMARK_INPUT_SIZE * LANDMARK_INPUT_SIZE)
        scaled.getPixels(pixels, 0, LANDMARK_INPUT_SIZE, 0, 0, LANDMARK_INPUT_SIZE, LANDMARK_INPUT_SIZE)
        if (scaled !== cropped) scaled.recycle()

        for (pixel in pixels) {
            inputBuf.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            inputBuf.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            inputBuf.putFloat((pixel and 0xFF) / 255.0f)
        }
        inputBuf.rewind()

        // 추론
        lmBuf.rewind()
        flagBuf.rewind()
        handBuf.rewind()
        val outputs = mapOf(0 to lmBuf, 1 to flagBuf, 2 to handBuf)
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuf), outputs)

        // 손 존재도 확인
        flagBuf.rewind()
        val handPresence = sigmoid(flagBuf.getFloat())
        if (handPresence < HAND_PRESENCE_THRESHOLD) return null

        // Handedness
        handBuf.rewind()
        val isRightHand = sigmoid(handBuf.getFloat()) > 0.5f

        // 21개 랜드마크 파싱 (크롭 좌표 → 원본 이미지 좌표)
        lmBuf.rewind()
        val landmarks = mutableListOf<HandLandmark>()
        for (j in 0 until 21) {
            val lx = lmBuf.getFloat() / LANDMARK_INPUT_SIZE  // 0~1 in crop
            val ly = lmBuf.getFloat() / LANDMARK_INPUT_SIZE
            val lz = lmBuf.getFloat() / LANDMARK_INPUT_SIZE

            // 크롭 → 원본 좌표 변환
            val origX = (cropLeft + lx * cropW) / bw
            val origY = (cropTop + ly * cropH) / bh
            landmarks.add(HandLandmark(origX.coerceIn(0f, 1f), origY.coerceIn(0f, 1f), lz))
        }

        val bbox = RectF(
            (palm.centerX - palm.width / 2).coerceIn(0f, 1f),
            (palm.centerY - palm.height / 2).coerceIn(0f, 1f),
            (palm.centerX + palm.width / 2).coerceIn(0f, 1f),
            (palm.centerY + palm.height / 2).coerceIn(0f, 1f)
        )

        return HandData(landmarks, isRightHand, handPresence, bbox)
    }

    // ── SSD 앵커 생성 ──

    /**
     * MediaPipe Palm Detection SSD 앵커 생성.
     * 모델 출력 텐서 shape에서 앵커 수를 동적으로 결정.
     */
    private fun generatePalmAnchors(numAnchors: Int) {
        palmAnchors.clear()

        // MediaPipe palm_detection_lite 앵커 사양:
        // strides = [8, 16, 16, 16], input=192
        // Layer 0: 24×24, 2 anchors → 1152
        // Layer 1~3: 12×12, 2 anchors → 288 each → 864
        // Total: 2016 (full model) 또는 모델에 따라 다름
        //
        // 실제 앵커 수가 모델마다 다를 수 있으므로 동적 계산
        val layerConfigs = listOf(
            Triple(8, 2, 0),     // stride, anchors_per_cell, layer_id
            Triple(16, 6, 1)     // stride, anchors_per_cell, layer_id
        )

        var totalGenerated = 0
        for ((stride, anchorsPerCell, _) in layerConfigs) {
            val gridSize = PALM_INPUT_SIZE / stride
            for (row in 0 until gridSize) {
                for (col in 0 until gridSize) {
                    val cx = (col + 0.5f) * stride / PALM_INPUT_SIZE
                    val cy = (row + 0.5f) * stride / PALM_INPUT_SIZE
                    for (k in 0 until anchorsPerCell) {
                        palmAnchors.add(cx to cy)
                        totalGenerated++
                    }
                }
            }
        }

        // 앵커 수가 모델과 맞지 않으면 단순 그리드로 폴백
        if (totalGenerated != numAnchors) {
            XRealLogger.impl.w(TAG, "Anchor count mismatch: generated=$totalGenerated, model=$numAnchors. Using uniform grid.")
            palmAnchors.clear()
            val gridSize = sqrt(numAnchors.toFloat()).toInt()
            for (i in 0 until numAnchors) {
                val cx = ((i % gridSize) + 0.5f) / gridSize
                val cy = ((i / gridSize) + 0.5f) / gridSize
                palmAnchors.add(cx to cy)
            }
        }

        XRealLogger.impl.i(TAG, "Generated ${palmAnchors.size} palm detection anchors")
    }

    // ── NMS ──

    private fun nms(detections: List<PalmDetection>): List<PalmDetection> {
        if (detections.isEmpty()) return emptyList()
        val sorted = detections.sortedByDescending { it.score }.toMutableList()
        val result = mutableListOf<PalmDetection>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            result.add(best)
            sorted.removeAll { iou(best, it) > PALM_NMS_IOU_THRESHOLD }
        }
        return result
    }

    private fun iou(a: PalmDetection, b: PalmDetection): Float {
        val ax1 = a.centerX - a.width / 2; val ay1 = a.centerY - a.height / 2
        val ax2 = a.centerX + a.width / 2; val ay2 = a.centerY + a.height / 2
        val bx1 = b.centerX - b.width / 2; val by1 = b.centerY - b.height / 2
        val bx2 = b.centerX + b.width / 2; val by2 = b.centerY + b.height / 2

        val ix1 = max(ax1, bx1); val iy1 = max(ay1, by1)
        val ix2 = min(ax2, bx2); val iy2 = min(ay2, by2)

        val inter = max(0f, ix2 - ix1) * max(0f, iy2 - iy1)
        val areaA = (ax2 - ax1) * (ay2 - ay1)
        val areaB = (bx2 - bx1) * (by2 - by1)
        val union = areaA + areaB - inter
        return if (union > 0f) inter / union else 0f
    }

    // ── 유틸리티 ──

    private fun sigmoid(x: Float): Float = 1.0f / (1.0f + exp(-x))

    private fun loadModelFile(filename: String): ByteBuffer? {
        return try {
            assetLoader.loadModelBuffer(filename)
        } catch (e: Exception) {
            XRealLogger.impl.w(TAG, "Model file not found: $filename (${e.message})")
            null
        }
    }
}
