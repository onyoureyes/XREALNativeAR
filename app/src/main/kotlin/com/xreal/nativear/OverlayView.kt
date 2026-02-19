package com.xreal.nativear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class OcrResult(val text: String, val box: Rect, val isValid: Boolean)

    var ocrResults: List<OcrResult> = emptyList()
    var detections: List<Detection> = emptyList()
    var poseResults: List<PoseEstimationModel.Keypoint> = emptyList()
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

    fun setOcrResults(results: List<OcrResult>, width: Int, height: Int) {
        ocrResults = results
        imageWidth = width
        imageHeight = height
        postInvalidate()
    }

    fun setDetections(results: List<Detection>) {
        detections = results
        postInvalidate()
    }

    fun setPoseResults(results: List<PoseEstimationModel.Keypoint>) {
        poseResults = results
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
            
            canvas.drawRect(left, top, right, bottom, boxPaint)
            // OCR text is usually drawn smaller or in a specific place, but here we just show the box 
            // to keep it clean. Labels are primarily for object detection.
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
}
