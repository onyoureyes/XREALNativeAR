package com.xreal.nativear.ai

import android.graphics.Bitmap
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.xreal.nativear.core.ErrorReporter
import com.xreal.nativear.core.ErrorSeverity
import com.google.ai.client.generativeai.type.FunctionCallPart
import com.google.ai.client.generativeai.type.FunctionDeclaration
import com.google.ai.client.generativeai.type.FunctionResponsePart
import com.google.ai.client.generativeai.type.Tool
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.defineFunction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * IAIProvider implementation for Google Gemini.
 * Wraps the Gemini SDK GenerativeModel with per-call chat sessions.
 *
 * Supports:
 * - Text conversation (stateless, per-request model)
 * - Tool calling via FunctionDeclaration registration
 * - Vision (bitmap) via sendVisionMessage
 * - ★ Phase B: role="tool" / role="assistant"+pendingToolCalls 히스토리 변환
 */
class GeminiProvider(private var config: ProviderConfig) : IAIProvider {
    private val TAG = "GeminiProvider"

    override val providerId = ProviderId.GEMINI
    override val isAvailable: Boolean get() = config.apiKey.isNotBlank()

    /**
     * Gemini SDK FunctionDeclaration tools registered externally.
     * Set once at startup from GeminiTools.getAllTools().
     * ★ tools 파라미터가 전달되면 그것을 우선 사용 (하위 호환).
     */
    var registeredTools: List<FunctionDeclaration> = emptyList()

    override suspend fun sendMessage(
        messages: List<AIMessage>,
        systemPrompt: String?,
        tools: List<AIToolDefinition>,
        temperature: Float?,
        maxTokens: Int?
    ): AIResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            // ★ tools 파라미터 우선 → registeredTools fallback (하위 호환)
            val geminiTools = when {
                tools.isNotEmpty() -> listOf(Tool(convertToFunctionDeclarations(tools)))
                registeredTools.isNotEmpty() -> listOf(Tool(registeredTools))
                else -> emptyList()
            }

            // Build a per-request model with optional system instruction and tools
            val model = GenerativeModel(
                modelName = config.model,
                apiKey = config.apiKey,
                systemInstruction = systemPrompt?.let { content { text(it) } },
                tools = geminiTools.ifEmpty { null }
            )

            // ★ Build chat history — role="tool" / role="assistant"+pendingToolCalls 변환
            val history = messages.dropLast(1).map { msg ->
                when {
                    // 도구 결과 메시지 → FunctionResponsePart
                    msg.role == "tool" -> content(role = "user") {
                        part(FunctionResponsePart(
                            name = msg.toolName ?: "",
                            response = JSONObject().apply { put("result", msg.content) }
                        ))
                    }
                    // 도구 호출이 포함된 assistant 메시지 → FunctionCallPart
                    msg.role == "assistant" && msg.pendingToolCalls != null -> content(role = "model") {
                        val toolCalls = msg.pendingToolCalls ?: emptyList()
                        toolCalls.forEach { tc ->
                            part(FunctionCallPart(
                                name = tc.name,
                                args = tc.arguments.mapValues { it.value?.toString() ?: "" }
                            ))
                        }
                        if (msg.content.isNotBlank()) text(msg.content)
                    }
                    // 일반 메시지
                    else -> content(role = if (msg.role == "user") "user" else "model") {
                        text(msg.content)
                    }
                }
            }

            val chat = model.startChat(history = history)
            val lastMsg = messages.last()
            val response = chat.sendMessage(lastMsg.content)

            val latency = System.currentTimeMillis() - startTime

            // Convert Gemini function calls to AIToolCall
            val toolCalls = response.functionCalls.map { fc ->
                AIToolCall(
                    id = fc.name,
                    name = fc.name,
                    arguments = fc.args
                )
            }

            AIResponse(
                text = response.text,
                toolCalls = toolCalls,
                finishReason = if (toolCalls.isNotEmpty()) "tool_use" else "stop",
                providerId = ProviderId.GEMINI,
                latencyMs = latency
            )
        } catch (e: Exception) {
            ErrorReporter.report(TAG, "Gemini API 호출 실패", e, ErrorSeverity.WARNING)
            AIResponse(
                text = "Error: ${e.message}",
                providerId = ProviderId.GEMINI,
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
    }

    /**
     * Send a vision (image + text) message.
     * Uses GenerativeModel.generateContent directly (stateless, no chat).
     */
    suspend fun sendVisionMessage(
        prompt: String,
        bitmap: Bitmap,
        systemPrompt: String? = null
    ): AIResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            val model = GenerativeModel(
                modelName = config.model,
                apiKey = config.apiKey,
                systemInstruction = systemPrompt?.let { content { text(it) } }
            )

            val inputContent = content {
                image(bitmap)
                text(prompt)
            }
            val response = model.generateContent(inputContent)
            val latency = System.currentTimeMillis() - startTime

            AIResponse(
                text = response.text,
                providerId = ProviderId.GEMINI,
                latencyMs = latency
            )
        } catch (e: Exception) {
            ErrorReporter.report(TAG, "Gemini Vision API 호출 실패", e, ErrorSeverity.WARNING)
            AIResponse(
                text = "Error: ${e.message}",
                providerId = ProviderId.GEMINI,
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
    }

    override fun updateApiKey(apiKey: String) {
        config = config.copy(apiKey = apiKey)
    }

    override fun getModelName() = config.model

    /**
     * AIToolDefinition → Gemini FunctionDeclaration 변환.
     * parametersJson (JSON Schema) 기반으로 Gemini SDK 포맷 생성.
     */
    private fun convertToFunctionDeclarations(tools: List<AIToolDefinition>): List<FunctionDeclaration> {
        return tools.map { tool ->
            defineFunction(tool.name, tool.description) { JSONObject(tool.parametersJson) }
        }
    }
}
