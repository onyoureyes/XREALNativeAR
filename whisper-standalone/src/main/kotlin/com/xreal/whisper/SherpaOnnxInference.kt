package com.xreal.whisper

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import org.tensorflow.lite.Interpreter
import java.io.File

/**
 * SherpaOnnxInference — sherpa-onnx SenseVoice 기반 오프라인 ASR.
 *
 * ## 지원 모델
 * - SenseVoice (권장): 한국어+영어+중국어+일어+광동어 다국어, 감정 인식 내장
 *   → 모델 디렉터리: model.int8.onnx + tokens.txt
 * - Zipformer Korean (대체): 한국어 전용
 *   → 모델 디렉터리: encoder*.onnx + decoder*.onnx + joiner*.onnx + tokens.txt
 *
 * ## 모델 경로 (ADB push 필요)
 * /sdcard/Android/data/com.xreal.nativear/files/models/sherpa-onnx-sense-voice/
 *
 * ## SenseVoice 감정 출력
 * transcribeFull() 반환 SpeechResult에:
 *   - emotion: "NEUTRAL" / "HAPPY" / "SAD" / "ANGRY" / "FEARFUL" / "DISGUSTED" / "SURPRISED"
 *   - lang: "ko" / "en" / "zh" / "ja" / "yue"
 *   - audioEvent: "speech" / "BGM" / "Laughter" / "Crying" / ...
 */
class SherpaOnnxInference(
    private val modelDir: File
) : WhisperInference {

    private val TAG = "SherpaOnnxInference"
    override val backend = WhisperBackend.SHERPA_ONNX

    private var recognizer: OfflineRecognizer? = null
    private var initialized = false

    override fun initialize(options: Interpreter.Options) {
        val dir = modelDir.absolutePath
        val isSenseVoice = modelDir.name.contains("sense-voice", ignoreCase = true) ||
                           modelDir.name.contains("sensevoice", ignoreCase = true)

        try {
            val config = if (isSenseVoice) {
                buildSenseVoiceConfig(dir)
            } else {
                buildZipformerConfig(dir)
            }
            recognizer = OfflineRecognizer(assetManager = null, config = config)
            initialized = true
            Log.i(TAG, "✅ sherpa-onnx 초기화 완료: ${modelDir.name} (SenseVoice=$isSenseVoice)")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "libsherpa-onnx-jni.so 로드 실패 — jniLibs/arm64-v8a/ 확인 필요: ${e.message}")
        } catch (e: NoClassDefFoundError) {
            // static init에서 UnsatisfiedLinkError → NoClassDefFoundError로 래핑됨
            Log.e(TAG, "sherpa-onnx 클래스 로드 실패 (libonnxruntime.so 누락?): ${e.message}")
        } catch (e: Throwable) {
            Log.e(TAG, "sherpa-onnx 초기화 실패: ${e.message}", e)
        }
    }

    /**
     * SenseVoice 모델 설정 빌드.
     * 파일명 후보: model.int8.onnx / model.onnx / sense-voice-encoder-int8.onnx / *encoder*.onnx
     */
    private fun buildSenseVoiceConfig(dir: String): OfflineRecognizerConfig {
        // model*.onnx 우선, 없으면 *encoder*.onnx (sense-voice-encoder-int8.onnx 포함)
        val modelFile = findFile(dir, "model", ".onnx")
            ?: findFile(dir, "encoder", ".onnx")
            ?: throw IllegalStateException("SenseVoice 모델 파일 없음 ($dir): model.onnx 또는 *encoder*.onnx 필요")
        val tokensFile = "$dir/tokens.txt"

        Log.d(TAG, "SenseVoice 모델: $modelFile")
        Log.d(TAG, "SenseVoice 토큰: $tokensFile")

        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = modelFile,
                    language = "",                        // 자동 감지 (ko/en/zh/ja/yue)
                    useInverseTextNormalization = true,   // 숫자/날짜 정규화
                ),
                tokens = tokensFile,
                numThreads = 4,
                provider = "cpu",
                debug = false,
            ),
            decodingMethod = "greedy_search",
        )
    }

    /**
     * Zipformer Korean 모델 설정 빌드.
     * 파일: encoder*.onnx + decoder*.onnx + joiner*.onnx + tokens.txt
     */
    private fun buildZipformerConfig(dir: String): OfflineRecognizerConfig {
        val encoder = findFile(dir, "encoder", ".onnx")
            ?: throw IllegalStateException("encoder.onnx 없음: $dir")
        val decoder = findFile(dir, "decoder", ".onnx")
            ?: throw IllegalStateException("decoder.onnx 없음: $dir")
        val joiner = findFile(dir, "joiner", ".onnx")
            ?: throw IllegalStateException("joiner.onnx 없음: $dir")
        val tokens = "$dir/tokens.txt"

        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = encoder,
                    decoder = decoder,
                    joiner = joiner,
                ),
                tokens = tokens,
                numThreads = 4,
                provider = "cpu",
                debug = false,
            ),
            decodingMethod = "greedy_search",
        )
    }

    // ── transcribeText() — backward compat ────────────────────────────────────

    override fun transcribeText(audioData: ShortArray): String? =
        transcribeFull(audioData)?.text

    // ── transcribeFull() — SenseVoice 감정 포함 결과 ────────────────────────────

    /**
     * 오디오 전사 + SenseVoice 감정/언어/이벤트 반환.
     *
     * @return SpeechResult (text, emotion, lang, audioEvent) or null on failure
     */
    override fun transcribeFull(audioData: ShortArray): SpeechResult? {
        val r = recognizer
        if (!initialized || r == null) {
            Log.w(TAG, "sherpa-onnx 미초기화 — null 반환")
            return null
        }
        return try {
            val floatAudio = FloatArray(audioData.size) { audioData[it] / 32768f }

            val stream = r.createStream()
            stream.acceptWaveform(floatAudio, sampleRate = 16000)
            r.decode(stream)
            val result = r.getResult(stream)
            stream.release()

            val text = result.text.trim()
            if (text.isBlank()) return null

            val emotion = result.emotion.takeIf { it.isNotBlank() }
            val lang = result.lang.takeIf { it.isNotBlank() }
            val event = result.event.takeIf { it.isNotBlank() }

            Log.d(TAG, "전사 완료: \"$text\" [lang=$lang, emotion=$emotion, event=$event]")
            SpeechResult(text = text, emotion = emotion, lang = lang, audioEvent = event)
        } catch (e: Exception) {
            Log.e(TAG, "sherpa-onnx 추론 오류: ${e.message}", e)
            null
        }
    }

    override fun close() {
        recognizer?.release()
        recognizer = null
        initialized = false
    }

    /** 디렉터리에서 키워드 포함 .onnx 파일 절대 경로 반환 (int8 우선) */
    private fun findFile(dir: String, keyword: String, ext: String): String? {
        return File(dir).listFiles()
            ?.filter { it.name.contains(keyword, ignoreCase = true) && it.name.endsWith(ext) }
            ?.minByOrNull { if (it.name.contains("int8")) 0 else 1 }
            ?.absolutePath
    }

    companion object {
        /**
         * 외부 저장소에서 sherpa-onnx 모델 디렉터리 검색.
         * 우선순위: sense-voice (다국어) > zipformer-korean > 기타 sherpa-onnx-*
         */
        fun findModelDir(baseDir: File): File? {
            val dirs = baseDir.listFiles()?.filter { it.isDirectory } ?: return null

            // 1순위: SenseVoice (한국어+영어 자동감지 + 감정)
            val senseVoice = dirs.firstOrNull { d ->
                d.name.contains("sense-voice", ignoreCase = true) ||
                d.name.contains("sensevoice", ignoreCase = true)
            }
            if (senseVoice != null) {
                Log.i("SherpaOnnxInference", "SenseVoice 모델 발견: ${senseVoice.name}")
                return senseVoice
            }

            // 2순위: Zipformer Korean
            val zipformer = dirs.firstOrNull { d ->
                d.name.contains("zipformer-korean", ignoreCase = true) ||
                d.name.contains("zipformer_korean", ignoreCase = true)
            }
            if (zipformer != null) {
                Log.i("SherpaOnnxInference", "Zipformer-Korean 모델 발견: ${zipformer.name}")
                return zipformer
            }

            // 3순위: 기타 sherpa-onnx-* 디렉터리
            val generic = dirs.firstOrNull { d ->
                d.name.startsWith("sherpa-onnx", ignoreCase = true) ||
                d.name.startsWith("sherpa_onnx", ignoreCase = true)
            }
            if (generic != null) {
                Log.i("SherpaOnnxInference", "sherpa-onnx 모델 발견: ${generic.name}")
            }
            return generic
        }
    }
}
