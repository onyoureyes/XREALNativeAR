package com.xreal.nativear

import android.util.Log

/**
 * EmotionClassifier: Classifies emotion from audio embeddings.
 * Uses simple heuristics on embedding vector patterns.
 * For production, use a trained ML model.
 */
class EmotionClassifier {
    private val TAG = "EmotionClassifier"
    
    data class EmotionResult(
        val emotion: String,
        val confidence: Float
    )
    
    /**
     * Classify emotion from audio embedding vector.
     * @param audioEmbedding: FloatArray of audio embedding (384-dim for Whisper)
     * @return EmotionResult with emotion label and confidence score
     */
    fun classifyEmotion(audioEmbedding: FloatArray): EmotionResult {
        // Simplified heuristic-based classification
        // In production, use a trained model (e.g., SVM, Neural Network)
        
        val magnitude = calculateMagnitude(audioEmbedding)
        val variance = calculateVariance(audioEmbedding)
        val sparsity = calculateSparsity(audioEmbedding)
        
        // Heuristic rules (placeholder logic)
        val (emotion, confidence) = when {
            magnitude > 15.0f && variance > 0.8f -> "angry" to 0.75f
            magnitude < 8.0f && sparsity > 0.6f -> "sad" to 0.70f
            variance > 1.0f && sparsity < 0.4f -> "happy" to 0.80f
            magnitude > 12.0f && variance < 0.5f -> "excited" to 0.72f
            else -> "neutral" to 0.60f
        }
        
        Log.d(TAG, "Classified emotion: $emotion (confidence: $confidence)")
        return EmotionResult(emotion, confidence)
    }
    
    private fun calculateMagnitude(vector: FloatArray): Float {
        var sumSquares = 0f
        for (value in vector) {
            sumSquares += value * value
        }
        return kotlin.math.sqrt(sumSquares)
    }
    
    private fun calculateVariance(vector: FloatArray): Float {
        val mean = vector.average().toFloat()
        var sumSquaredDiff = 0f
        for (value in vector) {
            val diff = value - mean
            sumSquaredDiff += diff * diff
        }
        return sumSquaredDiff / vector.size
    }
    
    private fun calculateSparsity(vector: FloatArray): Float {
        val threshold = 0.01f
        val nearZeroCount = vector.count { kotlin.math.abs(it) < threshold }
        return nearZeroCount.toFloat() / vector.size
    }
}
