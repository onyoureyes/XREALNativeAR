package com.xreal.nativear

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.xreal.nativear.core.VisionServiceDelegate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
    private val locationService: ILocationService
) : IVisionService {

    private val TAG = "VisionManager"
    private var isOcrEnabled = false
    private var isPoseEnabled = false
    private var isDetectionEnabled = true 
    private var isSceneCaptureEnabled = false
    private var latestBitmap: Bitmap? = null
    private var isFrozen = false

    private var lastOcrTime = 0L
    private var lastPoseTime = 0L
    private var lastDetectionTime = 0L
    private var lastEmbeddingTime = 0L
    
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    init {
        subscribeToEvents()
    }

    private fun subscribeToEvents() {
        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is com.xreal.nativear.core.XRealEvent.SystemEvent.VisionStateChanged -> {
                        isFrozen = event.isFrozen
                    }
                    else -> {}
                }
            }
        }
    }

    val analyzer = ImageAnalysis.Analyzer { imageProxy ->
        if (isFrozen) {
            imageProxy.close()
            return@Analyzer
        }

        val currentTs = System.currentTimeMillis()
        val needsOcr = (isOcrEnabled && currentTs - lastOcrTime >= 1000)
        val needsPose = (isPoseEnabled && currentTs - lastPoseTime >= 300)
        val needsDetect = (isDetectionEnabled && currentTs - lastDetectionTime >= 1000)

        if (needsOcr || needsPose || needsDetect) {
            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap != null) {
                if (needsOcr) {
                    lastOcrTime = currentTs
                    val bitmapCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    ocrModel.process(bitmapCopy) { results, width, height ->
                        eventBus.publish(com.xreal.nativear.core.XRealEvent.PerceptionEvent.OcrDetected(results, width, height))
                        bitmapCopy.recycle()
                    }
                }
                if (needsPose) {
                    lastPoseTime = currentTs
                    val bitmapCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    poseModel.process(bitmapCopy) { results ->
                        // Post-process pose results
                        bitmapCopy.recycle()
                    }
                }
                if (needsDetect) {
                    lastDetectionTime = currentTs
                    val bitmapCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    val detections = liteRTWrapper.detect(bitmapCopy)
                    if (detections.isNotEmpty()) {
                        eventBus.publish(com.xreal.nativear.core.XRealEvent.PerceptionEvent.ObjectsDetected(detections))
                        
                        // Generate and publish visual embedding
                        publishVisualEmbedding(bitmapCopy, detections.firstOrNull()?.label ?: "scene")
                    }
                    bitmapCopy.recycle()
                }
                if (needsOcr || needsPose || needsDetect) {
                    val bitmapCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    latestBitmap?.recycle()
                    latestBitmap = bitmapCopy
                }
                bitmap.recycle()
            }
        }


        imageProxy.close()
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * imageProxy.width
        
        val bitmap = Bitmap.createBitmap(
            imageProxy.width + rowPadding / pixelStride,
            imageProxy.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
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
        isOcrEnabled = enabled
        Log.i(TAG, "OCR Enabled: $enabled")
    }

    override fun setPoseEnabled(enabled: Boolean) {
        isPoseEnabled = enabled
        Log.i(TAG, "Pose Enabled: $enabled")
    }

    override fun setSceneCaptureEnabled(enabled: Boolean) {
        isSceneCaptureEnabled = enabled
    }

    override fun setDetectionEnabled(enabled: Boolean) {
        isDetectionEnabled = enabled
        Log.i(TAG, "Detection Enabled: $enabled")
    }

    override fun captureSceneSnapshot() {
        Log.i(TAG, "Capturing scene snapshot (Event Triggered)")
        // In a fully decoupled system, we might need a signal to actually grab the bitmap.
        // For now, we signal that a snapshot is ready/requested.
        eventBus.publish(com.xreal.nativear.core.XRealEvent.PerceptionEvent.SceneCaptured(
            bitmap = latestBitmap ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
            ocrText = "Decoupled Snapshot"
        ))
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

