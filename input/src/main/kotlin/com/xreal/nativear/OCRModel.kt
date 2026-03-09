package com.xreal.nativear

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.xreal.ai.IAIModel
import org.tensorflow.lite.Interpreter

/**
 * OCRModel: Unified ML Kit text recognition for Latin/CJK scripts.
 */
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

class OCRModel : IAIModel {
    private val TAG = "OCRModel"
    // Multiple recognizers for CJK support
    // ★ 한국어 우선: index 0=Korean (매 호출), index 1=Japanese/Chinese (5프레임에 1번씩 교체)
    private var krRecognizer: TextRecognizer? = null
    private var jpRecognizer: TextRecognizer? = null
    private var cnRecognizer: TextRecognizer? = null
    private var frameCount = 0

    override val priority: Int = 5 // Medium

    override var isReady: Boolean = false
        private set
    override var isLoaded: Boolean = false
        private set

    override suspend fun prepare(options: Interpreter.Options): Boolean {
        if (isLoaded) return true
        Log.i(TAG, "Preparing Multi-Language OCR (KR 우선, JP/CN 보조)...")
        return try {
            krRecognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            jpRecognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            cnRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            isLoaded = true
            isReady = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "OCR Preparation Failed: ${e.message}")
            false
        }
    }

    fun process(bitmap: Bitmap, callback: (List<OcrResult>, Int, Int) -> Unit) {
        val kr = krRecognizer ?: run {
            Log.w(TAG, "OCR 미준비 — process 무시")
            return
        }
        frameCount++

        // ★ 전략: Korean은 매 프레임 실행 (주요 사용 언어)
        // Japanese/Chinese는 5프레임에 1번 교대로 보조 실행
        // 이전: 3개 라운드로빈 → 한국어가 3프레임에 1번만 실행 (간헐적)
        // 개선: 한국어 상시 + JP/CN 10프레임에 1번 교대
        val recognizer: TextRecognizer
        val contextLang: String
        when {
            frameCount % 10 == 5 && jpRecognizer != null -> {
                recognizer = jpRecognizer!!
                contextLang = "Japanese"
            }
            frameCount % 10 == 0 && cnRecognizer != null -> {
                recognizer = cnRecognizer!!
                contextLang = "Chinese"
            }
            else -> {
                recognizer = kr
                contextLang = "Korean"
            }
        }

        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                if (visionText.textBlocks.isNotEmpty()) {
                    // Log.d(TAG, "OCR Hit ($contextLang): ${visionText.textBlocks.size} blocks")
                }
                
                val results = visionText.textBlocks.map { block ->
                    OcrResult(
                        text = block.text, // You might want to prefix [$contextLang] for debug
                        box = block.boundingBox ?: android.graphics.Rect(),
                        isValid = true
                    )
                }
                callback(results, bitmap.width, bitmap.height)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR Processing Failed ($contextLang): ${e.message}")
            }
    }
    
    override fun release() {
        Log.i(TAG, "Releasing OCR Recognizers")
        krRecognizer?.close(); krRecognizer = null
        jpRecognizer?.close(); jpRecognizer = null
        cnRecognizer?.close(); cnRecognizer = null
        // ★ isLoaded/isReady 초기화 → 다음 prepare() 호출 시 재초기화 가능
        isLoaded = false
        isReady = false
    }
}
