package com.xreal.nativear.core

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ErrorReporter v2 — 심각도 기반 에러 트리아지 (Phase G).
 *
 * ## 심각도 3단계
 * | 심각도 | 동작 |
 * |--------|------|
 * | CRITICAL | logcat(e) + EventBus 즉시 발행 (dedup 없음) |
 * | WARNING  | logcat(w) + 60초 dedup + 분당 10건 제한 후 EventBus 발행 |
 * | INFO     | logcat(i) only — EventBus 발행 없음 |
 *
 * ## 문제 배경 (Phase G)
 * ErrorReporter 커버리지 28%→41% 확장 시, WARNING 급 에러까지 모두
 * EmergencyOrchestrator AI 트리아지로 전달되면 신호/잡음비 붕괴.
 * → CRITICAL만 AI가 처리, WARNING은 통계 집계로 격하.
 *
 * ## dedup 키 설계
 * "TAG:ExceptionSimpleName" — 동적 메시지 문자열 제외로 dedup 정확도 보장.
 *
 * ## 사용 예시
 * ```kotlin
 * // CRITICAL (즉시 AI 트리아지):
 * ErrorReporter.report(TAG, "메모리 저장 실패", e, ErrorSeverity.CRITICAL)
 *
 * // WARNING (dedup/rate-limit 후 EventBus):
 * ErrorReporter.report(TAG, "API 호출 실패", e)  // 기본값 WARNING
 *
 * // INFO (logcat만):
 * ErrorReporter.report(TAG, "GPS 정확도 낮음", severity = ErrorSeverity.INFO)
 * ```
 */
object ErrorReporter {
    private const val TAG = "ErrorReporter"
    private var eventBus: GlobalEventBus? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // WARNING dedup: key="TAG:ExceptionSimpleName", value=lastPublishedMs
    private val dedupCache = ConcurrentHashMap<String, Long>()

    // WARNING rate limit: 분당 10건
    private val warningCountThisMinute = AtomicInteger(0)
    @Volatile private var rateLimitWindowStart = System.currentTimeMillis()

    /**
     * AppBootstrapper.start() 에서 1회 호출.
     */
    fun init(bus: GlobalEventBus) {
        eventBus = bus
        Log.i(TAG, "ErrorReporter v2 initialized (CRITICAL/WARNING/INFO 트리아지 활성)")
    }

    /**
     * 에러 보고.
     *
     * @param tag      로그캣 태그 (호출 클래스명, EmergencyOrchestrator rerouteRules 키로 사용 가능)
     * @param message  에러 설명
     * @param throwable 원인 예외 (nullable)
     * @param severity 심각도 — 기본값 WARNING
     */
    fun report(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        severity: ErrorSeverity = ErrorSeverity.WARNING
    ) {
        // 1. 항상 logcat 출력
        when (severity) {
            ErrorSeverity.CRITICAL -> Log.e(tag, message, throwable)
            ErrorSeverity.WARNING  -> Log.w(tag, message, throwable)
            ErrorSeverity.INFO     -> Log.i(tag, message)
        }

        // INFO → logcat만, EventBus 발행 없음
        if (severity == ErrorSeverity.INFO) return

        // CRITICAL → 즉시 발행 (dedup/rate limit 없음)
        if (severity == ErrorSeverity.CRITICAL) {
            publishEvent(tag, message, throwable, ErrorSeverity.CRITICAL)
            return
        }

        // WARNING → 60초 dedup + 분당 10건 rate limit
        val dedupKey = "$tag:${throwable?.javaClass?.simpleName ?: "NoException"}"
        val now = System.currentTimeMillis()

        // 60초 내 동일 키 중복 → 스킵
        val lastPublished = dedupCache[dedupKey] ?: 0L
        if (now - lastPublished < 60_000L) return
        dedupCache[dedupKey] = now

        // 분당 10건 초과 → 스킵
        if (now - rateLimitWindowStart > 60_000L) {
            warningCountThisMinute.set(0)
            rateLimitWindowStart = now
        }
        if (warningCountThisMinute.incrementAndGet() > 10) return

        publishEvent(tag, message, throwable, ErrorSeverity.WARNING)
    }

    private fun publishEvent(
        tag: String,
        message: String,
        throwable: Throwable?,
        severity: ErrorSeverity
    ) {
        scope.launch {
            try {
                val fullMessage = if (throwable?.message != null) "$message: ${throwable.message}" else message
                eventBus?.publish(
                    XRealEvent.SystemEvent.Error(
                        code = tag,
                        message = fullMessage,
                        throwable = throwable,
                        severity = severity
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "EventBus publish failed: ${e.message}")
            }
        }
    }
}
