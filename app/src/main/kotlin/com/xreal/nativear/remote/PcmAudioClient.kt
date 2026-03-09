package com.xreal.nativear.remote

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.util.concurrent.TimeUnit

/**
 * PcmAudioClient: Connects to a raw PCM HTTP stream and plays audio through AudioTrack.
 *
 * Protocol: GET /audio → application/octet-stream (PCM 16kHz, 16-bit signed LE, mono)
 * Gracefully handles 404 (no audio available on server).
 */
class PcmAudioClient(
    private val httpClient: OkHttpClient
) {
    private val TAG = "PcmAudioClient"

    private var streamJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var isRunning = false

    /**
     * Start streaming PCM audio from the given URL.
     * @param url Full URL (e.g., "http://100.64.88.46:8554/audio")
     * @param onError Callback on stream error
     * @param scope CoroutineScope to launch the streaming job
     */
    fun start(
        url: String,
        scope: CoroutineScope,
        onError: (String) -> Unit
    ) {
        stop()
        isRunning = true

        streamJob = scope.launch(Dispatchers.IO) {
            var retryCount = 0
            val maxRetries = 3
            val retryDelayMs = 3000L

            while (isRunning && retryCount < maxRetries) {
                try {
                    Log.i(TAG, "Connecting to PCM audio stream: $url (attempt ${retryCount + 1})")

                    val streamClient = httpClient.newBuilder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build()

                    val request = Request.Builder().url(url).build()
                    val response = streamClient.newCall(request).execute()

                    if (response.code == 404) {
                        Log.i(TAG, "Audio not available on server (404). Running in video-only mode.")
                        response.close()
                        return@launch // Graceful — no audio is fine
                    }

                    if (!response.isSuccessful) {
                        Log.e(TAG, "HTTP ${response.code}: ${response.message}")
                        onError("Audio HTTP ${response.code}")
                        response.close()
                        retryCount++
                        delay(retryDelayMs)
                        continue
                    }

                    // Initialize AudioTrack for 16kHz, 16-bit, mono playback
                    val sampleRate = 16000
                    val bufferSize = AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    ).coerceAtLeast(8192)

                    val track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()

                    audioTrack = track
                    track.play()

                    Log.i(TAG, "Audio playback started (16kHz mono, buffer=${bufferSize})")
                    retryCount = 0

                    val inputStream = BufferedInputStream(
                        response.body?.byteStream() ?: throw Exception("Empty audio response body"),
                        16384
                    )

                    val readBuffer = ByteArray(4096)
                    while (isRunning) {
                        val bytesRead = inputStream.read(readBuffer)
                        if (bytesRead == -1) break
                        track.write(readBuffer, 0, bytesRead)
                    }

                    inputStream.close()
                    response.close()

                } catch (e: CancellationException) {
                    Log.i(TAG, "Audio stream cancelled")
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Audio stream error: ${e.message}")
                    if (isRunning) {
                        retryCount++
                        onError("Audio connection lost, retry $retryCount/$maxRetries")
                        delay(retryDelayMs)
                    }
                }
            }

            if (retryCount >= maxRetries) {
                Log.w(TAG, "Audio: gave up after $maxRetries retries (video continues)")
            }
        }
    }

    fun stop() {
        isRunning = false
        streamJob?.cancel()
        streamJob = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack cleanup: ${e.message}")
        }
        audioTrack = null
    }
}
