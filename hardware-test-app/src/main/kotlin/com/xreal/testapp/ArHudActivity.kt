package com.xreal.testapp

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.text.method.ScrollingMovementMethod
import android.view.Display
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.xreal.hardware.OV580SlamCamera
import com.xreal.hardware.XRealHardwareManager

/**
 * AR HUD Activity: Controls the Nreal Light external display via Presentation API.
 *
 * Phone screen = control panel (status, logs, buttons)
 * Nreal Light  = AR HUD via NrealPresentation (1920x1080, black = transparent)
 */
class ArHudActivity : AppCompatActivity() {

    private val TAG = "ArHudActivity"

    private lateinit var hardwareManager: XRealHardwareManager
    private lateinit var displayManager: DisplayManager

    // Phone UI
    private lateinit var tvPhoneStatus: TextView
    private lateinit var tvPhoneLog: TextView

    // Nreal Light Presentation
    private var nrealPresentation: NrealPresentation? = null
    private var nrealDisplay: Display? = null

    // IMU state
    private var currentRoll = 0.0
    private var currentPitch = 0.0
    private var currentYaw = 0.0

    // Camera state
    private var cameraFpsCount = 0
    private var lastFpsTime = System.currentTimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar_hud_control)

        tvPhoneStatus = findViewById(R.id.tvPhoneStatus)
        tvPhoneLog = findViewById(R.id.tvPhoneLog)
        tvPhoneLog.movementMethod = ScrollingMovementMethod()

        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize hardware
        hardwareManager = XRealHardwareManager(this)

        // Try to find and attach to external display
        findNrealDisplay()

        // Buttons
        findViewById<Button>(R.id.btnHudConnect).setOnClickListener {
            phoneLog("Connecting hardware...")
            debugRawUsbDevices()
            connectHardware()
        }

        findViewById<Button>(R.id.btnHudStartCam).setOnClickListener {
            phoneLog("Starting SLAM camera...")
            hardwareManager.startSlamCamera()
        }

        // Monitor display changes (hot-plug)
        displayManager.registerDisplayListener(displayListener, null)

        tvPhoneStatus.text = if (nrealDisplay != null)
            "✅ Nreal Light Display found (${nrealDisplay!!.width}x${nrealDisplay!!.height})"
        else
            "⚠️ No external display — connect Nreal Light"
    }

    private fun debugRawUsbDevices() {
        phoneLog("--- Raw Linux USB Sysfs Enumeration ---")
        try {
            val usbDir = java.io.File("/sys/bus/usb/devices")
            if (usbDir.exists() && usbDir.isDirectory) {
                usbDir.listFiles()?.forEach { devDir ->
                    val vidFile = java.io.File(devDir, "idVendor")
                    val pidFile = java.io.File(devDir, "idProduct")
                    val prodFile = java.io.File(devDir, "product")
                    
                    if (vidFile.exists() && pidFile.exists()) {
                        val vid = vidFile.readText().trim()
                        val pid = pidFile.readText().trim()
                        val prod = if (prodFile.exists()) prodFile.readText().trim() else "Unknown"
                        
                        if (vid.isNotEmpty() && pid.isNotEmpty()) {
                            // Only log Nreal related VIDs to reduce noise
                            if (vid == "0486" || vid == "05a9" || vid == "0bda" || vid == "0817") {
                                phoneLog("  Raw USB: VID=$vid PID=$pid ($prod)")
                            }
                        }
                    }
                }
            } else {
                phoneLog("  /sys/bus/usb/devices not accessible")
            }
        } catch (e: Exception) {
            phoneLog("  Raw USB scan error: ${e.message}")
        }
        phoneLog("---------------------------------------")
    }

    private fun findNrealDisplay() {
        val displays = displayManager.displays
        phoneLog("Total displays: ${displays.size}")
        for (d in displays) {
            phoneLog("  Display ${d.displayId}: ${d.name} (${d.width}x${d.height})")
            // Find external display (non-built-in, typically the Nreal Light)
            if (d.displayId != Display.DEFAULT_DISPLAY && d.width == 1920 && d.height == 1080) {
                nrealDisplay = d
                phoneLog("  >>> Nreal Light display detected! <<<")
                showPresentation(d)
            }
        }
    }

    private fun showPresentation(display: Display) {
        nrealPresentation?.dismiss()
        try {
            val presentation = NrealPresentation(this, display)
            presentation.show()
            nrealPresentation = presentation
            phoneLog("AR HUD Presentation shown on ${display.name}")
            tvPhoneStatus.text = "✅ AR HUD active on Nreal Light (${display.width}x${display.height})"
        } catch (e: Exception) {
            phoneLog("ERROR showing presentation: ${e.message}")
            Log.e(TAG, "Presentation error", e)
        }
    }

    private fun connectHardware() {
        // Enumerate Camera2 devices to find OV580 RGB camera
        enumerateCameras()

        // Set up IMU listener
        hardwareManager.setIMUListener(object : XRealHardwareManager.IMUListener {
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
                currentRoll = roll
                currentPitch = pitch
                currentYaw = yaw

                // Update Nreal HUD at ~10Hz
                if (frameCount % 100 == 0) {
                    runOnUiThread {
                        nrealPresentation?.updateOrientation(roll, pitch, yaw)
                    }
                }
            }
        })

        // Set up SLAM camera listener
        hardwareManager.slamCameraListener = object : OV580SlamCamera.SlamFrameListener {
            override fun onFrame(frame: OV580SlamCamera.SlamFrame) {
                cameraFpsCount++
                val now = System.currentTimeMillis()
                if (now - lastFpsTime >= 1000) {
                    val fps = cameraFpsCount * 1000.0 / (now - lastFpsTime)
                    cameraFpsCount = 0
                    lastFpsTime = now
                    runOnUiThread {
                        nrealPresentation?.updateFps(fps)
                    }
                }
                // TODO Phase 3: Gaze detection + center crop → Gemini
            }
        }

        // Find and activate hardware
        hardwareManager.findAndActivate {
            runOnUiThread {
                phoneLog("Hardware activated!")
                nrealPresentation?.tvStatus?.text = "XREAL AR"
                tvPhoneStatus.text = "✅ Hardware active" +
                    if (nrealDisplay != null) " | HUD on Nreal Light" else " | No external display"
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            val d = displayManager.getDisplay(displayId) ?: return
            phoneLog("Display added: ${d.name} (${d.width}x${d.height})")
            if (d.width == 1920 && d.height == 1080 && nrealDisplay == null) {
                nrealDisplay = d
                runOnUiThread { showPresentation(d) }
            }
        }
        override fun onDisplayRemoved(displayId: Int) {
            if (nrealDisplay?.displayId == displayId) {
                phoneLog("Nreal Light display removed")
                nrealPresentation?.dismiss()
                nrealPresentation = null
                nrealDisplay = null
                runOnUiThread {
                    tvPhoneStatus.text = "⚠️ Nreal Light disconnected"
                }
            }
        }
        override fun onDisplayChanged(displayId: Int) {}
    }

    private fun phoneLog(msg: String) {
        Log.i(TAG, msg)
        runOnUiThread {
            val current = tvPhoneLog.text.toString()
            val lines = current.split("\n")
            val trimmed = if (lines.size > 15) lines.takeLast(15).joinToString("\n") else current
            tvPhoneLog.text = "$trimmed\n$msg"
        }
    }

    private fun enumerateCameras() {
        try {
            val camManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = camManager.cameraIdList
            phoneLog("=== Camera2 Enumeration: ${ids.size} cameras ===")
            for (id in ids) {
                val chars = camManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val facingStr = when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                    CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL <<<<<"
                    else -> "UNKNOWN($facing)"
                }
                val level = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                val levelStr = when (level) {
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    else -> "UNKNOWN($level)"
                }
                val resolutions = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = resolutions?.getOutputSizes(android.graphics.ImageFormat.JPEG)
                    ?.joinToString(", ") { "${it.width}x${it.height}" } ?: "N/A"
                phoneLog("  Camera $id: facing=$facingStr level=$levelStr")
                phoneLog("    JPEG sizes: $sizes")
            }
        } catch (e: Exception) {
            phoneLog("Camera enum error: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        displayManager.unregisterDisplayListener(displayListener)
        nrealPresentation?.dismiss()
        hardwareManager.release()
    }
}
