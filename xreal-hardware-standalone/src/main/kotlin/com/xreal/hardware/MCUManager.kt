package com.xreal.hardware

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

/**
 * Manages the STM32 MCU on Nreal Light.
 *
 * Responsibilities:
 *   - Display control (brightness, SBS mode)
 *   - Button events
 *   - Heartbeat packets
 *   - Native driver activation (JNI)
 *
 * Protocol: ASCII text "\x02:{type}:{cmd}:{data}:{timestamp:>8x}:{crc:>8x}:\x03"
 */
class MCUManager(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val log: (String) -> Unit
) {
    @Volatile private var running = false
    private var readThread: Thread? = null

    /** File descriptor for native JNI calls */
    val fileDescriptor: Int get() = connection.fileDescriptor

    /**
     * Activate MCU: claim HID interface, send magic payload, start read thread.
     */
    fun activate(onReady: () -> Unit) {
        log(">>> MCU ACTIVATE <<<")

        // Find HID interface (class 3) with IN + OUT endpoints
        var mcuInterface: android.hardware.usb.UsbInterface? = null
        var endpointIn: android.hardware.usb.UsbEndpoint? = null
        var endpointOut: android.hardware.usb.UsbEndpoint? = null

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
                endpointIn = epIn
                endpointOut = epOut
                break
            }
        }

        if (mcuInterface == null || endpointIn == null || endpointOut == null) {
            log("MCU: No HID interface found")
            onReady()
            return
        }

        connection.claimInterface(mcuInterface, true)
        log("MCU Interface claimed")

        // Send magic payload (enricoros format)
        val magicPayload = byteArrayOf(
            0xAA.toByte(), 0xC5.toByte(), 0xD1.toByte(), 0x21.toByte(),
            0x42.toByte(), 0x04.toByte(), 0x00.toByte(), 0x19.toByte(), 0x01.toByte()
        )
        val sendResult = connection.bulkTransfer(endpointOut, magicPayload, magicPayload.size, 200)
        log("MCU magic sent: $sendResult")

        // Start read thread (status/heartbeat monitoring)
        running = true
        val capturedEp = endpointIn
        readThread = Thread {
            log("MCU Read Thread started")
            val buf = ByteArray(64)
            var count = 0
            try {
                while (running) {
                    val n = connection.bulkTransfer(capturedEp, buf, 64, 200)
                    if (n > 0) {
                        count++
                        if (count <= 3 || count % 500 == 0) {
                            val ascii = String(buf, 1, n - 1, Charsets.US_ASCII)
                                .replace(Char(0), ' ').trimEnd()
                            log("MCU #$count: $ascii")
                        }
                    } else if (n < 0) {
                        Thread.sleep(50)
                    }
                }
            } catch (e: Exception) {
                log("MCU Thread Error: ${e.message}")
            }
            log("MCU Thread ended ($count pkts)")
        }
        readThread?.name = "MCU-Read"
        readThread?.start()

        onReady()
    }

    fun release() {
        running = false
        readThread?.join(2000)
        readThread = null
        log("MCUManager released")
    }
}
