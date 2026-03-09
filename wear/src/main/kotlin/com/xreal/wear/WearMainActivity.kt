package com.xreal.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.xreal.wear.sensor.SensorData
import com.xreal.wear.sensor.SensorStreamingService
import kotlinx.coroutines.launch

/**
 * Minimal watch face activity.
 * Requests permissions, starts sensor streaming, shows live HR + SpO2.
 */
class WearMainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "WearMain"
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private val requiredPermissions = arrayOf(
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.ACTIVITY_RECOGNITION,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    private lateinit var textView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = FrameLayout(this)
        textView = TextView(this).apply {
            text = "XREAL Sensor\nRequesting permissions..."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(60, 60, 60, 60)
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        )
        container.addView(textView, params)
        setContentView(container)

        if (hasPermissions()) {
            startStreaming()
        } else {
            ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (hasPermissions()) {
                startStreaming()
            } else {
                textView.text = "Permissions\ndenied"
                Log.w(TAG, "Permissions denied")
            }
        }
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private var lastHr = 0
    private var lastSpo2 = 0
    private var lastTemp = 0f

    private fun startStreaming() {
        textView.text = "XREAL\nConnecting..."
        SensorStreamingService.start(this)

        lifecycleScope.launch {
            SensorStreamingService.sensorData.collect { data ->
                when (data) {
                    is SensorData.HeartRate -> {
                        lastHr = data.bpm.toInt()
                        updateDisplay()
                    }
                    is SensorData.SpO2 -> {
                        lastSpo2 = data.spo2
                        updateDisplay()
                    }
                    is SensorData.SkinTemperature -> {
                        lastTemp = data.temperature
                        updateDisplay()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun updateDisplay() {
        val sb = StringBuilder()
        if (lastHr > 0) sb.append("HR $lastHr bpm\n")
        if (lastSpo2 > 0) sb.append("SpO2 $lastSpo2%\n")
        if (lastTemp > 0) sb.append("${String.format("%.1f", lastTemp)}\u00B0C")
        if (sb.isEmpty()) sb.append("Waiting...")
        textView.text = sb.toString().trimEnd()
    }
}
