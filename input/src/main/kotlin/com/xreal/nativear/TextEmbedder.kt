package com.xreal.nativear

import android.content.Context
import com.xreal.nativear.core.XRealLogger
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder as MPTextEmbedder
import org.tensorflow.lite.Interpreter

/**
 * TextEmbedder: Generates vector embeddings for text using MediaPipe Universal Sentence Encoder.
 * Used for semantic memory search (Korean + English supported).
 */
class TextEmbedder(private val context: Context) : com.xreal.ai.IAIModel {
    private val TAG = "TextEmbedder"
    private var embedder: MPTextEmbedder? = null
    private var embeddingSize = 100 // USE default, updated after first inference

    override val priority: Int = 2
    override var isReady: Boolean = false
        private set
    override var isLoaded: Boolean = false
        private set

    override suspend fun prepare(options: Interpreter.Options): Boolean {
        if (isLoaded) return true
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("universal_sentence_encoder.tflite")
                .build()
            val embedderOptions = MPTextEmbedder.TextEmbedderOptions.builder()
                .setBaseOptions(baseOptions)
                .build()
            embedder = MPTextEmbedder.createFromOptions(context, embedderOptions)
            isLoaded = true
            isReady = true
            XRealLogger.impl.i(TAG, "MediaPipe TextEmbedder ready (Universal Sentence Encoder)")
            true
        } catch (e: Exception) {
            XRealLogger.impl.e(TAG, "TextEmbedder init failed: ${e.message}")
            false
        }
    }

    /**
     * Generate embedding for input text.
     * Falls back to zero vector if embedder not ready.
     */
    fun getEmbedding(text: String): FloatArray {
        val emb = embedder ?: run {
            XRealLogger.impl.w(TAG, "Embedder not initialized, returning zero vector")
            return FloatArray(embeddingSize) { 0.0f }
        }

        return try {
            val result = emb.embed(text)
            val embedding = result.embeddingResult().embeddings()[0]
            val floatList = embedding.floatEmbedding()
            val arr = FloatArray(floatList.size)
            for (i in arr.indices) arr[i] = floatList[i]
            embeddingSize = arr.size
            l2Normalize(arr)
        } catch (e: Exception) {
            XRealLogger.impl.e(TAG, "Embedding failed: ${e.message}")
            FloatArray(embeddingSize) { 0.0f }
        }
    }

    fun calculateSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        if (vec1.size != vec2.size) return 0f
        var dotProduct = 0f
        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
        }
        return dotProduct // Cosine similarity (L2 normalized vectors)
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSquares = 0f
        for (v in vector) sumSquares += v * v
        val magnitude = kotlin.math.sqrt(sumSquares)
        if (magnitude == 0f) return vector
        for (i in vector.indices) vector[i] /= magnitude
        return vector
    }

    override fun release() {
        embedder?.close()
        embedder = null
        isLoaded = false
        isReady = false
    }
}
