package com.xreal.nativear

import android.content.Context
import android.util.Log
import com.xreal.nativear.nrsdk.XRealPose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * HardwareManager: Manages XREAL hardware interactions, IMU data, and sensors.
 * Optimized with sensor fusion for head tracking.
 */
class HardwareManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val eventBus: com.xreal.nativear.core.GlobalEventBus,
    private val xrealHardwareManager: com.xreal.hardware.XRealHardwareManager
) : android.hardware.SensorEventListener, com.xreal.hardware.XRealHardwareManager.IMUListener {
    private val TAG = "HardwareManager"

    fun startHardware() {
        Log.i(TAG, "Starting Hardware Manager...")
        accelerometer?.also { sensorManager.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_UI) }
        stepDetector?.also { sensorManager.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_UI) }
        
        // Native HID/IMU Integration
        xrealHardwareManager.setIMUListener(this)
        xrealHardwareManager.findAndActivate {
            Log.i(TAG, "XREAL Activated via Native Bridge")
            xrealHardwareManager.startIMU()
            eventBus.publish(com.xreal.nativear.core.XRealEvent.SystemEvent.DebugLog("✅ XREAL Hardware Activated"))
        }
    }

    fun stopHardware() {
        Log.i(TAG, "Stopping Hardware Manager...")
        sensorManager.unregisterListener(this)
        xrealHardwareManager.stopIMU()
        xrealHardwareManager.stopCamera()
    }

    override fun onOrientationUpdate(qx: Float, qy: Float, qz: Float, qw: Float) {
        eventBus.publish(com.xreal.nativear.core.XRealEvent.PerceptionEvent.HeadPoseUpdated(qx, qy, qz, qw))
        
        // Convert to Euler to get Yaw for PDR
        // Yaw (Y-axis rotation in some conventions, here Z-up usually)
        currentYaw = Math.atan2(2.0 * (qw * qz + qx * qy), 1.0 - 2.0 * (qy * qy + qz * qz)).toFloat()
    }

    override fun onSensorChanged(event: android.hardware.SensorEvent?) {
        if (event == null) return
        
        if (event.sensor.type == android.hardware.Sensor.TYPE_STEP_DETECTOR) {
            accumulatedSteps++
            
            // Update PDR
            val dx = (STRIDE_LENGTH * Math.cos(currentYaw.toDouble())).toFloat()
            val dy = (STRIDE_LENGTH * Math.sin(currentYaw.toDouble())).toFloat()
            pdrX += dx
            pdrY += dy
            eventBus.publish(com.xreal.nativear.core.XRealEvent.PerceptionEvent.LocationUpdated(pdrX.toDouble(), pdrY.toDouble(), "PDR_POSITION"))

            val progress = ((accumulatedSteps / 15.0) * 100).toInt().coerceAtMost(100)
            if (accumulatedSteps >= 15) {
                accumulatedSteps = 0
                eventBus.publish(com.xreal.nativear.core.XRealEvent.ActionRequest.TriggerSnapshot)
            }
            return
        }

        if (event.sensor.type == android.hardware.Sensor.TYPE_ACCELEROMETER) {
            val delta = Math.abs(lastX - event.values[0]) + Math.abs(lastY - event.values[1]) + Math.abs(lastZ - event.values[2])
            if (delta > STABILITY_THRESHOLD) {
                stabilityStartTime = 0L
                hasTriggeredForCurrentStability = false
                callback.onStabilityProgress(0)
            } else {
                if (!hasTriggeredForCurrentStability) {
                    if (stabilityStartTime == 0L) stabilityStartTime = System.currentTimeMillis()
                    val duration = System.currentTimeMillis() - stabilityStartTime
                    val progress = (duration / 20.0).toInt().coerceAtMost(100)
                    if (duration >= 2000) {
                        eventBus.publish(com.xreal.nativear.core.XRealEvent.ActionRequest.TriggerSnapshot)
                        hasTriggeredForCurrentStability = true
                    }
                }
            }
            lastX = event.values[0]
            lastY = event.values[1]
            lastZ = event.values[2]
        }
    }

    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
}


