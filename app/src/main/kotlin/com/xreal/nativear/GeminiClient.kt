package com.xreal.nativear

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GenerateContentResponse
import com.google.ai.client.generativeai.type.Tool
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * GeminiClient: Handles communication with the Google Gemini SDK.
 * Separated from prompts and tools for modularity.
 */
class GeminiClient(private val apiKey: String) {
    private val TAG = "GeminiClient"
    
    private val generativeModel = GenerativeModel(
        modelName = "gemini-1.5-flash",
        apiKey = apiKey,
        systemInstruction = content { text(GeminiPrompts.SYSTEM_INSTRUCTION) },
        tools = listOf(Tool(GeminiTools.getAllTools()))
    )
    
    private val chat = generativeModel.startChat()
    
    suspend fun sendMessage(message: String, bitmap: android.graphics.Bitmap? = null): GenerateContentResponse? = withContext(Dispatchers.IO) {
        try {
            if (bitmap != null) {
                // Vision calls are usually stateless in the current SDK for simplicity, 
                // but we can send them through the model.
                val inputContent = content {
                    image(bitmap)
                    text(message)
                }
                generativeModel.generateContent(inputContent)
            } else {
                chat.sendMessage(message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini Error: ${e.message}")
            null
        }
    }

    suspend fun getChatHistory() = chat.history
}

