package com.xreal.nativear

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import android.media.AudioManager
import android.media.MediaActionSound
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import android.view.MotionEvent
import com.google.android.gms.location.*
import android.location.Location
import android.location.Geocoder
import ai.picovoice.porcupine.Porcupine
import org.json.JSONObject
import ai.picovoice.porcupine.PorcupineManager
import org.json.JSONArray

class MainActivity : AppCompatActivity(), SensorEventListener, TextToSpeech.OnInitListener {

    private lateinit var liteRTWrapper: LiteRTWrapper
    private var isLiteRTReady = false
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    
    private val TAG = "MAIN_AR"
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
    
    // Engine State
    private var frameCount = 0
    private var lastFpsUpdateTime = 0L
    private var currentFps = 0.0
    private var lastOcrTime = 0L

    // Memory Components
    private lateinit var imageEmbedder: ImageEmbedder
    private lateinit var memoryDatabase: UnifiedMemoryDatabase
    private lateinit var memoryToolHandler: MemoryToolHandler
    private lateinit var geocoder: Geocoder
    
    // Gemini & TTS
    private lateinit var geminiClient: GeminiClient
    private lateinit var tts: TextToSpeech
    private val GEMINI_API_KEY = "AIzaSyAQbpllbZGGrBNjgbt8T96ZqGCeEsvn3cU"
    private val activityScope = MainScope()
    
    // Voice Engines
    private var porcupineManager: PorcupineManager? = null
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognizerIntent: Intent
    private val ACCESS_KEY = "GmdWaVcq/Ut1VhjmSbongouOmbErpDnK0bk8ZzWzqm0e+IrxOlsDqQ=="
    private lateinit var audioManager: AudioManager

    // Motion & PDR
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var stepDetector: Sensor? = null
    private var accumulatedSteps = 0
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var STABILITY_THRESHOLD = 0.8f 

    private var tapCount = 0
    private var lastTapTime = 0L
    private var hasTriggeredForCurrentStability = false
    private var isConversing = false

    // Journey Tracking
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLocation: Location? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Setup Logic
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        imageEmbedder = ImageEmbedder(this)
        memoryDatabase = UnifiedMemoryDatabase(this)
        memoryToolHandler = MemoryToolHandler(memoryDatabase)
        geocoder = Geocoder(this, Locale.KOREAN)
        
        geminiClient = GeminiClient(GEMINI_API_KEY)
        tts = TextToSpeech(this, this)
        
        setupMlKit()
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startLocationUpdates()

        setContentView(R.layout.activity_main)
        
        previewView = findViewById(R.id.cameraPreview)
        overlayView = findViewById(R.id.overlayView)
        val statusText = findViewById<TextView>(R.id.statusText)
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        liteRTWrapper = LiteRTWrapper(this)

        Thread {
            if (!imageEmbedder.isModelReady()) {
                runOnUiThread { statusText.text = "Downloading Embedding Model..." }
                runBlocking { imageEmbedder.downloadModel() }
            }
            imageEmbedder.initialize()
            isLiteRTReady = liteRTWrapper.initialize()
            MinimalNRSDK.initialize(this)
            
            runOnUiThread {
                if (isLiteRTReady) {
                    statusText.text = "✅ ACTIVE | Bumblebee Ready\nAR Scene Graph Syncing..."
                }
            }
        }.start()

        if (hasPermissions()) {
            startCamera()
            initPorcupine()
            initSpeechRecognizer()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
        }
    }

    private fun setupMlKit() {
        textRecognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        val translationOptions = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.KOREAN)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
        translator = Translation.getClient(translationOptions)
        translator.downloadModelIfNeeded().addOnSuccessListener { isTranslationModelDownloaded = true }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                
                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    if (overlayView.isFrozen || isConversing) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    // Throttling to ~10 FPS to save CPU
                    val currentTs = SystemClock.elapsedRealtime()
                    if (currentTs - lastFpsUpdateTime < 100) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    val bitmap = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
                    imageProxy.close()

                    // FPS Calculation
                    frameCount++
                    if (currentTs - lastFpsUpdateTime >= 1000) {
                        currentFps = frameCount * 1000.0 / (currentTs - lastFpsUpdateTime)
                        lastFpsUpdateTime = currentTs
                        frameCount = 0
                    }

                    // OCR Throttled (1000ms) to save CPU
                    if (currentTs - lastOcrTime >= 1000) {
                        lastOcrTime = currentTs
                        processOcr(bitmap)
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
            } catch (e: Exception) { Log.e(TAG, "Camera Fail: ${e.message}") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processOcr(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val results = visionText.textBlocks.map { block ->
                    OverlayView.OcrResult(block.text, block.boundingBox ?: android.graphics.Rect(), true)
                }
                runOnUiThread {
                    overlayView.setOcrResults(results, bitmap.width, bitmap.height)
                    if (results.isNotEmpty()) {
                        overlayView.addLog("👁️ OCR: ${results.size} blocks detected")
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR Realtime Failed", e)
            }
    }

    private fun android.graphics.RectF.toRect(): android.graphics.Rect {
        return android.graphics.Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

    private fun captureSceneSnapshot() {
        previewView.bitmap?.let { bitmap ->
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(inputImage).addOnSuccessListener { visionText ->
                val ocrText = visionText.text
                activityScope.launch {
                    val (reply, _) = geminiClient.interpretScene(bitmap, ocrText)
                    saveMemoryAsync("CAMERA", reply ?: "Scene snapshot", bitmap = bitmap)
                    runOnUiThread { 
                        overlayView.showSnapshotFeedback()
                        statusText("Scene Captured: ${reply ?: "Analysis shared"}")
                        reply?.let { speakOut(it) } // Restore TTS for Scene Description
                    }
                }
            }
        }
    }

    private fun initPorcupine() {
        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(ACCESS_KEY)
                .setKeywords(arrayOf(Porcupine.BuiltInKeyword.BUMBLEBEE))
                .build(this) { keywordIndex ->
                    Log.i(TAG, "Bumblebee Detected!")
                    runOnUiThread { startListening() }
                }
            porcupineManager?.start()
        } catch (e: Exception) { Log.e(TAG, "Porcupine Error: ${e.message}") }
    }

    private fun initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra("android.speech.extra.DICTATION_MODE", true)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                runOnUiThread { 
                    overlayView.setCentralMessage("Listening...") 
                    val normalized = (rmsdB + 2f) / 12f
                    overlayView.setAudioLevel(normalized)
                }
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { porcupineManager?.start() }
            override fun onResults(results: Bundle?) {
                val data = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!data.isNullOrEmpty()) {
                    val text = data[0]
                    Log.i(TAG, "STT Result: $text")
                    saveMemoryAsync("USER_VOICE", text)
                    processWithGemini(text)
                } else { porcupineManager?.start() }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val data = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!data.isNullOrEmpty()) {
                    runOnUiThread { overlayView.setCentralMessage(data[0]) }
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        try {
            isConversing = true
            runOnUiThread { overlayView.addLog("⏸️ Conv: Snapshots Paused") }
            porcupineManager?.stop()
            // Extreme Muting
            audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
            audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
            
            val intent = Intent(recognizerIntent).apply {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            }
            speechRecognizer.startListening(intent)
            
            Handler(Looper.getMainLooper()).postDelayed({
                audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
            }, 1000)
        } catch (e: Exception) { porcupineManager?.start() }
    }

    private fun processWithGemini(userText: String) {
        activityScope.launch {
            // 1. Fetch multimodal context
            val history = memoryDatabase.getRecentConversations(10)
                .reversed()
                .map { node -> 
                    com.google.genai.types.Content.builder()
                        .role(if (node.role == "USER_VOICE") "user" else "model")
                        .parts(listOf(com.google.genai.types.Part.builder().text(node.content).build()))
                        .build()
                }
            
            runOnUiThread { overlayView.setCentralMessage("Thinking...") } // Visual Feedback
            runOnUiThread { overlayView.addLog("🤖 Gemini Processing...") }
            
            val envContext = memoryDatabase.searchNodesByRole("SYSTEM_SUMMARY", 1)
                .firstOrNull()?.content ?: "No environmental data available yet."

            val currentInput = com.google.genai.types.Content.builder()
                .role("user")
                .parts(listOf(com.google.genai.types.Part.builder().text(userText).build()))
                .build()
            
            val responseText = geminiClient.generateAgenticResponse(
                history, // History already includes system context if present
                currentInput,
                memoryToolHandler
            )

            if (responseText.isNullOrEmpty()) {
                runOnUiThread { 
                    statusText("Gemini: No response")
                    speakOut("죄송합니다, 응답을 가져오지 못했습니다.") 
                    isConversing = false
                    porcupineManager?.start()
                }
            } else {
                val cleanJson = responseText.replace("```json", "").replace("```", "").trim()
                val reply = try {
                    val json = JSONObject(cleanJson)
                    json.optString("reply", responseText)
                } catch (e: Exception) { responseText }

                runOnUiThread { 
                    overlayView.setCentralMessage(reply)
                    overlayView.addLog("🤖 Reply: ${reply.take(20)}...")
                    statusText("Bumblebee: $reply")
                    
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            // Automatically start listening again for a conversational loop
                            runOnUiThread { startListening() }
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            isConversing = false
                            runOnUiThread { porcupineManager?.start() }
                        }
                    })
                    
                    val params = Bundle()
                    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "conversation")
                    tts.speak(reply, TextToSpeech.QUEUE_FLUSH, params, "conversation")
                }
                saveMemoryAsync("GEMINI", reply)
            }
        }
    }

    private fun saveMemoryAsync(
        role: String, 
        content: String, 
        bitmap: Bitmap? = null,
        importance: Double? = null,
        entities: List<String>? = null,
        intent: String? = null
    ) {
        val lat = currentLocation?.latitude
        val lon = currentLocation?.longitude
        val ts = System.currentTimeMillis()
        
        Thread {
            val floatEmbedding = bitmap?.let { imageEmbedder.embed(it) }
            val embedding = floatEmbedding?.let { floats ->
                val buffer = ByteBuffer.allocate(floats.size * 4)
                buffer.asFloatBuffer().put(floats)
                buffer.array()
            }
            val address = if (lat != null && lon != null) {
                try {
                    val addresses = geocoder.getFromLocation(lat, lon, 1)
                    addresses?.get(0)?.getAddressLine(0) ?: "Unknown Location"
                } catch (e: Exception) { "Unknown Location" }
            } else null
            
            val metadata = JSONObject().apply {
                put("address", address)
                if (role == "CAMERA") put("type", "SCENE_GRAPH")
                importance?.let { put("importance", it) }
                entities?.let { put("entities", JSONArray(it)) }
                intent?.let { put("intent", it) }
            }.toString()

            memoryDatabase.insertNode(UnifiedMemoryDatabase.MemoryNode(
                timestamp = ts, role = role, content = content, 
                embedding = embedding, latitude = lat, longitude = lon, metadata = metadata
            ))
            
            if (importance != null && importance > 0.8) {
                Log.i(TAG, "🚀 SIGNPOST MEMORY LOGGED: $content")
            }
        }.start()
    }

    private fun fetchDailyContext() {
        if (currentLocation == null) return
        
        activityScope.launch {
            val lat = currentLocation?.latitude ?: return@launch
            val lon = currentLocation?.longitude ?: return@launch
            
            // 1. Agentic Weather Context
        // We provide the location and ask the model to check the weather.
        val prompt = """
            현재 사용자의 위치는 위도 $lat, 경도 $lon 입니다.
            이 위치의 '현재 날씨'를 확인하고(도구 사용), 
            "현재 서울 날씨는 맑고 25도입니다" 처럼 자연스러운 한국어 문장 1~2줄로 요약해줘.
            
            또한, 사용자가 일반적인 질문(예: 인물, 사건, 역사)을 한다면 'perform_naver_search' 도구를 적극적으로 사용해도 좋아.
            이 요약은 시스템의 배경 지식으로 저장될 거야.
        """.trimIndent()

        val currentInput = com.google.genai.types.Content.builder()
            .role("user")
            .parts(listOf(com.google.genai.types.Part.builder().text(prompt).build()))
            .build()

        // Use Agentic Response (which has the weather tool enabled)
        val context = geminiClient.generateAgenticResponse(emptyList(), currentInput, memoryToolHandler)
            
            if (!context.isNullOrEmpty()) {
                Log.i(TAG, "🌍 Daily Context Updated: $context")
                runOnUiThread { overlayView.addLog("🌍 Daily Context Updated") }
                saveMemoryAsync("SYSTEM_SUMMARY", context, importance = 0.9, intent = "EnvironmentalAwareness")
            }
        }
    }


    private fun statusText(text: String) {
        findViewById<TextView>(R.id.statusText).text = text
    }


    override fun onSensorChanged(event: SensorEvent?) {
        if (overlayView.isFrozen || isConversing || event == null) return
        if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
            accumulatedSteps++
            val progress = ((accumulatedSteps / 15.0) * 100).toInt().coerceAtMost(100)
            runOnUiThread { overlayView.setStepProgress(progress) }
            if (accumulatedSteps >= 15) {
                accumulatedSteps = 0
                captureSceneSnapshot()
            }
            return
        }
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val delta = Math.abs(lastX - event.values[0]) + Math.abs(lastY - event.values[1]) + Math.abs(lastZ - event.values[2])
            if (delta > STABILITY_THRESHOLD) {
                overlayView.stabilityStartTime = 0L
                hasTriggeredForCurrentStability = false
                runOnUiThread { overlayView.setStabilityProgress(0) }
            } else {
                if (!hasTriggeredForCurrentStability) {
                    if (overlayView.stabilityStartTime == 0L) overlayView.stabilityStartTime = System.currentTimeMillis()
                    val duration = System.currentTimeMillis() - overlayView.stabilityStartTime
                    val progress = (duration / 20.0).toInt().coerceAtMost(100)
                    runOnUiThread { overlayView.setStabilityProgress(progress) }
                    if (duration >= 2000) {
                        captureSceneSnapshot()
                        hasTriggeredForCurrentStability = true
                    }
                }
            }
            lastX = event.values[0]
            lastY = event.values[1]
            lastZ = event.values[2]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.KOREAN
        }
    }

    private fun speakOut(text: String) {
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "message")
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "message")
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            if (now - lastTapTime < 300) tapCount++ else tapCount = 1
            lastTapTime = now
            if (tapCount == 3) {
                startActivity(Intent(this, MemoryQueryActivity::class.java).apply { putExtra("API_KEY", GEMINI_API_KEY) })
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun hasPermissions() = REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    override fun onResume() {
        super.onResume()
        accelerometer?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        stepDetector?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() { super.onPause(); sensorManager.unregisterListener(this) }

    override fun onDestroy() {
        super.onDestroy()
        porcupineManager?.delete()
        speechRecognizer.destroy()
        tts.shutdown()
        cameraExecutor.shutdown()
        MinimalNRSDK.shutdown()
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lr = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build()
        fusedLocationClient.requestLocationUpdates(lr, object : LocationCallback() {
            override fun onLocationResult(res: LocationResult) {
                val newLoc = res.lastLocation ?: return
                val isFirstUpdate = currentLocation == null
                val distance = currentLocation?.distanceTo(newLoc) ?: 0f
                currentLocation = newLoc
                
                // 1. Daily Context (Weather/Env) - Large movements (5km)
                if (isFirstUpdate || distance > 5000) {
                    fetchDailyContext()
                }

                // 2. Passive Path Tracking - Finer granularity (100m or 5 min)
                val now = System.currentTimeMillis()
                val distFromLog = lastLoggedLocation?.distanceTo(newLoc) ?: Float.MAX_VALUE
                val timeFromLog = now - lastLoggedTime

                // Trigger if moved > 100m OR time > 5 minutes (300,000ms)
                if (distFromLog > 100 || timeFromLog > 300000) {
                    logPassiveLocation(newLoc)
                    lastLoggedLocation = newLoc
                    lastLoggedTime = now
                }
            }
        }, mainLooper)
    }

    private var lastLoggedLocation: Location? = null
    private var lastLoggedTime: Long = 0

    private fun logPassiveLocation(loc: Location) {
        Thread {
            try {
                val geocoder = Geocoder(this, Locale.KOREAN)
                val addresses = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                val addressStr = addresses?.get(0)?.getAddressLine(0) ?: "Unknown Location"
                
                // Explicitly format for Gemini's understanding
                val logContent = "User Location Update: $addressStr"
                
                runOnUiThread { overlayView.addLog("📍 Loc: $addressStr") }
                
                val metadata = JSONObject().apply {
                    put("type", "LOCATION_TRACE")
                    put("address", addressStr)
                    put("source", "GPS_PASSIVE")
                }.toString()

                memoryDatabase.insertNode(UnifiedMemoryDatabase.MemoryNode(
                    timestamp = System.currentTimeMillis(),
                    role = "SYSTEM_LOG", // Distinct from USER/GEMINI
                    content = logContent,
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    metadata = metadata
                ))
                Log.d(TAG, "📍 Passive Location Logged: $addressStr")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log passive location", e)
            }
        }
    }
}
