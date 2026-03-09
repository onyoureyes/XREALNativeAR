package com.xreal.nativear.ai

/** Unique identifier for each AI provider backend */
enum class ProviderId {
    GEMINI, OPENAI, CLAUDE, GROK,
    // 로컬 LLM 서버 (llama.cpp/KoboldCpp, Tailscale 경유, OpenAI-호환 API)
    LOCAL,
    // 스팀덱 LLM 서버 (llama.cpp, gemma-3-4b, Tailscale 경유, $0 비용)
    LOCAL_STEAMDECK,
    // 음성 PC 서버 (Gemma-3 4B, RX570, Tailscale 경유, $0 비용)
    LOCAL_SPEECH_PC,
    // 엣지 LLM 프로바이더 (LiteRT-LM v0.8.1, Gemma 3 on-device)
    // RESEARCH.md §2 참조: 네트워크 불필요, tool calling 미지원
    EDGE_ROUTER,     // Gemma 3 270M — 쿼리 복잡도 분류 (SIMPLE/COMPLEX, ~5-15ms)
    EDGE_AGENT,      // Gemma 3 1B — 단순 작업 처리 (온라인 서브에이전트 + 오프라인 1차)
    EDGE_EMERGENCY   // Gemma 3n E2B — 전체 API 불가 시 전면 대체 (멀티모달, 지연 로딩)
}

/** Represents a single message in a conversation */
data class AIMessage(
    val role: String,       // "system", "user", "assistant", "tool"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    // ★ 도구 호출 지원 (nullable — 기존 코드 호환)
    val toolCallId: String? = null,              // role="tool" 메시지: 연결할 tool_call id
    val toolName: String? = null,                // role="tool" 메시지: 도구 이름
    val pendingToolCalls: List<AIToolCall>? = null // role="assistant" 메시지: 모델이 요청한 tool calls
)

/** Tool definition in provider-agnostic format */
data class AIToolDefinition(
    val name: String,
    val description: String,
    val parametersJson: String // JSON Schema string
)

/** A tool call returned from the provider */
data class AIToolCall(
    val id: String,           // tool_call id (for Claude/OpenAI)
    val name: String,
    val arguments: Map<String, Any?>
)

/** Response from any AI provider */
data class AIResponse(
    val text: String?,
    val toolCalls: List<AIToolCall> = emptyList(),
    val finishReason: String? = null,  // "stop", "tool_use", etc.
    val usage: TokenUsage? = null,
    val providerId: ProviderId,
    val latencyMs: Long = 0
)

data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
)

/** Configuration for a provider instance */
data class ProviderConfig(
    val providerId: ProviderId,
    val apiKey: String,
    val model: String,
    val baseUrl: String? = null,
    val maxTokens: Int = 2048,
    val temperature: Float = 0.7f
)
