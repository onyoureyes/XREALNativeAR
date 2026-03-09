package com.xreal.nativear.wear

import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.xreal.nativear.core.DeviceMode
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.monitoring.DeviceModeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.InputStream

/**
 * WearAudioReceiver — 갤럭시 워치에서 마이크 오디오를 수신하여 기존 파이프라인에 투입.
 *
 * ## 수신 흐름
 * WearMicrophoneService (워치) → ChannelClient /xreal/mic/audio → WearAudioReceiver (폰)
 *                                                                      ↓
 *                                              XRealEvent.SystemEvent.WatchAudioChunk 발행
 *                                                                      ↓
 *                                                    (향후) AudioAnalysisService 소비
 *                                                    → SileroVAD → Whisper STT → EventBus
 *
 * ## 마이크 제어 흐름
 * DeviceModeManager.AUDIO_ONLY 전환 → WearAudioReceiver.requestWatchMicStart()
 *   → MessageClient /xreal/mic/start 전송 → WearMicrophoneService.startCapture()
 *   → ChannelClient 스트리밍 시작
 *
 * ## 오디오 스펙 (WearMicrophoneService와 일치)
 * - 16kHz mono PCM_16BIT, 0.5초 청크 (8000 샘플 × 2 bytes = 16000 bytes)
 */
class WearAudioReceiver(
    private val context: android.content.Context,
    private val eventBus: GlobalEventBus,
    private val deviceModeManager: DeviceModeManager? = null
) : ChannelClient.ChannelCallback(), MessageClient.OnMessageReceivedListener {

    companion object {
        private const val TAG = "WearAudioReceiver"
        private const val PATH_MIC_START = "/xreal/mic/start"
        private const val PATH_MIC_STOP  = "/xreal/mic/stop"
        private const val PATH_MIC_AUDIO = "/xreal/mic/audio"
        private const val CHUNK_BYTES = 16000  // 0.5초 16kHz 16bit mono
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var streamJob: Job? = null
    private var watchNodeId: String? = null
    private var registered = false
    private var channelClient: ChannelClient? = null
    private var messageClient: MessageClient? = null

    @Volatile
    var isReceiving: Boolean = false
        private set

    fun start() {
        if (registered) return
        channelClient = Wearable.getChannelClient(context)
        messageClient = Wearable.getMessageClient(context)
        channelClient!!.registerChannelCallback(this)
        messageClient!!.addListener(this)
        registered = true

        // DeviceModeChanged 이벤트 구독 — AUDIO_ONLY 또는 PHONE_CAM 전환 시 워치 마이크 활성화
        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is XRealEvent.SystemEvent.DeviceModeChanged -> {
                        handleModeChange(event.newMode)
                    }
                    else -> {}
                }
            }
        }

        Log.i(TAG, "WearAudioReceiver started — 워치 채널 + 마이크 제어 대기 중")
    }

    fun stop() {
        if (!registered) return
        stopStreaming()
        try {
            channelClient?.unregisterChannelCallback(this)
            messageClient?.removeListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "수신자 해제 오류: ${e.message}")
        }
        registered = false
        Log.i(TAG, "WearAudioReceiver stopped")
    }

    // =========================================================================
    // DeviceMode 기반 워치 마이크 자동 제어
    // =========================================================================

    private fun handleModeChange(newMode: DeviceMode) {
        when (newMode) {
            DeviceMode.AUDIO_ONLY -> {
                // 카메라 없음 → 워치 마이크로 오디오 수집
                scope.launch { requestWatchMicStart() }
                Log.i(TAG, "AUDIO_ONLY 모드 → 워치 마이크 활성화 요청")
            }
            DeviceMode.PHONE_CAM -> {
                // 폰 카메라 + 이어폰 모드 — 워치 마이크 보조 사용 (선택적)
                Log.d(TAG, "PHONE_CAM 모드 — 워치 마이크 유지 (사용 중이면)")
            }
            DeviceMode.FULL_AR, DeviceMode.HUD_ONLY -> {
                // 폰 마이크 우선 → 워치 마이크 불필요
                scope.launch { requestWatchMicStop() }
                Log.d(TAG, "${newMode} 모드 → 워치 마이크 비활성화")
            }
        }
    }

    // =========================================================================
    // 워치 마이크 제어 메시지
    // =========================================================================

    /**
     * 워치에 마이크 시작 요청 전송.
     */
    suspend fun requestWatchMicStart() {
        val nodeId = getWatchNodeId() ?: run {
            Log.w(TAG, "워치 노드 없음 — 마이크 시작 불가")
            return
        }
        try {
            messageClient!!.sendMessage(nodeId, PATH_MIC_START, ByteArray(0)).await()
            Log.i(TAG, "워치 마이크 시작 요청 전송 → $nodeId")
        } catch (e: Exception) {
            Log.e(TAG, "마이크 시작 요청 실패: ${e.message}")
        }
    }

    /**
     * 워치에 마이크 중지 요청 전송.
     */
    suspend fun requestWatchMicStop() {
        val nodeId = watchNodeId ?: return
        try {
            messageClient!!.sendMessage(nodeId, PATH_MIC_STOP, ByteArray(0)).await()
            Log.i(TAG, "워치 마이크 중지 요청 전송")
        } catch (e: Exception) {
            Log.w(TAG, "마이크 중지 요청 실패: ${e.message}")
        }
    }

    // =========================================================================
    // ChannelClient.ChannelCallback — 오디오 스트리밍 수신
    // =========================================================================

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != PATH_MIC_AUDIO) return

        Log.i(TAG, "워치 오디오 채널 열림: ${channel.nodeId}")
        watchNodeId = channel.nodeId
        isReceiving = true

        streamJob = scope.launch {
            try {
                val inputStream: InputStream = channelClient!!.getInputStream(channel).await()
                val buffer = ByteArray(CHUNK_BYTES)
                Log.i(TAG, "✅ 워치 오디오 스트리밍 시작 (16kHz mono PCM, 0.5초 청크)")

                while (isActive && isReceiving) {
                    // 정확히 CHUNK_BYTES만큼 읽기 (블로킹 — IO 디스패처 사용)
                    var bytesRead = 0
                    while (bytesRead < CHUNK_BYTES) {
                        val n = inputStream.read(buffer, bytesRead, CHUNK_BYTES - bytesRead)
                        if (n < 0) {
                            isReceiving = false
                            break
                        }
                        bytesRead += n
                    }
                    if (!isReceiving) break

                    // XRealEvent로 발행 → AudioAnalysisService가 SileroVAD에 투입
                    eventBus.publish(XRealEvent.SystemEvent.WatchAudioChunk(
                        pcmData = buffer.copyOf(bytesRead),
                        timestamp = System.currentTimeMillis()
                    ))
                }
                Log.i(TAG, "워치 오디오 스트리밍 종료")
            } catch (e: Exception) {
                Log.e(TAG, "오디오 스트리밍 오류: ${e.message}")
            } finally {
                isReceiving = false
            }
        }
    }

    override fun onChannelClosed(channel: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
        if (channel.path != PATH_MIC_AUDIO) return
        Log.i(TAG, "워치 오디오 채널 닫힘 (이유: $closeReason)")
        stopStreaming()
    }

    // =========================================================================
    // MessageClient.OnMessageReceivedListener — 워치에서의 메시지 (선택적)
    // =========================================================================

    override fun onMessageReceived(event: MessageEvent) {
        // 워치 → 폰 방향 메시지는 WearDataReceiver가 처리
        // 여기서는 /xreal/mic/* 경로만 처리 (향후 확장용)
        if (!event.path.startsWith("/xreal/mic/")) return
        Log.d(TAG, "워치 마이크 메시지: ${event.path}")
    }

    // =========================================================================
    // 내부 유틸리티
    // =========================================================================

    private fun stopStreaming() {
        isReceiving = false
        streamJob?.cancel()
        streamJob = null
    }

    private suspend fun getWatchNodeId(): String? {
        if (watchNodeId != null) return watchNodeId
        return try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
            watchNodeId = node?.id
            node?.id
        } catch (e: Exception) {
            Log.w(TAG, "연결된 워치 노드 조회 실패: ${e.message}")
            null
        }
    }
}
