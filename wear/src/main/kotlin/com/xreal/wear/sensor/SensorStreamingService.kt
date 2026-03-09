package com.xreal.wear.sensor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import com.google.android.gms.wearable.Wearable
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Foreground service that streams sensor data from Galaxy Watch.
 * Uses Samsung Health Sensor SDK for: Heart Rate, IBI→HRV, SpO2, Skin Temperature.
 * Uses standard Android API for: Accelerometer, GPS.
 * Sends data to phone via Wear OS MessageClient.
 */
class SensorStreamingService : LifecycleService(), SensorEventListener {

    companion object {
        private const val TAG = "SensorStreaming"
        private const val CHANNEL_ID = "xreal_sensor_channel"
        private const val NOTIFICATION_ID = 1001

        // Message paths for phone communication
        const val PATH_HEART_RATE = "/xreal/sensor/hr"
        const val PATH_HRV = "/xreal/sensor/hrv"
        const val PATH_GPS = "/xreal/sensor/gps"
        const val PATH_SKIN_TEMP = "/xreal/sensor/skin_temp"
        const val PATH_ACCELEROMETER = "/xreal/sensor/accel"
        const val PATH_SPO2 = "/xreal/sensor/spo2"
        const val PATH_CONTROL = "/xreal/control"

        // Sensor data flow for local UI
        private val _sensorData = MutableSharedFlow<SensorData>(extraBufferCapacity = 64)
        val sensorData: SharedFlow<SensorData> = _sensorData

        fun start(context: Context) {
            val intent = Intent(context, SensorStreamingService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SensorStreamingService::class.java))
        }
    }

    // Samsung Health Sensor SDK
    private var healthTrackingService: HealthTrackingService? = null
    private var hrTracker: HealthTracker? = null
    private var spo2Tracker: HealthTracker? = null
    private var skinTempTracker: HealthTracker? = null
    private var trackerHandler: Handler? = null
    private var trackerThread: HandlerThread? = null

    // Standard Android sensors (accelerometer)
    private lateinit var sensorManager: SensorManager
    private var accelerometerSensor: Sensor? = null

    // GPS
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    // HRV calculation from IBI (Inter-Beat Interval) via Samsung SDK
    private val ibiIntervals = mutableListOf<Int>()

    // Throttling
    private var lastAccelSendTime = 0L
    private val accelSendIntervalMs = 200L  // 5Hz to phone

    // SpO2 periodic measurement
    private var spo2Job: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // Background thread for Samsung SDK tracker callbacks
        trackerThread = HandlerThread("SamsungSensorThread").also { it.start() }
        trackerHandler = Handler(trackerThread!!.looper)

        initSamsungHealthSensors()
        initStandardSensors()
        startGps()
        Log.i(TAG, "Sensor streaming service started")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSamsungHealthSensors()
        sensorManager.unregisterListener(this)
        stopGps()
        spo2Job?.cancel()
        trackerThread?.quitSafely()
        Log.i(TAG, "Sensor streaming service stopped")
    }

    // ===== Samsung Health Sensor SDK =====

    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            Log.i(TAG, "Samsung Health Tracking Service connected")
            checkCapabilitiesAndStart()
        }

        override fun onConnectionEnded() {
            Log.w(TAG, "Samsung Health Tracking Service disconnected")
        }

        override fun onConnectionFailed(e: HealthTrackerException) {
            Log.e(TAG, "Samsung Health Tracking Service connection failed: ${e.errorCode}", e)
            // Fall back to standard sensors if Samsung SDK unavailable
            if (e.hasResolution()) {
                Log.w(TAG, "Resolution available but no activity to resolve")
            }
            initFallbackHeartRate()
        }
    }

    private fun initSamsungHealthSensors() {
        try {
            healthTrackingService = HealthTrackingService(connectionListener, this)
            healthTrackingService?.connectService()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Samsung Health SDK, falling back to standard", e)
            initFallbackHeartRate()
        }
    }

    private fun checkCapabilitiesAndStart() {
        val service = healthTrackingService ?: return
        try {
            val available = service.trackingCapability.supportHealthTrackerTypes
            Log.i(TAG, "Available Samsung trackers: $available")

            // Heart Rate Continuous (includes IBI for HRV)
            if (available.contains(HealthTrackerType.HEART_RATE_CONTINUOUS)) {
                hrTracker = service.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
                trackerHandler?.post { hrTracker?.setEventListener(hrListener) }
                Log.i(TAG, "Samsung HR tracker started (continuous + IBI)")
            } else {
                Log.w(TAG, "Samsung HR not available, using fallback")
                initFallbackHeartRate()
            }

            // SpO2 On-Demand
            if (available.contains(HealthTrackerType.SPO2_ON_DEMAND)) {
                spo2Tracker = service.getHealthTracker(HealthTrackerType.SPO2_ON_DEMAND)
                startPeriodicSpo2()
                Log.i(TAG, "Samsung SpO2 tracker available")
            } else {
                Log.w(TAG, "SpO2 not available on this device")
            }

            // Skin Temperature Continuous
            if (available.contains(HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS)) {
                skinTempTracker = service.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS)
                trackerHandler?.post { skinTempTracker?.setEventListener(skinTempListener) }
                Log.i(TAG, "Samsung Skin Temperature tracker started (continuous)")
            } else if (available.contains(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)) {
                skinTempTracker = service.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)
                trackerHandler?.post { skinTempTracker?.setEventListener(skinTempListener) }
                Log.i(TAG, "Samsung Skin Temperature tracker started (on-demand)")
            } else {
                Log.w(TAG, "Skin temperature not available")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Samsung trackers", e)
        }
    }

    // Heart Rate + IBI listener
    private val hrListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
            for (data in dataPoints) {
                try {
                    val status = data.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS)
                    val heartRate = data.getValue(ValueKey.HeartRateSet.HEART_RATE)

                    if (heartRate > 0) {
                        val now = System.currentTimeMillis()
                        val hrData = SensorData.HeartRate(bpm = heartRate.toFloat(), timestamp = now)
                        emitAndSend(hrData, PATH_HEART_RATE)
                    }

                    // IBI (Inter-Beat Interval) for HRV calculation
                    val ibiList = data.getValue(ValueKey.HeartRateSet.IBI_LIST)
                    val ibiStatusList = data.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST)
                    if (ibiList != null && ibiList.isNotEmpty()) {
                        for (i in ibiList.indices) {
                            val ibiStatus = if (ibiStatusList != null && i < ibiStatusList.size) ibiStatusList[i] else 0
                            if (ibiStatus == 0 && ibiList[i] in 300..2000) {
                                ibiIntervals.add(ibiList[i])
                            }
                        }
                        if (ibiIntervals.size >= 10) {
                            calculateAndSendHrv()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing HR data", e)
                }
            }
        }

        override fun onFlushCompleted() {}

        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(TAG, "HR tracker error: $error")
            if (error == HealthTracker.TrackerError.PERMISSION_ERROR) {
                Log.e(TAG, "Body sensor permission denied")
            }
        }
    }

    // SpO2 listener
    private val spo2Listener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
            for (data in dataPoints) {
                try {
                    val status = data.getValue(ValueKey.SpO2Set.STATUS)
                    // status 2 = MEASUREMENT_COMPLETED
                    if (status == 2) {
                        val spo2 = data.getValue(ValueKey.SpO2Set.SPO2)
                        if (spo2 > 0) {
                            val now = System.currentTimeMillis()
                            val spo2Data = SensorData.SpO2(spo2 = spo2, timestamp = now)
                            emitAndSend(spo2Data, PATH_SPO2)
                            Log.d(TAG, "SpO2: $spo2%")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing SpO2 data", e)
                }
            }
        }

        override fun onFlushCompleted() {}

        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(TAG, "SpO2 tracker error: $error")
        }
    }

    // Skin Temperature listener
    private val skinTempListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
            for (data in dataPoints) {
                try {
                    val status = data.getValue(ValueKey.SkinTemperatureSet.STATUS)
                    // status 0 = SUCCESSFUL_MEASUREMENT
                    if (status == 0) {
                        val wristTemp = data.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)
                        val ambientTemp = data.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE)
                        if (wristTemp > 0) {
                            val now = System.currentTimeMillis()
                            val tempData = SensorData.SkinTemperature(
                                temperature = wristTemp,
                                ambientTemperature = ambientTemp,
                                timestamp = now
                            )
                            emitAndSend(tempData, PATH_SKIN_TEMP)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing skin temp data", e)
                }
            }
        }

        override fun onFlushCompleted() {}

        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(TAG, "Skin temp tracker error: $error")
        }
    }

    /**
     * SpO2 is on-demand (max 30s measurement). Measure every 5 minutes.
     */
    private fun startPeriodicSpo2() {
        spo2Job = lifecycleScope.launch {
            while (isActive) {
                try {
                    trackerHandler?.post { spo2Tracker?.setEventListener(spo2Listener) }
                    delay(30_000)  // 30s measurement window
                    trackerHandler?.post {
                        try { spo2Tracker?.unsetEventListener() } catch (_: Exception) {}
                    }
                    delay(270_000)  // Wait 4.5 min before next measurement
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "SpO2 periodic measurement error", e)
                    delay(60_000)
                }
            }
        }
    }

    private fun calculateAndSendHrv() {
        if (ibiIntervals.size < 5) return

        val recent = ibiIntervals.takeLast(20)
        val mean = recent.average()

        // RMSSD: Root Mean Square of Successive Differences
        val successiveDiffs = recent.zipWithNext { a, b -> (b - a).toDouble() }
        val rmssd = if (successiveDiffs.isNotEmpty()) {
            kotlin.math.sqrt(successiveDiffs.map { it * it }.average())
        } else 0.0

        // SDNN: Standard Deviation of NN intervals
        val sdnn = kotlin.math.sqrt(recent.map { (it - mean) * (it - mean) }.average())

        val data = SensorData.Hrv(
            rmssd = rmssd.toFloat(),
            sdnn = sdnn.toFloat(),
            meanRR = mean.toFloat(),
            sampleCount = recent.size,
            timestamp = System.currentTimeMillis()
        )
        emitAndSend(data, PATH_HRV)

        // Keep last 50 intervals
        if (ibiIntervals.size > 50) {
            ibiIntervals.subList(0, ibiIntervals.size - 50).clear()
        }
    }

    private fun stopSamsungHealthSensors() {
        try {
            trackerHandler?.post {
                try { hrTracker?.unsetEventListener() } catch (_: Exception) {}
                try { spo2Tracker?.unsetEventListener() } catch (_: Exception) {}
                try { skinTempTracker?.unsetEventListener() } catch (_: Exception) {}
            }
            healthTrackingService?.disconnectService()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Samsung sensors", e)
        }
    }

    // ===== Fallback: Standard Android SensorManager for HR =====

    private var fallbackHrSensor: Sensor? = null

    private fun initFallbackHeartRate() {
        if (hrTracker != null) return  // Samsung SDK already working
        fallbackHrSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        fallbackHrSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "Fallback HR sensor registered (standard Android)")
        } ?: Log.w(TAG, "No heart rate sensor available")
    }

    // ===== Standard Android sensors (Accelerometer) =====

    private fun initStandardSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometerSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.i(TAG, "Accelerometer registered (standard Android)")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> handleFallbackHeartRate(event)
            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun handleFallbackHeartRate(event: SensorEvent) {
        val bpm = event.values[0]
        if (bpm <= 0) return
        val now = System.currentTimeMillis()
        val data = SensorData.HeartRate(bpm = bpm, timestamp = now)
        emitAndSend(data, PATH_HEART_RATE)
    }

    private fun handleAccelerometer(event: SensorEvent) {
        val now = System.currentTimeMillis()
        if (now - lastAccelSendTime < accelSendIntervalMs) return
        lastAccelSendTime = now

        val data = SensorData.Accelerometer(
            x = event.values[0],
            y = event.values[1],
            z = event.values[2],
            timestamp = now
        )
        emitAndSend(data, PATH_ACCELEROMETER)
    }

    // ===== GPS =====

    private fun startGps() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateIntervalMillis(1000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    val data = SensorData.Gps(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        altitude = loc.altitude,
                        accuracy = loc.accuracy,
                        speed = loc.speed,
                        timestamp = System.currentTimeMillis()
                    )
                    emitAndSend(data, PATH_GPS)
                }
            }
        }

        try {
            fusedLocationClient?.requestLocationUpdates(request, locationCallback!!, mainLooper)
            Log.i(TAG, "GPS streaming started")
        } catch (e: SecurityException) {
            Log.e(TAG, "GPS permission denied", e)
        }
    }

    private fun stopGps() {
        locationCallback?.let { fusedLocationClient?.removeLocationUpdates(it) }
    }

    // ===== Data emission =====

    private fun emitAndSend(data: SensorData, path: String) {
        lifecycleScope.launch {
            _sensorData.emit(data)
        }
        sendToPhone(path, data.toJson())
    }

    private fun sendToPhone(path: String, json: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val nodeClient = Wearable.getNodeClient(this@SensorStreamingService)
                val nodes = nodeClient.connectedNodes.await()
                val messageClient = Wearable.getMessageClient(this@SensorStreamingService)
                val payload = json.toByteArray(Charsets.UTF_8)

                for (node in nodes) {
                    messageClient.sendMessage(node.id, path, payload)
                }
            } catch (e: Exception) {
                // Silent fail -- phone might not be reachable
            }
        }
    }

    // ===== Notification =====

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Sensor Streaming",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "XREAL sensor data streaming"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("XREAL Sensor")
            .setContentText("Streaming biometrics...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }
}
