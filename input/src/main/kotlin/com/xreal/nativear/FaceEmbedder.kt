package com.xreal.nativear

import android.graphics.Bitmap
import com.xreal.nativear.core.IAssetLoader
import com.xreal.nativear.core.XRealLogger
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp

/**
 * FaceEmbedder: MobileFaceNet-based face embedding for person identification.
 *
 * Input:  (1, 112, 112, 3) float32 RGB [0, 1]
 * Output: (1, 192) float32 — L2-normalized face embedding
 *
 * Pattern: mirrors ImageEmbedder with pre-allocated buffers.
 */
class FaceEmbedder(private val assetLoader: IAssetLoader) : com.xreal.ai.IAIModel {

    private val TAG = "FaceEmbedder"
    private val MODEL_FILE = "mobilefacenet.tflite"
    private val INPUT_SIZE = 112

    private var interpreter: Interpreter? = null
    private var imageProcessor: ImageProcessor? = null
    private var tensorImage: TensorImage? = null
    private var outputArray: Array<FloatArray>? = null
    var embeddingSize: Int = 192
        private set

    override val priority: Int = 5
    override var isReady: Boolean = false
        private set
    override var isLoaded: Boolean = false
        private set

    override suspend fun prepare(options: Interpreter.Options): Boolean {
        if (isLoaded) return true

        return try {
            val modelBuffer = assetLoader.loadModelBuffer(MODEL_FILE)
            val interp = Interpreter(modelBuffer, options)

            // Model default input is [2, 112, 112, 3] — resize to [1, 112, 112, 3]
            interp.resizeInput(0, intArrayOf(1, INPUT_SIZE, INPUT_SIZE, 3))
            interp.allocateTensors()

            interpreter = interp

            // Detect output embedding size
            val outputShape = interp.getOutputTensor(0).shape() // [1, 192]
            embeddingSize = outputShape[1]

            imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
                .add(NormalizeOp(0f, 255f))  // [0, 255] → [0, 1]
                .build()
            tensorImage = TensorImage(DataType.FLOAT32)
            outputArray = Array(1) { FloatArray(embeddingSize) }

            XRealLogger.impl.i(TAG, "MobileFaceNet loaded: embedding=$embeddingSize dim")
            isLoaded = true
            isReady = true
            true
        } catch (e: Exception) {
            XRealLogger.impl.e(TAG, "Failed to load MobileFaceNet: ${e.message}", e)
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
     * Generate face embedding from a face crop bitmap.
     * @param faceCrop Bitmap of a cropped face region
     * @return L2-normalized 192d embedding, or null on failure
     */
    fun embed(faceCrop: Bitmap): FloatArray? {
        val interp = interpreter ?: return null
        val proc = imageProcessor ?: return null
        val ti = tensorImage ?: return null
        val out = outputArray ?: return null

        try {
            ti.load(faceCrop)
            val processed = proc.process(ti)

            interp.run(processed.buffer, out)

            return l2Normalize(out[0])
        } catch (e: Exception) {
            XRealLogger.impl.e(TAG, "Face embedding failed: ${e.message}")
            return null
        }
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val mag = Math.sqrt(sum.toDouble()).toFloat()
        if (mag == 0f) return v
        for (i in v.indices) v[i] /= mag
        return v
    }

    fun calculateSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        if (vec1.size != vec2.size) return 0f
        var dotProduct = 0f
        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
        }
        return dotProduct  // L2 normalized → dot product = cosine similarity
    }
}
