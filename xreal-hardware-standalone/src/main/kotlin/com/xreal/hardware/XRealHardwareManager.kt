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

    private val usbPermissionReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { activate(it, lastOnReady) }
                    } else {
                        Log.e(TAG, "Permission denied for device $device")
                    }
                }
            }
        }
    }

    private var lastOnReady: () -> Unit = {}
    private var usbConnection: android.hardware.usb.UsbDeviceConnection? = null

    fun findAndActivate(onReady: () -> Unit) {
        lastOnReady = onReady
        val deviceList = usbManager.deviceList
        // Support Nreal Light (0x5731) and Nreal Air (0x5732)
        val device = deviceList.values.find { 
            it.vendorId == 0x0486 && (it.productId == 0x5731 || it.productId == 0x5732)
        }
        
        if (device == null) {
            Log.e(TAG, "XREAL device not found!")
            return
        }

        if (usbManager.hasPermission(device)) {
            activate(device, onReady)
        } else {
            val filter = android.content.IntentFilter(ACTION_USB_PERMISSION)
            context.registerReceiver(usbPermissionReceiver, filter)
            
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
        usbConnection = usbManager.openDevice(device)
        val connection = usbConnection
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
            connection.close() // Close if activation fails
            usbConnection = null
        }
    }

    fun isActivated(): Boolean = activeFd != -1

    fun startCamera(surface: Surface) {
        if (!isActivated()) return
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

    fun release() {
        try {
            context.unregisterReceiver(usbPermissionReceiver)
        } catch (e: Exception) {
            // Might not be registered
        }
        stopIMU()
        stopCamera()
        usbConnection?.close()
        usbConnection = null
        activeFd = -1
    }

    // Called from Native code via JNI
    fun onNativeIMU(qx: Float, qy: Float, qz: Float, qw: Float) {
        mainHandler.post {
            imuListener?.onOrientationUpdate(qx, qy, qz, qw)
        }
    }
}
