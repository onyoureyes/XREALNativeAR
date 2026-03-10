package com.xreal.nativear

import com.xreal.nativear.spatial.AnchorType
import com.xreal.nativear.spatial.AnchorLabel2D
import org.junit.Test
import org.junit.Assert.*

class SpatialTypesTest {

    // ─── AnchorType ───

    @Test
    fun `AnchorType 전체 값 개수`() {
        assertEquals(3, AnchorType.values().size)
    }

    @Test
    fun `AnchorType 값 존재`() {
        assertNotNull(AnchorType.OBJECT)
        assertNotNull(AnchorType.OCR_TEXT)
        assertNotNull(AnchorType.PROGRAMMATIC)
    }

    // ─── AnchorLabel2D ───

    @Test
    fun `AnchorLabel2D 생성`() {
        val label = AnchorLabel2D(
            anchorId = "anchor_001",
            label = "커피머신",
            screenXPercent = 30f,
            screenYPercent = 45f,
            distanceMeters = 2.5f,
            confidence = 0.87f,
            type = AnchorType.OBJECT
        )
        assertEquals("anchor_001", label.anchorId)
        assertEquals("커피머신", label.label)
        assertEquals(30f, label.screenXPercent, 0.001f)
        assertEquals(2.5f, label.distanceMeters, 0.001f)
        assertFalse(label.isGhost)
    }

    @Test
    fun `AnchorLabel2D ghost 앵커`() {
        val ghost = AnchorLabel2D(
            anchorId = "anchor_old",
            label = "문",
            screenXPercent = 50f,
            screenYPercent = 50f,
            distanceMeters = 3.0f,
            confidence = 0.5f,
            type = AnchorType.OBJECT,
            isGhost = true
        )
        assertTrue(ghost.isGhost)
    }

    @Test
    fun `AnchorLabel2D OCR 타입`() {
        val ocr = AnchorLabel2D(
            anchorId = "ocr_001",
            label = "출입금지",
            screenXPercent = 60f,
            screenYPercent = 40f,
            distanceMeters = 5.0f,
            confidence = 0.92f,
            type = AnchorType.OCR_TEXT
        )
        assertEquals(AnchorType.OCR_TEXT, ocr.type)
    }

    @Test
    fun `AnchorLabel2D 좌표 범위 (퍼센트)`() {
        // 화면 좌상단
        val topLeft = AnchorLabel2D("a", "test", 0f, 0f, 1f, 0.5f, AnchorType.OBJECT)
        assertEquals(0f, topLeft.screenXPercent, 0.001f)
        assertEquals(0f, topLeft.screenYPercent, 0.001f)

        // 화면 우하단
        val bottomRight = AnchorLabel2D("b", "test", 100f, 100f, 1f, 0.5f, AnchorType.OBJECT)
        assertEquals(100f, bottomRight.screenXPercent, 0.001f)
        assertEquals(100f, bottomRight.screenYPercent, 0.001f)
    }

    @Test
    fun `data class equals`() {
        val a = AnchorLabel2D("id1", "label", 50f, 50f, 2f, 0.9f, AnchorType.OBJECT)
        val b = AnchorLabel2D("id1", "label", 50f, 50f, 2f, 0.9f, AnchorType.OBJECT)
        assertEquals(a, b)
    }
}
