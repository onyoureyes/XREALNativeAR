package com.xreal.hardware

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * Handles USB device discovery and permission management for Nreal Light.
 *
 * Responsibilities:
 *   - Scan for Nreal devices (MCU, OV580, Audio)
 *   - Request USB permissions (with direct-open fallback)
 *   - Open device connections
 *   - Route activated devices to callback
 *
 * Knows nothing about OV580/MCU protocols — only USB plumbing.
 */
class USBDeviceRouter(
    private val context: Context,
    private val log: (String) -> Unit
) {
    private val ACTION_USB_PERMISSION = "com.xreal.hardware.USB_PERMISSION"
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var onDeviceReady: ((NrealDeviceType, UsbDevice, UsbDeviceConnection) -> Unit)? = null
    private var pendingDevices = mutableListOf<UsbDevice>()

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        log("USB Permission GRANTED for VID=0x${"%04X".format(device?.vendorId ?: 0)}")
                        device?.let { openAndDeliver(it) }
                    } else {
                        log("USB Permission DENIED for $device")
                    }
                }
            }
        }
    }

    /**
     * Scan for Nreal Light USB devices, request permissions, and deliver
     * opened connections via [onReady] callback.
     *
     * @param onReady Called for each device once permitted and opened.
     *                Receives (deviceType, device, connection).
     */
    fun scanAndOpen(onReady: (NrealDeviceType, UsbDevice, UsbDeviceConnection) -> Unit) {
        this.onDeviceReady = onReady
        val deviceList = usbManager.deviceList
        log("Scanning ${deviceList.size} USB devices...")

        // Categorize devices
        val found = mutableMapOf<NrealDeviceType, UsbDevice>()
        for ((name, device) in deviceList) {
            val vid = device.vendorId
            log("  USB: $name VID=0x${"%04X".format(vid)} PID=0x${"%04X".format(device.productId)}")
            val type = NrealDeviceType.identify(vid)
            if (type != null) {
                found[type] = device
                log("  >>> ${type.description} FOUND <<<")
            }
        }

        if (found.isEmpty()) {
            log("ERROR: No Nreal devices found!")
            return
        }

        // Priority: OV580 first (has IMU), then MCU
        val ordered = listOf(NrealDeviceType.OV580, NrealDeviceType.MCU)
        for (type in ordered) {
            val device = found[type] ?: continue
            pendingDevices.add(device)
        }

        // Process first pending device
        processNextPending()
    }

    private fun processNextPending() {
        if (pendingDevices.isEmpty()) return
        val device = pendingDevices.removeAt(0)
        tryOpenDevice(device)
    }

    private fun tryOpenDevice(device: UsbDevice) {
        val type = NrealDeviceType.identify(device.vendorId) ?: return
        log("Opening ${type.name}...")

        // Try direct open first
        try {
            val conn = usbManager.openDevice(device)
            if (conn != null) {
                log("${type.name} direct open SUCCESS")
                onDeviceReady?.invoke(type, device, conn)
                processNextPending()
                return
            }
        } catch (e: SecurityException) {
            log("${type.name} direct open: SecurityException")
        } catch (e: Exception) {
            log("${type.name} direct open error: ${e.message}")
        }

        // Formal permission request
        requestPermission(device)
    }

    private fun requestPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            openAndDeliver(device)
            return
        }

        log("Requesting USB permission...")
        try {
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(usbPermissionReceiver, filter)
            }
            val pi = PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) },
                PendingIntent.FLAG_MUTABLE
            )
            usbManager.requestPermission(device, pi)
            log("Permission dialog sent...")
        } catch (e: Exception) {
            log("Permission FAILED: ${e.message}")
        }
    }

    private fun openAndDeliver(device: UsbDevice) {
        val type = NrealDeviceType.identify(device.vendorId) ?: return
        try {
            val conn = usbManager.openDevice(device)
            if (conn != null) {
                onDeviceReady?.invoke(type, device, conn)
            } else {
                log("ERROR: Failed to open ${type.name}")
            }
        } catch (e: Exception) {
            log("ERROR opening ${type.name}: ${e.message}")
        }
        processNextPending()
    }

    /**
     * Log detailed info about all connected USB devices.
     */
    fun scanAndLogDetails(logCallback: (String) -> Unit) {
        val deviceList = usbManager.deviceList
        logCallback("=== Deep USB Scan: ${deviceList.size} devices ===")
        for ((_, device) in deviceList) {
            logCallback("Device: ${device.deviceName}")
            logCallback("  VID: 0x${"%04X".format(device.vendorId)} PID: 0x${"%04X".format(device.productId)}")
            logCallback("  Class: ${device.deviceClass}")
            logCallback("  Protocol: ${device.deviceProtocol}")
            logCallback("  Product: ${device.productName}")
            logCallback("  Manufacturer: ${device.manufacturerName}")
            logCallback("  Interfaces: ${device.interfaceCount}")

            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                logCallback("  Interface $i:")
                logCallback("    ID: ${iface.id}")
                logCallback("    Class: ${iface.interfaceClass}")
                logCallback("    Subclass: ${iface.interfaceSubclass}")
                logCallback("    Protocol: ${iface.interfaceProtocol}")
                logCallback("    Endpoints: ${iface.endpointCount}")
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    logCallback("    Endpoint $j:")
                    logCallback("      Address: 0x${"%02X".format(ep.address)}")
                    logCallback("      Direction: ${if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) "IN" else "OUT"}")
                    logCallback("      Type: ${ep.type}")
                    logCallback("      MaxPacket: ${ep.maxPacketSize}")
                }
            }
        }
    }

    fun release() {
        try {
            context.unregisterReceiver(usbPermissionReceiver)
        } catch (_: Exception) {}
        onDeviceReady = null
        pendingDevices.clear()
        log("USBDeviceRouter released")
    }
}
