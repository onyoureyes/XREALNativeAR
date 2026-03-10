package com.xreal.nativear

import org.junit.Test
import org.junit.Assert.*

class FaceInfoTest {

    @Test
    fun `FaceInfo 기본 생성`() {
        val face = FaceInfo(
            x = 100f, y = 150f, width = 80f, height = 100f,
            confidence = 0.92f
        )
        assertEquals(100f, face.x, 0.001f)
        assertEquals(150f, face.y, 0.001f)
        assertEquals(0.92f, face.confidence, 0.001f)
    }

    @Test
    fun `미식별 얼굴 nullable 필드`() {
        val face = FaceInfo(0f, 0f, 50f, 50f, 0.8f)
        assertNull(face.personId)
        assertNull(face.personName)
        assertNull(face.embedding)
        assertNull(face.expression)
        assertNull(face.expressionScore)
    }

    @Test
    fun `식별된 얼굴`() {
        val face = FaceInfo(
            x = 50f, y = 50f, width = 80f, height = 80f,
            confidence = 0.95f,
            personId = 42L,
            personName = "김선생",
            expression = "happy",
            expressionScore = 0.88f
        )
        assertEquals(42L, face.personId)
        assertEquals("김선생", face.personName)
        assertEquals("happy", face.expression)
        assertEquals(0.88f, face.expressionScore!!, 0.001f)
    }

    @Test
    fun `임베딩 있는 얼굴`() {
        val embedding = ByteArray(192) { it.toByte() }
        val face = FaceInfo(
            0f, 0f, 50f, 50f, 0.9f, embedding = embedding
        )
        assertNotNull(face.embedding)
        assertEquals(192, face.embedding!!.size)
    }

    @Test
    fun `copy로 personName 추가`() {
        val unknown = FaceInfo(0f, 0f, 50f, 50f, 0.9f)
        val identified = unknown.copy(personId = 1L, personName = "홍길동")
        assertNull(unknown.personId)
        assertEquals(1L, identified.personId)
        assertEquals("홍길동", identified.personName)
    }
}
