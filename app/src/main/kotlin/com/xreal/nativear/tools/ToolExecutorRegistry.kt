package com.xreal.nativear.tools

import android.util.Log
import com.xreal.nativear.core.ErrorReporter

class ToolExecutorRegistry(
    // ★ Phase M: 도구 사용 통계 추적기 (선택적, 기존 코드 영향 없음)
    private val toolUsageTracker: ToolUsageTracker? = null
) {
    private val TAG = "ToolExecutorRegistry"
    private val executors = mutableMapOf<String, IToolExecutor>()

    fun register(executor: IToolExecutor) {
        executor.supportedTools.forEach { toolName ->
            executors[toolName] = executor
        }
        Log.d(TAG, "Registered executor for: ${executor.supportedTools}")
    }

    // ★ Phase M: personaId 파라미터 추가 (default="unknown"으로 하위 호환 유지)
    suspend fun execute(name: String, args: Map<String, Any?>, personaId: String = "unknown"): ToolResult {
        val executor = executors[name]
            ?: return ToolResult(false, "Unknown tool: $name")
        return try {
            val startTime = System.currentTimeMillis()
            val result = executor.execute(name, args)
            val latency = System.currentTimeMillis() - startTime
            // ★ Phase M: 도구 호출 통계 기록 (성공 시)
            toolUsageTracker?.record(name, personaId, result.success, latency)
            result
        } catch (e: Exception) {
            // ★ Phase M: 도구 호출 통계 기록 (실패 시)
            toolUsageTracker?.record(name, personaId, false, 0L)
            ErrorReporter.report(TAG, "Tool $name execution failed", e)
            ToolResult(false, "Tool error: ${e.message}")
        }
    }

    fun hasExecutor(name: String): Boolean = executors.containsKey(name)
    fun getAllToolNames(): Set<String> = executors.keys.toSet()
}
