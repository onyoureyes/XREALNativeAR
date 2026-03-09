package com.xreal.hardware

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import java.util.zip.CRC32

/**
 * Manages the STM32 MCU on Nreal Light.
 *
 * Protocol (matching ar-drivers-rs):
 *   Packet = 64 bytes, zero-padded
 *   Format: STX(0x02) : category : cmd_id : data : 0 : CRC32(8hex) : ETX(0x03)
 *   CRC-32: standard IEEE 802.3 (same as zlib), computed over all bytes before CRC field
 *   Heartbeat: category='@', cmd='K', every 250ms
 *
 * Key commands:
 *   @:3:1  = SDK Works (handshake)
 *   1:h:1  = RGB Camera ON
 *   1:h:0  = RGB Camera OFF
 *   1:i:1  = Stereo Camera ON
 *   @:K    = Heartbeat (keep-alive)
 */
class MCUManager(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val log: (String) -> Unit
) {
    @Volatile private var running = false
    private var readThread: Thread? = null
    private var heartbeatThread: Thread? = null

    // USB Endpoints
    private var endpointIn: android.hardware.usb.UsbEndpoint? = null
    private var endpointOut: android.hardware.usb.UsbEndpoint? = null

    /** File descriptor for native JNI calls */
    val fileDescriptor: Int get() = connection.fileDescriptor

    /**
     * Activate MCU: claim HID interface, send magic payload, start read + heartbeat threads.
     */
    fun activate(onReady: () -> Unit) {
        log(">>> MCU ACTIVATE <<<")

        // Find HID interface (class 3) with IN + OUT endpoints
        var mcuInterface: android.hardware.usb.UsbInterface? = null
        var foundIn: android.hardware.usb.UsbEndpoint? = null
        var foundOut: android.hardware.usb.UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass != 3) continue

            var epIn: android.hardware.usb.UsbEndpoint? = null
            var epOut: android.hardware.usb.UsbEndpoint? = null
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                if (ep.direction == UsbConstants.USB_DIR_IN && epIn == null) epIn = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT && epOut == null) epOut = ep
            }
            if (epIn != null && epOut != null) {
                mcuInterface = iface
                foundIn = epIn
                foundOut = epOut
                break
            }
        }

        if (mcuInterface == null || foundIn == null || foundOut == null) {
            log("MCU: No HID interface found")
            onReady()
            return
        }

        endpointIn = foundIn
        endpointOut = foundOut

        connection.claimInterface(mcuInterface, true)
        log("MCU Interface claimed")

        // Send magic payload (enricoros format)
        val magicPayload = byteArrayOf(
            0xAA.toByte(), 0xC5.toByte(), 0xD1.toByte(), 0x21.toByte(),
            0x42.toByte(), 0x04.toByte(), 0x00.toByte(), 0x19.toByte(), 0x01.toByte()
        )
        val sendResult = connection.bulkTransfer(foundOut, magicPayload, magicPayload.size, 200)
        log("MCU magic sent: $sendResult")

        Thread.sleep(100)

        // SDK Works handshake (@:3:1)
        sendMcuCommand('@', '3', "1")
        Thread.sleep(100)

        // RGB Camera ON (1:h:1)
        log("MCU: Powering on RGB Camera...")
        sendMcuCommand('1', 'h', "1")

        // Start read thread
        running = true
        val capturedEpIn = foundIn
        readThread = Thread {
            log("MCU Read Thread started")
            val buf = ByteArray(64)
            var count = 0
            try {
                while (running) {
                    val n = connection.bulkTransfer(capturedEpIn, buf, 64, 200)
                    if (n > 0) {
                        count++
                        if (count <= 30 || count % 100 == 0) {
                            val ascii = String(buf, 0, n, Charsets.US_ASCII)
                                .replace(Char(0), ' ')
                                .replace('\u0002', '[')
                                .replace('\u0003', ']')
                                .trimEnd()
                            log("MCU RX #$count ($n bytes): $ascii")
                            if (count <= 5) {
                                val hex = buf.take(n).joinToString(" ") { "%02X".format(it) }
                                log("MCU RX #$count HEX: $hex")
                            }
                        }
                    } else if (n < 0) {
                        Thread.sleep(50)
                    }
                }
            } catch (e: Exception) {
                log("MCU Read Thread Error: ${e.message}")
            }
            log("MCU Read Thread ended ($count pkts)")
        }.apply {
            name = "MCU-Read"
            start()
        }

        // Start heartbeat thread — 250ms interval matching ar-drivers-rs
        heartbeatThread = Thread {
            log("MCU Heartbeat Thread started (250ms interval)")
            var hbCount = 0
            try {
                while (running) {
                    Thread.sleep(250)
                    if (!running) break
                    sendMcuCommand('@', 'K')
                    hbCount++
                    if (hbCount <= 10 || hbCount % 40 == 0) {
                        log("MCU Heartbeat #$hbCount")
                    }
                }
            } catch (e: Exception) {
                log("MCU Heartbeat Thread Error: ${e.message}")
            }
            log("MCU Heartbeat Thread ended ($hbCount sent)")
        }.apply {
            name = "MCU-Heartbeat"
            start()
        }

        onReady()
    }

    /**
     * Re-send RGB Camera ON command.
     * Can be called externally to attempt camera re-activation.
     */
    fun sendRGBCameraOn() {
        log("MCU: Sending RGB Camera ON (1:h:1)")
        sendMcuCommand('1', 'h', "1")
    }

    fun release() {
        running = false
        // Power off RGB Camera gracefully
        if (endpointOut != null) {
            log("MCU: Powering off RGB Camera...")
            sendMcuCommand('1', 'h', "0")
            Thread.sleep(50)
        }

        heartbeatThread?.join(1000)
        heartbeatThread = null
        readThread?.join(2000)
        readThread = null
        log("MCUManager released")
    }

    /**
     * Build and send an MCU packet matching ar-drivers-rs format exactly.
     *
     * Packet layout (64 bytes, zero-padded):
     *   [0x02] [:] [category] [:] [cmd_id] [:] [data] [:] [0] [:] [CRC32 as %8x] [:] [0x03] [0x00...]
     *
     * CRC-32 (IEEE 802.3, same polynomial as zlib) is computed over
     * all bytes from offset 0 to just before the CRC hex string.
     *
     * Timestamp is always "0" (ar-drivers-rs: "timestamp is not checked for sent packets").
     * CRC is formatted as right-aligned 8-char lowercase hex with space padding (Rust's {:>8x}).
     */
    @Synchronized
    private fun sendMcuCommand(category: Char, cmdId: Char, data: String = "") {
        val ep = endpointOut ?: return

        val buf = ByteArray(64) // 0x40 bytes, zero-padded
        var pos = 0

        // STX : category : cmd_id : data : 0 :
        buf[pos++] = 0x02
        buf[pos++] = ':'.code.toByte()
        buf[pos++] = category.code.toByte()
        buf[pos++] = ':'.code.toByte()
        buf[pos++] = cmdId.code.toByte()
        buf[pos++] = ':'.code.toByte()
        for (c in data) {
            if (pos >= 50) break // safety: leave room for CRC + ETX
            buf[pos++] = c.code.toByte()
        }
        buf[pos++] = ':'.code.toByte()
        buf[pos++] = '0'.code.toByte()  // timestamp = "0"
        buf[pos++] = ':'.code.toByte()

        // CRC-32 over buf[0..pos)
        val crc = CRC32()
        crc.update(buf, 0, pos)
        // Format as right-aligned 8-char hex with space padding (matches Rust {:>8x})
        val crcStr = String.format("%8x", crc.value)
        for (c in crcStr) {
            buf[pos++] = c.code.toByte()
        }
        buf[pos++] = ':'.code.toByte()
        buf[pos++] = 0x03  // ETX

        // Send full 64-byte HID report
        val result = connection.bulkTransfer(ep, buf, buf.size, 200)

        // Readable log
        val readable = String(buf, 0, pos, Charsets.US_ASCII)
            .replace('\u0002', '[')
            .replace('\u0003', ']')
        if (result >= 0) {
            log("MCU TX ($result B): $readable")
        } else {
            log("MCU TX FAIL ($result): $readable")
        }
    }
}
