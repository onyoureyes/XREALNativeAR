package com.xreal.hardware

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class HardwareStandaloneActivity : AppCompatActivity(), XRealHardwareManager.IMUListener {
    private lateinit var hwManager: XRealHardwareManager
    private lateinit var textureView: TextureView
    private lateinit var statusText: TextView
    private lateinit var imuDataText: TextView
    private lateinit var btnToggleIMU: Button
    
    private var isImuRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hardware_standalone)

        hwManager = XRealHardwareManager(this)
        hwManager.setIMUListener(this)

        textureView = findViewById(R.id.cameraTextureView)
        statusText = findViewById(R.id.statusOverlay)
        imuDataText = findViewById(R.id.imuDataText)
        btnToggleIMU = findViewById(R.id.btnToggleIMU)

        findViewById<Button>(R.id.btnActivate).setOnClickListener {
            checkPermissionsAndRun()
        }

        btnToggleIMU.setOnClickListener {
            toggleIMU()
        }
    }

    private fun checkPermissionsAndRun() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
            return
        }
        
        statusText.text = "Status: Searching for hardware..."
        hwManager.findAndActivate {
            runOnUiThread {
                statusText.text = "Status: Activated! Enabling controls..."
                btnToggleIMU.isEnabled = true
                setupSurface()
            }
        }
    }

    private fun toggleIMU() {
        if (isImuRunning) {
            hwManager.stopIMU()
            btnToggleIMU.text = "START IMU"
            statusText.text = "Status: IMU Stopped"
        } else {
            hwManager.startIMU()
            btnToggleIMU.text = "STOP IMU"
            statusText.text = "Status: IMU Tracking..."
        }
        isImuRunning = !isImuRunning
    }

    override fun onOrientationUpdate(qx: Float, qy: Float, qz: Float, qw: Float) {
        val data = String.format("IMU Q:\nX: %.4f\nY: %.4f\nZ: %.4f\nW: %.4f", qx, qy, qz, qw)
        imuDataText.text = data
    }

    private fun setupSurface() {
        if (textureView.isAvailable) {
            startCamera(textureView.surfaceTexture!!)
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    startCamera(st)
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    private fun startCamera(st: SurfaceTexture) {
        val surface = Surface(st)
        hwManager.startCamera(surface)
        statusText.text = "Status: Camera Streaming"
    }

    override fun onPause() {
        super.onPause()
        if (isImuRunning) {
            toggleIMU()
        }
        hwManager.stopCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        hwManager.release()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            checkPermissionsAndRun()
        }
    }
}
