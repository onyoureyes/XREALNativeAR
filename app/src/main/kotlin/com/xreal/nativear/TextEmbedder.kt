package com.xreal.nativear

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TextEmbedder: Generates vector embeddings for memory text using TFLite.
 */
class TextEmbedder(private val context: Context) {
    private val TAG = "TextEmbedder"
    private var interpreter: Interpreter? = null

    init {
        try {
            val modelBuffer = loadModelFile("text_embedder.tflite")
            interpreter = Interpreter(modelBuffer)
            Log.i(TAG, "✅ TextEmbedder Ready")
        } catch (e: Exception) {
            Log.e(TAG, "❌ TextEmbedder Init Failed: ${e.message}")
        }
    }

    private fun loadModelFile(modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    /**
     * Simple character-level tokenization for text embedding.
     * For production, use a proper tokenizer (e.g., SentencePiece, WordPiece).
     */
    private fun tokenize(text: String, maxLength: Int = 128): IntArray {
        val tokens = IntArray(maxLength) { 0 } // Padding with 0
        val chars = text.lowercase().toCharArray()
        
        for (i in 0 until minOf(chars.size, maxLength)) {
            // Simple ASCII mapping: 'a' = 1, 'b' = 2, ..., space = 27, etc.
            tokens[i] = when (val c = chars[i]) {
                in 'a'..'z' -> c - 'a' + 1
                ' ' -> 27
                else -> 28 // Unknown character
            }
        }
        return tokens
    }

    fun getEmbedding(text: String): FloatArray {
        if (interpreter == null) {
            Log.w(TAG, "Interpreter not initialized, returning zero vector")
            return FloatArray(384) { 0.0f }
        }
        
        try {
            // 1. Tokenize input text
            val tokens = tokenize(text)
            
            // 2. Prepare input buffer
            val inputBuffer = java.nio.ByteBuffer.allocateDirect(tokens.size * 4).apply {
                order(java.nio.ByteOrder.nativeOrder())
                tokens.forEach { putInt(it) }
                rewind()
            }
            
            // 3. Prepare output buffer
            val outputBuffer = java.nio.ByteBuffer.allocateDirect(384 * 4).apply {
                order(java.nio.ByteOrder.nativeOrder())
            }
            
            // 4. Run inference
            interpreter?.run(inputBuffer, outputBuffer)
            
            // 5. Extract output
            outputBuffer.rewind()
            val embedding = FloatArray(384)
            outputBuffer.asFloatBuffer().get(embedding)
            
            // 6. L2 Normalize
            return l2Normalize(embedding)
        } catch (e: Exception) {
            Log.e(TAG, "Embedding failed: ${e.message}", e)
            return FloatArray(384) { 0.0f }
        }
    }
    
    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSquares = 0f
        for (value in vector) {
            sumSquares += value * value
        }
        val magnitude = kotlin.math.sqrt(sumSquares)
        
        if (magnitude == 0f) return vector
        
        for (i in vector.indices) {
            vector[i] /= magnitude
        }
        return vector
    }
    
    fun calculateSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        if (vec1.size != vec2.size) return 0f
        
        var dotProduct = 0f
        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
        }
        return dotProduct // Cosine similarity (vectors are L2 normalized)
    }
    
    fun release() {
        interpreter?.close()
        interpreter = null
    }
}
