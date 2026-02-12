package com.xreal.nativear

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import android.speech.tts.TextToSpeech
import java.util.Locale

class AudioAnalysisService : Service(), TextToSpeech.OnInitListener {
    private val TAG = "AudioAnalysisService"
    private val CHANNEL_ID = "AudioAnalysisChannel"
    private val NOTIFICATION_ID = 101

    private var job: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + (SupervisorJob() as kotlin.coroutines.CoroutineContext))

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var memoryDatabase: UnifiedMemoryDatabase
    private lateinit var whisperEngine: WhisperEngine
    private lateinit var geminiClient: GeminiClient
    private lateinit var tts: TextToSpeech
    
    private var currentLat: Double? = null
    private var currentLon: Double? = null
    
    private val GEMINI_API_KEY = "AIzaSyAQbpllbZGGrBNjgbt8T96ZqGCeEsvn3cU" // Shared Key

    override fun onCreate() {
        super.onCreate()
        memoryDatabase = UnifiedMemoryDatabase(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        whisperEngine = WhisperEngine(this)
        geminiClient = GeminiClient(GEMINI_API_KEY)
        tts = TextToSpeech(this, this)
        
        createNotificationChannel()
        startLocationUpdates()
        // Wait for TTS init to start listening
    }

    private fun setupVoiceLoop() {
        whisperEngine.setOnResultListener { text ->
            // Filter short noise, engine errors, and common Gemini fallback responses to prevent loops
            val lower = text.lowercase()
            if (text.length < 2 || 
                text.startsWith("Error:") || 
                text.contains("Internal error") ||
                text == "Engine Not Ready" || 
                lower.contains("오류네요") || 
                lower.contains("죄송해요")
            ) return@setOnResultListener
            
            Log.i(TAG, "User Said: $text")
            
            // Broadcast User Speech
            val intent = Intent("com.xreal.nativear.TRANSCRIPT_UPDATE")
            intent.putExtra("type", "USER")
            intent.putExtra("text", "Hearing: $text")
            sendBroadcast(intent)
            
            // 1. Log to Memory
            memoryDatabase.insertNode(
                UnifiedMemoryDatabase.MemoryNode(
                    timestamp = System.currentTimeMillis(),
                    role = "USER_VOICE",
                    content = text,
                    latitude = currentLat,
                    longitude = currentLon
                )
            )

            // 2. Ask Gemini (Background)
            serviceScope.launch {
                val prompt = """
                    사용자가 방금 이렇게 말했습니다: "$text"
                    
                    당신은 사용자의 스마트 안경(AI 비서)입니다.
                    - 짧고 친근하게(한국어 반말/존댓말 맥락에 맞춰) 대답하세요.
                    - 20자 이내로 핵심만 말하세요.
                """.trimIndent()
                
                val (response, error) = geminiClient.generateText(prompt)
                val reply = response?.text()
                
                if (!reply.isNullOrEmpty()) {
                    Log.i(TAG, "Gemini Reply: $reply")
                    
                    // Broadcast Gemini Speech
                    val intent = Intent("com.xreal.nativear.TRANSCRIPT_UPDATE")
                    intent.putExtra("type", "GEMINI")
                    intent.putExtra("text", "Gemini: $reply")
                    sendBroadcast(intent)

                    speakOut(reply)
                    
                    // Log Reply
                    memoryDatabase.insertNode(
                        UnifiedMemoryDatabase.MemoryNode(
                            timestamp = System.currentTimeMillis(),
                            role = "ASSISTANT_VOICE",
                            content = reply
                        )
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("XREAL Whisper AI")
            .setContentText("Listening for speech...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(java.util.Locale.KOREAN)
            // Start engine immediately since assets are bundled
            setupVoiceLoop()
            whisperEngine.startListening()
            whisperEngine.logTensorShapes() // DEBUG: Force log on startup
            speakOut("음성 대화가 준비되었습니다.")
        } else {
            Log.e(TAG, "TTS Init Failed")
        }
    }


    private fun speakOut(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "")
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(5000)
            .setMaxUpdateDelayMillis(15000)
            .build()

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val location = result.lastLocation
                    currentLat = location?.latitude
                    currentLon = location?.longitude
                }
            }, mainLooper)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Audio Analysis Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        whisperEngine.close()
        job?.cancel()
        serviceScope.cancel()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
