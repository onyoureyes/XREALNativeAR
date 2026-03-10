package com.xreal.nativear.spatial

import org.junit.Assert.*
import org.junit.Test

/**
 * DepthPriors 단위 테스트 — 카테고리별 깊이 추정 + bbox 기반 깊이 추정.
 */
class DepthPriorsTest {

    private val EPSILON = 0.01f

    // ── getCategoryDepth 테스트 ──

    @Test
    fun `getCategoryDepth - 등록된 카테고리 person`() {
        assertEquals(3.0f, DepthPriors.getCategoryDepth("person"), EPSILON)
    }

    @Test
    fun `getCategoryDepth - 등록된 카테고리 car`() {
        assertEquals(8.0f, DepthPriors.getCategoryDepth("car"), EPSILON)
    }

    @Test
    fun `getCategoryDepth - 대소문자 무관`() {
        assertEquals(
            DepthPriors.getCategoryDepth("person"),
            DepthPriors.getCategoryDepth("PERSON"),
            EPSILON
        )
    }

    @Test
    fun `getCategoryDepth - 미등록 카테고리는 기본값 3m`() {
        val depth = DepthPriors.getCategoryDepth("unknown_object_xyz")
        assertEquals(3.0f, depth, EPSILON) // DEFAULT_DEPTH = 3.0f
    }

    @Test
    fun `getCategoryDepth - 모든 값이 양수`() {
        val labels = listOf(
            "person", "cat", "dog", "car", "bus", "truck",
            "chair", "laptop", "bottle", "cup", "tv"
        )
        for (label in labels) {
            val depth = DepthPriors.getCategoryDepth(label)
            assertTrue("$label 깊이($depth)는 양수여야 함", depth > 0f)
        }
    }

    // ── estimateDepthFromBbox 테스트 ──

    @Test
    fun `estimateDepthFromBbox - person bbox 기반 깊이 추정`() {
        // person 실제 높이 = 1.7m, focal=500, bboxHeight=170px
        // depth = 1.7 * 500 / 170 = 5.0m
        val depth = DepthPriors.estimateDepthFromBbox("person", 170f, 500f)
        assertNotNull(depth)
        assertEquals(5.0f, depth!!, EPSILON)
    }

    @Test
    fun `estimateDepthFromBbox - 깊이 범위 제한 (최소)`() {
        // 매우 큰 bbox → 매우 가까움 → MIN_DEPTH(0.3m) 클램프
        val depth = DepthPriors.estimateDepthFromBbox("person", 10000f, 500f)
        assertNotNull(depth)
        assertTrue("깊이(${depth}) >= 0.3m", depth!! >= 0.3f)
    }

    @Test
    fun `estimateDepthFromBbox - 깊이 범위 제한 (최대)`() {
        // 매우 작은 bbox → 매우 멀음 → MAX_DEPTH(20m) 클램프
        val depth = DepthPriors.estimateDepthFromBbox("person", 1f, 500f)
        assertNotNull(depth)
        assertTrue("깊이(${depth}) <= 20m", depth!! <= 20f)
    }

    @Test
    fun `estimateDepthFromBbox - 알 수 없는 라벨은 null`() {
        assertNull(DepthPriors.estimateDepthFromBbox("unknown_xyz", 100f, 500f))
    }

    @Test
    fun `estimateDepthFromBbox - bboxHeight 0이면 null`() {
        assertNull(DepthPriors.estimateDepthFromBbox("person", 0f, 500f))
    }

    @Test
    fun `estimateDepthFromBbox - bboxHeight 음수이면 null`() {
        assertNull(DepthPriors.estimateDepthFromBbox("person", -10f, 500f))
    }

    // ── hasKnownHeight 테스트 ──

    @Test
    fun `hasKnownHeight - person은 true`() {
        assertTrue(DepthPriors.hasKnownHeight("person"))
    }

    @Test
    fun `hasKnownHeight - 미등록 라벨은 false`() {
        assertFalse(DepthPriors.hasKnownHeight("airplane"))
    }

    // ── getOcrDepth 테스트 ──

    @Test
    fun `getOcrDepth - 큰 간판은 5m`() {
        assertEquals(5.0f, DepthPriors.getOcrDepth(20, 0.1f), EPSILON)
    }

    @Test
    fun `getOcrDepth - 중간 텍스트는 3m`() {
        assertEquals(3.0f, DepthPriors.getOcrDepth(10, 0.03f), EPSILON)
    }

    @Test
    fun `getOcrDepth - 작은 텍스트는 1_5m`() {
        assertEquals(1.5f, DepthPriors.getOcrDepth(3, 0.01f), EPSILON)
    }
}
