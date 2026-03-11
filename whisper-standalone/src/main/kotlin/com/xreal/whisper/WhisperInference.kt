package com.xreal.whisper

import android.content.Context
import android.util.Log
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
    private var dModel = 512  // tiny=384, base=512, small=768

    private val melSpectrogram = MelSpectrogram()

    // Whisper special tokens
    companion object {
        const val TOKEN_SOT = 50258       // <|startoftranscript|>
        const val TOKEN_EOT = 50257       // <|endoftext|>
        const val TOKEN_TRANSCRIBE = 50359 // <|transcribe|>
        const val TOKEN_NO_TIMESTAMPS = 50363 // <|notimestamps|>
        const val TOKEN_EN = 50259        // <|en|>
        const val TOKEN_KO = 50264        // <|ko|>
        const val MAX_DECODE_STEPS = 224  // Whisper 최대 디코딩 길이
    }

    override fun initialize(options: Interpreter.Options) {
        Log.i(TAG, "Initializing Split Inference ($modelType) with injected options...")

        val suffix = when(modelType) {
            ModelType.TINY -> "tiny"
            ModelType.BASE -> "base"
            ModelType.SMALL -> "small"
        }

        dModel = when(modelType) {
            ModelType.TINY -> 384
            ModelType.BASE -> 512
            ModelType.SMALL -> 768
        }

        val assetList = context.assets.list("") ?: emptyArray()

        val encPath = assetList.find {
            it.contains("whisper") && it.contains(suffix) && it.contains("encoder") && it.endsWith(".tflite")
        } ?: "whisper_encoder_$suffix.tflite"

        val decPath = assetList.find {
            it.contains("whisper") && it.contains(suffix) && it.contains("decoder") && it.endsWith(".tflite")
        } ?: "whisper_decoder_$suffix.tflite"

        Log.i(TAG, "Loading models: $encPath, $decPath")

        val assetManager = context.assets
        try {
            val encFd = assetManager.openFd(encPath)
            val encBuffer = FileInputStream(encFd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY, encFd.startOffset, encFd.declaredLength)
            encoderInterpreter = Interpreter(encBuffer, options)

            val decFd = assetManager.openFd(decPath)
            val decBuffer = FileInputStream(decFd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY, decFd.startOffset, decFd.declaredLength)
            decoderInterpreter = Interpreter(decBuffer, options)
        } catch (e: Exception) {
            Log.e(TAG, "Model Load Error: ${e.message}")
            throw e
        }

        inputBuffer = ByteBuffer.allocateDirect(1 * 80 * 3000 * 4).order(ByteOrder.nativeOrder())
        crossAttnBuffer = ByteBuffer.allocateDirect(1 * 1500 * dModel * 4).order(ByteOrder.nativeOrder())

        Log.i(TAG, "Buffer Allocation: d_model=$dModel")
        melSpectrogram.loadFiltersFromAssets(context)
        Log.i(TAG, "✅ WhisperSplitInference 초기화 완료")
    }

    override fun transcribe(audioData: ShortArray): IntArray {
        val encoder = encoderInterpreter ?: return IntArray(0)
        val decoder = decoderInterpreter ?: return IntArray(0)
        val mInputBuffer = inputBuffer ?: return IntArray(0)
        val mCrossAttn = crossAttnBuffer ?: return IntArray(0)

        // 1. Mel spectrogram 전처리
        val melData = melSpectrogram.process(audioData)
        if (melData.isEmpty()) {
            Log.e(TAG, "Mel spectrogram 비어있음 (filters.bin 누락?)")
            return IntArray(0)
        }

        mInputBuffer.rewind()
        for (i in 0 until 80) {
            for (j in 0 until 3000) mInputBuffer.putFloat(melData[i * 3000 + j])
        }
        mInputBuffer.rewind()

        // 2. 인코더 실행 → cross-attention 텐서
        mCrossAttn.rewind()
        encoder.runForMultipleInputsOutputs(
            arrayOf<Any>(mInputBuffer),
            mutableMapOf<Int, Any>(0 to mCrossAttn)
        )

        // 3. 디코더 autoregressive 루프
        // 초기 토큰: [SOT, lang, TRANSCRIBE, NO_TIMESTAMPS]
        val tokens = mutableListOf(TOKEN_SOT, TOKEN_EN, TOKEN_TRANSCRIBE, TOKEN_NO_TIMESTAMPS)

        try {
            for (step in 0 until MAX_DECODE_STEPS) {
                // 디코더 입력: cross-attention + 현재 토큰 시퀀스
                val tokenInput = ByteBuffer.allocateDirect(tokens.size * 4).order(ByteOrder.nativeOrder())
                for (t in tokens) tokenInput.putInt(t)
                tokenInput.rewind()

                mCrossAttn.rewind()

                // 디코더 출력: logits [1, vocab_size] 또는 [1, seq_len, vocab_size]
                // 모델 구조에 따라 출력 shape이 다를 수 있음 — 동적 감지
                val outTensor = decoder.getOutputTensor(0)
                val outShape = outTensor.shape()
                val vocabSize = outShape[outShape.size - 1]
                val outputSize = outShape.fold(1) { acc: Int, dim: Int -> acc * dim }
                val logitsBuffer = ByteBuffer.allocateDirect(outputSize * 4).order(ByteOrder.nativeOrder())

                decoder.runForMultipleInputsOutputs(
                    arrayOf<Any>(mCrossAttn, tokenInput),
                    mutableMapOf<Int, Any>(0 to logitsBuffer)
                )

                // 마지막 위치의 logits에서 argmax → 다음 토큰
                logitsBuffer.rewind()
                val floatBuf = logitsBuffer.asFloatBuffer()
                // 마지막 시퀀스 위치의 logits만 사용
                val lastOffset = (outputSize / vocabSize - 1) * vocabSize
                var maxVal = Float.NEGATIVE_INFINITY
                var maxIdx = 0
                for (v in 0 until vocabSize) {
                    val logit = floatBuf.get(lastOffset + v)
                    if (logit > maxVal) {
                        maxVal = logit
                        maxIdx = v
                    }
                }

                if (maxIdx == TOKEN_EOT) break
                tokens.add(maxIdx)
            }
        } catch (e: Exception) {
            Log.e(TAG, "디코더 루프 오류: ${e.message}", e)
        }

        // 프롬프트 토큰 제거 (SOT, lang, TRANSCRIBE, NO_TIMESTAMPS)
        return if (tokens.size > 4) tokens.subList(4, tokens.size).toIntArray() else IntArray(0)
    }

    override fun getEmbedding(audioData: ShortArray): FloatArray? {
        val encoder = encoderInterpreter ?: return null
        val mInputBuffer = inputBuffer ?: return null

        return try {
            val melData = melSpectrogram.process(audioData)
            if (melData.isEmpty()) return null

            mInputBuffer.rewind()
            for (i in 0 until 80) {
                for (j in 0 until 3000) mInputBuffer.putFloat(melData[i * 3000 + j])
            }
            mInputBuffer.rewind()

            val encoderOutputSize = 1 * 1500 * dModel
            val encoderOutputBuffer = ByteBuffer.allocateDirect(encoderOutputSize * 4)
                .order(ByteOrder.nativeOrder())

            // ★ Kotlin T.run {} 확장함수 충돌 회피 — runForMultipleInputsOutputs 사용
            encoder.runForMultipleInputsOutputs(
                arrayOf<Any>(mInputBuffer),
                mutableMapOf<Int, Any>(0 to encoderOutputBuffer)
            )

            // Average pooling → d_model-dim 벡터
            encoderOutputBuffer.rewind()
            val embedding = FloatArray(dModel)
            val tempBuffer = FloatArray(encoderOutputSize)
            encoderOutputBuffer.asFloatBuffer().get(tempBuffer)

            for (i in 0 until dModel) {
                var sum = 0f
                for (j in 0 until 1500) {
                    sum += tempBuffer[j * dModel + i]
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
        for (value in vector) { sumSquares += value * value }
        val magnitude = kotlin.math.sqrt(sumSquares)
        if (magnitude == 0f) return vector
        for (i in vector.indices) { vector[i] /= magnitude }
        return vector
    }

    override fun close() {
        encoderInterpreter?.close()
        decoderInterpreter?.close()
        encoderInterpreter = null
        decoderInterpreter = null
    }
}
