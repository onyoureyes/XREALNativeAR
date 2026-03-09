package com.xreal.whisper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*

class AudioCaptureManager(private val context: Context) {
    private val TAG = "AudioCaptureManager"
    private var sileroVAD: SileroVAD? = null
    private val circularBuffer = AudioCircularBuffer(16000 * 30) // 30 seconds
    private var audioRecord: AudioRecord? = null
    private var isListening = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 화면 꺼짐 후에도 CPU/오디오 캡처 유지 (WakeLock) */
    private val wakeLock: PowerManager.WakeLock by lazy {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "XRealNativeAR:AudioCaptureWakeLock"
        ).apply { setReferenceCounted(false) }
    }

    private var onVadStatusChanged: ((Boolean) -> Unit)? = null
    private var onAudioSegmentReady: ((ShortArray) -> Unit)? = null

    init {
        sileroVAD = SileroVAD(context)
    }

    fun setOnVadStatusListener(listener: (Boolean) -> Unit) {
        onVadStatusChanged = listener
    }

    fun setOnAudioSegmentReadyListener(listener: (ShortArray) -> Unit) {
        onAudioSegmentReady = listener
    }

    /**
     * Read the most recent [numSamples] from the circular buffer.
     * Used by YAMNet for periodic ambient sound classification.
     */
    fun getRecentAudio(numSamples: Int): ShortArray? {
        return circularBuffer.readFromEnd(0, numSamples)
    }

    fun start() {
        if (isListening) return
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permission denied")
            return
        }
        isListening = true
        // 화면 꺼짐 후에도 오디오 캡처 & Whisper 추론 유지
        if (!wakeLock.isHeld) {
            wakeLock.acquire(12 * 60 * 60 * 1000L) // 최대 12시간 (안전 상한)
            Log.i(TAG, "WakeLock 획득 — 화면 꺼짐 후에도 오디오 캡처 유지")
        }
        scope.launch {
            captureLoop()
        }
    }

    fun stop() {
        isListening = false
        if (wakeLock.isHeld) {
            wakeLock.release()
            Log.i(TAG, "WakeLock 해제")
        }
    }

    fun close() {
        stop()
        sileroVAD?.close()
        sileroVAD = null
    }

    private suspend fun captureLoop() {
        Log.i(TAG, "Starting Audio Capture Loop...")
        val sampleRate = 16000
        val frameSize = 512 
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = (minBufferSize * 2).coerceAtLeast(frameSize * 2)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed")
                isListening = false
                return
            }

            audioRecord?.startRecording()

            // VAD State
            var isSpeechActive = false
            var speechStartSamples = 0L
            var lastSpeechTime = 0L
            val preRollSamples = (3.0 * sampleRate).toInt()
            val postRollThresholdMs = 2000L 
            val tempBuffer = ShortArray(frameSize)

            while (isListening) {
                val read = audioRecord?.read(tempBuffer, 0, frameSize) ?: 0
                if (read > 0) {
                    circularBuffer.write(tempBuffer, read)

                    val isSpeech = sileroVAD?.isSpeech(tempBuffer) ?: false  // ★ Phase W: Float→Boolean
                    val currentTime = System.currentTimeMillis()

                    if (isSpeech) { // Speech Start
                        if (!isSpeechActive) {
                            Log.i(TAG, "VAD: Speech Triggered!")
                            isSpeechActive = true
                            speechStartSamples = circularBuffer.getSize().toLong()
                            withContext(Dispatchers.Main) { onVadStatusChanged?.invoke(true) }
                        }
                        lastSpeechTime = currentTime
                    } else { // Silence or Continuing Speech
                        if (isSpeechActive && (currentTime - lastSpeechTime > postRollThresholdMs)) {
                            // Speech End
                            isSpeechActive = false
                            Log.i(TAG, "VAD: Silence Detected. Processing...")
                            withContext(Dispatchers.Main) { onVadStatusChanged?.invoke(false) }

                            val samplesSinceStart = (circularBuffer.getSize() - speechStartSamples).toInt()
                            val fullLength = samplesSinceStart + preRollSamples
                            
                            val audioToProcess = circularBuffer.readFromEnd(0, kotlin.math.min(circularBuffer.getSize(), fullLength))
                            
                            if (audioToProcess != null && audioToProcess.size > 8000) { // Min 0.5s
                                onAudioSegmentReady?.invoke(audioToProcess)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture Loop Error: ${e.message}")
        } finally {
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing AudioRecord: ${e.message}")
            }
        }
    }
}
