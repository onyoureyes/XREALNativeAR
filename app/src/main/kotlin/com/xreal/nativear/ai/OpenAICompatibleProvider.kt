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
 * Shared base for OpenAI-compatible REST APIs (OpenAI, Grok/xAI).
 * Subclasses only need to provide providerId and default baseUrl.
 */
abstract class OpenAICompatibleProvider(
    protected var config: ProviderConfig,
    private val httpClient: OkHttpClient,
    private val defaultBaseUrl: String
) : IAIProvider {
    // config.providerId 사용: 추상 프로퍼티(providerId)는 서브클래스 초기화 전 null → NPE 방지
    private val TAG = "OpenAICompat[${config.providerId.name}]"
    private val JSON_MEDIA = "application/json".toMediaType()

    override val isAvailable: Boolean get() = config.apiKey.isNotBlank()

    override suspend fun sendMessage(
        messages: List<AIMessage>,
        systemPrompt: String?,
        tools: List<AIToolDefinition>,
        temperature: Float?,
        maxTokens: Int?
    ): AIResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            // ── 메시지 정규화 ──
            // tool role이 있거나 tools 파라미터가 있으면 Cloud API → 원본 유지
            // 그 외(llama.cpp 등) → strict alternation 정규화
            val hasToolMessages = messages.any { it.role == "tool" || it.pendingToolCalls != null }
            val useRawMessages = hasToolMessages || tools.isNotEmpty()
            val normalized = if (useRawMessages) {
                // Cloud API: systemPrompt만 앞에 삽입, 나머지 원본 유지
                buildList {
                    if (!systemPrompt.isNullOrBlank()) add(AIMessage("system", systemPrompt))
                    addAll(messages)
                }
            } else {
                normalizeMessages(messages, systemPrompt)
            }

            val messagesArray = JSONArray()
            for (msg in normalized) {
                when {
                    // ★ 도구 결과 메시지 → OpenAI tool role 포맷
                    msg.role == "tool" -> {
                        messagesArray.put(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", msg.toolCallId ?: msg.toolName ?: "")
                            put("content", msg.content)
                        })
                    }
                    // ★ 도구 호출이 포함된 assistant 메시지 → tool_calls 포맷
                    msg.role == "assistant" && msg.pendingToolCalls != null -> {
                        val toolCallsArray = JSONArray()
                        val toolCalls = msg.pendingToolCalls ?: emptyList()
                        toolCalls.forEach { tc ->
                            toolCallsArray.put(JSONObject().apply {
                                put("id", tc.id)
                                put("type", "function")
                                put("function", JSONObject().apply {
                                    put("name", tc.name)
                                    put("arguments", org.json.JSONObject(tc.arguments.mapValues { it.value?.toString() ?: "null" }).toString())
                                })
                            })
                        }
                        messagesArray.put(JSONObject().apply {
                            put("role", "assistant")
                            if (msg.content.isNotBlank()) put("content", msg.content) else put("content", JSONObject.NULL)
                            put("tool_calls", toolCallsArray)
                        })
                    }
                    // 일반 메시지
                    else -> messagesArray.put(JSONObject().put("role", msg.role).put("content", msg.content))
                }
            }

            val body = JSONObject().apply {
                put("model", config.model)
                put("messages", messagesArray)
                put("temperature", (temperature ?: config.temperature).toDouble())
                put("max_tokens", maxTokens ?: config.maxTokens)
                if (tools.isNotEmpty()) {
                    put("tools", buildToolsArray(tools))
                }
            }

            val request = Request.Builder()
                .url(config.baseUrl ?: defaultBaseUrl)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            val latency = System.currentTimeMillis() - startTime

            if (!response.isSuccessful) {
                Log.e(TAG, "API error ${response.code}: $responseBody")
                return@withContext AIResponse(
                    text = "API Error ${response.code}",
                    providerId = providerId,
                    latencyMs = latency
                )
            }

            parseResponse(responseBody, latency)
        } catch (e: Exception) {
            Log.e(TAG, "Request failed: ${e.message}", e)
            AIResponse(
                text = "Error: ${e.message}",
                providerId = providerId,
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
    }

    private fun buildToolsArray(tools: List<AIToolDefinition>): JSONArray {
        val arr = JSONArray()
        for (tool in tools) {
            arr.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", JSONObject(tool.parametersJson))
                })
            })
        }
        return arr
    }

    protected fun parseResponse(responseBody: String, latencyMs: Long): AIResponse {
        val json = JSONObject(responseBody)
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            return AIResponse(text = null, providerId = providerId, latencyMs = latencyMs)
        }

        val message = choices.getJSONObject(0).getJSONObject("message")
        val text: String? = if (message.isNull("content")) null else message.optString("content")
        val finishReason = choices.getJSONObject(0).optString("finish_reason", "stop")

        // Parse tool calls
        val toolCalls = mutableListOf<AIToolCall>()
        val toolCallsArray = message.optJSONArray("tool_calls")
        if (toolCallsArray != null) {
            for (i in 0 until toolCallsArray.length()) {
                val tc = toolCallsArray.getJSONObject(i)
                val fn = tc.getJSONObject("function")
                val args = try {
                    val argsJson = JSONObject(fn.getString("arguments"))
                    argsJson.keys().asSequence().associateWith { key -> argsJson.opt(key) }
                } catch (e: Exception) {
                    emptyMap<String, Any?>()
                }
                toolCalls.add(AIToolCall(
                    id = tc.optString("id", ""),
                    name = fn.getString("name"),
                    arguments = args
                ))
            }
        }

        // Parse usage
        val usageJson = json.optJSONObject("usage")
        val usage = if (usageJson != null) {
            TokenUsage(
                promptTokens = usageJson.optInt("prompt_tokens", 0),
                completionTokens = usageJson.optInt("completion_tokens", 0),
                totalTokens = usageJson.optInt("total_tokens", 0)
            )
        } else null

        return AIResponse(
            text = text,
            toolCalls = toolCalls,
            finishReason = finishReason,
            usage = usage,
            providerId = providerId,
            latencyMs = latencyMs
        )
    }

    /**
     * 비전 메시지 전송 (OpenAI Vision API 호환 — llama.cpp/KoboldCpp 지원).
     * 이미지를 base64 인코딩하여 content 배열에 포함.
     */
    suspend fun sendVisionMessage(
        bitmap: android.graphics.Bitmap,
        textPrompt: String,
        systemPrompt: String? = null
    ): AIResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            // Bitmap → JPEG → Base64
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, stream)
            val base64 = android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)

            val messagesArray = JSONArray()
            if (systemPrompt != null) {
                messagesArray.put(JSONObject().put("role", "system").put("content", systemPrompt))
            }

            // OpenAI Vision 포맷: content를 배열로 전달
            val contentArray = JSONArray()
            contentArray.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64"))
            })
            contentArray.put(JSONObject().apply {
                put("type", "text")
                put("text", textPrompt)
            })

            messagesArray.put(JSONObject().apply {
                put("role", "user")
                put("content", contentArray)
            })

            val body = JSONObject().apply {
                put("model", config.model)
                put("messages", messagesArray)
                put("temperature", config.temperature.toDouble())
                put("max_tokens", config.maxTokens)
            }

            val request = Request.Builder()
                .url(config.baseUrl ?: defaultBaseUrl)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            val latency = System.currentTimeMillis() - startTime

            if (!response.isSuccessful) {
                Log.e(TAG, "Vision API error ${response.code}: ${responseBody.take(200)}")
                return@withContext AIResponse(
                    text = "Error: Vision API ${response.code}",
                    providerId = providerId,
                    latencyMs = latency
                )
            }

            parseResponse(responseBody, latency)
        } catch (e: Exception) {
            Log.e(TAG, "Vision request failed: ${e.message}", e)
            AIResponse(
                text = "Error: ${e.message}",
                providerId = providerId,
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
    }

    /**
     * 메시지 정규화 — 모든 백엔드 호환성 보장.
     *
     * llama.cpp/gemma 등 strict alternation 요구 백엔드 호환:
     * 1) systemPrompt → 첫 번째 system 메시지로 삽입
     * 2) tool role → user로 변환 (llama.cpp는 tool role 미지원)
     * 3) assistant(tool_calls) → 일반 assistant로 변환
     * 4) 연속 같은 role 메시지 → "\n\n"으로 병합
     * 5) 첫 non-system 메시지가 user가 아니면 빈 user 삽입
     * 6) 마지막 메시지가 assistant면 빈 user 추가 (응답 유도)
     */
    private fun normalizeMessages(messages: List<AIMessage>, systemPrompt: String?): List<AIMessage> {
        val result = mutableListOf<AIMessage>()

        // system prompt 삽입
        if (!systemPrompt.isNullOrBlank()) {
            result.add(AIMessage("system", systemPrompt))
        }

        for (msg in messages) {
            // tool → user로 변환 (llama.cpp 호환)
            val normalized = when {
                msg.role == "tool" -> AIMessage(
                    role = "user",
                    content = "[Tool Result] ${msg.content}"
                )
                msg.role == "assistant" && msg.pendingToolCalls != null -> AIMessage(
                    role = "assistant",
                    content = buildString {
                        if (msg.content.isNotBlank()) append(msg.content)
                        msg.pendingToolCalls?.forEach { tc ->
                            if (isNotEmpty()) append("\n")
                            append("[Calling ${tc.name}]")
                        }
                    }
                )
                else -> msg
            }

            val last = result.lastOrNull()
            if (last != null && last.role == normalized.role && last.role != "system") {
                // 같은 role 연속 → 병합
                result[result.lastIndex] = AIMessage(
                    role = last.role,
                    content = "${last.content}\n\n${normalized.content}"
                )
            } else {
                result.add(normalized)
            }
        }

        // system 다음 첫 non-system 메시지가 user가 아니면 빈 user 삽입
        val firstNonSystem = result.indexOfFirst { it.role != "system" }
        if (firstNonSystem >= 0 && result[firstNonSystem].role != "user") {
            result.add(firstNonSystem, AIMessage("user", "."))
        }

        // 마지막이 assistant면 빈 user 추가 (응답 유도)
        if (result.lastOrNull()?.role == "assistant") {
            result.add(AIMessage("user", "Continue."))
        }

        return result
    }

    override fun updateApiKey(apiKey: String) {
        config = config.copy(apiKey = apiKey)
    }

    override fun getModelName() = config.model
}
