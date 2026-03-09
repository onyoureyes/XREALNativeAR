package com.xreal.testapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.xreal.hardware.XRealHardwareManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import android.graphics.BitmapFactory
import android.widget.ImageView
import android.os.Environment
import java.io.File
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import org.opencv.core.CvType
import org.opencv.imgcodecs.Imgcodecs
import com.xreal.hardware.StereoDepthEngine
import com.xreal.hardware.StereoRectifier
import android.view.View

class MainActivity : AppCompatActivity() {

    private lateinit var hardwareManager: XRealHardwareManager
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
    private val depthExecutor = Executors.newSingleThreadExecutor()
    private val rectifyExecutor = Executors.newSingleThreadExecutor()
    private val depthEngine by lazy { StereoDepthEngine() }
    private val stereoRectifier by lazy { StereoRectifier() }

    private val logDateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val CAMERA_PERMISSION_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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

        hardwareManager = XRealHardwareManager(this)
        hardwareManager.setScreenLogCallback { msg -> log("[HW] $msg") }

        setupListeners()
        setupRgbCameraListeners()

        // AR HUD launch button
        findViewById<Button>(R.id.btnLaunchHud).setOnClickListener {
            log("Launching AR HUD...")
            startActivity(android.content.Intent(this, ArHudActivity::class.java))
        }
        
        // Show version info prominently
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        val versionCode = packageManager.getPackageInfo(packageName, 0).longVersionCode
        log("=== XREAL Test App v$versionName (build $versionCode) ===")
        log("App initialized. Ready to connect.")
        
        if (OpenCVLoader.initDebug()) {
            log("OpenCV initialized successfully.")
        } else {
            log("ERROR: OpenCV initialization failed!")
        }
        
        // Request CAMERA permission upfront - needed for OV580 (UVC camera device)
        // Android 9+ won't show USB permission dialog for camera-class devices
        // unless CAMERA permission is already granted
        ensureCameraPermission()
    }

    override fun onResume() {
        super.onResume()
        // Auto-scan on resume to catch device state changes immediately
        hardwareManager.scanAndLogDeviceDetails { log(it) }
    }
    
    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            != PackageManager.PERMISSION_GRANTED) {
            log("Requesting CAMERA permission (required for OV580 UVC access)...")
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        } else {
            log("CAMERA permission already granted ✓")
        }
    }

    private val rgbDecodeExecutor = Executors.newSingleThreadExecutor()

    private fun setupRgbCameraListeners() {
        val btnStartRgb = findViewById<Button>(R.id.btnStartRgb)
        val btnStopRgb = findViewById<Button>(R.id.btnStopRgb)

        // Set up RGB frame listener with live preview
        hardwareManager.rgbCameraListener = object : com.xreal.hardware.RGBCameraUVC.RGBFrameListener {
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

                // Display every 3rd frame (~10fps) if not already decoding
                if (displaySkip++ % 3 != 0 || isDecoding) return
                isDecoding = true

                // Copy data for background decode (frame.data may be reused by native)
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
            hardwareManager.startRGBCamera()
        }

        btnStopRgb.setOnClickListener {
            log("Stopping RGB Camera...")
            hardwareManager.stopRGBCamera()
            rgbPreviewActive = false
            ivRgbPreview.visibility = View.GONE
            log("RGB Camera stopped.")
        }
    }

    private fun setupListeners() {
        btnConnect.setOnClickListener {
            // Check CAMERA permission before attempting OV580 connection
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
                != PackageManager.PERMISSION_GRANTED) {
                log("CAMERA permission not granted! Requesting...")
                log("(OV580 is a UVC camera - CAMERA perm required for USB access)")
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
                return@setOnClickListener
            }
            
            connectHardware()
        }

        btnStartImu.setOnClickListener {
            log("Starting IMU with AHRS sensor fusion...")
            hardwareManager.setIMUListener(object : XRealHardwareManager.IMUListener {
                var frameCount = 0
                override fun onOrientationUpdate(qx: Float, qy: Float, qz: Float, qw: Float) {
                    frameCount++
                    // Convert quaternion to Euler angles for display
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
                    // Update at 10Hz to avoid UI spam (every 100th frame at 1kHz)
                    if (frameCount % 100 == 0) {
                        runOnUiThread {
                            tvStatus.text = "R:%.1f° P:%.1f° Y:%.1f°".format(roll, pitch, yaw)
                        }
                    }
                }
            })
            hardwareManager.startIMU()
            log("IMU Started with Madgwick AHRS filter.")
        }

        btnStopImu.setOnClickListener {
            log("Stopping IMU...")
            hardwareManager.stopIMU()
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
            hardwareManager.slamCameraListener = object : com.xreal.hardware.OV580SlamCamera.SlamFrameListener {
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
                        // Passthrough mode: render rectified stereo view
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
                        // Depth mode: render disparity map
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
            hardwareManager.startSlamCamera()
            log("SLAM Camera command sent.")
        }

        btnStopCam.setOnClickListener {
            log("Stopping SLAM Camera...")
            hardwareManager.stopSlamCamera()
            tvStatus.text = "Status: Camera Stopped"
        }
    }
    
    private fun connectHardware() {
        log("Attempting to find and activate XREAL device...")
        tvStatus.text = "Status: Connecting..."
        
        try {
            // First, do a deep scan to log everything we see
            hardwareManager.scanAndLogDeviceDetails { log(it) }

            hardwareManager.findAndActivate {
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
                log("CAMERA permission GRANTED ✓ (OV580 USB access should work now)")
            } else {
                log("WARNING: CAMERA permission DENIED - OV580 access may fail!")
            }
        }
    }

    private fun log(message: String) {
        val timestamp = logDateFormat.format(Date())
        val logMsg = "[$timestamp] $message"
        
        // Append to TextView
        val currentText = tvLogs.text.toString()
        val newText = "$logMsg\n$currentText" // Prepend to show newest at top
        
        // Truncate if too long (simple memory management for test app)
        if (newText.length > 10000) {
            tvLogs.text = newText.substring(0, 10000)
        } else {
            tvLogs.text = newText
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::hardwareManager.isInitialized) hardwareManager.release()
        stereoRectifier.release()
    }
}
