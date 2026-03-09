package com.xreal.relay.watch

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.*
import com.xreal.relay.server.RelayHttpServer

/**
 * Galaxy Watch 7 → Wear OS MessageClient → Fold 3 → RelayHttpServer SSE 릴레이.
 *
 * 워치 앱이 보내는 메시지 경로:
 *   /xreal/sensor/hr       → {"hr": 72, "ts": ...}
 *   /xreal/sensor/hrv      → {"rmssd": 45.2, "sdnn": 52.1, ...}
 *   /xreal/sensor/steps    → {"steps": 1234, "ts": ...}
 *   /xreal/sensor/accel    → {"x": 0.1, "y": 9.8, "z": 0.3, ...}
 *   /xreal/sensor/gyro     → {"x": 0.01, "y": -0.02, "z": 0.005, ...}
 *   /xreal/sensor/ppg      → {"raw": [...], ...}
 *   /xreal/sensor/running  → {"cadence": 180, "gct_ms": 245, ...}
 */
class WatchSensorReceiver(
    private val context: Context,
    private val httpServer: RelayHttpServer
) : MessageClient.OnMessageReceivedListener {

    private val TAG = "WatchSensorReceiver"
    private val SENSOR_PATH_PREFIX = "/xreal/sensor/"

    // 통계
    var messageCount = 0L
        private set
    var connectedNodeName: String? = null
        private set

    fun start() {
        Wearable.getMessageClient(context).addListener(this)

        // 연결된 노드 확인
        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.isNotEmpty()) {
                connectedNodeName = nodes.first().displayName
                Log.i(TAG, "워치 연결됨: ${nodes.joinToString { "${it.displayName}(${it.id})" }}")
            } else {
                Log.w(TAG, "연결된 워치 없음")
            }
        }

        Log.i(TAG, "워치 센서 수신 시작")
    }

    fun stop() {
        Wearable.getMessageClient(context).removeListener(this)
        Log.i(TAG, "워치 센서 수신 종료 (총 ${messageCount}메시지)")
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        if (!path.startsWith(SENSOR_PATH_PREFIX)) return

        try {
            val json = String(messageEvent.data, Charsets.UTF_8)
            val sensorType = path.removePrefix(SENSOR_PATH_PREFIX)

            // SSE 이벤트로 래핑: type 필드 추가
            val wrappedJson = if (json.startsWith("{")) {
                // 기존 JSON에 type 필드 주입
                """{"type":"$sensorType",${json.substring(1)}"""
            } else {
                """{"type":"$sensorType","data":$json}"""
            }

            httpServer.enqueueSensorEvent(wrappedJson)
            messageCount++

            if (messageCount % 100 == 0L) {
                Log.d(TAG, "센서 릴레이 진행: ${messageCount}메시지 (최근: $sensorType)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "센서 메시지 처리 오류: ${e.message}")
        }
    }
}
