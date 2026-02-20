package com.xreal.hardware

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint

/**
 * OV580 SLAM Camera capture for Nreal Light.
 *
 * The OV580 has two Video class interfaces:
 *   Interface 0: Video Control (class 14, subclass 1)  
 *   Interface 1: Video Streaming (class 14, subclass 2) - SLAM cameras
 *
 * The streaming interface provides interleaved stereo frames:
 *   - 640x480 grayscale x 2 (left + right)
 *   - Even rows = left camera, odd rows = right camera
 *   - ~30 FPS
 *
 * Protocol from ar-drivers-rs NrealLightSlamCamera:
 *   1. Claim Interface 1
 *   2. Send UVC VS_COMMIT_CONTROL (34 bytes) via control transfer
 *   3. Bulk read from endpoint 0x81 (615,908 bytes per frame)
 *   4. Strip UVC headers every 0x8000 bytes
 *   5. Deinterleave rows into left/right images
 */
class OV580SlamCamera(
    private val logCallback: ((String) -> Unit)? = null
) {
    companion object {
        const val VIDEO_INTERFACE_INDEX = 1  // Interface 1 = Video Streaming
        const val FRAME_WIDTH = 640
        const val FRAME_HEIGHT = 480
        const val FRAME_PIXELS = FRAME_WIDTH * FRAME_HEIGHT  // 307,200
        const val RAW_FRAME_SIZE = FRAME_PIXELS * 2  // 614,400 (stereo)
        const val BULK_FRAME_SIZE = 615908  // With UVC headers
        const val MAX_TRANSFER_SIZE = 0x8000  // 32KB UVC transfer chunks

        // UVC VS_COMMIT_CONTROL packet from ar-drivers-rs
        // This configures the UVC streaming format
        val ENABLE_STREAMING_PACKET = byteArrayOf(
            0x01, 0x00,                   // bmHint
            0x01,                         // bFormatIndex
            0x01,                         // bFrameIndex
            0x15, 0x16, 0x05, 0x00,       // bFrameInterval (333333 = 30fps)
            0x00, 0x00,                   // wKeyFrameRate
            0x00, 0x00,                   // wPFrameRate
            0x00, 0x00,                   // wCompQuality
            0x00, 0x00,                   // wCompWindowSize
            0x65, 0x00,                   // wDelay
            0x00, 0x65, 0x09, 0x00,       // dwMaxVideoFrameSize (615680)
            0x00, 0x80.toByte(), 0x00, 0x00, // dwMaxPayloadTransferSize
            0x80.toByte(), 0xD1.toByte(),
            0xF0.toByte(), 0x08,          // dwClockFrequency
            0x08,                         // bmFramingInfo
            0xF0.toByte(),                // bPreferredVersion
            0xA9.toByte(),                // bMinVersion
            0x18                          // bMaxVersion
        )
    }

    /**
     * Data class for a single stereo SLAM frame
     */
    data class SlamFrame(
        val left: ByteArray,      // 640x480 grayscale
        val right: ByteArray,     // 640x480 grayscale
        val timestamp: Long,      // microseconds
        val frameNumber: Int
    )

    interface SlamFrameListener {
        fun onFrame(frame: SlamFrame)
    }

    var listener: SlamFrameListener? = null
    @Volatile private var running = false
    private var captureThread: Thread? = null

    private fun log(msg: String) {
        logCallback?.invoke(msg)
    }

    /**
     * Start capturing SLAM camera frames.
     * 
     * @param device The OV580 UsbDevice
     * @param connection Shared UsbDeviceConnection (also used by IMU)
     */
    fun start(device: UsbDevice, connection: UsbDeviceConnection) {
        if (running) {
            log("SLAM Camera already running")
            return
        }

        // Find Video Streaming interface (class 14, subclass 2)
        var videoInterface: android.hardware.usb.UsbInterface? = null
        var bulkEndpointIn: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            log("SLAM scan Iface $i: Class=${iface.interfaceClass}, Sub=${iface.interfaceSubclass}")
            
            // Video Streaming = class 14 (CC_VIDEO), subclass 2 (SC_VIDEOSTREAMING)
            if (iface.interfaceClass == 14 && iface.interfaceSubclass == 2) {
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    log("  Ep $j: addr=0x${String.format("%02X", ep.address)}, " +
                        "dir=${if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"}, " +
                        "type=${ep.type}, maxPkt=${ep.maxPacketSize}")
                    
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        bulkEndpointIn = ep
                    }
                }
                videoInterface = iface
                break
            }
        }

        if (videoInterface == null || bulkEndpointIn == null) {
            log("SLAM: No Video Streaming interface found!")
            return
        }

        log(">>> SLAM Camera: Iface ${videoInterface.id}, EP=0x${String.format("%02X", bulkEndpointIn.address)} <<<")

        // Claim the video streaming interface
        if (!connection.claimInterface(videoInterface, true)) {
            log("SLAM: Failed to claim video interface!")
            return
        }
        log("SLAM: Video interface claimed")

        // Send UVC VS_COMMIT_CONTROL to start streaming
        // bmRequestType=0x21 (host-to-device, class, interface)
        // bRequest=0x01 (UVC_SET_CUR)
        // wValue=0x0200 (VS_COMMIT_CONTROL << 8)
        // wIndex=interface number
        val uvcResult = connection.controlTransfer(
            0x21,  // USB_TYPE_CLASS | USB_RECIP_INTERFACE
            0x01,  // UVC_SET_CUR
            0x0200, // VS_COMMIT_CONTROL << 8
            videoInterface.id,
            ENABLE_STREAMING_PACKET,
            ENABLE_STREAMING_PACKET.size,
            1000
        )
        log("SLAM: UVC VS_COMMIT_CONTROL result=$uvcResult (expected ${ENABLE_STREAMING_PACKET.size})")

        if (uvcResult < 0) {
            log("SLAM: UVC setup failed!")
            connection.releaseInterface(videoInterface)
            return
        }

        // Start capture thread
        running = true
        val capturedEp = bulkEndpointIn
        val capturedIface = videoInterface
        captureThread = Thread {
            log(">>> SLAM CAPTURE THREAD STARTED <<<")
            val bulkBuf = ByteArray(BULK_FRAME_SIZE * 2) // extra space
            var frameCount = 0
            var lastFpsTime = System.currentTimeMillis()
            var fpsFrameCount = 0

            try {
                while (running) {
                    // Read one full frame via bulk transfer
                    val bytesRead = connection.bulkTransfer(
                        capturedEp, bulkBuf, BULK_FRAME_SIZE + 1024, 1000
                    )

                    if (bytesRead <= 0) {
                        if (bytesRead < 0) Thread.sleep(10)
                        continue
                    }

                    // Check for valid frame (first byte != 0, size ~615908)
                    if (bytesRead >= BULK_FRAME_SIZE - 1024 && bulkBuf[0] != 0.toByte()) {
                        frameCount++
                        fpsFrameCount++

                        // Strip UVC headers (occur every MAX_TRANSFER_SIZE bytes)
                        val rawData = stripUvcHeaders(bulkBuf, bytesRead)

                        // Deinterleave stereo frame
                        if (rawData.size >= RAW_FRAME_SIZE) {
                            val left = ByteArray(FRAME_PIXELS)
                            val right = ByteArray(FRAME_PIXELS)

                            for (row in 0 until FRAME_HEIGHT) {
                                // Even rows = left camera, odd rows = right camera
                                System.arraycopy(rawData, (row * 2) * FRAME_WIDTH,
                                    left, row * FRAME_WIDTH, FRAME_WIDTH)
                                System.arraycopy(rawData, (row * 2 + 1) * FRAME_WIDTH,
                                    right, row * FRAME_WIDTH, FRAME_WIDTH)
                            }

                            // Extract timestamp from after pixel data
                            val timestamp = if (rawData.size >= RAW_FRAME_SIZE + 8) {
                                readU64LE(rawData, RAW_FRAME_SIZE) / 1000 + 37600
                            } else {
                                System.currentTimeMillis() * 1000
                            }

                            val frame = SlamFrame(left, right, timestamp, frameCount)
                            listener?.onFrame(frame)
                        }

                        // Log FPS every second
                        val now = System.currentTimeMillis()
                        if (now - lastFpsTime >= 1000) {
                            val fps = fpsFrameCount * 1000.0 / (now - lastFpsTime)
                            if (frameCount <= 5 || frameCount % 30 == 0) {
                                log("SLAM: Frame #$frameCount, ${bytesRead} bytes, %.1f FPS".format(fps))
                            }
                            fpsFrameCount = 0
                            lastFpsTime = now
                        }

                        // Log first few frames for debugging
                        if (frameCount <= 3) {
                            log("SLAM Frame #$frameCount: ${bytesRead} bytes, raw=${rawData.size}")
                        }
                    }
                }
            } catch (e: Exception) {
                log("SLAM Thread Error: ${e.message}")
            }

            connection.releaseInterface(capturedIface)
            log("SLAM Capture ended (total frames=$frameCount)")
        }
        captureThread?.name = "OV580-SLAM-Camera"
        captureThread?.start()
    }

    fun stop() {
        running = false
        captureThread?.join(2000)
        captureThread = null
        log("SLAM Camera stopped")
    }

    /**
     * Strip UVC payload headers that occur every MAX_TRANSFER_SIZE bytes.
     * Each header's first byte is its length.
     */
    private fun stripUvcHeaders(data: ByteArray, length: Int): ByteArray {
        val output = ByteArray(length)
        var readIdx = 0
        var writeIdx = 0

        while (readIdx < length) {
            val headerSize = data[readIdx].toInt() and 0xFF
            if (headerSize == 0 || readIdx + headerSize > length) break

            readIdx += headerSize
            val chunkEnd = ((readIdx / MAX_TRANSFER_SIZE) + 1) * MAX_TRANSFER_SIZE
            val copyLen = minOf(chunkEnd - readIdx, length - readIdx)

            if (copyLen > 0 && writeIdx + copyLen <= output.size) {
                System.arraycopy(data, readIdx, output, writeIdx, copyLen)
                writeIdx += copyLen
            }
            readIdx += copyLen
        }

        return output.copyOf(writeIdx)
    }

    private fun readU64LE(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or ((buf[off + i].toLong()) and 0xFF)
        return v
    }
}
