package com.xreal.nativear

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.view.Display
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
import android.view.SurfaceView
import com.xreal.nativear.renderer.FilamentRenderer
import com.xreal.nativear.renderer.FilamentSceneManager
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
        // Nearby Connections: Bluetooth (Android 12+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        // Nearby Connections: Wi-Fi Direct (Android 13+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }.toTypedArray()

    private val eventBus: com.xreal.nativear.core.GlobalEventBus by inject()
    private val bootstrapper: com.xreal.nativear.core.AppBootstrapper by inject { org.koin.core.parameter.parametersOf(lifecycleScope) }
    private val inputCoordinator: com.xreal.nativear.core.InputCoordinator by inject()
    private val outputCoordinator: com.xreal.nativear.core.OutputCoordinator by inject()
    private val visionCoordinator: com.xreal.nativear.core.VisionCoordinator by inject()
    private val aiAgentManager: AIAgentManager by inject()
    private val remoteCameraService: com.xreal.nativear.remote.RemoteCameraService by inject()

    private lateinit var statusText: TextView
    private lateinit var cameraSourceText: TextView
    private lateinit var overlayView: OverlayView
    private var filamentRenderer: FilamentRenderer? = null
    private var filamentSceneManager: FilamentSceneManager? = null

    // ★ AR 안경 보조 디스플레이 (XREAL Light — USB-C DisplayPort Alt Mode)
    private lateinit var displayManager: DisplayManager
    private var arGlassesPresentation: ARGlassesPresentation? = null
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: androidx.camera.core.ImageAnalysis? = null
    private var preview: Preview? = null
    
    private var tapCount = 0
    private var lastTapTime = 0L
    @Volatile private var lastAiProviderLabel = "--"
    private val locationManager: LocationManager by inject()
    private val visionManager: VisionManager by inject()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        statusText = findViewById(R.id.statusText)
        cameraSourceText = findViewById(R.id.cameraSourceText)
        overlayView = findViewById(R.id.overlayView)
        statusText.text = "Initializing XREAL Native AR..."

        // Setup Filament 3D renderer on the SurfaceView
        try {
            val surfaceView = findViewById<SurfaceView>(R.id.filamentSurface)
            surfaceView.setZOrderOnTop(false) // Below OverlayView
            filamentRenderer = FilamentRenderer(surfaceView)
            filamentRenderer?.setup()
            Log.i(TAG, "Filament renderer initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup Filament renderer: ${e.message}", e)
        }

        // Set AIAgentManager callback
        aiAgentManager.setCallback(object : AIAgentManager.AIAgentCallback {
            override fun onCentralMessage(text: String) {
                runOnUiThread {
                    overlayView.setCentralMessage(text)
                    arGlassesPresentation?.setCentralMessage(text)
                }
            }
            override fun onGeminiResponse(reply: String) {
                // AI 프로바이더 레이블 추출: [리모트], [서버], [엣지], [서버비전], [엣지비전] 등
                val aiLabel = when {
                    reply.startsWith("[리모트비전]") -> "Remote Vision"
                    reply.startsWith("[리모트]") -> "리모트 LLM"
                    reply.startsWith("[서버비전]") -> "Gemini Vision"
                    reply.startsWith("[서버]") -> "Cloud AI"
                    reply.startsWith("[엣지비전]") -> "Edge Vision"
                    reply.startsWith("[엣지]") -> "Edge LLM"
                    else -> "AI"
                }
                lastAiProviderLabel = aiLabel
                runOnUiThread {
                    statusText.text = reply.take(80)
                    updateSourceLabel(null)
                    overlayView.setCentralMessage(reply)
                    arGlassesPresentation?.setCentralMessage(reply)
                }
            }
            override fun onSearchResults(resultsJson: String) {}
            override fun showSnapshotFeedback() {
                runOnUiThread {
                    overlayView.setCentralMessage("Capturing...")
                    arGlassesPresentation?.setCentralMessage("Capturing...")
                }
            }
            override fun onGetLatestBitmap() = findViewById<PreviewView>(R.id.cameraPreview).bitmap
            override fun onGetScreenObjects() = overlayView.getScreenObjects()
        })

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

        // ★ 카메라 소스 + AI 프로바이더 실시간 표시
        lifecycleScope.launchWhenStarted {
            val csm = org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.camera.CameraStreamManager>()
            csm?.activeSource?.collect { source ->
                runOnUiThread {
                    updateSourceLabel(source.label)
                }
            }
        }

        // ★ XREAL 연결 여부에 따라 CameraX 해제 + PreviewView 숨김
        // XREAL 연결됨 → AR center camera 사용 → CameraX 해제 (ISP/배터리 절약)
        // XREAL 미연결 → 갤폴드 후면 카메라가 유일한 소스 → CameraX 활성
        lifecycleScope.launchWhenStarted {
            visionManager.isExternalFrameSourceActiveFlow.collect { isExternalActive ->
                val previewView = findViewById<androidx.camera.view.PreviewView>(R.id.cameraPreview)
                if (isExternalActive) {
                    android.util.Log.i("MainActivity", "★ XREAL 활성 → CameraX 해제 + PreviewView 숨김 (AR camera 사용)")
                    previewView.visibility = android.view.View.GONE
                    // CameraX 해제: ISP 대역폭 + 배터리 절약
                    cameraProvider?.unbindAll()
                } else {
                    android.util.Log.i("MainActivity", "XREAL 미연결 → 갤폴드 후면 카메라 PreviewView 표시")
                    previewView.visibility = android.view.View.VISIBLE
                    // CameraX 재바인딩 (XREAL 분리 시)
                    rebindCamera()
                }
            }
        }

        if (hasPermissions()) {
            startApp()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
        }

        setupVisionToggles()

        // Hardware Test Activity launcher
        findViewById<android.widget.Button>(R.id.btnHardwareTest).setOnClickListener {
            startActivity(Intent(this, HardwareTestActivity::class.java))
        }

        // ★ AR 안경 디스플레이 자동 감지 (XREAL Light — USB-C DP Alt Mode)
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        detectAndShowArGlassesDisplay()
        displayManager.registerDisplayListener(arDisplayListener, null)
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
                    .setTargetResolution(android.util.Size(640, 480))
                    .setOutputImageFormat(androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                
                val extender = androidx.camera.camera2.interop.Camera2Interop.Extender(builder)
                extender.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    android.util.Range(5, 10)
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
        
        // ★ toggleScene → toggleDetection으로 변경 (객체 인식 토글 명확화)
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.toggleDetection).setOnCheckedChangeListener { _, isChecked ->
            visionManager.setDetectionEnabled(isChecked)
            Log.i(TAG, "Object Detection: $isChecked")
        }

        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.toggleHand).setOnCheckedChangeListener { _, isChecked ->
            visionManager.setHandTrackingEnabled(isChecked)
        }

        findViewById<android.widget.Button>(R.id.btnCapture).setOnClickListener {
            visionManager.captureSceneSnapshot()
        }
    }

    private fun startApp() {
        // MinimalNRSDK removed — was a no-op mock stub (getCurrentFrame never called)
        bootstrapper.start()
        startCamera()
        startBackgroundService()

        // Wire physics engine to OverlayView for animation effects
        try {
            val physicsEngine: com.xreal.nativear.interaction.HUDPhysicsEngine by inject()
            overlayView.physicsEngine = physicsEngine
        } catch (e: Exception) {
            Log.w(TAG, "Physics engine not available: ${e.message}")
        }

        // Wire SpatialUIManager to OverlayView for 3D UI (stabilization + gaze focus + depth rendering)
        try {
            val spatialUIManager: com.xreal.nativear.spatial.SpatialUIManager by inject()
            overlayView.spatialUIManager = spatialUIManager
        } catch (e: Exception) {
            Log.w(TAG, "Spatial UI manager not available: ${e.message}")
        }

        // Wire Filament renderer to HardwareManager for camera background texture
        // Wire FilamentSceneManager for VIO pose → 3D rendering
        try {
            val hardwareManager: HardwareManager by inject()
            filamentRenderer?.let { renderer ->
                hardwareManager.filamentRenderer = renderer
                Log.i(TAG, "Filament camera background wired to HardwareManager")

                // VIO 포즈 + 고스트 러너 → Filament 3D 씬
                filamentSceneManager = FilamentSceneManager(
                    scope = lifecycleScope,
                    eventBus = eventBus,
                    renderer = renderer
                )
                filamentSceneManager?.start()
                Log.i(TAG, "FilamentSceneManager started — subscribing to HeadPoseUpdated + SpatialAnchorEvent")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to wire Filament to HardwareManager: ${e.message}")
        }
    }

    private fun startBackgroundService() {
        try {
            val intent = Intent(this, AudioAnalysisService::class.java)
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            // Android 12+ ForegroundServiceStartNotAllowedException 방지
            Log.w(TAG, "AudioAnalysisService 시작 실패 (백그라운드 제한): ${e.message}")
        }
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

    private fun updateSourceLabel(camLabel: String?) {
        val cam = camLabel ?: run {
            val csm = org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.camera.CameraStreamManager>()
            csm?.activeSource?.value?.label ?: "?"
        }
        cameraSourceText.text = "CAM: $cam | AI: $lastAiProviderLabel"
    }
    override fun onAudioLevel(level: Float) { runOnUiThread { overlayView.setAudioLevel(level) } }
    
    override fun onShowMessage(text: String) {
        runOnUiThread { 
            overlayView.setCentralMessage(text)
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show() 
        } 
    }

    override fun onDetections(results: List<com.xreal.nativear.Detection>) {
        runOnUiThread {
            overlayView.detections = results
            arGlassesPresentation?.updateDetections(results)
        }
    }

    override fun onPoseDetected(results: List<com.xreal.nativear.PoseKeypoint>) {
        runOnUiThread { overlayView.poseResults = results }
    }

    override fun onOcrDetected(results: List<OcrResult>, width: Int, height: Int) {
        runOnUiThread {
            overlayView.setOcrResults(results, width, height)
            arGlassesPresentation?.setOcrResults(results, width, height)
        }
    }

    override fun onDrawingCommand(command: DrawCommand) {
        runOnUiThread {
            overlayView.applyDrawCommand(command)
            // ★ AR 안경에도 동일한 DrawingCommand 포워딩
            arGlassesPresentation?.applyDrawCommand(command)
        }
    }

    override fun onAnchorLabels(labels: List<com.xreal.nativear.spatial.AnchorLabel2D>) {
        runOnUiThread {
            overlayView.updateAnchorLabels(labels)
            arGlassesPresentation?.updateAnchorLabels(labels)
        }
    }

    override fun onHandsDetected(hands: List<com.xreal.nativear.hand.HandData>, gestures: List<com.xreal.nativear.hand.GestureEvent>) {
        runOnUiThread {
            val gestureRecognizer: com.xreal.nativear.hand.HandGestureRecognizer by inject()
            val trail = gestureRecognizer.currentDrawTrail
            overlayView.updateHandData(hands, gestures, trail)
        }
    }

    override fun onRemoteCameraToggle(show: Boolean, source: String?) {
        runOnUiThread {
            if (show) {
                Log.i(TAG, "Starting remote camera PIP")
                // Wire frame listener to push bitmaps to OverlayView
                remoteCameraService.onFrameListener = { bitmap ->
                    runOnUiThread {
                        overlayView.updateRemoteCameraFrame(bitmap)
                    }
                }
                remoteCameraService.onErrorListener = { error ->
                    runOnUiThread {
                        overlayView.addLog("📹 $error")
                    }
                }
                remoteCameraService.startStream(source)
                overlayView.remoteCameraVisible = true
                overlayView.remoteCameraPipPosition = remoteCameraService.pipPosition
                overlayView.remoteCameraPipSizePercent = remoteCameraService.pipSizePercent
            } else {
                Log.i(TAG, "Stopping remote camera PIP")
                remoteCameraService.stopStream()
                overlayView.remoteCameraVisible = false
                overlayView.remoteCameraBitmap = null
                overlayView.postInvalidate()
            }
        }
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
        // Let all touch events pass through to child views (buttons, switches, etc.)
        return super.dispatchTouchEvent(ev)
    }


    override fun onPause() {
        super.onPause()
        filamentRenderer?.onPause()
    }

    override fun onResume() {
        super.onResume()
        filamentRenderer?.onResume()
    }

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(arDisplayListener)
        arGlassesPresentation?.dismiss()
        arGlassesPresentation = null
        remoteCameraService.release()
        filamentRenderer?.destroy()
        filamentRenderer = null
        filamentSceneManager = null
        super.onDestroy()
        bootstrapper.release()
    }

    // ─── AR 안경 디스플레이 관리 ──────────────────────────────────────────────

    /**
     * 현재 연결된 디스플레이 중 XREAL Light를 찾아 Presentation 표시.
     * XREAL Light = 비기본 디스플레이, 1920x1080.
     */
    private fun detectAndShowArGlassesDisplay() {
        val displays = displayManager.displays
        Log.i(TAG, "연결된 디스플레이 수: ${displays.size}")
        for (d in displays) {
            Log.i(TAG, "  디스플레이 ${d.displayId}: ${d.name} (${d.width}x${d.height})")
            if (isXrealDisplay(d)) {
                Log.i(TAG, "  >>> XREAL Light 디스플레이 감지! AR HUD 표시 <<<")
                showArGlassesPresentation(d)
            }
        }
    }

    /** XREAL Light 디스플레이 판별: 비기본(non-DEFAULT) + 1920x1080 */
    private fun isXrealDisplay(d: Display): Boolean {
        return d.displayId != Display.DEFAULT_DISPLAY && d.width == 1920 && d.height == 1080
    }

    /** AR 안경에 Presentation 표시 */
    private fun showArGlassesPresentation(display: Display) {
        arGlassesPresentation?.dismiss()
        try {
            val presentation = ARGlassesPresentation(this, display)
            presentation.show()
            arGlassesPresentation = presentation
            Log.i(TAG, "AR Glasses HUD Presentation 활성화: ${display.name} (${display.width}x${display.height})")
            statusText.text = "AR 안경 HUD 활성 (${display.width}x${display.height})"
        } catch (e: Exception) {
            Log.e(TAG, "AR Glasses Presentation 표시 실패: ${e.message}", e)
        }
    }

    /** XREAL Light 핫플러그 감지 리스너 */
    private val arDisplayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            val d = displayManager.getDisplay(displayId) ?: return
            Log.i(TAG, "디스플레이 추가: ${d.name} (${d.width}x${d.height})")
            if (isXrealDisplay(d) && arGlassesPresentation == null) {
                runOnUiThread { showArGlassesPresentation(d) }
            }
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (arGlassesPresentation?.display?.displayId == displayId) {
                Log.i(TAG, "XREAL Light 디스플레이 분리됨 → AR HUD 해제")
                runOnUiThread {
                    arGlassesPresentation?.dismiss()
                    arGlassesPresentation = null
                    statusText.text = "AR 안경 연결 해제됨"
                }
            }
        }

        override fun onDisplayChanged(displayId: Int) {}
    }

}


