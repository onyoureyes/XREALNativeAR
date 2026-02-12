package com.xreal.nativear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LiteRTWrapper(private val context: Context) {

    data class Detection(val box: RectF, val classId: Int, val confidence: Float)

    private var interpreter: Interpreter? = null
    private var nnApiDelegate: NnApiDelegate? = null
    private var gpuDelegate: GpuDelegate? = null
    
    // Model Input Specs (YOLOv8n)
    private var inputImageWidth: Int = 0
    private var inputImageHeight: Int = 0
    private var inputDataType: DataType = DataType.INT8
    
    // Model Output Specs
    private var outputScale: Float = 1.0f
    private var outputZeroPoint: Int = 0
    private var outputShape: IntArray = intArrayOf(1, 84, 8400)

    // Pre-allocated Buffers
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null
    private var outputData: ByteArray? = null
    private var pixelArray: IntArray? = null

    companion object {
        private const val TAG = "LiteRTWrapper"
        private const val MODEL_NAME = "yolov8n_full_integer_quant.tflite"
        
        val LABELS = listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
            "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
            "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
        )
    }

    private var scaledBitmap: Bitmap? = null
    private var canvas: android.graphics.Canvas? = null
    private val matrix = android.graphics.Matrix()

    fun initialize(): Boolean {
        try {
            val options = Interpreter.Options()
            options.setNumThreads(Runtime.getRuntime().availableProcessors())
            options.setUseXNNPACK(true) 

            // 1. Try Android NNAPI Delegate First (Samsung Standard)
            try {
                val nnOptions = NnApiDelegate.Options()
                nnOptions.executionPreference = 1 // EXECUTION_PREFERENCE_LOW_LATENCY
                nnOptions.setAllowFp16(true)
                nnOptions.setUseNnapiCpu(false) // Strict NPU/DSP preference
                
                nnApiDelegate = NnApiDelegate(nnOptions)
                options.addDelegate(nnApiDelegate)
                Log.i(TAG, "✅ NNAPI Delegate added (Samsung NPU Target)")
            } catch (e: Exception) {
                Log.e(TAG, "NNAPI Delegate failed, trying GPU: ${e.message}")
                
                // 2. Try GPU Delegate (Fallback)
                try {
                    val gpuOptions = GpuDelegate.Options()
                    gpuOptions.setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
                    gpuDelegate = GpuDelegate(gpuOptions)
                    options.addDelegate(gpuDelegate)
                    Log.i(TAG, "GPU Delegate added")
                } catch (e2: Exception) {
                    Log.e(TAG, "GPU Delegate failed, using CPU: ${e2.message}")
                }
            }

            val modelBuffer = FileUtil.loadMappedFile(context, MODEL_NAME)
            interpreter = Interpreter(modelBuffer, options)
            
            // Get Tensor Info
            val inputTensor = interpreter!!.getInputTensor(0)
            inputImageHeight = inputTensor.shape()[1]
            inputImageWidth = inputTensor.shape()[2]
            inputDataType = inputTensor.dataType()
            
            val outputTensor = interpreter!!.getOutputTensor(0)
            outputShape = outputTensor.shape()
            outputScale = outputTensor.quantizationParams().scale
            outputZeroPoint = outputTensor.quantizationParams().zeroPoint

            // Buffer Allocation
            inputBuffer = ByteBuffer.allocateDirect(inputTensor.numBytes())
            inputBuffer?.order(ByteOrder.nativeOrder())
            
            outputBuffer = ByteBuffer.allocateDirect(outputTensor.numBytes())
            outputBuffer?.order(ByteOrder.nativeOrder())
            outputData = ByteArray(outputTensor.numBytes())
            pixelArray = IntArray(inputImageWidth * inputImageHeight)

            // Scaling Helper
            scaledBitmap = Bitmap.createBitmap(inputImageWidth, inputImageHeight, Bitmap.Config.ARGB_8888)
            canvas = android.graphics.Canvas(scaledBitmap!!)

            Log.i(TAG, "Initialized: $MODEL_NAME ($inputImageWidth x $inputImageHeight $inputDataType)")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "LiteRT Initialization Failed: ${e.message}")
            return false
        }
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        if (interpreter == null || inputBuffer == null || pixelArray == null || scaledBitmap == null) return emptyList()
        val totalStart = SystemClock.elapsedRealtime()
        try {
            // 1. Preprocessing
            val preStart = SystemClock.elapsedRealtime()
            
            matrix.reset()
            matrix.postScale(inputImageWidth.toFloat() / bitmap.width, inputImageHeight.toFloat() / bitmap.height)
            canvas?.drawBitmap(bitmap, matrix, null)
            
            scaledBitmap!!.getPixels(pixelArray!!, 0, inputImageWidth, 0, 0, inputImageWidth, inputImageHeight)
            inputBuffer!!.rewind()

            // Optimized Loop for Quantization
            if (inputDataType == DataType.INT8) {
                // ... (Assuming standard YUV/RGB logic, simplified here for ARGB input)
                for (pixel in pixelArray!!) {
                    // Extract RGB, ignore Alpha. Subtract 128 for INT8 detection.
                    inputBuffer!!.put(((pixel shr 16 and 0xFF) - 128).toByte())
                    inputBuffer!!.put(((pixel shr 8 and 0xFF) - 128).toByte())
                    inputBuffer!!.put(((pixel and 0xFF) - 128).toByte())
                }
            } else {
                for (pixel in pixelArray!!) {
                    inputBuffer!!.put((pixel shr 16 and 0xFF).toByte())
                    inputBuffer!!.put((pixel shr 8 and 0xFF).toByte())
                    inputBuffer!!.put((pixel and 0xFF).toByte())
                }
            }
            val preTime = SystemClock.elapsedRealtime() - preStart

            // 2. Inference
            val inferStart = SystemClock.elapsedRealtime()
            interpreter!!.run(inputBuffer!!.rewind(), outputBuffer!!.rewind())
            val inferTime = SystemClock.elapsedRealtime() - inferStart
            
            // 3. Post-processing
            val postStart = SystemClock.elapsedRealtime()
            val candidates = mutableListOf<Detection>()
            val rows = outputShape[1]
            val cols = outputShape[2]
            
            outputBuffer!!.rewind()
            outputBuffer!!.get(outputData!!)
            
            // Vectorized-like parsing (Manual unrolling for performance)
            for (c in 0 until cols) {
                var maxConfRaw = -128
                var maxConfPos = -1
                
                // Find class with max confidence
                for (r in 4 until rows) {
                    val raw = outputData!![r * cols + c].toInt()
                    if (raw > maxConfRaw) {
                        maxConfRaw = raw
                        maxConfPos = r
                    }
                }
                
                val confidence = (maxConfRaw - outputZeroPoint) * outputScale
                if (confidence > 0.45f) { 
                    val cx = (outputData!![0 * cols + c].toInt() - outputZeroPoint) * outputScale
                    val cy = (outputData!![1 * cols + c].toInt() - outputZeroPoint) * outputScale
                    val w = (outputData!![2 * cols + c].toInt() - outputZeroPoint) * outputScale
                    val h = (outputData!![3 * cols + c].toInt() - outputZeroPoint) * outputScale
                    
                    val rect = RectF(cx - w/2f, cy - h/2f, cx + w/2f, cy + h/2f)
                    candidates.add(Detection(rect, maxConfPos - 4, confidence))
                }
            }
            val result = nms(candidates)
            val postTime = SystemClock.elapsedRealtime() - postStart
            
            Log.i(TAG, "Perf: Pre ${preTime}ms | Inf ${inferTime}ms | Post ${postTime}ms | Total ${SystemClock.elapsedRealtime() - totalStart}ms")
            
            return result

        } catch (e: Exception) {
            Log.e(TAG, "Detection Failed: ${e.message}")
            return emptyList()
        }
    }

    private fun nms(detections: MutableList<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()
        detections.sortByDescending { it.confidence }
        val result = mutableListOf<Detection>()
        while (detections.isNotEmpty()) {
            val first = detections.removeAt(0)
            result.add(first)
            val iterator = detections.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (boxIoU(first.box, next.box) > 0.45f) iterator.remove()
            }
        }
        return result
    }

    private fun boxIoU(a: RectF, b: RectF): Float {
        val intersection = RectF()
        if (!intersection.setIntersect(a, b)) return 0f
        val interArea = intersection.width() * intersection.height()
        val unionArea = (a.width() * a.height()) + (b.width() * b.height()) - interArea
        return interArea / unionArea
    }

    fun close() {
        interpreter?.close()
        nnApiDelegate?.close()
        gpuDelegate?.close()
    }
}
