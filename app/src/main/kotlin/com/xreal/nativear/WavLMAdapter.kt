package com.xreal.nativear

import android.content.Context
import android.util.Log
import com.xreal.ai.IAIModel
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * WavLMAdapter: Extracts high-dimensional audio embeddings for Speaker ID and Emotion.
 * Uses the WavLM-Base-Plus model.
 */
class WavLMAdapter(private val context: Context) : IAIModel {
    private val TAG = "WavLMAdapter"
    private var interpreter: Interpreter? = null
    
    override val priority: Int = 8 // High priority for real-time analysis
    override var isReady: Boolean = false
        private set
    override var isLoaded: Boolean = false
        private set

    override suspend fun prepare(options: Interpreter.Options): Boolean {
        if (isLoaded) return true
        return try {
            val modelName = "huggingface_wavlm_base_plus.tflite"
            loadModel(modelName, options)
            isLoaded = true
            isReady = true
            Log.i(TAG, "WavLM Model Loaded Successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load WavLM: ${e.message}")
            false
        }
    }

    private fun loadModel(fileName: String, options: Interpreter.Options) {
        val assetFileDescriptor = context.assets.openFd(fileName)
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        
        interpreter = Interpreter(modelBuffer, options)
    }

    /**
     * Extracts embedding from raw PCM audio (16kHz).
     * @param audioData: ShortArray of 16kHz mono audio.
     * @return FloatArray representing the audio features.
     */
    fun extractEmbedding(audioData: ShortArray): FloatArray? {
        if (!isReady || interpreter == null) return null

        try {
            // WavLM usually expects float input normalized to [-1, 1]
            val floatInput = FloatArray(audioData.size) { audioData[it] / 32768f }
            
            // Input shape: [1, seq_len]
            // Output shape depends on the TFLite conversion (usually [1, 768] for pooled base)
            val outputTensor = interpreter?.getOutputTensor(0)
            val outputShape = outputTensor?.shape() ?: intArrayOf(1, 768)
            val outputBuffer = FloatArray(outputShape.last())
            
            // Note: If the model isn't pooled, we'd need to handle sequence dimension
            // For simplicity, we assume a pooled output or we'll need to adapt it.
            interpreter?.run(arrayOf(floatInput), arrayOf(outputBuffer))
            
            return outputBuffer
        } catch (e: Exception) {
            Log.e(TAG, "Inference Error: ${e.message}")
            return null
        }
    }

    override fun release() {
        interpreter?.close()
        interpreter = null
        isReady = false
        isLoaded = false
    }
}
