package com.xreal.hardware

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface

/**
 * Thin facade for Nreal Light hardware — delegates to focused managers.
 *
 * Architecture:
 *   XRealHardwareManager (this class)
 *     ├── USBDeviceRouter  → permission & discovery
 *     ├── OV580Manager     → IMU + SLAM camera
 *     └── MCUManager       → display, buttons, heartbeat
 */
class XRealHardwareManager(private val context: Context) {
    private val TAG = "XRealHardwareManager"
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Logging ──
    private var screenLogCallback: ((String) -> Unit)? = null
    fun setScreenLogCallback(callback: (String) -> Unit) { screenLogCallback = callback }
    private fun sLog(msg: String) {
        Log.i(TAG, msg)
        mainHandler.post { screenLogCallback?.invoke(msg) }
    }

    // ── Native JNI bridge (for MCU display driver) ──
    companion object {
        init { System.loadLibrary("xreal-hardware") }
    }
    private external fun nativeActivate(fd: Int): Int
    private external fun nativeStartCamera(surface: Surface): Int
    private external fun nativeStopCamera()
    private external fun nativeStartIMU(fd: Int): Int
    private external fun nativeStopIMU()

    // ── Sub-managers ──
    private val router = USBDeviceRouter(context, ::sLog)
    private var ov580: OV580Manager? = null
    private var mcu: MCUManager? = null
    private var activeFd = -1

    // Connections (kept for lifecycle management)
    private var ov580Connection: UsbDeviceConnection? = null
    private var mcuConnection: UsbDeviceConnection? = null

    // ── IMU Listener (delegate to OV580Manager) ──
    interface IMUListener {
        fun onOrientationUpdate(qx: Float, qy: Float, qz: Float, qw: Float)
    }
    var imuListener: IMUListener? = null
    fun setIMUListener(listener: IMUListener) { imuListener = listener }

    // ── SLAM Camera Listener ──
    var slamCameraListener: OV580SlamCamera.SlamFrameListener? = null

    // ── Main Entry Point ──

    fun findAndActivate(onReady: () -> Unit) {
        sLog("=== XRealHardwareManager findAndActivate ===")

        router.scanAndOpen { type, device, connection ->
            sLog(">>> Device delivered: ${type.name} <<<")

            when (type) {
                NrealDeviceType.OV580 -> {
                    sLog("Creating OV580Manager...")
                    ov580Connection = connection
                    val mgr = OV580Manager(device, connection, ::sLog)
                    mgr.imuListener = imuListener?.let { listener ->
                        object : OV580Manager.IMUListener {
                            override fun onOrientationUpdate(qx: Float, qy: Float, qz: Float, qw: Float) {
                                listener.onOrientationUpdate(qx, qy, qz, qw)
                            }
                        }
                    }
                    mgr.slamCameraListener = slamCameraListener
                    mgr.activateIMU()
                    ov580 = mgr
                    sLog("OV580Manager ready (ov580 != null: ${ov580 != null})")
                }

                NrealDeviceType.MCU -> {
                    sLog("Creating MCUManager...")
                    mcuConnection = connection
                    activeFd = connection.fileDescriptor
                    sLog("MCU FD: $activeFd")
                    val result = nativeActivate(activeFd)
                    sLog("nativeActivate: $result")

                    val mcuMgr = MCUManager(device, connection, ::sLog)
                    mcuMgr.activate {
                        sLog("MCU activate callback fired")
                    }
                    mcu = mcuMgr
                }

                NrealDeviceType.AUDIO -> {
                    sLog("Audio device found (not managed)")
                    connection.close()
                }
            }
        }

        // Call onReady after scanAndOpen has processed all devices synchronously
        // The router processes sequentially: OV580 first, then MCU
        sLog("findAndActivate complete. OV580=${ov580 != null}, MCU=${mcu != null}")
        onReady()
    }

    // ── Public API (delegates) ──

    fun isActivated(): Boolean = activeFd != -1 || ov580 != null

    fun startCamera(surface: Surface) {
        if (activeFd == -1) return
        nativeStartCamera(surface)
    }

    fun stopCamera() = nativeStopCamera()

    fun startIMU() {
        if (activeFd != -1) nativeStartIMU(activeFd)
    }

    fun stopIMU() = nativeStopIMU()

    fun startSlamCamera() {
        val mgr = ov580
        if (mgr == null) {
            sLog("SLAM: OV580 not activated yet! Call 'Find & Activate XREAL' first.")
            return
        }
        mgr.slamCameraListener = slamCameraListener
        mgr.startCamera()
    }

    fun stopSlamCamera() {
        ov580?.stopCamera()
    }

    fun scanAndLogDeviceDetails(logCallback: (String) -> Unit) {
        router.scanAndLogDetails(logCallback)
    }

    // ── Lifecycle ──

    fun release() {
        ov580?.release()
        ov580 = null
        mcu?.release()
        mcu = null
        router.release()

        stopIMU()
        stopCamera()

        mcuConnection?.close()
        mcuConnection = null
        ov580Connection?.close()
        ov580Connection = null
        activeFd = -1

        sLog("XRealHardwareManager released")
    }

    // Called from Native code via JNI
    fun onNativeIMU(qx: Float, qy: Float, qz: Float, qw: Float) {
        mainHandler.post {
            imuListener?.onOrientationUpdate(qx, qy, qz, qw)
        }
    }
}
