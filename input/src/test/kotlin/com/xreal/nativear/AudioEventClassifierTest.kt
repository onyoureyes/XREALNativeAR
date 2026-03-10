package com.xreal.nativear

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AudioEventClassifier 순수 로직 테스트.
 * prepareInput (PCM → float 변환) 로직을 독립 검증.
 */
class AudioEventClassifierTest {

    private val INPUT_SAMPLES = 15600

    /** prepareInput 재구현 — AudioEventClassifier.prepareInput과 동일 */
    private fun prepareInput(pcm16kHz: ShortArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(INPUT_SAMPLES * 4).order(ByteOrder.nativeOrder())
        val samplesToUse = minOf(pcm16kHz.size, INPUT_SAMPLES)
        for (i in 0 until samplesToUse) {
            buffer.putFloat(pcm16kHz[i].toFloat() / 32768.0f)
        }
        for (i in samplesToUse until INPUT_SAMPLES) {
            buffer.putFloat(0f)
        }
        buffer.rewind()
        return buffer
    }

    @Test
    fun `prepareInput 출력 크기는 INPUT_SAMPLES x 4`() {
        val pcm = ShortArray(INPUT_SAMPLES) { 0 }
        val buffer = prepareInput(pcm)
        assertEquals(INPUT_SAMPLES * 4, buffer.capacity())
    }

    @Test
    fun `prepareInput PCM 최대값 → 약 1_0`() {
        val pcm = ShortArray(1) { Short.MAX_VALUE }
        val buffer = prepareInput(pcm)
        val firstValue = buffer.getFloat()
        assertEquals(1.0f, firstValue, 0.001f)
    }

    @Test
    fun `prepareInput PCM 최소값 → 약 -1_0`() {
        val pcm = ShortArray(1) { Short.MIN_VALUE }
        val buffer = prepareInput(pcm)
        val firstValue = buffer.getFloat()
        assertEquals(-1.0f, firstValue, 0.001f)
    }

    @Test
    fun `prepareInput 짧은 PCM은 제로 패딩`() {
        val pcm = ShortArray(100) { 1000 }
        val buffer = prepareInput(pcm)
        // 101번째 값(index 100)은 0이어야 함
        buffer.position(100 * 4)
        val paddedValue = buffer.getFloat()
        assertEquals(0f, paddedValue, 0.001f)
    }

    @Test
    fun `AudioEvent data class 생성`() {
        val event = AudioEventClassifier.AudioEvent("Speech", 0.85f)
        assertEquals("Speech", event.label)
        assertEquals(0.85f, event.score, 0.001f)
    }

    @Test
    fun `INPUT_SAMPLES는 0_975초 at 16kHz`() {
        // 0.975 * 16000 = 15600
        assertEquals(15600, INPUT_SAMPLES)
    }
}
