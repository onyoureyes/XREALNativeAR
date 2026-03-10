package com.xreal.nativear.renderer

import org.junit.Test
import org.junit.Assert.*

/**
 * PaceColorMapper 단위 테스트.
 * 페이스메이커 라벨 → RGBA 색상 매핑 검증.
 */
class PaceColorMapperTest {

    @Test
    fun `삼각형 화살표 뒤처짐 라벨은 빨간 계열 색상 반환`() {
        val color = PaceColorMapper.mapLabelToColor("▶ +3m")
        assertEquals(PaceColorMapper.RED, color)
    }

    @Test
    fun `플러스 기호만 있는 라벨도 빨간 계열 색상 반환`() {
        val color = PaceColorMapper.mapLabelToColor("+5m behind")
        assertEquals(PaceColorMapper.RED, color)
    }

    @Test
    fun `삼각형 화살표 앞서감 라벨은 초록 계열 색상 반환`() {
        val color = PaceColorMapper.mapLabelToColor("◀ -2m")
        assertEquals(PaceColorMapper.GREEN, color)
    }

    @Test
    fun `마이너스 기호만 있는 라벨도 초록 계열 색상 반환`() {
        val color = PaceColorMapper.mapLabelToColor("-4m ahead")
        assertEquals(PaceColorMapper.GREEN, color)
    }

    @Test
    fun `체크마크 라벨은 시안 계열 색상 반환`() {
        val color = PaceColorMapper.mapLabelToColor("✓ on pace")
        assertEquals(PaceColorMapper.CYAN, color)
    }

    @Test
    fun `체크마크만 있는 라벨도 시안 계열 색상 반환`() {
        val color = PaceColorMapper.mapLabelToColor("✓")
        assertEquals(PaceColorMapper.CYAN, color)
    }

    @Test
    fun `기타 라벨은 기본 색상(초록) 반환`() {
        val color = PaceColorMapper.mapLabelToColor("unknown label")
        assertEquals(PaceColorMapper.DEFAULT, color)
    }

    @Test
    fun `빈 문자열은 기본 색상 반환`() {
        val color = PaceColorMapper.mapLabelToColor("")
        assertEquals(PaceColorMapper.DEFAULT, color)
    }

    @Test
    fun `RED 색상 RGBA 값 검증`() {
        val red = PaceColorMapper.RED
        assertEquals(1.0f, red.r, 0.001f)
        assertEquals(0.3f, red.g, 0.001f)
        assertEquals(0.2f, red.b, 0.001f)
        assertEquals(0.6f, red.a, 0.001f)
    }

    @Test
    fun `GREEN 색상 RGBA 값 검증`() {
        val green = PaceColorMapper.GREEN
        assertEquals(0.2f, green.r, 0.001f)
        assertEquals(1.0f, green.g, 0.001f)
        assertEquals(0.4f, green.b, 0.001f)
        assertEquals(0.6f, green.a, 0.001f)
    }

    @Test
    fun `CYAN 색상 RGBA 값 검증`() {
        val cyan = PaceColorMapper.CYAN
        assertEquals(0.4f, cyan.r, 0.001f)
        assertEquals(0.9f, cyan.g, 0.001f)
        assertEquals(1.0f, cyan.b, 0.001f)
        assertEquals(0.5f, cyan.a, 0.001f)
    }

    @Test
    fun `DEFAULT는 GREEN과 동일`() {
        assertEquals(PaceColorMapper.GREEN, PaceColorMapper.DEFAULT)
    }

    @Test
    fun `삼각형과 플러스가 모두 포함된 라벨은 빨간색 반환`() {
        // "▶"가 먼저 매칭되므로 RED
        val color = PaceColorMapper.mapLabelToColor("▶ +10m")
        assertEquals(PaceColorMapper.RED, color)
    }
}
