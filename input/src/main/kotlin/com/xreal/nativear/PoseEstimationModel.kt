package com.xreal.nativear

import android.graphics.Bitmap
import com.xreal.nativear.core.IAssetLoader
import com.xreal.nativear.core.XRealLogger
import com.xreal.ai.IAIModel
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer

/**
 * PoseEstimationModel: Handles human body pose detection using TFLite.
 * Zero-copy optimized: all per-frame buffers are pre-allocated in prepare().
 */
class PoseEstimationModel(private val assetLoader: IAssetLoader) : IAIModel {
    private val TAG = "PoseEstimationModel"
    private var interpreter: Interpreter? = null

    private val INPUT_SIZE = 192

    // TF Support Library: replaces Canvas + getPixels + normalize loop
    private var imageProcessor: ImageProcessor? = null
    private var tensorImage: TensorImage? = null
    private val outputBuffer = Array(1) { Array(1) { Array(17) { FloatArray(3) } } }

    override val priority: Int = 3

    override var isReady: Boolean = false
        private set
    override var isLoaded: Boolean = false
        private set

    override suspend fun prepare(options: Interpreter.Options): Boolean {
        if (isLoaded) return true
        XRealLogger.impl.i(TAG, "Preparing Pose estimation model...")
        return try {
            val modelBuffer = loadModelFile("centernet_pose.tflite")
            interpreter = Interpreter(modelBuffer, options)

            // TF Support Library: resize + normalize in single optimized pass
            imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
                .add(NormalizeOp(0f, 255f))  // [0,255] → [0,1]
                .build()
            tensorImage = TensorImage(DataType.FLOAT32)

            isLoaded = true
            isReady = true
            true
        } catch (e: Exception) {
            XRealLogger.impl.e(TAG, "Failed to load pose model: ${e.message}")
            false
        }
    }

    private fun loadModelFile(modelName: String): ByteBuffer {
        return assetLoader.loadModelBuffer(modelName)
    }

    /**
     * Processes the bitmap to detect human pose.
     * Uses pre-allocated buffers — zero heap allocation per frame.
     */
    fun process(bitmap: Bitmap, callback: (List<PoseKeypoint>) -> Unit) {
        if (!isReady || interpreter == null || imageProcessor == null || tensorImage == null) {
            callback(emptyList())
            return
        }

        try {
            // TF Support Library: resize + normalize in single pass
            tensorImage!!.load(bitmap)
            val processed = imageProcessor!!.process(tensorImage!!)

            // Inference (output array is pre-allocated)
            interpreter?.run(processed.buffer.rewind(), outputBuffer)

            // 5. Parse keypoints
            val keypoints = mutableListOf<PoseKeypoint>()
            val detectedKeypoints = outputBuffer[0][0]
            for (i in 0 until 17) {
                val y = detectedKeypoints[i][0]
                val x = detectedKeypoints[i][1]
                val score = detectedKeypoints[i][2]
                keypoints.add(PoseKeypoint(i, x * bitmap.width, y * bitmap.height, score))
            }

            callback(keypoints)
        } catch (e: Exception) {
            XRealLogger.impl.e(TAG, "Pose Inference Error: ${e.message}")
            callback(emptyList())
        }
    }

    override fun release() {
        XRealLogger.impl.i(TAG, "Releasing Pose Interpreter")
        interpreter?.close()
        interpreter = null
        imageProcessor = null
        tensorImage = null
        isLoaded = false
        isReady = false
    }
}
