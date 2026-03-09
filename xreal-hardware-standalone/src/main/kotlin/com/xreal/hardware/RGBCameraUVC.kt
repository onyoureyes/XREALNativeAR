package com.xreal.hardware

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

/**
 * RGB Center Camera capture for Nreal Light via USB Host API + UVC protocol.
 *
 * The RGB camera uses **isochronous** endpoints (not bulk like OV580).
 * Android's Java USB API doesn't support isochronous transfers, so we use
 * JNI with USBDEVFS_SUBMITURB / USBDEVFS_REAPURB for the actual streaming.
 *
 * Flow:
 *   1. MCU sends "1:h:1" → RGB camera appears on USB3
 *   2. USBDeviceRouter opens the device
 *   3. We find the Video Streaming interface and best ISOC alt setting
 *   4. Parse raw USB descriptors for actual packet size (SS burst/mult)
 *   5. Claim VS interface, send UVC VS_COMMIT_CONTROL
 *   6. JNI: SETINTERFACE to high-bandwidth alt, submit ISOC URBs
 *   7. JNI: Reap URBs, strip UVC headers, assemble frames, deliver via callback
 */
class RGBCameraUVC(
    private val logCallback: ((String) -> Unit)? = null
) {
    data class RGBFrame(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val format: Int,       // 0=unknown, 1=MJPEG, 2=YUY2, 3=NV12/YUV420
        val timestamp: Long,
        val frameNumber: Int
    )

    interface RGBFrameListener {
        fun onFrame(frame: RGBFrame)
    }

    var listener: RGBFrameListener? = null
    @Volatile private var running = false

    private var frameWidth = 0
    private var frameHeight = 0

    // ── JNI ──
    companion object {
        init { System.loadLibrary("xreal-hardware") }
    }
    private external fun nativeStartIso(
        fd: Int, ifaceNum: Int, altSetting: Int,
        endpoint: Int, maxPktSize: Int, maxFrameSize: Int
    ): Int
    private external fun nativeStopIso()

    private fun log(msg: String) { logCallback?.invoke(msg) }

    /**
     * Called from native code (usb_iso_camera.cpp) when a complete frame is assembled.
     */
    @Suppress("unused")
    fun onNativeIsoFrame(data: ByteArray, length: Int, frameNumber: Int) {
        try {
            val frame = RGBFrame(
                data = data,
                width = frameWidth.takeIf { it > 0 } ?: 1280,
                height = frameHeight.takeIf { it > 0 } ?: 960,
                format = 1,  // MJPEG — frames start with FF D8 (JPEG SOI)
                timestamp = System.nanoTime(),
                frameNumber = frameNumber
            )
            listener?.onFrame(frame)
        } catch (e: Exception) {
            // ★ CRITICAL: 예외가 JNI로 전파되면 ISO 스트림 전체 정지 → 반드시 catch
            log("ERROR in onNativeIsoFrame #$frameNumber: ${e.message}")
        }
    }

    // ── Probe (non-streaming, for diagnostics) ──

    fun probeAndLog(device: UsbDevice, connection: UsbDeviceConnection): Boolean {
        log("=== RGB Camera UVC Probe ===")
        log("Device: ${device.deviceName}, VID=0x${"%04X".format(device.vendorId)}, PID=0x${"%04X".format(device.productId)}")
        log("Interfaces: ${device.interfaceCount}")

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            log("  Iface $i: id=${iface.id}, alt=${iface.alternateSetting}, " +
                "Class=${iface.interfaceClass}, Sub=${iface.interfaceSubclass}, " +
                "Proto=${iface.interfaceProtocol}, Name=${iface.name}")
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                val dirStr = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                val typeStr = when (ep.type) {
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                    UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
                    UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
                    UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CTRL"
                    else -> "TYPE(${ep.type})"
                }
                log("    Ep $j: addr=0x${"%02X".format(ep.address)}, $dirStr, $typeStr, maxPkt=${ep.maxPacketSize}")
            }
        }

        // Find Video Streaming interface (any alt setting) and claim alt 0
        val vsIface = findVideoStreamingInterface(device)
        if (vsIface == null) {
            log("No Video Streaming interface (class=14, sub=2) found!")
            return false
        }

        if (!connection.claimInterface(vsIface, true)) {
            log("Failed to claim Video Streaming interface!")
            return false
        }
        log("Video Streaming interface claimed (force=true)")

        // UVC Probe
        val probeData = ByteArray(34)
        val probeResult = connection.controlTransfer(
            0xA1, 0x81, 0x0100, vsIface.id,
            probeData, probeData.size, 1000
        )
        log("VS_PROBE_CONTROL GET_CUR: result=$probeResult")
        if (probeResult > 0) logProbeData("GET_CUR", probeData, probeResult)

        // Parse raw descriptors for SS companion info
        parseAndLogRawDescriptors(connection, vsIface.id)

        connection.releaseInterface(vsIface)
        log("=== RGB Camera UVC Probe Complete ===")
        return probeResult > 0
    }

    // ── Start streaming (uses JNI ISOC) ──

    fun start(device: UsbDevice, connection: UsbDeviceConnection) {
        if (running) {
            log("RGB UVC already running")
            return
        }

        // 1. Find VS interface id and zero-bandwidth alt setting
        var vsIfaceId = -1
        var zeroAltIfaceIndex = -1
        var epAddr = 0

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == 14 && iface.interfaceSubclass == 2) {
                if (vsIfaceId == -1) vsIfaceId = iface.id
                if (iface.endpointCount == 0) zeroAltIfaceIndex = i
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.direction == UsbConstants.USB_DIR_IN &&
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
                        epAddr = ep.address
                    }
                }
            }
        }

        if (vsIfaceId == -1 || epAddr == 0) {
            log("RGB UVC: No ISOC Video Streaming endpoint found!")
            return
        }

        // 2. Claim VS interface (alt 0) to detach kernel UVC driver
        val zeroIface = if (zeroAltIfaceIndex >= 0) device.getInterface(zeroAltIfaceIndex) else null
        if (zeroIface != null) {
            if (connection.claimInterface(zeroIface, true)) {
                log("VS interface claimed (alt 0, force=true, kernel driver detached)")
            } else {
                log("WARNING: Failed to claim VS interface!")
            }
        }

        // 3. UVC Probe/Commit — get maxPayload to select correct alt setting
        val probeData = ByteArray(34)
        val probeResult = connection.controlTransfer(
            0xA1, 0x81, 0x0100, vsIfaceId,
            probeData, probeData.size, 1000
        )
        log("VS_PROBE GET_CUR: $probeResult bytes")

        var maxFrameSize = 1843200
        var maxPayload = 12288
        if (probeResult >= 26) {
            maxFrameSize = readU32LE(probeData, 18).toInt()
            maxPayload = readU32LE(probeData, 22).toInt()
            logProbeData("Probe", probeData, probeResult)
            log("maxFrameSize=$maxFrameSize, maxPayload=$maxPayload")
        }

        val commitData = if (probeResult >= 26) probeData.copyOf(probeResult) else probeData
        val commitResult = connection.controlTransfer(
            0x21, 0x01, 0x0200, vsIfaceId,
            commitData, commitData.size, 1000
        )
        log("VS_COMMIT: $commitResult (expected ${commitData.size})")

        if (commitResult < 0) {
            log("VS_COMMIT failed ($commitResult)! Cannot start streaming. Releasing interface.")
            if (zeroIface != null) connection.releaseInterface(zeroIface)
            return
        }

        // 4. Parse raw descriptors: find alt setting matching maxPayload
        val altSettings = parseAllAltSettings(connection, vsIfaceId, epAddr)
        log("Alt settings found: ${altSettings.size}")
        for ((alt, bpi) in altSettings) {
            log("  alt=$alt, wBytesPerInterval=$bpi")
        }

        // Select smallest alt whose wBytesPerInterval >= maxPayload
        val selectedAlt = altSettings.entries
            .filter { it.value >= maxPayload }
            .minByOrNull { it.value }
        val bestAlt: Int
        val isoPktSize: Int
        if (selectedAlt != null) {
            bestAlt = selectedAlt.key
            isoPktSize = selectedAlt.value
            log("Selected alt=$bestAlt (bpi=$isoPktSize >= maxPayload=$maxPayload)")
        } else {
            // Fallback: use highest available
            val fallback = altSettings.maxByOrNull { it.value }
            bestAlt = fallback?.key ?: 1
            isoPktSize = fallback?.value ?: 1024
            log("FALLBACK: alt=$bestAlt, bpi=$isoPktSize (no alt >= maxPayload=$maxPayload)")
        }

        // Determine frame dimensions from maxFrameSize
        when {
            maxFrameSize == 1843200 -> { frameWidth = 1280; frameHeight = 960 }
            maxFrameSize == 921600 ->  { frameWidth = 1280; frameHeight = 480 }
            maxFrameSize == 460800 ->  { frameWidth = 640;  frameHeight = 480 }
            else -> { frameWidth = 1280; frameHeight = 960 }
        }
        log("Frame dimensions: ${frameWidth}x${frameHeight}")

        // 5. Start native ISOC streaming
        running = true
        val fd = connection.fileDescriptor
        val result = nativeStartIso(fd, vsIfaceId, bestAlt, epAddr, isoPktSize, maxFrameSize)
        if (result < 0) {
            log("nativeStartIso failed: $result")
            running = false
        }
    }

    fun stop() {
        if (!running) return
        running = false
        nativeStopIso()
        log("RGB UVC Camera stopped")
    }

    fun isRunning() = running

    // ── Raw descriptor parsing for SS Endpoint Companion ──

    /**
     * Parse raw USB descriptors to find the actual ISO packet size for a
     * specific endpoint in a specific alternate setting.
     *
     * For USB 3.0, the SS Endpoint Companion Descriptor contains
     * wBytesPerInterval which is the exact amount of data per service interval.
     * For USB 2.0, we compute from wMaxPacketSize including the mult bits.
     *
     * Returns 0 if not found.
     */
    private fun findIsocPacketSize(
        connection: UsbDeviceConnection,
        targetEndpoint: Int,
        targetInterface: Int,
        targetAltSetting: Int
    ): Int {
        val rawDesc = connection.rawDescriptors
        if (rawDesc == null) {
            log("rawDescriptors is null!")
            return 0
        }
        log("Raw descriptors: ${rawDesc.size} bytes")

        var idx = 0
        var currentIface = -1
        var currentAlt = -1
        var foundEndpoint = false
        var hsTotal = 0

        while (idx + 1 < rawDesc.size) {
            val bLength = rawDesc[idx].toInt() and 0xFF
            if (bLength < 2 || idx + bLength > rawDesc.size) break
            val bDescType = rawDesc[idx + 1].toInt() and 0xFF

            when (bDescType) {
                4 -> { // INTERFACE descriptor
                    if (bLength >= 9) {
                        currentIface = rawDesc[idx + 2].toInt() and 0xFF
                        currentAlt = rawDesc[idx + 3].toInt() and 0xFF
                        foundEndpoint = false
                    }
                }
                5 -> { // ENDPOINT descriptor
                    if (bLength >= 7 &&
                        currentIface == targetInterface &&
                        currentAlt == targetAltSetting) {
                        val epAddr = rawDesc[idx + 2].toInt() and 0xFF
                        if (epAddr == targetEndpoint) {
                            val wMaxPkt = (rawDesc[idx + 4].toInt() and 0xFF) or
                                          ((rawDesc[idx + 5].toInt() and 0xFF) shl 8)
                            val basePkt = wMaxPkt and 0x7FF
                            val addlTrans = (wMaxPkt shr 11) and 3
                            hsTotal = basePkt * (addlTrans + 1)
                            log("Raw EP: addr=0x${"%02X".format(epAddr)}, " +
                                "wMaxPkt=0x${"%04X".format(wMaxPkt)}, " +
                                "base=$basePkt, mult=${addlTrans + 1}, HS_total=$hsTotal")
                            foundEndpoint = true
                        }
                    }
                }
                48 -> { // SS ENDPOINT COMPANION descriptor (0x30)
                    if (foundEndpoint && bLength >= 6) {
                        val bMaxBurst = rawDesc[idx + 2].toInt() and 0xFF
                        val bmAttr = rawDesc[idx + 3].toInt() and 0xFF
                        val wBytesPerInterval = (rawDesc[idx + 4].toInt() and 0xFF) or
                                               ((rawDesc[idx + 5].toInt() and 0xFF) shl 8)
                        log("SS Companion: MaxBurst=$bMaxBurst, Mult=${bmAttr and 3}, " +
                            "wBytesPerInterval=$wBytesPerInterval")
                        return wBytesPerInterval
                    }
                }
            }

            idx += bLength
        }

        // No SS companion found — use HS calculation
        if (foundEndpoint && hsTotal > 0) {
            log("No SS Companion found, using HS total: $hsTotal")
            return hsTotal
        }

        log("Descriptor parsing: endpoint not found")
        return 0
    }

    /**
     * Parse and log all raw descriptors for diagnostics.
     */
    private fun parseAndLogRawDescriptors(connection: UsbDeviceConnection, targetIface: Int) {
        val rawDesc = connection.rawDescriptors ?: return
        var idx = 0
        var currentIface = -1
        var currentAlt = -1

        log("--- Raw Descriptor Dump (iface $targetIface) ---")
        while (idx + 1 < rawDesc.size) {
            val bLength = rawDesc[idx].toInt() and 0xFF
            if (bLength < 2 || idx + bLength > rawDesc.size) break
            val bDescType = rawDesc[idx + 1].toInt() and 0xFF

            when (bDescType) {
                4 -> {
                    if (bLength >= 9) {
                        currentIface = rawDesc[idx + 2].toInt() and 0xFF
                        currentAlt = rawDesc[idx + 3].toInt() and 0xFF
                        if (currentIface == targetIface) {
                            val numEp = rawDesc[idx + 4].toInt() and 0xFF
                            log("  IFACE: num=$currentIface, alt=$currentAlt, numEp=$numEp")
                        }
                    }
                }
                5 -> {
                    if (bLength >= 7 && currentIface == targetIface) {
                        val epAddr = rawDesc[idx + 2].toInt() and 0xFF
                        val bmAttr = rawDesc[idx + 3].toInt() and 0xFF
                        val wMaxPkt = (rawDesc[idx + 4].toInt() and 0xFF) or
                                      ((rawDesc[idx + 5].toInt() and 0xFF) shl 8)
                        val bInterval = rawDesc[idx + 6].toInt() and 0xFF
                        log("  EP: addr=0x${"%02X".format(epAddr)}, attr=0x${"%02X".format(bmAttr)}, " +
                            "wMaxPkt=0x${"%04X".format(wMaxPkt)}($wMaxPkt), interval=$bInterval")
                    }
                }
                48 -> { // 0x30 = SS Endpoint Companion
                    if (bLength >= 6 && currentIface == targetIface) {
                        val bMaxBurst = rawDesc[idx + 2].toInt() and 0xFF
                        val bmAttr = rawDesc[idx + 3].toInt() and 0xFF
                        val wBPI = (rawDesc[idx + 4].toInt() and 0xFF) or
                                   ((rawDesc[idx + 5].toInt() and 0xFF) shl 8)
                        log("  SS_COMPANION: MaxBurst=$bMaxBurst, Attr=0x${"%02X".format(bmAttr)}, " +
                            "wBytesPerInterval=$wBPI")
                    }
                }
            }
            idx += bLength
        }
        log("--- End Raw Descriptor Dump ---")
    }

    /**
     * Parse all alternate settings for a given interface/endpoint from raw descriptors.
     * Returns Map<altSetting, wBytesPerInterval>.
     * For USB 3.0 uses SS Endpoint Companion; for USB 2.0 uses wMaxPacketSize with mult bits.
     */
    private fun parseAllAltSettings(
        connection: UsbDeviceConnection,
        targetInterface: Int,
        targetEndpoint: Int
    ): Map<Int, Int> {
        val rawDesc = connection.rawDescriptors
        if (rawDesc == null) {
            log("rawDescriptors is null!")
            return emptyMap()
        }

        val result = mutableMapOf<Int, Int>()
        var idx = 0
        var currentIface = -1
        var currentAlt = -1
        var pendingEpAlt = -1   // alt setting for which we found a matching EP
        var pendingHsTotal = 0  // HS fallback value for that EP

        while (idx + 1 < rawDesc.size) {
            val bLength = rawDesc[idx].toInt() and 0xFF
            if (bLength < 2 || idx + bLength > rawDesc.size) break
            val bDescType = rawDesc[idx + 1].toInt() and 0xFF

            when (bDescType) {
                4 -> { // INTERFACE descriptor
                    if (bLength >= 9) {
                        // Flush any pending EP without SS companion
                        if (pendingEpAlt >= 0 && pendingHsTotal > 0 && pendingEpAlt !in result) {
                            result[pendingEpAlt] = pendingHsTotal
                        }
                        currentIface = rawDesc[idx + 2].toInt() and 0xFF
                        currentAlt = rawDesc[idx + 3].toInt() and 0xFF
                        pendingEpAlt = -1
                        pendingHsTotal = 0
                    }
                }
                5 -> { // ENDPOINT descriptor
                    if (bLength >= 7 && currentIface == targetInterface) {
                        val epAddr = rawDesc[idx + 2].toInt() and 0xFF
                        if (epAddr == targetEndpoint) {
                            val wMaxPkt = (rawDesc[idx + 4].toInt() and 0xFF) or
                                          ((rawDesc[idx + 5].toInt() and 0xFF) shl 8)
                            val basePkt = wMaxPkt and 0x7FF
                            val addlTrans = (wMaxPkt shr 11) and 3
                            pendingHsTotal = basePkt * (addlTrans + 1)
                            pendingEpAlt = currentAlt
                        }
                    }
                }
                48 -> { // SS ENDPOINT COMPANION descriptor (0x30)
                    if (pendingEpAlt >= 0 && bLength >= 6 && currentIface == targetInterface) {
                        val wBytesPerInterval = (rawDesc[idx + 4].toInt() and 0xFF) or
                                               ((rawDesc[idx + 5].toInt() and 0xFF) shl 8)
                        result[pendingEpAlt] = wBytesPerInterval
                        pendingEpAlt = -1
                        pendingHsTotal = 0
                    }
                }
            }

            idx += bLength
        }

        // Flush last pending
        if (pendingEpAlt >= 0 && pendingHsTotal > 0 && pendingEpAlt !in result) {
            result[pendingEpAlt] = pendingHsTotal
        }

        return result
    }

    // ── Helpers ──

    private fun findVideoStreamingInterface(device: UsbDevice): android.hardware.usb.UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == 14 && iface.interfaceSubclass == 2) {
                return iface
            }
        }
        return null
    }

    private fun logProbeData(label: String, data: ByteArray, length: Int) {
        if (length < 26) {
            val hex = data.take(length).joinToString(" ") { "%02X".format(it) }
            log("  $label (${length}B): $hex")
            return
        }
        val bFormatIndex = data[2].toInt() and 0xFF
        val bFrameIndex = data[3].toInt() and 0xFF
        val dwFrameInterval = readU32LE(data, 4)
        val dwMaxVideoFrameSize = readU32LE(data, 18)
        val dwMaxPayloadTransferSize = readU32LE(data, 22)
        val fps = if (dwFrameInterval > 0) 10000000.0 / dwFrameInterval else 0.0
        log("  $label: FmtIdx=$bFormatIndex, FrmIdx=$bFrameIndex, " +
            "Interval=$dwFrameInterval (%.1ffps), MaxFrame=$dwMaxVideoFrameSize, MaxPayload=$dwMaxPayloadTransferSize".format(fps))
    }

    private fun readU32LE(buf: ByteArray, off: Int): Long {
        return ((buf[off].toLong() and 0xFF)) or
               ((buf[off+1].toLong() and 0xFF) shl 8) or
               ((buf[off+2].toLong() and 0xFF) shl 16) or
               ((buf[off+3].toLong() and 0xFF) shl 24)
    }
}
