package com.xreal.nativear.core

import android.util.Log
import com.xreal.nativear.DrawCommand
import com.xreal.nativear.VoiceManager
import com.xreal.nativear.hand.GestureEvent
import com.xreal.nativear.hand.HandData
import com.xreal.nativear.spatial.AnchorLabel2D
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

    companion object {
        /**
         * 조용히 모드 — true이면 important=false인 SpeakTTS 무음 처리.
         * 음성 명령 "조용히" → true, "다 말해" → false.
         */
        @Volatile
        var quietMode: Boolean = false
    }

    interface OutputListener {
        fun onShowMessage(text: String)
        fun onLog(message: String)
        fun onAudioLevel(level: Float)
        fun onDrawingCommand(command: DrawCommand)
        fun onAnchorLabels(labels: List<AnchorLabel2D>) {}
        fun onHandsDetected(hands: List<HandData>, gestures: List<GestureEvent>) {}
        fun onRemoteCameraToggle(show: Boolean, source: String?) {}
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
                        // quietMode=true이면 important=false 메시지 무음 처리
                        if (event.important || !quietMode) {
                            voiceManager.speak(event.text, true)
                        } else {
                            Log.d(TAG, "[조용히 모드] TTS 무음: ${event.text.take(40)}")
                        }
                    }
                    // 음성 명령으로 조용히 모드 토글
                    is XRealEvent.InputEvent.VoiceCommand -> {
                        val lower = event.text.lowercase()
                        when {
                            lower.contains("조용히") || lower.contains("음소거") || lower.contains("quiet mode") -> {
                                quietMode = true
                                Log.i(TAG, "조용히 모드 ON — important=false TTS 무음")
                                scope.launch {
                                    eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(
                                        "조용히 모드 활성화. 중요 메시지만 음성 출력합니다.", important = true
                                    ))
                                }
                            }
                            lower.contains("다 말해") || lower.contains("소리 켜") || lower.contains("unmute") -> {
                                quietMode = false
                                Log.i(TAG, "조용히 모드 OFF — 모든 TTS 출력")
                                scope.launch {
                                    eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(
                                        "음소거 해제. 모든 알림을 음성으로 출력합니다.", important = true
                                    ))
                                }
                            }
                        }
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
                    is XRealEvent.ActionRequest.DrawingCommand -> {
                        listener?.onDrawingCommand(event.command)
                    }
                    is XRealEvent.ActionRequest.AnchorLabelsUpdate -> {
                        listener?.onAnchorLabels(event.visibleLabels)
                    }
                    is XRealEvent.PerceptionEvent.HandsDetected -> {
                        listener?.onHandsDetected(event.hands, emptyList())
                    }
                    is XRealEvent.PerceptionEvent.HandGestureDetected -> {
                        listener?.onHandsDetected(emptyList(), event.gestures)
                    }
                    is XRealEvent.ActionRequest.ShowRemoteCamera -> {
                        listener?.onRemoteCameraToggle(event.show, event.source)
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
        val vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
    }
}
