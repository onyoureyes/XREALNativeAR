package com.xreal.whisper

import android.content.Context
import android.util.Log
import com.google.android.gms.tflite.gpu.GpuDelegate
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

enum class ModelType {
    TINY, BASE, SMALL
}

/** ★ Phase W: 멀티백엔드 식별자 */
enum class WhisperBackend { TFLITE, WHISPER_CPP, SHERPA_ONNX }

/**
 * 음성 인식 결과 (텍스트 + SenseVoice 감정/언어/오디오이벤트 포함).
 *
 * SenseVoice 백엔드 사용 시:
 *   - emotion: "NEUTRAL" / "HAPPY" / "SAD" / "ANGRY" / "FEARFUL" / "DISGUSTED" / "SURPRISED"
 *   - lang: "ko" / "en" / "zh" / "ja" / "yue"
 *   - audioEvent: "speech" / "BGM" / "Laughter" / "Crying" / "Applause" / ...
 *
 * TFLite / whisper.cpp 백엔드: emotion=null, lang=null, audioEvent=null
 */
data class SpeechResult(
    val text: String,
    val emotion: String? = null,
    val lang: String? = null,
    val audioEvent: String? = null,
)

interface WhisperInference {
    /** 현재 백엔드 — 기본값 TFLITE (기존 구현체 하위 호환) */
    val backend: WhisperBackend get() = WhisperBackend.TFLITE

    fun initialize(options: Interpreter.Options)

    /** TFLite 경로: 토큰 배열 반환 (WhisperTokenizer.decode() 필요). 기본 빈 배열. */
    fun transcribe(audioData: ShortArray): IntArray = IntArray(0)

    /** ★ Phase W — cpp/sherpa 경로: 텍스트 직접 반환 (토크나이저 불필요). 기본 null. */
    fun transcribeText(audioData: ShortArray): String? = null

    /**
     * ★ Phase S — 전체 결과 반환 (텍스트 + 감정 + 언어).
     * 기본 구현: transcribeText() 결과를 SpeechResult로 래핑 (emotion=null).
     * SherpaOnnxInference에서 재정의하여 SenseVoice 감정 데이터 포함.
     */
    fun transcribeFull(audioData: ShortArray): SpeechResult? =
        transcribeText(audioData)?.let { SpeechResult(it) }

    fun getEmbedding(audioData: ShortArray): FloatArray? = null

    fun close()
}

class WhisperSingleInference(
    private val context: Context,
    private val modelPath: String,       // assets 경로 또는 파일 이름 (assets 우선)
    private val externalFilePath: String? = null  // 외부 저장소 전체 경로 (assets 없을 때 사용)
) : WhisperInference {

    private val TAG = "WhisperSingleInference"
    private var interpreter: Interpreter? = null
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer1D: IntArray? = null    // 출력 shape [N] 일 때
    private var outputBuffer2D: Array<IntArray>? = null  // 출력 shape [1, N] 일 때 (whisper-small 등)
    private var outputTokenCount = 448  // 기본값: Tiny 기준, 모델 로드 후 실제 값으로 업데이트
    private var outputIs2D = false      // true → [1, N] 형태, false → [N] 형태
    private val melSpectrogram = MelSpectrogram()

    override fun initialize(options: Interpreter.Options) {
        Log.i(TAG, "Initializing Single Model with injected options...")

        // ★ 우선순위: 1) 외부 파일 경로, 2) assets
        val modelBuffer: ByteBuffer = if (externalFilePath != null) {
            val file = File(externalFilePath)
            if (!file.exists()) {
                Log.e(TAG, "외부 모델 파일 없음: $externalFilePath")
                throw IllegalStateException("External model file not found: $externalFilePath")
            }
            Log.i(TAG, "외부 저장소에서 모델 로드: $externalFilePath (${file.length() / 1_048_576}MB)")
            FileInputStream(file).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
            }
        } else {
            Log.i(TAG, "Assets에서 모델 로드: $modelPath")
            val fd = context.assets.openFd(modelPath)
            FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }

        interpreter = Interpreter(modelBuffer, options)

        // ★ 출력 텐서 형태 자동 감지 → 토큰 버퍼 크기 결정
        val outTensor = interpreter!!.getOutputTensor(0)
        val outShape = outTensor.shape()
        outputTokenCount = when {
            outShape.size >= 2 -> outShape[outShape.size - 1]  // 마지막 차원 = 토큰 수
            outShape.size == 1 -> outShape[0]
            else -> 448
        }
        // ★ 2D 출력 여부 감지: [1, 448] → outputIs2D=true, [448] → false
        outputIs2D = outShape.size >= 2
        Log.i(TAG, "출력 텐서: shape=${outShape.toList()}, dtype=${outTensor.dataType()} → tokenBuf=$outputTokenCount, 2D=$outputIs2D")

        inputBuffer = ByteBuffer.allocateDirect(1 * 80 * 3000 * 4).order(ByteOrder.nativeOrder())
        // ★ 출력 버퍼: shape에 따라 1D 또는 2D 배열 할당
        if (outputIs2D) {
            outputBuffer2D = Array(1) { IntArray(outputTokenCount) }
        } else {
            outputBuffer1D = IntArray(outputTokenCount)
        }

        // Initialize Preprocessor
        melSpectrogram.loadFiltersFromAssets(context)
        Log.i(TAG, "✅ WhisperSingleInference 초기화 완료 (외부모델: ${externalFilePath != null})")
    }


    override fun transcribe(audioData: ShortArray): IntArray {
        val currentInterpreter = interpreter ?: return IntArray(0)
        val currentInputBuffer = inputBuffer ?: return IntArray(0)

        // Pre-processing: audio → mel spectrogram
        val melData = melSpectrogram.process(audioData)

        currentInputBuffer.rewind()
        for (i in 0 until 80) {
            for (j in 0 until 3000) {
                currentInputBuffer.putFloat(melData[i * 3000 + j])
            }
        }
        currentInputBuffer.rewind()

        return try {
            if (outputIs2D) {
                // ★ [1, 448] 형태 — 2D Array<IntArray>로 수신 후 첫 행 반환
                val buf2D = outputBuffer2D ?: return IntArray(0)
                val outputs = mutableMapOf<Int, Any>(0 to buf2D)
                currentInterpreter.runForMultipleInputsOutputs(arrayOf(currentInputBuffer), outputs)
                buf2D[0]
            } else {
                // [448] 형태 — 1D IntArray 직접 수신
                val buf1D = outputBuffer1D ?: return IntArray(0)
                val outputs = mutableMapOf<Int, Any>(0 to buf1D)
                currentInterpreter.runForMultipleInputsOutputs(arrayOf(currentInputBuffer), outputs)
                buf1D
            }
        } catch (e: Exception) {
            Log.e(TAG, "추론 실패: ${e.message}")
            IntArray(0)
        }
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
        
        // Match both naming conventions: "whisper_base_encoder..." or "whisper_encoder_base..."
        val encPath = assetList.find {
            it.contains("whisper") && it.contains(suffix) && it.contains("encoder") && it.endsWith(".tflite")
        } ?: "whisper_encoder_$suffix.tflite"

        val decPath = assetList.find {
            it.contains("whisper") && it.contains(suffix) && it.contains("decoder") && it.endsWith(".tflite")
        } ?: "whisper_decoder_$suffix.tflite"

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
