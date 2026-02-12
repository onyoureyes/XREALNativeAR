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
    TINY, BASE
}

interface WhisperInference {
    fun initialize(options: Interpreter.Options)
    fun transcribe(audioData: ShortArray): IntArray
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
        
        val suffix = if (modelType == ModelType.BASE) "base" else "tiny"
        val encPath = "whisper_encoder_$suffix.tflite"
        val decPath = "whisper_decoder_$suffix.tflite"

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
        
        val dModel = if (modelType == ModelType.BASE) 512 else 384
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

    override fun close() {
        encoderInterpreter?.close()
        decoderInterpreter?.close()
        // Do NOT close GpuDelegate here
        encoderInterpreter = null
        decoderInterpreter = null
    }
}
