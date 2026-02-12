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
class OCRModel : IAIModel {
    private val TAG = "OCRModel"
    private var recognizer: TextRecognizer? = null
    
    override val priority: Int = 5 // Medium
    
    override fun initialize(interpreterOptions: Interpreter.Options) {
        Log.i(TAG, "Initializing ML Kit OCR (Korean Optimized)...")
        recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    
    fun process(bitmap: Bitmap, callback: (List<OverlayView.OcrResult>, Int, Int) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer?.process(image)
            ?.addOnSuccessListener { visionText ->
                val results = visionText.textBlocks.map { block ->
                    OverlayView.OcrResult(
                        text = block.text,
                        boundingBox = block.boundingBox ?: android.graphics.Rect(),
                        isVisible = true
                    )
                }
                callback(results, bitmap.width, bitmap.height)
            }
            ?.addOnFailureListener { e ->
                Log.e(TAG, "OCR Processing Failed: ${e.message}")
            }
    }
    
    override fun release() {
        Log.i(TAG, "Releasing OCR Recognizer")
        recognizer?.close()
        recognizer = null
    }
}
