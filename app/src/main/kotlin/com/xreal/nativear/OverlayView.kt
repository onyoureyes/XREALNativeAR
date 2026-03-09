package com.xreal.nativear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.xreal.nativear.remote.PipPosition
import com.xreal.nativear.hand.HandData
import com.xreal.nativear.hand.HandGestureType
import com.xreal.nativear.hand.GestureEvent
import com.xreal.nativear.interaction.HUDPhysicsEngine
import com.xreal.nativear.spatial.AnchorLabel2D
import com.xreal.nativear.spatial.AnchorType
import com.xreal.nativear.spatial.SpatialUIManager
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    init {
        // GPU hardware layer: onDraw() commands → HWUI display list → Adreno 730 GPU
        // Cached in GPU texture — no CPU redraw unless invalidate() is called.
        // LAYER_TYPE_HARDWARE is safe here since we use only hardware-accelerated Canvas ops.
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    var ocrResults: List<OcrResult> = emptyList()
    var detections: List<Detection> = emptyList()
        set(value) {
            field = value
            postInvalidate()
        }
    var poseResults: List<PoseKeypoint> = emptyList()
        set(value) {
            field = value
            postInvalidate()
        }
    var isFrozen: Boolean = false

        private set
    var stabilityStartTime: Long = 0L

    private var imageWidth = 640
    private var imageHeight = 480
    private var stabilityProgress = 0 // 0 to 100
    private var stepProgress = 0 // 0 to 100 (Top Bar)

    // Paints
    private val boxPaint = Paint().apply {
        color = Color.parseColor("#80000000") // Semi-transparent black
        style = Paint.Style.FILL
    }
    private val textPaint = Paint().apply {
        color = Color.CYAN
        textSize = 50f
        style = Paint.Style.FILL
        isFakeBoldText = true
    }
    private val roiPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
        pathEffect = DashPathEffect(floatArrayOf(50f, 20f), 0f)
    }
    private val progressPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 15f
        strokeCap = Paint.Cap.ROUND
    }
    private val stepPaint = Paint().apply {
        color = Color.parseColor("#FFD700") // Gold/Yellow
        style = Paint.Style.FILL
    }
    private val frozenPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 10f
    }
    
    // Debug Text Paint
    private val debugPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }

    private val jointPaint = Paint().apply {
        color = Color.MAGENTA
        style = Paint.Style.FILL
    }
    
    private val bonePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private var debugLog: String = ""

    // Spatial Anchor Labels (3D → 2D 재투영 결과)
    private val anchorLabels = mutableListOf<AnchorLabel2D>()
    /** SpatialUIManager 연결 — 스무딩, 포커스, 깊이 정렬된 라벨 사용 */
    var spatialUIManager: SpatialUIManager? = null
    private val anchorTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 36f
        isFakeBoldText = true
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }
    private val anchorBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val anchorDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    // 포커스 앵커 하이라이트 테두리
    private val anchorFocusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#FFFFFF")
    }
    // 콘텐츠 패널 배경
    private val panelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val panelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        color = Color.WHITE
    }
    private val panelTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 32f
        isFakeBoldText = true
        color = Color.WHITE
    }

    // Hand Tracking Layer
    private var handLandmarks: List<HandData> = emptyList()
    private var activeGestures: List<GestureEvent> = emptyList()
    private var drawTrail: List<Pair<Float, Float>> = emptyList()
    private val handJointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val handBonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }
    private val handTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#FF6600")
    }
    private val gestureLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 32f
        color = Color.parseColor("#FFD700")
        isFakeBoldText = true
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    /** 물리 엔진 참조 (HandInteractionManager에서 설정) */
    var physicsEngine: HUDPhysicsEngine? = null

    // Remote Camera PIP Layer
    var remoteCameraBitmap: Bitmap? = null
    var remoteCameraVisible: Boolean = false
    var remoteCameraPipPosition: PipPosition = PipPosition.BOTTOM_RIGHT
    var remoteCameraPipSizePercent: Float = 30f
    private val pipBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00CCFF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val pipLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        color = Color.parseColor("#00CCFF")
        isFakeBoldText = true
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val pipBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }

    fun updateRemoteCameraFrame(bitmap: Bitmap) {
        remoteCameraBitmap = bitmap
        postInvalidate()
    }

    // AI Drawing Layer
    private val drawingElements = mutableListOf<DrawElement>()
    private val drawElementPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrowPath = Path()
    private var nextAutoId = 0

    fun setOcrResults(results: List<OcrResult>, width: Int, height: Int) {
        ocrResults = results
        imageWidth = width
        imageHeight = height
        postInvalidate()
    }

    /**
     * 공간 앵커 라벨 업데이트 (SpatialAnchorManager → OverlayView).
     *
     * 매 프레임 (~30Hz) 호출. 월드 3D → 화면 2D 재투영된 앵커 위치를 받아 렌더링.
     * 퍼센트 좌표 (0-100)로 전달되며, DrawElement과 동일한 좌표계.
     */
    fun updateAnchorLabels(labels: List<AnchorLabel2D>) {
        anchorLabels.clear()
        anchorLabels.addAll(labels)
        postInvalidate()
    }

    /** 손 추적 데이터 업데이트 */
    fun updateHandData(hands: List<HandData>, gestures: List<GestureEvent> = emptyList(), trail: List<Pair<Float, Float>> = emptyList()) {
        handLandmarks = hands
        activeGestures = gestures
        drawTrail = trail
        postInvalidate()
    }




    fun getLatestText(): String {
        return ocrResults.filter { it.isValid }.joinToString(" ") { it.text }
    }

    fun setStabilityProgress(progress: Int) {
        stabilityProgress = progress
        postInvalidate()
    }

    fun setStepProgress(progress: Int) {
        stepProgress = progress
        postInvalidate()
    }

    fun setFrozen(frozen: Boolean) {
        isFrozen = frozen
        if (!frozen) {
            stabilityProgress = 0
            ocrResults = emptyList()
        }
        postInvalidate()
    }

    fun setDebugLog(log: String) {
        debugLog = log
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // 1. Bottom Stability Bar (Motion)
        if (stabilityProgress > 0 && !isFrozen) {
            val barHeight = 10f
            val barWidth = width * (stabilityProgress / 100f)
            canvas.drawRect(0f, height - barHeight, barWidth, height.toFloat(), progressPaint)
        }

        // 2. Top Step Progress Bar (PDR)
        // Moved down by 80px to avoid Status Bar / Camera Cutout
        val topMargin = 80f
        
        // Draw Background Track
        canvas.drawRect(0f, topMargin, width.toFloat(), topMargin + 15f, boxPaint)

        if (stepProgress > 0) {
            val barHeight = 15f
            val barWidth = width * (stepProgress / 100f)
            canvas.drawRect(0f, topMargin, barWidth, topMargin + barHeight, stepPaint)
        }
        
        // Debug Text: Show explicitly "Steps: 5/15"
        val stepCountRaw = (stepProgress / 100.0 * 15).toInt()
        canvas.drawText("Steps: $stepCountRaw / 15", 40f, topMargin + 60f, textPaint)

        // 3. Object Detections & OCR & Pose
        drawDetections(canvas)
        drawOcr(canvas)
        drawPose(canvas)

        // Draw Transcript Log (Bottom)
        if (debugLog.isNotEmpty()) {
            val lines = debugLog.split("\n")
            var yLog = height - 100f - (lines.size * 50f)
            for (line in lines) {
                canvas.drawText(line, 50f, yLog, debugPaint)
                yLog += 50f
            }
        }

        // Draw Log Messages (Left Side)
        if (logMessages.isNotEmpty()) {
            var yPos = 200f
            for (msg in logMessages.takeLast(10)) {
                canvas.drawText(msg, 20f, yPos, debugPaint)
                yPos += 45f
            }
        }

        // Draw Audio Level (Right Side)
        if (audioLevel > 0f) {
            val barWidth = 30f
            val barHeight = 200f
            val fillHeight = barHeight * audioLevel
            val x = width - 60f
            val y = 200f
            
            // Background
            canvas.drawRect(x, y, x + barWidth, y + barHeight, boxPaint)
            
            // Level
            val levelPaint = Paint().apply {
                color = when {
                    audioLevel > 0.7f -> Color.RED
                    audioLevel > 0.4f -> Color.YELLOW
                    else -> Color.GREEN
                }
                style = Paint.Style.FILL
            }
            canvas.drawRect(x, y + barHeight - fillHeight, x + barWidth, y + barHeight, levelPaint)
        }

        // 5. CENTRAL DEBUG MESSAGE
        if (centralMessage.isNotEmpty()) {
            val paint = Paint().apply {
                color = if (centralMessage.startsWith("STT:")) Color.CYAN else Color.YELLOW
                textSize = 100f 
                textAlign = Paint.Align.CENTER
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = 3f
                setShadowLayer(10f, 0f, 0f, Color.BLACK)
            }
            
            val fixedY = 450f
            val displayMsg = centralMessage.replace("STT:", "").trim()
            canvas.drawText(displayMsg, width / 2f, fixedY, paint)
        }

        // 6. Spatial Anchor Labels (3D world-locked)
        drawAnchorLabels(canvas)

        // 7. Hand Skeleton + Gestures (interaction layer)
        drawHands(canvas)

        // 8. AI Drawing Layer (on top of everything, with physics)
        drawCustomElements(canvas)

        // 9. Remote Camera PIP (topmost layer)
        if (remoteCameraVisible && remoteCameraBitmap != null) {
            drawRemoteCameraPIP(canvas)
        }
    }

    /**
     * 손 스켈레톤 + 제스처 라벨 + 드로잉 궤적 렌더링.
     */
    private fun drawHands(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. 드로잉 궤적 (검지 추적선)
        if (drawTrail.size >= 2) {
            for (i in 1 until drawTrail.size) {
                val (x1, y1) = drawTrail[i - 1]
                val (x2, y2) = drawTrail[i]
                val alpha = (i.toFloat() / drawTrail.size * 255).toInt().coerceIn(50, 255)
                handTrailPaint.alpha = alpha
                canvas.drawLine(x1 / 100f * w, y1 / 100f * h, x2 / 100f * w, y2 / 100f * h, handTrailPaint)
            }
        }

        // 2. 손 스켈레톤
        for (hand in handLandmarks) {
            val lm = hand.landmarks
            if (lm.size < 21) continue

            // 색상: 오른손=파랑, 왼손=초록
            val jointColor = if (hand.isRightHand) Color.parseColor("#4488FF") else Color.parseColor("#44FF88")
            val boneColor = if (hand.isRightHand) Color.parseColor("#6699FF") else Color.parseColor("#66FFAA")
            handJointPaint.color = jointColor
            handBonePaint.color = boneColor

            // 관절 점 렌더링
            for (landmark in lm) {
                val px = landmark.x * w
                val py = landmark.y * h
                canvas.drawCircle(px, py, 6f, handJointPaint)
            }

            // 뼈대 연결선
            val bones = listOf(
                // 엄지
                intArrayOf(0, 1, 2, 3, 4),
                // 검지
                intArrayOf(0, 5, 6, 7, 8),
                // 중지
                intArrayOf(0, 9, 10, 11, 12),
                // 약지
                intArrayOf(0, 13, 14, 15, 16),
                // 소지
                intArrayOf(0, 17, 18, 19, 20),
                // 손바닥 가로
                intArrayOf(5, 9, 13, 17)
            )
            for (chain in bones) {
                for (j in 0 until chain.size - 1) {
                    val a = lm[chain[j]]
                    val b = lm[chain[j + 1]]
                    canvas.drawLine(a.x * w, a.y * h, b.x * w, b.y * h, handBonePaint)
                }
            }

            // 검지 끝에 포인터 표시
            val indexTip = lm[HandData.INDEX_TIP]
            handJointPaint.color = Color.parseColor("#FF4444")
            canvas.drawCircle(indexTip.x * w, indexTip.y * h, 10f, handJointPaint)
        }

        // 3. 활성 제스처 라벨
        for (gesture in activeGestures) {
            if (gesture.gesture == HandGestureType.NONE) continue
            val gestureText = when (gesture.gesture) {
                HandGestureType.POINT -> "Point"
                HandGestureType.PINCH -> "Pinch"
                HandGestureType.TAP -> "Tap!"
                HandGestureType.FIST -> "Fist"
                HandGestureType.OPEN_PALM -> "Palm"
                HandGestureType.PEACE -> "Peace"
                HandGestureType.THUMBS_UP -> "Thumbs Up"
                HandGestureType.SWIPE_LEFT -> "Swipe <"
                HandGestureType.SWIPE_RIGHT -> "Swipe >"
                HandGestureType.SWIPE_UP -> "Swipe ^"
                HandGestureType.SWIPE_DOWN -> "Swipe v"
                HandGestureType.PINCH_MOVE -> "Drag"
                HandGestureType.DRAW -> "Draw"
                else -> ""
            }
            if (gestureText.isNotEmpty()) {
                val px = gesture.screenX / 100f * w
                val py = gesture.screenY / 100f * h - 30f
                canvas.drawText(gestureText, px, py, gestureLabelPaint)
            }
        }
    }

    /**
     * Remote Camera PIP (Picture-in-Picture) 렌더링.
     * 원격 PC 웹캠 프레임을 라운드 코너 + 보더로 HUD에 표시.
     */
    private fun drawRemoteCameraPIP(canvas: Canvas) {
        val bitmap = remoteCameraBitmap ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val margin = 16f
        val pipW = w * (remoteCameraPipSizePercent / 100f)
        val pipH = pipW * (bitmap.height.toFloat() / bitmap.width.toFloat()) // Maintain aspect ratio

        // Calculate position based on PipPosition
        val (left, top) = when (remoteCameraPipPosition) {
            PipPosition.TOP_LEFT -> margin to margin + 100f  // Below status bar
            PipPosition.TOP_RIGHT -> (w - pipW - margin) to (margin + 100f)
            PipPosition.BOTTOM_LEFT -> margin to (h - pipH - margin - 60f)
            PipPosition.BOTTOM_RIGHT -> (w - pipW - margin) to (h - pipH - margin - 60f)
            PipPosition.CENTER -> ((w - pipW) / 2f) to ((h - pipH) / 2f)
        }

        val destRect = RectF(left, top, left + pipW, top + pipH)
        val cornerRadius = 12f

        // Save canvas state for rounded clip
        canvas.save()
        val clipPath = Path()
        clipPath.addRoundRect(destRect, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.clipPath(clipPath)

        // Draw bitmap
        canvas.drawBitmap(bitmap, null, destRect, pipBitmapPaint)
        canvas.restore()

        // Draw border
        canvas.drawRoundRect(destRect, cornerRadius, cornerRadius, pipBorderPaint)

        // Draw label "📹 PC CAM" at top-left of PIP
        canvas.drawText("📹 PC CAM", left + 8f, top + 22f, pipLabelPaint)
    }

    private fun drawDetections(canvas: Canvas) {
        val scaleX = width.toFloat() / imageWidth
        val scaleY = height.toFloat() / imageHeight
        
        for (det in detections) {
            val left = (det.x - det.width / 2) * scaleX
            val top = (det.y - det.height / 2) * scaleY
            val right = (det.x + det.width / 2) * scaleX
            val bottom = (det.y + det.height / 2) * scaleY
            
            canvas.drawRect(left, top, right, bottom, roiPaint)
            canvas.drawText(det.label, left, top - 10f, debugPaint)
        }
    }

    private val ocrTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val ocrBoxPaint = Paint().apply {
        color = Color.parseColor("#4000FFFF") // Semi-transparent cyan
        style = Paint.Style.FILL
    }
    private val ocrBorderPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private fun drawOcr(canvas: Canvas) {
        val scaleX = width.toFloat() / imageWidth
        val scaleY = height.toFloat() / imageHeight

        for (res in ocrResults) {
            if (!res.isValid) continue
            val b = res.box
            val left = b.left * scaleX
            val top = b.top * scaleY
            val right = b.right * scaleX
            val bottom = b.bottom * scaleY

            canvas.drawRect(left, top, right, bottom, ocrBoxPaint)
            canvas.drawRect(left, top, right, bottom, ocrBorderPaint)
            canvas.drawText(res.text, left + 4f, top - 6f, ocrTextPaint)
        }
    }

    private fun drawPose(canvas: Canvas) {
        if (poseResults.isEmpty()) return
        
        val scaleX = width.toFloat() / imageWidth
        val scaleY = height.toFloat() / imageHeight
        val threshold = 0.3f
        
        // Keypoints to draw circles
        for (kp in poseResults) {
            if (kp.score > threshold) {
                canvas.drawCircle(kp.x * scaleX, kp.y * scaleY, 8f, jointPaint)
            }
        }
        
        // Helper to draw a bone between two keypoints
        fun drawBone(id1: Int, id2: Int) {
            val p1 = poseResults.find { it.id == id1 }
            val p2 = poseResults.find { it.id == id2 }
            if (p1 != null && p2 != null && p1.score > threshold && p2.score > threshold) {
                canvas.drawLine(p1.x * scaleX, p1.y * scaleY, p2.x * scaleX, p2.y * scaleY, bonePaint)
            }
        }

        // Draw Skeleton Arms/Torso
        drawBone(5, 6)   // Shoulders
        drawBone(5, 7);  drawBone(7, 9)   // Left Arm
        drawBone(6, 8);  drawBone(8, 10)  // Right Arm
        drawBone(11, 12) // Hips
        drawBone(5, 11); drawBone(6, 12)  // Torso
        drawBone(11, 13); drawBone(13, 15) // Left Leg
        drawBone(12, 14); drawBone(14, 16) // Right Leg
    }

    /**
     * 공간 앵커 라벨 렌더링.
     *
     * 각 앵커에 대해:
     * - 거리 기반 크기 조절 (가까울수록 크게)
     * - 신뢰도 기반 투명도
     * - 유형별 색상 (OBJECT=초록, OCR=청록)
     * - 필 배경 + 라벨 텍스트 + 거리 표시 + 위치 도트
     */
    private fun drawAnchorLabels(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // SpatialUIManager가 있으면 Enhanced 라벨 사용 (스무딩 + 포커스 + 깊이 정렬)
        val uiManager = spatialUIManager
        if (uiManager != null) {
            drawEnhancedAnchorLabels(canvas, uiManager.processedLabels, w, h)
            return
        }

        // 폴백: 기존 기본 렌더링
        if (anchorLabels.isEmpty()) return
        for (label in anchorLabels) {
            drawBasicAnchorLabel(canvas, label, w, h)
        }
    }

    /**
     * Enhanced 앵커 라벨 렌더링 — 깊이 정렬 + 스무딩 + 포커스 + 콘텐츠 패널.
     * SpatialUIManager가 전처리한 EnhancedAnchorLabel 사용.
     */
    private fun drawEnhancedAnchorLabels(
        canvas: Canvas,
        enhancedLabels: List<SpatialUIManager.EnhancedAnchorLabel>,
        w: Float, h: Float
    ) {
        if (enhancedLabels.isEmpty()) return

        // 깊이 정렬된 순서대로 렌더링 (먼 것 먼저 = painter's algorithm)
        for (enhanced in enhancedLabels) {
            val label = enhanced.original
            val px = enhanced.smoothedX / 100f * w
            val py = enhanced.smoothedY / 100f * h
            val dist = String.format("%.1fm", label.distanceMeters)
            val displayText = "${label.label} $dist"

            // 거리 기반 텍스트 크기 × 포커스 배율
            val baseTextSize = (40f * (3f / label.distanceMeters.coerceIn(1f, 10f))) * (w / 1000f)
            val textSize = (baseTextSize * enhanced.renderScale).coerceIn(14f, 72f)
            anchorTextPaint.textSize = textSize

            // 최종 투명도 (SpatialUIManager가 깊이+신뢰도+오클루전 종합 계산)
            val alpha = (enhanced.renderAlpha * 255).toInt().coerceIn(30, 255)

            // 유형별 색상
            val color = when (label.type) {
                AnchorType.OBJECT -> Color.parseColor("#00FF88")
                AnchorType.OCR_TEXT -> Color.parseColor("#00CCFF")
                AnchorType.PROGRAMMATIC -> Color.parseColor("#FFD700")  // 골드: 프로그래밍 앵커 (랩 마커 등)
            }

            val textWidth = anchorTextPaint.measureText(displayText)
            val textHeight = anchorTextPaint.textSize
            val padding = 8f * (w / 1000f)
            val bgLeft = px - padding
            val bgTop = py - textHeight - padding
            val bgRight = px + textWidth + padding
            val bgBottom = py + padding
            val cornerRadius = 6f * (w / 1000f)

            // 배경 사각형
            anchorBgPaint.color = Color.argb((alpha * 0.5f).toInt(), 0, 0, 0)
            val bgRect = RectF(bgLeft, bgTop, bgRight, bgBottom)
            canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, anchorBgPaint)

            // 포커스 하이라이트 테두리
            if (enhanced.isFocused) {
                val focusAlpha = if (enhanced.isDeepFocused) 255 else 180
                anchorFocusPaint.color = Color.argb(focusAlpha, 255, 255, 255)
                anchorFocusPaint.strokeWidth = if (enhanced.isDeepFocused) 4f else 2f
                val focusRect = RectF(bgLeft - 2, bgTop - 2, bgRight + 2, bgBottom + 2)
                canvas.drawRoundRect(focusRect, cornerRadius + 2, cornerRadius + 2, anchorFocusPaint)
            }

            // 오클루전 시 점선 표시
            if (enhanced.isOccluded) {
                anchorTextPaint.alpha = alpha / 2
            }

            // 라벨 텍스트
            anchorTextPaint.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawText(displayText, px, py, anchorTextPaint)

            // 위치 도트
            val dotRadius = (if (enhanced.isFocused) 6f else 4f) * (w / 1000f)
            anchorDotPaint.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawCircle(px, py + padding + dotRadius, dotRadius, anchorDotPaint)

            // 콘텐츠 패널 렌더링 (앵커 아래에 부착)
            val panel = enhanced.contentPanel
            if (panel != null && panel.alpha > 0.01f) {
                drawContentPanel(canvas, panel, px, bgBottom + 4f * (w / 1000f), w, h)
            }
        }
    }

    /**
     * 월드 앵커 콘텐츠 패널 렌더링.
     * AI가 제공한 정보를 앵커 아래에 표시.
     */
    private fun drawContentPanel(
        canvas: Canvas,
        panel: SpatialUIManager.AnchorContentPanel,
        x: Float, y: Float,
        canvasW: Float, canvasH: Float
    ) {
        val scale = canvasW / 1000f
        val panelPadding = 10f * scale
        val maxWidth = 250f * scale

        // 패널 텍스트
        panelTitlePaint.textSize = 28f * scale
        panelTextPaint.textSize = 22f * scale

        val titleWidth = panelTitlePaint.measureText(panel.title)
        val contentWidth = panelTextPaint.measureText(panel.content).coerceAtMost(maxWidth)
        val panelWidth = maxOf(titleWidth, contentWidth) + panelPadding * 2
        val titleHeight = panelTitlePaint.textSize
        val contentHeight = panelTextPaint.textSize
        val panelHeight = titleHeight + contentHeight + panelPadding * 3

        val alpha = (panel.alpha * 220).toInt().coerceIn(0, 220)

        // 패널 배경
        panelBgPaint.color = Color.argb(alpha, 20, 30, 50)
        val panelRect = RectF(x, y, (x + panelWidth).coerceAtMost(canvasW - 10),
            (y + panelHeight).coerceAtMost(canvasH - 10))
        canvas.drawRoundRect(panelRect, 8f * scale, 8f * scale, panelBgPaint)

        // 좌측 컬러 바
        val barColor = panel.color
        panelBgPaint.color = Color.argb(alpha, Color.red(barColor), Color.green(barColor), Color.blue(barColor))
        canvas.drawRoundRect(RectF(x, y, x + 4f * scale, y + panelHeight), 2f, 2f, panelBgPaint)

        // 제목
        val textAlpha = (panel.alpha * 255).toInt().coerceIn(0, 255)
        panelTitlePaint.color = Color.argb(textAlpha, 255, 255, 255)
        canvas.drawText(panel.title, x + panelPadding + 4f * scale, y + panelPadding + titleHeight, panelTitlePaint)

        // 내용 (한 줄 — 필요 시 잘림)
        panelTextPaint.color = Color.argb((textAlpha * 0.8f).toInt(), 200, 200, 200)
        val truncatedContent = if (panelTextPaint.measureText(panel.content) > maxWidth) {
            var end = panel.content.length
            while (end > 0 && panelTextPaint.measureText(panel.content.substring(0, end) + "…") > maxWidth) end--
            panel.content.substring(0, end) + "…"
        } else panel.content
        canvas.drawText(truncatedContent, x + panelPadding + 4f * scale,
            y + panelPadding * 2 + titleHeight + contentHeight, panelTextPaint)
    }

    /** 기존 기본 앵커 라벨 렌더링 (폴백) */
    private fun drawBasicAnchorLabel(canvas: Canvas, label: AnchorLabel2D, w: Float, h: Float) {
        val px = label.screenXPercent / 100f * w
        val py = label.screenYPercent / 100f * h
        val dist = String.format("%.1fm", label.distanceMeters)
        val displayText = "${label.label} $dist"

        val textSize = (40f * (3f / label.distanceMeters.coerceIn(1f, 10f))) * (w / 1000f)
        anchorTextPaint.textSize = textSize.coerceIn(16f, 60f)

        val baseAlpha = if (label.isGhost) 0.4f else label.confidence
        val alpha = (baseAlpha * 255).toInt().coerceIn(60, 255)

        val color = when (label.type) {
            AnchorType.OBJECT -> Color.parseColor("#00FF88")
            AnchorType.OCR_TEXT -> Color.parseColor("#00CCFF")
            AnchorType.PROGRAMMATIC -> Color.parseColor("#FFD700")  // 골드: 프로그래밍 앵커
        }

        val textWidth = anchorTextPaint.measureText(displayText)
        val textHeight = anchorTextPaint.textSize
        val padding = 8f * (w / 1000f)
        val bgLeft = px - padding
        val bgTop = py - textHeight - padding
        val bgRight = px + textWidth + padding
        val bgBottom = py + padding

        anchorBgPaint.color = Color.argb((alpha * 0.5f).toInt(), 0, 0, 0)
        val bgRect = RectF(bgLeft, bgTop, bgRight, bgBottom)
        val cornerRadius = 6f * (w / 1000f)
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, anchorBgPaint)

        anchorTextPaint.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawText(displayText, px, py, anchorTextPaint)

        val dotRadius = 4f * (w / 1000f)
        anchorDotPaint.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawCircle(px, py + padding + dotRadius, dotRadius, anchorDotPaint)
    }

    private var centralMessage: String = ""
    private val logMessages = mutableListOf<String>()
    private var audioLevel: Float = 0f

    fun setCentralMessage(msg: String) {
        centralMessage = msg
        postInvalidate()
        
        // Auto-clear after 5 seconds if it's just "Listening..."? No, user wants to see it.
    }

    fun addLog(message: String) {
        logMessages.add(message)
        if (logMessages.size > 10) {
            logMessages.removeAt(0)
        }
        postInvalidate()
    }

    fun setAudioLevel(level: Float) {
        audioLevel = level.coerceIn(0f, 1f)
        postInvalidate()
    }

    // --- AI Drawing Layer ---

    fun applyDrawCommand(command: DrawCommand) {
        when (command) {
            is DrawCommand.Add -> drawingElements.add(command.element)
            is DrawCommand.Remove -> drawingElements.removeAll { it.id == command.id }
            is DrawCommand.Modify -> modifyElement(command.id, command.updates)
            is DrawCommand.ClearAll -> drawingElements.clear()
        }
        postInvalidate()
    }

    private fun modifyElement(id: String, updates: Map<String, Any>) {
        val idx = drawingElements.indexOfFirst { it.id == id }
        if (idx < 0) return
        val old = drawingElements[idx]
        val newColor = updates["color"] as? String ?: old.color
        val newOpacity = (updates["opacity"] as? Number)?.toFloat() ?: old.opacity
        drawingElements[idx] = when (old) {
            is DrawElement.Text -> old.copy(
                color = newColor, opacity = newOpacity,
                x = (updates["x"] as? Number)?.toFloat() ?: old.x,
                y = (updates["y"] as? Number)?.toFloat() ?: old.y,
                text = updates["text"] as? String ?: old.text,
                size = (updates["size"] as? Number)?.toFloat() ?: old.size
            )
            is DrawElement.Rect -> old.copy(
                color = newColor, opacity = newOpacity,
                x = (updates["x"] as? Number)?.toFloat() ?: old.x,
                y = (updates["y"] as? Number)?.toFloat() ?: old.y,
                width = (updates["width"] as? Number)?.toFloat() ?: old.width,
                height = (updates["height"] as? Number)?.toFloat() ?: old.height
            )
            is DrawElement.Circle -> old.copy(
                color = newColor, opacity = newOpacity,
                cx = (updates["cx"] as? Number)?.toFloat() ?: old.cx,
                cy = (updates["cy"] as? Number)?.toFloat() ?: old.cy,
                radius = (updates["radius"] as? Number)?.toFloat() ?: old.radius
            )
            is DrawElement.Line -> old.copy(
                color = newColor, opacity = newOpacity,
                x1 = (updates["x1"] as? Number)?.toFloat() ?: old.x1,
                y1 = (updates["y1"] as? Number)?.toFloat() ?: old.y1,
                x2 = (updates["x2"] as? Number)?.toFloat() ?: old.x2,
                y2 = (updates["y2"] as? Number)?.toFloat() ?: old.y2
            )
            is DrawElement.Arrow -> old.copy(
                color = newColor, opacity = newOpacity,
                x1 = (updates["x1"] as? Number)?.toFloat() ?: old.x1,
                y1 = (updates["y1"] as? Number)?.toFloat() ?: old.y1,
                x2 = (updates["x2"] as? Number)?.toFloat() ?: old.x2,
                y2 = (updates["y2"] as? Number)?.toFloat() ?: old.y2
            )
            is DrawElement.Highlight -> old.copy(
                color = newColor, opacity = newOpacity,
                x = (updates["x"] as? Number)?.toFloat() ?: old.x,
                y = (updates["y"] as? Number)?.toFloat() ?: old.y,
                width = (updates["width"] as? Number)?.toFloat() ?: old.width,
                height = (updates["height"] as? Number)?.toFloat() ?: old.height
            )
            is DrawElement.Polyline -> {
                @Suppress("UNCHECKED_CAST")
                val newPoints = (updates["points"] as? List<Pair<Float, Float>>) ?: old.points
                old.copy(
                    color = newColor, opacity = newOpacity,
                    points = newPoints,
                    strokeWidth = (updates["strokeWidth"] as? Number)?.toFloat() ?: old.strokeWidth
                )
            }
        }
    }

    fun generateElementId(): String = "draw_${nextAutoId++}"

    fun getScreenObjects(): String {
        val array = JSONArray()

        // Include current detections
        for (det in detections) {
            val obj = JSONObject()
            obj.put("type", "detection")
            obj.put("label", det.label)
            obj.put("confidence", det.confidence)
            obj.put("x", det.x / imageWidth * 100f)
            obj.put("y", det.y / imageHeight * 100f)
            obj.put("width", det.width / imageWidth * 100f)
            obj.put("height", det.height / imageHeight * 100f)
            array.put(obj)
        }

        // Include current OCR results
        for (res in ocrResults) {
            if (!res.isValid) continue
            val obj = JSONObject()
            obj.put("type", "ocr")
            obj.put("text", res.text)
            obj.put("x", res.box.left.toFloat() / imageWidth * 100f)
            obj.put("y", res.box.top.toFloat() / imageHeight * 100f)
            obj.put("width", (res.box.right - res.box.left).toFloat() / imageWidth * 100f)
            obj.put("height", (res.box.bottom - res.box.top).toFloat() / imageHeight * 100f)
            array.put(obj)
        }

        // Include current custom drawings
        for (elem in drawingElements) {
            val obj = JSONObject()
            obj.put("type", "drawing")
            obj.put("id", elem.id)
            when (elem) {
                is DrawElement.Text -> { obj.put("shape", "text"); obj.put("text", elem.text); obj.put("x", elem.x); obj.put("y", elem.y) }
                is DrawElement.Rect -> { obj.put("shape", "rect"); obj.put("x", elem.x); obj.put("y", elem.y); obj.put("width", elem.width); obj.put("height", elem.height) }
                is DrawElement.Circle -> { obj.put("shape", "circle"); obj.put("cx", elem.cx); obj.put("cy", elem.cy); obj.put("radius", elem.radius) }
                is DrawElement.Line -> { obj.put("shape", "line"); obj.put("x1", elem.x1); obj.put("y1", elem.y1); obj.put("x2", elem.x2); obj.put("y2", elem.y2) }
                is DrawElement.Arrow -> { obj.put("shape", "arrow"); obj.put("x1", elem.x1); obj.put("y1", elem.y1); obj.put("x2", elem.x2); obj.put("y2", elem.y2) }
                is DrawElement.Highlight -> { obj.put("shape", "highlight"); obj.put("x", elem.x); obj.put("y", elem.y); obj.put("width", elem.width); obj.put("height", elem.height) }
                is DrawElement.Polyline -> { obj.put("shape", "polyline"); obj.put("points", elem.points.size) }
            }
            array.put(obj)
        }

        return array.toString()
    }

    private fun parseColor(colorStr: String, opacity: Float): Int {
        val baseColor = try {
            when (colorStr.lowercase()) {
                "red" -> Color.RED
                "green" -> Color.GREEN
                "blue" -> Color.BLUE
                "yellow" -> Color.YELLOW
                "cyan" -> Color.CYAN
                "white" -> Color.WHITE
                "orange" -> Color.parseColor("#FF6600")
                "magenta" -> Color.MAGENTA
                else -> Color.parseColor(colorStr)
            }
        } catch (e: Exception) {
            Color.CYAN
        }
        val alpha = (opacity * 255).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
    }

    private fun drawCustomElements(canvas: Canvas) {
        if (drawingElements.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val engine = physicsEngine  // 로컬 참조

        for (elem in drawingElements) {
            // 물리 엔진 오프셋 적용
            val physBody = engine?.getBody(elem.id)
            val (offsetX, offsetY) = engine?.getAnimationOffset(elem.id) ?: (0f to 0f)
            val animScale = engine?.getAnimationScale(elem.id) ?: 1f
            val animAlpha = engine?.getAnimationAlpha(elem.id) ?: 1f

            when (elem) {
                is DrawElement.Text -> {
                    drawElementPaint.reset()
                    drawElementPaint.isAntiAlias = true
                    drawElementPaint.color = parseColor(elem.color, elem.opacity * animAlpha)
                    drawElementPaint.textSize = elem.size * (w / 400f) * animScale
                    drawElementPaint.isFakeBoldText = elem.bold
                    drawElementPaint.setShadowLayer(4f, 0f, 0f, Color.BLACK)
                    val baseX = physBody?.x ?: elem.x
                    val baseY = physBody?.y ?: elem.y
                    val px = (baseX / 100f * w) + offsetX * (w / 100f)
                    val py = (baseY / 100f * h) + offsetY * (h / 100f)
                    canvas.drawText(elem.text, px, py, drawElementPaint)
                }
                is DrawElement.Rect -> {
                    drawElementPaint.reset()
                    drawElementPaint.isAntiAlias = true
                    drawElementPaint.color = parseColor(elem.color, elem.opacity)
                    drawElementPaint.style = if (elem.filled) Paint.Style.FILL else Paint.Style.STROKE
                    drawElementPaint.strokeWidth = elem.strokeWidth * (w / 1000f)
                    val left = elem.x / 100f * w
                    val top = elem.y / 100f * h
                    val right = left + elem.width / 100f * w
                    val bottom = top + elem.height / 100f * h
                    canvas.drawRect(left, top, right, bottom, drawElementPaint)
                }
                is DrawElement.Circle -> {
                    drawElementPaint.reset()
                    drawElementPaint.isAntiAlias = true
                    drawElementPaint.color = parseColor(elem.color, elem.opacity)
                    drawElementPaint.style = if (elem.filled) Paint.Style.FILL else Paint.Style.STROKE
                    drawElementPaint.strokeWidth = 3f * (w / 1000f)
                    val cx = elem.cx / 100f * w
                    val cy = elem.cy / 100f * h
                    val r = elem.radius / 100f * w
                    canvas.drawCircle(cx, cy, r, drawElementPaint)
                }
                is DrawElement.Line -> {
                    drawElementPaint.reset()
                    drawElementPaint.isAntiAlias = true
                    drawElementPaint.color = parseColor(elem.color, elem.opacity)
                    drawElementPaint.style = Paint.Style.STROKE
                    drawElementPaint.strokeWidth = elem.strokeWidth * (w / 1000f)
                    drawElementPaint.strokeCap = Paint.Cap.ROUND
                    canvas.drawLine(
                        elem.x1 / 100f * w, elem.y1 / 100f * h,
                        elem.x2 / 100f * w, elem.y2 / 100f * h,
                        drawElementPaint
                    )
                }
                is DrawElement.Arrow -> {
                    drawElementPaint.reset()
                    drawElementPaint.isAntiAlias = true
                    drawElementPaint.color = parseColor(elem.color, elem.opacity)
                    drawElementPaint.style = Paint.Style.STROKE
                    drawElementPaint.strokeWidth = elem.strokeWidth * (w / 1000f)
                    drawElementPaint.strokeCap = Paint.Cap.ROUND
                    val x1 = elem.x1 / 100f * w
                    val y1 = elem.y1 / 100f * h
                    val x2 = elem.x2 / 100f * w
                    val y2 = elem.y2 / 100f * h
                    canvas.drawLine(x1, y1, x2, y2, drawElementPaint)
                    // Arrowhead
                    val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
                    val headLen = 20f * (w / 1000f)
                    drawElementPaint.style = Paint.Style.FILL_AND_STROKE
                    arrowPath.reset()
                    arrowPath.moveTo(x2, y2)
                    arrowPath.lineTo(
                        (x2 - headLen * cos(angle - Math.PI / 6)).toFloat(),
                        (y2 - headLen * sin(angle - Math.PI / 6)).toFloat()
                    )
                    arrowPath.lineTo(
                        (x2 - headLen * cos(angle + Math.PI / 6)).toFloat(),
                        (y2 - headLen * sin(angle + Math.PI / 6)).toFloat()
                    )
                    arrowPath.close()
                    canvas.drawPath(arrowPath, drawElementPaint)
                }
                is DrawElement.Highlight -> {
                    drawElementPaint.reset()
                    drawElementPaint.isAntiAlias = true
                    drawElementPaint.color = parseColor(elem.color, elem.opacity)
                    drawElementPaint.style = Paint.Style.FILL
                    val left = elem.x / 100f * w
                    val top = elem.y / 100f * h
                    val right = left + elem.width / 100f * w
                    val bottom = top + elem.height / 100f * h
                    canvas.drawRect(left, top, right, bottom, drawElementPaint)
                }
                is DrawElement.Polyline -> {
                    if (elem.points.size < 2) continue
                    drawElementPaint.reset()
                    drawElementPaint.isAntiAlias = true
                    drawElementPaint.color = parseColor(elem.color, elem.opacity)
                    drawElementPaint.style = Paint.Style.STROKE
                    drawElementPaint.strokeWidth = elem.strokeWidth * (w / 1000f)
                    drawElementPaint.strokeCap = Paint.Cap.ROUND
                    drawElementPaint.strokeJoin = Paint.Join.ROUND

                    arrowPath.reset()
                    val first = elem.points[0]
                    arrowPath.moveTo(first.first / 100f * w, first.second / 100f * h)
                    for (i in 1 until elem.points.size) {
                        val pt = elem.points[i]
                        arrowPath.lineTo(pt.first / 100f * w, pt.second / 100f * h)
                    }
                    if (elem.closed && elem.points.size > 2) {
                        arrowPath.close()
                    }
                    canvas.drawPath(arrowPath, drawElementPaint)
                }
            }
        }
    }
}
