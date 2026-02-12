package com.xreal.hardware

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface

class XRealHardwareManager(private val context: Context) {
    private val TAG = "XRealHardwareManager"
    private val ACTION_USB_PERMISSION = "com.xreal.hardware.USB_PERMISSION"
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    companion object {
        init {
            System.loadLibrary("xreal-hardware")
        }
    }

    private external fun nativeActivate(fd: Int): Int
    private external fun nativeStartCamera(surface: Surface): Int
    private external fun nativeStopCamera()
    private external fun nativeStartIMU(fd: Int): Int
    private external fun nativeStopIMU()

    fun findAndActivate(onReady: () -> Unit) {
        val deviceList = usbManager.deviceList
        val device = deviceList.values.find { it.vendorId == 0x0486 } // Nreal Light
        
        if (device == null) {
            Log.e(TAG, "Nreal Light not found!")
            return
        }

        if (usbManager.hasPermission(device)) {
            activate(device, onReady)
        } else {
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private var activeFd: Int = -1
    private var imuListener: IMUListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    interface IMUListener {
        fun onOrientationUpdate(qx: Float, qy: Float, qz: Float, qw: Float)
    }

    fun setIMUListener(listener: IMUListener) {
        this.imuListener = listener
    }

    private fun activate(device: UsbDevice, onReady: () -> Unit) {
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.e(TAG, "Failed to open USB device")
            return
        }

        activeFd = connection.fileDescriptor
        val result = nativeActivate(activeFd)
        
        if (result == 0) {
            Log.i(TAG, "XREAL Activated Successfully")
            onReady()
        } else {
            Log.e(TAG, "XREAL Activation Failed: $result")
        }
        // Connection stays open or closed depending on if we need HID data, 
        // but for magic packet we can close if camera doesn't need it.
        // Usually, the glasses stay active once packet is sent.
    }

    fun startCamera(surface: Surface) {
        nativeStartCamera(surface)
    }

    fun stopCamera() {
        nativeStopCamera()
    }

    fun startIMU() {
        if (activeFd != -1) {
            nativeStartIMU(activeFd)
        }
    }

    fun stopIMU() {
        nativeStopIMU()
    }

    // Called from Native code via JNI
    fun onNativeIMU(qx: Float, qy: Float, qz: Float, qw: Float) {
        mainHandler.post {
            imuListener?.onOrientationUpdate(qx, qy, qz, qw)
        }
    }
}
