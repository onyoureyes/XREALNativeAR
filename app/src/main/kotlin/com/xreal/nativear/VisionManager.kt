package com.xreal.nativear

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.xreal.nativear.core.VisionServiceDelegate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * VisionManager: Manages CameraX analysis and ML Kit vision processing.
 * Optimized with bitmap lifecycle management to prevent OOM.
 */
class VisionManager(
    private val context: Context,
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val ocrModel: OCRModel,
    private val poseModel: PoseEstimationModel,
    private val liteRTWrapper: LiteRTWrapper,
    private val imageEmbedder: ImageEmbedder,
    private val eventBus: com.xreal.nativear.core.GlobalEventBus,
    private val locationService: ILocationService,
    private val scope: CoroutineScope // Injected Scope
) : IVisionService {

    private val TAG = "VisionManager"

    // State Machine
    private val _visionMode = MutableStateFlow<VisionMode>(VisionMode.Idle)
    val visionMode: StateFlow<VisionMode> = _visionMode.asStateFlow()

    // Configuration Flags
    private var configOcrEnabled = false
    private var configPoseEnabled = false
    private var configDetectionEnabled = true 
    private var instantCaptureRequested = false
    private var isSceneCaptureEnabled = false // Legacy?

    // Throttling
    private var lastOcrTime = 0L
    private var lastPoseTime = 0L
    private var lastDetectionTime = 0L
    
    private val OCR_INTERVAL = 300L
    private val POSE_INTERVAL = 100L
    private val DETECT_INTERVAL = 500L

    private var reuseBitmap: Bitmap? = null

    // Helper: Determine target mode based on config
    private fun resolveTargetMode(): VisionMode {
        if (isFrozen()) return VisionMode.Frozen
        
        val anyEnabled = configOcrEnabled || configPoseEnabled || configDetectionEnabled
        return if (anyEnabled) VisionMode.Active else VisionMode.Idle 
    }
    
    // Internal Freeze flag for temporary pauses
    private var _isFrozen = false
    fun isFrozen() = _isFrozen

    private fun updateState() {
        val target = resolveTargetMode()
        if (_visionMode.value != target) {
            transitionTo(target)
        }
    }

    private fun transitionTo(newMode: VisionMode) {
        val oldMode = _visionMode.value
        Log.i(TAG, "State Transition: $oldMode -> $newMode")
        
        // 1. Exit Side Effects
        when (oldMode) {
            VisionMode.Active -> {
                // Moving away from Active? Maybe pause heartbeat?
            }
            else -> {}
        }

        // 2. Update State
        _visionMode.value = newMode

        // 3. Enter Side Effects (Lifecycle Management)
        when (newMode) {
            VisionMode.Idle -> {
                Log.i(TAG, "Lifecycle: IDLE. Releasing resources.")
                // Absolute Zero: Release all models
                ocrModel.release()
                poseModel.release()
                // liteRTWrapper.release() // If applicable
                
                // Release Heavy Bitmap
                reuseBitmap?.recycle()
                reuseBitmap = null
            }
            VisionMode.Standby -> {
                Log.i(TAG, "Lifecycle: STANDBY. Warming up models.")
                // Warmup if needed
            }
            VisionMode.Active -> {
                Log.i(TAG, "Lifecycle: ACTIVE. Models ready.")
                 // Ensure models are prepared (Lazy load in processing loop handles this too, but good to check)
                 // ocrModel.prepare() 
            }
            VisionMode.Frozen -> {
                Log.i(TAG, "Lifecycle: FROZEN. Pausing processing.")
            }
        }
    }

    val analyzer = ImageAnalysis.Analyzer { imageProxy ->
        // State Check
        val currentMode = _visionMode.value
        
        if (currentMode != VisionMode.Active) {
            imageProxy.close()
            return@Analyzer
        }

        // Interval checks for continuous sensing
        val currentTs = System.currentTimeMillis()
        val needsOcr = configOcrEnabled && (currentTs - lastOcrTime > OCR_INTERVAL)
        val needsPose = configPoseEnabled && (currentTs - lastPoseTime > POSE_INTERVAL)
        val needsDetect = configDetectionEnabled && (currentTs - lastDetectionTime > DETECT_INTERVAL)
        val needsInstant = instantCaptureRequested
        
        // ... (rest of processing loop)

        if (needsOcr || needsPose || needsDetect || needsInstant) {
            // Reset one-shot flags
            if (needsInstant) instantCaptureRequested = false

            // 1. UPDATE REUSE BITMAP
            if (reuseBitmap == null || reuseBitmap?.width != imageProxy.width || reuseBitmap?.height != imageProxy.height) {
                Log.i(TAG, "Allocating new Reusable Bitmap: ${imageProxy.width}x${imageProxy.height}")
                reuseBitmap?.recycle()
                reuseBitmap = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
            }
            
            // Fast Copy (Zero Allocation if size matches)
            imageProxy.planes[0].buffer.rewind()
            reuseBitmap!!.copyPixelsFromBuffer(imageProxy.planes[0].buffer)

            val processingBitmap = reuseBitmap!!

            // 2. RUN MODELS (Pass the reusable bitmap directy)
            // Note: Models must NOT hold a reference to this bitmap asynchronously after this function returns.
            // TFLite wrappers (Pose/LiteRT) convert to ByteBuffer immediately, so this is safe.
            // ML Kit (OCR) InputImage takes a reference, but we are throttled to 300ms, mostly safe.
            // For rigorous safety in async OCR, we might need a copy ONLY for OCR if artifacts appear.
            
            if (needsOcr) {
                lastOcrTime = currentTs
                // CRITICAL FIX: Async OCR must use a copy to prevent "tearing" 
                // as reuseBitmap will be overwritten by the next frame 300ms later.
                // ML Kit's internal processing might outlive the reuse cycle on slow devices.
                val ocrCopy = processingBitmap.copy(Bitmap.Config.ARGB_8888, false)
                ocrModel.process(ocrCopy) { results, width, height ->
                    eventBus.publish(com.xreal.nativear.core.XRealEvent.PerceptionEvent.OcrDetected(results, width, height))
                    ocrCopy.recycle()
                }
            }
            
            // Pose is generally faster, but for absolute safety, we should consider it too. 
            // However, Pose loops are usually tighter. Leaving as Zero-Alloc for now unless artifacts reported.
            if (needsPose) {
                lastPoseTime = currentTs
                poseModel.process(processingBitmap) { results ->
                    eventBus.publish(com.xreal.nativear.core.XRealEvent.PerceptionEvent.PoseDetected(results))
                }
            }
            
            if (needsDetect) {
                lastDetectionTime = currentTs
                val detections = liteRTWrapper.detect(processingBitmap) // Synchronous
                if (detections.isNotEmpty()) {
                    eventBus.publish(com.xreal.nativear.core.XRealEvent.PerceptionEvent.ObjectsDetected(detections))
                    
                    // Visual Embedding needs a persistence copy as it runs in background scope
                    val embeddingCopy = processingBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    publishVisualEmbedding(embeddingCopy, detections.firstOrNull()?.label ?: "scene")
                }
            }

            if (needsInstant) {
                Log.i(TAG, "Instant capture complete, publishing snapshot")
                // Snapshots require a permanent copy for the UI/Storage
                val snapshotCopy = processingBitmap.copy(Bitmap.Config.ARGB_8888, false)
                eventBus.publish(com.xreal.nativear.core.XRealEvent.PerceptionEvent.SceneCaptured(
                    bitmap = snapshotCopy,
                    ocrText = "Instant Snapshot"
                ))
            }
            
            // For "Latest Bitmap" Preview (Debugging)
            // We can't hold reuseBitmap, so we assume the UI pulls it on demand or we cache a small preview?
            // Existing logic cached 'latestBitmap' which was a copy. 
            // To save memory, let's only copy if specifically requested or update a smaller cached version?
            // For now, removing the continuous 'latestBitmap' caching unless needed to save huge memory.
            // If internal components need it, they usually ask via event.
            // legacy 'latestBitmap' support:
            latestBitmap?.recycle()
            latestBitmap = processingBitmap.copy(Bitmap.Config.ARGB_8888, false) 
        }

        imageProxy.close()
    }

    private val translatorOptions = com.google.mlkit.nl.translate.TranslatorOptions.Builder()
        .setSourceLanguage(com.google.mlkit.nl.translate.TranslateLanguage.KOREAN)
        .setTargetLanguage(com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH)
        .build()
    private val translator = com.google.mlkit.nl.translate.Translation.getClient(translatorOptions)
    private var isTranslationDownloaded = false

    init {
        translator.downloadModelIfNeeded().addOnSuccessListener { isTranslationDownloaded = true }
    }

    override fun setOcrEnabled(enabled: Boolean) {
        configOcrEnabled = enabled
        Log.i(TAG, "Config: OCR Enabled = $enabled")
        updateState()
    }

    override fun setPoseEnabled(enabled: Boolean) {
        configPoseEnabled = enabled
        Log.i(TAG, "Config: Pose Enabled = $enabled")
        updateState()
    }

    override fun setSceneCaptureEnabled(enabled: Boolean) {
        isSceneCaptureEnabled = enabled
    }

    override fun setDetectionEnabled(enabled: Boolean) {
        configDetectionEnabled = enabled
        Log.i(TAG, "Config: Detection Enabled = $enabled")
        updateState()
    }

    override fun captureSceneSnapshot() {
        Log.i(TAG, "Requesting instant scene snapshot...")
        instantCaptureRequested = true
    }

    override fun translate(text: String, onResult: (String) -> Unit) {
        if (isTranslationDownloaded) {
            translator.translate(text).addOnSuccessListener { onResult(it) }
        } else {
            onResult(text)
        }
    }

    override fun getLatestBitmap(): Bitmap? {
        return latestBitmap
    }

    override fun cycleCamera() {
        Log.i(TAG, "Cycling camera...")
    }

    private var lastEmbeddingTime = 0L
    
    private fun publishVisualEmbedding(bitmap: Bitmap, label: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEmbeddingTime < 5000) {
            return // Throttle to once every 5 seconds
        }
        lastEmbeddingTime = currentTime
        
        scope.launch {
            try {
                val embedding = imageEmbedder.embed(bitmap)
                if (embedding != null) {
                    val location = locationService.getCurrentLocation()
                    val embeddingBytes = floatArrayToByteArray(embedding)
                    
                    eventBus.publish(com.xreal.nativear.core.XRealEvent.PerceptionEvent.VisualEmbedding(
                        bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false),
                        embedding = embeddingBytes,
                        label = label,
                        timestamp = currentTime,
                        latitude = location?.latitude,
                        longitude = location?.longitude
                    ))
                    Log.d(TAG, "Published VisualEmbedding event: $label")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to publish visual embedding", e)
            }
        }
    }
    
    private fun floatArrayToByteArray(floats: FloatArray): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(floats.size * 4)
        buffer.order(java.nio.ByteOrder.nativeOrder())
        for (f in floats) {
            buffer.putFloat(f)
        }
        return buffer.array()
    }
}

