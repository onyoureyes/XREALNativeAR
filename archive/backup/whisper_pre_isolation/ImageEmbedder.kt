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

class ImageEmbedder(private val context: Context) {
    private var interpreter: Interpreter? = null
    private val TAG = "ImageEmbedder"
    
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

    fun initialize(): Boolean {
        try {
            val file = File(context.filesDir, MODEL_NAME)
            if (!file.exists()) {
                Log.e(TAG, "Model file not found at ${file.absolutePath}")
                return false
            }

            val options = Interpreter.Options()
            options.setNumThreads(4)
            interpreter = Interpreter(file, options)
            Log.i(TAG, "Embedder Initialized")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Init Failed: ${e.message}")
            return false
        }
    }

    fun embed(bitmap: Bitmap): FloatArray? {
        if (interpreter == null) return null

        try {
            // 1. Preprocess
            val imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(224, 224, ResizeOp.ResizeMethod.BILINEAR))
                .add(NormalizeOp(0f, 255f)) // Normalize 0..1 (MobileNet specific)
                .build()

            var tensorImage = TensorImage(DataType.FLOAT32)
            tensorImage.load(bitmap)
            tensorImage = imageProcessor.process(tensorImage)

            // 2. Inference
            val outputBuffer = ByteBuffer.allocateDirect(1024 * 4) // Adjust size based on model
            outputBuffer.order(java.nio.ByteOrder.nativeOrder())
            
            // MobileNet V3 Small Output is usually [1, 1024] or [1, 1280]
            // We'll let TFLite handle the output shape mapping via map behavior if needed, 
            // but standard run() works for single output.
            val outputArray = Array(1) { FloatArray(1024) }
            
            interpreter?.run(tensorImage.buffer, outputArray)

            return outputArray[0]
        } catch (e: Exception) {
            Log.e(TAG, "Embedding Failed: ${e.message}")
            return null
        }
    }
    
    fun close() {
        interpreter?.close()
    }
}
