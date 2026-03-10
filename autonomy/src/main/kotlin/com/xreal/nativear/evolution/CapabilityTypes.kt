package com.xreal.nativear.evolution

import java.util.UUID

/**
 * CapabilityTypes: Data types for the self-improving AI pipeline.
 *
 * AI experts can identify their own limitations and formally request
 * new capabilities (tools, data sources, sensors, etc.) through
 * CapabilityRequest. The flow is:
 *
 * 1. AI detects limitation -> calls request_capability tool
 * 2. CapabilityRequest stored in DB (status: PENDING)
 * 3. User gets HUD/TTS notification
 * 4. User approves (NOD/voice) or rejects
 * 5. If approved -> exported for Claude Code implementation
 * 6. Claude Code implements, builds, verifies
 * 7. New capability activated in system
 */

data class CapabilityRequest(
    val id: String = UUID.randomUUID().toString().take(12),
    val timestamp: Long = System.currentTimeMillis(),
    val requestingExpertId: String,
    val requestingDomainId: String? = null,
    val type: CapabilityType,
    val title: String,
    val description: String,
    val currentLimitation: String? = null,
    val expectedBenefit: String? = null,
    val priority: RequestPriority = RequestPriority.NORMAL,
    val situation: String? = null,
    val status: RequestStatus = RequestStatus.PENDING,
    val userResponse: String? = null,
    val implementationNotes: String? = null,
    val resolvedAt: Long? = null
)

enum class CapabilityType(val displayName: String) {
    NEW_TOOL("새 도구"),
    TOOL_ENHANCEMENT("도구 개선"),
    NEW_DATA_SOURCE("새 데이터 소스"),
    NEW_SENSOR("새 센서 활용"),
    PROMPT_IMPROVEMENT("프롬프트 개선"),
    HUD_WIDGET("새 HUD 위젯"),
    WORKFLOW_AUTOMATION("워크플로우 자동화"),
    BUG_REPORT("버그 리포트"),
    PERFORMANCE_ISSUE("성능 문제")
}

enum class RequestPriority(val level: Int) {
    CRITICAL(0),
    HIGH(1),
    NORMAL(2),
    LOW(3),
    WISHLIST(4)
}

enum class RequestStatus(val displayName: String) {
    PENDING("검토 대기"),
    APPROVED("승인됨"),
    IN_PROGRESS("구현 중"),
    IMPLEMENTED("구현 완료"),
    VERIFIED("검증 완료"),
    REJECTED("거부됨"),
    DEFERRED("보류")
}

data class RequestStats(
    val total: Int = 0,
    val pending: Int = 0,
    val approved: Int = 0,
    val inProgress: Int = 0,
    val implemented: Int = 0,
    val rejected: Int = 0,
    val deferred: Int = 0
)
