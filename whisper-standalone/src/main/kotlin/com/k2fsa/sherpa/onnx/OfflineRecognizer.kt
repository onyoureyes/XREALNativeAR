package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

/**
 * sherpa-onnx OfflineRecognizer — 비스트리밍 오프라인 음성 인식 JNI 래퍼.
 *
 * SenseVoice (다국어 + 감정 인식):
 * ```
 * val config = OfflineRecognizerConfig(
 *     featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
 *     modelConfig = OfflineModelConfig(
 *         senseVoice = OfflineSenseVoiceModelConfig(
 *             model = "/sdcard/.../sense-voice/model.int8.onnx",
 *             language = "",       // 자동 감지 (한국어+영어+중국어+일어+광동어)
 *             useInverseTextNormalization = true,
 *         ),
 *         tokens = "/sdcard/.../sense-voice/tokens.txt",
 *         numThreads = 4,
 *         provider = "cpu",
 *     ),
 * )
 * val recognizer = OfflineRecognizer(config = config)
 * val stream = recognizer.createStream()
 * stream.acceptWaveform(floatSamples, sampleRate = 16000)
 * recognizer.decode(stream)
 * val result = recognizer.getResult(stream)
 * println("text=${result.text}, lang=${result.lang}, emotion=${result.emotion}")
 * stream.release()
 * recognizer.release()
 * ```
 *
 * @param assetManager null = 파일시스템 경로 사용 (외부 다운로드 모델)
 *                     non-null = APK assets에서 모델 로드
 * @param config OfflineRecognizerConfig (모델 경로, 스레드 수, 제공자 설정 등)
 */
class OfflineRecognizer(
    assetManager: AssetManager? = null,
    val config: OfflineRecognizerConfig,
) {
    private var ptr: Long

    init {
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    /** native 메모리 해제. */
    fun release() = finalize()

    /** 새 OfflineStream 생성. 오디오 입력 후 decode() 호출 필요. */
    fun createStream(): OfflineStream {
        val p = createStream(ptr)
        return OfflineStream(p)
    }

    /**
     * 인식 결과 반환.
     * @param stream decode() 완료된 OfflineStream
     * @return OfflineRecognizerResult (text, lang, emotion, event 포함)
     */
    fun getResult(stream: OfflineStream): OfflineRecognizerResult {
        return getResult(stream.ptr)
    }

    /**
     * 스트림 디코딩 (내부 ASR 추론 실행).
     * acceptWaveform() → decode() → getResult() 순서로 호출.
     */
    fun decode(stream: OfflineStream) = decode(ptr, stream.ptr)

    /** 런타임 설정 변경 (hotwords 등). */
    fun setConfig(config: OfflineRecognizerConfig) = setConfig(ptr, config)

    // ── JNI native 함수 ─────────────────────────────────────────────────────
    private external fun delete(ptr: Long)
    private external fun createStream(ptr: Long): Long
    private external fun setConfig(ptr: Long, config: OfflineRecognizerConfig)
    private external fun newFromAsset(assetManager: AssetManager, config: OfflineRecognizerConfig): Long
    private external fun newFromFile(config: OfflineRecognizerConfig): Long
    private external fun decode(ptr: Long, streamPtr: Long)
    private external fun getResult(streamPtr: Long): OfflineRecognizerResult

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }

        @JvmStatic
        external fun prependAdspLibraryPath(newPath: String)
    }
}
