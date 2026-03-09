package com.xreal.nativear.tools

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * ToolUsageTracker — 도구 호출 통계 추적기.
 *
 * ## 역할 (Phase M)
 * - 페르소나별·도구별 호출 횟수·성공률·평균 레이턴시 수집
 * - StrategistService가 5분 주기 반영 컨텍스트에 포함
 *   → Gemini가 "어떤 도구가 잘 쓰이고, 어떤 도구가 실패하는지" 파악해 지시사항 생성 가능
 * - Koin single{} 싱글톤
 */
class ToolUsageTracker {
    private val TAG = "ToolUsageTracker"

    data class ToolStats(
        val callCount: AtomicLong = AtomicLong(0),
        val successCount: AtomicLong = AtomicLong(0),
        val totalLatencyMs: AtomicLong = AtomicLong(0)
    )

    // personaId → toolName → stats
    private val stats = ConcurrentHashMap<String, ConcurrentHashMap<String, ToolStats>>()

    /**
     * 도구 호출 1회 기록.
     * @param toolName 호출된 도구 이름
     * @param personaId 호출한 페르소나 ID (모를 경우 "unknown")
     * @param success 도구 실행 성공 여부
     * @param latencyMs 실행 소요 시간 (ms)
     */
    fun record(toolName: String, personaId: String, success: Boolean, latencyMs: Long) {
        val personaStats = stats.getOrPut(personaId) { ConcurrentHashMap() }
        val toolStat = personaStats.getOrPut(toolName) { ToolStats() }
        toolStat.callCount.incrementAndGet()
        if (success) toolStat.successCount.incrementAndGet()
        toolStat.totalLatencyMs.addAndGet(latencyMs)
    }

    /**
     * StrategistService 반영용 통계 요약.
     * 호출 횟수 상위 10개 도구 + 페르소나별 최다 사용 도구 포함.
     */
    fun getSummary(): String {
        if (stats.isEmpty()) return ""

        return buildString {
            appendLine("[도구 사용 통계]")

            // 전체 도구 집계 (personaId 무관)
            val allToolStats = mutableMapOf<String, Triple<Long, Long, Long>>()  // name → (calls, success, latency)
            for ((_, personaStats) in stats) {
                for ((toolName, toolStat) in personaStats) {
                    val current = allToolStats.getOrDefault(toolName, Triple(0L, 0L, 0L))
                    allToolStats[toolName] = Triple(
                        current.first + toolStat.callCount.get(),
                        current.second + toolStat.successCount.get(),
                        current.third + toolStat.totalLatencyMs.get()
                    )
                }
            }

            // 상위 10개 (호출 횟수 기준)
            allToolStats.entries.sortedByDescending { it.value.first }.take(10).forEach { (tool, triple) ->
                val (calls, success, latency) = triple
                val successRate = if (calls > 0) (success * 100 / calls) else 0
                val avgLatency = if (calls > 0) (latency / calls) else 0
                appendLine("- $tool: ${calls}회 호출, 성공률 ${successRate}%, 평균 ${avgLatency}ms")
            }

            // 페르소나별 요약
            appendLine("[페르소나별 도구 사용]")
            for ((personaId, personaStats) in stats) {
                val total = personaStats.values.sumOf { it.callCount.get() }
                if (total > 0) {
                    val topTool = personaStats.maxByOrNull { it.value.callCount.get() }?.key ?: "없음"
                    appendLine("- $personaId: ${total}회, 최다 도구=$topTool")
                }
            }
        }
    }

    // ★ Phase N: ToolHealthMonitor용 구조화 통계

    data class ToolStatsSnapshot(
        val toolName: String,
        val callCount: Long,
        val successCount: Long,
        val totalLatencyMs: Long
    ) {
        val failureRate: Float
            get() = if (callCount > 0) 1f - (successCount.toFloat() / callCount) else 0f
        val avgLatencyMs: Long
            get() = if (callCount > 0) totalLatencyMs / callCount else 0L
    }

    /**
     * ToolHealthMonitor용 구조화 통계 반환.
     * personaId 무관하게 도구 단위로 집계.
     */
    fun getStatsSnapshot(): List<ToolStatsSnapshot> {
        val allToolStats = mutableMapOf<String, Triple<Long, Long, Long>>()
        for ((_, personaStats) in stats) {
            for ((toolName, toolStat) in personaStats) {
                val current = allToolStats.getOrDefault(toolName, Triple(0L, 0L, 0L))
                allToolStats[toolName] = Triple(
                    current.first + toolStat.callCount.get(),
                    current.second + toolStat.successCount.get(),
                    current.third + toolStat.totalLatencyMs.get()
                )
            }
        }
        return allToolStats.map { (name, triple) ->
            ToolStatsSnapshot(name, triple.first, triple.second, triple.third)
        }
    }

    /** 통계 초기화 (테스트/디버그용) */
    fun reset() {
        stats.clear()
        Log.d(TAG, "Tool usage stats reset")
    }
}
