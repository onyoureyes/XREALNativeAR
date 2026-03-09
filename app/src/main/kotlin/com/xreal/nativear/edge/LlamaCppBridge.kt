package com.xreal.nativear.edge

import android.util.Log

/**
 * LlamaCppBridge — llama.cpp JNI Kotlin 래퍼.
 *
 * C++ 구현: app/src/main/cpp/llama_bridge.cpp
 * 네이티브 라이브러리: xreal_native_ar.so (기존 JNI와 동일 .so에 포함)
 *
 * ## 사용법
 * ```kotlin
 * LlamaCppBridge.init()                          // 앱 시작 시 1회
 * val handle = LlamaCppBridge.loadModel(path, 512, 4)
 * val text = LlamaCppBridge.generate(handle, prompt, 256, 0.7f, 0.9f)
 * LlamaCppBridge.unloadModel(handle)
 * LlamaCppBridge.cleanup()                       // 앱 종료 시
 * ```
 */
object LlamaCppBridge {
    private const val TAG = "LlamaCppBridge"

    @Volatile
    private var loaded = false

    /**
     * 네이티브 라이브러리 로드.
     * System.loadLibrary()는 1회만 호출 (중복 호출 무해하지만 로그 정리용).
     */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                System.loadLibrary("xreal_native_ar")
                loaded = true
                Log.i(TAG, "xreal_native_ar.so 로드 완료")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "xreal_native_ar.so 로드 실패: ${e.message}")
            }
        }
    }

    // ── JNI 함수 선언 ──

    /** llama.cpp backend 초기화 (앱 시작 시 1회) */
    @JvmStatic
    external fun nativeInit()

    /** llama.cpp backend 해제 (앱 종료 시) */
    @JvmStatic
    external fun nativeCleanup()

    /**
     * GGUF 모델 로딩.
     * @param modelPath GGUF 파일 절대 경로
     * @param nCtx 컨텍스트 크기 (256~2048)
     * @param nThreads CPU 스레드 수 (4~8)
     * @return 핸들 (jlong, 0=실패)
     */
    @JvmStatic
    external fun nativeLoadModel(modelPath: String, nCtx: Int, nThreads: Int): Long

    /**
     * 텍스트 생성 (동기).
     * @param handle nativeLoadModel() 반환값
     * @param prompt 프롬프트 (Gemma chat template 포함)
     * @param maxTokens 최대 생성 토큰 수
     * @param temperature 온도 (0.0=greedy, 0.7=default)
     * @param topP nucleus sampling (0.9 default)
     * @return 생성된 텍스트 (null=오류)
     */
    @JvmStatic
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float
    ): String?

    /**
     * 모델 언로드.
     * @param handle nativeLoadModel() 반환값
     */
    @JvmStatic
    external fun nativeUnloadModel(handle: Long)

    /**
     * 모델 vocab 크기 (로딩 확인용).
     * @param handle nativeLoadModel() 반환값
     * @return vocab 크기 (0=실패)
     */
    @JvmStatic
    external fun nativeGetVocabSize(handle: Long): Int

    // ── 편의 메서드 ──

    /** backend 초기화 + 라이브러리 로드 */
    fun init() {
        ensureLoaded()
        if (loaded) {
            nativeInit()
            Log.i(TAG, "llama.cpp backend 초기화 완료")
        }
    }

    /** backend 정리 */
    fun cleanup() {
        if (loaded) {
            nativeCleanup()
            Log.i(TAG, "llama.cpp backend 정리 완료")
        }
    }

    /**
     * 모델 로딩 (편의 래퍼).
     * @return 핸들 (0L=실패)
     */
    fun loadModel(modelPath: String, nCtx: Int = 512, nThreads: Int = 4): Long {
        ensureLoaded()
        if (!loaded) return 0L
        return nativeLoadModel(modelPath, nCtx, nThreads)
    }

    /**
     * 텍스트 생성 (편의 래퍼).
     * @return 생성된 텍스트 (null=오류)
     */
    fun generate(
        handle: Long,
        prompt: String,
        maxTokens: Int = 256,
        temperature: Float = 0.7f,
        topP: Float = 0.9f
    ): String? {
        if (!loaded || handle == 0L) return null
        return nativeGenerate(handle, prompt, maxTokens, temperature, topP)
    }

    /** 모델 언로드 (편의 래퍼) */
    fun unloadModel(handle: Long) {
        if (!loaded || handle == 0L) return
        nativeUnloadModel(handle)
    }

    /** vocab 크기 조회 (0=미로딩) */
    fun getVocabSize(handle: Long): Int {
        if (!loaded || handle == 0L) return 0
        return nativeGetVocabSize(handle)
    }
}
