package com.xreal.nativear

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp

class ImageEmbedder(private val context: Context) : com.xreal.ai.IAIModel {
    private var interpreter: Interpreter? = null
    private val TAG = "ImageEmbedder"
    
    override val priority: Int = 4 // Medium
    
    override var isReady: Boolean = false
        private set
    override var isLoaded: Boolean = false
        private set
    
    // MobileNetV3 Large (Feature Vector) - bundled in assets
    // Input: 224x224 RGB, Output: 1280-dim Float Vector
    private val MODEL_NAME = "mobilenet_v3_feature_vector.tflite"

    private var embeddingSize: Int = 1280

    // Pre-allocated processing pipeline (created once in prepare, reused every call)
    private var imageProcessor: ImageProcessor? = null
    private var tensorImage: TensorImage? = null
    private var outputArray: Array<FloatArray>? = null

    override suspend fun prepare(options: Interpreter.Options): Boolean {
        if (isLoaded) return true

        return try {
            val modelBuffer = FileUtil.loadMappedFile(context, MODEL_NAME)
            val interp = Interpreter(modelBuffer, options)
            interpreter = interp

            // Detect output size
            val outputShape = interp.getOutputTensor(0).shape() // [1, size]
            embeddingSize = outputShape[1]

            // Pre-allocate processing pipeline
            imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(224, 224, ResizeOp.ResizeMethod.BILINEAR))
                .add(NormalizeOp(0f, 255f))
                .build()
            tensorImage = TensorImage(DataType.FLOAT32)
            outputArray = Array(1) { FloatArray(embeddingSize) }

            Log.i(TAG, "Embedder Initialized with Size: $embeddingSize")
            isLoaded = true
            isReady = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Init Failed: ${e.message}")
            false
        }
    }

    fun getEmbeddingSize(): Int = embeddingSize

    fun embed(bitmap: Bitmap): FloatArray? {
        val interp = interpreter ?: return null
        val proc = imageProcessor ?: return null
        val ti = tensorImage ?: return null
        val out = outputArray ?: return null

        try {
            // Reuse pre-allocated TensorImage + ImageProcessor
            ti.load(bitmap)
            val processed = proc.process(ti)

            // Reuse pre-allocated output array
            interp.run(processed.buffer, out)

            return l2Normalize(out[0])
        } catch (e: Exception) {
            Log.e(TAG, "Embedding Failed: ${e.message}")
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
        return dotProduct // Since vectors are L2 normalized, dot product = cosine similarity
    }

    
    override fun release() {
        interpreter?.close()
    }
}
