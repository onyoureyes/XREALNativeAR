package com.xreal.nativear.remote

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SSE 클라이언트 — Fold 3 RelayHttpServer /sensors 엔드포인트에서 워치 센서 데이터 수신.
 *
 * 프로토콜: Server-Sent Events (text/event-stream)
 *   data: {"type":"hr","bpm":72,"ts":1709900000000}
 *
 * WearDataReceiver와 동일한 XRealEvent.PerceptionEvent를 발행하므로
 * 기존 소비자 (RunningCoachManager, ContextAggregator 등) 투명 호환.
 *
 * 사용 시나리오: 워치가 Fold 3에 BT 연결 → Fold 3 릴레이 → Fold 4 수신
 * (Fold 4에 직접 BT 연결 불가능할 때의 대체 경로)
 *
 * 연결 주소는 SharedPreferences에 영속 저장 — WiFi 변경 시 재입력 불필요.
 * Tailscale IP는 네트워크 변경에 불변이므로 기본값으로 사용.
 */
class SensorRelayReceiver(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val eventBus: GlobalEventBus
) {
    companion object {
        private const val TAG = "SensorRelayReceiver"
        private const val MAX_RETRIES = 10
        private const val RETRY_BASE_DELAY_MS = 3000L
        private const val RETRY_MAX_DELAY_MS = 30000L

        private const val PREFS_NAME = "relay_connection"
        private const val KEY_SERVER_URL = "fold3_server_url"
        // Fold 3 Tailscale IP — WiFi 변경에 무관하게 고정
        const val DEFAULT_SERVER_URL = "http://100.92.243.78:8554"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var streamJob: Job? = null

    // Fold 3 서버 주소 — SharedPreferences에서 로드, 변경 시 자동 저장
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) {
            prefs.edit().putString(KEY_SERVER_URL, value).apply()
            Log.i(TAG, "Fold 3 서버 주소 저장됨: $value")
        }

    // 통계
    var eventCount = 0L
        private set
    var lastEventType: String? = null
        private set
    var isConnected = false
        private set

    fun start() {
        if (running.getAndSet(true)) return
        startStream()
        Log.i(TAG, "센서 릴레이 수신 시작: $serverUrl/sensors")
    }

    fun stop() {
        running.set(false)
        streamJob?.cancel()
        streamJob = null
        isConnected = false
        Log.i(TAG, "센서 릴레이 수신 종료 (총 ${eventCount}이벤트)")
    }

    private fun startStream() {
        streamJob = scope.launch {
            var retryCount = 0

            while (running.get() && retryCount < MAX_RETRIES) {
                try {
                    connectAndParse()
                    // 정상 종료 시 재연결 시도
                    if (running.get()) {
                        retryCount++
                        Log.w(TAG, "SSE 스트림 종료, 재연결 시도 ($retryCount/$MAX_RETRIES)")
                        delay(RETRY_BASE_DELAY_MS)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    isConnected = false
                    retryCount++
                    val delayMs = (RETRY_BASE_DELAY_MS * retryCount).coerceAtMost(RETRY_MAX_DELAY_MS)
                    Log.w(TAG, "SSE 연결 오류 ($retryCount/$MAX_RETRIES): ${e.message}")

                    if (retryCount >= MAX_RETRIES) {
                        Log.e(TAG, "최대 재시도 초과, 센서 릴레이 중단")
                        eventBus.publish(XRealEvent.SystemEvent.Error(
                            code = "SENSOR_RELAY_MAX_RETRY",
                            message = "Fold 3 센서 릴레이 연결 실패 (${MAX_RETRIES}회 재시도)",
                            throwable = e
                        ))
                        break
                    }
                    delay(delayMs)
                }
            }
        }
    }

    private suspend fun connectAndParse() {
        val sseClient = httpClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // SSE는 무한 스트림
            .build()

        val request = Request.Builder()
            .url("$serverUrl/sensors")
            .header("Accept", "text/event-stream")
            .build()

        val response = sseClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw Exception("HTTP ${response.code}: ${response.message}")
        }

        val reader = BufferedReader(
            InputStreamReader(
                response.body?.byteStream() ?: throw Exception("Empty response body"),
                Charsets.UTF_8
            )
        )

        isConnected = true
        Log.i(TAG, "SSE 스트림 연결됨: $serverUrl/sensors")

        try {
            while (running.get()) {
                val line = reader.readLine() ?: break // 스트림 종료

                // SSE 포맷: "data: {...}\n\n"
                if (line.startsWith("data: ")) {
                    val jsonStr = line.substring(6)
                    try {
                        processEvent(JSONObject(jsonStr))
                    } catch (e: Exception) {
                        Log.w(TAG, "센서 이벤트 파싱 오류: ${e.message}")
                    }
                }
                // 빈 줄, 코멘트(: ...) 등은 무시
            }
        } finally {
            isConnected = false
            runCatching { reader.close() }
            runCatching { response.close() }
        }
    }

    /**
     * Fold 3 WatchSensorReceiver가 래핑한 JSON을 XRealEvent로 변환.
     *
     * 형식: {"type":"hr","bpm":72,"ts":1709900000000}
     * WearDataReceiver와 동일한 이벤트 타입 발행.
     */
    private suspend fun processEvent(obj: JSONObject) {
        val type = obj.optString("type", "")
        lastEventType = type
        eventCount++

        val xrealEvent: XRealEvent? = when (type) {
            "hr" -> XRealEvent.PerceptionEvent.WatchHeartRate(
                bpm = obj.getDouble("bpm").toFloat(),
                timestamp = obj.getLong("ts")
            )
            "hrv" -> XRealEvent.PerceptionEvent.WatchHrv(
                rmssd = obj.getDouble("rmssd").toFloat(),
                sdnn = obj.getDouble("sdnn").toFloat(),
                meanRR = obj.getDouble("mean_rr").toFloat(),
                timestamp = obj.getLong("ts")
            )
            "gps" -> XRealEvent.PerceptionEvent.WatchGps(
                latitude = obj.getDouble("lat"),
                longitude = obj.getDouble("lon"),
                altitude = obj.optDouble("alt", 0.0),
                accuracy = obj.optDouble("acc", 0.0).toFloat(),
                speed = obj.optDouble("spd", 0.0).toFloat(),
                timestamp = obj.getLong("ts")
            )
            "skin_temp" -> XRealEvent.PerceptionEvent.WatchSkinTemperature(
                temperature = obj.getDouble("temp").toFloat(),
                ambientTemperature = obj.optDouble("ambient", 0.0).toFloat(),
                timestamp = obj.getLong("ts")
            )
            "spo2" -> XRealEvent.PerceptionEvent.WatchSpO2(
                spo2 = obj.getInt("spo2"),
                timestamp = obj.getLong("ts")
            )
            "accel" -> XRealEvent.PerceptionEvent.WatchAccelerometer(
                x = obj.getDouble("x").toFloat(),
                y = obj.getDouble("y").toFloat(),
                z = obj.getDouble("z").toFloat(),
                timestamp = obj.getLong("ts")
            )
            "running" -> {
                // 러닝 다이나믹스 — RunningDynamics 이벤트로 변환
                XRealEvent.PerceptionEvent.RunningDynamics(
                    cadence = obj.optDouble("cadence", 0.0).toFloat(),
                    groundContactTime = obj.optDouble("gct_ms", 0.0).toFloat(),
                    verticalOscillation = obj.optDouble("vo_cm", 0.0).toFloat(),
                    groundReactionForce = obj.optDouble("grf", 0.0).toFloat(),
                    timestamp = obj.getLong("ts")
                )
            }
            "steps" -> {
                // 걸음수는 별도 이벤트 없음 — 로그만
                if (eventCount % 60 == 0L) {
                    Log.d(TAG, "걸음수: ${obj.optLong("steps", 0)}")
                }
                null
            }
            else -> {
                Log.d(TAG, "알 수 없는 센서 타입: $type")
                null
            }
        }

        xrealEvent?.let { eventBus.publish(it) }

        if (eventCount % 500 == 0L) {
            Log.d(TAG, "센서 릴레이 진행: ${eventCount}이벤트 (최근: $type)")
        }
    }

    val isRunning: Boolean get() = running.get()
}
