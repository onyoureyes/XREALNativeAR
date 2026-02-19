package com.xreal.whisper

import android.content.Context
import android.util.Log
import com.google.android.gms.tflite.gpu.GpuDelegate
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

enum class ModelType {
    TINY, BASE, SMALL
}

interface WhisperInference {
    fun initialize(options: Interpreter.Options)
    fun transcribe(audioData: ShortArray): IntArray
    fun getEmbedding(audioData: ShortArray): FloatArray?
    fun close()
}

class WhisperSingleInference(
    private val context: Context,
    private val modelPath: String
) : WhisperInference {
    
    private val TAG = "WhisperSingleInference"
    private var interpreter: Interpreter? = null
    // Remove local GpuDelegate, use the one from options if present
    private var inputBuffer: ByteBuffer? = null
    private lateinit var outputBuffer: IntArray
    private val melSpectrogram = MelSpectrogram()

    override fun initialize(options: Interpreter.Options) {
        val assetFileDescriptor = context.assets.openFd(modelPath)
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, assetFileDescriptor.startOffset, assetFileDescriptor.declaredLength)

        // Use injected options directly. 
        // The orchestrator manages the GpuDelegate life-cycle attached to these options.
        Log.i(TAG, "Initializing Single Model with injected options...")

        interpreter = Interpreter(modelBuffer, options)
        inputBuffer = ByteBuffer.allocateDirect(1 * 80 * 3000 * 4).order(ByteOrder.nativeOrder())
        outputBuffer = IntArray(448) // Max tokens for Tiny
        
        // Initialize Preprocessor
        melSpectrogram.loadFiltersFromAssets(context)
    }


    override fun transcribe(audioData: ShortArray): IntArray {
        val currentInterpreter = interpreter ?: return IntArray(0)
        val currentInputBuffer = inputBuffer ?: return IntArray(0)

        // Pre-processing
        val melData = melSpectrogram.process(audioData) 
        
        currentInputBuffer.rewind()
        for (i in 0 until 80) {
            for (j in 0 until 3000) {
                currentInputBuffer.putFloat(melData[i * 3000 + j])
            }
        }
        currentInputBuffer.rewind()
        
        val outputs = mutableMapOf<Int, Any>(0 to outputBuffer)
        currentInterpreter.runForMultipleInputsOutputs(arrayOf(currentInputBuffer), outputs)
        
        return outputBuffer
    }

    override fun getEmbedding(audioData: ShortArray): FloatArray? {
        return try {
            // Return mel spectrogram as embedding (80 * 3000 = 240,000 dim)
            // For more compact representation, could average over time dimension
            val melData = melSpectrogram.process(audioData)
            
            // Average pooling over time to get 80-dim vector
            val embedding = FloatArray(80)
            for (i in 0 until 80) {
                var sum = 0f
                for (j in 0 until 3000) {
                    sum += melData[i * 3000 + j]
                }
                embedding[i] = sum / 3000f
            }
            
            l2Normalize(embedding)
        } catch (e: Exception) {
            Log.e(TAG, "Embedding extraction failed: ${e.message}")
            null
        }
    }
    
    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSquares = 0f
        for (value in vector) {
            sumSquares += value * value
        }
        val magnitude = kotlin.math.sqrt(sumSquares)
        
        if (magnitude == 0f) return vector
        
        for (i in vector.indices) {
            vector[i] /= magnitude
        }
        return vector
    }

    override fun close() {
        interpreter?.close()
        // Do NOT close GpuDelegate here, it's owned by the Orchestrator
        interpreter = null
    }
}

class WhisperSplitInference(
    private val context: Context,
    private val modelType: ModelType
) : WhisperInference {

    private val TAG = "WhisperSplitInference"
    private var encoderInterpreter: Interpreter? = null
    private var decoderInterpreter: Interpreter? = null
    
    // Buffers
    private var inputBuffer: ByteBuffer? = null
    private var crossAttnBuffer: ByteBuffer? = null
    
    private val melSpectrogram = MelSpectrogram()

    override fun initialize(options: Interpreter.Options) {
        Log.i(TAG, "Initializing Split Inference ($modelType) with injected options...")
        
        val suffix = when(modelType) {
            ModelType.TINY -> "tiny"
            ModelType.BASE -> "base"
            ModelType.SMALL -> "small"
        }

        // Search for Qualcomm proxy files first, then fallback to standard naming
        val assetList = context.assets.list("") ?: emptyArray()
        
        val encPath = assetList.find { it.startsWith("whisper_$suffix") && it.contains("encoder") }
            ?: "whisper_encoder_$suffix.tflite"
            
        val decPath = assetList.find { it.startsWith("whisper_$suffix") && it.contains("decoder") }
            ?: "whisper_decoder_$suffix.tflite"

        Log.i(TAG, "Loading models: $encPath, $decPath")

        // Load Encoder
        val assetManager = context.assets
        try {
            val encFd = assetManager.openFd(encPath)
            val encBuffer = FileInputStream(encFd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, encFd.startOffset, encFd.declaredLength)
            encoderInterpreter = Interpreter(encBuffer, options)
            
            val decFd = assetManager.openFd(decPath)
            val decBuffer = FileInputStream(decFd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, decFd.startOffset, decFd.declaredLength)
            decoderInterpreter = Interpreter(decBuffer, options)
        } catch (e: Exception) {
            Log.e(TAG, "Model Load Mismatch/Error: ${e.message}")
            throw e
        }

        // Allocate Buffers
        inputBuffer = ByteBuffer.allocateDirect(1 * 80 * 3000 * 4).order(ByteOrder.nativeOrder())
        
        val dModel = when(modelType) {
            ModelType.TINY -> 384
            ModelType.BASE -> 512
            ModelType.SMALL -> 768
        }
        Log.i(TAG, "Buffer Allocation: d_model=$dModel")
        crossAttnBuffer = ByteBuffer.allocateDirect(1 * 1500 * dModel * 4).order(ByteOrder.nativeOrder())

        // Initialize Preprocessor
        melSpectrogram.loadFiltersFromAssets(context)
    }


    override fun transcribe(audioData: ShortArray): IntArray {
        val encoder = encoderInterpreter ?: return IntArray(0)
        // val decoder = decoderInterpreter ?: return IntArray(0) 
        val mInputBuffer = inputBuffer ?: return IntArray(0)
        val mCrossAttn = crossAttnBuffer ?: return IntArray(0)

        // 1. Run ENCODER
        val melData = melSpectrogram.process(audioData)
        mInputBuffer.rewind()
        for (i in 0 until 80) {
            for (j in 0 until 3000) mInputBuffer.putFloat(melData[i * 3000 + j])
        }
        mInputBuffer.rewind()
        
        mCrossAttn.rewind()
        val encInputs = arrayOf<Any>(mInputBuffer)
        val encOutputs = mutableMapOf<Int, Any>(0 to mCrossAttn)
        encoder.runForMultipleInputsOutputs(encInputs, encOutputs)
        
        // 2. Decoder Loop (Placeholder)
        return intArrayOf(50259, 50359, 220, 50257) 
    }

    override fun getEmbedding(audioData: ShortArray): FloatArray? {
        val encoder = encoderInterpreter ?: return null
        val mInputBuffer = inputBuffer ?: return null
        
        return try {
            // Pre-processing
            val melData = melSpectrogram.process(audioData)
            
            mInputBuffer.rewind()
            for (i in 0 until 80) {
                for (j in 0 until 3000) mInputBuffer.putFloat(melData[i * 3000 + j])
            }
            mInputBuffer.rewind()
            
            // Run encoder to get cross-attention tensor
            val encoderOutputSize = 1 * 1500 * 384
            val encoderOutputBuffer = ByteBuffer.allocateDirect(encoderOutputSize * 4).apply {
                order(ByteOrder.nativeOrder())
            }
            
            encoder.run(mInputBuffer, encoderOutputBuffer)
            
            // Average pooling over time dimension (1500) to get 384-dim vector
            encoderOutputBuffer.rewind()
            val embedding = FloatArray(384)
            val tempBuffer = FloatArray(encoderOutputSize)
            encoderOutputBuffer.asFloatBuffer().get(tempBuffer)
            
            for (i in 0 until 384) {
                var sum = 0f
                for (j in 0 until 1500) {
                    sum += tempBuffer[j * 384 + i]
                }
                embedding[i] = sum / 1500f
            }
            
            l2Normalize(embedding)
        } catch (e: Exception) {
            Log.e(TAG, "Embedding extraction failed: ${e.message}", e)
            null
        }
    }
    
    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSquares = 0f
        for (value in vector) {
            sumSquares += value * value
        }
        val magnitude = kotlin.math.sqrt(sumSquares)
        
        if (magnitude == 0f) return vector
        
        for (i in vector.indices) {
            vector[i] /= magnitude
        }
        return vector
    }

    override fun close() {
        encoderInterpreter?.close()
        decoderInterpreter?.close()
        // Do NOT close GpuDelegate here
        encoderInterpreter = null
        decoderInterpreter = null
    }
}
