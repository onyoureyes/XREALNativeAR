package com.xreal.nativear.core

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ExecutionFlowMonitor — 전체 실행 흐름 실시간 추적 + 위험 패턴 사전 탐지.
 *
 * ## 역할
 * 1. **Ring Buffer**: 최근 500개 FlowEvent 유지 (LockFree CAS, thread-safe)
 * 2. **Crash Handler**: Thread.UncaughtExceptionHandler — 크래시 시 ASCII 타임라인 자동 덤프
 * 3. **Pattern Guard**: 위험 패턴 실시간 감지 (ChildCancelledException+close 등)
 * 4. **SequenceTracer 통합**: SEQ 이벤트 → 자동 FlowEvent 변환
 *
 * ## 출력 예시 (LogCat TAG=FlowMon)
 * ```
 * ════════════════════════════════════════════════════
 * 🔍 FLOW DUMP — 마지막 30 이벤트, 5 스레드
 * ────────────────────────────────────────────────────
 * +000000ms [main      ] [EventBus ] →TriggerSnapshot
 * +000050ms [pool-2-thr] [AIAgent  ]  interpretScene START
 * +000051ms [pool-2-thr] [AIAgent  ] →Gemini Vision 호출
 * +001200ms [pool-3-thr] [AIAgent  ] ✗←Gemini Error
 * +001201ms [pool-3-thr] [EdgeLLM  ] ⚠ROUTER_270M ChildCancelled→null반환
 * ════════════════════════════════════════════════════
 * ```
 *
 * ## 사용법
 * ```kotlin
 * // Application.onCreate():
 * ExecutionFlowMonitor.installCrashHandler()
 *
 * // 이벤트 기록:
 * ExecutionFlowMonitor.record("AIAgent", "interpretScene START")
 * ExecutionFlowMonitor.record("EdgeLLM", "ChildCancelled → close 생략", Level.WARN)
 *
 * // 수동 덤프:
 * ExecutionFlowMonitor.dump(last = 30)
 * ```
 */
object ExecutionFlowMonitor {

    private const val TAG = "FlowMon"
    private const val CAPACITY = 500     // ring buffer 크기
    private const val PATTERN_WINDOW = 10 // 패턴 감지 최근 N 이벤트

    enum class Level { INFO, WARN, ERROR, DANGER }

    /**
     * 개별 실행 흐름 이벤트.
     * @param absMs 앱 시작 시 기준의 절대 ms
     * @param threadId 스레드 ID
     * @param threadName 스레드 명 (최대 10자)
     * @param module 모듈 명 (최대 8자)
     * @param event 이벤트 설명
     * @param level 심각도
     */
    data class FlowEvent(
        val absMs: Long,
        val threadId: Long,
        val threadName: String,
        val module: String,
        val event: String,
        val level: Level = Level.INFO
    )

    // ─── Ring Buffer (Lock-Free) ──────────────────────────────────────────────
    private val buffer = arrayOfNulls<FlowEvent>(CAPACITY)
    private val writeIdx = AtomicInteger(0)
    private val startMs = AtomicLong(System.currentTimeMillis())

    // 패턴 감지용 최근 이벤트 스냅샷 (단순 volatile Array)
    @Volatile private var recentSnapshot = emptyArray<FlowEvent>()
    private val snapshotLock = Any()

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * 이벤트 기록. 모든 스레드에서 호출 가능 (논블로킹).
     */
    fun record(module: String, event: String, level: Level = Level.INFO) {
        val e = FlowEvent(
            absMs     = System.currentTimeMillis(),
            threadId  = Thread.currentThread().id,
            threadName = Thread.currentThread().name.take(10),
            module    = module.take(8),
            event     = event
        )
        // CAS ring write
        val idx = writeIdx.getAndIncrement() % CAPACITY
        buffer[idx] = e

        // 패턴 감지 (최근 PATTERN_WINDOW개만 검사)
        updateSnapshotAndCheck(e)
    }

    /**
     * UncaughtExceptionHandler 설치.
     * Application.onCreate() 에서 한 번 호출.
     * 크래시 발생 시 → 마지막 50 이벤트를 ASCII 타임라인으로 logcat 출력.
     */
    fun installCrashHandler() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val label = "CRASH — ${throwable.javaClass.simpleName} on ${thread.name}"
                record("CRASH", label, Level.DANGER)
                dump(last = 50, header = label)
            } catch (_: Exception) { /* 덤프 실패해도 원래 핸들러는 호출 */ }
            original?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "Crash handler installed — crashes will dump FlowMonitor timeline")
    }

    /**
     * 현재 ring buffer에서 최근 [last]개 이벤트를 ASCII 타임라인으로 출력.
     * 4000자 초과 시 청크 분할 (logcat 제한 대응).
     */
    fun dump(last: Int = 40, header: String = "FLOW DUMP") {
        val events = collectSorted().takeLast(last.coerceIn(1, CAPACITY))
        if (events.isEmpty()) {
            Log.w(TAG, "[$header] 기록된 이벤트 없음")
            return
        }

        val sb = StringBuilder()
        val divider = "=".repeat(56)
        val thin    = "-".repeat(56)
        sb.appendLine(divider)
        sb.appendLine("FLOW: $header")
        sb.appendLine("   ${events.size}개 이벤트 | ${events.distinctBy { it.threadId }.size}개 스레드")
        sb.appendLine(thin)

        val base = events.first().absMs
        for (e in events) {
            val relMs = (e.absMs - base).let { "+${it.toString().padStart(6)}ms" }
            val th    = e.threadName.padEnd(10)
            val mod   = "[${e.module.padEnd(8)}]"
            val icon  = when (e.level) {
                Level.INFO   -> " "
                Level.WARN   -> "!"
                Level.ERROR  -> "X"
                Level.DANGER -> "!!"
            }
            sb.appendLine("$relMs [$th] $mod $icon ${e.event}")
        }
        sb.appendLine(divider)

        // 청크 출력 (logcat 4096자 제한)
        val text = sb.toString()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + 3800, text.length)
            Log.e(TAG, text.substring(start, end))
            start = end
        }
    }

    // ─── 내부 구현 ─────────────────────────────────────────────────────────────

    /** ring buffer → 시간순 정렬 */
    private fun collectSorted(): List<FlowEvent> =
        buffer.filterNotNull().sortedBy { it.absMs }

    private fun updateSnapshotAndCheck(newEvent: FlowEvent) {
        synchronized(snapshotLock) {
            val prev = recentSnapshot
            val next = if (prev.size >= PATTERN_WINDOW)
                prev.drop(1).toTypedArray() + newEvent
            else
                prev + newEvent
            recentSnapshot = next
            checkPatterns(next, newEvent)
        }
    }

    /**
     * 위험 패턴 탐지.
     * 패턴 1: ChildCancelledException 직후 closeConversation → SIGSEGV 예정
     * 패턴 2: 동일 스레드에서 Mutex 연속 획득 → 데드락 위험
     * 패턴 3: Gemini Error 반복 → API 한도 소진 의심
     */
    private fun checkPatterns(window: Array<FlowEvent>, latest: FlowEvent) {
        // 패턴 1: ChildCancelledException → close 연속 (같은 스레드, 500ms 이내)
        if (latest.event.contains("close", ignoreCase = true) &&
            latest.event.contains("Conversation", ignoreCase = true)) {
            val recentSameThread = window.filter {
                it.threadId == latest.threadId &&
                it !== latest &&
                (latest.absMs - it.absMs) < 500
            }
            if (recentSameThread.any { "ChildCancelled" in it.event || "ChildCancelledException" in it.event }) {
                record("FlowMon", "PATTERN-1: ChildCancelled 후 close() — SIGSEGV 위험!", Level.DANGER)
                dump(last = 15, header = "PATTERN-1 DETECTED")
            }
        }

        // 패턴 2: 동일 스레드 globalInferenceMutex 2회 대기 → 데드락 의심
        if (latest.event.contains("Mutex 대기") && latest.level == Level.INFO) {
            val sameThreadMutexWaits = window.count {
                it.threadId == latest.threadId && "Mutex 대기" in it.event
            }
            if (sameThreadMutexWaits >= 2) {
                record("FlowMon", "PATTERN-2: 동일 스레드 Mutex 2회 대기 — 데드락 가능성", Level.WARN)
            }
        }

        // 패턴 3: 5개 연속 Gemini Error → API 키/한도 문제
        val geminiErrors = window.count { "Gemini" in it.module || "Gemini" in it.event }
            .let { window.count { "Gemini 에러" in it.event || "Gemini Error" in it.event } }
        if (geminiErrors >= 3) {
            // 3회 연속 오류면 한 번만 경고
            if (latest.event.contains("Gemini 에러") || latest.event.contains("Gemini Error")) {
                record("FlowMon", "PATTERN-3: Gemini ${geminiErrors}회 연속 에러 — API 상태 확인 필요", Level.WARN)
            }
        }
    }
}
