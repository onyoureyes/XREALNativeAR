package com.k2fsa.sherpa.onnx

/**
 * sherpa-onnx OfflineStream — JNI 오디오 스트림 래퍼.
 *
 * 사용 패턴:
 * ```
 * val stream = recognizer.createStream()
 * stream.acceptWaveform(floatSamples, sampleRate = 16000)
 * recognizer.decode(stream)
 * val result = recognizer.getResult(stream)
 * stream.release()
 * ```
 *
 * ⚠️ 반드시 사용 후 release() 호출하여 native 메모리 해제.
 */
class OfflineStream(var ptr: Long) {

    /**
     * 오디오 파형 입력.
     * @param samples Float 배열 (범위: -1.0 ~ 1.0, Short → Float 변환: samples[i] / 32768f)
     * @param sampleRate 샘플레이트 (SenseVoice: 16000)
     */
    fun acceptWaveform(samples: FloatArray, sampleRate: Int) =
        acceptWaveform(ptr, samples, sampleRate)

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    /** native 메모리 해제. */
    fun release() = finalize()

    // ── JNI native 함수 ──────────────────────────────────────────────────────
    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun delete(ptr: Long)

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
