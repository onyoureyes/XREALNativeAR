package com.xreal.nativear

import android.util.Log
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * GestureManager: Detects multi-tap gestures and head gestures, publishing them to GlobalEventBus.
 */
class GestureManager(private val eventBus: GlobalEventBus) {
    private val TAG = "GestureManager"
    private var tapCount = 0
    private var lastTapTime = 0L
    private val TAP_TIMEOUT = 500L // 500ms
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        subscribeToEvents()
    }

    private fun subscribeToEvents() {
        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is XRealEvent.PerceptionEvent.HeadPoseUpdated -> {
                        processPose(com.xreal.nativear.nrsdk.XRealPose(
                            qx = event.qx, qy = event.qy, qz = event.qz, qw = event.qw
                        ))
                    }
                    else -> {}
                }
            }
        }
    }

    fun onTap() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastTapTime > TAP_TIMEOUT) {
            tapCount = 1
        } else {
            tapCount++
        }
        lastTapTime = currentTime

        Log.d(TAG, "Tap Count: $tapCount")
        when (tapCount) {
            2 -> publishGesture(GestureType.DOUBLE_TAP)
            3 -> publishGesture(GestureType.TRIPLE_TAP)
            4 -> publishGesture(GestureType.QUAD_TAP)
        }
    }

    /**
     * processPose: Detects head gestures (Nod/Shake) from orientation data.
     */
    fun processPose(pose: com.xreal.nativear.nrsdk.XRealPose) {
        // Convert Quaternion to Euler (Simplified for Head Gestures)
        // Pitch (X), Yaw (Y), Roll (Z)
        val pitch = Math.toDegrees(Math.asin(2.0 * (pose.qw * pose.qy - pose.qx * pose.qz))).toFloat()
        val yaw = Math.toDegrees(Math.atan2(2.0 * (pose.qw * pose.qz + pose.qx * pose.qy), 1.0 - 2.0 * (pose.qy * pose.qy + pose.qz * pose.qz))).toFloat()

        val now = System.currentTimeMillis()
        
        // Nod Detection (Pitch change)
        val deltaPitch = Math.abs(pitch - lastEulerX)
        if (deltaPitch > 15.0f && now - lastNodTime > 1000) {
            Log.i(TAG, "Head Nod Detected | DeltaPitch: $deltaPitch")
            publishGesture(GestureType.NOD)
            lastNodTime = now
        }

        // Shake Detection (Yaw change)
        val deltaYaw = Math.abs(yaw - lastEulerY)
        if (deltaYaw > 20.0f && now - lastShakeTime > 1000) {
            Log.i(TAG, "Head Shake Detected | DeltaYaw: $deltaYaw")
            publishGesture(GestureType.SHAKE)
            lastShakeTime = now
        }

        lastEulerX = pitch
        lastEulerY = yaw
    }
    
    private fun publishGesture(type: GestureType) {
        scope.launch {
            eventBus.publish(XRealEvent.InputEvent.Gesture(type))
            Log.d(TAG, "Published Gesture Event: $type")
        }
    }
}

