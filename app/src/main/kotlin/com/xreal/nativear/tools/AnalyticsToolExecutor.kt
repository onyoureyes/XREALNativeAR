package com.xreal.nativear.tools

import com.xreal.nativear.analytics.SystemAnalyticsService

/**
 * AnalyticsToolExecutor: Provides 4 statistics query tools for AI experts.
 * Delegates to SystemAnalyticsService (singleton, 0 AI calls, pure DB mining).
 */
class AnalyticsToolExecutor(
    private val analyticsService: SystemAnalyticsService
) : IToolExecutor {

    override val supportedTools = setOf(
        "get_expert_report",
        "get_student_report",
        "get_token_report",
        "get_system_report"
    )

    override suspend fun execute(name: String, args: Map<String, Any?>): ToolResult {
        val result = when (name) {
            "get_expert_report" -> analyticsService.getExpertPerformanceReport(
                expertId = args["expert_id"] as? String
            )
            "get_student_report" -> analyticsService.getStudentProgressReport(
                studentKey = args["student_key"] as? String
            )
            "get_token_report" -> analyticsService.getTokenUsageReport(
                days = (args["days"] as? Number)?.toInt() ?: 7
            )
            "get_system_report" -> analyticsService.getSystemHealthReport()
            else -> "Unknown analytics tool: $name"
        }
        return ToolResult(success = true, data = result)
    }
}
