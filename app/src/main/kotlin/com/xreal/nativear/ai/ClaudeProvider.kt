package com.xreal.nativear.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * IAIProvider implementation for Anthropic Claude.
 * Claude has a different API format from OpenAI:
 * - system prompt is a top-level field, not a message
 * - uses x-api-key header (not Bearer)
 * - tool definitions use input_schema (not parameters)
 * - response content is an array of blocks (text/tool_use)
 */
class ClaudeProvider(
    private var config: ProviderConfig,
    private val httpClient: OkHttpClient
) : IAIProvider {
    private val TAG = "ClaudeProvider"
    private val JSON_MEDIA = "application/json".toMediaType()

    override val providerId = ProviderId.CLAUDE
    override val isAvailable: Boolean get() = config.apiKey.isNotBlank()

    companion object {
        private const val BASE_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
    }

    override suspend fun sendMessage(
        messages: List<AIMessage>,
        systemPrompt: String?,
        tools: List<AIToolDefinition>,
        temperature: Float?,
        maxTokens: Int?
    ): AIResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            val body = JSONObject().apply {
                put("model", config.model)
                if (systemPrompt != null) put("system", systemPrompt)
                put("messages", buildClaudeMessages(messages))
                put("max_tokens", maxTokens ?: config.maxTokens)
                put("temperature", (temperature ?: config.temperature).toDouble())
                if (tools.isNotEmpty()) put("tools", buildClaudeTools(tools))
            }

            val request = Request.Builder()
                .url(config.baseUrl ?: BASE_URL)
                .addHeader("x-api-key", config.apiKey)
                .addHeader("anthropic-version", API_VERSION)
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            val latency = System.currentTimeMillis() - startTime

            if (!response.isSuccessful) {
                Log.e(TAG, "Claude API error ${response.code}: $responseBody")
                return@withContext AIResponse(
                    text = "API Error ${response.code}",
                    providerId = ProviderId.CLAUDE,
                    latencyMs = latency
                )
            }

            parseClaudeResponse(responseBody, latency)
        } catch (e: Exception) {
            Log.e(TAG, "Claude request failed: ${e.message}", e)
            AIResponse(
                text = "Error: ${e.message}",
                providerId = ProviderId.CLAUDE,
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
    }

    private fun buildClaudeMessages(messages: List<AIMessage>): JSONArray {
        val arr = JSONArray()
        for (msg in messages) {
            // Claude doesn't support "system" role in messages array
            if (msg.role == "system") continue
            when {
                // ★ 도구 결과 메시지 → Claude tool_result 포맷
                msg.role == "tool" -> {
                    arr.put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "tool_result")
                                put("tool_use_id", msg.toolCallId ?: msg.toolName ?: "")
                                put("content", msg.content)
                            })
                        })
                    })
                }
                // ★ 도구 호출이 포함된 assistant 메시지 → tool_use 블록 포맷
                msg.role == "assistant" && msg.pendingToolCalls != null -> {
                    val contentArray = JSONArray()
                    if (msg.content.isNotBlank()) {
                        contentArray.put(JSONObject().apply {
                            put("type", "text")
                            put("text", msg.content)
                        })
                    }
                    msg.pendingToolCalls.forEach { tc ->
                        contentArray.put(JSONObject().apply {
                            put("type", "tool_use")
                            put("id", tc.id)
                            put("name", tc.name)
                            put("input", JSONObject(tc.arguments.mapValues { it.value?.toString() ?: "null" }))
                        })
                    }
                    arr.put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", contentArray)
                    })
                }
                // 일반 메시지
                else -> arr.put(JSONObject().apply {
                    put("role", if (msg.role == "assistant") "assistant" else "user")
                    put("content", msg.content)
                })
            }
        }
        return arr
    }

    private fun buildClaudeTools(tools: List<AIToolDefinition>): JSONArray {
        val arr = JSONArray()
        for (tool in tools) {
            arr.put(JSONObject().apply {
                put("name", tool.name)
                put("description", tool.description)
                put("input_schema", JSONObject(tool.parametersJson))
            })
        }
        return arr
    }

    private fun parseClaudeResponse(responseBody: String, latencyMs: Long): AIResponse {
        val json = JSONObject(responseBody)
        val contentArray = json.optJSONArray("content") ?: return AIResponse(
            text = null, providerId = ProviderId.CLAUDE, latencyMs = latencyMs
        )

        var text: String? = null
        val toolCalls = mutableListOf<AIToolCall>()

        for (i in 0 until contentArray.length()) {
            val block = contentArray.getJSONObject(i)
            when (block.getString("type")) {
                "text" -> text = block.getString("text")
                "tool_use" -> {
                    val input = block.getJSONObject("input")
                    val args = input.keys().asSequence().associateWith { key -> input.opt(key) }
                    toolCalls.add(AIToolCall(
                        id = block.getString("id"),
                        name = block.getString("name"),
                        arguments = args
                    ))
                }
            }
        }

        val stopReason = json.optString("stop_reason", "end_turn")
        val usageJson = json.optJSONObject("usage")
        val usage = if (usageJson != null) {
            TokenUsage(
                promptTokens = usageJson.optInt("input_tokens", 0),
                completionTokens = usageJson.optInt("output_tokens", 0),
                totalTokens = usageJson.optInt("input_tokens", 0) + usageJson.optInt("output_tokens", 0)
            )
        } else null

        return AIResponse(
            text = text,
            toolCalls = toolCalls,
            finishReason = if (toolCalls.isNotEmpty()) "tool_use" else stopReason,
            usage = usage,
            providerId = ProviderId.CLAUDE,
            latencyMs = latencyMs
        )
    }

    override fun updateApiKey(apiKey: String) {
        config = config.copy(apiKey = apiKey)
    }

    override fun getModelName() = config.model
}
