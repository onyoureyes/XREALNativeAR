package com.xreal.nativear.evolution

import com.xreal.nativear.tools.IToolExecutor
import com.xreal.nativear.tools.ToolResult

/**
 * CapabilityToolExecutor: AI-facing tool for requesting new capabilities.
 *
 * Allows AI experts to formally request tools, data sources, sensors,
 * or other capabilities they need but don't have.
 */
class CapabilityToolExecutor(
    private val capabilityManager: CapabilityManager
) : IToolExecutor {

    override val supportedTools = setOf("request_capability")

    override suspend fun execute(name: String, args: Map<String, Any?>): ToolResult {
        return when (name) {
            "request_capability" -> requestCapability(args)
            else -> ToolResult(false, "Unknown tool: $name")
        }
    }

    private fun requestCapability(args: Map<String, Any?>): ToolResult {
        val type = args["type"]?.toString() ?: return ToolResult(false, "Missing 'type' parameter")
        val title = args["title"]?.toString() ?: return ToolResult(false, "Missing 'title' parameter")
        val description = args["description"]?.toString() ?: return ToolResult(false, "Missing 'description' parameter")

        val capType = try {
            CapabilityType.valueOf(type.uppercase())
        } catch (_: Exception) {
            CapabilityType.NEW_TOOL
        }

        val priority = try {
            val p = args["priority"]?.toString()?.uppercase()
            if (p != null) RequestPriority.valueOf(p) else RequestPriority.NORMAL
        } catch (_: Exception) {
            RequestPriority.NORMAL
        }

        val request = CapabilityRequest(
            requestingExpertId = args["expert_id"]?.toString() ?: "unknown",
            requestingDomainId = args["domain_id"]?.toString(),
            type = capType,
            title = title,
            description = description,
            currentLimitation = args["current_limitation"]?.toString(),
            expectedBenefit = args["expected_benefit"]?.toString(),
            priority = priority,
            situation = args["situation"]?.toString()
        )

        val requestId = capabilityManager.submitRequest(request)

        return if (requestId == "DUPLICATE") {
            ToolResult(true, "Similar request already exists. No duplicate created.")
        } else {
            ToolResult(true,
                "Capability request submitted (ID: $requestId). " +
                "User will be notified for approval. " +
                "Status: PENDING. " +
                "Suggest a workaround to the user while this capability is being developed."
            )
        }
    }
}
