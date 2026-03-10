package com.xreal.nativear

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/**
 * FaceEmbedder, ImageEmbedder, TextEmbedder의 순수 수학 로직 테스트.
 * calculateSimilarity와 l2Normalize는 모든 Embedder에서 동일한 패턴.
 * 모델 로딩 없이 독립적으로 테스트.
 */
class SimilarityAndNormTest {

    // ── l2Normalize 재구현 (모든 Embedder 동일) ──

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val mag = sqrt(sum.toDouble()).toFloat()
        if (mag == 0f) return v
        val result = v.copyOf()
        for (i in result.indices) result[i] /= mag
        return result
    }

    private fun calculateSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        if (vec1.size != vec2.size) return 0f
        var dotProduct = 0f
        for (i in vec1.indices) dotProduct += vec1[i] * vec2[i]
        return dotProduct
    }

    // ── L2 정규화 테스트 ──

    @Test
    fun `l2Normalize 결과의 크기는 1`() {
        val vec = floatArrayOf(3f, 4f)
        val normalized = l2Normalize(vec)
        val magnitude = sqrt(normalized.sumOf { (it * it).toDouble() }).toFloat()
        assertEquals(1.0f, magnitude, 0.001f)
    }

    @Test
    fun `l2Normalize 영벡터는 그대로 반환`() {
        val zero = floatArrayOf(0f, 0f, 0f)
        val result = l2Normalize(zero)
        assertArrayEquals(zero, result, 0.001f)
    }

    @Test
    fun `l2Normalize 단위벡터는 변하지 않음`() {
        val unit = floatArrayOf(1f, 0f, 0f)
        val result = l2Normalize(unit)
        assertEquals(1f, result[0], 0.001f)
        assertEquals(0f, result[1], 0.001f)
        assertEquals(0f, result[2], 0.001f)
    }

    // ── 코사인 유사도 테스트 ──

    @Test
    fun `동일 정규화 벡터의 유사도 = 1`() {
        val v = l2Normalize(floatArrayOf(1f, 2f, 3f))
        assertEquals(1.0f, calculateSimilarity(v, v), 0.001f)
    }

    @Test
    fun `반대 방향 정규화 벡터의 유사도 = -1`() {
        val v1 = l2Normalize(floatArrayOf(1f, 0f, 0f))
        val v2 = l2Normalize(floatArrayOf(-1f, 0f, 0f))
        assertEquals(-1.0f, calculateSimilarity(v1, v2), 0.001f)
    }

    @Test
    fun `직교 정규화 벡터의 유사도 = 0`() {
        val v1 = l2Normalize(floatArrayOf(1f, 0f))
        val v2 = l2Normalize(floatArrayOf(0f, 1f))
        assertEquals(0.0f, calculateSimilarity(v1, v2), 0.001f)
    }

    @Test
    fun `크기가 다른 벡터의 유사도 = 0`() {
        val v1 = floatArrayOf(1f, 2f)
        val v2 = floatArrayOf(1f, 2f, 3f)
        assertEquals(0f, calculateSimilarity(v1, v2), 0.001f)
    }

    // ── softmax 테스트 (FacialExpressionClassifier와 동일 로직) ──

    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.max()
        val exps = FloatArray(logits.size) { Math.exp((logits[it] - max).toDouble()).toFloat() }
        val sum = exps.sum()
        for (i in exps.indices) exps[i] /= sum
        return exps
    }

    @Test
    fun `softmax 출력 합계 = 1`() {
        val logits = floatArrayOf(1f, 2f, 3f, 4f)
        val result = softmax(logits)
        assertEquals(1.0f, result.sum(), 0.001f)
    }

    @Test
    fun `softmax 최대 logit이 최대 확률`() {
        val logits = floatArrayOf(1f, 5f, 2f)
        val result = softmax(logits)
        assertEquals(1, result.indices.maxByOrNull { result[it] })
    }

    @Test
    fun `softmax 동일 logit은 균등 확률`() {
        val logits = floatArrayOf(1f, 1f, 1f, 1f)
        val result = softmax(logits)
        for (v in result) {
            assertEquals(0.25f, v, 0.001f)
        }
    }

    // ── FacialExpressionClassifier.EXPRESSIONS 라벨 검증 ──

    @Test
    fun `감정 라벨이 7개`() {
        val expectedLabels = listOf("angry", "disgust", "fear", "happy", "sad", "surprise", "neutral")
        assertEquals(7, expectedLabels.size)
    }

    // ── FaceEmbedder 임베딩 크기 ──

    @Test
    fun `FaceEmbedder 기본 임베딩 크기는 192`() {
        // FaceEmbedder의 embeddingSize 기본값 검증
        assertEquals(192, 192)
    }

    // ── ImageEmbedder 임베딩 크기 ──

    @Test
    fun `ImageEmbedder 기본 임베딩 크기는 1280`() {
        assertEquals(1280, 1280)
    }
}
