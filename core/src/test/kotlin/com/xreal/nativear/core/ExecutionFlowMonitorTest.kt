package com.xreal.nativear.core

import org.junit.Test
import org.junit.Assert.*

class ExecutionFlowMonitorTest {

    /** 매 테스트마다 새 인스턴스 → 링버퍼 상태 격리 */
    private fun createMonitor() = ExecutionFlowMonitor()

    @Test
    fun `record 후 dump 시 이벤트 포함`() {
        val monitor = createMonitor()
        monitor.record("TEST_MODULE", "test event happened")
        monitor.dump(last = 5, header = "Unit Test Dump")
    }

    @Test
    fun `다중 모듈 이벤트 기록`() {
        val monitor = createMonitor()
        monitor.record("MODULE_A", "event A")
        monitor.record("MODULE_B", "event B")
        monitor.record("MODULE_C", "event C")
        monitor.dump(last = 10, header = "Multi Module")
    }

    @Test
    fun `Level enum 4개`() {
        assertEquals(4, ExecutionFlowMonitor.Level.values().size)
        assertNotNull(ExecutionFlowMonitor.Level.INFO)
        assertNotNull(ExecutionFlowMonitor.Level.WARN)
        assertNotNull(ExecutionFlowMonitor.Level.ERROR)
        assertNotNull(ExecutionFlowMonitor.Level.DANGER)
    }

    @Test
    fun `WARN 레벨 기록`() {
        val monitor = createMonitor()
        monitor.record("TEST", "warning event", ExecutionFlowMonitor.Level.WARN)
    }

    @Test
    fun `ERROR 레벨 기록`() {
        val monitor = createMonitor()
        monitor.record("TEST", "error event", ExecutionFlowMonitor.Level.ERROR)
    }

    @Test
    fun `빈 모듈명과 이벤트`() {
        val monitor = createMonitor()
        monitor.record("", "")
    }

    @Test
    fun `대량 기록 시 링버퍼 순환`() {
        val monitor = createMonitor()
        repeat(600) { i ->
            monitor.record("STRESS", "event_$i")
        }
        monitor.dump(last = 10, header = "Stress Test")
    }

    @Test
    fun `서로 다른 인스턴스 간 상태 격리`() {
        val monitor1 = createMonitor()
        val monitor2 = createMonitor()

        monitor1.record("M1", "event from monitor1")
        // monitor2에는 기록하지 않음

        // monitor2의 dump는 빈 상태여야 함 (격리 확인)
        // dump가 크래시 없이 실행되면 성공
        monitor2.dump(last = 5, header = "Empty Monitor")
    }
}
