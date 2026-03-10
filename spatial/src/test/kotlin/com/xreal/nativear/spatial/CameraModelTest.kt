package com.xreal.nativear.spatial

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * CameraModel 단위 테스트 — 핀홀 카메라 투영/역투영 수학 검증.
 */
class CameraModelTest {

    private val camera = CameraModel(
        fx = 500f, fy = 500f, cx = 320f, cy = 240f,
        imageWidth = 640, imageHeight = 480
    )

    // ── 투영 테스트 ──

    @Test
    fun `project - 카메라 정면 물체가 주점에 투영된다`() {
        // 카메라 정면 (0,0,5) → 주점 (cx, cy) 에 투영
        val result = camera.project(0f, 0f, 5f)
        assertNotNull(result)
        assertEquals(320f, result!!.first, 0.01f)
        assertEquals(240f, result.second, 0.01f)
    }

    @Test
    fun `project - 우측 물체가 우측에 투영된다`() {
        // X=1, Y=0, Z=5 → u = 500*(1/5)+320 = 420
        val result = camera.project(1f, 0f, 5f)
        assertNotNull(result)
        assertEquals(420f, result!!.first, 0.01f)
        assertEquals(240f, result.second, 0.01f)
    }

    @Test
    fun `project - 카메라 뒤 물체는 null 반환`() {
        // Z <= 0.01 이면 null
        assertNull(camera.project(1f, 1f, 0f))
        assertNull(camera.project(1f, 1f, -1f))
        assertNull(camera.project(1f, 1f, 0.005f))
    }

    @Test
    fun `project - 최소 깊이 경계에서 정상 투영`() {
        // Z = 0.02 (MIN_DEPTH=0.01 초과)
        val result = camera.project(0f, 0f, 0.02f)
        assertNotNull(result)
    }

    // ── 역투영 테스트 ──

    @Test
    fun `unproject - 주점에서 역투영하면 정면 좌표`() {
        // (cx, cy, depth=5) → (0, 0, 5)
        val result = camera.unproject(320f, 240f, 5f)
        assertEquals(0f, result[0], 0.01f)
        assertEquals(0f, result[1], 0.01f)
        assertEquals(5f, result[2], 0.01f)
    }

    @Test
    fun `unproject - 깊이가 정확히 z 좌표로 매핑된다`() {
        val depth = 10f
        val result = camera.unproject(320f, 240f, depth)
        assertEquals(depth, result[2], 0.001f)
    }

    // ── 투영 → 역투영 왕복 일관성 ──

    @Test
    fun `project - unproject 왕복 일관성`() {
        val points = arrayOf(
            floatArrayOf(1f, 2f, 5f),
            floatArrayOf(-0.5f, 0.3f, 3f),
            floatArrayOf(0f, 0f, 10f),
            floatArrayOf(2f, -1f, 8f),
        )
        for (pt in points) {
            val projected = camera.project(pt[0], pt[1], pt[2])
            assertNotNull("투영 실패: ${pt.contentToString()}", projected)
            val unprojected = camera.unproject(projected!!.first, projected.second, pt[2])
            assertEquals("X 불일치: ${pt.contentToString()}", pt[0], unprojected[0], 0.01f)
            assertEquals("Y 불일치: ${pt.contentToString()}", pt[1], unprojected[1], 0.01f)
            assertEquals("Z 불일치: ${pt.contentToString()}", pt[2], unprojected[2], 0.001f)
        }
    }

    // ── isVisible 테스트 ──

    @Test
    fun `isVisible - 화면 내 물체는 true`() {
        assertTrue(camera.isVisible(0f, 0f, 5f))
    }

    @Test
    fun `isVisible - 화면 밖 물체는 false`() {
        // 매우 큰 X → 이미지 범위 초과
        assertFalse(camera.isVisible(100f, 0f, 5f))
    }

    @Test
    fun `isVisible - 카메라 뒤 물체는 false`() {
        assertFalse(camera.isVisible(0f, 0f, -1f))
    }

    @Test
    fun `isVisible - margin 내 경계 물체는 true`() {
        // 이미지 경계 바로 밖이지만 margin 내
        // u = fx*(X/Z) + cx = 500*(6.5/10) + 320 = 645, imageWidth=640
        // margin=0.05 → mx=32, 범위 -32 ~ 672 → 645 < 672 → visible
        assertTrue(camera.isVisible(6.5f, 0f, 10f, margin = 0.05f))
    }

    // ── pixelToPercent 테스트 ──

    @Test
    fun `pixelToPercent - 이미지 중심은 50퍼센트`() {
        val (xPct, yPct) = camera.pixelToPercent(320f, 240f)
        assertEquals(50f, xPct, 0.01f)
        assertEquals(50f, yPct, 0.01f)
    }

    @Test
    fun `pixelToPercent - 좌상단은 0퍼센트`() {
        val (xPct, yPct) = camera.pixelToPercent(0f, 0f)
        assertEquals(0f, xPct, 0.01f)
        assertEquals(0f, yPct, 0.01f)
    }

    @Test
    fun `pixelToPercent - 우하단은 100퍼센트`() {
        val (xPct, yPct) = camera.pixelToPercent(640f, 480f)
        assertEquals(100f, xPct, 0.01f)
        assertEquals(100f, yPct, 0.01f)
    }

    // ── distanceFromCamera 테스트 ──

    @Test
    fun `distanceFromCamera - 유클리드 거리 정확성`() {
        // (3, 4, 0) → 거리 5
        assertEquals(5f, camera.distanceFromCamera(3f, 4f, 0f), 0.001f)
    }

    @Test
    fun `distanceFromCamera - 원점은 0`() {
        assertEquals(0f, camera.distanceFromCamera(0f, 0f, 0f), 0.001f)
    }

    // ── 팩토리 메서드 테스트 ──

    @Test
    fun `slamCamera - 파라미터 검증`() {
        val slam = CameraModel.slamCamera()
        assertEquals(640, slam.imageWidth)
        assertEquals(480, slam.imageHeight)
        assertTrue(slam.fx > 300f && slam.fx < 400f) // ~350.71
    }

    @Test
    fun `rgbCamera - 파라미터 검증`() {
        val rgb = CameraModel.rgbCamera()
        assertEquals(1280, rgb.imageWidth)
        assertEquals(960, rgb.imageHeight)
    }

    // ── rgbToSlamPixel 테스트 ──

    @Test
    fun `rgbToSlamPixel - 중심 좌표 변환`() {
        val (su, sv) = CameraModel.rgbToSlamPixel(640f, 480f)
        assertEquals(320f, su, 0.01f)
        assertEquals(240f, sv, 0.01f)
    }
}
