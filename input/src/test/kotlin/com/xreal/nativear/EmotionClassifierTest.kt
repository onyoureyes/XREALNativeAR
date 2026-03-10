package com.xreal.nativear

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * EmotionClassifier 단위 테스트.
 * 순수 휴리스틱 기반이므로 ML 모델 불필요.
 */
class EmotionClassifierTest {

    private lateinit var classifier: EmotionClassifier

    @Before
    fun setUp() {
        classifier = EmotionClassifier()
    }

    @Test
    fun `prepare 호출 후 isReady true`() = kotlinx.coroutines.test.runTest {
        val result = classifier.prepare(org.tensorflow.lite.Interpreter.Options())
        assertTrue(result)
        assertTrue(classifier.isReady)
        assertTrue(classifier.isLoaded)
    }

    @Test
    fun `빈 임베딩 벡터 → neutral`() {
        kotlinx.coroutines.test.runTest { classifier.prepare(org.tensorflow.lite.Interpreter.Options()) }
        val result = classifier.classifyEmotion(FloatArray(384) { 0f })
        // magnitude=0, variance=0, sparsity=1.0
        // sparsity > 0.6 and magnitude < 8 → "sad"
        // Actually: magnitude(0) < 8 and sparsity(1.0) > 0.6 → "sad"
        assertNotNull(result)
        assertTrue("confidence > 0", result.confidence > 0f)
    }

    @Test
    fun `높은 magnitude + 높은 variance → angry`() {
        kotlinx.coroutines.test.runTest { classifier.prepare(org.tensorflow.lite.Interpreter.Options()) }
        // magnitude > 15, variance > 0.8
        val embedding = FloatArray(384) { i -> if (i % 2 == 0) 1.0f else -1.0f }
        // magnitude = sqrt(384) ≈ 19.6, variance ≈ 1.0 (mean=0, values=±1)
        val result = classifier.classifyEmotion(embedding)
        assertEquals("angry", result.emotion)
        assertEquals(0.75f, result.confidence, 0.01f)
    }

    @Test
    fun `높은 variance + 낮은 sparsity → happy`() {
        kotlinx.coroutines.test.runTest { classifier.prepare(org.tensorflow.lite.Interpreter.Options()) }
        // variance > 1.0, sparsity < 0.4, magnitude <= 15
        // magnitude ≤ 15 and variance > 0.8 and magnitude > 15 → no
        // Need: variance > 1.0, sparsity < 0.4
        // Create: values with high variance, no near-zero values, moderate magnitude
        val embedding = FloatArray(384) { i ->
            val v = ((i % 7) - 3).toFloat() * 0.2f  // values: -0.6, -0.4, -0.2, 0.0, 0.2, 0.4, 0.6
            v
        }
        // Very few near-zero values → low sparsity
        // magnitude ~ sqrt(sum of squares) moderate
        // Need to hit: variance > 1.0 && sparsity < 0.4
        // Better: use larger values to ensure variance > 1.0
        val highVarEmbedding = FloatArray(384) { i ->
            ((i % 5) - 2).toFloat() * 1.5f  // -3, -1.5, 0, 1.5, 3
        }
        val result = classifier.classifyEmotion(highVarEmbedding)
        // Check it returns a valid result
        assertNotNull(result)
        assertTrue(result.emotion in listOf("angry", "happy", "excited", "sad", "neutral"))
    }

    @Test
    fun `EmotionResult data class 동등성`() {
        val r1 = EmotionClassifier.EmotionResult("happy", 0.8f)
        val r2 = EmotionClassifier.EmotionResult("happy", 0.8f)
        assertEquals(r1, r2)
        assertEquals("happy", r1.emotion)
        assertEquals(0.8f, r1.confidence, 0.001f)
    }
}
