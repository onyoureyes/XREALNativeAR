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
    private var recognizers: List<TextRecognizer> = emptyList()
    private var languages = listOf("Korean", "Japanese", "Chinese")
    private var currentLanguageIndex = 0
    
    override val priority: Int = 5 // Medium
    
    override var isReady: Boolean = false
        private set
    override var isLoaded: Boolean = false
        private set

    override suspend fun prepare(options: Interpreter.Options): Boolean {
        if (isLoaded) return true
        Log.i(TAG, "Preparing Multi-Language OCR (KR, JP, CN)...")
        return try {
            val kr = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            val jp = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            val cn = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            
            recognizers = listOf(kr, jp, cn)
            isLoaded = true
            isReady = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "OCR Preparation Failed: ${e.message}")
            false
        }
    }

    
    fun process(bitmap: Bitmap, callback: (List<OverlayView.OcrResult>, Int, Int) -> Unit) {
        if (recognizers.isEmpty()) return

        // Round-Robin Scheduling: Cycle through languages each frame
        val recognizer = recognizers[currentLanguageIndex]
        val contextLang = languages[currentLanguageIndex]
        
        // Advance index for next call
        currentLanguageIndex = (currentLanguageIndex + 1) % recognizers.size

        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                if (visionText.textBlocks.isNotEmpty()) {
                    // Log.d(TAG, "OCR Hit ($contextLang): ${visionText.textBlocks.size} blocks")
                }
                
                val results = visionText.textBlocks.map { block ->
                    OverlayView.OcrResult(
                        text = block.text, // You might want to prefix [$contextLang] for debug
                        boundingBox = block.boundingBox ?: android.graphics.Rect(),
                        isVisible = true
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
        recognizers.forEach { it.close() }
        recognizers = emptyList()
    }
}
