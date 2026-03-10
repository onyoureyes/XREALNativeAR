package com.xreal.nativear.cadence

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * CadenceConfig + CadenceProfile 단위 테스트.
 * PolicyReader는 Koin 미초기화 시 fallback 반환하므로 테스트에서 안전하게 기본값 사용.
 */
class CadenceConfigTest {

    // ── CadenceProfile 기본값 테스트 ──

    @Test
    fun `기본 CadenceProfile의 모든 간격이 양수`() {
        val profile = CadenceProfile()
        assertTrue("ocrIntervalMs > 0", profile.ocrIntervalMs > 0)
        assertTrue("detectIntervalMs > 0", profile.detectIntervalMs > 0)
        assertTrue("poseIntervalMs > 0", profile.poseIntervalMs > 0)
        assertTrue("visualEmbeddingIntervalMs > 0", profile.visualEmbeddingIntervalMs > 0)
        assertTrue("handTrackingIntervalMs > 0", profile.handTrackingIntervalMs > 0)
        assertTrue("tiltCooldownMs > 0", profile.tiltCooldownMs > 0)
        assertTrue("scheduleExtractCooldownMs > 0", profile.scheduleExtractCooldownMs > 0)
    }

    @Test
    fun `기본 CadenceProfile 하드웨어 임계값이 합리적 범위`() {
        val profile = CadenceProfile()
        assertTrue("pdrStepThreshold > 0", profile.pdrStepThreshold > 0)
        assertTrue("stabilityDurationMs > 0", profile.stabilityDurationMs > 0)
        assertTrue("stabilityAccelThreshold > 0", profile.stabilityAccelThreshold > 0)
        assertTrue("slamFrameInterval >= 1", profile.slamFrameInterval >= 1)
        assertTrue("rgbFrameInterval >= 1", profile.rgbFrameInterval >= 1)
    }

    @Test
    fun `기본 CadenceProfile의 OCR 간격이 2000ms`() {
        // PolicyReader fallback = 2000L
        val profile = CadenceProfile()
        assertEquals(2000L, profile.ocrIntervalMs)
    }

    @Test
    fun `기본 CadenceProfile의 포즈 간격이 OCR보다 짧다`() {
        val profile = CadenceProfile()
        assertTrue(
            "poseIntervalMs(${profile.poseIntervalMs}) < ocrIntervalMs(${profile.ocrIntervalMs})",
            profile.poseIntervalMs < profile.ocrIntervalMs
        )
    }

    @Test
    fun `기본 CadenceProfile의 핸드트래킹 간격이 33ms (약 30fps)`() {
        val profile = CadenceProfile()
        assertEquals(33L, profile.handTrackingIntervalMs)
    }

    // ── CadenceProfile 커스텀 값 테스트 ──

    @Test
    fun `CadenceProfile 커스텀 값으로 생성 가능`() {
        val profile = CadenceProfile(
            ocrIntervalMs = 5000L,
            detectIntervalMs = 3000L,
            poseIntervalMs = 1000L,
            frameSkip = 5
        )
        assertEquals(5000L, profile.ocrIntervalMs)
        assertEquals(3000L, profile.detectIntervalMs)
        assertEquals(1000L, profile.poseIntervalMs)
        assertEquals(5, profile.frameSkip)
    }

    @Test
    fun `CadenceProfile copy로 일부 값만 변경`() {
        val original = CadenceProfile()
        val modified = original.copy(ocrIntervalMs = 10000L)
        assertEquals(10000L, modified.ocrIntervalMs)
        // 나머지 값은 원본과 동일
        assertEquals(original.detectIntervalMs, modified.detectIntervalMs)
        assertEquals(original.poseIntervalMs, modified.poseIntervalMs)
    }

    // ── CadenceConfig (StateFlow wrapper) 테스트 ──

    @Test
    fun `CadenceConfig 초기 프로필은 기본 CadenceProfile`() {
        val config = CadenceConfig()
        val profile = config.current
        assertEquals(CadenceProfile().ocrIntervalMs, profile.ocrIntervalMs)
    }

    @Test
    fun `CadenceConfig update로 프로필 변경`() {
        val config = CadenceConfig()
        config.update { copy(ocrIntervalMs = 8000L) }
        assertEquals(8000L, config.current.ocrIntervalMs)
    }

    @Test
    fun `CadenceConfig applyProfile로 전체 교체`() {
        val config = CadenceConfig()
        val powerSave = CadenceProfile(
            ocrIntervalMs = 10000L,
            detectIntervalMs = 10000L,
            poseIntervalMs = 2000L,
            frameSkip = 10
        )
        config.applyProfile(powerSave)
        assertEquals(10000L, config.current.ocrIntervalMs)
        assertEquals(10, config.current.frameSkip)
    }

    @Test
    fun `CadenceConfig StateFlow에서 변경 수신`() = runTest {
        val config = CadenceConfig()
        // StateFlow는 현재 값을 즉시 반환
        val initial = config.profile.first()
        assertEquals(CadenceProfile().ocrIntervalMs, initial.ocrIntervalMs)

        config.update { copy(ocrIntervalMs = 999L) }
        val updated = config.profile.first()
        assertEquals(999L, updated.ocrIntervalMs)
    }

    @Test
    fun `CadenceConfig 연속 update 시 최종 값 반영`() {
        val config = CadenceConfig()
        config.update { copy(ocrIntervalMs = 1000L) }
        config.update { copy(ocrIntervalMs = 2000L) }
        config.update { copy(ocrIntervalMs = 3000L) }
        assertEquals(3000L, config.current.ocrIntervalMs)
    }
}
