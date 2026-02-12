package com.xreal.nativear

import android.graphics.Bitmap
import android.util.Log
import com.google.genai.Client
import com.google.genai.types.Part
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Tool
import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.Schema
import com.google.genai.types.GenerateContentResponse
import com.google.genai.types.Content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class GeminiClient(apiKey: String) {
    private val TAG = "GeminiClient"
    // Using the 2026 Unified GenAI Client
    internal val client = Client.builder()
        .apiKey(apiKey)
        .build()

    suspend fun interpretScene(bitmap: Bitmap, ocrText: String): Pair<String?, String?> {
        return withContext(Dispatchers.IO) {
            try {
                val prompt = """
                    사용자는 현재 AR 글래스를 통해 세상을 보고 있습니다. 
                    장면에서 감지된 텍스트(OCR): "$ocrText"
                    
                    사진을 분석하여 사용자가 무엇을 보고 있는지 친구처럼 자연스럽게 설명해주세요.
                    - "스냅샷 캡처됨" 같은 딱딱한 말투는 절대 지양하세요.
                    - 한 문장으로 매우 짧게 (10자 내외) 말해주세요. 
                    - 한국어 텍스트가 있다면 그 의미를 중심으로 설명해주세요.
                    - 친절하고 스마트한 비서의 말투(한국어)를 사용하세요.
                """.trimIndent()

                // Convert Bitmap to ByteArray for 2026 SDK
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                val byteArray = stream.toByteArray()

                // 2026 SDK Syntax: Using gemini-2.5-flash with Content -> List<Part> -> Blob
                val content = com.google.genai.types.Content.builder()
                    .role("user")
                    .parts(listOf(
                        Part.builder().text(prompt).build(),
                        Part.builder().inlineData(
                            com.google.genai.types.Blob.builder()
                                .data(byteArray)
                                .mimeType("image/jpeg")
                                .build()
                        ).build()
                    ))
                    .build()

                val response = client.models.generateContent(
                    "gemini-2.5-flash",
                    listOf(content),
                    null
                )

                val text = response.text()
                Log.i(TAG, "Gemini Response: $text")
                Pair(text, null)
            } catch (e: Exception) {
                Log.e(TAG, "Interpretation failed: ${e.message}")
                Pair(null, e.message ?: "Unknown Error")
            }
        }
    }
    suspend fun generateText(prompt: String, useTools: Boolean = false): Pair<com.google.genai.types.GenerateContentResponse?, String?> {
        return withContext(Dispatchers.IO) {
            try {
                val content = com.google.genai.types.Content.builder()
                    .role("user")
                    .parts(listOf(Part.builder().text(prompt).build()))
                    .build()

                val config = if (useTools) {
                    GenerateContentConfig.builder()
                        .tools(listOf(Tool.builder()
                            .functionDeclarations(listOf(
                                FunctionDeclaration.builder()
                                    .name("query_temporal_memory")
                                    .description("Search memories within a specific time range.")
                                    .parameters(Schema.builder()
                                        .type("object")
                                        .properties(mapOf(
                                            "start_time" to Schema.builder().type("string").description("ISO format or 'today', 'now'").build(),
                                            "end_time" to Schema.builder().type("string").description("ISO format or 'today', 'now'").build()
                                        ))
                                        .required(listOf("start_time", "end_time"))
                                        .build())
                                    .build(),
                                FunctionDeclaration.builder()
                                    .name("query_spatial_memory")
                                    .description("Search memories near a location.")
                                    .parameters(Schema.builder()
                                        .type("object")
                                        .properties(mapOf(
                                            "latitude" to Schema.builder().type("number").build(),
                                            "longitude" to Schema.builder().type("number").build(),
                                            "radius_km" to Schema.builder().type("number").description("Radius in kilometers").build()
                                        ))
                                        .required(listOf("latitude", "longitude"))
                                        .build())
                                    .build(),
                                FunctionDeclaration.builder()
                                    .name("query_keyword_memory")
                                    .description("Search memories by text keywords.")
                                    .parameters(Schema.builder()
                                        .type("object")
                                        .properties(mapOf(
                                            "keyword" to Schema.builder().type("string").build()
                                        ))
                                        .required(listOf("keyword"))
                                        .build())
                                    .build()
                            ))
                            .build()))
                        .build()
                } else null

                val response = client.models.generateContent(
                    "gemini-2.5-flash",
                    listOf(content),
                    config
                )
                Pair(response, null)
            } catch (e: Exception) {
                Log.e(TAG, "Text Generation Failed: ${e.message}")
                Pair(null, e.message)
            }
        }
    }

    suspend fun generateWithHistory(history: List<com.google.genai.types.Content>): com.google.genai.types.GenerateContentResponse? {
        return withContext(Dispatchers.IO) {
            try {
                client.models.generateContent(
                    "gemini-2.5-flash",
                    history,
                    null
                )
            } catch (e: Exception) {
                Log.e(TAG, "History Generation Failed: ${e.message}")
                null
            }
        }
    }
}
