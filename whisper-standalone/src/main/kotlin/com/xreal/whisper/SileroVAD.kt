package com.xreal.whisper

/**
 * SileroVAD — android-vad(ONNX Runtime) 기반 음성 활동 감지.
 *
 * ## Phase W 변경 (기존 TFLite 코드 완전 교체)
 * - 기존: silero_vad.tflite (299KB HTML 파일, 에너지 폴백만 동작)
 * - 현재: gkonovalov/android-vad:silero:2.0.10 (Silero VAD ONNX, ~2MB 내장)
 *
 * ## 공개 API 변경
 * - isSpeech(ShortArray): Float → isSpeech(ShortArray): Boolean
 * - AudioCaptureManager: `speechProb > 0.6f` → `isSpeech == true`
 *
 * ## 폴백
 * VadSilero 초기화 실패 시 에너지 기반(RMS > 1800) 폴백 유지.
 */

import android.content.Context
import android.util.Log
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import kotlin.math.sqrt

class SileroVAD(private val context: Context) {
    private val TAG = "SileroVAD"
    private var vad: VadSilero? = null

    init {
        try {
            vad = VadSilero(
                context,
                sampleRate = SampleRate.SAMPLE_RATE_16K,
                frameSize = FrameSize.FRAME_SIZE_512,
                mode = Mode.NORMAL
            )
            Log.i(TAG, "SileroVAD(ONNX) 초기화 완료 — android-vad:silero:2.0.10")
        } catch (e: Throwable) {
            // NoClassDefFoundError(onnxruntime Java API 미포함) 또는 UnsatisfiedLinkError 대응
            Log.e(TAG, "SileroVAD 초기화 실패 → 에너지 폴백 사용: ${e.message}")
            vad = null
        }
    }

    /**
     * 512샘플 프레임의 음성 여부 반환.
     * ONNX Silero VAD 추론 → 실패 시 에너지 기반 폴백(RMS > 1800).
     */
    fun isSpeech(samples: ShortArray): Boolean {
        val v = vad
        return if (v != null) {
            val floatSamples = FloatArray(samples.size) { samples[it] / 32768f }
            try {
                v.isSpeech(floatSamples)
            } catch (e: Exception) {
                Log.w(TAG, "VadSilero 추론 오류 → 에너지 폴백: ${e.message}")
                energyFallback(samples)
            }
        } else {
            energyFallback(samples)
        }
    }

    /** RMS 기반 에너지 폴백 — ONNX 미사용 시 */
    private fun energyFallback(samples: ShortArray): Boolean {
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        val rms = sqrt(sum / samples.size)
        return rms > 1800.0
    }

    fun close() {
        vad?.close()
        vad = null
    }
}
