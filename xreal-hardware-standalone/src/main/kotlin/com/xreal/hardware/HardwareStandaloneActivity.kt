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

class HardwareStandaloneActivity : AppCompatActivity() {
    private lateinit var hwManager: XRealHardwareManager
    private lateinit var textureView: TextureView
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hardware_standalone)

        hwManager = XRealHardwareManager(this)
        textureView = findViewById(R.id.cameraTextureView)
        statusText = findViewById(R.id.statusOverlay)

        findViewById<Button>(R.id.btnActivate).setOnClickListener {
            checkPermissionsAndRun()
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
                statusText.text = "Status: Activated! Waiting for Surface..."
                setupSurface()
            }
        }
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

    override fun onDestroy() {
        super.onDestroy()
        hwManager.stopCamera()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            checkPermissionsAndRun()
        }
    }
}
