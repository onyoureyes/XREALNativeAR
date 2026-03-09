package com.k2fsa.sherpa.onnx

/**
 * sherpa-onnx v1.12.28 Speaker Embedding Extractor JNI 데이터 클래스.
 *
 * 3D-Speaker ECAPA-TDNN 모델 사용 (20MB, 192-dim 출력).
 * libsherpa-onnx-jni.so의 JNI 함수가 기대하는 정확한 필드명·타입·순서.
 *
 * ⚠️ 필드명 변경 금지 — JNI native 코드가 반사적으로 필드명을 조회합니다.
 */

data class SpeakerEmbeddingExtractorConfig(
    var model: String = "",
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
)

/**
 * Speaker Embedding Extractor — 화자 임베딩 추출.
 *
 * 3D-Speaker ECAPA-TDNN 모델 (20MB, 192-dim):
 *   - WavLM (370MB, 768-dim) 대비 18.5배 경량
 *   - Speaker verification / diarization 전용 모델
 *   - sherpa-onnx native JNI로 동작
 *
 * 사용법:
 * ```kotlin
 * val extractor = SpeakerEmbeddingExtractor(config)
 * val stream = extractor.createStream()
 * stream.acceptWaveform(floatAudio, sampleRate = 16000)
 * stream.inputFinished()
 * val embedding = extractor.compute(stream)  // FloatArray(192)
 * stream.release()
 * extractor.release()
 * ```
 */
class SpeakerEmbeddingExtractor(
    assetManager: android.content.res.AssetManager? = null,
    val config: SpeakerEmbeddingExtractorConfig,
) {
    private var ptr: Long

    init {
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
    }

    fun createStream(): OnlineStream {
        val p = createStream(ptr)
        return OnlineStream(p)
    }

    fun isReady(stream: OnlineStream): Boolean = isReady(ptr, stream.ptr)

    fun compute(stream: OnlineStream): FloatArray = compute(ptr, stream.ptr)

    val dim: Int get() = dim(ptr)

    fun release() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0L
        }
    }

    protected fun finalize() {
        release()
    }

    private external fun newFromAsset(
        assetManager: android.content.res.AssetManager,
        config: SpeakerEmbeddingExtractorConfig,
    ): Long

    private external fun newFromFile(config: SpeakerEmbeddingExtractorConfig): Long
    private external fun delete(ptr: Long)
    private external fun createStream(ptr: Long): Long
    private external fun isReady(ptr: Long, streamPtr: Long): Boolean
    private external fun compute(ptr: Long, streamPtr: Long): FloatArray
    private external fun dim(ptr: Long): Int
}

/**
 * Online audio stream — SpeakerEmbeddingExtractor용 오디오 입력.
 *
 * ⚠️ sherpa-onnx의 OnlineStream JNI 래퍼. OfflineStream과 별도 클래스.
 */
class OnlineStream(internal val ptr: Long) {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        acceptWaveform(ptr, samples, sampleRate)
    }

    fun inputFinished() {
        inputFinished(ptr)
    }

    fun release() {
        if (ptr != 0L) {
            delete(ptr)
        }
    }

    protected fun finalize() {
        release()
    }

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun inputFinished(ptr: Long)
    private external fun delete(ptr: Long)
}
