package com.xreal.nativear.tools

import android.util.Log
import com.xreal.nativear.ai.AIToolDefinition
import java.util.concurrent.ConcurrentHashMap

/**
 * ToolHealthMonitor — 실패율 기반 도구 자동 격리.
 *
 * ## 역할 (Phase N)
 * - ToolUsageTracker.getStatsSnapshot() 데이터 분석
 * - 실패율 >= 50% & 호출횟수 >= 5회 → 15분 격리 (quarantine)
 * - MultiAIOrchestrator.callPersona()에서 격리된 도구 제외
 * - ESSENTIAL_TOOLS는 격리 면제 (메모리/위치 등 핵심 도구)
 * - Koin single{} 싱글톤
 */
class ToolHealthMonitor(
    private val toolUsageTracker: ToolUsageTracker
) {
    private val TAG = "ToolHealthMonitor"

    // quarantinedTools: toolName → quarantineUntil (epoch ms)
    private val quarantinedTools = ConcurrentHashMap<String, Long>()

    companion object {
        const val FAILURE_RATE_THRESHOLD = 0.5f        // 50% 이상 실패 시 격리
        const val MIN_CALLS_FOR_QUARANTINE = 5          // 최소 5회 호출 이후 판단
        const val QUARANTINE_DURATION_MS = 15 * 60 * 1000L  // 15분

        // 격리 면제 핵심 도구 (실패율 무관하게 항상 전달)
        val ESSENTIAL_TOOLS = setOf(
            "query_keyword_memory", "save_memory", "get_screen_objects", "get_current_location",
            "save_structured_data", "query_structured_data"
        )

        // CAREFUL 예산 시 전달 가능한 최대 도구 수
        const val MAX_CAREFUL_TOOLS = 5
    }

    /**
     * 건강한 도구만 필터링하여 반환.
     * 격리 상태를 최신화한 후 quarantined 도구 제외.
     */
    fun filterHealthyTools(tools: List<AIToolDefinition>): List<AIToolDefinition> {
        refreshQuarantineStatus()
        return tools.filter { tool ->
            val quarantined = isQuarantined(tool.name)
            if (quarantined) Log.d(TAG, "Tool ${tool.name} excluded (quarantined)")
            !quarantined
        }
    }

    /**
     * ToolUsageTracker 통계 기반으로 격리 상태 갱신.
     * 실패율 >= FAILURE_RATE_THRESHOLD && callCount >= MIN_CALLS_FOR_QUARANTINE → 격리
     */
    private fun refreshQuarantineStatus() {
        for (stat in toolUsageTracker.getStatsSnapshot()) {
            if (stat.toolName in ESSENTIAL_TOOLS) continue  // 핵심 도구 면제
            if (stat.callCount < MIN_CALLS_FOR_QUARANTINE) continue  // 데이터 부족
            if (stat.failureRate >= FAILURE_RATE_THRESHOLD && !isCurrentlyQuarantined(stat.toolName)) {
                quarantinedTools[stat.toolName] = System.currentTimeMillis() + QUARANTINE_DURATION_MS
                Log.w(TAG, "🚫 Tool ${stat.toolName} quarantined: " +
                    "failure=${(stat.failureRate * 100).toInt()}%, calls=${stat.callCount}")
            }
        }
    }

    private fun isQuarantined(toolName: String): Boolean {
        val until = quarantinedTools[toolName] ?: return false
        return if (System.currentTimeMillis() < until) {
            true
        } else {
            quarantinedTools.remove(toolName)
            Log.i(TAG, "✅ Tool $toolName quarantine lifted")
            false
        }
    }

    private fun isCurrentlyQuarantined(toolName: String): Boolean {
        val until = quarantinedTools[toolName] ?: return false
        return System.currentTimeMillis() < until
    }

    /** 디버그용 건강 보고서 */
    fun getHealthReport(): String {
        val now = System.currentTimeMillis()
        val active = quarantinedTools.filter { it.value > now }
        if (active.isEmpty()) return "All tools healthy"
        return buildString {
            appendLine("[격리 중인 도구]")
            active.forEach { (tool, until) ->
                appendLine("- $tool: ${(until - now) / 60_000}분 남음")
            }
        }.trimEnd()
    }
}
