package com.xreal.nativear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    
    private var nativeLibLoaded = false
    private var nativeError = ""
    
    // Native methods (marked with try-catch at callsites)
    private external fun runDiagnostic(): String
    private external fun initQNN(modelPath: String): Boolean
    private external fun detectObjects(imageData: ByteArray?, width: Int, height: Int): Array<Detection>?
    private external fun cleanup()

    private lateinit var cameraExecutor: ExecutorService
    private val TAG = "XREAL_ROBUST"
    private val PERMISSION_REQUEST_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        val statusText = findViewById<TextView>(R.id.statusText)
        statusText.text = "Initializing Robust Mode..."
        cameraExecutor = Executors.newSingleThreadExecutor()

        // 1. SAFE Native Library Loading
        try {
            System.loadLibrary("xreal_native_ar")
            nativeLibLoaded = true
            Log.i(TAG, "✅ Native Library Loaded")
        } catch (e: UnsatisfiedLinkError) {
            nativeError = "❌ LIB LOAD FAILED: ${e.message}"
            Log.e(TAG, nativeError)
        } catch (e: Exception) {
            nativeError = "❌ UNKNOWN ERROR: ${e.message}"
            Log.e(TAG, nativeError)
        }

        // 2. Permission & Camera Setup (Always try regardless of native status)
        if (hasPermissions()) {
            initializeApp()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun initializeApp() {
        val statusText = findViewById<TextView>(R.id.statusText)
        statusText.text = "Copying Model & Initializing Native..."

        // 1. Copy model from assets
        val modelFile = File(filesDir, "yolov5s.dlc")
        if (!modelFile.exists()) {
            try {
                assets.open("yolov5s.dlc").use { input ->
                    modelFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                statusText.text = "❌ Model Copy Failed: ${e.message}"
                return
            }
        }

        // 2. Try Native Init
        if (nativeLibLoaded) {
            val success = initQNN(modelFile.absolutePath)
            if (success) {
                Log.i(TAG, "✅ Native Pipeline Ready")
                startCamera() // Keep CameraX active for phone screen preview
                startNativeDetectionLoop()
            } else {
                statusText.text = "❌ Native Init Failed\nCheck Logcat for 'XREAL_ROBUST'"
            }
        } else {
            statusText.text = "❌ Cannot Init: Native Lib Missing"
        }
    }

    private fun startNativeDetectionLoop() {
        Thread {
            while (!isDestroyed) {
                try {
                    val detections = detectObjects(null, 0, 0) // null triggers native camera path
                    if (detections != null) {
                        runOnUiThread {
                            // Update UI status overlay with detections
                            val statusText = findViewById<TextView>(R.id.statusText)
                            statusText.text = "DETECTED: ${detections.size} objects"
                        }
                    }
                } catch (e: Exception) {}
                Thread.sleep(100)
            }
        }.start()
    }

    private fun hasPermissions() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera Permission Needed", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(findViewById<PreviewView>(R.id.cameraPreview).surfaceProvider)
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (e: Exception) {
                Log.e(TAG, "Camera Fail: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (nativeLibLoaded) {
            try { cleanup() } catch (e: Exception) {}
        }
    }
}

