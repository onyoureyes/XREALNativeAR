package com.xreal.nativear.ai

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.xreal.nativear.core.ErrorReporter
import com.xreal.nativear.core.ErrorSeverity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * IAIProvider implementation for Google Gemini.
 * Gemini REST API (generativelanguage.googleapis.com) 직접 호출.
 * SDK(com.google.ai.client.generativeai) 의존성 제거 — OkHttp로 통일.
 *
 * Supports:
 * - Text conversation (stateless, per-request)
 * - Tool calling via AIToolDefinition → Gemini function_declarations
 * - Vision (bitmap) via sendVisionMessage
 * - role="tool" / role="assistant"+pendingToolCalls 히스토리 변환
 */
class GeminiProvider(
    private var config: ProviderConfig,
    private val httpClient: OkHttpClient = OkHttpClient()
) : IAIProvider {
    private val TAG = "GeminiProvider"
    private val JSON_MEDIA = "application/json".toMediaType()

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    }

    override val providerId = ProviderId.GEMINI
    override val isAvailable: Boolean get() = config.apiKey.isNotBlank()

    /**
     * Fallback 도구 목록 — tools 파라미터가 비어있을 때 사용.
     * AppModule에서 ToolDefinitionRegistry.getAllToolDefinitions()로 설정.
     */
    var registeredTools: List<AIToolDefinition> = emptyList()

    override suspend fun sendMessage(
        messages: List<AIMessage>,
        systemPrompt: String?,
        tools: List<AIToolDefinition>,
        temperature: Float?,
        maxTokens: Int?
    ): AIResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            val activeTools = tools.ifEmpty { registeredTools }

            val body = JSONObject().apply {
                // System instruction
                if (!systemPrompt.isNullOrBlank()) {
                    put("system_instruction", JSONObject().put("parts",
                        JSONArray().put(JSONObject().put("text", systemPrompt))))
                }

                // Contents (chat history)
                put("contents", buildContents(messages))

                // Tools
                if (activeTools.isNotEmpty()) {
                    put("tools", JSONArray().put(JSONObject().put(
                        "function_declarations", buildFunctionDeclarations(activeTools))))
                }

                // Generation config
                put("generationConfig", JSONObject().apply {
                    put("temperature", (temperature ?: config.temperature).toDouble())
                    put("maxOutputTokens", maxTokens ?: config.maxTokens)
                })
            }

            val url = "$BASE_URL/${config.model}:generateContent?key=${config.apiKey}"
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            val latency = System.currentTimeMillis() - startTime

            if (!response.isSuccessful) {
                Log.e(TAG, "Gemini API error ${response.code}: ${responseBody.take(300)}")
                return@withContext AIResponse(
                    text = "API Error ${response.code}",
                    providerId = ProviderId.GEMINI,
                    latencyMs = latency
                )
            }

            parseGeminiResponse(responseBody, latency)
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
     * Vision (image + text) 메시지 전송.
     * Bitmap → JPEG base64 → inlineData part.
     */
    suspend fun sendVisionMessage(
        prompt: String,
        bitmap: Bitmap,
        systemPrompt: String? = null
    ): AIResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            // Bitmap → JPEG → Base64
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
            val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

            val parts = JSONArray().apply {
                put(JSONObject().put("inlineData", JSONObject().apply {
                    put("mimeType", "image/jpeg")
                    put("data", base64)
                }))
                put(JSONObject().put("text", prompt))
            }

            val body = JSONObject().apply {
                if (!systemPrompt.isNullOrBlank()) {
                    put("system_instruction", JSONObject().put("parts",
                        JSONArray().put(JSONObject().put("text", systemPrompt))))
                }
                put("contents", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", parts)
                }))
                put("generationConfig", JSONObject().apply {
                    put("temperature", config.temperature.toDouble())
                    put("maxOutputTokens", config.maxTokens)
                })
            }

            val url = "$BASE_URL/${config.model}:generateContent?key=${config.apiKey}"
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            val latency = System.currentTimeMillis() - startTime

            if (!response.isSuccessful) {
                Log.e(TAG, "Gemini Vision API error ${response.code}: ${responseBody.take(300)}")
                return@withContext AIResponse(
                    text = "Error: Vision API ${response.code}",
                    providerId = ProviderId.GEMINI,
                    latencyMs = latency
                )
            }

            parseGeminiResponse(responseBody, latency)
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

    // ── 요청 빌드 ──

    /**
     * AIMessage 리스트 → Gemini contents 배열.
     * role: user→"user", assistant→"model", tool→functionResponse, system→무시(별도 처리)
     */
    private fun buildContents(messages: List<AIMessage>): JSONArray {
        val contents = JSONArray()

        for (msg in messages) {
            when {
                msg.role == "system" -> {} // system_instruction으로 별도 전달

                // 도구 결과 → functionResponse part
                msg.role == "tool" -> {
                    contents.put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("functionResponse",
                            JSONObject().apply {
                                put("name", msg.toolName ?: "")
                                put("response", JSONObject().put("result", msg.content))
                            }
                        )))
                    })
                }

                // 도구 호출 포함 assistant → functionCall parts
                msg.role == "assistant" && msg.pendingToolCalls != null -> {
                    val parts = JSONArray()
                    msg.pendingToolCalls?.forEach { tc ->
                        parts.put(JSONObject().put("functionCall", JSONObject().apply {
                            put("name", tc.name)
                            put("args", JSONObject(tc.arguments.mapValues { it.value?.toString() ?: "" }))
                        }))
                    }
                    if (msg.content.isNotBlank()) {
                        parts.put(JSONObject().put("text", msg.content))
                    }
                    contents.put(JSONObject().apply {
                        put("role", "model")
                        put("parts", parts)
                    })
                }

                // 일반 메시지
                else -> {
                    val role = if (msg.role == "user") "user" else "model"
                    contents.put(JSONObject().apply {
                        put("role", role)
                        put("parts", JSONArray().put(JSONObject().put("text", msg.content)))
                    })
                }
            }
        }

        return contents
    }

    /**
     * AIToolDefinition 리스트 → Gemini function_declarations 배열.
     * parametersJson의 type 값을 대문자로 변환 (Gemini API 규격).
     */
    private fun buildFunctionDeclarations(tools: List<AIToolDefinition>): JSONArray {
        val arr = JSONArray()
        for (tool in tools) {
            arr.put(JSONObject().apply {
                put("name", tool.name)
                put("description", tool.description)
                val params = JSONObject(tool.parametersJson)
                uppercaseTypes(params)
                put("parameters", params)
            })
        }
        return arr
    }

    /** JSON Schema의 type 값을 재귀적으로 대문자로 변환 (Gemini API 규격) */
    private fun uppercaseTypes(json: JSONObject) {
        if (json.has("type")) {
            json.put("type", json.getString("type").uppercase())
        }
        val props = json.optJSONObject("properties")
        if (props != null) {
            for (key in props.keys()) {
                val prop = props.optJSONObject(key)
                if (prop != null) uppercaseTypes(prop)
            }
        }
        val items = json.optJSONObject("items")
        if (items != null) uppercaseTypes(items)
    }

    // ── 응답 파싱 ──

    private fun parseGeminiResponse(responseBody: String, latencyMs: Long): AIResponse {
        val json = JSONObject(responseBody)
        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            return AIResponse(text = null, providerId = ProviderId.GEMINI, latencyMs = latencyMs)
        }

        val candidate = candidates.getJSONObject(0)
        val content = candidate.optJSONObject("content")
        val parts = content?.optJSONArray("parts") ?: JSONArray()

        var text: String? = null
        val toolCalls = mutableListOf<AIToolCall>()

        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            when {
                part.has("text") -> {
                    text = (text ?: "") + part.getString("text")
                }
                part.has("functionCall") -> {
                    val fc = part.getJSONObject("functionCall")
                    val name = fc.getString("name")
                    val args = fc.optJSONObject("args")
                    val argsMap = if (args != null) {
                        args.keys().asSequence().associateWith { key -> args.opt(key) }
                    } else emptyMap()

                    toolCalls.add(AIToolCall(
                        id = name,
                        name = name,
                        arguments = argsMap
                    ))
                }
            }
        }

        // Usage
        val usageMeta = json.optJSONObject("usageMetadata")
        val usage = if (usageMeta != null) {
            TokenUsage(
                promptTokens = usageMeta.optInt("promptTokenCount", 0),
                completionTokens = usageMeta.optInt("candidatesTokenCount", 0),
                totalTokens = usageMeta.optInt("totalTokenCount", 0)
            )
        } else null

        return AIResponse(
            text = text,
            toolCalls = toolCalls,
            finishReason = if (toolCalls.isNotEmpty()) "tool_use"
                else candidate.optString("finishReason", "STOP").lowercase(),
            usage = usage,
            providerId = ProviderId.GEMINI,
            latencyMs = latencyMs
        )
    }
}
