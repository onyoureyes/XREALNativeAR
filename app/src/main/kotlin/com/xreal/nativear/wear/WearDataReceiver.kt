package com.xreal.nativear.wear

import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Receives real-time sensor data from Galaxy Watch via Wear OS MessageClient.
 * Parses JSON payloads and publishes XRealEvents to GlobalEventBus.
 */
class WearDataReceiver(
    private val context: android.content.Context,
    private val eventBus: GlobalEventBus
) : MessageClient.OnMessageReceivedListener {

    companion object {
        private const val TAG = "WearDataReceiver"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var registered = false

    fun start() {
        if (registered) return
        Wearable.getMessageClient(context).addListener(this)
        registered = true
        Log.i(TAG, "Wear data receiver started — listening for watch sensor data")
    }

    fun stop() {
        if (!registered) return
        Wearable.getMessageClient(context).removeListener(this)
        registered = false
        Log.i(TAG, "Wear data receiver stopped")
    }

    override fun onMessageReceived(event: MessageEvent) {
        val json = String(event.data, Charsets.UTF_8)
        scope.launch {
            try {
                val obj = JSONObject(json)
                val xrealEvent = when (event.path) {
                    "/xreal/sensor/hr" -> XRealEvent.PerceptionEvent.WatchHeartRate(
                        bpm = obj.getDouble("bpm").toFloat(),
                        timestamp = obj.getLong("ts")
                    )
                    "/xreal/sensor/hrv" -> XRealEvent.PerceptionEvent.WatchHrv(
                        rmssd = obj.getDouble("rmssd").toFloat(),
                        sdnn = obj.getDouble("sdnn").toFloat(),
                        meanRR = obj.getDouble("mean_rr").toFloat(),
                        timestamp = obj.getLong("ts")
                    )
                    "/xreal/sensor/gps" -> XRealEvent.PerceptionEvent.WatchGps(
                        latitude = obj.getDouble("lat"),
                        longitude = obj.getDouble("lon"),
                        altitude = obj.getDouble("alt"),
                        accuracy = obj.getDouble("acc").toFloat(),
                        speed = obj.getDouble("spd").toFloat(),
                        timestamp = obj.getLong("ts")
                    )
                    "/xreal/sensor/skin_temp" -> XRealEvent.PerceptionEvent.WatchSkinTemperature(
                        temperature = obj.getDouble("temp").toFloat(),
                        ambientTemperature = obj.optDouble("ambient", 0.0).toFloat(),
                        timestamp = obj.getLong("ts")
                    )
                    "/xreal/sensor/spo2" -> XRealEvent.PerceptionEvent.WatchSpO2(
                        spo2 = obj.getInt("spo2"),
                        timestamp = obj.getLong("ts")
                    )
                    "/xreal/sensor/accel" -> XRealEvent.PerceptionEvent.WatchAccelerometer(
                        x = obj.getDouble("x").toFloat(),
                        y = obj.getDouble("y").toFloat(),
                        z = obj.getDouble("z").toFloat(),
                        timestamp = obj.getLong("ts")
                    )
                    else -> null
                }
                xrealEvent?.let { eventBus.publish(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse watch data: ${event.path}", e)
            }
        }
    }

    /**
     * Send control command to watch (start/stop sensor streaming).
     */
    fun sendWatchCommand(command: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val nodeClient = Wearable.getNodeClient(context)
                val nodes = nodeClient.connectedNodes.await()
                val messageClient = Wearable.getMessageClient(context)
                for (node in nodes) {
                    messageClient.sendMessage(node.id, "/xreal/control/$command", byteArrayOf())
                }
                Log.i(TAG, "Sent command '$command' to ${nodes.size} watch(es)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send watch command: $command", e)
            }
        }
    }
}
