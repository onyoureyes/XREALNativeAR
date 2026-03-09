package com.k2fsa.sherpa.onnx

/**
 * sherpa-onnx v1.12.28 Android Kotlin JNI 데이터 클래스.
 *
 * 이 파일의 클래스 정의는 libsherpa-onnx-jni.so의 JNI 함수가 기대하는
 * 정확한 필드명·타입·순서에 맞춰 작성되었습니다.
 * (k2-fsa/sherpa-onnx 공식 kotlin-api 소스 기반)
 *
 * ⚠️ 필드명 변경 금지 — JNI native 코드가 반사적으로 필드명을 조회합니다.
 */

// ── 공통 설정 ─────────────────────────────────────────────────────────────────

data class FeatureConfig(
    var sampleRate: Int = 16000,
    var featureDim: Int = 80,
    var dither: Float = 0.0f
)

data class QnnConfig(
    var backendLib: String = "",
    var contextBinary: String = "",
    var systemLib: String = "",
)

data class HomophoneReplacerConfig(
    var dictDir: String = "",
    var lexicon: String = "",
    var ruleFsts: String = "",
)

// ── 인식 결과 ──────────────────────────────────────────────────────────────────

/**
 * 오프라인 인식 결과.
 *
 * SenseVoice 전용 필드:
 *   - lang: 감지된 언어 코드 ("ko", "en", "zh", "ja", "yue")
 *   - emotion: 7-class 감정 ("NEUTRAL", "HAPPY", "SAD", "ANGRY", "FEARFUL", "DISGUSTED", "SURPRISED")
 *   - event: 오디오 이벤트 ("speech", "BGM", "Laughter", "Crying", "Applause", ...)
 *   - durations: TDT 모델 전용 (SenseVoice는 빈 배열)
 *
 * ⚠️ 이 data class는 JNI native 코드에서 직접 생성합니다.
 *    필드 순서와 타입은 libsherpa-onnx-jni.so가 기대하는 생성자 시그니처와 정확히 일치해야 합니다.
 */
data class OfflineRecognizerResult(
    val text: String,
    val tokens: Array<String>,
    val timestamps: FloatArray,
    val lang: String,       // SenseVoice: 감지된 언어 (예: "ko")
    val emotion: String,    // SenseVoice: 감지된 감정 (예: "HAPPY")
    val event: String,      // SenseVoice: 오디오 이벤트 (예: "speech")
    val durations: FloatArray,  // TDT 전용, SenseVoice는 빈 배열
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OfflineRecognizerResult) return false
        return text == other.text && lang == other.lang && emotion == other.emotion && event == other.event
    }
    override fun hashCode(): Int = text.hashCode() * 31 + lang.hashCode()
}

// ── 모델별 설정 클래스 ──────────────────────────────────────────────────────────

data class OfflineTransducerModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var joiner: String = "",
)

data class OfflineParaformerModelConfig(
    var model: String = "",
    var qnnConfig: QnnConfig = QnnConfig(),
)

data class OfflineNemoEncDecCtcModelConfig(
    var model: String = "",
)

data class OfflineWhisperModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var language: String = "en",
    var task: String = "transcribe",
    var tailPaddings: Int = 1000,
    var enableTokenTimestamps: Boolean = false,
    var enableSegmentTimestamps: Boolean = false,
)

/**
 * SenseVoice 모델 설정.
 *
 * @param model 모델 .onnx 파일 절대 경로 (예: "/sdcard/.../model.int8.onnx")
 * @param language 언어 코드 또는 빈 문자열(자동 감지). "ko"/"en"/"zh"/"ja"/"yue" 또는 "" 사용
 * @param useInverseTextNormalization 역텍스트 정규화 (숫자/날짜 표기 정규화). 권장: true
 * @param qnnConfig QNN 가속 설정 (사용 안 하면 기본값 유지)
 */
data class OfflineSenseVoiceModelConfig(
    var model: String = "",
    var language: String = "",
    var useInverseTextNormalization: Boolean = true,
    var qnnConfig: QnnConfig = QnnConfig(),
)

data class OfflineFireRedAsrModelConfig(
    var encoder: String = "",
    var decoder: String = "",
)

data class OfflineFireRedAsrCtcModelConfig(
    var model: String = "",
)

data class OfflineMoonshineModelConfig(
    var preprocessor: String = "",
    var encoder: String = "",
    var uncachedDecoder: String = "",
    var cachedDecoder: String = "",
    var mergedDecoder: String = "",
)

data class OfflineDolphinModelConfig(
    var model: String = "",
)

data class OfflineZipformerCtcModelConfig(
    var model: String = "",
    var qnnConfig: QnnConfig = QnnConfig(),
)

data class OfflineWenetCtcModelConfig(
    var model: String = "",
)

data class OfflineOmnilingualAsrCtcModelConfig(
    var model: String = "",
)

data class OfflineMedAsrCtcModelConfig(
    var model: String = "",
)

data class OfflineFunAsrNanoModelConfig(
    var encoderAdaptor: String = "",
    var llm: String = "",
    var embedding: String = "",
    var tokenizer: String = "",
    var systemPrompt: String = "You are a helpful assistant.",
    var userPrompt: String = "语音转写：",
    var maxNewTokens: Int = 512,
    var temperature: Float = 1e-6f,
    var topP: Float = 0.8f,
    var seed: Int = 42,
    var language: String = "",
    var itn: Boolean = true,
    var hotwords: String = "",
)

data class OfflineCanaryModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var srcLang: String = "en",
    var tgtLang: String = "en",
    var usePnc: Boolean = true,
)

// ── 통합 모델 설정 ────────────────────────────────────────────────────────────

/**
 * 오프라인 모델 통합 설정.
 *
 * 사용하는 모델 타입 하나만 설정하고 나머지는 기본값으로 유지합니다.
 * JNI native 코드가 어떤 모델 파일이 설정되어 있는지 확인하여 해당 모델만 사용합니다.
 *
 * SenseVoice 사용 예:
 * ```
 * OfflineModelConfig(
 *     senseVoice = OfflineSenseVoiceModelConfig(model = "/path/model.int8.onnx"),
 *     tokens = "/path/tokens.txt",
 *     numThreads = 4,
 *     provider = "cpu",
 * )
 * ```
 */
data class OfflineModelConfig(
    var transducer: OfflineTransducerModelConfig = OfflineTransducerModelConfig(),
    var paraformer: OfflineParaformerModelConfig = OfflineParaformerModelConfig(),
    var whisper: OfflineWhisperModelConfig = OfflineWhisperModelConfig(),
    var fireRedAsr: OfflineFireRedAsrModelConfig = OfflineFireRedAsrModelConfig(),
    var moonshine: OfflineMoonshineModelConfig = OfflineMoonshineModelConfig(),
    var nemo: OfflineNemoEncDecCtcModelConfig = OfflineNemoEncDecCtcModelConfig(),
    var senseVoice: OfflineSenseVoiceModelConfig = OfflineSenseVoiceModelConfig(),
    var dolphin: OfflineDolphinModelConfig = OfflineDolphinModelConfig(),
    var zipformerCtc: OfflineZipformerCtcModelConfig = OfflineZipformerCtcModelConfig(),
    var wenetCtc: OfflineWenetCtcModelConfig = OfflineWenetCtcModelConfig(),
    var omnilingual: OfflineOmnilingualAsrCtcModelConfig = OfflineOmnilingualAsrCtcModelConfig(),
    var medasr: OfflineMedAsrCtcModelConfig = OfflineMedAsrCtcModelConfig(),
    var funasrNano: OfflineFunAsrNanoModelConfig = OfflineFunAsrNanoModelConfig(),
    var fireRedAsrCtc: OfflineFireRedAsrCtcModelConfig = OfflineFireRedAsrCtcModelConfig(),
    var canary: OfflineCanaryModelConfig = OfflineCanaryModelConfig(),
    var teleSpeech: String = "",
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
    var modelType: String = "",
    var tokens: String = "",
    var modelingUnit: String = "",
    var bpeVocab: String = "",
)

// ── 인식기 설정 ────────────────────────────────────────────────────────────────

data class OfflineRecognizerConfig(
    var featConfig: FeatureConfig = FeatureConfig(),
    var modelConfig: OfflineModelConfig = OfflineModelConfig(),
    var hr: HomophoneReplacerConfig = HomophoneReplacerConfig(),
    var decodingMethod: String = "greedy_search",
    var maxActivePaths: Int = 4,
    var hotwordsFile: String = "",
    var hotwordsScore: Float = 1.5f,
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var blankPenalty: Float = 0.0f,
)
