package com.xreal.nativear

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.xreal.nativear.nrsdk.MinimalNRSDK
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaActionSound
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import android.view.MotionEvent
import com.google.android.gms.location.*
import android.location.Location

// ... other imports

class MainActivity : AppCompatActivity(), SensorEventListener, TextToSpeech.OnInitListener {

    private lateinit var liteRTWrapper: LiteRTWrapper
    private var isLiteRTReady = false
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    
    private val TAG = "XREAL_ROBUST"
    private val PERMISSION_REQUEST_CODE = 101
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ).let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            it + Manifest.permission.ACTIVITY_RECOGNITION
        } else it
    }.let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            it + Manifest.permission.POST_NOTIFICATIONS
        } else it
    }
    
    // ML Kit Components
    private lateinit var textRecognizer: TextRecognizer
    private lateinit var translator: Translator
    private var isTranslationModelDownloaded = false
    
    // FPS Tracking
    private var frameCount = 0
    private var lastFpsUpdateTime = 0L
    private var currentFps = 0.0
    private var lastOcrTime = 0L

    // Scene Graph
    private lateinit var imageEmbedder: ImageEmbedder
    private lateinit var memoryDatabase: UnifiedMemoryDatabase
    private var isSnapshotPending = false
    
    // Gemini & TTS
    private lateinit var geminiClient: GeminiClient
    private lateinit var tts: TextToSpeech
    private val GEMINI_API_KEY = "AIzaSyAQbpllbZGGrBNjgbt8T96ZqGCeEsvn3cU"
    private val activityScope = MainScope()
    
    // Motion & Snapshot
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var stepDetector: Sensor? = null // PDR
    private var accumulatedSteps = 0 // PDR
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var STABILITY_THRESHOLD = 0.8f 

    private var lastTapTime: Long = 0
    private var tapCount = 0
    private var hasTriggeredForCurrentStability = false

    // Journey Tracking
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastCapturedLocation: Location? = null
    private var currentLocation: Location? = null
    private val SPATIAL_DISTANCE_THRESHOLD_WALKING = 10f
    private val SPATIAL_DISTANCE_THRESHOLD_CYCLING = 50f
    private val SPATIAL_DISTANCE_THRESHOLD_DRIVING = 200f

    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Setup Sensors
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) // PDR
        
        // Setup Memory Components
        imageEmbedder = ImageEmbedder(this)
        memoryDatabase = UnifiedMemoryDatabase(this)
        
        // Setup Gemini & TTS
        geminiClient = GeminiClient(GEMINI_API_KEY)
        tts = TextToSpeech(this, this)
        
        // 1. Initialize ML Kit OCR & Translation
        setupMlKit()
        
        // Setup Location for Journey Tracking
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startLocationUpdates()

        // Phase 28: Register Debug Receiver
        val filter = IntentFilter("com.xreal.nativear.TRANSCRIPT_UPDATE")
        ContextCompat.registerReceiver(this, debugReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        setContentView(R.layout.activity_main)
        
        previewView = findViewById(R.id.cameraPreview)
        overlayView = findViewById(R.id.overlayView)
        val statusText = findViewById<TextView>(R.id.statusText)
        
        statusText.text = "Initializing LiteRT Engine..."
        cameraExecutor = Executors.newSingleThreadExecutor()

        liteRTWrapper = LiteRTWrapper(this)
        Thread {
            // Check & Download Embedding Model
            if (!imageEmbedder.isModelReady()) {
                runOnUiThread { statusText.text = "Downloading Embedding Model..." }
                // Use a proper scope or runBlocking for the download
                runBlocking {
                    val downloaded = imageEmbedder.downloadModel()
                    if (downloaded) {
                        runOnUiThread { statusText.text = "Model Downloaded!" }
                    } else {
                        runOnUiThread { statusText.text = "Model Download Failed (Check Internet)" }
                    }
                }
            }
            
            // Initialize Embedder after download check
            imageEmbedder.initialize()

            isLiteRTReady = liteRTWrapper.initialize()
            val isNRSDKReady = MinimalNRSDK.initialize(this)
            
            runOnUiThread {
                if (isLiteRTReady) {
                    val nrsdkStatus = if (isNRSDKReady) "NRSDK Ready" else "NRSDK Fail"
                    statusText.text = "✅ LiteRT Ready | $nrsdkStatus\nWaiting for Camera..."
                } else {
                    statusText.text = "❌ LiteRT Init Failed"
                }
            }
        }.start()

        if (hasPermissions()) {
            startCamera()
            startMemoryService()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE
            )
        }
        
        // DEBUG: Force UI Test
        overlayView.setCentralMessage("UI READY\nWaiting for Speech...")
    }

    private fun startMemoryService() {
        val intent = Intent(this, AudioAnalysisService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private var cameraProvider: ProcessCameraProvider? = null

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                
                // ... rest of setup ...
                
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                
                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    // If Frozen, skip processing
                    if (overlayView.isFrozen) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
    
                    val startTime = SystemClock.elapsedRealtime()
                    val bitmap = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
                    imageProxy.close()

                    // ** SILENT SNAPSHOT LOGIC **
                    if (isSnapshotPending) {
                        isSnapshotPending = false
                        
                        // 1. Capture & Embed (Async Background)
                        Thread {
                            val embedding = imageEmbedder.embed(bitmap)
                            if (embedding != null) {
                                // Save to DB
                                val byteBuffer = ByteBuffer.allocate(embedding.size * 4)
                                byteBuffer.order(ByteOrder.nativeOrder())
                                for (f in embedding) byteBuffer.putFloat(f)
                                val spatialMeta = mutableMapOf<String, Any>(
                                    "label" to "Snapshot_${System.currentTimeMillis()}",
                                    "trigger" to (if (overlayView.stabilityStartTime == 0L) "SPATIAL" else "GAZE")
                                )
                                currentLocation?.let {
                                    spatialMeta["speed"] = it.speed
                                    spatialMeta["bearing"] = it.bearing
                                    spatialMeta["accuracy"] = it.accuracy
                                }

                                val id = memoryDatabase.insertNode(
                                    UnifiedMemoryDatabase.MemoryNode(
                                        timestamp = System.currentTimeMillis(),
                                        role = "CAMERA",
                                        content = "Scene captured",
                                        embedding = byteBuffer.array(),
                                        latitude = currentLocation?.latitude,
                                        longitude = currentLocation?.longitude,
                                        metadata = spatialMeta.toString() // Simplified for now, JSON preferred in production
                                    )
                                )
                                
                                // 2. Trigger Gemini Interpretation (Background)
                                // 2. Trigger Gemini Interpretation (Background) - DISABLED by User Request
                                // val currentOCR = overlayView.getLatestText()
                                // activityScope.launch {
                                //     val (interpretation, error) = geminiClient.interpretScene(bitmap, currentOCR)
                                //     if (interpretation != null) {
                                //         speakOut(interpretation)
                                //         // Update the node with the actual description
                                //         memoryDatabase.updateNodeContent(id, interpretation)
                                //     }
                                // }
                            }
                        }.start()

                        // ** NO UI FREEZE, NO CAMERA UNBIND **
                        // Camera keeps running seamlessly in the background.
                        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
                        vibrator.vibrate(VibrationEffect.createOneShot(30, 50))
                        
                        return@setAnalyzer
                    }

                    // 1. LiteRT / YOLO (Legacy/Optional)
                    if (isLiteRTReady) {
                        // Disabled YOLO for now to focus on OCR
                        // val results = liteRTWrapper.detect(bitmap) 
                    }

                    // 2. FPS Calculation
                    frameCount++
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastFpsUpdateTime >= 1000) {
                        currentFps = frameCount * 1000.0 / (now - lastFpsUpdateTime)
                        lastFpsUpdateTime = now
                        frameCount = 0
                    }

                    // 3. ML Kit OCR Processing (Throttled & Filtered)
                    val ocrGap = now - lastOcrTime
                    if (ocrGap >= 500) { // Throttle: Run every 500ms
                        lastOcrTime = now
                        
                        val inputImage = InputImage.fromBitmap(bitmap, 0)
                        textRecognizer.process(inputImage)
                            .addOnSuccessListener { visionText ->
                                val ocrResults = mutableListOf<OverlayView.OcrResult>()
                                val imgW = bitmap.width
                                val imgH = bitmap.height
                                
                                // ROI: Center 50%
                                val roiRect = android.graphics.Rect(
                                    (imgW * 0.25).toInt(), (imgH * 0.25).toInt(),
                                    (imgW * 0.75).toInt(), (imgH * 0.75).toInt()
                                )
                                
                                var rejectedCount = 0
                                
                                for (block in visionText.textBlocks) {
                                    val originalText = block.text
                                    val box = block.boundingBox ?: continue
                                    
                                    var isValid = true
                                    
                                    // Filter 1: ROI Check
                                    if (!android.graphics.Rect.intersects(roiRect, box)) isValid = false

                                    // Filter 2: Language Check - RELAXED
                                    // User wants to see English too if it's in the center.
                                    // if (!originalText.matches(Regex(".*[가-힣]+.*"))) isValid = false
                                    
                                    if (!isValid) rejectedCount++
                                    
                                    ocrResults.add(OverlayView.OcrResult(originalText, box, isValid))
                                    
                                    // Real-time Translate (Only valid)
                                    if (isValid && isTranslationModelDownloaded && originalText.isNotEmpty()) {
                                        translator.translate(originalText)
                                            .addOnSuccessListener { translatedText ->
                                                // TODO: Update overlay with translation
                                            }
                                    }
                                }

                                runOnUiThread {
                                    overlayView.setOcrResults(ocrResults, bitmap.width, bitmap.height)
                                    
                                    findViewById<TextView>(R.id.statusText).text = 
                                        "Detected: ${ocrResults.size} | Valid: ${ocrResults.size - rejectedCount} | FPS: ${String.format("%.1f", currentFps)}"
                                }
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "OCR Failed: ${e.message}")
                            }
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
                
            } catch (e: Exception) {
                Log.e(TAG, "Camera Fail: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupMlKit() {
        // OCR Engine (Switched to Korean, which also supports English)
        textRecognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

        // Translation Engine (Korean -> English)
        val translationOptions = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.KOREAN)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
        translator = Translation.getClient(translationOptions)

        // Pre-download translation model
        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                isTranslationModelDownloaded = true
                Log.i(TAG, "Translation model ready")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Translation model download failed: ${e.message}")
            }
    }


    private fun hasPermissions() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startCamera()
            startMemoryService()
        } else {
            Toast.makeText(this, "Camera Permission Needed", Toast.LENGTH_LONG).show()
        }
    }

    // --- Sensor Event Listener (Motion Detection & PDR) ---
    override fun onSensorChanged(event: SensorEvent?) {
        if (overlayView.isFrozen || event == null) return

        if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
            // PDR Logic: Count steps for indoor distance
            accumulatedSteps++
            
            // Visual Feedback: Update Progress Bar (0-15 steps -> 0-100%)
            val progress = ((accumulatedSteps / 15.0) * 100).toInt().coerceAtMost(100)
            runOnUiThread { overlayView.setStepProgress(progress) }
            
            // 15 steps * 0.7m/step ~= 10.5m
            if (accumulatedSteps >= 15) {
                Log.i(TAG, "PDR Trigger: Expanded 15 steps (~10m). Requesting Snapshot.")
                // isSnapshotPending = true // DISABLED by User Request
                accumulatedSteps = 0 // Reset counter
                runOnUiThread { overlayView.setStepProgress(0) }
                // speakOut("장면이 기록되었습니다.") // DISABLED
            }
            return
        }

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
    
            // Calculate variance (Shake detection)
            val deltaX = kotlin.math.abs(lastX - x)
            val deltaY = kotlin.math.abs(lastY - y)
            val deltaZ = kotlin.math.abs(lastZ - z)
    
            if (deltaX > STABILITY_THRESHOLD || deltaY > STABILITY_THRESHOLD || deltaZ > STABILITY_THRESHOLD) {
                // Movement Detected -> Reset Timer and Flag
                overlayView.stabilityStartTime = 0L
                hasTriggeredForCurrentStability = false
                runOnUiThread { overlayView.setStabilityProgress(0) }
            } else {
                // Stable -> Increment Timer if not already triggered
                if (!hasTriggeredForCurrentStability) {
                    if (overlayView.stabilityStartTime == 0L) {
                        overlayView.stabilityStartTime = System.currentTimeMillis()
                    }
                    
                    val duration = System.currentTimeMillis() - overlayView.stabilityStartTime
                    val progress = (duration / 20.0).toInt().coerceAtMost(100) // 2000ms target
                    
                    runOnUiThread { overlayView.setStabilityProgress(progress) }
                    
                    if (duration >= 2000) {
                        // Request Snapshot from Camera Loop
                        // isSnapshotPending = true // DISABLED by User Request
                        hasTriggeredForCurrentStability = true
                        accumulatedSteps = 0 // Reset PDR on stable snapshot too
                    }
                }
            }
    
            lastX = x
            lastY = y
            lastZ = z
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    // Tap to Resume & Triple Tap for Query
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastTapTime < 300) {
                tapCount++
            } else {
                tapCount = 1
            }
            lastTapTime = currentTime

            if (tapCount == 3) {
                // Triple Tap: Launch Memory Query
                val intent = Intent(this, MemoryQueryActivity::class.java)
                intent.putExtra("API_KEY", GEMINI_API_KEY)
                startActivity(intent)
                tapCount = 0
                return true
            }

            if (overlayView.isFrozen) {
                isSnapshotPending = false
                runOnUiThread {
                    overlayView.setFrozen(false)
                    overlayView.setStabilityProgress(0)
                    findViewById<TextView>(R.id.statusText).text = "Resuming Camera..."
                    // Restart Camera
                    startCamera()
                }
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        stepDetector?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI) // PDR
        }
        
        // Force permission check for Mic
        if (!hasPermissions()) {
             ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra("text") ?: return
            overlayView.setDebugLog(text)
            overlayView.setCentralMessage(text) // SHOW CENTERED
            
            // Visual confirm toast (Fixed typo: Whisper sends "Hearing...", not "Hearing:")
            if (text.startsWith("Hearing")) {
                 Toast.makeText(this@MainActivity, text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(debugReceiver)
        } catch (e: Exception) {
            // content not registered
        }
        super.onDestroy()
        cameraExecutor.shutdown()
        MinimalNRSDK.shutdown()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        if (isLiteRTReady) {
            liteRTWrapper.close()
        }
    }

    // TTS Implementation
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.KOREAN)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "Language not supported")
            }
        } else {
            Log.e("TTS", "Initialization failed")
        }
    }

    private fun speakOut(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "")
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateIntervalMillis(2000)
            .build()

        fusedLocationClient.requestLocationUpdates(locationRequest, object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    currentLocation = location
                    checkSpatialTrigger(location)
                }
            }
        }, mainLooper)
    }

    private fun checkSpatialTrigger(location: Location) {
        if (lastCapturedLocation == null) {
            lastCapturedLocation = location
            return
        }

        val distance = location.distanceTo(lastCapturedLocation!!)
        val speed = location.speed // m/s
        
        val threshold = when {
            speed < 3.0 -> SPATIAL_DISTANCE_THRESHOLD_WALKING
            speed < 10.0 -> SPATIAL_DISTANCE_THRESHOLD_CYCLING
            else -> SPATIAL_DISTANCE_THRESHOLD_DRIVING
        }

        if (distance >= threshold) {
            Log.i(TAG, "Spatial Trigger: Moved ${distance}m at speed ${speed}m/s. Requesting Snapshot.")
            isSnapshotPending = true
            lastCapturedLocation = location
            accumulatedSteps = 0 // Hybrid: Reset PDR counter if GPS triggers
            runOnUiThread { overlayView.setStepProgress(0) }
            speakOut("장면이 기록되었습니다.")
        }
    }
}
