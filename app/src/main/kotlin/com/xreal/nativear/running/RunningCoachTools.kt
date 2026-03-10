package com.xreal.nativear.running

import com.google.ai.client.generativeai.type.defineFunction
import com.google.ai.client.generativeai.type.FunctionDeclaration
import com.xreal.nativear.ai.AIToolDefinition
import org.json.JSONObject

object RunningCoachTools {

    private data class ToolPair(val declaration: FunctionDeclaration, val definition: AIToolDefinition)

    private fun defineTool(
        name: String,
        description: String,
        schemaJson: String
    ): ToolPair {
        val declaration = defineFunction(name, description) { JSONObject(schemaJson) }
        val definition = AIToolDefinition(name, description, schemaJson)
        return ToolPair(declaration, definition)
    }

    private val runningStats = defineTool(
        "get_running_stats",
        "Get current running session statistics including pace, distance, cadence, and form metrics.",
        """{ "type": "OBJECT", "properties": {} }"""
    )

    private val runningSession = defineTool(
        "control_running_session",
        "Control the running session: start, stop, pause, resume, or record a lap.",
        """{
            "type": "OBJECT",
            "properties": {
                "action": {
                    "type": "STRING",
                    "description": "Action to perform: start, stop, pause, resume, lap"
                }
            },
            "required": ["action"]
        }"""
    )

    private val runningAdvice = defineTool(
        "get_running_advice",
        "Get AI coaching advice based on current running form and metrics.",
        """{ "type": "OBJECT", "properties": {} }"""
    )

    // 기존 공개 API 호환 유지
    val getRunningStats: FunctionDeclaration = runningStats.declaration
    val controlRunningSession: FunctionDeclaration = runningSession.declaration
    val getRunningAdvice: FunctionDeclaration = runningAdvice.declaration

    private val allTools = listOf(runningStats, runningSession, runningAdvice)

    fun getAllRunningTools(): List<FunctionDeclaration> = allTools.map { it.declaration }

    fun getAllRunningToolDefinitions(): List<AIToolDefinition> = allTools.map { it.definition }
}
