package com.xreal.nativear.core

/**
 * SequenceTracer — 실행 시퀀스 추적 로그 (켜고 끄기 가능).
 *
 * ExecutionFlowMonitor의 facade. logcat opt-in + 자동 레벨 감지 추가.
 *
 * ## 사용법
 * ```
 * adb shell setprop log.tag.SEQ VERBOSE   // 켜기
 * adb shell setprop log.tag.SEQ SUPPRESS  // 끄기
 * adb logcat -s SEQ                       // 실시간 보기
 * ```
 *
 * ## 인스턴스 관리
 * Koin `single {}` 로 등록. 기존 정적 호출은 companion object가 위임.
 */
class SequenceTracer(
    private val flowMonitor: ExecutionFlowMonitor
) {

    val tag = "SEQ"

    fun isEnabled(): Boolean = XRealLogger.impl.isLoggable(tag, XRealLogger.VERBOSE)

    fun log(module: String, event: String) {
        if (isEnabled()) XRealLogger.impl.v(tag, "[$module] $event")

        val level = when {
            "에러" in event || "Error" in event || "실패" in event -> ExecutionFlowMonitor.Level.ERROR
            "WARN" in event || "위험" in event || "ChildCancelled" in event -> ExecutionFlowMonitor.Level.WARN
            "CRASH" in event || "FATAL" in event -> ExecutionFlowMonitor.Level.DANGER
            else -> ExecutionFlowMonitor.Level.INFO
        }
        flowMonitor.record(module, event, level)
    }

    fun bus(event: String)      = log("EventBus", event)
    fun vision(event: String)   = log("Vision",   event)
    fun aiAgent(event: String)  = log("AIAgent",  event)
    fun edgeLLM(event: String)  = log("EdgeLLM",  event)
    fun input(event: String)    = log("Input",    event)
    fun output(event: String)   = log("Output",   event)
    fun hardware(event: String) = log("Hardware", event)

    fun dumpFlow(last: Int = 40) = flowMonitor.dump(last, "수동 덤프")

    // ─── Companion: 기존 정적 호출 호환 ──────────────────────────────────────

    companion object {
        private fun instance(): SequenceTracer? = try {
            org.koin.java.KoinJavaComponent.getKoin().get()
        } catch (_: Exception) { null }

        fun log(module: String, event: String) { instance()?.log(module, event) }
        fun isEnabled(): Boolean = instance()?.isEnabled() ?: false
        fun bus(event: String) { instance()?.bus(event) }
        fun vision(event: String) { instance()?.vision(event) }
        fun aiAgent(event: String) { instance()?.aiAgent(event) }
        fun edgeLLM(event: String) { instance()?.edgeLLM(event) }
        fun input(event: String) { instance()?.input(event) }
        fun output(event: String) { instance()?.output(event) }
        fun hardware(event: String) { instance()?.hardware(event) }
        fun dumpFlow(last: Int = 40) { instance()?.dumpFlow(last) }
    }
}
