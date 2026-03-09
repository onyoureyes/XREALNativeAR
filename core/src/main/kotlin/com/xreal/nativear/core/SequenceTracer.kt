package com.xreal.nativear.core

import android.util.Log

/**
 * SequenceTracer — 실행 시퀀스 추적 로그 (켜고 끄기 가능).
 *
 * ## 사용법
 * ```
 * // 켜기:
 * adb shell setprop log.tag.SEQ VERBOSE
 *
 * // 끄기:
 * adb shell setprop log.tag.SEQ SUPPRESS
 *
 * // 실시간 로그 보기:
 * adb logcat -s SEQ
 * ```
 *
 * ## 로그 형식
 * `SEQ: [Module] → Event (detail)`
 * 타임스탬프는 logcat이 자동 추가하므로 생략.
 *
 * ## 주요 추적 지점
 * - EventBus: 모든 이벤트 publish/collect
 * - VisionCoordinator: 스냅샷 트리거 → AIAgentManager
 * - AIAgentManager: interpretScene → Gemini → EdgeAI 폴백
 * - EdgeLLMProvider: Mutex 획득 → 추론 → 완료
 */
object SequenceTracer {

    const val TAG = "SEQ"

    /** adb shell setprop log.tag.SEQ VERBOSE 로 활성화 */
    fun isEnabled(): Boolean = Log.isLoggable(TAG, Log.VERBOSE)

    /**
     * 시퀀스 추적 로그 출력.
     * logcat SEQ 태그 + ExecutionFlowMonitor ring buffer에 동시 기록.
     * @param module 모듈명 (예: "EventBus", "AIAgent", "EdgeLLM")
     * @param event  이벤트 설명 (예: "→ interpretScene(640x480)")
     */
    fun log(module: String, event: String) {
        // SEQ logcat (opt-in)
        if (isEnabled()) Log.v(TAG, "[$module] $event")

        // ExecutionFlowMonitor ring buffer (항상 기록 — 크래시 덤프용)
        val level = when {
            "에러" in event || "Error" in event || "실패" in event -> ExecutionFlowMonitor.Level.ERROR
            "WARN" in event || "위험" in event || "ChildCancelled" in event -> ExecutionFlowMonitor.Level.WARN
            "CRASH" in event || "FATAL" in event -> ExecutionFlowMonitor.Level.DANGER
            else -> ExecutionFlowMonitor.Level.INFO
        }
        ExecutionFlowMonitor.record(module, event, level)
    }

    // ── 편의 메서드 (모듈별 prefix 고정) ─────────────────────────────────────

    fun bus(event: String)      = log("EventBus", event)
    fun vision(event: String)   = log("Vision",   event)
    fun aiAgent(event: String)  = log("AIAgent",  event)
    fun edgeLLM(event: String)  = log("EdgeLLM",  event)
    fun input(event: String)    = log("Input",    event)
    fun output(event: String)   = log("Output",   event)
    fun hardware(event: String) = log("Hardware", event)

    /** 수동 FlowMonitor 덤프 (디버그 용) */
    fun dumpFlow(last: Int = 40) = ExecutionFlowMonitor.dump(last, "수동 덤프")
}
