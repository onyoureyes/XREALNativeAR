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
import com.xreal.nativear.core.ErrorReporter
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
    private val scope: CoroutineScope,
    private val faceDetector: FaceDetector,
    private val faceEmbedder: FaceEmbedder,
    private val personRepository: PersonRepository,
    private val facialExpressionClassifier: FacialExpressionClassifier,
    private val cadenceConfig: com.xreal.nativear.cadence.CadenceConfig,
    private val handTrackingModel: com.xreal.nativear.hand.HandTrackingModel? = null
) : IVisionService {

    private val TAG = "VisionManager"

    // State Machine
    private val _visionMode = MutableStateFlow<VisionMode>(VisionMode.Idle)
    val visionMode: StateFlow<VisionMode> = _visionMode.asStateFlow()

    // Configuration Flags
    private var configOcrEnabled = false
    private var configPoseEnabled = false
    private var configDetectionEnabled = true
    @Volatile
    var configHandTrackingEnabled = false
    @Volatile
    private var instantCaptureRequested = false
    private var isSceneCaptureEnabled = false // Legacy?

    // Throttling
    private var lastOcrTime = 0L
    private var lastPoseTime = 0L
    private var lastDetectionTime = 0L
    private var lastHandTrackingTime = 0L

    // ★ 프레임 품질 추적: 어두운 프레임 / 연속 빈 감지 → 자동 backoff
    private var consecutiveEmptyDetections = 0
    companion object {
        private const val DARK_FRAME_LUMINANCE_THRESHOLD = 18   // 0-255, 이 이하면 카메라 꺼짐으로 판단
        private const val DARK_FRAME_SKIP_MULTIPLIER = 4        // 어두운 프레임 → 다음 감지까지 4× 인터벌
        private const val EMPTY_BACKOFF_THRESHOLD = 10          // 연속 N회 빈 감지 후 backoff 시작
        private const val EMPTY_FRAME_SKIP_MULTIPLIER = 1       // 빈 감지 → 다음 감지까지 +1 인터벌 추가
    }


    private var reuseBitmap: Bitmap? = null
    private var latestBitmap: Bitmap? = null
    // OCR bitmap pool: two bitmaps alternating to avoid copy while ML Kit processes async
    private var ocrBitmapA: Bitmap? = null
    private var ocrBitmapB: Bitmap? = null
    private var ocrBitmapToggle = false
    @Volatile private var ocrBitmapAInUse = false
    @Volatile private var ocrBitmapBInUse = false
    private var frameCounter = 0

    // When true, CameraX analyzer is bypassed (external source like SLAM camera is active)
    // ★ StateFlow로 변경: MainActivity에서 XREAL 연결 여부를 observe하여 PreviewView 표시 제어
    private val _isExternalFrameSourceActive = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isExternalFrameSourceActiveFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _isExternalFrameSourceActive
    var isExternalFrameSourceActive: Boolean
        get() = _isExternalFrameSourceActive.value
        set(value) { _isExternalFrameSourceActive.value = value }

    // DeviceHealthMonitor가 참조: 안경 연결 여부 + 프레임 수신 상태 판단용
    @Volatile var lastFrameReceivedMs: Long = 0L
    @Volatile var currentFrameRateFps: Float = 0f
    private var fpsFrameCount = 0
    private var fpsWindowStartMs = 0L

    // Helper: Determine target mode based on config
    private fun resolveTargetMode(): VisionMode {
        if (isFrozen()) return VisionMode.Frozen
        
        val anyEnabled = configOcrEnabled || configPoseEnabled || configDetectionEnabled || configHandTrackingEnabled
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
                ocrModel.release()
                poseModel.release()

                // Release all bitmaps
                reuseBitmap?.recycle(); reuseBitmap = null
                latestBitmap?.recycle(); latestBitmap = null
                ocrBitmapA?.recycle(); ocrBitmapA = null
                ocrBitmapB?.recycle(); ocrBitmapB = null
                ocrBitmapAInUse = false; ocrBitmapBInUse = false
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
        // Skip CameraX frames when external source (SLAM camera) is active
        if (isExternalFrameSourceActive) {
            imageProxy.close()
            return@Analyzer
        }

        val currentMode = _visionMode.value
        if (currentMode != VisionMode.Active) {
            imageProxy.close()
            return@Analyzer
        }

        // Frame skip: reduce CPU load by only processing every Nth frame
        // Exception: always process if instant capture is requested
        frameCounter++
        if (frameCounter % cadenceConfig.current.frameSkip != 0 && !instantCaptureRequested) {
            imageProxy.close()
            return@Analyzer
        }

        // Early bail-out: check if any model needs this frame BEFORE copying pixels (~1.2MB)
        val currentTs = System.currentTimeMillis()
        val needsOcr = configOcrEnabled && (currentTs - lastOcrTime > cadenceConfig.current.ocrIntervalMs)
        val needsPose = configPoseEnabled && (currentTs - lastPoseTime > cadenceConfig.current.poseIntervalMs)
        val needsDetect = configDetectionEnabled && (currentTs - lastDetectionTime > cadenceConfig.current.detectIntervalMs)
        val needsHands = configHandTrackingEnabled && handTrackingModel?.isReady == true && (currentTs - lastHandTrackingTime > cadenceConfig.current.handTrackingIntervalMs)
        val needsInstant = instantCaptureRequested

        if (!(needsOcr || needsPose || needsDetect || needsHands || needsInstant)) {
            imageProxy.close()
            return@Analyzer
        }

        // 1. UPDATE REUSE BITMAP
        if (reuseBitmap == null || reuseBitmap?.width != imageProxy.width || reuseBitmap?.height != imageProxy.height) {
            Log.i(TAG, "Allocating new Reusable Bitmap: ${imageProxy.width}x${imageProxy.height}")
            reuseBitmap?.recycle()
            reuseBitmap = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
        }

        // Fast Copy (Zero Allocation if size matches)
        imageProxy.planes[0].buffer.rewind()
        reuseBitmap!!.copyPixelsFromBuffer(imageProxy.planes[0].buffer)

        processBitmap(reuseBitmap!!, currentTs)
        imageProxy.close()
    }

    /**
     * Feed a frame from an external source (e.g., OV580 SLAM camera).
     * The bitmap is used directly and must remain valid for the duration of this call.
     */
    fun feedExternalFrame(bitmap: Bitmap) {
        val now = System.currentTimeMillis()
        lastFrameReceivedMs = now
        // FPS 추적: 1초 윈도우
        fpsFrameCount++
        if (fpsWindowStartMs == 0L) fpsWindowStartMs = now
        val elapsed = now - fpsWindowStartMs
        if (elapsed >= 1000L) {
            currentFrameRateFps = fpsFrameCount * 1000f / elapsed
            fpsFrameCount = 0
            fpsWindowStartMs = now
        }
        processBitmap(bitmap, null)
    }

    /**
     * Core processing pipeline: runs enabled models against a Bitmap.
     * Called from both CameraX analyzer (with pre-computed timestamp) and external SLAM frame feed.
     * @param precomputedTs Pre-computed timestamp from analyzer to avoid redundant checks; null for external frames.
     */
    private fun processBitmap(bitmap: Bitmap, precomputedTs: Long?) {
        val currentMode = _visionMode.value
        if (currentMode != VisionMode.Active) return

        val currentTs = precomputedTs ?: System.currentTimeMillis()
        val needsOcr = configOcrEnabled && (currentTs - lastOcrTime > cadenceConfig.current.ocrIntervalMs)
        val needsPose = configPoseEnabled && (currentTs - lastPoseTime > cadenceConfig.current.poseIntervalMs)
        val needsDetect = configDetectionEnabled && (currentTs - lastDetectionTime > cadenceConfig.current.detectIntervalMs)
        val needsHands = configHandTrackingEnabled && handTrackingModel?.isReady == true && (currentTs - lastHandTrackingTime > cadenceConfig.current.handTrackingIntervalMs)
        val needsInstant = instantCaptureRequested

        if (!(needsOcr || needsPose || needsDetect || needsHands || needsInstant)) return

        if (needsInstant) instantCaptureRequested = false

        if (needsOcr) {
            lastOcrTime = currentTs
            // Use double-buffered OCR bitmap pool instead of bitmap.copy()
            val ocrBitmap = getOcrBitmap(bitmap.width, bitmap.height)
            if (ocrBitmap != null) {
                val canvas = android.graphics.Canvas(ocrBitmap)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                val isSlotA = ocrBitmapToggle // after getOcrBitmap: toggle=true→A, toggle=false→B
                ocrModel.process(ocrBitmap) { results, width, height ->
                    eventBus.publish(com.xreal.nativear.core.XRealEvent.PerceptionEvent.OcrDetected(results, width, height))
                    // Mark pool slot as available (no recycle — it's reused)
                    if (isSlotA) ocrBitmapAInUse = false else ocrBitmapBInUse = false
                }
            }
        }

        if (needsPose) {
            lastPoseTime = currentTs
            poseModel.process(bitmap) { results ->
                eventBus.publish(com.xreal.nativear.core.XRealEvent.PerceptionEvent.PoseDetected(results))
            }
        }

        if (needsDetect) {
            lastDetectionTime = currentTs

            // ★ 프레임 품질 체크: 너무 어두운 프레임(카메라 꺼짐/차단) → YOLO 건너뜀
            if (isFrameTooDark(bitmap)) {
                consecutiveEmptyDetections++
                if (consecutiveEmptyDetections % 20 == 1) {
                    Log.d(TAG, "어두운 프레임 — YOLO 건너뜀 (연속 ${consecutiveEmptyDetections}회)")
                }
                // lastDetectionTime을 미래로 밀어 다음 감지 간격을 늘림
                lastDetectionTime += cadenceConfig.current.detectIntervalMs * DARK_FRAME_SKIP_MULTIPLIER
            } else {
                val detections = liteRTWrapper.detect(bitmap)
                if (detections.isNotEmpty()) {
                    consecutiveEmptyDetections = 0  // 감지됨 → 연속 카운터 리셋
                    eventBus.publish(com.xreal.nativear.core.XRealEvent.PerceptionEvent.ObjectsDetected(detections))
                    // Extract embedding synchronously (no bitmap copy needed — imageEmbedder runs on same thread)
                    val now = System.currentTimeMillis()
                    if (now - lastEmbeddingTime >= cadenceConfig.current.visualEmbeddingIntervalMs) {
                        publishVisualEmbedding(bitmap, detections.firstOrNull()?.label ?: "scene")
                    }
                } else {
                    // 감지 없음 → 연속 카운터 증가
                    consecutiveEmptyDetections++
                    // 연속 빈 감지 임계값 초과 → 다음 감지 간격 연장 (불필요한 YOLO 추론 감소)
                    if (consecutiveEmptyDetections >= EMPTY_BACKOFF_THRESHOLD) {
                        lastDetectionTime += cadenceConfig.current.detectIntervalMs * EMPTY_FRAME_SKIP_MULTIPLIER
                        if (consecutiveEmptyDetections % EMPTY_BACKOFF_THRESHOLD == 0) {
                            Log.d(TAG, "빈 감지 ${consecutiveEmptyDetections}회 → 다음 감지 간격 연장")
                        }
                    }
                }

                // Face pipeline: when "person" detected, run face detection + embedding
                val personDetections = detections.filter { it.label == "person" && it.confidence > 0.5f }
                if (personDetections.isNotEmpty() && faceDetector.isReady && faceEmbedder.isReady) {
                    processFaces(bitmap, currentTs)
                }
            }

            // Hand tracking: runs regardless of YOLO dark-frame skip (hands can be tracked in dim light)
            if (needsHands && handTrackingModel != null) {
                lastHandTrackingTime = currentTs
                try {
                    val hands = handTrackingModel.detect(bitmap)
                    if (hands.isNotEmpty()) {
                        eventBus.publish(com.xreal.nativear.core.XRealEvent.PerceptionEvent.HandsDetected(
                            hands = hands,
                            timestamp = currentTs
                        ))
                    }
                } catch (e: Exception) {
                    ErrorReporter.report(TAG, "Hand tracking failed", e)
                }
            }

            // Swap latestBitmap: copy into pre-allocated buffer instead of creating new bitmap
            if (latestBitmap == null || latestBitmap?.width != bitmap.width || latestBitmap?.height != bitmap.height) {
                latestBitmap?.recycle()
                latestBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            }
            val canvas = android.graphics.Canvas(latestBitmap!!)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        }

        if (needsInstant) {
            Log.i(TAG, "Instant capture complete, publishing snapshot")
            val snapshotCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            eventBus.publish(com.xreal.nativear.core.XRealEvent.PerceptionEvent.SceneCaptured(
                bitmap = snapshotCopy,
                ocrText = "Instant Snapshot"
            ))
        }
    }

    /**
     * ★ 프레임 밝기 체크: 10×10 픽셀 샘플링으로 평균 휘도 계산.
     * 평균 휘도 < DARK_FRAME_LUMINANCE_THRESHOLD → 카메라 꺼짐/차단으로 판단 → YOLO 생략.
     * BT.601 가중치 사용 (R×0.299 + G×0.587 + B×0.114).
     */
    private fun isFrameTooDark(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return true
        val stepX = (w / 10).coerceAtLeast(1)
        val stepY = (h / 10).coerceAtLeast(1)
        var totalLuminance = 0L
        var sampleCount = 0
        for (yi in 0 until 10) {
            for (xi in 0 until 10) {
                val px = bitmap.getPixel(xi * stepX, yi * stepY)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                totalLuminance += (299L * r + 587L * g + 114L * b) / 1000L
                sampleCount++
            }
        }
        val avgLuminance = if (sampleCount > 0) totalLuminance / sampleCount else 0L
        return avgLuminance < DARK_FRAME_LUMINANCE_THRESHOLD
    }

    private val translatorOptions = com.google.mlkit.nl.translate.TranslatorOptions.Builder()
        .setSourceLanguage(com.google.mlkit.nl.translate.TranslateLanguage.KOREAN)
        .setTargetLanguage(com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH)
        .build()
    private val translator = com.google.mlkit.nl.translate.Translation.getClient(translatorOptions)
    private var isTranslationDownloaded = false

    init {
        translator.downloadModelIfNeeded().addOnSuccessListener { isTranslationDownloaded = true }
        // Resolve initial vision mode based on default config flags
        updateState()
    }

    override fun setOcrEnabled(enabled: Boolean) {
        configOcrEnabled = enabled
        Log.i(TAG, "Config: OCR Enabled = $enabled")
        if (!enabled) {
            // Clear OCR overlay when disabled
            eventBus.publish(com.xreal.nativear.core.XRealEvent.PerceptionEvent.OcrDetected(emptyList(), 640, 480))
        }
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

    override fun setHandTrackingEnabled(enabled: Boolean) {
        configHandTrackingEnabled = enabled
        Log.i(TAG, "Config: Hand Tracking Enabled = $enabled")
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
        // ★ CameraStreamManager에 위임 (소스 순환: RGB → SLAM → Phone → ...)
        try {
            org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.camera.CameraStreamManager>()
                ?.cycle()
                ?: Log.w(TAG, "CameraStreamManager 미등록 — cycle 불가")
        } catch (e: Exception) {
            Log.w(TAG, "cycleCamera 실패: ${e.message}")
        }
    }

    /**
     * Double-buffered OCR bitmap pool. Returns an available bitmap slot,
     * or null if both are in use (OCR backpressure — skip this frame).
     */
    private fun getOcrBitmap(width: Int, height: Int): Bitmap? {
        ocrBitmapToggle = !ocrBitmapToggle
        return if (ocrBitmapToggle) {
            if (ocrBitmapAInUse) return null // Both busy
            if (ocrBitmapA == null || ocrBitmapA?.width != width || ocrBitmapA?.height != height) {
                ocrBitmapA?.recycle()
                ocrBitmapA = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
            ocrBitmapAInUse = true
            ocrBitmapA
        } else {
            if (ocrBitmapBInUse) return null
            if (ocrBitmapB == null || ocrBitmapB?.width != width || ocrBitmapB?.height != height) {
                ocrBitmapB?.recycle()
                ocrBitmapB = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
            ocrBitmapBInUse = true
            ocrBitmapB
        }
    }

    private var lastEmbeddingTime = 0L

    /**
     * Extract embedding synchronously from bitmap and publish event.
     * No bitmap copy — embedding is extracted on the calling thread (analyzer thread),
     * and only the lightweight embedding ByteArray is published via EventBus.
     */
    private fun publishVisualEmbedding(bitmap: Bitmap, label: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEmbeddingTime < cadenceConfig.current.visualEmbeddingIntervalMs) {
            return
        }
        lastEmbeddingTime = currentTime

        try {
            // Extract embedding synchronously — bitmap stays valid on analyzer thread
            val embedding = imageEmbedder.embed(bitmap) ?: return
            val embeddingBytes = floatArrayToByteArray(embedding)
            val location = locationService.getCurrentLocation()

            eventBus.publish(com.xreal.nativear.core.XRealEvent.PerceptionEvent.VisualEmbedding(
                embedding = embeddingBytes,
                label = label,
                timestamp = currentTime,
                latitude = location?.latitude,
                longitude = location?.longitude
            ))
            Log.d(TAG, "Published VisualEmbedding event: $label")
        } catch (e: Exception) {
            ErrorReporter.report(TAG, "Failed to publish visual embedding", e)
        }
    }
    
    /**
     * Face pipeline: detect faces → embed → identify/register.
     * Called when YOLO detects "person" and face models are ready.
     */
    private fun processFaces(bitmap: Bitmap, timestamp: Long) {
        val faces = faceDetector.detect(bitmap)
        if (faces.isEmpty()) return

        Log.d(TAG, "Detected ${faces.size} face(s)")
        val faceInfos = mutableListOf<FaceInfo>()

        for (face in faces) {
            val crop = cropFace(bitmap, face) ?: continue
            val embedding = faceEmbedder.embed(crop)

            // FER: classify expression before recycling crop
            val expression = if (facialExpressionClassifier.isReady) {
                facialExpressionClassifier.classify(crop)
            } else null
            crop.recycle()

            faceInfos.add(FaceInfo(
                x = face.x,
                y = face.y,
                width = face.width,
                height = face.height,
                confidence = face.confidence,
                embedding = embedding?.let { floatArrayToByteArray(it) },
                expression = expression?.first,
                expressionScore = expression?.second
            ))

            // Async person identification (DB operations)
            if (embedding != null) {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        personRepository.identifyOrRegister(embedding)
                    } catch (e: Exception) {
                        ErrorReporter.report(TAG, "Face identification failed", e)
                    }
                }
            }
        }

        if (faceInfos.isNotEmpty()) {
            eventBus.publish(com.xreal.nativear.core.XRealEvent.PerceptionEvent.FacesDetected(faceInfos, timestamp))
        }
    }

    /**
     * Crop a face region from the bitmap with 20% margin.
     * Returns null if the crop is too small (< 20px).
     */
    private fun cropFace(bitmap: Bitmap, face: FaceDetector.Face): Bitmap? {
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()

        val margin = 0.2f
        val fw = face.width * bw
        val fh = face.height * bh
        val cx = face.x * bw
        val cy = face.y * bh

        val left = ((cx - fw / 2) - fw * margin).toInt().coerceIn(0, bitmap.width - 1)
        val top = ((cy - fh / 2) - fh * margin).toInt().coerceIn(0, bitmap.height - 1)
        val right = ((cx + fw / 2) + fw * margin).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = ((cy + fh / 2) + fh * margin).toInt().coerceIn(top + 1, bitmap.height)

        val cropW = right - left
        val cropH = bottom - top
        if (cropW < 20 || cropH < 20) return null

        return try {
            Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
        } catch (e: Exception) {
            Log.e(TAG, "Face crop failed", e)
            null
        }
    }

    /**
     * H2 FIX: Release all resources including cameraExecutor.
     * Must be called on app shutdown to prevent ExecutorService thread leak.
     */
    fun release() {
        Log.i(TAG, "Releasing VisionManager resources")
        transitionTo(VisionMode.Idle) // Releases models + bitmaps
        try {
            cameraExecutor.shutdown()
            if (!cameraExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                cameraExecutor.shutdownNow()
            }
        } catch (e: Exception) {
            Log.w(TAG, "cameraExecutor shutdown error: ${e.message}")
            cameraExecutor.shutdownNow()
        }
        translator.close()
        embeddingBuffer = null
        Log.i(TAG, "VisionManager released")
    }

    // Pre-allocated embedding conversion buffer (1280 floats = 5120 bytes for MobileNetV3)
    private var embeddingBuffer: java.nio.ByteBuffer? = null

    private fun floatArrayToByteArray(floats: FloatArray): ByteArray {
        val neededSize = floats.size * 4
        val buf = embeddingBuffer?.let {
            if (it.capacity() >= neededSize) it else null
        } ?: java.nio.ByteBuffer.allocate(neededSize).apply {
            order(java.nio.ByteOrder.nativeOrder())
            embeddingBuffer = this
        }
        buf.rewind()
        for (f in floats) buf.putFloat(f)
        return buf.array().copyOf(neededSize)
    }
}

