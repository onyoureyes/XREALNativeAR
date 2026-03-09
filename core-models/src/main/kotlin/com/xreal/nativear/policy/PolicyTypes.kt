package com.xreal.nativear.policy

/**
 * PolicyCategory — 정책 분류 카테고리.
 */
enum class PolicyCategory {
    CADENCE, BUDGET, COMPANION, VISION, RUNNING, MEETING, FOCUS, AI_CONFIG, CAPACITY, SYSTEM,
    MISSION, EXPERT, RESILIENCE, GATEWAY
}

/**
 * PolicyValueType — 정책 값 타입.
 */
enum class PolicyValueType {
    INT, LONG, FLOAT, STRING, BOOLEAN, STRING_LIST
}

/**
 * PolicyEntry — 개별 정책 항목.
 *
 * @param key 정책 키 (예: "cadence.ocr_interval_ms")
 * @param category 정책 카테고리
 * @param defaultValue 코드 기본값
 * @param valueType 값 타입
 * @param description 설명 (한국어)
 * @param min 최소값 (숫자형, nullable)
 * @param max 최대값 (숫자형, nullable)
 * @param overrideValue DB/런타임 오버라이드 값
 * @param overrideSource 변경 주체
 * @param overrideTtlMs 유효 기간 (0 = 영구)
 * @param overrideUpdatedAt 오버라이드 시각
 */
data class PolicyEntry(
    val key: String,
    val category: PolicyCategory,
    val defaultValue: String,
    val valueType: PolicyValueType,
    val description: String,
    val min: String? = null,
    val max: String? = null,
    val overrideValue: String? = null,
    val overrideSource: String? = null,
    val overrideTtlMs: Long = 0L,
    val overrideUpdatedAt: Long = 0L
) {
    /** 현재 유효 값 */
    val effectiveValue: String get() = overrideValue ?: defaultValue
    /** 오버라이드 여부 */
    val isOverridden: Boolean get() = overrideValue != null
}
