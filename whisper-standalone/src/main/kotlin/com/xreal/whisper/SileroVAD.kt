package com.xreal.whisper

/**
 * [AI ASSISTANT NOTE] VAD Implementation Context:
 * Silero VAD is the gatekeeper for Whisper. If VAD thresholds are too high, 
 * users will report "Whisper is not working". Currently set to 0.6f.
 * 
 * FALLBACK LOGIC: If 'silero_vad.tflite' is missing or fails on certain devices,
 * it falls back to an energy-based detection. This is a safety net for production.
 */

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Silero VAD (Voice Activity Detection) Wrapper.
 * Provides speech probability for audio frames.
 * Includes a robust energy-based fallback if the TFLite model is missing/invalid.
 */
class SileroVAD(private val context: Context) {
    private var interpreter: Interpreter? = null
    private val modelName = "silero_vad.tflite"
    
    // Silero VAD v4/v5 State Management:
    // Silero is an RNN-based VAD that maintains state (h and c) across frames.
    // This allows it to learn temporal patterns (e.g., distinguishing a short click from speech).
    private var hState = ByteBuffer.allocateDirect(1 * 1 * 64 * 4).order(ByteOrder.nativeOrder())
    private var cState = ByteBuffer.allocateDirect(1 * 1 * 64 * 4).order(ByteOrder.nativeOrder())
    
    private var useFallback = false
    private val TAG = "SileroVAD"

    init {
        try {
            val modelBuffer = FileUtil.loadMappedFile(context, modelName)
            if (modelBuffer.limit() < 1000) {
                Log.w(TAG, "VAD model file is too small (placeholder?). Using energy-based fallback.")
                useFallback = true
            } else {
                val options = Interpreter.Options()
                options.setNumThreads(1) // VAD is lightweight
                interpreter = Interpreter(modelBuffer, options)
                Log.i(TAG, "Silero VAD initialized successfully.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Silero VAD model: ${e.message}. Using fallback.")
            useFallback = true
        }
    }

    /**
     * Analyzes a frame of audio and returns the speech probability (0.0 to 1.0).
     * frame size should be 512 for Silero, but fallback handles any size.
     */
    fun isSpeech(samples: ShortArray): Float {
        if (useFallback || interpreter == null) {
            return energyDetection(samples)
        }

        return try {
            // Silero VAD v4/v5 usually expects Float32 input [1, 512]
            val inputBuffer = ByteBuffer.allocateDirect(samples.size * 4).order(ByteOrder.nativeOrder())
            for (s in samples) {
                inputBuffer.putFloat(s / 32768.0f)
            }
            inputBuffer.rewind()

            val outputBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
            
            // Silero VAD Inference:
            // Input 0: Audio samples (Float32, [1, 512])
            // Input 1 & 2: Hidden/Cell states for the RNN.
            val inputs = arrayOf(inputBuffer, hState, cState)
            val outputs = mutableMapOf<Int, Any>(
                0 to outputBuffer,
                1 to hState,
                2 to cState
            )
            
            interpreter?.runForMultipleInputsOutputs(inputs, outputs)
            outputBuffer.rewind()
            outputBuffer.getFloat()
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}")
            energyDetection(samples)
        }
    }

    /**
     * Simple but robust energy detection.
     * Calculated as RMS normalized to [0, 1].
     */
    private fun energyDetection(samples: ShortArray): Float {
        var sum = 0.0
        for (s in samples) {
            sum += s.toDouble() * s
        }
        val rms = sqrt(sum / samples.size)
        // Normalize: -40dB (approx 327) is low speech, -20dB (3276) is normal.
        // We use a sigmoid-like mapping or linear clamp.
        val normalized = (rms / 3000.0).toFloat().coerceIn(0.0f, 1.0f)
        return normalized
    }

    fun close() {
        interpreter?.close()
    }
}
