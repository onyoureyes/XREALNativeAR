package com.xreal.wear.sensor

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.OutputStream

/**
 * WearMicrophoneService — 갤럭시 워치 마이크를 통한 오디오 캡처 및 폰 전송.
 *
 * ## 용도
 * AR 안경을 착용하지 않는 상황에서 워치 마이크를 통해:
 * - Whisper STT (발화 기록)
 * - SileroVAD (음성 활동 감지)
 * - 상황 컨텍스트 수집 (주변 소리)
 *
 * ## 오디오 스펙
 * - 샘플레이트: 16kHz (Whisper 호환)
 * - 채널: MONO
 * - 형식: PCM_16BIT
 * - 청크 크기: 8000 샘플 = 0.5초 (VAD 최소 단위)
 *
 * ## 전송 방식
 * ChannelClient를 사용하여 연속 스트리밍 (MessageClient는 단발성 메시지로 오디오 부적합)
 *
 * ## 활성화 조건
 * - 폰에서 DeviceMode.AUDIO_ONLY 또는 DeviceMode.PHONE_CAM 전환 시 활성화 요청
 * - 폰에서 /xreal/mic/start 메시지 수신 시 시작
 * - 폰에서 /xreal/mic/stop 메시지 수신 시 정지
 *
 * ## 배터리 고려사항
 * - 연속 마이크 녹음: 워치 배터리 소모 증가 (~5-10%/hr 추가)
 * - 필요할 때만 활성화, 기본은 비활성
 */
class WearMicrophoneService(private val context: android.content.Context) {

    private val TAG = "WearMicService"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var channelOutputStream: OutputStream? = null
    private var channelClient: ChannelClient? = null

    // 오디오 설정
    private val SAMPLE_RATE = 16000            // 16kHz — Whisper/VAD 호환
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val CHUNK_SAMPLES = 8000           // 0.5초 청크
    private val CHUNK_BYTES = CHUNK_SAMPLES * 2 // PCM_16BIT = 2 bytes/sample

    // Wear OS 통신 경로
    companion object {
        const val PATH_MIC_START = "/xreal/mic/start"
        const val PATH_MIC_STOP  = "/xreal/mic/stop"
        const val PATH_MIC_AUDIO = "/xreal/mic/audio"  // ChannelClient path
    }

    @Volatile
    var isCapturing: Boolean = false
        private set

    /**
     * 마이크 캡처 시작 + 폰으로 스트리밍.
     * @param phoneNodeId 폰 Wear OS 노드 ID
     */
    suspend fun startCapture(phoneNodeId: String) {
        if (isCapturing) {
            Log.w(TAG, "이미 캡처 중")
            return
        }

        Log.i(TAG, "Watch 마이크 캡처 시작 → 폰 노드: $phoneNodeId")

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(CHUNK_BYTES * 2)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            ).also {
                if (it.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord 초기화 실패")
                    it.release()
                    return
                }
            }

            // ChannelClient로 폰에 스트리밍 연결
            channelClient = Wearable.getChannelClient(context)
            val channel = channelClient!!.openChannel(phoneNodeId, PATH_MIC_AUDIO).await()
            channelOutputStream = channelClient!!.getOutputStream(channel).await()

            audioRecord!!.startRecording()
            isCapturing = true

            captureJob = scope.launch {
                val buffer = ByteArray(CHUNK_BYTES)
                Log.i(TAG, "✅ 오디오 스트리밍 시작 (16kHz mono PCM, 0.5초 청크)")

                while (isActive && isCapturing) {
                    val bytesRead = audioRecord!!.read(buffer, 0, CHUNK_BYTES)
                    if (bytesRead > 0) {
                        try {
                            channelOutputStream?.write(buffer, 0, bytesRead)
                            channelOutputStream?.flush()
                        } catch (e: Exception) {
                            Log.w(TAG, "오디오 전송 실패: ${e.message} — 스트리밍 종료")
                            break
                        }
                    } else if (bytesRead < 0) {
                        Log.e(TAG, "AudioRecord 읽기 오류: $bytesRead")
                        break
                    }
                    // VAD가 처리할 시간 확보 (0.5초 청크 기준, 폴링 불필요)
                }
                stopCapture()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "마이크 권한 없음: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "캡처 시작 실패: ${e.message}")
        }
    }

    /**
     * 마이크 캡처 중지.
     */
    fun stopCapture() {
        if (!isCapturing) return
        isCapturing = false
        captureJob?.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            channelOutputStream?.close()
            channelOutputStream = null
            Log.i(TAG, "Watch 마이크 캡처 중지")
        } catch (e: Exception) {
            Log.w(TAG, "캡처 중지 오류: ${e.message}")
        }
    }

    fun release() {
        stopCapture()
        scope.cancel()
    }
}
