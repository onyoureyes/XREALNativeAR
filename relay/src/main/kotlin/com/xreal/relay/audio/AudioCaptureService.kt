package com.xreal.relay.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import com.xreal.relay.server.RelayHttpServer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 마이크 → raw PCM 16kHz mono 16-bit → RelayHttpServer 브로드캐스트.
 *
 * Fold 4의 PcmAudioClient와 호환:
 *   Content-Type: application/octet-stream
 *   16000Hz, mono, signed 16-bit LE
 */
class AudioCaptureService(
    private val context: Context,
    private val httpServer: RelayHttpServer
) {
    private val TAG = "AudioCaptureService"

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val running = AtomicBoolean(false)

    // 통계
    var totalBytesCaptures = 0L
        private set

    fun start() {
        if (running.getAndSet(true)) return

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO 권한 없음")
            running.set(false)
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "AudioRecord 버퍼 크기 오류: $bufferSize")
            running.set(false)
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 초기화 실패")
                running.set(false)
                return
            }

            audioRecord?.startRecording()

            captureThread = Thread({
                val buffer = ByteArray(bufferSize)
                Log.i(TAG, "오디오 캡처 시작 (${SAMPLE_RATE}Hz, mono, 16bit)")

                while (running.get()) {
                    try {
                        val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                        if (bytesRead > 0 && httpServer.audioClients.isNotEmpty()) {
                            val chunk = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
                            httpServer.broadcastAudioChunk(chunk)
                            totalBytesCaptures += bytesRead
                        }
                    } catch (e: Exception) {
                        if (running.get()) {
                            Log.w(TAG, "오디오 읽기 오류: ${e.message}")
                        }
                    }
                }

                Log.i(TAG, "오디오 캡처 종료 (총 ${totalBytesCaptures / 1024}KB)")
            }, "AudioCapture")
            captureThread?.start()

        } catch (e: Exception) {
            Log.e(TAG, "오디오 캡처 시작 실패: ${e.message}", e)
            running.set(false)
        }
    }

    fun stop() {
        running.set(false)
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord 해제 오류: ${e.message}")
        }
        audioRecord = null
        captureThread?.join(2000)
        captureThread = null
    }

    val isRunning: Boolean get() = running.get()
}
