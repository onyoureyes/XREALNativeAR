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

    // Screen log callback — all internal logs go here AND to logcat
    private var screenLogCallback: ((String) -> Unit)? = null
    fun setScreenLogCallback(callback: (String) -> Unit) { screenLogCallback = callback }
    private fun sLog(msg: String) {
        Log.i(TAG, msg)
        mainHandler.post { screenLogCallback?.invoke(msg) }
    }

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

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeFd = -1

    interface IMUListener {
        fun onOrientationUpdate(qx: Float, qy: Float, qz: Float, qw: Float)
    }
    var imuListener: IMUListener? = null
    fun setIMUListener(listener: IMUListener) { imuListener = listener }

    // ================================================================
    // USB Permission handling
    // The permission receiver handles BOTH MCU and OV580 devices.
    // It routes to the correct activation based on VID.
    // ================================================================
    private val usbPermissionReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        sLog("USB Permission GRANTED for VID=0x${String.format("%04X", device?.vendorId ?: 0)}")
                        device?.let { routeActivation(it) }
                    } else {
                        sLog("ERROR: USB Permission DENIED for device $device")
                    }
                }
            }
        }
    }

    private var lastOnReady: () -> Unit = {}
    private var usbConnection: android.hardware.usb.UsbDeviceConnection? = null
    private var ov580Connection: android.hardware.usb.UsbDeviceConnection? = null
    private var pendingMcuDevice: UsbDevice? = null

    // ================================================================
    // ARCHITECTURE: Nreal Light has TWO USB devices
    // ================================================================
    // 1. MCU (VID 0x0486, STMicroelectronics STM32F4)
    //    - Handles: display brightness, SBS mode, buttons, heartbeat
    //    - Protocol: ASCII text ("\x02:{type}:{cmd}:{data}:{timestamp}:{crc}:\x03")
    //    - The ":5:S:" packets we were seeing are MCU status, NOT IMU!
    //
    // 2. OV580 (VID 0x05A9, OmniVision Camera DSP)
    //    - Handles: stereo camera + IMU (yes, IMU is in the camera chip!)
    //    - IMU activation: send {0x02, 0x19, 0x01} to HID
    //    - IMU data: packets starting with 0x01
    //    - Command responses: packets starting with 0x02
    //
    // Source: https://voidcomputing.hu/blog/good-bad-ugly/
    //         "ImuDataProtocol_Generic_Ov580" in Nreal Mac SDK .dylib
    // ================================================================

    /**
     * Find and activate BOTH Nreal Light USB devices:
     * Priority: OV580 first (has the IMU), then MCU (display/heartbeat)
     */
    fun findAndActivate(onReady: () -> Unit) {
        lastOnReady = onReady
        val deviceList = usbManager.deviceList
        sLog("Scanning ${deviceList.size} USB devices...")

        var mcuDevice: UsbDevice? = null
        var ov580Device: UsbDevice? = null

        for ((name, device) in deviceList) {
            val vId = device.vendorId
            val pId = device.productId
            sLog("  USB: $name VID=0x${String.format("%04X", vId)} PID=0x${String.format("%04X", pId)}")
            if (vId == 0x0486) {
                mcuDevice = device
                sLog("  >>> MCU FOUND <<<")
            }
            if (vId == 0x05A9) {
                ov580Device = device
                sLog("  >>> OV580 FOUND (IMU is HERE!) <<<")
            }
        }

        if (ov580Device == null) {
            sLog("WARNING: OV580 not found! IMU unavailable.")
            if (mcuDevice != null) {
                sLog("MCU-only mode (no IMU)...")
                requestPermAndActivate(mcuDevice)
            } else {
                sLog("ERROR: No Nreal devices found!")
            }
            return
        }

        // Store MCU for later activation after OV580 is done
        pendingMcuDevice = mcuDevice
        
        // OV580 (has the IMU): Try direct open first, then permission request
        // IMPORTANT: OV580 is a UVC camera device. On Android 9+, CAMERA
        // runtime permission must be granted BEFORE USB permission dialog 
        // will appear for camera-class devices.
        sLog("Attempting direct OV580 open (skip permission dialog)...")
        try {
            val testConn = usbManager.openDevice(ov580Device)
            if (testConn != null) {
                sLog(">>> OV580 DIRECT OPEN SUCCESS! <<<")
                testConn.close()
                // Permission is implicitly granted, proceed to activation
                routeActivation(ov580Device)
                return
            } else {
                sLog("OV580 direct open returned null")
            }
        } catch (e: SecurityException) {
            sLog("OV580 direct open SecurityException: ${e.message}")
        } catch (e: Exception) {
            sLog("OV580 direct open error: ${e.message}")
        }
        
        // Direct open failed, try formal permission request
        sLog("Trying formal permission request for OV580...")
        requestPermAndActivate(ov580Device)
    }

    private fun requestPermAndActivate(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            sLog("Permission OK for VID=0x${String.format("%04X", device.vendorId)}")
            routeActivation(device)
        } else {
            sLog("Requesting permission for VID=0x${String.format("%04X", device.vendorId)}...")
            try {
                val filter = android.content.IntentFilter(ACTION_USB_PERMISSION)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(usbPermissionReceiver, filter)
                }
                val permissionIntent = PendingIntent.getBroadcast(
                    context, 0,
                    Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) },
                    PendingIntent.FLAG_MUTABLE
                )
                usbManager.requestPermission(device, permissionIntent)
                sLog("Permission dialog sent...")
            } catch (e: Exception) {
                sLog("Permission FAILED: ${e.message}")
            }
        }
    }

    /**
     * Route activation to the correct handler based on device VID
     */
    private fun routeActivation(device: UsbDevice) {
        when (device.vendorId) {
            0x05A9 -> {
                activateOV580IMU(device)
                // Also activate MCU if available
                val mcu = pendingMcuDevice
                if (mcu != null) {
                    pendingMcuDevice = null
                    requestPermAndActivate(mcu)
                } else {
                    lastOnReady()
                }
            }
            0x0486 -> {
                activateMCU(device, lastOnReady)
            }
            else -> {
                sLog("Unknown device VID=0x${String.format("%04X", device.vendorId)}")
            }
        }
    }

    // ================================================================
    // OV580 IMU ACTIVATION
    // This is the REAL IMU! It's embedded in the OV580 camera DSP.
    // ================================================================
    /**
     * Activate IMU data streaming from the OV580 camera DSP chip.
     *
     * Protocol (from voidcomputing.hu reverse engineering):
     * 1. Find HID interface on OV580
     * 2. Claim it
     * 3. Send magic 3 bytes: {0x02, 0x19, 0x01}
     *    - 0x02 = command packet type
     *    - 0x19 = "start IMU" command
     *    - 0x01 = enable
     * 4. Read loop: packets with byte[0]==0x01 are IMU events
     *
     * IMU packet format (from voidcomputing.hu):
     * - Byte 0: 0x01 (IMU event marker)
     * - Gyro/Accel/Temp data with multiplier/divisor fields
     * - All integers are signed little-endian
     */
    private fun activateOV580IMU(device: UsbDevice) {
        sLog(">>> OV580 IMU ACTIVATE START <<<")

        val connection = try {
            usbManager.openDevice(device)
        } catch (e: SecurityException) {
            sLog("SECURITY EXCEPTION opening OV580: ${e.message}")
            return
        }
        if (connection == null) {
            sLog("ERROR: Failed to open OV580 (null connection)")
            return
        }
        ov580Connection = connection
        sLog("OV580 Opened. FD: ${connection.fileDescriptor}")

        // Find HID interface (class 3) on OV580
        // OV580 has 3 interfaces:
        //   Interface 0: Class 14 (Video), IN 0x86 - camera control
        //   Interface 1: Class 14 (Video), IN 0x81 - camera stream
        //   Interface 2: Class 3 (HID), IN 0x89 (MaxPkt 128) - IMU data!
        // We MUST use Interface 2 (HID) for IMU
        var hidInterface: android.hardware.usb.UsbInterface? = null
        var endpointIn: android.hardware.usb.UsbEndpoint? = null
        var endpointOut: android.hardware.usb.UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            sLog("OV580 Iface $i: Class=${iface.interfaceClass}, Sub=${iface.interfaceSubclass}, EPs=${iface.endpointCount}")

            // Only look at HID class (class 3) interfaces for IMU
            if (iface.interfaceClass != 3) continue

            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                sLog("  Ep $j: addr=0x${String.format("%02X", ep.address)}, dir=${if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) "IN" else "OUT"}, type=${ep.type}, maxPkt=${ep.maxPacketSize}")

                if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN && endpointIn == null) {
                    endpointIn = ep
                }
                if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_OUT && endpointOut == null) {
                    endpointOut = ep
                }
            }

            if (endpointIn != null) {
                hidInterface = iface
                sLog(">>> OV580 HID FOUND: Iface ${iface.id}, IN=0x${String.format("%02X", endpointIn!!.address)}${if (endpointOut != null) ", OUT=0x${String.format("%02X", endpointOut!!.address)}" else " (no OUT)"} <<<")
                break
            }
        }

        if (hidInterface == null || endpointIn == null) {
            sLog("CRITICAL: No HID interface found on OV580!")
            return
        }

        val claimed = connection.claimInterface(hidInterface, true)
        sLog("Claim OV580 Interface: $claimed")

        // ================================================================
        // Send IMU activation command
        // ar-drivers-rs format: [0x02, cmd, subcmd, 0, 0, 0, 0] (7 bytes)
        // cmd=0x19 (IMU stream), subcmd=0x01 (enable)
        // Uses HID SET_REPORT control transfer (no OUT endpoint on OV580)
        // ================================================================
        val imuActivateCmd = byteArrayOf(0x02, 0x19, 0x01, 0x00, 0x00, 0x00, 0x00)

        sLog("Sending IMU activate via HID SET_REPORT (7 bytes)...")
        val ctrlResult = connection.controlTransfer(
            0x21,  // bmRequestType: host-to-device, class, interface
            0x09,  // bRequest: SET_REPORT
            0x0302, // wValue: Report Type=Feature(3), Report ID=2
            hidInterface.id,
            imuActivateCmd, imuActivateCmd.size, 500
        )
        sLog("OV580 controlTransfer result: $ctrlResult")

        // ================================================================
        // Start IMU read loop
        // Source: ar-drivers-rs parse_report() @ nreal_light.rs
        //
        // IMU packet (128 bytes, byte[0] == 0x01):
        //   Offset 44: Gyro timestamp (u64 LE, microseconds)
        //   Offset 52: Gyro multiplier (u32 LE)
        //   Offset 56: Gyro divisor (u32 LE)
        //   Offset 60: Gyro X (i32 LE)
        //   Offset 64: Gyro Y (i32 LE)
        //   Offset 68: Gyro Z (i32 LE)
        //   Offset 72: Accel timestamp (u64 LE)
        //   Offset 80: Accel multiplier (u32 LE)
        //   Offset 84: Accel divisor (u32 LE)
        //   Offset 88: Accel X (i32 LE)
        //   Offset 92: Accel Y (i32 LE)
        //   Offset 96: Accel Z (i32 LE)
        //
        // Gyro (rad/s): reading * mul / div * PI / 180
        // Accel (m/s²): reading * mul / div * 9.81
        // ================================================================
        val capturedEpIn = endpointIn
        val capturedConn = connection
        Thread {
            sLog(">>> OV580 IMU READ THREAD STARTED <<<")
            val buf = ByteArray(capturedEpIn.maxPacketSize.coerceAtLeast(128))
            var imuEventCount = 0
            var cmdResponseCount = 0

            // Madgwick AHRS filter for sensor fusion
            // beta=0.05 is a good balance: responsive but stable
            // sampleFreq=1000 matches OV580's ~1kHz output rate
            val ahrs = MadgwickAHRS(beta = 0.05f, sampleFreq = 1000f)
            sLog("AHRS Madgwick filter initialized (beta=0.05)")

            try {
                while (ov580Connection != null) {
                    val bytesRead = capturedConn.bulkTransfer(capturedEpIn, buf, buf.size, 200)
                    if (bytesRead <= 0) {
                        if (bytesRead < 0) Thread.sleep(10)
                        continue
                    }

                    when (buf[0].toInt() and 0xFF) {
                        0x01 -> {
                            imuEventCount++

                            // Need at least 100 bytes for full gyro+accel data
                            if (bytesRead >= 100) {
                                // Parse gyro block (offset 44)
                                val gyroTs = readU64LE(buf, 44)
                                val gyroMul = readU32LE(buf, 52).toFloat()
                                val gyroDivisor = readU32LE(buf, 56).toFloat()
                                val gyroX = readI32LE(buf, 60).toFloat()
                                val gyroY = readI32LE(buf, 64).toFloat()
                                val gyroZ = readI32LE(buf, 68).toFloat()

                                // Parse accel block (offset 72)
                                val accTs = readU64LE(buf, 72)
                                val accMul = readU32LE(buf, 80).toFloat()
                                val accDiv = readU32LE(buf, 84).toFloat()
                                val accX = readI32LE(buf, 88).toFloat()
                                val accY = readI32LE(buf, 92).toFloat()
                                val accZ = readI32LE(buf, 96).toFloat()

                                // Convert to physical units
                                val PI = 3.14159265f
                                val gyroScale = if (gyroDivisor != 0f) gyroMul / gyroDivisor else 0f
                                val accScale = if (accDiv != 0f) accMul / accDiv else 0f

                                // Apply axis corrections from ar-drivers-rs:
                                // Gyro: X as-is, Y negated, Z negated
                                // Accel: X as-is, Y negated, Z negated
                                val gx =  gyroX * gyroScale * PI / 180f  // rad/s
                                val gy = -(gyroY * gyroScale * PI / 180f)
                                val gz = -(gyroZ * gyroScale * PI / 180f)
                                val ax =  accX * accScale * 9.81f  // m/s²
                                val ay = -(accY * accScale * 9.81f)
                                val az = -(accZ * accScale * 9.81f)

                                // Feed into Madgwick AHRS filter
                                ahrs.update(gx, gy, gz, ax, ay, az, gyroTs)

                                // Log first 10 packets and every 500th for debugging
                                if (imuEventCount <= 10 || imuEventCount % 500 == 0) {
                                    val euler = ahrs.getEulerDegrees()
                                    sLog("IMU #$imuEventCount ts=${gyroTs/1000}ms")
                                    sLog("  Gyro: [%.4f, %.4f, %.4f] rad/s".format(gx, gy, gz))
                                    sLog("  Accel: [%.4f, %.4f, %.4f] m/s²".format(ax, ay, az))
                                    sLog("  Quat: [%.4f, %.4f, %.4f, %.4f]".format(
                                        ahrs.q0, ahrs.q1, ahrs.q2, ahrs.q3))
                                    sLog("  Euler: roll=%.1f° pitch=%.1f° yaw=%.1f°".format(
                                        euler[0], euler[1], euler[2]))
                                }

                                // Pass quaternion to listener (on main thread)
                                val qw = ahrs.q0
                                val qx = ahrs.q1
                                val qy = ahrs.q2
                                val qz = ahrs.q3
                                imuListener?.onOrientationUpdate(qx, qy, qz, qw)
                            } else {
                                // Short packet - log for debugging
                                if (imuEventCount <= 5) {
                                    val hexData = buf.take(bytesRead).joinToString(" ") { String.format("%02X", it) }
                                    sLog("IMU SHORT #$imuEventCount ($bytesRead bytes): $hexData")
                                }
                            }
                        }

                        0x02 -> {
                            cmdResponseCount++
                            if (cmdResponseCount <= 3) {
                                val hexData = buf.take(bytesRead.coerceAtMost(32)).joinToString(" ") { String.format("%02X", it) }
                                sLog("OV580 CMD RSP #$cmdResponseCount: $hexData")
                            }
                        }

                        else -> {
                            if (imuEventCount + cmdResponseCount < 5) {
                                sLog("OV580 UNK type=0x${String.format("%02X", buf[0])} ($bytesRead bytes)")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                sLog("OV580 IMU Thread Error: ${e.message}")
            }
            sLog("OV580 IMU Thread ended (imu=$imuEventCount, rsp=$cmdResponseCount)")
        }.start()

        sLog("IMU Started.")
    }

    // ================================================================
    // MCU ACTIVATION (Display, Buttons, Heartbeat)
    // This is the STM32 MCU - sends ASCII status packets
    // Protocol: "\x02:{type}:{cmd}:{data}:{timestamp:>8x}:{crc:>8x}:\x03"
    // ================================================================
    private fun activateMCU(device: UsbDevice, onReady: () -> Unit) {
        sLog(">>> MCU ACTIVATE START <<<")
        try {
            usbConnection = usbManager.openDevice(device)
        } catch (e: SecurityException) {
            sLog("SECURITY EXCEPTION opening MCU: ${e.message}")
            return
        }
        val connection = usbConnection
        if (connection == null) {
            sLog("ERROR: Failed to open MCU (null connection)")
            return
        }

        activeFd = connection.fileDescriptor
        sLog("MCU Opened. FD: $activeFd")

        // Find HID interface
        var mcuInterface: android.hardware.usb.UsbInterface? = null
        var endpointIn: android.hardware.usb.UsbEndpoint? = null
        var endpointOut: android.hardware.usb.UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == 3) {
                var epIn: android.hardware.usb.UsbEndpoint? = null
                var epOut: android.hardware.usb.UsbEndpoint? = null
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN && epIn == null) epIn = ep
                    if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_OUT && epOut == null) epOut = ep
                }
                if (epIn != null && epOut != null) {
                    mcuInterface = iface
                    endpointIn = epIn
                    endpointOut = epOut
                    break
                }
            }
        }

        if (mcuInterface == null || endpointIn == null || endpointOut == null) {
            sLog("MCU: No HID interface found")
            onReady()
            return
        }

        connection.claimInterface(mcuInterface, true)
        sLog("MCU Interface claimed")

        // Send original magic payload (enricoros format for MCU)
        val magicPayload = byteArrayOf(
            0xAA.toByte(), 0xC5.toByte(), 0xD1.toByte(), 0x21.toByte(),
            0x42.toByte(), 0x04.toByte(), 0x00.toByte(), 0x19.toByte(), 0x01.toByte()
        )
        val sendResult = connection.bulkTransfer(endpointOut, magicPayload, magicPayload.size, 200)
        sLog("MCU magic sent: $sendResult")

        // MCU read thread (for status/heartbeat monitoring)
        val capturedEpIn = endpointIn
        Thread {
            sLog("MCU Read Thread started")
            val buf = ByteArray(64)
            var count = 0
            try {
                while (usbConnection != null) {
                    val bytesRead = connection.bulkTransfer(capturedEpIn, buf, 64, 200)
                    if (bytesRead > 0) {
                        count++
                        // Only log first few MCU packets and periodically
                        if (count <= 3 || count % 500 == 0) {
                            val ascii = String(buf, 1, bytesRead - 1, Charsets.US_ASCII)
                                .replace(Char(0), ' ').trimEnd()
                            sLog("MCU #$count: $ascii")
                        }
                    } else if (bytesRead < 0) {
                        Thread.sleep(50)
                    }
                }
            } catch (e: Exception) {
                sLog("MCU Thread Error: ${e.message}")
            }
            sLog("MCU Thread ended ($count pkts)")
        }.start()

        val result = nativeActivate(activeFd)
        sLog("nativeActivate: $result")
        onReady()
    }

    // Legacy activate function (redirects to activateMCU)
    private fun activate(device: UsbDevice, onReady: () -> Unit) {
        activateMCU(device, onReady)
    }

    /**
     * Read a 24-bit signed integer from a byte array (little-endian).
     */
    private fun read24bitSigned(buf: ByteArray, offset: Int): Int {
        val raw = (buf[offset].toInt() and 0xFF) or
                  ((buf[offset + 1].toInt() and 0xFF) shl 8) or
                  ((buf[offset + 2].toInt() and 0xFF) shl 16)
        return if ((raw and 0x800000) != 0) raw or (0xFF shl 24) else raw
    }

    fun scanAndLogDeviceDetails(logCallback: (String) -> Unit) {
        val deviceList = usbManager.deviceList
        logCallback("=== Deep USB Scan: ${deviceList.size} devices ===")

        for ((name, device) in deviceList) {
            logCallback("--- Device: $name ---")
            logCallback("  VID: ${device.vendorId} (0x${String.format("%04X", device.vendorId)})")
            logCallback("  PID: ${device.productId} (0x${String.format("%04X", device.productId)})")
            logCallback("  Class: ${device.deviceClass}")
            logCallback("  Subclass: ${device.deviceSubclass}")
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
                    logCallback("      Address: 0x${String.format("%02X", ep.address)}")
                    logCallback("      Direction: ${if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) "IN" else "OUT"}")
                    logCallback("      Type: ${ep.type}")
                    logCallback("      MaxPacket: ${ep.maxPacketSize}")
                    logCallback("      Interval: ${ep.interval}")
                }
            }
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
        ov580Connection?.close()
        ov580Connection = null
        activeFd = -1
    }

    // Called from Native code via JNI
    fun onNativeIMU(qx: Float, qy: Float, qz: Float, qw: Float) {
        mainHandler.post {
            imuListener?.onOrientationUpdate(qx, qy, qz, qw)
        }
    }

    // ================================================================
    // Little-Endian byte readers for IMU packet parsing
    // ================================================================
    private fun readU64LE(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or ((buf[off + i].toLong()) and 0xFF)
        return v
    }

    private fun readU32LE(buf: ByteArray, off: Int): Long {
        return ((buf[off].toLong() and 0xFF)) or
               ((buf[off + 1].toLong() and 0xFF) shl 8) or
               ((buf[off + 2].toLong() and 0xFF) shl 16) or
               ((buf[off + 3].toLong() and 0xFF) shl 24)
    }

    private fun readI32LE(buf: ByteArray, off: Int): Int {
        return ((buf[off].toInt() and 0xFF)) or
               ((buf[off + 1].toInt() and 0xFF) shl 8) or
               ((buf[off + 2].toInt() and 0xFF) shl 16) or
               ((buf[off + 3].toInt()) shl 24)
    }
}
