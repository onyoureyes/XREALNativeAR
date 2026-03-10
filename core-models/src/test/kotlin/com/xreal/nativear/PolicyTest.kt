package com.xreal.nativear

import com.xreal.nativear.policy.*
import org.junit.Test
import org.junit.Assert.*

class PolicyTest {

    // ─── PolicyCategory ───

    @Test
    fun `PolicyCategory 전체 값 개수`() {
        assertEquals(14, PolicyCategory.values().size)
    }

    @Test
    fun `주요 카테고리 존재`() {
        assertNotNull(PolicyCategory.CADENCE)
        assertNotNull(PolicyCategory.BUDGET)
        assertNotNull(PolicyCategory.VISION)
        assertNotNull(PolicyCategory.AI_CONFIG)
        assertNotNull(PolicyCategory.SYSTEM)
        assertNotNull(PolicyCategory.GATEWAY)
    }

    // ─── PolicyValueType ───

    @Test
    fun `PolicyValueType 전체 값 개수`() {
        assertEquals(6, PolicyValueType.values().size)
    }

    @Test
    fun `모든 타입 존재`() {
        assertNotNull(PolicyValueType.INT)
        assertNotNull(PolicyValueType.LONG)
        assertNotNull(PolicyValueType.FLOAT)
        assertNotNull(PolicyValueType.STRING)
        assertNotNull(PolicyValueType.BOOLEAN)
        assertNotNull(PolicyValueType.STRING_LIST)
    }

    // ─── PolicyEntry ───

    @Test
    fun `PolicyEntry 기본 생성`() {
        val entry = PolicyEntry(
            key = "cadence.ocr_interval_ms",
            category = PolicyCategory.CADENCE,
            defaultValue = "3000",
            valueType = PolicyValueType.INT,
            description = "OCR 간격"
        )
        assertEquals("cadence.ocr_interval_ms", entry.key)
        assertEquals("3000", entry.defaultValue)
        assertEquals(PolicyValueType.INT, entry.valueType)
    }

    @Test
    fun `effectiveValue - override 없으면 기본값`() {
        val entry = PolicyEntry(
            key = "test.key", category = PolicyCategory.SYSTEM,
            defaultValue = "100", valueType = PolicyValueType.INT,
            description = "test"
        )
        assertEquals("100", entry.effectiveValue)
        assertFalse(entry.isOverridden)
    }

    @Test
    fun `effectiveValue - override 있으면 override 값`() {
        val entry = PolicyEntry(
            key = "test.key", category = PolicyCategory.SYSTEM,
            defaultValue = "100", valueType = PolicyValueType.INT,
            description = "test",
            overrideValue = "200",
            overrideSource = "user_voice"
        )
        assertEquals("200", entry.effectiveValue)
        assertTrue(entry.isOverridden)
    }

    @Test
    fun `min max 범위`() {
        val entry = PolicyEntry(
            key = "budget.threshold",
            category = PolicyCategory.BUDGET,
            defaultValue = "0.9",
            valueType = PolicyValueType.FLOAT,
            description = "threshold",
            min = "0.0",
            max = "1.0"
        )
        assertNotNull(entry.min)
        assertNotNull(entry.max)
        val default = entry.defaultValue.toFloat()
        val min = entry.min!!.toFloat()
        val max = entry.max!!.toFloat()
        assertTrue("기본값이 min보다 크거나 같아야 함", default >= min)
        assertTrue("기본값이 max보다 작거나 같아야 함", default <= max)
    }

    @Test
    fun `min max nullable`() {
        val entry = PolicyEntry(
            key = "test.string",
            category = PolicyCategory.SYSTEM,
            defaultValue = "hello",
            valueType = PolicyValueType.STRING,
            description = "string type"
        )
        assertNull(entry.min)
        assertNull(entry.max)
    }

    @Test
    fun `overrideTtlMs 기본값 0 (영구)`() {
        val entry = PolicyEntry(
            key = "test", category = PolicyCategory.SYSTEM,
            defaultValue = "1", valueType = PolicyValueType.INT,
            description = "test"
        )
        assertEquals(0L, entry.overrideTtlMs)
    }

    // ─── PolicyDefaults ───

    @Test
    fun `PolicyDefaults 전체 항목 로드`() {
        val defaults = PolicyDefaults.getAllDefaults()
        assertTrue("최소 100개 이상의 정책이 있어야 함", defaults.size >= 100)
    }

    @Test
    fun `PolicyDefaults 키 중복 없음`() {
        val defaults = PolicyDefaults.getAllDefaults()
        val keys = defaults.map { it.key }
        val uniqueKeys = keys.toSet()
        assertEquals(
            "중복 키 발견: ${keys.groupBy { it }.filter { it.value.size > 1 }.keys}",
            keys.size, uniqueKeys.size
        )
    }

    @Test
    fun `PolicyDefaults 키 네이밍 컨벤션`() {
        val defaults = PolicyDefaults.getAllDefaults()
        defaults.forEach { entry ->
            assertTrue(
                "키 '${entry.key}'에 점(.)이 없음 — 형식: category.name",
                entry.key.contains(".")
            )
        }
    }

    @Test
    fun `PolicyDefaults 숫자형 min max 유효성`() {
        val defaults = PolicyDefaults.getAllDefaults()
        val numericTypes = setOf(PolicyValueType.INT, PolicyValueType.LONG, PolicyValueType.FLOAT)

        defaults.filter { it.valueType in numericTypes }.forEach { entry ->
            // 숫자형은 defaultValue가 파싱 가능해야 함
            val parsed = when (entry.valueType) {
                PolicyValueType.INT -> entry.defaultValue.toIntOrNull()
                PolicyValueType.LONG -> entry.defaultValue.toLongOrNull()
                PolicyValueType.FLOAT -> entry.defaultValue.toFloatOrNull()
                else -> null
            }
            assertNotNull(
                "키 '${entry.key}' (${entry.valueType}): defaultValue '${entry.defaultValue}' 파싱 실패",
                parsed
            )

            // min이 있으면 default >= min
            if (entry.min != null) {
                val minVal = entry.min!!.toDoubleOrNull()
                val defVal = entry.defaultValue.toDoubleOrNull()
                if (minVal != null && defVal != null) {
                    assertTrue(
                        "키 '${entry.key}': default($defVal) < min($minVal)",
                        defVal >= minVal
                    )
                }
            }

            // max가 있으면 default <= max
            if (entry.max != null) {
                val maxVal = entry.max!!.toDoubleOrNull()
                val defVal = entry.defaultValue.toDoubleOrNull()
                if (maxVal != null && defVal != null) {
                    assertTrue(
                        "키 '${entry.key}': default($defVal) > max($maxVal)",
                        defVal <= maxVal
                    )
                }
            }
        }
    }

    @Test
    fun `PolicyDefaults BOOLEAN 타입 유효값`() {
        val defaults = PolicyDefaults.getAllDefaults()
        defaults.filter { it.valueType == PolicyValueType.BOOLEAN }.forEach { entry ->
            assertTrue(
                "키 '${entry.key}': BOOLEAN인데 값이 '${entry.defaultValue}' (true/false여야 함)",
                entry.defaultValue in listOf("true", "false")
            )
        }
    }

    @Test
    fun `PolicyDefaults 모든 카테고리 사용됨`() {
        val defaults = PolicyDefaults.getAllDefaults()
        val usedCategories = defaults.map { it.category }.toSet()
        // 최소 10개 카테고리는 사용되어야 함
        assertTrue(
            "사용된 카테고리: ${usedCategories.size}개 (최소 10개 기대)",
            usedCategories.size >= 10
        )
    }

    @Test
    fun `PolicyDefaults description 한글 존재`() {
        val defaults = PolicyDefaults.getAllDefaults()
        defaults.forEach { entry ->
            assertTrue(
                "키 '${entry.key}': description이 비어있음",
                entry.description.isNotBlank()
            )
        }
    }
}
