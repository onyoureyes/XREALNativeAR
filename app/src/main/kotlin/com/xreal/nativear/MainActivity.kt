package com.xreal.nativear

import android.Manifest
import android.content.Intent
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
import androidx.lifecycle.lifecycleScope
import java.io.File
import android.graphics.Bitmap
import android.view.MotionEvent
import org.koin.android.ext.android.inject
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

class MainActivity : AppCompatActivity(), 
    com.xreal.nativear.core.InputCoordinator.InputListener,
    com.xreal.nativear.core.OutputCoordinator.OutputListener,
    com.xreal.nativear.core.VisionCoordinator.VisionListener {

    
    private val TAG = "XREAL_MainActivity"
    private val PERMISSION_REQUEST_CODE = 101
    private val REQUIRED_PERMISSIONS = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ).apply {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val eventBus: com.xreal.nativear.core.GlobalEventBus by inject()
    private val bootstrapper: com.xreal.nativear.core.AppBootstrapper by inject { org.koin.core.parameter.parametersOf(lifecycleScope) }
    private val inputCoordinator: com.xreal.nativear.core.InputCoordinator by inject()
    private val outputCoordinator: com.xreal.nativear.core.OutputCoordinator by inject()
    private val visionCoordinator: com.xreal.nativear.core.VisionCoordinator by inject()
    private val aiAgentManager: AIAgentManager by inject { org.koin.core.parameter.parametersOf(lifecycleScope, object : AIAgentManager.AIAgentCallback {
        override fun onLog(message: String) { Log.d(TAG, "[AI] $message") }
        override fun onStatusUpdate(status: String) { runOnUiThread { statusText.text = status } }
        override fun onAudioLevel(level: Float) {}
        override fun onCentralMessage(text: String) { runOnUiThread { overlayView.setCentralMessage(text) } }
        override fun onGeminiResponse(reply: String) { runOnUiThread { statusText.text = "Gemini: $reply"; overlayView.setCentralMessage(reply) } }
        override fun onSearchResults(resultsJson: String) {}
        override fun showSnapshotFeedback() { eventBus.publish(com.xreal.nativear.core.XRealEvent.PerceptionEvent.SceneCaptured(Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888), "")) }
        override fun onGetLatestBitmap() = findViewById<PreviewView>(R.id.cameraPreview).bitmap
    }) }

    private lateinit var statusText: TextView
    private lateinit var overlayView: OverlayView
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: androidx.camera.core.ImageAnalysis? = null
    private var preview: Preview? = null
    
    private var tapCount = 0
    private var lastTapTime = 0L
    private val locationManager: LocationManager by inject()
    private val visionManager: VisionManager by inject()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        statusText = findViewById(R.id.statusText)
        overlayView = findViewById(R.id.overlayView)
        statusText.text = "Initializing XREAL Native AR..."

        // Attach listeners to coordinators
        inputCoordinator.setListener(this)
        outputCoordinator.setListener(this)
        visionCoordinator.setListener(this)

        // Vision State Machine (Replaces VisionStateListener)
        lifecycleScope.launchWhenStarted {
            visionManager.visionMode.collect { mode ->
                handleVisionMode(mode)
            }
        }

        if (hasPermissions()) {
            startApp()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
        }

        setupVisionToggles()
    }

    private fun handleVisionMode(mode: VisionMode) {
        val shouldEnableVision = when (mode) {
            VisionMode.Active, VisionMode.Standby, VisionMode.Frozen -> true
            VisionMode.Idle -> false
        }

        if (shouldEnableVision) {
            if (imageAnalysis == null) {
                Log.i(TAG, "Enabling Hardware Vision (ISP Data Transfer Start) - Mode: $mode")
                val builder = androidx.camera.core.ImageAnalysis.Builder()
                    .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                
                val extender = androidx.camera.camera2.interop.Camera2Interop.Extender(builder)
                extender.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, 
                    android.util.Range(5, 15)
                )

                imageAnalysis = builder.build().also {
                    it.setAnalyzer(Executors.newSingleThreadExecutor(), visionManager.analyzer)
                }
                rebindCamera()
            }
        } else {
            if (imageAnalysis != null) {
                Log.i(TAG, "Disabling Hardware Vision (ISP Data Transfer Stop) - Mode: $mode")
                imageAnalysis = null
                rebindCamera()
            }
        }
    }

    private fun setupVisionToggles() {
        
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.toggleOcr).setOnCheckedChangeListener { _, isChecked ->
            visionManager.setOcrEnabled(isChecked)
        }
        
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.togglePose).setOnCheckedChangeListener { _, isChecked ->
            visionManager.setPoseEnabled(isChecked)
        }
        
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.toggleScene).setOnCheckedChangeListener { _, isChecked ->
            visionManager.setDetectionEnabled(isChecked)
        }
        
        findViewById<android.widget.Button>(R.id.btnCapture).setOnClickListener {
            visionManager.captureSceneSnapshot()
        }
    }

    private fun startApp() {
        com.xreal.nativear.nrsdk.MinimalNRSDK.initialize(this)
        bootstrapper.start()
        startCamera()
        startBackgroundService()
    }

    private fun startBackgroundService() {
        val intent = Intent(this, AudioAnalysisService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(findViewById<PreviewView>(R.id.cameraPreview).surfaceProvider)
                }
                
                
                
                // visionManager.setVisionStateListener(this) // Deprecated: Using StateFlow

                rebindCamera()
            } catch (e: Exception) {
                Log.e(TAG, "Camera Fail: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun rebindCamera() {
        try {
            cameraProvider?.unbindAll()
            val useCases = mutableListOf<androidx.camera.core.UseCase>()
            preview?.let { useCases.add(it) }
            imageAnalysis?.let { useCases.add(it) }
            
            if (useCases.isNotEmpty()) {
                cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Rebind Fail: ${e.message}")
        }
    }

    override fun onVisionStateChanged(anyFeatureEnabled: Boolean) {
        if (anyFeatureEnabled) {
            if (imageAnalysis == null) {
                Log.i(TAG, "Enabling Hardware Vision (ISP Data Transfer Start)")
                val builder = androidx.camera.core.ImageAnalysis.Builder()
                    .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                
                // [Hardware-Level Optimization] Cap the ISP to 15 FPS to save battery
                val extender = androidx.camera.camera2.interop.Camera2Interop.Extender(builder)
                extender.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, 
                    android.util.Range(5, 15)
                )

                imageAnalysis = builder.build().also {
                    it.setAnalyzer(Executors.newSingleThreadExecutor(), visionManager.analyzer)
                }
                rebindCamera()
            }
        } else {
            if (imageAnalysis != null) {
                Log.i(TAG, "Disabling Hardware Vision (ISP Data Transfer Stop)")
                imageAnalysis = null
                rebindCamera()
            }
        }
    }

    private fun hasPermissions() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startApp()
        } else {
            Toast.makeText(this, "Permissions required for AR features", Toast.LENGTH_LONG).show()
        }
    }


    // --- Coordinator Listeners ---
    override fun onLog(message: String) { 
        Log.d(TAG, message) 
        runOnUiThread { overlayView.addLog(message) }
    }
    override fun onStatusUpdate(status: String) { runOnUiThread { statusText.text = status } }
    override fun onAudioLevel(level: Float) { runOnUiThread { overlayView.setAudioLevel(level) } }
    
    override fun onShowMessage(text: String) {
        runOnUiThread { 
            overlayView.setCentralMessage(text)
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show() 
        } 
    }

    override fun onDetections(results: List<com.xreal.nativear.Detection>) {
        runOnUiThread { overlayView.setDetections(results) }
    }

    override fun onPoseDetected(results: List<com.xreal.nativear.PoseEstimationModel.Keypoint>) {
        runOnUiThread { overlayView.setPoseResults(results) }
    }

    // Input Actions
    override fun onCycleCamera() { /* Already handled by NRSDK or visionManager directly? */ }
    override fun onDailySummary() { aiAgentManager.processWithGemini("Give me a daily summary") }
    override fun onSyncMemory() { aiAgentManager.processWithGemini("Sync my memories to cloud") }
    override fun onOpenMemQuery() {
        runOnUiThread {
            val intent = Intent(this, MemoryQueryActivity::class.java)
            locationManager.getCurrentLocation()?.let {
                intent.putExtra("extra_lat", it.latitude)
                intent.putExtra("extra_lon", it.longitude)
            }
            startActivity(intent)
        }
    }
    override fun onConfirmAction(message: String) { onShowMessage(message); aiAgentManager.processWithGemini("Yes, please continue.") }
    override fun onCancelAction(message: String) { onShowMessage(message); aiAgentManager.processWithGemini("No, stop.") }
    override fun processGeminiCommand(command: String) { aiAgentManager.processWithGemini(command) }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev?.action == android.view.MotionEvent.ACTION_DOWN) {
            // Publish a generic tap event or handle it here
            // For now, trigger a snapshot request as a direct action for debugging
            eventBus.publish(com.xreal.nativear.core.XRealEvent.ActionRequest.TriggerSnapshot)
            return true
        }
        return super.dispatchTouchEvent(ev)
    }


    override fun onDestroy() {
        super.onDestroy()
        bootstrapper.release()
        com.xreal.nativear.nrsdk.MinimalNRSDK.shutdown()
    }

}


