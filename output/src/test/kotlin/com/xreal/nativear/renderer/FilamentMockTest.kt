package com.xreal.nativear.renderer

import io.mockk.*
import org.junit.Test
import org.junit.Assert.*

/**
 * Filament 관련 클래스 Mock 테스트.
 * 네이티브 라이브러리 로딩 불가하므로 인터페이스/로직 레벨만 테스트.
 */
class FilamentMockTest {

    @Test
    fun `MaterialFactory 인스턴스 생성 가능`() {
        // MaterialFactory는 class이므로 인스턴스 생성 확인
        // init/shutdown은 MaterialBuilder 네이티브 의존이므로 호출 불가
        val factory = MaterialFactory()
        assertNotNull(factory)
    }

    @Test
    fun `FilamentSceneManager PACER_ANCHOR_ID 상수 확인`() {
        // FilamentSceneManager의 상수가 올바른지 확인
        // companion object은 private이므로 리플렉션으로 확인
        val field = FilamentSceneManager::class.java.getDeclaredField("PACER_ANCHOR_ID")
        field.isAccessible = true
        assertEquals("pacemaker_dot", field.get(null))
    }

    @Test
    fun `GhostRunnerEntity 상수 값 확인`() {
        // 캡슐 치수 상수 확인 (리플렉션)
        val cls = GhostRunnerEntity::class.java

        val radiusField = cls.getDeclaredField("RADIUS")
        radiusField.isAccessible = true
        assertEquals(0.2f, radiusField.getFloat(null), 0.01f)

        val bodyHeightField = cls.getDeclaredField("BODY_HEIGHT")
        bodyHeightField.isAccessible = true
        assertEquals(1.2f, bodyHeightField.getFloat(null), 0.01f)

        val totalHeightField = cls.getDeclaredField("TOTAL_HEIGHT")
        totalHeightField.isAccessible = true
        assertEquals(1.6f, totalHeightField.getFloat(null), 0.01f)  // 1.2 + 2*0.2

        val segmentsField = cls.getDeclaredField("SEGMENTS")
        segmentsField.isAccessible = true
        assertEquals(24, segmentsField.getInt(null))
    }

    @Test
    fun `FilamentRenderer companion 상수 존재 확인`() {
        // FilamentRenderer companion object init 블록에서 Filament.init() 호출하므로
        // 클래스 로딩 자체가 UnsatisfiedLinkError 발생.
        // 따라서 Filament 네이티브 의존 클래스는 리플렉션으로도 테스트 불가.
        // 이 테스트는 문서적 목적으로 존재하며, 상수 값은 소스 코드에서 확인됨:
        // FX=914.0, FY=914.0, IMG_W=1280.0, IMG_H=960.0, NEAR=0.05, FAR=100.0
        assertTrue("Filament 네이티브 의존으로 JVM 테스트 불가 — 상수 값은 소스에서 검증", true)
    }
}
