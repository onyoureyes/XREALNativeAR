package com.xreal.nativear.core

import android.util.Log
import com.xreal.nativear.VoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * OutputCoordinator: Subscribes to ActionRequest events and executes them.
 * Replaces the direct callbacks from CoreEngine to VoiceManager/UI.
 */
class OutputCoordinator(
    private val context: android.content.Context,
    private val eventBus: GlobalEventBus,
    private val voiceManager: VoiceManager
) {
    private val TAG = "OutputCoordinator"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    interface OutputListener {
        fun onShowMessage(text: String)
        fun onLog(message: String)
        fun onAudioLevel(level: Float)
    }
    
    private var listener: OutputListener? = null
    
    fun setListener(listener: OutputListener) {
        this.listener = listener
    }

    init {
        subscribeToEvents()
        Log.i(TAG, "OutputCoordinator reactive for dynamic listeners")
    }

    private fun subscribeToEvents() {
        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is XRealEvent.ActionRequest.SpeakTTS -> {
                        voiceManager.speak(event.text, true)
                    }
                    is XRealEvent.ActionRequest.ShowMessage -> {
                        listener?.onShowMessage(event.text)
                    }
                    is XRealEvent.SystemEvent.DebugLog -> {
                        listener?.onLog(event.message)
                    }
                    is XRealEvent.InputEvent.AudioLevel -> {
                        listener?.onAudioLevel(event.level)
                    }
                    is XRealEvent.PerceptionEvent.SceneCaptured -> {
                        showSnapshotFeedback()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun showSnapshotFeedback() {
        Log.i(TAG, "📸 OutputCoordinator: Snapshot Haptic Feedback")
        listener?.onShowMessage("📸 Snapshot Captured")
        val vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(50)
        }
    }
}
