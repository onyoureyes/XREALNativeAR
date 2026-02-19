package com.xreal.nativear

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.xreal.ai.IAIModel
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * PoseEstimationModel: Handles human body pose detection using TFLite.
 */
class PoseEstimationModel(private val context: Context) : IAIModel {
    private val TAG = "PoseEstimationModel"
    private var interpreter: Interpreter? = null
    
    override var isReady: Boolean = false
        private set
    override var isLoaded: Boolean = false
        private set

    override suspend fun prepare(options: Interpreter.Options): Boolean {
        if (isLoaded) return true
        Log.i(TAG, "Preparing Pose estimation model...")
        return try {
            val modelBuffer = loadModelFile("pose_detector.tflite")
            interpreter = Interpreter(modelBuffer, options)
            isLoaded = true
            isReady = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pose model: ${e.message}")
            false
        }
    }
    
    private fun loadModelFile(modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }
    
    /**
     * Data class represent a keypoint with its coordinates and confidence score.
     */
    data class Keypoint(val id: Int, val x: Float, val y: Float, val score: Float)

    /**
     * Processes the bitmap to detect human pose.
     * @param bitmap: Source bitmap from camera.
     * @param callback: Returns a list of Keypoints.
     */
    fun process(bitmap: Bitmap, callback: (List<Keypoint>) -> Unit) {
        if (!isReady || interpreter == null) {
            callback(emptyList())
            return
        }

        try {
            // 1. Preprocessing: Resize to 192x192 (Default for MoveNet Lightning)
            // Note: In a production app, we'd use a dedicated ImageProcessor.
            val inputSize = 192
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            
            // 2. Prepare Input Tensor [1, 192, 192, 3] float32
            val byteBuffer = java.nio.ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
            byteBuffer.order(java.nio.ByteOrder.nativeOrder())
            
            val pixels = IntArray(inputSize * inputSize)
            resizedBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
            
            for (pixel in pixels) {
                byteBuffer.putFloat(((pixel shr 16) and 0xFF) / 255f) // R
                byteBuffer.putFloat(((pixel shr 8) and 0xFF) / 255f)  // G
                byteBuffer.putFloat((pixel and 0xFF) / 255f)         // B
            }
            
            // 3. Inference
            // MoveNet Output Shape: [1, 1, 17, 3]
            val outputBuffer = Array(1) { Array(1) { Array(17) { FloatArray(3) } } }
            interpreter?.run(byteBuffer, outputBuffer)
            
            // 4. Post-processing: Parse 17 keypoints
            val keypoints = mutableListOf<Keypoint>()
            val detectedKeypoints = outputBuffer[0][0]
            
            for (i in 0 until 17) {
                val y = detectedKeypoints[i][0] // Normalized y [0, 1]
                val x = detectedKeypoints[i][1] // Normalized x [0, 1]
                val score = detectedKeypoints[i][2] // Confidence score
                
                // Scale back to original bitmap dimensions for drawing
                keypoints.add(Keypoint(i, x * bitmap.width, y * bitmap.height, score))
            }
            
            callback(keypoints)
            resizedBitmap.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Pose Inference Error: ${e.message}")
            callback(emptyList())
        }
    }
    
    override fun release() {
        Log.i(TAG, "Releasing Pose Interpreter")
        interpreter?.close()
        interpreter = null
    }
}
