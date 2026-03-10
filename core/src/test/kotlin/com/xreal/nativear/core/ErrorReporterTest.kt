package com.xreal.nativear.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ErrorReporterTest {

    /** 매 테스트마다 새 인스턴스 → 상태 격리 보장 */
    private fun createReporter(): Pair<ErrorReporter, GlobalEventBus> {
        val bus = GlobalEventBus()
        val reporter = ErrorReporter(bus, UnconfinedTestDispatcher())
        return reporter to bus
    }

    @Test
    fun `CRITICAL 에러는 동기적으로 EventBus로 전파`() = runTest {
        val (reporter, bus) = createReporter()
        val received = mutableListOf<XRealEvent>()
        val job = launch { bus.events.collect { received.add(it) } }
        advanceUntilIdle()

        reporter.report(
            tag = "TEST",
            message = "critical error",
            severity = ErrorSeverity.CRITICAL
        )
        advanceUntilIdle()

        val errors = received.filterIsInstance<XRealEvent.SystemEvent.Error>()
        assertTrue("CRITICAL 에러가 EventBus에 전파되어야 함", errors.isNotEmpty())
        assertEquals("critical error", errors[0].message)
        assertEquals(ErrorSeverity.CRITICAL, errors[0].severity)
        job.cancel()
    }

    @Test
    fun `WARNING 에러 dedup - 같은 에러 60초 내 1번만 전파`() = runTest {
        val (reporter, bus) = createReporter()
        val received = mutableListOf<XRealEvent>()
        val job = launch { bus.events.collect { received.add(it) } }
        advanceUntilIdle()

        val exception = RuntimeException("same error")
        reporter.report("TEST", "dup test", exception, ErrorSeverity.WARNING)
        reporter.report("TEST", "dup test", exception, ErrorSeverity.WARNING)
        advanceUntilIdle()

        val errors = received.filterIsInstance<XRealEvent.SystemEvent.Error>()
        assertEquals("WARNING dedup: 60초 내 동일 에러는 1번만 전파", 1, errors.size)
        job.cancel()
    }

    @Test
    fun `INFO 에러는 EventBus에 전파하지 않음`() = runTest {
        val (reporter, bus) = createReporter()
        val received = mutableListOf<XRealEvent>()
        val job = launch { bus.events.collect { received.add(it) } }
        advanceUntilIdle()

        reporter.report(
            tag = "TEST",
            message = "info only",
            severity = ErrorSeverity.INFO
        )
        advanceUntilIdle()

        val errors = received.filterIsInstance<XRealEvent.SystemEvent.Error>()
        assertEquals("INFO는 EventBus에 전파하면 안 됨", 0, errors.size)
        job.cancel()
    }

    @Test
    fun `throwable 없이 보고 가능`() = runTest {
        val (reporter, bus) = createReporter()
        val received = mutableListOf<XRealEvent>()
        val job = launch { bus.events.collect { received.add(it) } }
        advanceUntilIdle()

        reporter.report("TEST", "no throwable", severity = ErrorSeverity.CRITICAL)
        advanceUntilIdle()

        val errors = received.filterIsInstance<XRealEvent.SystemEvent.Error>()
        assertTrue(errors.isNotEmpty())
        assertNull(errors[0].throwable)
        job.cancel()
    }

    @Test
    fun `CRITICAL은 throwable 메시지를 포함한 fullMessage 생성`() = runTest {
        val (reporter, bus) = createReporter()
        val received = mutableListOf<XRealEvent>()
        val job = launch { bus.events.collect { received.add(it) } }
        advanceUntilIdle()

        reporter.report("TEST", "DB 저장 실패", RuntimeException("disk full"), ErrorSeverity.CRITICAL)
        advanceUntilIdle()

        val errors = received.filterIsInstance<XRealEvent.SystemEvent.Error>()
        assertEquals("DB 저장 실패: disk full", errors[0].message)
        job.cancel()
    }

    @Test
    fun `서로 다른 인스턴스 간 상태 격리`() = runTest {
        val (reporter1, bus1) = createReporter()
        val (reporter2, bus2) = createReporter()
        val received1 = mutableListOf<XRealEvent>()
        val received2 = mutableListOf<XRealEvent>()

        val job1 = launch { bus1.events.collect { received1.add(it) } }
        val job2 = launch { bus2.events.collect { received2.add(it) } }
        advanceUntilIdle()

        // reporter1에만 WARNING 발행
        reporter1.report("TEST", "warning A", severity = ErrorSeverity.WARNING)
        advanceUntilIdle()

        // reporter2의 dedup 캐시는 비어있으므로 같은 메시지도 통과해야 함
        reporter2.report("TEST", "warning A", severity = ErrorSeverity.WARNING)
        advanceUntilIdle()

        val errors1 = received1.filterIsInstance<XRealEvent.SystemEvent.Error>()
        val errors2 = received2.filterIsInstance<XRealEvent.SystemEvent.Error>()
        assertEquals("reporter1에 1개", 1, errors1.size)
        assertEquals("reporter2에도 1개 (격리)", 1, errors2.size)
        job1.cancel()
        job2.cancel()
    }
}
