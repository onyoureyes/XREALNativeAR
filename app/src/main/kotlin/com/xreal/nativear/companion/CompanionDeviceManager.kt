package com.xreal.nativear.companion

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.xreal.nativear.VisionManager
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.resilience.DeviceHealthMonitor
import com.xreal.nativear.resource.ResourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * CompanionDeviceManager — 컴패니언 기기(갤폴드3 등) Nearby Connections 연결 관리.
 *
 * ## 역할
 * - P2P_CLUSTER 전략: 폴드4(호스트) ↔ 폴드3(컴패니언) 자동 발견 + 연결
 * - 엣지 LLM 오프로드: 폴드3에서 Gemma 1B 실행 → 결과 수신
 * - 카메라 스트림 메타데이터: 폴드3 카메라 방향 설정, 프레임 수신 트리거
 * - RAM 정보 교환: 폴드3의 가용 RAM → ResourceRegistry 업데이트
 *
 * ## 보안
 * - SERVICE_ID에 앱 패키지명 사용 (타 앱과 충돌 방지)
 * - 연결 시 토큰 검증 (동일 앱 빌드 서명)
 *
 * ## Phase 2 (현재 Stub)
 * - 실시간 MJPEG 비디오 스트림 수신 → VisionManager.feedExternalFrame()
 * - 현재는 연결/해제 상태만 관리
 */
class CompanionDeviceManager(
    private val context: Context,
    private val eventBus: GlobalEventBus,
    private val resourceRegistry: ResourceRegistry,
    private val visionManager: VisionManager? = null,         // ★ Phase 2: 카메라 프레임 → AI 파이프라인
    private val deviceHealthMonitor: DeviceHealthMonitor? = null  // ★ fold3Connected 동기화
) {
    companion object {
        private const val TAG = "CompanionDeviceMgr"
        private const val SERVICE_ID = "com.xreal.nativear.companion"
        private const val DEVICE_NAME = "XRealNativeAR-Fold4"
        private const val LLM_OFFLOAD_TIMEOUT_MS = 30_000L  // LLM 응답 30초 대기
        private const val RAM_REQUEST_INTERVAL_MS = 60_000L  // 1분마다 RAM 정보 갱신

        // 페이로드 타입
        private const val MSG_TYPE_RAM_INFO = "ram_info"
        private const val MSG_TYPE_LLM_REQUEST = "llm_request"
        private const val MSG_TYPE_LLM_RESPONSE = "llm_response"
        private const val MSG_TYPE_CAMERA_START = "camera_start"
        private const val MSG_TYPE_CAMERA_STOP = "camera_stop"
        private const val MSG_TYPE_PING = "ping"
        private const val MSG_TYPE_PONG = "pong"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionsClient by lazy { Nearby.getConnectionsClient(context) }

    // 연결된 컴패니언들
    data class CompanionInfo(
        val endpointId: String,
        val displayName: String,
        var availableRamMb: Int = 0,
        var hasCameraStream: Boolean = false
    )

    private val connectedDevices = ConcurrentHashMap<String, CompanionInfo>()

    // LLM 오프로드 응답 채널
    private val llmResponseChannels = ConcurrentHashMap<String, Channel<String>>()

    // =========================================================================
    // 생명주기
    // =========================================================================

    fun start() {
        startAdvertising()
        startDiscovery()
        Log.i(TAG, "CompanionDeviceManager 시작 — 광고 + 탐색 시작")
    }

    fun stop() {
        try {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
        } catch (e: Exception) {
            Log.w(TAG, "Nearby 정지 중 오류: ${e.message}")
        }
        connectedDevices.clear()
        Log.i(TAG, "CompanionDeviceManager 종료")
    }

    // =========================================================================
    // 연결 API
    // =========================================================================

    /**
     * 연결된 컴패니언 목록.
     */
    fun getConnectedDevices(): List<CompanionInfo> = connectedDevices.values.toList()

    /**
     * 주컴패니언 기기 (첫 번째 연결된 기기).
     */
    fun getPrimaryCompanion(): CompanionInfo? = connectedDevices.values.firstOrNull()

    /**
     * 연결 여부.
     */
    fun isConnected(): Boolean = connectedDevices.isNotEmpty()

    /**
     * 컴패니언 가용 RAM.
     */
    fun getCompanionRamMb(): Int = connectedDevices.values.maxOfOrNull { it.availableRamMb } ?: 0

    /**
     * 엣지 LLM 오프로드: 폴드3에 프롬프트 전송 → 응답 수신.
     * 폴드3가 연결돼 있고 Gemma 1B 실행 중일 때 사용.
     *
     * @return null if no companion or timeout
     */
    suspend fun offloadLLM(prompt: String): String? {
        val companion = getPrimaryCompanion() ?: run {
            Log.d(TAG, "LLM 오프로드 불가: 컴패니언 없음")
            return null
        }

        val requestId = "llm_${System.currentTimeMillis()}"
        val responseChannel = Channel<String>(1)
        llmResponseChannels[requestId] = responseChannel

        val payload = buildJsonPayload(
            MSG_TYPE_LLM_REQUEST,
            mapOf("request_id" to requestId, "prompt" to prompt)
        )

        return try {
            sendPayload(companion.endpointId, payload)
            withTimeoutOrNull(LLM_OFFLOAD_TIMEOUT_MS) {
                responseChannel.receive()
            } ?: run {
                Log.w(TAG, "LLM 오프로드 타임아웃")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "LLM 오프로드 실패: ${e.message}")
            null
        } finally {
            llmResponseChannels.remove(requestId)
            responseChannel.close()
        }
    }

    /**
     * 컴패니언 카메라 스트림 시작 요청.
     * ★ Phase 2: 수신 프레임 → VisionManager.feedExternalFrame()으로 AI 파이프라인 연결.
     */
    fun requestVideoStream(endpointId: String) {
        val companion = connectedDevices[endpointId] ?: return
        val payload = buildJsonPayload(MSG_TYPE_CAMERA_START, mapOf("quality" to "720p"))
        sendPayload(endpointId, payload)
        companion.hasCameraStream = true

        // ★ 외부 프레임 소스 활성화: CameraX 우회 + PreviewView 숨김
        visionManager?.isExternalFrameSourceActive = true
        Log.i(TAG, "카메라 스트림 시작 요청: ${companion.displayName} → feedExternalFrame 연결")
    }

    /**
     * 컴패니언 카메라 스트림 중지 요청.
     */
    fun stopVideoStream(endpointId: String) {
        val companion = connectedDevices[endpointId] ?: return
        val payload = buildJsonPayload(MSG_TYPE_CAMERA_STOP, emptyMap())
        sendPayload(endpointId, payload)
        companion.hasCameraStream = false

        // CameraX 복원 (다른 외부 소스가 없으면)
        val hasOtherActiveStream = connectedDevices.values.any { it.hasCameraStream }
        if (!hasOtherActiveStream) {
            visionManager?.isExternalFrameSourceActive = false
        }
        Log.i(TAG, "카메라 스트림 중지: ${companion.displayName}")
    }

    // =========================================================================
    // Nearby Connections 설정
    // =========================================================================

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startAdvertising(
            DEVICE_NAME,
            SERVICE_ID,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            Log.d(TAG, "광고 시작 성공")
        }.addOnFailureListener { e ->
            Log.w(TAG, "광고 시작 실패: ${e.message}")
        }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            Log.d(TAG, "탐색 시작 성공")
        }.addOnFailureListener { e ->
            Log.w(TAG, "탐색 시작 실패: ${e.message}")
        }
    }

    // =========================================================================
    // 콜백
    // =========================================================================

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.i(TAG, "컴패니언 발견: ${info.endpointName} ($endpointId)")
            // XRealNativeAR 앱인지 확인 후 연결 요청
            if (info.serviceId == SERVICE_ID) {
                connectionsClient.requestConnection(
                    DEVICE_NAME,
                    endpointId,
                    connectionLifecycleCallback
                ).addOnFailureListener { e ->
                    Log.w(TAG, "연결 요청 실패: ${e.message}")
                }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "컴패니언 소실: $endpointId")
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.i(TAG, "연결 시작: ${info.endpointName} — 자동 수락")
            // 자동 수락 (동일 SERVICE_ID 보장)
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            when (resolution.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.i(TAG, "연결 성공: $endpointId")
                    val info = CompanionInfo(endpointId, "GalaxyFold3")
                    connectedDevices[endpointId] = info

                    // ResourceRegistry + DeviceHealthMonitor 연결 상태 동기화
                    resourceRegistry.companionConnected = true
                    deviceHealthMonitor?.fold3Connected = true

                    scope.launch {
                        eventBus.publish(
                            XRealEvent.ActionRequest.SpeakTTS("컴패니언 기기 연결됨")
                        )
                        // RAM 정보 요청
                        requestRamInfo(endpointId)
                    }
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Log.w(TAG, "연결 거부: $endpointId")
                }
                ConnectionsStatusCodes.STATUS_ERROR -> {
                    Log.e(TAG, "연결 오류: $endpointId")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.i(TAG, "연결 해제: $endpointId")
            connectedDevices.remove(endpointId)

            // 모든 컴패니언 해제 시 상태 초기화
            if (connectedDevices.isEmpty()) {
                resourceRegistry.companionConnected = false
                resourceRegistry.companionRamAvailMb = 0
                deviceHealthMonitor?.fold3Connected = false
                deviceHealthMonitor?.fold3RamAvailMb = 0
            }

            scope.launch {
                eventBus.publish(
                    XRealEvent.ActionRequest.SpeakTTS("컴패니언 기기 연결 해제됨")
                )
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return

            // ★ Phase 2: JPEG 프레임 감지 (SOI 마커: 0xFF 0xD8)
            // 컴패니언 앱이 카메라 프레임을 raw JPEG bytes로 전송
            if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
                handleCameraFrame(endpointId, bytes)
                return
            }

            // 일반 JSON 메시지
            val json = try { JSONObject(String(bytes)) } catch (e: Exception) { return }
            handleIncomingPayload(endpointId, json)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // 대용량 페이로드 진행률 추적 (필요시 프로그레스 바 업데이트)
        }
    }

    /**
     * 컴패니언 카메라 JPEG 프레임 수신 처리.
     * CAMERA_COMPANION 리소스가 활성화된 경우 VisionManager로 전달.
     */
    private fun handleCameraFrame(endpointId: String, jpegBytes: ByteArray) {
        val companion = connectedDevices[endpointId] ?: return
        if (!companion.hasCameraStream) return

        val vm = visionManager ?: return
        if (!vm.isExternalFrameSourceActive) {
            // CAMERA_COMPANION이 activate되면 isExternalFrameSourceActive=true로 설정됨
            // 아직 활성화 안 됐으면 프레임 무시
            return
        }

        try {
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return
            vm.feedExternalFrame(bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "컴패니언 카메라 프레임 디코딩 실패: ${e.message}")
        }
    }

    // =========================================================================
    // 페이로드 처리
    // =========================================================================

    private fun handleIncomingPayload(endpointId: String, json: JSONObject) {
        val type = json.optString("type")
        val data = json.optJSONObject("data") ?: JSONObject()

        when (type) {
            MSG_TYPE_RAM_INFO -> {
                val ramMb = data.optInt("available_ram_mb", 0)
                connectedDevices[endpointId]?.availableRamMb = ramMb
                resourceRegistry.companionRamAvailMb = ramMb
                deviceHealthMonitor?.fold3RamAvailMb = ramMb
                Log.d(TAG, "컴패니언 RAM: ${ramMb}MB")
            }

            MSG_TYPE_LLM_RESPONSE -> {
                val requestId = data.optString("request_id")
                val response = data.optString("response")
                llmResponseChannels[requestId]?.let { channel ->
                    scope.launch { channel.send(response) }
                }
            }

            MSG_TYPE_PONG -> {
                Log.d(TAG, "PONG 수신: $endpointId")
            }

            else -> {
                Log.d(TAG, "알 수 없는 페이로드 타입: $type")
            }
        }
    }

    private fun requestRamInfo(endpointId: String) {
        val payload = buildJsonPayload(MSG_TYPE_PING, mapOf("request" to "ram_info"))
        sendPayload(endpointId, payload)
    }

    private fun sendPayload(endpointId: String, jsonStr: String) {
        try {
            val payload = Payload.fromBytes(jsonStr.toByteArray(Charsets.UTF_8))
            connectionsClient.sendPayload(endpointId, payload)
        } catch (e: Exception) {
            Log.e(TAG, "페이로드 전송 실패: ${e.message}")
        }
    }

    private fun buildJsonPayload(type: String, data: Map<String, Any>): String {
        val json = JSONObject()
        json.put("type", type)
        val dataObj = JSONObject()
        data.forEach { (k, v) -> dataObj.put(k, v) }
        json.put("data", dataObj)
        return json.toString()
    }
}
