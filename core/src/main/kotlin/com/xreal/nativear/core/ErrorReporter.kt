package com.xreal.nativear.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ErrorReporter v3 — 심각도 기반 에러 트리아지.
 *
 * ## 심각도 3단계
 * | 심각도 | 동작 |
 * |--------|------|
 * | CRITICAL | logcat(e) + EventBus **동기** 발행 (dedup 없음) |
 * | WARNING  | logcat(w) + 60초 dedup + 분당 10건 제한 후 EventBus 비동기 발행 |
 * | INFO     | logcat(i) only — EventBus 발행 없음 |
 *
 * ## 인스턴스 관리
 * Koin `single {}` 로 등록. 기존 `ErrorReporter.report(...)` 정적 호출은
 * companion object가 Koin 인스턴스에 위임 → **기존 22개 호출부 변경 불필요**.
 * 테스트에서는 `ErrorReporter(testBus, testDispatcher)` 로 새 인스턴스 생성.
 */
class ErrorReporter(
    private val eventBus: GlobalEventBus,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val tag = "ErrorReporter"
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val dedupCache = ConcurrentHashMap<String, Long>()
    private val warningCountThisMinute = AtomicInteger(0)
    @Volatile private var rateLimitWindowStart = System.currentTimeMillis()

    fun report(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        severity: ErrorSeverity = ErrorSeverity.WARNING
    ) {
        when (severity) {
            ErrorSeverity.CRITICAL -> XRealLogger.impl.e(tag, message, throwable)
            ErrorSeverity.WARNING  -> XRealLogger.impl.w(tag, message, throwable)
            ErrorSeverity.INFO     -> XRealLogger.impl.i(tag, message)
        }

        if (severity == ErrorSeverity.INFO) return

        val fullMessage = if (throwable?.message != null) "$message: ${throwable.message}" else message
        val errorEvent = XRealEvent.SystemEvent.Error(
            code = tag,
            message = fullMessage,
            throwable = throwable,
            severity = severity
        )

        // CRITICAL → 동기 발행 (tryEmit은 논블로킹)
        if (severity == ErrorSeverity.CRITICAL) {
            eventBus.publish(errorEvent)
            return
        }

        // WARNING → 60초 dedup + 분당 10건 rate limit
        val dedupKey = "$tag:${throwable?.javaClass?.simpleName ?: "NoException"}"
        val now = System.currentTimeMillis()

        val lastPublished = dedupCache[dedupKey] ?: 0L
        if (now - lastPublished < 60_000L) return
        dedupCache[dedupKey] = now

        if (now - rateLimitWindowStart > 60_000L) {
            warningCountThisMinute.set(0)
            rateLimitWindowStart = now
        }
        if (warningCountThisMinute.incrementAndGet() > 10) return

        scope.launch {
            try {
                eventBus.publish(errorEvent)
            } catch (e: Exception) {
                XRealLogger.impl.w(this@ErrorReporter.tag, "EventBus publish failed: ${e.message}")
            }
        }
    }

    // ─── Companion: 기존 정적 호출 호환 (22개 파일 변경 불필요) ───────────────

    companion object {
        private fun instance(): ErrorReporter? = try {
            org.koin.java.KoinJavaComponent.getKoin().get()
        } catch (_: Exception) { null }

        /**
         * 기존 init() 호환. Koin 사용 시 불필요하지만, 기존 AppBootstrapper 호출은 유지.
         */
        fun init(bus: GlobalEventBus, dispatcher: CoroutineDispatcher = Dispatchers.IO) {
            // Koin에서 관리하므로 no-op. 기존 호출부 컴파일 에러 방지.
        }

        fun report(
            tag: String,
            message: String,
            throwable: Throwable? = null,
            severity: ErrorSeverity = ErrorSeverity.WARNING
        ) {
            instance()?.report(tag, message, throwable, severity)
        }
    }
}
