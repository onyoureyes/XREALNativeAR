package com.xreal.nativear

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.ByteBuffer

class ImageEmbedder(private val context: Context) : com.xreal.ai.IAIModel {
    private var interpreter: Interpreter? = null
    private val TAG = "ImageEmbedder"
    
    override val priority: Int = 4 // Medium
    
    override var isReady: Boolean = false
        private set
    override var isLoaded: Boolean = false
        private set
    
    // MobileNetV3 Small (Feature Vector)
    // Input: 224x224 RGB
    // Output: 1024 Float Vector (or similar depending on variant)
    private val MODEL_NAME = "mobilenet_v3_small.tflite"
    private val MODEL_URL = "https://tfhub.dev/google/lite-model/imagenet/mobilenet_v3_small_100_224/feature_vector/5/default/1?lite-format=tflite"

    fun isModelReady(): Boolean {
        return File(context.filesDir, MODEL_NAME).exists()
    }

    suspend fun downloadModel(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Downloading embedding model from $MODEL_URL...")
                val url = URL(MODEL_URL)
                val connection = url.openConnection()
                connection.connect()
                
                val input = connection.getInputStream()
                val file = File(context.filesDir, MODEL_NAME)
                val output = FileOutputStream(file)
                
                val buffer = ByteArray(4096)
                var bytesRead = input.read(buffer)
                while (bytesRead != -1) {
                    output.write(buffer, 0, bytesRead)
                    bytesRead = input.read(buffer)
                }
                
                output.close()
                input.close()
                Log.i(TAG, "Download complete: ${file.absolutePath}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}")
                false
            }
        }
    }

    private var embeddingSize: Int = 1024

    override suspend fun prepare(options: Interpreter.Options): Boolean {
        if (isLoaded) return true
        
        // 1. Ensure Model exists
        if (!isModelReady()) {
            val downloaded = downloadModel()
            if (!downloaded) return false
        }

        // 2. Initialize
        return try {
            val file = File(context.filesDir, MODEL_NAME)
            val interp = Interpreter(file, options)
            interpreter = interp
            
            // Detect output size
            val outputShape = interp.getOutputTensor(0).shape() // [1, size]
            embeddingSize = outputShape[1]
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
        if (interpreter == null) return null

        try {
            // 1. Preprocess
            val imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(224, 224, ResizeOp.ResizeMethod.BILINEAR))
                .add(NormalizeOp(0f, 255f))
                .build()

            var tensorImage = TensorImage(DataType.FLOAT32)
            tensorImage.load(bitmap)
            tensorImage = imageProcessor.process(tensorImage)

            // 2. Inference
            val outputArray = Array(1) { FloatArray(embeddingSize) }
            interpreter?.run(tensorImage.buffer, outputArray)

            // 3. Post-process: L2 Normalization
            return l2Normalize(outputArray[0])
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

    
    fun close() {
        interpreter?.close()
    }
}
