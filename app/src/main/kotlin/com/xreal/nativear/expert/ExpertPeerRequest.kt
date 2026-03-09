package com.xreal.nativear.expert

import java.util.UUID

/**
 * ExpertPeerRequest — 전문가 AI가 팀장(StrategistReflector/Gemini)에게 제출하는 역량 강화 요청.
 *
 * ## 흐름
 * 전문가 AI (tool: request_prompt_addition / request_tool_access)
 *   → ExpertPeerRequestStore.submitRequest() → PENDING 저장
 *   → StrategistService 5분 주기 (피크타임 제외)
 *   → StrategistReflector가 Gemini에게 전달 → APPROVED/REJECTED/MODIFIED
 *   → PeerRequestReviewer.applyReviewDecisions()
 *     - APPROVED(PROMPT_ADDITION) → DirectiveStore에 Directive 추가
 *     - APPROVED(TOOL_ACCESS)     → ExpertDynamicProfileStore에 도구 권한 추가
 *   → OutcomeTracker 관찰 → StrategistService 다음 주기에 효과 평가
 */
data class ExpertPeerRequest(
    val id: String = UUID.randomUUID().toString().take(12),
    val requestingExpertId: String,           // 요청하는 전문가 ID
    val requestType: ExpertRequestType,
    val content: String,                       // 요청 내용
    val rationale: String,                     // 요청 이유
    val situation: String? = null,             // 현재 상황 컨텍스트
    val status: ExpertRequestStatus = ExpertRequestStatus.PENDING,
    val reviewerNotes: String? = null,         // 팀장(Gemini) 결정 이유
    val approvedContent: String? = null,       // MODIFIED 시 수정된 내용
    val appliedAt: Long? = null,               // 실제 적용 시각
    val expiresAt: Long? = null,               // 임시 승인 만료 (null = 무기한)
    val createdAt: Long = System.currentTimeMillis(),
    val outcomeScore: Float? = null            // 승인 후 OutcomeTracker 효과 측정값
)

enum class ExpertRequestType(val displayName: String) {
    PROMPT_ADDITION("프롬프트 추가"),    // 내 시스템 프롬프트에 맥락/지침 추가
    TOOL_ACCESS("도구 접근"),            // 특정 도구 사용 권한 요청
    EXPERT_COLLABORATION("전문가 협업"), // 다른 전문가와의 협업 세션 요청
    CONTEXT_EXPANSION("컨텍스트 확장")  // 추가 데이터 소스 접근 요청
}

enum class ExpertRequestStatus(val displayName: String) {
    PENDING("검토 대기"),
    APPROVED("승인됨"),
    REJECTED("거부됨"),
    MODIFIED("수정 승인됨"),   // 내용을 수정하여 승인
    REVOKED("취소됨"),         // 효과 없어 StrategistService가 취소
    EXPIRED("만료됨")          // expiresAt 초과
}
