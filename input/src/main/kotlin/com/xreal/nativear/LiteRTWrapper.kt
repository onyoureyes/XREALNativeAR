package com.xreal.nativear

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import com.xreal.nativear.core.IAssetLoader
import com.xreal.nativear.core.XRealLogger
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

// LiteRTWrapper: Handles object detection using YOLO26n (NMS-free) or YOLOv8n (legacy).
// Dynamically detects output format: YOLO26 [1,300,6] vs YOLOv8 [1,84,8400].
// Now integrated with UnifiedAIOrchestrator via IAIModel.
class LiteRTWrapper(private val assetLoader: IAssetLoader) : com.xreal.ai.IAIModel {

    private var interpreter: Interpreter? = null

    // Model Input Specs
    private var inputImageWidth: Int = 0
    private var inputImageHeight: Int = 0
    private var inputDataType: DataType = DataType.INT8

    // Model Output Specs
    private var outputScale: Float = 1.0f
    private var outputZeroPoint: Int = 0
    private var outputShape: IntArray = intArrayOf(1, 300, 6)

    // YOLO26 vs YOLOv8 output format detection
    private var isYolo26Format = true // true=[1,300,6] NMS-free, false=[1,84,8400] legacy

    // Pre-allocated Buffers
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null
    private var outputData: ByteArray? = null

    // TF Support Library: replaces Canvas + getPixels + pixel loop
    private var imageProcessor: ImageProcessor? = null
    private var tensorImage: TensorImage? = null

    override val priority: Int = 8 // High Priority (Real-time AR)

    companion object {
        private const val TAG = "LiteRTWrapper"
        // YOLO26n — NMS-free, 43% faster than YOLO11n, DFL removed
        // Fallback to YOLOv8n if YOLO26n model not found in assets
        private const val MODEL_NAME_YOLO26 = "yolo26n_int8.tflite"
        private const val MODEL_NAME_LEGACY = "yolov8n_full_integer_quant.tflite"

        val LABELS = listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
            "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
            "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
        )
    }

    override var isReady: Boolean = false
        private set
    override var isLoaded: Boolean = false
        private set

    // Attempts to load YOLO26n first, falls back to YOLOv8n if not found in assets.
    override suspend fun prepare(interpreterOptions: Interpreter.Options): Boolean {
        if (isLoaded) return true
        try {
            // Try YOLO26n first, fallback to YOLOv8n
            val modelName = if (assetLoader.assetExists(MODEL_NAME_YOLO26)) {
                XRealLogger.impl.i(TAG, "Found YOLO26n model, using NMS-free detection")
                MODEL_NAME_YOLO26
            } else {
                XRealLogger.impl.i(TAG, "YOLO26n not found, falling back to YOLOv8n legacy")
                MODEL_NAME_LEGACY
            }

            XRealLogger.impl.i(TAG, "Preparing LiteRTWrapper ($modelName) with Orchestrator Options...")

            val modelBuffer = assetLoader.loadModelBuffer(modelName)

            interpreter = Interpreter(modelBuffer, interpreterOptions)

            // Get Tensor Info
            val inputTensor = interpreter!!.getInputTensor(0)
            inputImageHeight = inputTensor.shape()[1]
            inputImageWidth = inputTensor.shape()[2]
            inputDataType = inputTensor.dataType()

            val outputTensor = interpreter!!.getOutputTensor(0)
            outputShape = outputTensor.shape()
            outputScale = outputTensor.quantizationParams().scale
            outputZeroPoint = outputTensor.quantizationParams().zeroPoint

            // Auto-detect output format: YOLO26 [1,300,6] vs YOLOv8 [1,84,8400]
            isYolo26Format = outputShape.size == 3 && outputShape[2] <= 10 // [1, N, 6]
            XRealLogger.impl.i(TAG, "Output: shape=${outputShape.toList()}, dtype=${outputTensor.dataType()}, " +
                    "scale=$outputScale, zp=$outputZeroPoint, format=${if (isYolo26Format) "YOLO26_NMS_FREE" else "YOLOv8_LEGACY"}")

            // Buffer Allocation
            inputBuffer = ByteBuffer.allocateDirect(inputTensor.numBytes())
            inputBuffer?.order(ByteOrder.nativeOrder())

            outputBuffer = ByteBuffer.allocateDirect(outputTensor.numBytes())
            outputBuffer?.order(ByteOrder.nativeOrder())
            outputData = ByteArray(outputTensor.numBytes())

            // TF Support Library: resize in a single optimized pass
            imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(inputImageHeight, inputImageWidth, ResizeOp.ResizeMethod.BILINEAR))
                .build()
            tensorImage = TensorImage(DataType.UINT8)

            XRealLogger.impl.i(TAG, "Prepared: $modelName ($inputImageWidth x $inputImageHeight $inputDataType)")
            isLoaded = true
            isReady = true
            return true

        } catch (e: Exception) {
            XRealLogger.impl.e(TAG, "LiteRT Preparation Failed: ${e.message}")
            return false
        }
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        if (interpreter == null || inputBuffer == null || imageProcessor == null || tensorImage == null) return emptyList()
        val totalStart = SystemClock.elapsedRealtime()
        try {
            // 1. Preprocessing via TF Support Library (single optimized resize pass)
            val preStart = SystemClock.elapsedRealtime()

            tensorImage!!.load(bitmap)
            val processed = imageProcessor!!.process(tensorImage!!)
            val src = processed.buffer
            src.rewind()
            inputBuffer!!.rewind()

            if (inputDataType == DataType.INT8) {
                // UINT8 → INT8: subtract 128 from each byte
                while (src.hasRemaining()) {
                    inputBuffer!!.put((src.get().toInt() and 0xFF - 128).toByte())
                }
            } else {
                // UINT8 pass-through
                inputBuffer!!.put(src)
            }
            val preTime = SystemClock.elapsedRealtime() - preStart

            // 2. Inference
            val inferStart = SystemClock.elapsedRealtime()
            interpreter!!.run(inputBuffer!!.rewind(), outputBuffer!!.rewind())
            val inferTime = SystemClock.elapsedRealtime() - inferStart

            // 3. Post-processing (format-specific)
            val postStart = SystemClock.elapsedRealtime()
            outputBuffer!!.rewind()
            outputBuffer!!.get(outputData!!)

            val result = if (isYolo26Format) {
                detectYolo26(bitmap)
            } else {
                detectYolov8Legacy(bitmap)
            }
            val postTime = SystemClock.elapsedRealtime() - postStart

            XRealLogger.impl.i(TAG, "Perf: Pre ${preTime}ms | Inf ${inferTime}ms | Post ${postTime}ms | " +
                    "Total ${SystemClock.elapsedRealtime() - totalStart}ms | Det=${result.size} " +
                    "fmt=${if (isYolo26Format) "YOLO26" else "YOLOv8"}")

            return result

        } catch (e: Exception) {
            XRealLogger.impl.e(TAG, "Detection Failed: ${e.message}")
            return emptyList()
        }
    }

    /**
     * YOLO26n NMS-free output parsing.
     * Output shape: [1, 300, 6] where each row = [x1, y1, x2, y2, confidence, class_id]
     * Coordinates are already in image-relative scale. No NMS needed.
     */
    private fun detectYolo26(bitmap: Bitmap): List<Detection> {
        val maxDetections = outputShape[1] // 300
        val valuesPerDet = outputShape[2]  // 6
        val results = mutableListOf<Detection>()

        for (i in 0 until maxDetections) {
            val baseIdx = i * valuesPerDet
            val confRaw = outputData!![baseIdx + 4].toInt()
            val confidence = (confRaw - outputZeroPoint) * outputScale

            if (confidence > 0.45f) {
                // x1, y1, x2, y2 — dequantize and scale to bitmap coords
                val x1 = (outputData!![baseIdx + 0].toInt() - outputZeroPoint) * outputScale * bitmap.width
                val y1 = (outputData!![baseIdx + 1].toInt() - outputZeroPoint) * outputScale * bitmap.height
                val x2 = (outputData!![baseIdx + 2].toInt() - outputZeroPoint) * outputScale * bitmap.width
                val y2 = (outputData!![baseIdx + 3].toInt() - outputZeroPoint) * outputScale * bitmap.height
                val classIdRaw = outputData!![baseIdx + 5].toInt()
                val classId = ((classIdRaw - outputZeroPoint) * outputScale).toInt()

                val cx = (x1 + x2) / 2f
                val cy = (y1 + y2) / 2f
                val w = (x2 - x1).coerceAtLeast(0f)
                val h = (y2 - y1).coerceAtLeast(0f)

                val label = LABELS.getOrElse(classId) { "unknown" }
                results.add(Detection(label, confidence, cx, cy, w, h))
            }
        }
        return results
    }

    /**
     * YOLOv8n legacy output parsing.
     * Output shape: [1, 84, 8400] — requires manual NMS post-processing.
     */
    private fun detectYolov8Legacy(bitmap: Bitmap): List<Detection> {
        val candidates = mutableListOf<Detection>()
        val rows = outputShape[1] // 84
        val cols = outputShape[2] // 8400

        for (c in 0 until cols) {
            var maxConfRaw = -128
            var maxConfPos = -1

            for (r in 4 until rows) {
                val raw = outputData!![r * cols + c].toInt()
                if (raw > maxConfRaw) {
                    maxConfRaw = raw
                    maxConfPos = r
                }
            }

            val confidence = (maxConfRaw - outputZeroPoint) * outputScale
            if (confidence > 0.45f) {
                val cx = (outputData!![0 * cols + c].toInt() - outputZeroPoint) * outputScale * bitmap.width
                val cy = (outputData!![1 * cols + c].toInt() - outputZeroPoint) * outputScale * bitmap.height
                val w = (outputData!![2 * cols + c].toInt() - outputZeroPoint) * outputScale * bitmap.width
                val h = (outputData!![3 * cols + c].toInt() - outputZeroPoint) * outputScale * bitmap.height

                val rect = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
                val label = LABELS.getOrElse(maxConfPos - 4) { "unknown" }
                candidates.add(Detection(label, confidence, rect.centerX(), rect.centerY(), rect.width(), rect.height()))
            }
        }
        return nms(candidates)
    }

    /** NMS — only needed for YOLOv8 legacy format */
    private fun nms(detections: MutableList<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()
        detections.sortByDescending { it.confidence }
        val result = mutableListOf<Detection>()
        while (detections.isNotEmpty()) {
            val first = detections.removeAt(0)
            result.add(first)
            val iterator = detections.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (calculateIoU(first, next) > 0.45f) iterator.remove()
            }
        }
        return result
    }

    private fun calculateIoU(a: Detection, b: Detection): Float {
        val rectA = RectF(a.x - a.width / 2f, a.y - a.height / 2f, a.x + a.width / 2f, a.y + a.height / 2f)
        val rectB = RectF(b.x - b.width / 2f, b.y - b.height / 2f, b.x + b.width / 2f, b.y + b.height / 2f)
        val intersection = RectF()
        if (!intersection.setIntersect(rectA, rectB)) return 0f
        val interArea = intersection.width() * intersection.height()
        val unionArea = (a.width * a.height) + (b.width * b.height) - interArea
        return interArea / unionArea
    }


    override fun release() {
        interpreter?.close()
        interpreter = null
        // ★ 상태 초기화 → 다음 prepare() 호출 시 재초기화 가능
        // 이전: release() 후 isLoaded=true 유지 → prepare()의 if(isLoaded) return true로 재준비 불가
        isLoaded = false
        isReady = false
        inputBuffer = null
        outputBuffer = null
        outputData = null
        imageProcessor = null
        tensorImage = null
    }
}
