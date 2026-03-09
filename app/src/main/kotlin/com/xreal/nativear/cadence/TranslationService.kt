package com.xreal.nativear.cadence

import android.util.Log
import com.xreal.nativear.ai.AICallGateway
import com.xreal.nativear.ai.AIMessage

/**
 * Lightweight translation service using Gemini.
 * Designed for quick OCR text translation in AR scenarios.
 */
class TranslationService(
    private val aiRegistry: com.xreal.nativear.ai.IAICallService,
    private val batchProcessor: com.xreal.nativear.batch.BatchProcessor? = null,
    private val tokenBudgetTracker: com.xreal.nativear.router.persona.TokenBudgetTracker? = null
) {
    private val TAG = "TranslationService"

    companion object {
        private const val SYSTEM_PROMPT = "당신은 번역기입니다. 입력된 텍스트를 지정된 언어로 번역하세요. 번역 결과만 출력하세요. 추가 설명이나 주석은 절대 포함하지 마세요."
    }

    /**
     * Translate text from source language to target language.
     * Uses BatchProcessor cache if available to avoid duplicate API calls.
     * @return translated text, or null on failure
     */
    suspend fun translate(text: String, sourceLang: String, targetLang: String = "ko"): String? {
        if (text.isBlank()) return null

        // Check batch processor translation cache first
        batchProcessor?.getCachedTranslation(text, sourceLang, targetLang)?.let { cached ->
            Log.d(TAG, "Translation cache hit: $text → $cached")
            return cached
        }

        // Budget gate
        tokenBudgetTracker?.let { tracker ->
            val check = tracker.checkBudget(com.xreal.nativear.ai.ProviderId.GEMINI, estimatedTokens = 300)
            if (!check.allowed) {
                Log.w(TAG, "Translation blocked by budget: ${check.reason}")
                return null
            }
        }

        return try {
            val prompt = "[$sourceLang → $targetLang] $text"
            val response = aiRegistry.quickText(
                messages = listOf(AIMessage(role = "user", content = prompt)),
                systemPrompt = SYSTEM_PROMPT,
                temperature = 0.1f,
                maxTokens = 512,
                visibility = AICallGateway.VisibilityIntent.INTERNAL_ONLY,
                intent = "ocr_translation"
            ) ?: return null
            val result = response.text?.trim()
            tokenBudgetTracker?.recordUsage(com.xreal.nativear.ai.ProviderId.GEMINI, (result?.length?.div(4) ?: 0).coerceAtLeast(50))

            // Cache the translation
            if (result != null) {
                batchProcessor?.cacheTranslation(text, sourceLang, targetLang, result)
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed: ${e.message}")
            null
        }
    }

    /**
     * Detect the primary language of the given text.
     * Uses character-range heuristics (fast, no API call).
     */
    fun detectLanguage(text: String): String {
        var korean = 0
        var japanese = 0
        var chinese = 0
        var latin = 0
        var total = 0

        for (c in text) {
            if (c.isWhitespace() || c.isDigit()) continue
            total++
            when {
                c in '\uAC00'..'\uD7AF' || c in '\u3131'..'\u318E' -> korean++
                c in '\u3040'..'\u309F' || c in '\u30A0'..'\u30FF' -> japanese++
                c in '\u4E00'..'\u9FFF' -> chinese++
                c in 'A'..'Z' || c in 'a'..'z' -> latin++
            }
        }

        if (total == 0) return "unknown"

        val koreanRatio = korean.toFloat() / total
        val japaneseRatio = japanese.toFloat() / total
        val chineseRatio = chinese.toFloat() / total
        val latinRatio = latin.toFloat() / total

        return when {
            koreanRatio > 0.3f -> "ko"
            japaneseRatio > 0.2f -> "ja"
            chineseRatio > 0.3f -> "zh"
            latinRatio > 0.3f -> "en"
            else -> "unknown"
        }
    }
}
