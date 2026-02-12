package com.xreal.nativear

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import com.xreal.ai.UnifiedAIOrchestrator
import com.xreal.nativear.core.GestureHandler
import com.xreal.nativear.core.VisionServiceDelegate
import com.xreal.nativear.nrsdk.XRealPose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.component.inject

class CoreEngine(
    private val context: Context,
    private val scope: LifecycleCoroutineScope,
    private val listener: CoreListener
) : VoiceManager.VoiceCallback,
    VisionManager.VisionCallback,
    HardwareManager.HardwareCallback,
    AIAgentManager.AIAgentCallback,
    org.koin.core.component.KoinComponent {

    private val TAG = "CoreEngine"
    
    // Managers injected via Koin
    private val hardwareManager: HardwareManager by inject { org.koin.core.parameter.parametersOf(context, scope, this) }
    private val voiceManager: VoiceManager by inject { org.koin.core.parameter.parametersOf(context, this) }
    private val visionManager: VisionManager by inject { org.koin.core.parameter.parametersOf(context, java.util.concurrent.Executors.newSingleThreadExecutor(), this) }
    private val aiAgentManager: AIAgentManager by inject { org.koin.core.parameter.parametersOf(context, scope, this) }
    private val locationManager: LocationManager by inject()


    private val aiOrchestrator: com.xreal.ai.UnifiedAIOrchestrator by inject()
    private val imageEmbedder: ImageEmbedder by inject()
    private val gestureHandler: GestureHandler by inject()
    private val gestureManager = GestureManager(gestureHandler)
    
    interface CoreListener {

        fun onLog(message: String)
        fun onStatusUpdate(status: String)
        fun onAudioLevel(level: Float)
        fun onCentralMessage(text: String)
        fun onStabilityProgress(progress: Int)
        fun onGeminiResponse(reply: String)
        fun onDetections(results: List<Detection>)
        fun onQueryTriggered()
        fun onGetLatestBitmap(): Bitmap?
        fun onIsFrozen(): Boolean
    }



    init {
        Log.i(TAG, "Booting CoreEngine via Koin...")
        
        scope.launch(Dispatchers.IO) {
            if (!imageEmbedder.isModelReady()) {
                listener.onStatusUpdate("Downloading Visual AI Model...")
                val success = imageEmbedder.downloadModel()
                if (success) {
                    imageEmbedder.initialize()
                    listener.onStatusUpdate("Visual AI Ready")
                } else {
                    listener.onStatusUpdate("Visual AI Model Failed")
                }
            } else {
                imageEmbedder.initialize()
            }
        }
        
        gestureHandler.onUserAction = { action -> executeUserAction(action) }
    }



    fun onTouchTap() {
        gestureManager.onTap()
    }

    fun start() {

        hardwareManager.startHardware()
        voiceManager.initPorcupine()
        voiceManager.startWakeWordDetection()

        
        locationManager.startLocationUpdates { 
            onStabilityTriggered()
        }
        
        // Warmup critical models
        scope.launch {
            aiOrchestrator.warmupModels(listOf("OCR", "SystemTTS"))
        }
    }

    fun release() {
        Log.i(TAG, "Releasing CoreEngine...")
        hardwareManager.stopHardware() // Need to add this to HardwareManager
        voiceManager.stopListening()
        voiceManager.stopWakeWordDetection()

        // Other cleanup as needed
    }


    fun executeUserAction(action: UserAction) {
        listener.onLog("Action Triggered: $action")
        when (action) {
            UserAction.CYCLE_CAMERA -> visionManager.cycleCamera()
            UserAction.DAILY_SUMMARY -> aiAgentManager.processWithGemini("Give me a daily summary")
            UserAction.SYNC_MEMORY -> aiAgentManager.processWithGemini("Sync my memories to cloud")
            UserAction.OPEN_MEM_QUERY -> listener.onQueryTriggered()
            UserAction.CONFIRM_AI_ACTION -> {
                listener.onCentralMessage("Nod: Confirm Proceed")
                aiAgentManager.processWithGemini("Yes, please continue with that action.")
            }
            UserAction.CANCEL_AI_ACTION -> {
                listener.onCentralMessage("Shake: Cancel Action")
                aiAgentManager.processWithGemini("No, stop that.")
            }
        }
    }



    // --- Callbacks ---

    override fun onUserText(text: String) {
        listener.onCentralMessage("You: $text")
        aiAgentManager.processWithGemini(text)
    }

    override fun onLog(message: String) = listener.onLog(message)
    override fun onStatusUpdate(status: String) = listener.onStatusUpdate(status)
    override fun onAudioLevel(level: Float) = listener.onAudioLevel(level)
    override fun onPartialResult(partial: String) { Log.d(TAG, "Partial: $partial") }
    override fun onResponseStarted() { Log.i(TAG, "AI Response Started") }
    override fun onResponseFinished() { Log.i(TAG, "AI Response Finished") }

    override fun onOcrResults(results: List<OverlayView.OcrResult>, width: Int, height: Int) {
        listener.onStatusUpdate("👁️ OCR: ${results.size} blocks")
    }

    override fun onDetections(results: List<Detection>) {
        listener.onDetections(results)
        aiAgentManager.processDetections(results)
    }





    override fun onSnapshotReady(bitmap: Bitmap?, ocrText: String) {
        // CoreEngine orchestrates the actual bitmap capture if bitmap is null
        val finalBitmap = bitmap ?: listener.onGetLatestBitmap()
        
        if (finalBitmap == null) {
            listener.onLog("Snapshot Triggered. Bitmap retrieval failed.")
        } else {
            aiAgentManager.interpretScene(finalBitmap, ocrText)
        }
    }

    override fun isConversing() = voiceManager.isConversing
    override fun isFrozen() = listener.onIsFrozen()
    override fun onUpdateFps(fps: Double) { Log.d(TAG, "FPS: $fps") }

    override fun onCameraCountChanged(count: Int) { Log.i(TAG, "Camera Count: $count") }
    override fun onStepDetected(progress: Int) { Log.d(TAG, "Steps Progress: $progress") }
    override fun onStabilityProgress(progress: Int) { listener.onStabilityProgress(progress) }
    override fun onStabilityTriggered() {
        visionManager.captureSceneSnapshot()
    }

    override fun onHeadPoseUpdate(headPose: com.xreal.nativear.nrsdk.XRealPose) {
        gestureManager.processPose(headPose)
    }
    override fun onNativeActivated(fd: Int) { Log.i(TAG, "Native HID Activated: $fd") }
    override fun onOrientationUpdate(qx: Float, qy: Float, qz: Float, qw: Float) {
        gestureManager.processPose(com.xreal.nativear.nrsdk.XRealPose(qx = qx, qy = qy, qz = qz, qw = qw))
    }

    override fun onPdrUpdate(dx: Float, dy: Float) {
        locationManager.updatePdr(dx, dy)
    }



    override fun onSpeak(text: String, isResponse: Boolean) = voiceManager.speak(text, isResponse)

    override fun onCentralMessage(text: String) = listener.onCentralMessage(text)
    override fun onGeminiResponse(reply: String) = listener.onGeminiResponse(reply)
    override fun onSearchResults(resultsJson: String) {
        Log.i(TAG, "Search Results Received: ${resultsJson.length} bytes")
        // Potential: Show a small indicator in AR HUD
    }


    override fun showSnapshotFeedback() {
        Log.i(TAG, "📸 Triggering Snapshot Feedback (Haptic)")
        listener.onCentralMessage("📸 Snapshot Captured")
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(50)
        }
    }

    override fun startWakeWordDetection() = voiceManager.startWakeWordDetection()

    
    override fun setConversing(isConversing: Boolean) = voiceManager.setConversing(isConversing)

    override fun onGetLatestBitmap(): Bitmap? {
        return listener.onGetLatestBitmap()
    }
}


