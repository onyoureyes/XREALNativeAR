package com.xreal.nativear.remote

import android.graphics.Bitmap
import android.util.Log
import com.xreal.nativear.core.GlobalEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * RemoteCameraService: Manages MJPEG video + PCM audio streaming from a remote PC webcam.
 *
 * Connects to a webcam_server.py instance running on a Tailscale-connected PC,
 * delivers decoded Bitmap frames for HUD PIP rendering, and plays audio through speaker.
 *
 * Usage flow:
 * 1. Gemini calls "show_remote_camera" tool → RemoteCameraToolExecutor → startStream()
 * 2. Frames delivered via onFrameListener → OverlayView renders PIP
 * 3. Gemini calls "hide_remote_camera" → stopStream()
 */
class RemoteCameraService(
    private val httpClient: OkHttpClient,
    private val eventBus: GlobalEventBus
) {
    private val TAG = "RemoteCameraService"

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var mjpegClient: MjpegStreamClient? = null
    private var audioClient: PcmAudioClient? = null

    private var _isStreaming = false
    val isStreaming: Boolean get() = _isStreaming

    // Configurable properties
    var serverUrl: String = "http://100.64.88.46:8554"
    var pipPosition: PipPosition = PipPosition.BOTTOM_RIGHT
    var pipSizePercent: Float = 30f  // Percentage of HUD width

    // Frame callback — set by MainActivity/OutputCoordinator to push frames to OverlayView
    var onFrameListener: ((Bitmap) -> Unit)? = null
    var onErrorListener: ((String) -> Unit)? = null
    var onStreamStateListener: ((Boolean) -> Unit)? = null

    /**
     * Start streaming video + audio from the remote PC.
     * @param url Optional override for server URL (e.g., "http://100.64.88.46:8554")
     */
    fun startStream(url: String? = null) {
        if (_isStreaming) {
            Log.w(TAG, "Already streaming, stopping first...")
            stopStream()
        }

        url?.let { serverUrl = it }
        _isStreaming = true
        onStreamStateListener?.invoke(true)
        Log.i(TAG, "Starting remote camera stream from $serverUrl")

        // Start MJPEG video client
        mjpegClient = MjpegStreamClient(httpClient).also { client ->
            client.start(
                url = "$serverUrl/video",
                scope = serviceScope,
                onFrame = { bitmap ->
                    onFrameListener?.invoke(bitmap)
                },
                onError = { error ->
                    Log.w(TAG, "Video: $error")
                    onErrorListener?.invoke(error)
                }
            )
        }

        // Start PCM audio client (graceful if server has no audio)
        audioClient = PcmAudioClient(httpClient).also { client ->
            client.start(
                url = "$serverUrl/audio",
                scope = serviceScope,
                onError = { error ->
                    Log.w(TAG, "Audio: $error")
                    // Audio errors are non-fatal — video continues
                }
            )
        }
    }

    /**
     * Stop all streaming and release resources.
     */
    fun stopStream() {
        Log.i(TAG, "Stopping remote camera stream")
        _isStreaming = false

        mjpegClient?.stop()
        mjpegClient = null

        audioClient?.stop()
        audioClient = null

        onStreamStateListener?.invoke(false)
    }

    /**
     * Configure PIP display settings without restarting the stream.
     */
    fun configure(
        position: PipPosition? = null,
        sizePercent: Float? = null,
        url: String? = null
    ) {
        position?.let { pipPosition = it }
        sizePercent?.let { pipSizePercent = it.coerceIn(10f, 60f) }
        url?.let { serverUrl = it }
    }

    fun release() {
        stopStream()
    }
}

/**
 * PIP window position on the HUD.
 */
enum class PipPosition {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    CENTER
}
