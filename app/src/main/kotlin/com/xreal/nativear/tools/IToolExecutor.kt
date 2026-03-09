package com.xreal.nativear.tools

data class ToolResult(
    val success: Boolean,
    val data: String,
    val metadata: Map<String, Any>? = null
)

interface IToolExecutor {
    val supportedTools: Set<String>
    suspend fun execute(name: String, args: Map<String, Any?>): ToolResult
}
