package com.xreal.nativear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FaceDetector: BlazeFace-based face detection (MediaPipe short-range).
 *
 * Input:  (1, 128, 128, 3) float32 RGB [0,1]
 * Output: boxes (1, 896, 16) + scores (1, 896, 1)
 *   - 896 anchor proposals
 *   - Each box: [x_center, y_center, w, h, keypoint0_x, keypoint0_y, ..., keypoint5_x, keypoint5_y]
 *   - 6 keypoints: right_eye, left_eye, nose, mouth, right_ear, left_ear
 */
class FaceDetector(private val context: Context) : com.xreal.ai.IAIModel {

    private val TAG = "FaceDetector"
    private val MODEL_FILE = "face_detection_front.tflite"
    private val INPUT_SIZE = 128
    private val NUM_ANCHORS = 896
    private val SCORE_THRESHOLD = 0.75f
    private val NMS_IOU_THRESHOLD = 0.3f

    private var interpreter: Interpreter? = null
    private var imageProcessor: ImageProcessor? = null
    private var tensorImage: TensorImage? = null

    // Pre-generated anchors for BlazeFace SSD
    private lateinit var anchors: Array<FloatArray>

    override val priority: Int = 6
    override var isReady: Boolean = false
        private set
    override var isLoaded: Boolean = false
        private set

    data class Face(
        val x: Float,          // center x (0-1 normalized)
        val y: Float,          // center y (0-1 normalized)
        val width: Float,      // width (0-1 normalized)
        val height: Float,     // height (0-1 normalized)
        val confidence: Float,
        val leftEye: PointF,   // (0-1 normalized)
        val rightEye: PointF,
        val nose: PointF,
        val mouth: PointF
    )

    override suspend fun prepare(options: Interpreter.Options): Boolean {
        if (isLoaded) return true

        return try {
            val modelBuffer = FileUtil.loadMappedFile(context, MODEL_FILE)
            interpreter = Interpreter(modelBuffer, options)

            imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
                .add(NormalizeOp(0f, 255f))  // [0, 255] → [0, 1]
                .build()
            tensorImage = TensorImage(DataType.FLOAT32)

            generateAnchors()

            Log.i(TAG, "BlazeFace loaded: ${anchors.size} anchors")
            isLoaded = true
            isReady = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load BlazeFace: ${e.message}", e)
            false
        }
    }

    override fun release() {
        interpreter?.close()
        interpreter = null
        isLoaded = false
        isReady = false
    }

    /**
     * Detect faces in a bitmap.
     * @return List of faces with normalized [0,1] coordinates
     */
    fun detect(bitmap: Bitmap): List<Face> {
        val interp = interpreter ?: return emptyList()
        val proc = imageProcessor ?: return emptyList()
        val ti = tensorImage ?: return emptyList()

        try {
            ti.load(bitmap)
            val processed = proc.process(ti)

            // Output tensors
            val boxesOutput = Array(1) { Array(NUM_ANCHORS) { FloatArray(16) } }
            val scoresOutput = Array(1) { Array(NUM_ANCHORS) { FloatArray(1) } }

            val outputMap = HashMap<Int, Any>()
            outputMap[0] = boxesOutput
            outputMap[1] = scoresOutput

            interp.runForMultipleInputsOutputs(arrayOf(processed.buffer), outputMap)

            // Decode detections
            val candidates = mutableListOf<Face>()
            for (i in 0 until NUM_ANCHORS) {
                val score = sigmoid(scoresOutput[0][i][0])
                if (score < SCORE_THRESHOLD) continue

                val anchor = anchors[i]
                val raw = boxesOutput[0][i]

                // Decode box: anchor + offset (normalized to INPUT_SIZE)
                val cx = (raw[0] / INPUT_SIZE + anchor[0]).coerceIn(0f, 1f)
                val cy = (raw[1] / INPUT_SIZE + anchor[1]).coerceIn(0f, 1f)
                val w = (raw[2] / INPUT_SIZE).coerceIn(0f, 1f)
                val h = (raw[3] / INPUT_SIZE).coerceIn(0f, 1f)

                // Decode keypoints
                val rightEye = PointF(
                    (raw[4] / INPUT_SIZE + anchor[0]).coerceIn(0f, 1f),
                    (raw[5] / INPUT_SIZE + anchor[1]).coerceIn(0f, 1f)
                )
                val leftEye = PointF(
                    (raw[6] / INPUT_SIZE + anchor[0]).coerceIn(0f, 1f),
                    (raw[7] / INPUT_SIZE + anchor[1]).coerceIn(0f, 1f)
                )
                val nose = PointF(
                    (raw[8] / INPUT_SIZE + anchor[0]).coerceIn(0f, 1f),
                    (raw[9] / INPUT_SIZE + anchor[1]).coerceIn(0f, 1f)
                )
                val mouth = PointF(
                    (raw[10] / INPUT_SIZE + anchor[0]).coerceIn(0f, 1f),
                    (raw[11] / INPUT_SIZE + anchor[1]).coerceIn(0f, 1f)
                )

                candidates.add(Face(cx, cy, w, h, score, leftEye, rightEye, nose, mouth))
            }

            return nms(candidates)
        } catch (e: Exception) {
            Log.e(TAG, "Face detection failed: ${e.message}")
            return emptyList()
        }
    }

    private fun sigmoid(x: Float): Float = (1.0f / (1.0f + Math.exp(-x.toDouble()))).toFloat()

    private fun nms(faces: MutableList<Face>): List<Face> {
        if (faces.isEmpty()) return emptyList()
        faces.sortByDescending { it.confidence }

        val result = mutableListOf<Face>()
        while (faces.isNotEmpty()) {
            val first = faces.removeAt(0)
            result.add(first)
            val iterator = faces.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (calculateIoU(first, next) > NMS_IOU_THRESHOLD) {
                    iterator.remove()
                }
            }
        }
        return result
    }

    private fun calculateIoU(a: Face, b: Face): Float {
        val rectA = RectF(a.x - a.width / 2, a.y - a.height / 2, a.x + a.width / 2, a.y + a.height / 2)
        val rectB = RectF(b.x - b.width / 2, b.y - b.height / 2, b.x + b.width / 2, b.y + b.height / 2)
        val intersection = RectF()
        if (!intersection.setIntersect(rectA, rectB)) return 0f
        val interArea = intersection.width() * intersection.height()
        val unionArea = (a.width * a.height) + (b.width * b.height) - interArea
        return if (unionArea > 0f) interArea / unionArea else 0f
    }

    /**
     * Generate SSD anchors for BlazeFace (128x128, 2 layers).
     * Layer 0: 16x16 grid, 2 anchors per cell = 512
     * Layer 1: 8x8 grid, 6 anchors per cell = 384
     * Total: 896 anchors
     */
    private fun generateAnchors() {
        val anchorList = mutableListOf<FloatArray>()

        // Layer 0: stride=8, 16x16 grid, 2 anchors
        val gridSize0 = INPUT_SIZE / 8  // 16
        for (y in 0 until gridSize0) {
            for (x in 0 until gridSize0) {
                val cx = (x + 0.5f) / gridSize0
                val cy = (y + 0.5f) / gridSize0
                anchorList.add(floatArrayOf(cx, cy))
                anchorList.add(floatArrayOf(cx, cy))
            }
        }

        // Layer 1: stride=16, 8x8 grid, 6 anchors
        val gridSize1 = INPUT_SIZE / 16  // 8
        for (y in 0 until gridSize1) {
            for (x in 0 until gridSize1) {
                val cx = (x + 0.5f) / gridSize1
                val cy = (y + 0.5f) / gridSize1
                repeat(6) {
                    anchorList.add(floatArrayOf(cx, cy))
                }
            }
        }

        anchors = anchorList.toTypedArray()
        Log.d(TAG, "Generated ${anchors.size} anchors")
    }
}
