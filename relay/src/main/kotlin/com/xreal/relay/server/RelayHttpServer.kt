package com.xreal.relay.server

import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 경량 HTTP 서버 — Fold 4의 MjpegStreamClient/PcmAudioClient와 호환.
 *
 * 엔드포인트:
 *   GET /video   → MJPEG 멀티파트 스트림 (카메라)
 *   GET /audio   → raw 16kHz mono PCM 스트림 (마이크)
 *   GET /sensors → SSE (Server-Sent Events) — 워치 센서 JSON
 *   GET /status  → 서버 상태 JSON
 */
class RelayHttpServer(private val port: Int = 8554) {
    private val TAG = "RelayHttpServer"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)

    // 스트림 클라이언트 목록
    val videoClients = CopyOnWriteArrayList<OutputStream>()
    val audioClients = CopyOnWriteArrayList<OutputStream>()
    val sensorClients = CopyOnWriteArrayList<OutputStream>()

    // 센서 이벤트 큐 (SSE용)
    val sensorEventQueue = ConcurrentLinkedQueue<String>()

    // 콜백
    var onClientConnected: ((type: String) -> Unit)? = null

    fun start() {
        if (running.getAndSet(true)) return

        scope.launch {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "HTTP 서버 시작: port=$port")

                while (running.get()) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        launch { handleClient(socket) }
                    } catch (e: Exception) {
                        if (running.get()) Log.w(TAG, "Accept 오류: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "서버 시작 실패: ${e.message}", e)
            }
        }

        // SSE 이벤트 디스패처
        scope.launch {
            while (running.get()) {
                val event = sensorEventQueue.poll()
                if (event != null) {
                    broadcastSensorEvent(event)
                } else {
                    delay(10)
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        videoClients.forEach { runCatching { it.close() } }
        audioClients.forEach { runCatching { it.close() } }
        sensorClients.forEach { runCatching { it.close() } }
        videoClients.clear()
        audioClients.clear()
        sensorClients.clear()
        runCatching { serverSocket?.close() }
        scope.cancel()
        Log.i(TAG, "HTTP 서버 종료")
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            // 나머지 헤더 소비
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            val path = requestLine.split(" ").getOrNull(1) ?: "/"
            val output = socket.getOutputStream()

            when {
                path == "/video" -> handleVideoStream(output)
                path == "/audio" -> handleAudioStream(output)
                path == "/sensors" -> handleSensorStream(output)
                path == "/status" -> handleStatus(output)
                else -> {
                    val body = """{"error":"Not Found","endpoints":["/video","/audio","/sensors","/status"]}"""
                    val response = "HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body"
                    output.write(response.toByteArray())
                    output.flush()
                    socket.close()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "클라이언트 처리 오류: ${e.message}")
            runCatching { socket.close() }
        }
    }

    private fun handleVideoStream(output: OutputStream) {
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray())
        output.flush()
        videoClients.add(output)
        onClientConnected?.invoke("video")
        Log.i(TAG, "Video 클라이언트 연결 (총 ${videoClients.size})")
    }

    private fun handleAudioStream(output: OutputStream) {
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray())
        output.flush()
        audioClients.add(output)
        onClientConnected?.invoke("audio")
        Log.i(TAG, "Audio 클라이언트 연결 (총 ${audioClients.size})")
    }

    private fun handleSensorStream(output: OutputStream) {
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: keep-alive\r\n\r\n"
        output.write(header.toByteArray())
        output.flush()
        sensorClients.add(output)
        onClientConnected?.invoke("sensors")
        Log.i(TAG, "Sensor 클라이언트 연결 (총 ${sensorClients.size})")
    }

    private fun handleStatus(output: OutputStream) {
        val body = """{"status":"running","port":$port,"clients":{"video":${videoClients.size},"audio":${audioClients.size},"sensors":${sensorClients.size}}}"""
        val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body"
        output.write(response.toByteArray())
        output.flush()
    }

    /** MJPEG 프레임 브로드캐스트 — CameraStreamProvider에서 호출 */
    fun broadcastVideoFrame(jpegData: ByteArray) {
        val header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpegData.size}\r\n\r\n"
        val trailer = "\r\n"
        val dead = mutableListOf<OutputStream>()

        for (client in videoClients) {
            try {
                client.write(header.toByteArray())
                client.write(jpegData)
                client.write(trailer.toByteArray())
                client.flush()
            } catch (e: Exception) {
                dead.add(client)
            }
        }
        videoClients.removeAll(dead.toSet())
    }

    /** PCM 오디오 청크 브로드캐스트 — AudioCaptureService에서 호출 */
    fun broadcastAudioChunk(pcmData: ByteArray) {
        val dead = mutableListOf<OutputStream>()
        for (client in audioClients) {
            try {
                client.write(pcmData)
                client.flush()
            } catch (e: Exception) {
                dead.add(client)
            }
        }
        audioClients.removeAll(dead.toSet())
    }

    /** SSE 센서 이벤트 브로드캐스트 */
    private fun broadcastSensorEvent(json: String) {
        val sseData = "data: $json\n\n"
        val dead = mutableListOf<OutputStream>()
        for (client in sensorClients) {
            try {
                client.write(sseData.toByteArray())
                client.flush()
            } catch (e: Exception) {
                dead.add(client)
            }
        }
        sensorClients.removeAll(dead.toSet())
    }

    /** 센서 이벤트 큐에 추가 (워치 수신 시 호출) */
    fun enqueueSensorEvent(json: String) {
        sensorEventQueue.offer(json)
    }

    val isRunning: Boolean get() = running.get()
}
