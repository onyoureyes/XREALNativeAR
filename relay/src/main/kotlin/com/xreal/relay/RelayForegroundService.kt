package com.xreal.relay

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xreal.relay.audio.AudioCaptureService
import com.xreal.relay.camera.CameraStreamProvider
import com.xreal.relay.health.HealthConnectReader
import com.xreal.relay.server.RelayHttpServer
import com.xreal.relay.sync.RelaySyncService
import com.xreal.relay.watch.WatchSensorReceiver

/**
 * 포그라운드 서비스 — 모든 릴레이 컴포넌트 오케스트레이션.
 *
 * 구성:
 *   RelayHttpServer (port 8554) — MJPEG + PCM + SSE 서빙
 *   CameraStreamProvider — 후면 카메라 → JPEG
 *   AudioCaptureService — 마이크 → PCM 16kHz
 *   WatchSensorReceiver — Wear OS MessageClient → SSE
 *   HealthConnectReader — Samsung Health / Garmin 데이터
 *   RelaySyncService — Health Connect → PC 서버
 */
class RelayForegroundService : Service() {

    private val TAG = "RelayForegroundService"
    private val NOTIFICATION_ID = 9001
    private val CHANNEL_ID = "relay_service"

    // 컴포넌트
    var httpServer: RelayHttpServer? = null
        private set
    private var cameraProvider: CameraStreamProvider? = null
    var audioCapture: AudioCaptureService? = null
        private set
    var watchReceiver: WatchSensorReceiver? = null
        private set
    var healthReader: HealthConnectReader? = null
        private set
    private var syncService: RelaySyncService? = null

    // 상태 콜백
    var onStatusChanged: ((String) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRelay()
            ACTION_STOP -> stopRelay()
        }
        return START_STICKY
    }

    private fun startRelay() {
        // 포그라운드 알림
        val notification = buildNotification("릴레이 시작 중...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            // 1. HTTP 서버
            httpServer = RelayHttpServer(port = RELAY_PORT).also { server ->
                server.onClientConnected = { type ->
                    Log.i(TAG, "클라이언트 연결: $type")
                    updateNotification()
                }
                server.start()
            }

            // 2. 오디오 캡처
            audioCapture = AudioCaptureService(this, httpServer!!).also {
                it.start()
            }

            // 3. 워치 센서 수신
            watchReceiver = WatchSensorReceiver(this, httpServer!!).also {
                it.start()
            }

            // 4. Health Connect
            healthReader = HealthConnectReader(this).also {
                it.start()
            }

            // 5. PC 서버 동기화
            syncService = RelaySyncService(healthReader!!).also {
                it.start()
            }

            updateNotification()
            onStatusChanged?.invoke("running")
            Log.i(TAG, "릴레이 서비스 시작 완료")

        } catch (e: Exception) {
            Log.e(TAG, "릴레이 시작 실패: ${e.message}", e)
            onStatusChanged?.invoke("error: ${e.message}")
            stopRelay()
        }
    }

    /**
     * 카메라는 LifecycleOwner 필요 — Activity에서 별도 호출.
     */
    fun startCamera(lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
        val server = httpServer ?: return
        cameraProvider = CameraStreamProvider(this, server).also {
            it.start(lifecycleOwner)
        }
        Log.i(TAG, "카메라 스트림 시작")
    }

    private fun stopRelay() {
        cameraProvider?.stop()
        audioCapture?.stop()
        watchReceiver?.stop()
        healthReader?.stop()
        syncService?.stop()
        httpServer?.stop()

        cameraProvider = null
        audioCapture = null
        watchReceiver = null
        healthReader = null
        syncService = null
        httpServer = null

        onStatusChanged?.invoke("stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "릴레이 서비스 종료")
    }

    fun getStatusJson(): String {
        val server = httpServer
        return """
        {
            "running": ${server?.isRunning ?: false},
            "port": $RELAY_PORT,
            "clients": {
                "video": ${server?.videoClients?.size ?: 0},
                "audio": ${server?.audioClients?.size ?: 0},
                "sensors": ${server?.sensorClients?.size ?: 0}
            },
            "camera": ${cameraProvider?.isRunning ?: false},
            "audio_capture": ${audioCapture?.isRunning ?: false},
            "watch": "${watchReceiver?.connectedNodeName ?: "disconnected"}",
            "watch_messages": ${watchReceiver?.messageCount ?: 0},
            "health_connect": ${healthReader?.isRunning ?: false},
            "frames_sent": ${cameraProvider?.frameCount ?: 0}
        }
        """.trimIndent()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "XREAL Relay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "카메라/마이크/워치 데이터 릴레이"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, RelayMainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("XREAL Relay")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val server = httpServer ?: return
        val clients = server.videoClients.size + server.audioClients.size + server.sensorClients.size
        val watch = watchReceiver?.connectedNodeName ?: "미연결"
        val text = "클라이언트: ${clients} | 워치: $watch"

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        stopRelay()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.xreal.relay.START"
        const val ACTION_STOP = "com.xreal.relay.STOP"
        const val RELAY_PORT = 8554
    }
}
