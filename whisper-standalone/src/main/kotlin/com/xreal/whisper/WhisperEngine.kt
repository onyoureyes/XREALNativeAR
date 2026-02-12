package com.xreal.whisper

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter

/**
 * WhisperEngine: Facade for on-device Speech-to-Text.
 * 
 * Modularized Architecture:
 * - AudioCaptureManager: Handles Mic & VAD.
 * - WhisperTokenizer: Handles decoding & filtering.
 * - WhisperInference: Handles TFLite model execution (Single or Split).
 */
class WhisperEngine(private val context: Context) {
    private val TAG = "WhisperEngine"
    
    private val audioCapture = AudioCaptureManager(context)
    private val tokenizer = WhisperTokenizer(context)
    private var inference: WhisperInference? = null
    
    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var onResultListener: ((String) -> Unit)? = null
    private var onVadStatusListener: ((Boolean) -> Unit)? = null

    init {
        // Wire up callbacks
        audioCapture.setOnVadStatusListener { isSpeech ->
            onVadStatusListener?.invoke(isSpeech)
        }

        audioCapture.setOnAudioSegmentReadyListener { audioData ->
            processAudio(audioData)
        }
    }

    fun setOnResultListener(l: (String) -> Unit) {
        onResultListener = l
    }

    fun setOnVadStatusListener(l: (Boolean) -> Unit) {
        onVadStatusListener = l
        audioCapture.setOnVadStatusListener(l) // Forward to manager
    }

    // Default to TINY if not specified
    fun initialize(options: Interpreter.Options, modelType: ModelType = ModelType.TINY) {
        Log.i(TAG, "Initializing WhisperEngine with type: $modelType")
        
        try {
            val assetManager = context.assets
            val modelList = assetManager.list("") ?: emptyArray()

            // Strategy Selection: Split vs Single
            // Logic: If SPLIT suffix exists, force SplitInference
            val typeSuffix = if(modelType == ModelType.BASE) "base" else "tiny"
            val encName = "whisper_encoder_$typeSuffix.tflite"

            if (modelList.contains(encName)) {
                Log.i(TAG, "🚀 Strategy: Split Model ($modelType)")
                inference = WhisperSplitInference(context, modelType)
            } else {
                // Fallback / Legacy Single Model Logic (Tiny only usually)
                 val modelFile = when {
                    modelList.contains("whisper-tiny.tflite") -> "whisper-tiny.tflite"
                    modelList.contains("whisper-tiny-fp16.tflite") -> "whisper-tiny-fp16.tflite"
                    modelList.contains("whisper-tiny-en.tflite") -> "whisper-tiny-en.tflite"
                    else -> throw IllegalStateException("No Whisper model found in assets!")
                }
                Log.i(TAG, "🚀 Strategy: Single Model ($modelFile) - Type ignored")
                inference = WhisperSingleInference(context, modelFile)
            }

            inference?.initialize(options)
            Log.i(TAG, "✅ WhisperEngine Initialized")

        } catch (e: Exception) {
            Log.e(TAG, "Init Failed: ${e.message}")
            e.printStackTrace()
        }
    }
    
    fun initializeManual(options: Interpreter.Options) {
        initialize(options, ModelType.TINY)
    }

    fun startListening() {
        audioCapture.start()
    }

    fun stopListening() {
        audioCapture.stop()
    }

    fun release() {
        close()
    }

    fun close() {
        stopListening()
        audioCapture.close()
        inference?.close()
        inference = null
        engineScope.cancel()
    }

    private fun processAudio(audioData: ShortArray) {
        engineScope.launch {
            try {
                val currentInference = inference ?: return@launch
                val tokens = currentInference.transcribe(audioData)
                val text = tokenizer.decode(tokens)
                
                if (text.isNotBlank()) {
                    withContext(Dispatchers.Main) {
                        onResultListener?.invoke(text)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Processing Error: ${e.message}")
            }
        }
    }
}
