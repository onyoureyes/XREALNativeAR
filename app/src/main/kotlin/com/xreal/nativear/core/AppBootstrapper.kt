package com.xreal.nativear.core

import android.content.Context
import android.util.Log
import com.xreal.nativear.*
import com.xreal.ai.UnifiedAIOrchestrator
import kotlinx.coroutines.*

/**
 * AppBootstrapper: Handles the lifecycle of the application services.
 * Replaces the bootstrapping logic of CoreEngine.
 */
class AppBootstrapper(
    private val context: Context,
    private val scope: CoroutineScope,
    private val hardwareManager: HardwareManager,
    private val voiceManager: VoiceManager,
    private val visionManager: VisionManager,
    private val locationManager: LocationManager,
    private val aiOrchestrator: UnifiedAIOrchestrator,
    private val imageEmbedder: ImageEmbedder,
    private val eventBus: GlobalEventBus
) {
    private val TAG = "AppBootstrapper"

    fun start() {
        Log.i(TAG, "🚀 AppBootstrapper: Starting system services...")

        // 1. Model Initialization
        scope.launch(Dispatchers.IO) {
            if (!imageEmbedder.isModelReady()) {
                Log.i(TAG, "Downloading Visual AI Model...")
                val success = imageEmbedder.downloadModel()
                if (success) {
                    imageEmbedder.initialize()
                }
            } else {
                imageEmbedder.initialize()
            }
            
            // Warmup critical models
            aiOrchestrator.warmupModels(listOf("OCR", "SystemTTS"))
        }

        // 2. Hardware and Sensors
        hardwareManager.startHardware()
        
        // 3. Location and Context
        locationManager.startLocationUpdates {
            eventBus.publish(XRealEvent.ActionRequest.TriggerSnapshot)
        }

        // 4. Voice and ASR
        voiceManager.initPorcupine()
        voiceManager.startWakeWordDetection()

        Log.i(TAG, "✅ System services initialized")
    }

    fun release() {
        Log.i(TAG, "🛑 AppBootstrapper: Releasing services...")
        hardwareManager.stopHardware()
        voiceManager.stopListening()
        voiceManager.stopWakeWordDetection()
        // locationManager.stopLocationUpdates() // If implemented
    }
}
