package com.xreal.nativear

import org.junit.Test
import org.junit.Assert.*

class DrawElementTest {

    // ─── DrawElement 서브타입 생성 ───

    @Test
    fun `Text 생성 및 기본값`() {
        val text = DrawElement.Text(
            id = "test_1", x = 50f, y = 50f, text = "Hello"
        )
        assertEquals("test_1", text.id)
        assertEquals("#00FFFF", text.color) // 기본 cyan
        assertEquals(1f, text.opacity, 0.001f)
        assertEquals(24f, text.size, 0.001f)
        assertFalse(text.bold)
    }

    @Test
    fun `Rect 생성 및 기본값`() {
        val rect = DrawElement.Rect(
            id = "rect_1", x = 10f, y = 20f, width = 30f, height = 40f
        )
        assertEquals("#00FF00", rect.color) // 기본 green
        assertEquals(0.6f, rect.opacity, 0.001f)
        assertFalse(rect.filled)
        assertEquals(3f, rect.strokeWidth, 0.001f)
    }

    @Test
    fun `Circle 생성`() {
        val circle = DrawElement.Circle(
            id = "cir_1", cx = 50f, cy = 50f, radius = 10f
        )
        assertEquals(50f, circle.cx, 0.001f)
        assertEquals(10f, circle.radius, 0.001f)
        assertFalse(circle.filled)
    }

    @Test
    fun `Line 생성`() {
        val line = DrawElement.Line(
            id = "line_1", x1 = 0f, y1 = 0f, x2 = 100f, y2 = 100f
        )
        assertEquals(0f, line.x1, 0.001f)
        assertEquals(100f, line.x2, 0.001f)
    }

    @Test
    fun `Arrow 생성`() {
        val arrow = DrawElement.Arrow(
            id = "arr_1", x1 = 10f, y1 = 20f, x2 = 80f, y2 = 90f
        )
        assertEquals(4f, arrow.strokeWidth, 0.001f)
        assertEquals("#FF6600", arrow.color)
    }

    @Test
    fun `Highlight 생성 및 반투명 기본값`() {
        val hl = DrawElement.Highlight(
            id = "hl_1", x = 10f, y = 10f, width = 50f, height = 20f
        )
        assertEquals(0.3f, hl.opacity, 0.001f) // 하이라이트는 반투명
    }

    @Test
    fun `Polyline 생성`() {
        val points = listOf(0f to 0f, 50f to 50f, 100f to 0f)
        val poly = DrawElement.Polyline(
            id = "poly_1", points = points, closed = true
        )
        assertEquals(3, poly.points.size)
        assertTrue(poly.closed)
    }

    @Test
    fun `Polyline 빈 포인트`() {
        val poly = DrawElement.Polyline(
            id = "poly_empty", points = emptyList()
        )
        assertTrue(poly.points.isEmpty())
        assertFalse(poly.closed)
    }

    // ─── sealed class 다형성 ───

    @Test
    fun `DrawElement sealed class로 when 분기`() {
        val elements: List<DrawElement> = listOf(
            DrawElement.Text(id = "t1", x = 0f, y = 0f, text = "hi"),
            DrawElement.Rect(id = "r1", x = 0f, y = 0f, width = 10f, height = 10f),
            DrawElement.Circle(id = "c1", cx = 50f, cy = 50f, radius = 5f),
            DrawElement.Line(id = "l1", x1 = 0f, y1 = 0f, x2 = 1f, y2 = 1f),
            DrawElement.Arrow(id = "a1", x1 = 0f, y1 = 0f, x2 = 1f, y2 = 1f),
            DrawElement.Highlight(id = "h1", x = 0f, y = 0f, width = 1f, height = 1f),
            DrawElement.Polyline(id = "p1", points = emptyList())
        )

        assertEquals(7, elements.size)
        // 모든 서브타입이 DrawElement로 접근 가능
        elements.forEach { element ->
            assertNotNull(element.id)
            assertNotNull(element.color)
            assertTrue(element.opacity in 0f..1f)
        }
    }

    // ─── DrawCommand ───

    @Test
    fun `DrawCommand Add`() {
        val text = DrawElement.Text(id = "cmd_t", x = 0f, y = 0f, text = "test")
        val cmd = DrawCommand.Add(text)
        assertEquals("cmd_t", cmd.element.id)
    }

    @Test
    fun `DrawCommand Remove`() {
        val cmd = DrawCommand.Remove("some_id")
        assertEquals("some_id", cmd.id)
    }

    @Test
    fun `DrawCommand Modify`() {
        val updates = mapOf("x" to 50f, "text" to "updated")
        val cmd = DrawCommand.Modify("mod_id", updates)
        assertEquals("mod_id", cmd.id)
        assertEquals(2, cmd.updates.size)
    }

    @Test
    fun `DrawCommand ClearAll 싱글톤`() {
        val a = DrawCommand.ClearAll
        val b = DrawCommand.ClearAll
        assertSame(a, b) // object는 싱글톤
    }

    // ─── 커스텀 값 ───

    @Test
    fun `커스텀 색상과 투명도`() {
        val text = DrawElement.Text(
            id = "custom", x = 0f, y = 0f, text = "hi",
            color = "#FF0000", opacity = 0.5f
        )
        assertEquals("#FF0000", text.color)
        assertEquals(0.5f, text.opacity, 0.001f)
    }
}
