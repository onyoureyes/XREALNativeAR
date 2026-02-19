package com.xreal.nativear.core

import android.graphics.Bitmap
import com.xreal.nativear.Detection
import com.xreal.nativear.OverlayView
import com.xreal.nativear.PoseEstimationModel

/**
 * XRealEvent: The base sealed class for all events in the distributed system.
 */
sealed class XRealEvent {

    /**
     * InputEvent: Raw user inputs (Voice, Gestures, Buttons)
     */
    sealed class InputEvent : XRealEvent() {
        data class VoiceCommand(val text: String) : InputEvent()
        data class Gesture(val type: GestureType) : InputEvent()
        data class Touch(val x: Float, val y: Float) : InputEvent()
        data class AudioLevel(val level: Float) : InputEvent()
        
        // Audio Embedding Event for Whisper-based lifelog
        data class AudioEmbedding(
            val transcript: String,
            val audioEmbedding: ByteArray,
            val timestamp: Long,
            val latitude: Double?,
            val longitude: Double?
        ) : InputEvent()

        data class EnrichedVoiceCommand(
            val text: String,
            val speaker: String,
            val emotion: String,
            val emotionScore: Float = 1.0f
        ) : InputEvent()
    }

    /**
     * PerceptionEvent: Signals from sensors and AI models (Vision, Location)
     */
    sealed class PerceptionEvent : XRealEvent() {
        data class ObjectsDetected(val results: List<Detection>) : PerceptionEvent()
        data class OcrDetected(val results: List<OverlayView.OcrResult>, val width: Int, val height: Int) : PerceptionEvent()
        data class SceneCaptured(val bitmap: Bitmap, val ocrText: String) : PerceptionEvent()
        data class LocationUpdated(val lat: Double, val lon: Double, val address: String?) : PerceptionEvent()
        data class HeadPoseUpdated(val qx: Float, val qy: Float, val qz: Float, val qw: Float) : PerceptionEvent()
        data class FpsUpdated(val fps: Double) : PerceptionEvent()
        data class PoseDetected(val keypoints: List<PoseEstimationModel.Keypoint>) : PerceptionEvent()
        
        // Visual Embedding Event for Image-based lifelog
        data class VisualEmbedding(
            val bitmap: Bitmap,
            val embedding: ByteArray,
            val label: String,
            val timestamp: Long,
            val latitude: Double?,
            val longitude: Double?
        ) : PerceptionEvent()
    }

    /**
     * SystemEvent: Hardware and System status updates
     */
    sealed class SystemEvent : XRealEvent() {
        data class BatteryLevel(val percent: Int) : SystemEvent()
        data class ThermalState(val isOverheated: Boolean) : SystemEvent()
        data class NetworkStatus(val isConnected: Boolean) : SystemEvent()
        data class DebugLog(val message: String) : SystemEvent()
        data class VoiceActivity(val isSpeaking: Boolean) : SystemEvent()
        data class VisionStateChanged(val isFrozen: Boolean) : SystemEvent()
        data class Error(val code: String, val message: String, val throwable: Throwable? = null) : SystemEvent()
    }

    /**
     * ActionRequest: Requests for the system to DO something (Output)
     */
    sealed class ActionRequest : XRealEvent() {
        data class SpeakTTS(val text: String) : ActionRequest()
        data class ShowMessage(val text: String) : ActionRequest()
        data class UpdateOverlay(val blocks: List<OverlayView.OcrResult>) : ActionRequest()
        data class SaveMemory(val content: String, val role: String, val metadata: String? = null) : ActionRequest()
        object TriggerSnapshot : ActionRequest()
    }
}

enum class GestureType {
    TAP, NOD, SHAKE, DOUBLE_TAP, TRIPLE_TAP, QUAD_TAP
}
