package com.xreal.nativear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp

/**
 * FacialExpressionClassifier: MobileNet-based FER (Facial Expression Recognition).
 *
 * Input:  (1, 48, 48, 1) float32 grayscale [0, 1]
 * Output: (1, 7) float32 — class scores for 7 emotions
 *
 * Labels: angry, disgust, fear, happy, sad, surprise, neutral
 */
class FacialExpressionClassifier(private val context: Context) : com.xreal.ai.IAIModel {

    private val TAG = "FERClassifier"
    private val MODEL_FILE = "fer_emotion.tflite"
    private val INPUT_SIZE = 48

    val EXPRESSIONS = listOf("angry", "disgust", "fear", "happy", "sad", "surprise", "neutral")

    private var interpreter: Interpreter? = null
    private var outputArray: Array<FloatArray>? = null

    override val priority: Int = 3
    override var isReady: Boolean = false
        private set
    override var isLoaded: Boolean = false
        private set

    override suspend fun prepare(options: Interpreter.Options): Boolean {
        if (isLoaded) return true

        return try {
            val modelBuffer = FileUtil.loadMappedFile(context, MODEL_FILE)
            interpreter = Interpreter(modelBuffer, options)

            val outputShape = interpreter!!.getOutputTensor(0).shape() // [1, 7]
            outputArray = Array(1) { FloatArray(outputShape[1]) }

            Log.i(TAG, "FER model loaded: ${outputShape[1]} classes")
            isLoaded = true
            isReady = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load FER model: ${e.message}", e)
            false
        }
    }

    override fun release() {
        interpreter?.close()
        interpreter = null
        isLoaded = false
        isReady = false
    }

    /**
     * Classify facial expression from a face crop.
     * @param faceCrop Bitmap of a cropped face region (any size, will be resized)
     * @return Pair(expressionLabel, confidence) or null on failure
     */
    fun classify(faceCrop: Bitmap): Pair<String, Float>? {
        val interp = interpreter ?: return null
        val out = outputArray ?: return null

        try {
            // Convert to 48x48 grayscale
            val grayscale = toGrayscale(faceCrop, INPUT_SIZE, INPUT_SIZE)
            val inputBuffer = bitmapToGrayscaleBuffer(grayscale)
            grayscale.recycle()

            interp.run(inputBuffer, out)

            // Softmax + argmax
            val scores = softmax(out[0])
            var maxIdx = 0
            for (i in scores.indices) {
                if (scores[i] > scores[maxIdx]) maxIdx = i
            }

            return EXPRESSIONS[maxIdx] to scores[maxIdx]
        } catch (e: Exception) {
            Log.e(TAG, "FER classification failed: ${e.message}")
            return null
        }
    }

    private fun toGrayscale(src: Bitmap, width: Int, height: Int): Bitmap {
        val scaled = Bitmap.createScaledBitmap(src, width, height, true)
        val grayscale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grayscale)
        val paint = Paint()
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        if (scaled !== src) scaled.recycle()
        return grayscale
    }

    /**
     * Convert a grayscale ARGB_8888 bitmap to a float buffer (1, 48, 48, 1).
     * Extracts the red channel (R=G=B for grayscale) and normalizes to [0, 1].
     */
    private fun bitmapToGrayscaleBuffer(bitmap: Bitmap): java.nio.ByteBuffer {
        val buffer = java.nio.ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 1 * 4)
        buffer.order(java.nio.ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            buffer.putFloat(r / 255.0f)
        }

        buffer.rewind()
        return buffer
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.max()
        val exps = FloatArray(logits.size) { Math.exp((logits[it] - max).toDouble()).toFloat() }
        val sum = exps.sum()
        for (i in exps.indices) exps[i] /= sum
        return exps
    }
}
