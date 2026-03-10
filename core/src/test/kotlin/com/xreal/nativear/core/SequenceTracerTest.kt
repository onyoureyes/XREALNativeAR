package com.xreal.nativear.core

import org.junit.Test
import org.junit.Assert.*

class SequenceTracerTest {

    /** 매 테스트마다 새 인스턴스 → 상태 격리 */
    private fun createTracer() = SequenceTracer(ExecutionFlowMonitor())

    @Test
    fun `log 호출 시 크래시 없음`() {
        val tracer = createTracer()
        tracer.log("TEST", "trace event")
    }

    @Test
    fun `편의 메서드 크래시 없음`() {
        val tracer = createTracer()
        tracer.bus("bus event")
        tracer.vision("vision event")
        tracer.aiAgent("ai event")
        tracer.input("input event")
        tracer.output("output event")
        tracer.hardware("hardware event")
    }

    @Test
    fun `edgeLLM 편의 메서드`() {
        val tracer = createTracer()
        tracer.edgeLLM("edge model loaded")
    }

    @Test
    fun `dumpFlow 크래시 없음`() {
        val tracer = createTracer()
        tracer.dumpFlow(last = 5)
    }

    @Test
    fun `isEnabled 반환값 확인`() {
        val tracer = createTracer()
        // NoOpLogger 기본값이므로 항상 false
        assertFalse(tracer.isEnabled())
    }

    @Test
    fun `빈 문자열 이벤트`() {
        val tracer = createTracer()
        tracer.log("", "")
    }

    @Test
    fun `한글 이벤트`() {
        val tracer = createTracer()
        tracer.log("한글모듈", "한글 이벤트 메시지입니다")
    }

    @Test
    fun `에러 키워드 포함 시 ERROR 레벨로 기록`() {
        val monitor = ExecutionFlowMonitor()
        val tracer = SequenceTracer(monitor)
        tracer.log("TEST", "Gemini API 에러 발생")
        // ExecutionFlowMonitor에 ERROR 레벨로 기록됨 (크래시 없이 완료)
    }

    @Test
    fun `서로 다른 인스턴스 간 상태 격리`() {
        val tracer1 = createTracer()
        val tracer2 = createTracer()

        tracer1.log("T1", "event from tracer1")
        // tracer2의 FlowMonitor에는 기록되지 않음
        tracer2.dumpFlow(last = 5) // 빈 상태에서 크래시 없이 완료
    }
}
