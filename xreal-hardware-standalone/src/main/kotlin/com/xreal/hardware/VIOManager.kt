package com.xreal.hardware

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Kotlin wrapper for the OpenVINS 6-DoF VIO pipeline.
 *
 * Lifecycle: init → start → feedIMU / feedStereoFrame → stop → release
 *
 * Pose updates are delivered on the main thread via [PoseListener].
 */
class VIOManager(private val log: (String) -> Unit) {

    companion object {
        private const val TAG = "VIOManager"
        init { System.loadLibrary("xreal-hardware") }
    }

    // ── JNI declarations ──
    private external fun nativeInit(): Int
    private external fun nativeFeedIMU(gx: Float, gy: Float, gz: Float,
                                       ax: Float, ay: Float, az: Float,
                                       timestampUs: Long)
    private external fun nativeFeedStereoFrame(left: ByteArray, right: ByteArray,
                                                width: Int, height: Int,
                                                timestampUs: Long)
    private external fun nativeStart()
    private external fun nativeStop()
    private external fun nativeRelease()
    private external fun nativeIsInitialized(): Boolean

    // ── Public listener interface ──
    interface PoseListener {
        fun onPoseUpdate(x: Float, y: Float, z: Float,
                         qx: Float, qy: Float, qz: Float, qw: Float,
                         timestamp: Double)
    }

    var poseListener: PoseListener? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRunning = false

    fun init(): Boolean {
        log("VIOManager.init()")
        val result = nativeInit()
        if (result != 0) {
            log("VIOManager.init() FAILED: $result")
            return false
        }
        log("VIOManager.init() OK")
        return true
    }

    fun start() {
        if (isRunning) return
        log("VIOManager.start()")
        nativeStart()
        isRunning = true
    }

    fun stop() {
        if (!isRunning) return
        log("VIOManager.stop()")
        nativeStop()
        isRunning = false
    }

    fun release() {
        stop()
        nativeRelease()
        log("VIOManager released")
    }

    fun isInitialized(): Boolean = nativeIsInitialized()

    /**
     * Feed raw IMU measurement.
     * @param gx,gy,gz Gyroscope (rad/s)
     * @param ax,ay,az Accelerometer (m/s²)
     * @param timestampUs Device timestamp in microseconds
     */
    fun feedIMU(gx: Float, gy: Float, gz: Float,
                ax: Float, ay: Float, az: Float,
                timestampUs: Long) {
        if (!isRunning) return
        nativeFeedIMU(gx, gy, gz, ax, ay, az, timestampUs)
    }

    /**
     * Feed pre-rectified stereo grayscale frames.
     * @param left,right Grayscale pixel data
     * @param width,height Image dimensions
     * @param timestampUs Device timestamp in microseconds
     */
    fun feedStereoFrame(left: ByteArray, right: ByteArray,
                        width: Int, height: Int,
                        timestampUs: Long) {
        if (!isRunning) return
        nativeFeedStereoFrame(left, right, width, height, timestampUs)
    }

    /**
     * Called from native code (C++ vio_jni.cpp) when a new pose is available.
     * Signature must match JNI: (FFFFFFFD)V
     */
    @Suppress("unused") // Called from JNI
    fun onNativePose(x: Float, y: Float, z: Float,
                     qx: Float, qy: Float, qz: Float, qw: Float,
                     timestamp: Double) {
        mainHandler.post {
            poseListener?.onPoseUpdate(x, y, z, qx, qy, qz, qw, timestamp)
        }
    }
}
