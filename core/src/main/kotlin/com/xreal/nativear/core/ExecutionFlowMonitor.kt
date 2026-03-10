package com.xreal.nativear.core

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
 * ## 인스턴스 관리
 * Koin `single {}` 로 등록. 기존 정적 호출은 companion object가 위임.
 * 테스트에서는 `ExecutionFlowMonitor()` 로 새 인스턴스 생성 → 상태 격리.
 */
class ExecutionFlowMonitor {

    private val tag = "FlowMon"
    private val capacity = 500
    private val patternWindow = 10

    enum class Level { INFO, WARN, ERROR, DANGER }

    data class FlowEvent(
        val absMs: Long,
        val threadId: Long,
        val threadName: String,
        val module: String,
        val event: String,
        val level: Level = Level.INFO
    )

    // ─── Ring Buffer (Lock-Free) ──────────────────────────────────────────────
    private val buffer = arrayOfNulls<FlowEvent>(capacity)
    private val writeIdx = AtomicInteger(0)
    private val startMs = AtomicLong(System.currentTimeMillis())

    @Volatile private var recentSnapshot = emptyArray<FlowEvent>()
    private val snapshotLock = Any()

    // ─── Public API ───────────────────────────────────────────────────────────

    fun record(module: String, event: String, level: Level = Level.INFO) {
        val e = FlowEvent(
            absMs      = System.currentTimeMillis(),
            threadId   = Thread.currentThread().id,
            threadName = Thread.currentThread().name.take(10),
            module     = module.take(8),
            event      = event
        )
        val idx = writeIdx.getAndIncrement() % capacity
        buffer[idx] = e
        updateSnapshotAndCheck(e)
    }

    fun installCrashHandler() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val label = "CRASH — ${throwable.javaClass.simpleName} on ${thread.name}"
                record("CRASH", label, Level.DANGER)
                dump(last = 50, header = label)
            } catch (_: Exception) {}
            original?.uncaughtException(thread, throwable)
        }
        XRealLogger.impl.i(tag, "Crash handler installed — crashes will dump FlowMonitor timeline")
    }

    fun dump(last: Int = 40, header: String = "FLOW DUMP") {
        val events = collectSorted().takeLast(last.coerceIn(1, capacity))
        if (events.isEmpty()) {
            XRealLogger.impl.w(tag, "[$header] 기록된 이벤트 없음")
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

        val text = sb.toString()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + 3800, text.length)
            XRealLogger.impl.e(tag, text.substring(start, end))
            start = end
        }
    }

    // ─── 내부 구현 ─────────────────────────────────────────────────────────────

    private fun collectSorted(): List<FlowEvent> =
        buffer.filterNotNull().sortedBy { it.absMs }

    private fun updateSnapshotAndCheck(newEvent: FlowEvent) {
        synchronized(snapshotLock) {
            val prev = recentSnapshot
            val next = if (prev.size >= patternWindow)
                prev.drop(1).toTypedArray() + newEvent
            else
                prev + newEvent
            recentSnapshot = next
            checkPatterns(next, newEvent)
        }
    }

    private fun checkPatterns(window: Array<FlowEvent>, latest: FlowEvent) {
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

        if (latest.event.contains("Mutex 대기") && latest.level == Level.INFO) {
            val sameThreadMutexWaits = window.count {
                it.threadId == latest.threadId && "Mutex 대기" in it.event
            }
            if (sameThreadMutexWaits >= 2) {
                record("FlowMon", "PATTERN-2: 동일 스레드 Mutex 2회 대기 — 데드락 가능성", Level.WARN)
            }
        }

        val geminiErrors = window.count { "Gemini 에러" in it.event || "Gemini Error" in it.event }
        if (geminiErrors >= 3) {
            if (latest.event.contains("Gemini 에러") || latest.event.contains("Gemini Error")) {
                record("FlowMon", "PATTERN-3: Gemini ${geminiErrors}회 연속 에러 — API 상태 확인 필요", Level.WARN)
            }
        }
    }

    // ─── Companion: 기존 정적 호출 호환 (Koin 인스턴스에 위임) ─────────────────

    companion object {
        /** 매 호출마다 Koin 조회. Koin 미초기화 시 no-op. lazy 캐싱 금지. */
        private fun instance(): ExecutionFlowMonitor? = try {
            org.koin.java.KoinJavaComponent.getKoin().get()
        } catch (_: Exception) { null }

        fun record(module: String, event: String, level: Level = Level.INFO) {
            instance()?.record(module, event, level)
        }

        fun installCrashHandler() { instance()?.installCrashHandler() }

        fun dump(last: Int = 40, header: String = "FLOW DUMP") {
            instance()?.dump(last, header)
        }
    }
}
