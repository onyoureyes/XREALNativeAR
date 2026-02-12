package com.xreal.nativear.nrsdk

/**
 * XRealPose: Data class representing the 6DoF pose of the AR glasses.
 */
data class XRealPose(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val qx: Float = 0f,
    val qy: Float = 0f,
    val qz: Float = 0f,
    val qw: Float = 1f,
    val timestamp: Long = System.currentTimeMillis()
)
