package com.xreal.nativear

import com.google.ai.client.generativeai.type.defineFunction
import com.google.ai.client.generativeai.type.FunctionDeclaration
import org.json.JSONObject

/**
 * Gemini SDK 전용 FunctionDeclaration 정의.
 * 도구 정의(AIToolDefinition)는 ToolDefinitionRegistry에 통합됨.
 * GeminiProvider.registeredTools fallback 용도로만 유지.
 */
object GeminiTools {

    private fun geminiFunc(
        name: String,
        description: String,
        schemaJson: String
    ): FunctionDeclaration =
        defineFunction(name, description) { JSONObject(schemaJson) }

    val searchWeb = geminiFunc(
        "searchWeb",
        "Search the web for information using Naver Search API.",
        """{"type":"OBJECT","properties":{"query":{"type":"STRING","description":"The search query."}},"required":["query"]}"""
    )

    val getWeather = geminiFunc(
        "getWeather",
        "Get current weather for a specific location.",
        """{"type":"OBJECT","properties":{"location":{"type":"STRING","description":"City or region name."}},"required":["location"]}"""
    )

    val setVisionControl = geminiFunc(
        "setVisionControl",
        "Control vision features like OCR or pose detection.",
        """{"type":"OBJECT","properties":{"feature":{"type":"STRING"},"enabled":{"type":"BOOLEAN"}},"required":["feature","enabled"]}"""
    )

    val queryTemporalMemory = geminiFunc(
        "query_temporal_memory",
        "Search memories within a specific time range.",
        """{"type":"OBJECT","properties":{"start_time":{"type":"STRING"},"end_time":{"type":"STRING"}},"required":["start_time","end_time"]}"""
    )

    val querySpatialMemory = geminiFunc(
        "query_spatial_memory",
        "Search memories near a location.",
        """{"type":"OBJECT","properties":{"latitude":{"type":"NUMBER"},"longitude":{"type":"NUMBER"},"radius_km":{"type":"NUMBER"}},"required":["latitude","longitude"]}"""
    )

    val queryKeywordMemory = geminiFunc(
        "query_keyword_memory",
        "Search memories by text keywords.",
        """{"type":"OBJECT","properties":{"keyword":{"type":"STRING"}},"required":["keyword"]}"""
    )

    val queryEmotionMemory = geminiFunc(
        "query_emotion_memory",
        "Search audio memories by detected emotion.",
        """{"type":"OBJECT","properties":{"emotion":{"type":"STRING"}},"required":["emotion"]}"""
    )

    val getDirections = geminiFunc(
        "get_directions",
        "Get navigation directions to a destination.",
        """{"type":"OBJECT","properties":{"destination":{"type":"STRING"},"origin":{"type":"STRING"}},"required":["destination"]}"""
    )

    val takeSnapshot = geminiFunc("take_snapshot", "Capture a manual snapshot.", """{"type":"OBJECT","properties":{}}""")
    val getCurrentLocation = geminiFunc("get_current_location", "Get GPS coordinates and speed.", """{"type":"OBJECT","properties":{}}""")
    val getSystemHealth = geminiFunc("get_system_health", "Get system health status.", """{"type":"OBJECT","properties":{}}""")
    val syncMemory = geminiFunc("sync_memory", "Sync memory DB to cloud.", """{"type":"OBJECT","properties":{}}""")
    val queryVisualMemory = geminiFunc("query_visual_memory", "Search visually similar memories.", """{"type":"OBJECT","properties":{}}""")

    val drawElement = geminiFunc(
        "draw_element",
        "Draw a visual element on the AR overlay.",
        """{"type":"OBJECT","properties":{"type":{"type":"STRING"},"id":{"type":"STRING"},"x":{"type":"NUMBER"},"y":{"type":"NUMBER"},"x2":{"type":"NUMBER"},"y2":{"type":"NUMBER"},"width":{"type":"NUMBER"},"height":{"type":"NUMBER"},"radius":{"type":"NUMBER"},"text":{"type":"STRING"},"color":{"type":"STRING"},"size":{"type":"NUMBER"},"bold":{"type":"BOOLEAN"},"filled":{"type":"BOOLEAN"},"opacity":{"type":"NUMBER"}},"required":["type"]}"""
    )

    val removeDrawing = geminiFunc("remove_drawing", "Remove a drawing element by ID.", """{"type":"OBJECT","properties":{"id":{"type":"STRING"}},"required":["id"]}""")
    val modifyDrawing = geminiFunc("modify_drawing", "Modify an existing drawing element.", """{"type":"OBJECT","properties":{"id":{"type":"STRING"},"x":{"type":"NUMBER"},"y":{"type":"NUMBER"},"color":{"type":"STRING"},"text":{"type":"STRING"},"size":{"type":"NUMBER"},"opacity":{"type":"NUMBER"}},"required":["id"]}""")
    val clearDrawings = geminiFunc("clear_drawings", "Remove all custom drawings.", """{"type":"OBJECT","properties":{}}""")
    val getScreenObjects = geminiFunc("get_screen_objects", "Get visible objects on screen with positions.", """{"type":"OBJECT","properties":{}}""")

    val showRemoteCamera = geminiFunc("show_remote_camera", "Show remote PC webcam as PIP.", """{"type":"OBJECT","properties":{"source":{"type":"STRING"}}}""")
    val hideRemoteCamera = geminiFunc("hide_remote_camera", "Hide remote webcam PIP.", """{"type":"OBJECT","properties":{}}""")
    val configureRemoteCamera = geminiFunc("configure_remote_camera", "Configure remote camera PIP settings.", """{"type":"OBJECT","properties":{"server_url":{"type":"STRING"},"position":{"type":"STRING"},"size_percent":{"type":"NUMBER"}}}""")

    val listResources = geminiFunc("list_resources", "List available resources and status.", """{"type":"OBJECT","properties":{"category":{"type":"STRING"}}}""")
    val activateResource = geminiFunc("activate_resource", "Activate a specific resource.", """{"type":"OBJECT","properties":{"type":{"type":"STRING"},"reason":{"type":"STRING"}},"required":["type"]}""")
    val proposeResourceCombo = geminiFunc("propose_resource_combo", "Propose resource combination to user.", """{"type":"OBJECT","properties":{"resources":{"type":"ARRAY","items":{"type":"STRING"}},"benefit":{"type":"STRING"},"explanation":{"type":"STRING"},"scenario":{"type":"STRING"}},"required":["resources","benefit","explanation"]}""")

    val requestPromptAddition = geminiFunc("request_prompt_addition", "Request system prompt addition.", """{"type":"OBJECT","properties":{"content":{"type":"STRING"},"rationale":{"type":"STRING"},"duration_hours":{"type":"NUMBER"}},"required":["content","rationale"]}""")
    val requestToolAccess = geminiFunc("request_tool_access", "Request tool access.", """{"type":"OBJECT","properties":{"tool_name":{"type":"STRING"},"rationale":{"type":"STRING"},"task_context":{"type":"STRING"},"duration_hours":{"type":"NUMBER"}},"required":["tool_name","rationale"]}""")
    val requestExpertCollaboration = geminiFunc("request_expert_collaboration", "Request expert collaboration.", """{"type":"OBJECT","properties":{"expert_id":{"type":"STRING"},"collaboration_type":{"type":"STRING"},"reason":{"type":"STRING"}},"required":["expert_id","reason"]}""")

    // ★ Policy Department — 정책 조회/변경 도구
    val requestPolicyChange = geminiFunc(
        "request_policy_change",
        "Request a policy setting change. Use source='user_voice' for user commands (auto-approved). Other sources go to review queue.",
        """{"type":"OBJECT","properties":{"key":{"type":"STRING","description":"Policy key (e.g. cadence.ocr_interval_ms)"},"value":{"type":"STRING","description":"New value"},"rationale":{"type":"STRING","description":"Reason for change"},"priority":{"type":"INTEGER","description":"0=normal, 1=recommended, 2=urgent"},"source":{"type":"STRING","description":"Requester: user_voice, ai_agent, strategist"}},"required":["key","value"]}"""
    )
    val queryPolicy = geminiFunc(
        "query_policy",
        "Query the current value and metadata of a specific policy.",
        """{"type":"OBJECT","properties":{"key":{"type":"STRING","description":"Policy key to query"}},"required":["key"]}"""
    )
    val listPolicies = geminiFunc(
        "list_policies",
        "List all policies, optionally filtered by category (CADENCE, BUDGET, COMPANION, VISION, RUNNING, MEETING, FOCUS, AI_CONFIG, CAPACITY, SYSTEM).",
        """{"type":"OBJECT","properties":{"category":{"type":"STRING","description":"Optional category filter"}}}"""
    )

    /**
     * Gemini SDK FunctionDeclaration 목록 — GeminiProvider.registeredTools fallback 전용.
     */
    fun getAllTools(): List<FunctionDeclaration> {
        return listOf(
            searchWeb, getWeather, setVisionControl,
            queryTemporalMemory, querySpatialMemory, queryKeywordMemory,
            getDirections, takeSnapshot, getCurrentLocation,
            syncMemory, queryVisualMemory, queryEmotionMemory,
            drawElement, removeDrawing, modifyDrawing, clearDrawings, getScreenObjects,
            showRemoteCamera, hideRemoteCamera, configureRemoteCamera,
            listResources, activateResource, proposeResourceCombo,
            requestPromptAddition, requestToolAccess, requestExpertCollaboration,
            // ★ Policy Department
            requestPolicyChange, queryPolicy, listPolicies
        ) + com.xreal.nativear.running.RunningCoachTools.getAllRunningTools()
    }
}
