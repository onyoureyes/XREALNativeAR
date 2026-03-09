package com.xreal.hardware

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread

/**
 * RGB Center Camera capture for Nreal Light using Camera2 API.
 *
 * The RGB camera (VID=0x0817, PID=0x0909) is registered by the kernel UVC
 * driver as /dev/video2,3 after MCU powers it on (command "1:h:1").
 *
 * Camera2 API works WITH the kernel UVC driver. USB Host API (claimInterface)
 * conflicts with it and causes ~47s disconnection.
 *
 * Flow:
 *   1. MCU sends "1:h:1" → RGB camera USB device appears on Bus 003
 *   2. Kernel UVC driver registers /dev/video2,3
 *   3. Camera2 enumerates it as LENS_FACING_EXTERNAL
 *   4. We open via Camera2 → ImageReader → YUV frames
 */
class RGBCameraManager(
    private val context: Context,
    private val logCallback: ((String) -> Unit)? = null
) {
    /**
     * Data class for a single RGB camera frame.
     */
    data class RGBFrame(
        val data: ByteArray,      // Raw frame data (YUV_420_888 planes)
        val width: Int,
        val height: Int,
        val format: Int,          // ImageFormat constant
        val timestamp: Long,
        val frameNumber: Int
    )

    interface RGBFrameListener {
        fun onFrame(frame: RGBFrame)
    }

    var listener: RGBFrameListener? = null
    @Volatile private var running = false
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var externalCameraId: String? = null
    private var frameCount = 0

    private fun log(msg: String) {
        logCallback?.invoke(msg)
    }

    /**
     * Probe Camera2 for external USB cameras and log details.
     * Returns the camera ID if an external camera is found, null otherwise.
     */
    fun probeAndLog(): String? {
        log("=== RGB Camera2 Probe ===")
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraIds = cameraManager.cameraIdList
        log("Camera2: ${cameraIds.size} cameras found")

        var foundId: String? = null

        for (id in cameraIds) {
            try {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val facingStr = when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                    CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                    else -> "UNKNOWN($facing)"
                }

                log("  Camera $id: facing=$facingStr")

                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                if (map != null) {
                    val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)
                    if (yuvSizes != null && yuvSizes.isNotEmpty()) {
                        log("    YUV_420_888: ${yuvSizes.joinToString { "${it.width}x${it.height}" }}")
                    }
                    val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)
                    if (jpegSizes != null && jpegSizes.isNotEmpty()) {
                        log("    JPEG: ${jpegSizes.joinToString { "${it.width}x${it.height}" }}")
                    }
                }

                // External camera = USB camera
                if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                    log("  >>> EXTERNAL USB CAMERA FOUND: id=$id <<<")
                    foundId = id
                }
            } catch (e: Exception) {
                log("  Camera $id: error reading characteristics: ${e.message}")
            }
        }

        externalCameraId = foundId
        if (foundId == null) {
            log("Camera2: No external USB camera found!")
        }
        log("=== RGB Camera2 Probe Complete ===")
        return foundId
    }

    /**
     * Start capturing frames from the external USB camera.
     * Requires CAMERA permission to be granted.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (running) {
            log("RGB Camera already running")
            return
        }

        val camId = externalCameraId
        if (camId == null) {
            log("RGB Camera: No external camera found. Call probeAndLog() first.")
            return
        }

        log(">>> RGB Camera: Opening camera id=$camId <<<")

        // Background thread for camera callbacks
        cameraThread = HandlerThread("RGB-Camera").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Determine resolution from camera characteristics
        val chars = cameraManager.getCameraCharacteristics(camId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)

        // Pick largest available size
        val targetSize = yuvSizes?.maxByOrNull { it.width * it.height }
        val width = targetSize?.width ?: 1280
        val height = targetSize?.height ?: 960

        log("RGB Camera: Using ${width}x${height} YUV_420_888")

        // ImageReader receives frames
        imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 4).also { reader ->
            reader.setOnImageAvailableListener({ ir ->
                val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    frameCount++

                    // Extract Y plane (luminance) — full resolution grayscale
                    val yPlane = image.planes[0]
                    val yBuffer = yPlane.buffer
                    val yData = ByteArray(yBuffer.remaining())
                    yBuffer.get(yData)

                    val frame = RGBFrame(
                        data = yData,
                        width = image.width,
                        height = image.height,
                        format = image.format,
                        timestamp = image.timestamp,
                        frameNumber = frameCount
                    )
                    listener?.onFrame(frame)
                } catch (e: Exception) {
                    if (frameCount <= 3) {
                        log("RGB Frame error: ${e.message}")
                    }
                } finally {
                    image.close()
                }
            }, cameraHandler)
        }

        // Open camera
        try {
            cameraManager.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    log("RGB Camera: OPENED successfully!")
                    cameraDevice = camera
                    createCaptureSession(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    log("RGB Camera: DISCONNECTED")
                    camera.close()
                    cameraDevice = null
                    running = false
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    val errorStr = when (error) {
                        ERROR_CAMERA_IN_USE -> "CAMERA_IN_USE"
                        ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
                        ERROR_CAMERA_DISABLED -> "CAMERA_DISABLED"
                        ERROR_CAMERA_DEVICE -> "CAMERA_DEVICE"
                        ERROR_CAMERA_SERVICE -> "CAMERA_SERVICE"
                        else -> "UNKNOWN($error)"
                    }
                    log("RGB Camera: ERROR $errorStr")
                    camera.close()
                    cameraDevice = null
                    running = false
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            log("RGB Camera: CAMERA permission not granted!")
        } catch (e: Exception) {
            log("RGB Camera: Error opening: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun createCaptureSession(camera: CameraDevice) {
        val reader = imageReader ?: return

        try {
            camera.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        log("RGB Camera: Session CONFIGURED!")
                        captureSession = session
                        running = true

                        try {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(reader.surface)
                            }
                            session.setRepeatingRequest(request.build(), null, cameraHandler)
                            log("RGB Camera: STREAMING started!")
                        } catch (e: Exception) {
                            log("RGB Camera: Error starting stream: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        log("RGB Camera: Session configuration FAILED!")
                        running = false
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            log("RGB Camera: Error creating session: ${e.message}")
        }
    }

    fun stop() {
        running = false
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        frameCount = 0
        externalCameraId = null
        log("RGB Camera stopped")
    }

    fun isRunning() = running
}
