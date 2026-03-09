package com.xreal.wear.communication

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.xreal.wear.sensor.SensorStreamingService

/**
 * Receives control messages from phone app.
 * Phone can start/stop sensor streaming remotely.
 */
class PhoneMessageService : WearableListenerService() {

    companion object {
        private const val TAG = "PhoneMessageService"
    }

    override fun onMessageReceived(event: MessageEvent) {
        Log.d(TAG, "Message received: ${event.path}")

        when (event.path) {
            "/xreal/control/start" -> {
                Log.i(TAG, "Phone requested sensor start")
                SensorStreamingService.start(this)
            }
            "/xreal/control/stop" -> {
                Log.i(TAG, "Phone requested sensor stop")
                SensorStreamingService.stop(this)
            }
        }
    }
}
