package com.xreal.nativear

import android.util.Log

/**
 * GestureManager: Detects multi-tap gestures on the XREAL glasses side button.
 */
class GestureManager(private val listener: GestureListener) {
    private val TAG = "GestureManager"
    private var tapCount = 0
    private var lastTapTime = 0L
    private val TAP_TIMEOUT = 500L // 500ms

    interface GestureListener {
        fun onDoubleTap()
        fun onTripleTap()
        fun onQuadTap()
        fun onNod()
        fun onShake()
    }

    private var lastEulerX = 0f
    private var lastEulerY = 0f
    private var lastNodTime = 0L
    private var lastShakeTime = 0L

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
            2 -> listener.onDoubleTap()
            3 -> listener.onTripleTap()
            4 -> listener.onQuadTap()
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
            listener.onNod()
            lastNodTime = now
        }

        // Shake Detection (Yaw change)
        val deltaYaw = Math.abs(yaw - lastEulerY)
        if (deltaYaw > 20.0f && now - lastShakeTime > 1000) {
            Log.i(TAG, "Head Shake Detected | DeltaYaw: $deltaYaw")
            listener.onShake()
            lastShakeTime = now
        }

        lastEulerX = pitch
        lastEulerY = yaw
    }
}

