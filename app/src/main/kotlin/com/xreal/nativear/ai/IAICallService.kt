package com.xreal.nativear.ai

import android.graphics.Bitmap

/**
 * IAICallService — 모든 AI 호출의 유일한 관문.
 *
 * 모든 서비스는 이 인터페이스를 통해서만 AI를 호출할 수 있다.
 * 직접 IAIProvider, AIResourceRegistry, RemoteLLMPool, TokenBudgetTracker를
 * 주입받아 호출하는 것은 금지.
 *
 * 내부적으로 StoryPhase 게이트 + AICallGateway 예산 게이트 +
 * cascade 라우팅 (리모트 → 서버 → 엣지)을 모두 거친다.
 *
 * 구현체: AIResourceRegistry
 */
interface IAICallService {

    /**
     * 텍스트 AI 호출 — cascade 라우팅 + 전체 게이트.
     * 모든 프로바이더 실패 시 null 반환.
     */
    suspend fun quickText(
        messages: List<AIMessage>,
        systemPrompt: String? = null,
        tools: List<AIToolDefinition> = emptyList(),
        temperature: Float? = null,
        maxTokens: Int? = null,
        isEssential: Boolean = false,
        callPriority: AICallGateway.CallPriority = AICallGateway.CallPriority.REACTIVE,
        visibility: AICallGateway.VisibilityIntent = AICallGateway.VisibilityIntent.USER_FACING,
        intent: String = "unknown"
    ): AIResponse?

    /**
     * 텍스트 AI 호출 — RouteResult 포함 (providerId, tier, latency 추적 필요 시).
     */
    suspend fun routeText(
        messages: List<AIMessage>,
        systemPrompt: String? = null,
        tools: List<AIToolDefinition> = emptyList(),
        temperature: Float? = null,
        maxTokens: Int? = null,
        isEssential: Boolean = false,
        callPriority: AICallGateway.CallPriority = AICallGateway.CallPriority.REACTIVE,
        visibility: AICallGateway.VisibilityIntent = AICallGateway.VisibilityIntent.USER_FACING,
        intent: String = "unknown"
    ): AIResourceRegistry.RouteResult?

    /**
     * 비전 AI 호출 — 이미지 + 텍스트 프롬프트.
     * cascade: 리모트 텍스트($0) → 서버 비전($) → 엣지 텍스트($0)
     */
    suspend fun routeVision(
        bitmap: Bitmap,
        textPrompt: String,
        systemPrompt: String? = null,
        ocrHint: String? = null,
        isEssential: Boolean = false,
        callPriority: AICallGateway.CallPriority = AICallGateway.CallPriority.REACTIVE,
        visibility: AICallGateway.VisibilityIntent = AICallGateway.VisibilityIntent.USER_FACING,
        intent: String = "vision"
    ): AIResourceRegistry.RouteResult?

    /** 등록된 프로바이더 상태 요약 (디버그/HUD 용) */
    fun getStatusSummary(): String
}
