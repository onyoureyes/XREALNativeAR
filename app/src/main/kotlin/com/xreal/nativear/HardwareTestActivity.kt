package com.xreal.nativear

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.xreal.hardware.StereoDepthEngine
import com.xreal.hardware.StereoRectifier
import com.xreal.hardware.XRealHardwareManager
import org.koin.android.ext.android.inject
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class HardwareTestActivity : AppCompatActivity() {

    private val hwManager: XRealHardwareManager by inject()

    private lateinit var tvStatus: TextView
    private lateinit var tvLogs: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnStartImu: Button
    private lateinit var btnStopImu: Button
    private lateinit var btnCaptureFrame: Button
    private lateinit var ivDepthMap: ImageView
    private lateinit var ivPassthrough: ImageView
    private lateinit var ivRgbPreview: ImageView
    private lateinit var btnPassthrough: Button

    @Volatile private var requestCapture = false
    @Volatile private var passthroughMode = false
    @Volatile private var rgbPreviewActive = false

    // Save main app's listeners to restore on exit
    private var savedRgbListener: com.xreal.hardware.RGBCameraUVC.RGBFrameListener? = null
    private var savedSlamListener: com.xreal.hardware.OV580SlamCamera.SlamFrameListener? = null
    private val depthExecutor = Executors.newSingleThreadExecutor()
    private val rectifyExecutor = Executors.newSingleThreadExecutor()
    private val rgbDecodeExecutor = Executors.newSingleThreadExecutor()
    private val depthEngine by lazy { StereoDepthEngine() }
    private val stereoRectifier by lazy { StereoRectifier() }

    private val logDateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val CAMERA_PERMISSION_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hardware_test)

        tvStatus = findViewById(R.id.tvStatus)
        tvLogs = findViewById(R.id.tvLogs)
        btnConnect = findViewById(R.id.btnConnect)
        btnStartImu = findViewById(R.id.btnStartImu)
        btnStopImu = findViewById(R.id.btnStopImu)
        btnCaptureFrame = findViewById(R.id.btnCaptureFrame)
        ivDepthMap = findViewById(R.id.ivDepthMap)
        ivPassthrough = findViewById(R.id.ivPassthrough)
        ivRgbPreview = findViewById(R.id.ivRgbPreview)
        btnPassthrough = findViewById(R.id.btnPassthrough)

        hwManager.setScreenLogCallback { msg -> log("[HW] $msg") }

        setupListeners()
        setupRgbCameraListeners()

        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        val versionCode = packageManager.getPackageInfo(packageName, 0).longVersionCode
        log("=== Hardware Test (v$versionName build $versionCode) ===")
        log("Ready to connect.")

        if (OpenCVLoader.initDebug()) {
            log("OpenCV initialized successfully.")
        } else {
            log("ERROR: OpenCV initialization failed!")
        }

        ensureCameraPermission()
    }

    override fun onResume() {
        super.onResume()
        hwManager.scanAndLogDeviceDetails { log(it) }
    }

    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            log("Requesting CAMERA permission...")
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        } else {
            log("CAMERA permission already granted")
        }
    }

    private fun setupRgbCameraListeners() {
        val btnStartRgb = findViewById<Button>(R.id.btnStartRgb)
        val btnStopRgb = findViewById<Button>(R.id.btnStopRgb)

        // Save main app's listener before overwriting
        savedRgbListener = hwManager.rgbCameraListener

        hwManager.rgbCameraListener = object : com.xreal.hardware.RGBCameraUVC.RGBFrameListener {
            var fpsCount = 0
            var lastFpsTime = System.currentTimeMillis()
            @Volatile var isDecoding = false
            var displaySkip = 0

            override fun onFrame(frame: com.xreal.hardware.RGBCameraUVC.RGBFrame) {
                fpsCount++
                val now = System.currentTimeMillis()
                if (now - lastFpsTime >= 1000) {
                    val fps = fpsCount * 1000.0 / (now - lastFpsTime)
                    fpsCount = 0
                    lastFpsTime = now
                    runOnUiThread {
                        log("RGB: #${frame.frameNumber} ${frame.width}x${frame.height} %.1f FPS, ${frame.data.size} bytes".format(fps))
                    }
                }
                if (frame.frameNumber <= 3) {
                    runOnUiThread {
                        log("RGB Frame #${frame.frameNumber}: ${frame.data.size} bytes, ${frame.width}x${frame.height}, fmt=${frame.format}")
                    }
                }

                if (displaySkip++ % 3 != 0 || isDecoding) return
                isDecoding = true

                val jpegData = frame.data.copyOf()
                rgbDecodeExecutor.execute {
                    try {
                        val bmp = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
                        if (bmp != null) {
                            runOnUiThread {
                                ivRgbPreview.setImageBitmap(bmp)
                            }
                        }
                    } catch (e: Exception) {
                        // ignore decode errors
                    } finally {
                        isDecoding = false
                    }
                }
            }
        }

        btnStartRgb.setOnClickListener {
            log("Starting RGB Camera...")
            rgbPreviewActive = true
            ivRgbPreview.visibility = View.VISIBLE
            hwManager.startRGBCamera()
        }

        btnStopRgb.setOnClickListener {
            log("Stopping RGB Camera...")
            hwManager.stopRGBCamera()
            rgbPreviewActive = false
            ivRgbPreview.visibility = View.GONE
            log("RGB Camera stopped.")
        }
    }

    private fun setupListeners() {
        // Deep Scan button
        findViewById<Button>(R.id.btnDeepScan).setOnClickListener {
            log("Deep scanning USB devices...")
            hwManager.scanAndLogDeviceDetails { log(it) }
        }

        btnConnect.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
                log("CAMERA permission not granted! Requesting...")
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
                return@setOnClickListener
            }
            connectHardware()
        }

        btnStartImu.setOnClickListener {
            log("Starting IMU with AHRS sensor fusion...")
            hwManager.setIMUListener(object : XRealHardwareManager.IMUListener {
                var frameCount = 0
                override fun onOrientationUpdate(qx: Float, qy: Float, qz: Float, qw: Float) {
                    frameCount++
                    val roll = Math.toDegrees(
                        kotlin.math.atan2(
                            2.0 * (qw * qx + qy * qz),
                            1.0 - 2.0 * (qx * qx + qy * qy)
                        )
                    )
                    val sinP = 2.0 * (qw * qy - qz * qx)
                    val pitch = if (kotlin.math.abs(sinP) >= 1.0)
                        Math.copySign(90.0, sinP) else Math.toDegrees(kotlin.math.asin(sinP))
                    val yaw = Math.toDegrees(
                        kotlin.math.atan2(
                            2.0 * (qw * qz + qx * qy),
                            1.0 - 2.0 * (qy * qy + qz * qz)
                        )
                    )
                    if (frameCount % 100 == 0) {
                        runOnUiThread {
                            tvStatus.text = "R:%.1f° P:%.1f° Y:%.1f°".format(roll, pitch, yaw)
                        }
                    }
                }
            })
            hwManager.startIMU()
            log("IMU Started with Madgwick AHRS filter.")
        }

        btnStopImu.setOnClickListener {
            log("Stopping IMU...")
            hwManager.stopIMU()
            tvStatus.text = "Status: IMU Stopped"
        }

        // SLAM Camera controls
        val btnStartCam = findViewById<Button>(R.id.btnStartCam)
        val btnStopCam = findViewById<Button>(R.id.btnStopCam)

        btnCaptureFrame.setOnClickListener {
            log("Capture requested. Next frame will be saved.")
            requestCapture = true
        }

        btnPassthrough.setOnClickListener {
            passthroughMode = !passthroughMode
            if (passthroughMode) {
                btnPassthrough.text = "Passthrough: ON"
                ivPassthrough.visibility = View.VISIBLE
                ivDepthMap.visibility = View.GONE
                log("Passthrough mode: ON (rectified stereo view)")
            } else {
                btnPassthrough.text = "Passthrough: OFF"
                ivPassthrough.visibility = View.GONE
                ivDepthMap.visibility = View.VISIBLE
                log("Passthrough mode: OFF (depth map view)")
            }
        }

        btnStartCam.setOnClickListener {
            log("Starting SLAM Camera (stereo 640x480)...")
            // Save main app's SLAM listener before overwriting
            savedSlamListener = hwManager.slamCameraListener
            hwManager.slamCameraListener = object : com.xreal.hardware.OV580SlamCamera.SlamFrameListener {
                var lastFpsUpdate = System.currentTimeMillis()
                var fpsCount = 0
                @Volatile var isProcessingDepth = false

                override fun onFrame(frame: com.xreal.hardware.OV580SlamCamera.SlamFrame) {
                    fpsCount++
                    val now = System.currentTimeMillis()
                    if (now - lastFpsUpdate >= 1000) {
                        val fps = fpsCount * 1000.0 / (now - lastFpsUpdate)
                        fpsCount = 0
                        lastFpsUpdate = now
                        runOnUiThread {
                            tvStatus.text = "SLAM: #${frame.frameNumber} %.1f FPS".format(fps)
                        }
                    }

                    if (requestCapture) {
                        requestCapture = false
                        depthExecutor.execute {
                            try {
                                val ts = System.currentTimeMillis()
                                val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                                if (dir != null) {
                                    if (!dir.exists()) dir.mkdirs()

                                    val leftMat = Mat(480, 640, CvType.CV_8UC1)
                                    leftMat.put(0, 0, frame.left)
                                    val rightMat = Mat(480, 640, CvType.CV_8UC1)
                                    rightMat.put(0, 0, frame.right)

                                    val leftPath = File(dir, "calib_${ts}_left.png").absolutePath
                                    val rightPath = File(dir, "calib_${ts}_right.png").absolutePath

                                    Imgcodecs.imwrite(leftPath, leftMat)
                                    Imgcodecs.imwrite(rightPath, rightMat)

                                    leftMat.release()
                                    rightMat.release()

                                    runOnUiThread {
                                        log("Saved: calib_${ts} (left/right)")
                                    }
                                }
                            } catch (e: Exception) {
                                runOnUiThread { log("Capture error: ${e.message}") }
                            }
                        }
                    }

                    if (passthroughMode) {
                        if (!isProcessingDepth) {
                            isProcessingDepth = true
                            rectifyExecutor.execute {
                                try {
                                    val bmp = stereoRectifier.rectifySideBySide(frame.left, frame.right)
                                    if (bmp != null) {
                                        runOnUiThread {
                                            ivPassthrough.setImageBitmap(bmp)
                                        }
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                } finally {
                                    isProcessingDepth = false
                                }
                            }
                        }
                    } else {
                        if (!isProcessingDepth) {
                            isProcessingDepth = true
                            depthExecutor.execute {
                                try {
                                    val bmp = depthEngine.computeDisparityMap(frame.left, frame.right)
                                    if (bmp != null) {
                                        runOnUiThread {
                                            ivDepthMap.setImageBitmap(bmp)
                                        }
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                } finally {
                                    isProcessingDepth = false
                                }
                            }
                        }
                    }
                }
            }
            hwManager.startSlamCamera()
            log("SLAM Camera command sent.")
        }

        btnStopCam.setOnClickListener {
            log("Stopping SLAM Camera...")
            hwManager.stopSlamCamera()
            tvStatus.text = "Status: Camera Stopped"
        }
    }

    private fun connectHardware() {
        log("Attempting to find and activate XREAL device...")
        tvStatus.text = "Status: Connecting..."

        try {
            hwManager.scanAndLogDeviceDetails { log(it) }

            hwManager.findAndActivate {
                runOnUiThread {
                    log("SUCCESS: Device Activated and Ready!")
                    tvStatus.text = "Status: Activated"
                }
            }
        } catch (e: Exception) {
            log("ERROR: Activation failed - ${e.message}")
            tvStatus.text = "Status: Error"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                log("CAMERA permission GRANTED")
            } else {
                log("WARNING: CAMERA permission DENIED")
            }
        }
    }

    private fun log(message: String) {
        val timestamp = logDateFormat.format(Date())
        val logMsg = "[$timestamp] $message"

        val currentText = tvLogs.text.toString()
        val newText = "$logMsg\n$currentText"

        if (newText.length > 10000) {
            tvLogs.text = newText.substring(0, 10000)
        } else {
            tvLogs.text = newText
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // ★ CRITICAL: 콜백 먼저 해제 → Executor shutdown 순서 보장
        // 순서: (1) 콜백 제거 → (2) Executor 종료 → (3) 리소스 해제 → (4) 리스너 복원
        hwManager.setScreenLogCallback { /* no-op */ }
        hwManager.slamCameraListener = null
        hwManager.rgbCameraListener = null

        // Executor 종료 (awaitTermination으로 in-flight 작업 완료 대기)
        depthExecutor.shutdown()
        rectifyExecutor.shutdown()
        rgbDecodeExecutor.shutdown()
        try {
            if (!depthExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                depthExecutor.shutdownNow()
            }
            if (!rectifyExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                rectifyExecutor.shutdownNow()
            }
            if (!rgbDecodeExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                rgbDecodeExecutor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            depthExecutor.shutdownNow()
            rectifyExecutor.shutdownNow()
            rgbDecodeExecutor.shutdownNow()
        }

        // Release OpenCV resources
        stereoRectifier.release()

        // 메인 앱 리스너 복원 (executor 종료 후 안전)
        hwManager.slamCameraListener = savedSlamListener
        hwManager.rgbCameraListener = savedRgbListener
        // Do NOT call hwManager.release() — it's a shared Koin singleton
    }
}
