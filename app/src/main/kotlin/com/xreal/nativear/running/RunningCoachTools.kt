package com.xreal.nativear.running

import com.xreal.nativear.ai.AIToolDefinition

/**
 * 러닝 코치 전용 도구 정의.
 * ToolDefinitionRegistry에 이미 등록되어 있으므로 추가 등록 시에만 사용.
 */
object RunningCoachTools {

    val getRunningStats = AIToolDefinition(
        "get_running_stats",
        "Get current running session statistics including pace, distance, cadence, and form metrics.",
        """{"type":"object","properties":{}}"""
    )

    val controlRunningSession = AIToolDefinition(
        "control_running_session",
        "Control the running session: start, stop, pause, resume, or record a lap.",
        """{"type":"object","properties":{"action":{"type":"string","description":"Action: start, stop, pause, resume, lap"}},"required":["action"]}"""
    )

    val getRunningAdvice = AIToolDefinition(
        "get_running_advice",
        "Get AI coaching advice based on current running form and metrics.",
        """{"type":"object","properties":{}}"""
    )

    fun getAllRunningToolDefinitions(): List<AIToolDefinition> =
        listOf(getRunningStats, controlRunningSession, getRunningAdvice)
}
