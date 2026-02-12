package com.xreal.nativear

import android.content.Context
import android.util.Log
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.whisper.WhisperEngine
import java.io.File

/**
 * VoiceManager: Handles voice input, keyword detection (Porcupine), 
 * and ASR (Whisper) for the AR companion.
 * NOW DECOUPLED via GlobalEventBus.
 */
class VoiceManager(
    private val context: Context,
    private val ttsAdapter: SystemTTSAdapter,
    private val eventBus: GlobalEventBus
) : IVoiceService {

    private val TAG = "VoiceManager"
    private var porcupineManager: ai.picovoice.porcupine.PorcupineManager? = null
    private var speechRecognizer: android.speech.SpeechRecognizer? = null
    private val ACCESS_KEY = "GmdWaVcq/Ut1VhjmSbongouOmbErpDnK0bk8ZzWzqm0e+IrxOlsDqQ=="
    
    // Removed VoiceCallback support
    // private val callback: VoiceCallback

    init {
        initializeWhisper()
        initSpeechRecognizer()
    }

    private fun initializeWhisper() {
        Log.i(TAG, "Initializing Whisper Engine...")
        try {
            whisperEngine = WhisperEngine(context)
            // whisperEngine?.initialize(org.tensorflow.lite.Interpreter.Options())
            eventBus.publish(XRealEvent.SystemEvent.DebugLog("✅ Whisper Engine Ready"))
        } catch (e: Exception) {
            Log.e(TAG, "❌ Whisper Init Failed: ${e.message}")
            eventBus.publish(XRealEvent.SystemEvent.DebugLog("❌ Whisper Init Failed"))
        }
    }

    private fun initSpeechRecognizer() {
        speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(context)
    }

    fun initPorcupine() {
        Log.i(TAG, "Initializing Porcupine Wake Word...")
        try {
            porcupineManager = ai.picovoice.porcupine.PorcupineManager.Builder()
                .setAccessKey(ACCESS_KEY)
                .setKeywords(arrayOf(ai.picovoice.porcupine.Porcupine.BuiltInKeyword.BUMBLEBEE))
                .build(context) { keywordIndex ->
                    Log.i(TAG, "Bumblebee Detected!")
                    startListening()
                }
            eventBus.publish(XRealEvent.SystemEvent.DebugLog("✅ Porcupine Initialized"))
        } catch (e: Exception) {
            Log.e(TAG, "Porcupine Error: ${e.message}")
        }
    }

    fun startListening() {
        if (isConversing) return
        isConversing = true
        porcupineManager?.stop()
        
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        }
        
        speechRecognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onResults(results: android.os.Bundle?) {
                val data = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                if (!data.isNullOrEmpty()) {
                    eventBus.publish(XRealEvent.InputEvent.VoiceCommand(data[0]))
                }
                isConversing = false
                startPorcupine()
            }
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { 
                eventBus.publish(XRealEvent.InputEvent.AudioLevel((rmsdB + 2f) / 12f)) 
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { 
                isConversing = false
                startPorcupine() 
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        isConversing = false
    }

    override fun speak(text: String, isResponse: Boolean) {
        if (isResponse) {
            isConversing = true
            eventBus.publish(XRealEvent.SystemEvent.VoiceActivity(true))
        }
        
        ttsAdapter.setProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (isResponse) {
                    isConversing = false
                    eventBus.publish(XRealEvent.SystemEvent.VoiceActivity(false))
                    // Automatically restart listening for conversational flow
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        startListening()
                    }
                }
            }
            override fun onError(utteranceId: String?) {
                if (isResponse) {
                    isConversing = false
                    startWakeWordDetection() 
                }
            }
        })
        
        ttsAdapter.speak(text, "CONVERSATION_REPLY")
    }

    override fun startWakeWordDetection() {
        try {
            porcupineManager?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Porcupine: ${e.message}")
        }
    }

    override fun stopWakeWordDetection() {
        try {
            porcupineManager?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop Porcupine: ${e.message}")
        }
    }

    override fun setConversing(conversing: Boolean) {
        isConversing = conversing
    }
}

