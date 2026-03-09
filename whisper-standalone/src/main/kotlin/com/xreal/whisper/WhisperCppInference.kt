package com.xreal.whisper

import android.util.Log
import io.github.givimad.whisperjni.WhisperContext
import io.github.givimad.whisperjni.WhisperFullParams
import io.github.givimad.whisperjni.WhisperJNI
import io.github.givimad.whisperjni.WhisperSamplingStrategy
import org.tensorflow.lite.Interpreter
import java.io.File

/**
 * WhisperCppInference — whisper-jni(whisper.cpp) 기반 추론.
 *
 * ## Phase W (Primary 백엔드)
 * - 라이브러리: io.github.givimad:whisper-jni:1.7.1 (Maven Central, arm64 prebuilt)
 * - 모델: GGML 포맷 (.bin) — ggml-base-q5_1.bin (57MB) 또는 ggml-small-q5_1.bin (190MB)
 * - 언어: "auto" (한국어/영어 자동감지)
 *
 * ## 모델 배치 경로 (ADB push 필요)
 * /sdcard/Android/data/com.xreal.nativear/files/models/ggml-*.bin
 *
 * ## 주의: Qualcomm QNN .bin 파일은 GGML 포맷이 아님 → 호환 불가
 */
class WhisperCppInference(
    private val modelPath: String   // 절대 경로: ggml-*.bin
) : WhisperInference {

    private val TAG = "WhisperCppInference"
    override val backend = WhisperBackend.WHISPER_CPP

    private var whisperJni: WhisperJNI? = null
    private var ctx: WhisperContext? = null

    override fun initialize(options: Interpreter.Options) {
        try {
            // Android Bionic .so 우선 로드:
            //   jniLibs/arm64-v8a/에 NDK 빌드된 libggml*.so + libwhisper.so 배치 시 작동.
            //   없으면 whisper-jni:1.7.1 내장 GLIBC .so 시도 (Android에서 실패 예상).
            //
            // NDK 빌드: https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.android
            // 필요 .so: libggml.so, libggml-base.so, libggml-cpu.so, libwhisper.so (arm64-v8a)
            try {
                System.loadLibrary("ggml")
                System.loadLibrary("ggml-base")
                System.loadLibrary("ggml-cpu")
                System.loadLibrary("whisper")
                Log.i(TAG, "Android NDK .so 로드 성공 (jniLibs/arm64-v8a/)")
            } catch (e: UnsatisfiedLinkError) {
                Log.d(TAG, "NDK .so 없음 → whisper-jni 내장 .so 시도 (Android에서 실패 가능)")
                WhisperJNI.loadLibrary()
            }
            val jni = WhisperJNI()
            whisperJni = jni
            ctx = jni.init(File(modelPath).toPath())
            Log.i(TAG, "whisper.cpp 초기화 완료: $modelPath (${File(modelPath).length() / 1_048_576}MB)")
        } catch (e: Exception) {
            Log.e(TAG, "whisper.cpp 초기화 실패: ${e.message}")
            throw e
        }
    }

    /**
     * GGML 모델로 오디오 전사.
     * language="auto" → 한국어/영어 자동감지.
     */
    override fun transcribeText(audioData: ShortArray): String? {
        val jni = whisperJni ?: return null
        val context = ctx ?: return null
        return try {
            val floatAudio = FloatArray(audioData.size) { audioData[it] / 32768f }
            val params = WhisperFullParams(WhisperSamplingStrategy.GREEDY).apply {
                language = "auto"       // 한국어/영어 자동감지
                noTimestamps = true
                singleSegment = false
                printProgress = false
                printRealtime = false
                printTimestamps = false
                suppressBlank = true
            }
            jni.full(context, params, floatAudio, floatAudio.size)
            val segmentCount = jni.fullNSegments(context)
            if (segmentCount <= 0) return null
            buildString {
                for (i in 0 until segmentCount) {
                    append(jni.fullGetSegmentText(context, i))
                }
            }.trim().ifBlank { null }
        } catch (e: Exception) {
            Log.e(TAG, "whisper.cpp 추론 오류: ${e.message}")
            null
        }
    }

    override fun getEmbedding(audioData: ShortArray): FloatArray? = null  // whisper.cpp 미지원

    override fun close() {
        try {
            ctx?.close()
        } catch (e: Exception) {
            Log.w(TAG, "ctx close 오류: ${e.message}")
        }
        ctx = null
        whisperJni = null
    }

    companion object {
        /**
         * 외부 저장소에서 GGML 모델 검색.
         * 우선순위: small-q5_1 > small > base-q5_1 > base > tiny-q5_1 > tiny
         */
        fun findModel(baseDir: File): File? {
            val priority = listOf(
                "ggml-small-q5_1.bin", "ggml-small.bin",
                "ggml-base-q5_1.bin",  "ggml-base.bin",
                "ggml-tiny-q5_1.bin",  "ggml-tiny.bin"
            )
            for (name in priority) {
                val f = File(baseDir, name)
                // 최소 1MB 이상 → 실제 GGML 바이너리 (HTML/placeholder 제외)
                if (f.exists() && f.length() > 1_000_000L) {
                    Log.i("WhisperCppInference", "GGML 모델 발견: ${f.name} (${f.length() / 1_048_576}MB)")
                    return f
                }
            }
            return null
        }
    }
}
