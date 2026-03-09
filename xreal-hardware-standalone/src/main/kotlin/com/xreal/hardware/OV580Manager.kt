package com.xreal.hardware

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/**
 * Manages the OV580 camera DSP chip on Nreal Light.
 *
 * Responsibilities:
 *   - IMU activation via HID interface (Interface 2, class 3)
 *   - IMU data parsing at 1kHz (gyro + accel → quaternion via Madgwick AHRS)
 *   - SLAM camera control (delegated to OV580SlamCamera)
 *
 * Takes an already-opened UsbDeviceConnection (managed by XRealHardwareManager).
 */
class OV580Manager(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val log: (String) -> Unit
) {
    interface IMUListener {
        fun onOrientationUpdate(qx: Float, qy: Float, qz: Float, qw: Float)
    }

    /** Raw IMU data listener for VIO pipeline (pre-AHRS, calibrated SI units). */
    interface RawIMUListener {
        fun onRawIMU(gx: Float, gy: Float, gz: Float,
                     ax: Float, ay: Float, az: Float,
                     timestampUs: Long)
    }

    var imuListener: IMUListener? = null
    var rawImuListener: RawIMUListener? = null

    @Volatile private var imuRunning = false
    private var imuThread: Thread? = null
    private var slamCamera: OV580SlamCamera? = null
    var slamCameraListener: OV580SlamCamera.SlamFrameListener? = null

    /**
     * Activate IMU streaming from the OV580's HID interface.
     *
     * Protocol (from ar-drivers-rs):
     * 1. Find HID interface (class 3) → Interface 2
     * 2. Claim it
     * 3. Send 7-byte command via HID SET_REPORT: [0x02, 0x19, 0x01, 0, 0, 0, 0]
     * 4. Bulk read from IN endpoint (0x89), parse IMU packets (byte[0] == 0x01)
     */
    fun activateIMU() {
        log(">>> OV580 IMU ACTIVATE <<<")

        // ── Find HID interface (class 3) ──
        // OV580 interfaces:
        //   0: Class 14 (Video Control), IN 0x86
        //   1: Class 14 (Video Streaming), IN 0x81 — SLAM cameras
        //   2: Class 3 (HID), IN 0x89 (MaxPkt 128) — IMU data
        var hidInterface: UsbInterface? = null
        var endpointIn: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            log("OV580 Iface $i: Class=${iface.interfaceClass}, Sub=${iface.interfaceSubclass}")
            if (iface.interfaceClass != 3) continue // HID only

            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                log("  Ep $j: 0x${"%02X".format(ep.address)} ${if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"} maxPkt=${ep.maxPacketSize}")
                if (ep.direction == UsbConstants.USB_DIR_IN && endpointIn == null) {
                    endpointIn = ep
                }
            }

            if (endpointIn != null) {
                hidInterface = iface
                log(">>> HID FOUND: Iface ${iface.id}, IN=0x${"%02X".format(endpointIn!!.address)} <<<")
                break
            }
        }

        if (hidInterface == null || endpointIn == null) {
            log("CRITICAL: No HID interface on OV580!")
            return
        }

        // ── Claim interface ──
        if (!connection.claimInterface(hidInterface, true)) {
            log("ERROR: Failed to claim HID interface!")
            return
        }
        log("HID interface claimed")

        // ── Send IMU activation command ──
        // ar-drivers-rs format: [0x02, cmd, subcmd, 0, 0, 0, 0]
        // cmd=0x19 (IMU stream), subcmd=0x01 (enable)
        val cmd = byteArrayOf(0x02, 0x19, 0x01, 0x00, 0x00, 0x00, 0x00)
        val result = connection.controlTransfer(
            0x21,   // bmRequestType: host-to-device, class, interface
            0x09,   // bRequest: SET_REPORT
            0x0302, // wValue: Report Type=Feature(3), Report ID=2
            hidInterface.id, cmd, cmd.size, 500
        )
        log("IMU activate SET_REPORT: $result")

        // ── Start IMU read thread ──
        imuRunning = true
        val capturedEp = endpointIn
        imuThread = Thread {
            log(">>> IMU READ THREAD STARTED <<<")
            val buf = ByteArray(capturedEp.maxPacketSize.coerceAtLeast(128))
            var eventCount = 0
            var cmdRspCount = 0
            val ahrs = MadgwickAHRS(beta = 0.05f, sampleFreq = 1000f)
            log("AHRS Madgwick initialized (beta=0.05)")

            try {
                while (imuRunning) {
                    val n = connection.bulkTransfer(capturedEp, buf, buf.size, 200)
                    if (n <= 0) { if (n < 0) Thread.sleep(10); continue }

                    when (buf[0].toInt() and 0xFF) {
                        0x01 -> {
                            eventCount++
                            if (n >= 100) parseAndFuseIMU(buf, eventCount, ahrs)
                        }
                        0x02 -> {
                            cmdRspCount++
                            if (cmdRspCount <= 3) {
                                val hex = buf.take(n.coerceAtMost(32)).joinToString(" ") { "%02X".format(it) }
                                log("CMD RSP #$cmdRspCount: $hex")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log("IMU Thread Error: ${e.message}")
            }
            log("IMU Thread ended (imu=$eventCount, rsp=$cmdRspCount)")
        }
        imuThread?.name = "OV580-IMU"
        imuThread?.start()
        log("IMU Started")
    }

    /**
     * Parse a single IMU packet and feed into AHRS filter.
     *
     * Packet layout (from ar-drivers-rs parse_report()):
     *   Offset 44: Gyro  — timestamp(u64) mul(u32) div(u32) X/Y/Z(i32)
     *   Offset 72: Accel — timestamp(u64) mul(u32) div(u32) X/Y/Z(i32)
     */
    private fun parseAndFuseIMU(buf: ByteArray, count: Int, ahrs: MadgwickAHRS) {
        val gyroTs = readU64LE(buf, 44)
        val gyroMul = readU32LE(buf, 52).toFloat()
        val gyroDiv = readU32LE(buf, 56).toFloat()
        val gyroX = readI32LE(buf, 60).toFloat()
        val gyroY = readI32LE(buf, 64).toFloat()
        val gyroZ = readI32LE(buf, 68).toFloat()

        val accMul = readU32LE(buf, 80).toFloat()
        val accDiv = readU32LE(buf, 84).toFloat()
        val accX = readI32LE(buf, 88).toFloat()
        val accY = readI32LE(buf, 92).toFloat()
        val accZ = readI32LE(buf, 96).toFloat()

        val PI = 3.14159265f
        val gScale = if (gyroDiv != 0f) gyroMul / gyroDiv else 0f
        val aScale = if (accDiv != 0f) accMul / accDiv else 0f

        // Axis corrections from ar-drivers-rs: Y/Z negated
        val gx =  gyroX * gScale * PI / 180f
        val gy = -(gyroY * gScale * PI / 180f)
        val gz = -(gyroZ * gScale * PI / 180f)
        val ax =  accX * aScale * 9.81f
        val ay = -(accY * aScale * 9.81f)
        val az = -(accZ * aScale * 9.81f)

        ahrs.update(gx, gy, gz, ax, ay, az, gyroTs)

        // Forward raw calibrated IMU to VIO pipeline
        rawImuListener?.onRawIMU(gx, gy, gz, ax, ay, az, gyroTs)

        // Periodic logging
        if (count <= 10 || count % 500 == 0) {
            val e = ahrs.getEulerDegrees()
            log("IMU #$count ts=${gyroTs / 1000}ms")
            log("  Gyro: [%.4f, %.4f, %.4f] rad/s".format(gx, gy, gz))
            log("  Accel: [%.4f, %.4f, %.4f] m/s²".format(ax, ay, az))
            log("  Quat: [%.4f, %.4f, %.4f, %.4f]".format(ahrs.q0, ahrs.q1, ahrs.q2, ahrs.q3))
            log("  Euler: R=%.1f° P=%.1f° Y=%.1f°".format(e[0], e[1], e[2]))
        }

        imuListener?.onOrientationUpdate(ahrs.q1, ahrs.q2, ahrs.q3, ahrs.q0)
    }

    // ── SLAM Camera ──

    fun startCamera() {
        if (slamCamera != null) { log("SLAM Camera already running"); return }
        slamCamera = OV580SlamCamera { msg -> log("[SLAM] $msg") }
        slamCamera?.listener = slamCameraListener
        slamCamera?.start(device, connection)
        log("SLAM Camera started")
    }

    fun stopCamera() {
        slamCamera?.stop()
        slamCamera = null
    }

    fun release() {
        imuRunning = false
        imuThread?.join(2000)
        imuThread = null
        stopCamera()
        log("OV580Manager released")
    }

    // ── Little-Endian byte readers ──

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
