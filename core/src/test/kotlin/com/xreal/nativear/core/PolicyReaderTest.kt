package com.xreal.nativear.core

import com.xreal.nativear.policy.PolicyReader
import org.junit.Test
import org.junit.Assert.*

/**
 * PolicyReader는 Koin이 없을 때 fallback을 반환해야 한다.
 * 테스트 환경에서는 Koin이 초기화되지 않으므로 항상 fallback 경로를 탄다.
 * 이것이 핵심: Koin 미초기화 시 절대 크래시하지 않는다.
 */
class PolicyReaderTest {

    @Test
    fun `Koin 미초기화 시 getInt fallback 반환`() {
        val result = PolicyReader.getInt("nonexistent.key", 42)
        assertEquals(42, result)
    }

    @Test
    fun `Koin 미초기화 시 getFloat fallback 반환`() {
        val result = PolicyReader.getFloat("nonexistent.key", 3.14f)
        assertEquals(3.14f, result, 0.001f)
    }

    @Test
    fun `Koin 미초기화 시 getLong fallback 반환`() {
        val result = PolicyReader.getLong("nonexistent.key", 99999L)
        assertEquals(99999L, result)
    }

    @Test
    fun `Koin 미초기화 시 getBoolean fallback 반환`() {
        val result = PolicyReader.getBoolean("nonexistent.key", true)
        assertTrue(result)

        val resultFalse = PolicyReader.getBoolean("nonexistent.key", false)
        assertFalse(resultFalse)
    }

    @Test
    fun `Koin 미초기화 시 getString fallback 반환`() {
        val result = PolicyReader.getString("nonexistent.key", "default_value")
        assertEquals("default_value", result)
    }

    @Test
    fun `빈 키에도 크래시 없이 fallback`() {
        val result = PolicyReader.getInt("", 0)
        assertEquals(0, result)
    }

    @Test
    fun `특수문자 키에도 크래시 없이 fallback`() {
        val result = PolicyReader.getString("key.with.많은.dots", "safe")
        assertEquals("safe", result)
    }

    @Test
    fun `연속 호출 안정성`() {
        // 100번 연속 호출해도 크래시 없음
        repeat(100) { i ->
            val result = PolicyReader.getInt("batch.key.$i", i)
            assertEquals(i, result)
        }
    }
}
