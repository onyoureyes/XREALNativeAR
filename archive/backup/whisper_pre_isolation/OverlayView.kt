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

    private var debugLog: String = ""

    fun setOcrResults(results: List<OcrResult>, width: Int, height: Int) {
        ocrResults = results
        imageWidth = width
        imageHeight = height
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
    // Helper to visualize if sensor is working
    val stepCountRaw = (stepProgress / 100.0 * 15).toInt()
    canvas.drawText("Steps: $stepCountRaw / 15", 40f, topMargin + 60f, textPaint)

    // 4. BIG VISUAL DEBUG LABEL for Fold 4 Cover Screen
    // Centered, Red, Large Text
    val centerX = width / 2f
    val centerY = height / 2f
    
    // 4. (REMOVED STATIC LABEL TO PREVENT CLUTTER)
    
    // Draw Transcript Log (Bottom - keep for logcat alternative)
    if (debugLog.isNotEmpty()) {
        val lines = debugLog.split("\n")
        var yLog = height - 100f - (lines.size * 50f)
        for (line in lines) {
            canvas.drawText(line, 50f, yLog, debugPaint)
            yLog += 50f
        }
    }

    // 5. CENTRAL DEBUG MESSAGE (Single Slot for Narrow FOV)
    // Only shows the latest message to avoid overlap
    if (centralMessage.isNotEmpty()) {
        val paint = Paint().apply {
            // Color coding: Results in CYAN, Status in YELLOW
            color = if (centralMessage.startsWith("STT:")) Color.CYAN else Color.YELLOW
            textSize = 100f 
            textAlign = Paint.Align.CENTER
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = 3f
            setShadowLayer(10f, 0f, 0f, Color.BLACK)
        }
        
        val fixedY = 450f // User-confirmed visible FOV
        // Remove "STT:" prefix for cleaner UI if present
        val displayMsg = centralMessage.replace("STT:", "").trim()
        
        canvas.drawText(displayMsg, width / 2f, fixedY, paint)
    }
}
    private var centralMessage: String = ""

    fun setCentralMessage(msg: String) {
        centralMessage = msg
        postInvalidate()
        
        // Auto-clear after 5 seconds if it's just "Listening..."? No, user wants to see it.
    }
}
