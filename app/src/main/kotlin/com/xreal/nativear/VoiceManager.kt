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

    private val wavLM: WavLMAdapter by org.koin.core.component.KoinComponent.inject()
    private val emotionClassifier: EmotionClassifier by org.koin.core.component.KoinComponent.inject()
    private var isConversing = false
    private var audioBuffer = mutableListOf<Short>()
    private var captureJob: kotlinx.coroutines.Job? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    init {
        initializeWhisper()
        initSpeechRecognizer()
    }

    private fun initializeWhisper() {
        Log.i(TAG, "Initializing Whisper Engine...")
        try {
            // whisperEngine = WhisperEngine(context) // Already registered in AIModelWarehouse
            eventBus.publish(XRealEvent.SystemEvent.DebugLog("✅ Voice Manager Ready (Whisper + WavLM)"))
        } catch (e: Exception) {
            Log.e(TAG, "❌ Init Failed: ${e.message}")
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
        
        // Parallel Audio Capture for WavLM
        audioBuffer.clear()
        startParallelCapture()

        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        }
        
        speechRecognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onResults(results: android.os.Bundle?) {
                stopParallelCapture()
                val data = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                if (!data.isNullOrEmpty()) {
                    val promptText = data[0]
                    
                    // Perform WavLM Analysis on the captured buffer
                    scope.launch {
                        val finalBuffer = audioBuffer.toShortArray()
                        val embedding = wavLM.extractEmbedding(finalBuffer)
                        val emotionResult = embedding?.let { emotionClassifier.classifyEmotion(it) }
                        
                        val speaker = "USER" // For now, we can add Speaker ID logic here later
                        val emotion = emotionResult?.emotion ?: "neutral"
                        val score = emotionResult?.confidence ?: 1.0f
                        
                        Log.i(TAG, "Enriched Command: $promptText | Emotion: $emotion ($score)")
                        
                        eventBus.publish(XRealEvent.InputEvent.EnrichedVoiceCommand(
                            text = promptText,
                            speaker = speaker,
                            emotion = emotion,
                            emotionScore = score
                        ))
                    }
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
                stopParallelCapture()
                isConversing = false
                startPorcupine() 
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    private fun startParallelCapture() {
        captureJob = scope.launch {
            val sampleRate = 16000
            val bufferSize = android.media.AudioRecord.getMinBufferSize(
                sampleRate, 
                android.media.AudioFormat.CHANNEL_IN_MONO, 
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            
            val audioRecord = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate, 
                android.media.AudioFormat.CHANNEL_IN_MONO, 
                android.media.AudioFormat.ENCODING_PCM_16BIT, 
                bufferSize
            )

            if (audioRecord.state == android.media.AudioRecord.STATE_INITIALIZED) {
                audioRecord.startRecording()
                val tempBuffer = ShortArray(1024)
                while (isActive) {
                    val read = audioRecord.read(tempBuffer, 0, tempBuffer.size)
                    if (read > 0) {
                        synchronized(audioBuffer) {
                            for (i in 0 until read) audioBuffer.add(tempBuffer[i])
                        }
                    }
                }
                audioRecord.stop()
                audioRecord.release()
            }
        }
    }

    private fun stopParallelCapture() {
        captureJob?.cancel()
        captureJob = null
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

