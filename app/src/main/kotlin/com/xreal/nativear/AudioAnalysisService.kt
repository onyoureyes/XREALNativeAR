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
import kotlinx.coroutines.*
import android.speech.tts.TextToSpeech
import java.util.Locale
import org.koin.android.ext.android.inject

/**
 * AudioAnalysisService: Foreground service for continuous background speech processing.
 * Using Koin for dependency injection.
 */
class AudioAnalysisService : Service() {
    private val TAG = "AudioAnalysisService"
    private val CHANNEL_ID = "AudioAnalysisChannel"
    private val NOTIFICATION_ID = 101

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Injected dependencies
    private val memoryRepo: MemoryRepository by inject()
    private val locationManager: LocationManager by inject()
    private val whisperEngine: WhisperEngine by inject()
    private val aiAgentManager: AIAgentManager by inject()
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupWhisperListener()
        startVoiceLoop()
    }

    private fun setupWhisperListener() {
        whisperEngine.setOnResultListener { text ->
            val cleanText = text.trim()
            if (cleanText.length < 2) return@setOnResultListener
            
            Log.i(TAG, "Passive STT: $cleanText")
            
            // 1. Send Broadcast for UI/Overlay
            val intent = Intent("com.xreal.nativear.TRANSCRIPT_UPDATE")
            intent.putExtra("type", "PASSIVE_STT")
            intent.putExtra("text", cleanText)
            sendBroadcast(intent)
            
            // 2. Index to Memory with Location
            serviceScope.launch {
                val loc = locationManager.currentLocation
                memoryRepo.database.insertNode(
                    UnifiedMemoryDatabase.MemoryNode(
                        timestamp = System.currentTimeMillis(),
                        role = "PASSIVE_VOICE",
                        content = cleanText,
                        latitude = loc?.latitude,
                        longitude = loc?.longitude
                    )
                )
            }
            
            // 3. Proactive Engagement (If keyword Gemini is mentioned or high sentiment?)
            if (cleanText.lowercase().contains("gemini") || cleanText.lowercase().contains("제미니")) {
                aiAgentManager.processWithGemini(cleanText)
            }
        }
    }

    private fun startVoiceLoop() {
        serviceScope.launch {
            Log.i(TAG, "Starting Background Passive Listener...")
            whisperEngine.startListening()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("XREAL AI Life-Logger")
            .setContentText("Passively recording context for your memory...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        return START_STICKY
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
        Log.i(TAG, "Shutting down AudioAnalysisService...")
        whisperEngine.stopListening()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

