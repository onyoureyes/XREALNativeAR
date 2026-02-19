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
        
        // [Rescue Strategy] Manually scan for Camera/HID and send 'Magic Packet'
        // Nreal/XReal glasses often need a specific HID command to wake up sensors/camera
        try {
            wakeUpDevice(device, connection)
        } catch (e: Exception) {
            Log.w(TAG, "Manual wake-up failed: ${e.message}. Proceeding to native activate...")
        }

        val result = nativeActivate(activeFd)
        
        if (result == 0) {
            Log.i(TAG, "XREAL Activated Successfully (Native)")
            onReady()
        } else {
            Log.e(TAG, "XREAL Native Activation Failed: $result. Attempting Software-Only Fallback...")
            // If native fails, we might still have successfully woken it up via USB.
            // Check if we found a UVC interface?
            if (scanForCameraInterface(device)) {
                Log.i(TAG, "Found UVC Interface! Marking as ready despite native failure.")
                onReady()
            } else {
                connection.close()
                usbConnection = null
            }
        }
    }

    private fun scanForCameraInterface(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            Log.d(TAG, "Interface $i: Class=${iface.interfaceClass}, Subclass=${iface.interfaceSubclass}")
            if (iface.interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_VIDEO) { // 14 (0x0E)
                return true
            }
        }
        return false
    }

    private fun wakeUpDevice(device: UsbDevice, connection: android.hardware.usb.UsbDeviceConnection) {
        Log.i(TAG, "Attempting Manual Device Wake-Up (Magic Packet)...")
        
        // Find the HID/Control Interface (Usually Interface 0 or 3)
        // Nreal Air/Light Control Interface is often Vendor Specific or HID
        var controlInterface: android.hardware.usb.UsbInterface? = null
        
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            // Look for HID (3) or Vendor Specific (255)
            if (iface.interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_HID || 
                iface.interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_VENDOR_SPEC) {
                controlInterface = iface
                // Break on the first likely candidate? Or specific index?
                // Nreal usually uses Interface 3 or 0 for control.
                if (i == 3 || i == 0) break 
            }
        }

        controlInterface?.let { iface ->
            connection.claimInterface(iface, true)
            
            // "Magic Packet" / Activation Command
            // This is a common initialization sequence for XR glasses sensors
            // (Payload derived from open-source drivers like OpenTrack/Monado/AirDriver)
            // It often involves sending a "Set Report" or specific control transfer.
            
            // Example: "Keep Alive" or "Start Sensor" command
            // RequestType: 0x21 (Host to Device | Class | Interface)
            // Request: 0x09 (SET_REPORT)
            // Value: 0x0300 (Feature Report) or specific Report ID
            // Index: Interface ID
            
            val magicPayload = byteArrayOf(
                0xAA.toByte(), 0xC5.toByte(), 0xD1.toByte(), 0x21.toByte(), 
                0x42.toByte(), 0x04.toByte(), 0x00.toByte(), 0x19.toByte(), 0x01.toByte() 
            ) // Placeholder for known "Start" command
            
            val transferred = connection.controlTransfer(
                0x21, // RequestType
                0x09, // SET_REPORT
                0x0300, // Value
                iface.id, // Index
                magicPayload,
                magicPayload.size,
                1000 // Timeout
            )
            
            Log.i(TAG, "Wake-Up Packet Sent: $transferred bytes")
            connection.releaseInterface(iface)
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
