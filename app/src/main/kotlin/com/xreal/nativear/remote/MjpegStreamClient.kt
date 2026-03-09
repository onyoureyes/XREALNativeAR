package com.xreal.nativear.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * MjpegStreamClient: Connects to an MJPEG HTTP stream and delivers decoded Bitmap frames.
 *
 * Protocol: multipart/x-mixed-replace; boundary=frame
 * Each part: --frame\r\n Content-Type: image/jpeg\r\n Content-Length: N\r\n \r\n <JPEG bytes> \r\n
 */
class MjpegStreamClient(
    private val httpClient: OkHttpClient
) {
    private val TAG = "MjpegStreamClient"

    private var streamJob: Job? = null
    private var isRunning = false

    // Reusable decode options to reduce GC pressure
    private val decodeOptions = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inMutable = true
    }

    /**
     * Start streaming from the given MJPEG URL.
     * @param url Full URL (e.g., "http://100.64.88.46:8554/video")
     * @param onFrame Callback with each decoded Bitmap (called on IO thread)
     * @param onError Callback on stream error (called on IO thread)
     * @param scope CoroutineScope to launch the streaming job
     */
    fun start(
        url: String,
        scope: CoroutineScope,
        onFrame: (Bitmap) -> Unit,
        onError: (String) -> Unit
    ) {
        stop()
        isRunning = true

        streamJob = scope.launch(Dispatchers.IO) {
            var retryCount = 0
            val maxRetries = 5
            val retryDelayMs = 3000L

            while (isRunning && retryCount < maxRetries) {
                try {
                    Log.i(TAG, "Connecting to MJPEG stream: $url (attempt ${retryCount + 1})")

                    val streamClient = httpClient.newBuilder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build()

                    val request = Request.Builder().url(url).build()
                    val response = streamClient.newCall(request).execute()

                    if (!response.isSuccessful) {
                        Log.e(TAG, "HTTP ${response.code}: ${response.message}")
                        onError("HTTP ${response.code}")
                        retryCount++
                        delay(retryDelayMs)
                        continue
                    }

                    val inputStream = BufferedInputStream(
                        response.body?.byteStream() ?: throw Exception("Empty response body"),
                        65536 // 64KB buffer
                    )

                    Log.i(TAG, "Connected! Parsing MJPEG stream...")
                    retryCount = 0 // Reset on successful connection

                    parseMjpegStream(inputStream, onFrame)

                    // If we get here, stream ended normally
                    inputStream.close()
                    response.close()

                } catch (e: CancellationException) {
                    Log.i(TAG, "Stream cancelled")
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Stream error: ${e.message}")
                    if (isRunning) {
                        retryCount++
                        onError("Connection lost, retry $retryCount/$maxRetries")
                        delay(retryDelayMs)
                    }
                }
            }

            if (retryCount >= maxRetries) {
                onError("Max retries exceeded")
                Log.e(TAG, "Gave up after $maxRetries retries")
            }
        }
    }

    /**
     * Parse the MJPEG multipart stream.
     * Looks for JPEG SOI (FF D8) and EOI (FF D9) markers to extract frames.
     */
    private fun parseMjpegStream(
        input: BufferedInputStream,
        onFrame: (Bitmap) -> Unit
    ) {
        val jpegBuffer = ByteArrayOutputStream(65536)
        var inJpeg = false
        var prevByte = 0

        while (isRunning) {
            val b = input.read()
            if (b == -1) break // Stream ended

            if (!inJpeg) {
                // Look for JPEG SOI marker: FF D8
                if (prevByte == 0xFF && b == 0xD8) {
                    inJpeg = true
                    jpegBuffer.reset()
                    jpegBuffer.write(0xFF)
                    jpegBuffer.write(0xD8)
                }
            } else {
                jpegBuffer.write(b)

                // Look for JPEG EOI marker: FF D9
                if (prevByte == 0xFF && b == 0xD9) {
                    // Complete JPEG frame found
                    val jpegBytes = jpegBuffer.toByteArray()

                    try {
                        // Reuse bitmap if possible
                        val bitmap = BitmapFactory.decodeByteArray(
                            jpegBytes, 0, jpegBytes.size, decodeOptions
                        )
                        if (bitmap != null) {
                            onFrame(bitmap)
                            // Update inBitmap for next decode (reuse allocation)
                            decodeOptions.inBitmap = bitmap
                        }
                    } catch (e: Exception) {
                        // Bitmap reuse failed (size mismatch), clear and retry
                        decodeOptions.inBitmap = null
                    }

                    inJpeg = false
                    jpegBuffer.reset()
                }
            }

            prevByte = b
        }
    }

    fun stop() {
        isRunning = false
        streamJob?.cancel()
        streamJob = null
        decodeOptions.inBitmap = null
    }
}
