package com.xreal.nativear

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.whisper.WhisperEngine
import kotlinx.coroutines.*
import org.koin.android.ext.android.inject
import org.tensorflow.lite.Interpreter

/**
 * WhisperLifelogService: Background service for continuous audio logging.
 * Runs independently from VoiceManager's conversational AI system.
 * 
 * Architecture:
 * - Continuous audio capture via WhisperEngine
 * - On-device STT via Whisper
 * - Audio embedding extraction
 * - Emotion classification
 * - Publish AudioEmbedding events to GlobalEventBus
 */
class WhisperLifelogService : Service() {
    private val TAG = "WhisperLifelogService"
    
    private lateinit var whisperEngine: WhisperEngine
    private lateinit var emotionClassifier: EmotionClassifier
    
    private val locationService: ILocationService by inject()
    private val eventBus: GlobalEventBus by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private var lastAudioSegment: ShortArray? = null
    private var isRunning = false
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "WhisperLifelogService created")
        
        // Initialize components
        emotionClassifier = EmotionClassifier()
        
        // Initialize WhisperEngine
        whisperEngine = WhisperEngine(this)
        
        // Set up callbacks
        setupWhisperCallbacks()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "WhisperLifelogService started")
        
        if (!isRunning) {
            startLifelogging()
        }
        
        return START_STICKY // Restart if killed
    }
    
    private fun setupWhisperCallbacks() {
        // Callback for transcription results
        whisperEngine.setOnResultListener { transcript ->
            processTranscript(transcript)
        }

        // Callback for raw audio segments (needed for embedding extraction)
        whisperEngine.setOnAudioSegmentListener { audioData ->
            lastAudioSegment = audioData
        }
        
        // Callback for VAD status (optional logging)
        whisperEngine.setOnVadStatusListener { isSpeech ->
            if (isSpeech) {
                Log.d(TAG, "Speech detected")
            }
        }
    }
    
    private fun startLifelogging() {
        serviceScope.launch {
            try {
                // Initialize Whisper with GPU acceleration
                val options = Interpreter.Options().apply {
                    setNumThreads(4)
                    // GPU delegate will be added by AIModelOrchestrator if available
                }
                
                whisperEngine.initialize(options, com.xreal.whisper.ModelType.BASE)
                
                // Start continuous listening
                whisperEngine.startListening()
                isRunning = true
                
                Log.i(TAG, "Whisper lifelog started - continuous audio capture active")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start lifelog: ${e.message}", e)
                eventBus.publish(XRealEvent.SystemEvent.DebugLog("❌ Whisper Lifelog Failed: ${e.message}"))
            }
        }
    }
    
    private fun processTranscript(transcript: String) {
        if (transcript.isBlank()) return
        
        serviceScope.launch {
            try {
                val currentTime = System.currentTimeMillis()
                
                // Get last audio segment for embedding extraction
                val audioData = lastAudioSegment
                if (audioData == null) {
                    Log.w(TAG, "No audio data available for embedding")
                    return@launch
                }
                
                // Extract audio embedding
                val embedding = whisperEngine.extractEmbedding(audioData)
                if (embedding == null) {
                    Log.w(TAG, "Failed to extract embedding")
                    return@launch
                }
                
                // Classify emotion
                val (emotion, emotionScore) = emotionClassifier.classifyEmotion(embedding)
                
                // Get location
                val location = locationService.getCurrentLocation()
                
                // Convert embedding to ByteArray
                val embeddingBytes = floatArrayToByteArray(embedding)
                
                // Publish AudioEmbedding event
                eventBus.publish(XRealEvent.InputEvent.AudioEmbedding(
                    transcript = transcript,
                    audioEmbedding = embeddingBytes,
                    timestamp = currentTime,
                    latitude = location?.latitude,
                    longitude = location?.longitude
                ))
                
                Log.d(TAG, "📝 Logged: \"$transcript\" (emotion: $emotion, score: $emotionScore)")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process transcript: ${e.message}", e)
            }
        }
    }
    
    private fun floatArrayToByteArray(floats: FloatArray): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(floats.size * 4)
        buffer.order(java.nio.ByteOrder.nativeOrder())
        for (f in floats) {
            buffer.putFloat(f)
        }
        return buffer.array()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "WhisperLifelogService destroyed")
        
        isRunning = false
        whisperEngine.close()
        serviceScope.cancel()
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null // Not a bound service
    }
}
