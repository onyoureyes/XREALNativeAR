package com.xreal.nativear.running

import com.google.ai.client.generativeai.type.defineFunction
import com.google.ai.client.generativeai.type.FunctionDeclaration
import com.xreal.nativear.ai.AIToolDefinition
import org.json.JSONObject

object RunningCoachTools {

    private val _toolDefs = mutableListOf<AIToolDefinition>()

    private fun defineTool(
        name: String,
        description: String,
        schemaJson: String
    ): FunctionDeclaration {
        _toolDefs.add(AIToolDefinition(name, description, schemaJson))
        return defineFunction(name, description) { JSONObject(schemaJson) }
    }

    val getRunningStats = defineTool(
        "get_running_stats",
        "Get current running session statistics including pace, distance, cadence, and form metrics.",
        """{ "type": "OBJECT", "properties": {} }"""
    )

    val controlRunningSession = defineTool(
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

    val getRunningAdvice = defineTool(
        "get_running_advice",
        "Get AI coaching advice based on current running form and metrics.",
        """{ "type": "OBJECT", "properties": {} }"""
    )

    fun getAllRunningTools(): List<FunctionDeclaration> {
        return listOf(getRunningStats, controlRunningSession, getRunningAdvice)
    }

    fun getAllRunningToolDefinitions(): List<AIToolDefinition> = _toolDefs.toList()
}
