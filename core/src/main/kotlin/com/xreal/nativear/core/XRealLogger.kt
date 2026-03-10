package com.xreal.nativear.core

/**
 * android.util.Log 추상화 — core 모듈의 Android 프레임워크 종속 제거.
 *
 * 프로덕션: [AndroidLogger] (android.util.Log 위임)
 * 테스트:   [NoOpLogger] (기본값, 출력 없음)
 *
 * ## 설정
 * ```kotlin
 * // Application.onCreate():
 * XRealLogger.impl = AndroidLogger
 *
 * // 테스트:
 * XRealLogger.impl = NoOpLogger  // 기본값이므로 설정 불필요
 * ```
 */
interface XRealLogger {

    fun v(tag: String, msg: String)
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String, t: Throwable? = null)
    fun e(tag: String, msg: String, t: Throwable? = null)
    fun isLoggable(tag: String, level: Int): Boolean

    companion object {
        /** 현재 활성 로거. 프로덕션에서 AndroidLogger로 교체. */
        @Volatile
        var impl: XRealLogger = NoOpLogger

        // android.util.Log 레벨 상수 (android.util.Log 미참조용)
        const val VERBOSE = 2
        const val DEBUG = 3
        const val INFO = 4
        const val WARN = 5
        const val ERROR = 6
    }
}

/** 테스트/JVM 환경용 — 아무것도 출력하지 않음. */
object NoOpLogger : XRealLogger {
    override fun v(tag: String, msg: String) {}
    override fun d(tag: String, msg: String) {}
    override fun i(tag: String, msg: String) {}
    override fun w(tag: String, msg: String, t: Throwable?) {}
    override fun e(tag: String, msg: String, t: Throwable?) {}
    override fun isLoggable(tag: String, level: Int) = false
}

/** android.util.Log 위임 구현. AppBootstrapper에서 설정. */
object AndroidLogger : XRealLogger {
    override fun v(tag: String, msg: String) = run { android.util.Log.v(tag, msg); Unit }
    override fun d(tag: String, msg: String) = run { android.util.Log.d(tag, msg); Unit }
    override fun i(tag: String, msg: String) = run { android.util.Log.i(tag, msg); Unit }
    override fun w(tag: String, msg: String, t: Throwable?) = run { android.util.Log.w(tag, msg, t); Unit }
    override fun e(tag: String, msg: String, t: Throwable?) = run { android.util.Log.e(tag, msg, t); Unit }
    override fun isLoggable(tag: String, level: Int) = android.util.Log.isLoggable(tag, level)
}
