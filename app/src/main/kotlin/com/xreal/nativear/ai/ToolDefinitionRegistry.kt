package com.xreal.nativear.ai

/**
 * Registry that provides provider-agnostic tool definitions.
 * Maps existing GeminiTools names to AIToolDefinition format.
 */
object ToolDefinitionRegistry {

    // mutableMapOf로 변경 — 원격 도구 동적 등록 지원
    private val tools = mutableMapOf(
        "searchWeb" to AIToolDefinition(
            name = "searchWeb",
            description = "Search the web for information.",
            parametersJson = """{"type":"object","properties":{"query":{"type":"string","description":"The search query."}},"required":["query"]}"""
        ),
        "getWeather" to AIToolDefinition(
            name = "getWeather",
            description = "Get current weather for a specific location.",
            parametersJson = """{"type":"object","properties":{"location":{"type":"string","description":"City or region name."}},"required":["location"]}"""
        ),
        "get_screen_objects" to AIToolDefinition(
            name = "get_screen_objects",
            description = "Get a list of all currently visible objects on screen with positions.",
            parametersJson = """{"type":"object","properties":{}}"""
        ),
        "take_snapshot" to AIToolDefinition(
            name = "take_snapshot",
            description = "Capture a manual snapshot of the current scene.",
            parametersJson = """{"type":"object","properties":{}}"""
        ),
        "query_visual_memory" to AIToolDefinition(
            name = "query_visual_memory",
            description = "Search memories visually similar to the current view.",
            parametersJson = """{"type":"object","properties":{}}"""
        ),
        "get_current_location" to AIToolDefinition(
            name = "get_current_location",
            description = "Get the user's current GPS coordinates and speed.",
            parametersJson = """{"type":"object","properties":{}}"""
        ),
        "query_spatial_memory" to AIToolDefinition(
            name = "query_spatial_memory",
            description = "Search memories near a location.",
            parametersJson = """{"type":"object","properties":{"latitude":{"type":"number"},"longitude":{"type":"number"},"radius_km":{"type":"number","description":"Search radius in km (default 0.5)"}},"required":["latitude","longitude"]}"""
        ),
        "query_temporal_memory" to AIToolDefinition(
            name = "query_temporal_memory",
            description = "Search memories within a specific time range.",
            parametersJson = """{"type":"object","properties":{"start_time":{"type":"string","description":"Start time (ISO or 'today')"},"end_time":{"type":"string","description":"End time (ISO or 'now')"}},"required":["start_time","end_time"]}"""
        ),
        "query_keyword_memory" to AIToolDefinition(
            name = "query_keyword_memory",
            description = "Search memories by text keywords.",
            parametersJson = """{"type":"object","properties":{"keyword":{"type":"string"}},"required":["keyword"]}"""
        ),
        "query_emotion_memory" to AIToolDefinition(
            name = "query_emotion_memory",
            description = "Search audio memories by detected emotion.",
            parametersJson = """{"type":"object","properties":{"emotion":{"type":"string","description":"One of: angry, happy, sad, excited, neutral"}},"required":["emotion"]}"""
        ),

        // --- Structured Data Tools ---
        "save_structured_data" to AIToolDefinition(
            name = "save_structured_data",
            description = "Save structured data to a named domain. Use for persisting session data, user preferences, analytics, or any domain-specific records. Upserts by (domain, data_key).",
            parametersJson = """{"type":"object","properties":{"domain":{"type":"string","description":"Data domain name (e.g. 'running_session', 'travel_log', 'user_preference')"},"data_key":{"type":"string","description":"Unique key within the domain (e.g. '2026-03-01_morning_run')"},"value":{"type":"string","description":"JSON string containing the data to store"},"tags":{"type":"string","description":"Optional comma-separated tags for filtering"}},"required":["domain","data_key","value"]}"""
        ),
        "query_structured_data" to AIToolDefinition(
            name = "query_structured_data",
            description = "Query structured data from a domain. Returns recent records matching the filters.",
            parametersJson = """{"type":"object","properties":{"domain":{"type":"string","description":"Data domain to query"},"data_key":{"type":"string","description":"Optional key pattern to search (partial match)"},"tags":{"type":"string","description":"Optional tag filter (partial match)"},"limit":{"type":"integer","description":"Max results (default 20)"}},"required":["domain"]}"""
        ),
        "list_data_domains" to AIToolDefinition(
            name = "list_data_domains",
            description = "List all structured data domains and their record counts.",
            parametersJson = """{"type":"object","properties":{}}"""
        ),
        "delete_structured_data" to AIToolDefinition(
            name = "delete_structured_data",
            description = "Delete a specific structured data record by domain and key.",
            parametersJson = """{"type":"object","properties":{"domain":{"type":"string","description":"Data domain"},"data_key":{"type":"string","description":"Exact key to delete"}},"required":["domain","data_key"]}"""
        ),

        // --- Backup Sync Tools ---
        "trigger_backup_sync" to AIToolDefinition(
            name = "trigger_backup_sync",
            description = "Trigger an immediate backup sync of structured data and recent memories to the server.",
            parametersJson = """{"type":"object","properties":{}}"""
        ),
        "configure_backup" to AIToolDefinition(
            name = "configure_backup",
            description = "Configure the backup server connection. Requires Tailscale IP and API key from the server setup.",
            parametersJson = """{"type":"object","properties":{"server_url":{"type":"string","description":"Server URL (e.g. 'http://100.x.x.x:8090')"},"api_key":{"type":"string","description":"API key from server setup"},"sync_interval_minutes":{"type":"integer","description":"Sync interval in minutes (default 60)"},"require_wifi":{"type":"boolean","description":"Only sync on WiFi (default false)"}},"required":["server_url","api_key"]}"""
        ),
        "get_backup_status" to AIToolDefinition(
            name = "get_backup_status",
            description = "Get current backup configuration and sync status.",
            parametersJson = """{"type":"object","properties":{}}"""
        ),

        // --- Plan/Todo Tools (Phase 3) ---
        "create_todo" to AIToolDefinition(
            name = "create_todo",
            description = "Create a new todo item for the user. Supports priority, deadline, and category.",
            parametersJson = """{"type":"object","properties":{"title":{"type":"string","description":"Todo title (concise, actionable)"},"description":{"type":"string","description":"Optional detailed description"},"priority":{"type":"string","description":"URGENT, HIGH, NORMAL, or LOW (default: NORMAL)"},"deadline":{"type":"string","description":"Deadline in format: yyyy-MM-dd HH:mm or HH:mm (today)"},"category":{"type":"string","description":"Category tag (e.g. 'work', 'health', 'personal')"}},"required":["title"]}"""
        ),
        "complete_todo" to AIToolDefinition(
            name = "complete_todo",
            description = "Mark a todo item as completed.",
            parametersJson = """{"type":"object","properties":{"todo_id":{"type":"string","description":"The ID of the todo to complete"}},"required":["todo_id"]}"""
        ),
        "list_todos" to AIToolDefinition(
            name = "list_todos",
            description = "List todo items with optional filters. Returns pending todos sorted by priority and deadline.",
            parametersJson = """{"type":"object","properties":{"status":{"type":"string","description":"Filter by status: PENDING, IN_PROGRESS, COMPLETED, CANCELLED"},"category":{"type":"string","description":"Filter by category"},"limit":{"type":"integer","description":"Max results (default 20)"}}}"""
        ),
        "create_schedule" to AIToolDefinition(
            name = "create_schedule",
            description = "Create a schedule block (time-bound appointment/task).",
            parametersJson = """{"type":"object","properties":{"title":{"type":"string","description":"Schedule block title"},"start_time":{"type":"string","description":"Start time: yyyy-MM-dd HH:mm"},"end_time":{"type":"string","description":"End time: yyyy-MM-dd HH:mm"},"type":{"type":"string","description":"MEETING, TASK, BREAK, EXERCISE, SOCIAL, CUSTOM (default: TASK)"},"notes":{"type":"string","description":"Optional notes"}},"required":["title","start_time","end_time"]}"""
        ),
        "get_schedule" to AIToolDefinition(
            name = "get_schedule",
            description = "Get schedule blocks for today or a specific date.",
            parametersJson = """{"type":"object","properties":{"date":{"type":"string","description":"Date in yyyy-MM-dd format (default: today)"}}}"""
        ),
        "get_daily_summary" to AIToolDefinition(
            name = "get_daily_summary",
            description = "Get a combined summary of today's todos and schedule for briefing.",
            parametersJson = """{"type":"object","properties":{}}"""
        ),

        // --- HUD Mode Tools (Phase 4) ---
        "switch_hud_mode" to AIToolDefinition(
            name = "switch_hud_mode",
            description = "Switch the AR HUD display mode. Available modes: DEFAULT, BRIEFING, RUNNING, TRAVEL, MUSIC, WORK, SOCIAL, FOCUS, HEALTH, MINIMAL, DEBUG.",
            parametersJson = """{"type":"object","properties":{"mode":{"type":"string","description":"HUD mode name (e.g. RUNNING, WORK, MINIMAL)"}},"required":["mode"]}"""
        ),
        "compose_hud_template" to AIToolDefinition(
            name = "compose_hud_template",
            description = "Create a custom HUD template by combining widgets. Use when no existing template fits the situation.",
            parametersJson = """{"type":"object","properties":{"name":{"type":"string","description":"Template name"},"widgets":{"type":"array","items":{"type":"string"},"description":"List of widget names: CLOCK, SITUATION_BADGE, TODO_CARD, SCHEDULE_COUNTDOWN, GOAL_PROGRESS, BIOMETRIC_PANEL, EXPERT_STATUS, WEATHER_MINI, RUNNING_STATS, TRANSLATION_OVERLAY, MUSIC_TIMER, DAILY_PROGRESS_BAR, PERSON_LABELS, OBJECT_LABELS"},"situation":{"type":"string","description":"LifeSituation to associate (e.g. STUDYING)"},"save":{"type":"boolean","description":"Save for reuse (default: true)"}},"required":["name","widgets"]}"""
        ),
        "add_hud_widget" to AIToolDefinition(
            name = "add_hud_widget",
            description = "Add a widget to the currently active HUD template.",
            parametersJson = """{"type":"object","properties":{"widget":{"type":"string","description":"Widget name to add"}},"required":["widget"]}"""
        ),
        "remove_hud_widget" to AIToolDefinition(
            name = "remove_hud_widget",
            description = "Remove a widget from the currently active HUD template.",
            parametersJson = """{"type":"object","properties":{"widget":{"type":"string","description":"Widget name to remove"}},"required":["widget"]}"""
        ),
        // --- Phase 11: Self-Improving AI Pipeline ---
        // --- Remote Camera Tools ---
        "show_remote_camera" to AIToolDefinition(
            name = "show_remote_camera",
            description = "Show the remote PC webcam video on the AR HUD as a PIP window.",
            parametersJson = """{"type":"object","properties":{"source":{"type":"string","description":"Optional server URL (e.g., 'http://100.64.88.46:8554')"}}}"""
        ),
        "hide_remote_camera" to AIToolDefinition(
            name = "hide_remote_camera",
            description = "Hide the remote PC webcam PIP and stop streaming.",
            parametersJson = """{"type":"object","properties":{}}"""
        ),
        "configure_remote_camera" to AIToolDefinition(
            name = "configure_remote_camera",
            description = "Configure remote camera PIP settings (position, size, URL).",
            parametersJson = """{"type":"object","properties":{"server_url":{"type":"string","description":"Server URL"},"position":{"type":"string","description":"PIP position: TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, CENTER"},"size_percent":{"type":"number","description":"PIP size as percentage of screen width (10-60)"}}}"""
        ),

        // --- Drawing Tools ---
        "draw_element" to AIToolDefinition(
            name = "draw_element",
            description = "Draw a visual element on the AR overlay. Types: text, rect, circle, line, arrow, highlight. Coordinates are percentages (0-100).",
            parametersJson = """{"type":"object","properties":{"type":{"type":"string","description":"Shape type: text, rect, circle, line, arrow, highlight"},"id":{"type":"string","description":"Optional unique ID"},"x":{"type":"number","description":"X position (0-100%)"},"y":{"type":"number","description":"Y position (0-100%)"},"x2":{"type":"number"},"y2":{"type":"number"},"width":{"type":"number"},"height":{"type":"number"},"radius":{"type":"number"},"text":{"type":"string"},"color":{"type":"string"},"size":{"type":"number"},"bold":{"type":"boolean"},"filled":{"type":"boolean"},"opacity":{"type":"number"}},"required":["type"]}"""
        ),
        "remove_drawing" to AIToolDefinition(
            name = "remove_drawing",
            description = "Remove a specific drawing element from the AR overlay by its ID.",
            parametersJson = """{"type":"object","properties":{"id":{"type":"string","description":"The ID of the drawing element to remove."}},"required":["id"]}"""
        ),
        "modify_drawing" to AIToolDefinition(
            name = "modify_drawing",
            description = "Modify properties of an existing drawing element on the AR overlay.",
            parametersJson = """{"type":"object","properties":{"id":{"type":"string","description":"The ID of the drawing element to modify."},"x":{"type":"number"},"y":{"type":"number"},"color":{"type":"string"},"text":{"type":"string"},"size":{"type":"number"},"opacity":{"type":"number"},"width":{"type":"number"},"height":{"type":"number"}},"required":["id"]}"""
        ),
        "clear_drawings" to AIToolDefinition(
            name = "clear_drawings",
            description = "Remove all custom drawings from the AR overlay.",
            parametersJson = """{"type":"object","properties":{}}"""
        ),

        // --- Vision/System Tools ---
        "setVisionControl" to AIToolDefinition(
            name = "setVisionControl",
            description = "Control vision features like OCR or pose detection.",
            parametersJson = """{"type":"object","properties":{"feature":{"type":"string","description":"The feature to control (OCR, POSE)."},"enabled":{"type":"boolean","description":"Whether to enable or disable."}},"required":["feature","enabled"]}"""
        ),
        "get_directions" to AIToolDefinition(
            name = "get_directions",
            description = "Get navigation directions to a destination.",
            parametersJson = """{"type":"object","properties":{"destination":{"type":"string","description":"The destination address or place name."},"origin":{"type":"string","description":"Optional starting point."}},"required":["destination"]}"""
        ),
        "sync_memory" to AIToolDefinition(
            name = "sync_memory",
            description = "Synchronize the local memory database to the cloud for backup.",
            parametersJson = """{"type":"object","properties":{}}"""
        ),
        "get_system_health" to AIToolDefinition(
            name = "get_system_health",
            description = "Get the current system health status: battery%, hardware connections, CPU/RAM, network, edge LLM availability, capability tier.",
            parametersJson = """{"type":"object","properties":{}}"""
        ),

        // --- Resource Management Tools ---
        "list_resources" to AIToolDefinition(
            name = "list_resources",
            description = "List all available resources (camera/mic/compute/display/sensor) and their status.",
            parametersJson = """{"type":"object","properties":{"category":{"type":"string","description":"Filter: camera | audio | compute | display | sensor"}}}"""
        ),
        "activate_resource" to AIToolDefinition(
            name = "activate_resource",
            description = "Activate a specific resource. Low-power resources auto-activate, high-power requires user approval.",
            parametersJson = """{"type":"object","properties":{"type":{"type":"string","description":"ResourceType name"},"reason":{"type":"string","description":"Reason for activation"}},"required":["type"]}"""
        ),
        "propose_resource_combo" to AIToolDefinition(
            name = "propose_resource_combo",
            description = "Propose a resource combination to the user. User accepts → AI auto-activates each resource.",
            parametersJson = """{"type":"object","properties":{"resources":{"type":"array","items":{"type":"string"},"description":"ResourceType names"},"benefit":{"type":"string","description":"Expected benefit"},"explanation":{"type":"string","description":"Why this combination"},"scenario":{"type":"string","description":"Scenario name"}},"required":["resources","benefit","explanation"]}"""
        ),

        // --- Expert Self-Advocacy Tools ---
        "request_prompt_addition" to AIToolDefinition(
            name = "request_prompt_addition",
            description = "Request additional context or instructions to be added to system prompt for current mission.",
            parametersJson = """{"type":"object","properties":{"content":{"type":"string","description":"Prompt content to add (max 500 chars)"},"rationale":{"type":"string","description":"Why this addition is needed"},"duration_hours":{"type":"number","description":"Duration in hours (default 24, max 168)"}},"required":["content","rationale"]}"""
        ),
        "request_tool_access" to AIToolDefinition(
            name = "request_tool_access",
            description = "Request access to a specific tool for current role.",
            parametersJson = """{"type":"object","properties":{"tool_name":{"type":"string","description":"Tool name to request access to"},"rationale":{"type":"string","description":"Why this tool is needed"},"task_context":{"type":"string","description":"Current task context"},"duration_hours":{"type":"number","description":"Access duration in hours (default 24)"}},"required":["tool_name","rationale"]}"""
        ),
        "request_expert_collaboration" to AIToolDefinition(
            name = "request_expert_collaboration",
            description = "Request collaboration session with another expert AI.",
            parametersJson = """{"type":"object","properties":{"expert_id":{"type":"string","description":"Target expert AI ID"},"collaboration_type":{"type":"string","description":"Type: joint_analysis, handoff, second_opinion"},"reason":{"type":"string","description":"Why collaboration is needed"}},"required":["expert_id","reason"]}"""
        ),

        // --- Running Coach Tools ---
        "get_running_stats" to AIToolDefinition(
            name = "get_running_stats",
            description = "Get current running session statistics including pace, distance, cadence, and form metrics.",
            parametersJson = """{"type":"object","properties":{}}"""
        ),
        "control_running_session" to AIToolDefinition(
            name = "control_running_session",
            description = "Control the running session: start, stop, pause, resume, or record a lap.",
            parametersJson = """{"type":"object","properties":{"action":{"type":"string","description":"Action: start, stop, pause, resume, lap"}},"required":["action"]}"""
        ),
        "get_running_advice" to AIToolDefinition(
            name = "get_running_advice",
            description = "Get AI coaching advice based on current running form and metrics.",
            parametersJson = """{"type":"object","properties":{}}"""
        ),

        "request_capability" to AIToolDefinition(
            name = "request_capability",
            description = "Request a new capability (tool, data source, sensor, etc.) that you need but don't have. Use when you identify a limitation in what you can do. Be honest about what you cannot do.",
            parametersJson = """{"type":"object","properties":{"type":{"type":"string","description":"Capability type: NEW_TOOL, TOOL_ENHANCEMENT, NEW_DATA_SOURCE, NEW_SENSOR, PROMPT_IMPROVEMENT, HUD_WIDGET, WORKFLOW_AUTOMATION, BUG_REPORT, PERFORMANCE_ISSUE"},"title":{"type":"string","description":"Short title for the capability"},"description":{"type":"string","description":"Detailed description of what is needed and why"},"current_limitation":{"type":"string","description":"What you cannot do right now"},"expected_benefit":{"type":"string","description":"What will be possible with this capability"},"priority":{"type":"string","description":"CRITICAL, HIGH, NORMAL, LOW, or WISHLIST"},"expert_id":{"type":"string","description":"Your expert ID"},"domain_id":{"type":"string","description":"Your domain ID"}},"required":["type","title","description"]}"""
        ),

        // --- Analytics/Statistics Tools (Phase 17) ---
        "get_expert_report" to AIToolDefinition(
            name = "get_expert_report",
            description = "Get expert performance report: effectiveness score, strategy count, growth stage, traits. Optionally filter by expert_id.",
            parametersJson = """{"type":"object","properties":{"expert_id":{"type":"string","description":"Optional expert ID to filter (e.g. 'behavior_analyst'). Omit for all experts."}}}"""
        ),
        "get_student_report" to AIToolDefinition(
            name = "get_student_report",
            description = "Get student progress report: observations count, IEP goals, activity outcomes. Optionally filter by student_key.",
            parametersJson = """{"type":"object","properties":{"student_key":{"type":"string","description":"Optional student key to filter (e.g. 'stu_민수'). Omit for all students."}}}"""
        ),
        "get_token_report" to AIToolDefinition(
            name = "get_token_report",
            description = "Get token usage report: daily per-provider usage, budget utilization, tier status.",
            parametersJson = """{"type":"object","properties":{"days":{"type":"integer","description":"Number of days to report (default: 7)"}}}"""
        ),
        "get_system_report" to AIToolDefinition(
            name = "get_system_report",
            description = "Get comprehensive system health report: memory nodes, data domains, tool activity, expert effectiveness, edge delegation candidates.",
            parametersJson = """{"type":"object","properties":{}}"""
        ),
        // ★ Policy Department: 정책 조회/변경 도구
        "request_policy_change" to AIToolDefinition(
            name = "request_policy_change",
            description = "Request a policy change (override a system constant). Auto-approved for user_voice and emergency; otherwise queued for strategist review.",
            parametersJson = """{"type":"object","properties":{"key":{"type":"string","description":"Policy key (e.g. cadence.ocr_interval_ms)"},"value":{"type":"string","description":"New value"},"rationale":{"type":"string","description":"Reason for change"},"priority":{"type":"integer","description":"0=normal, 1=recommended, 2=emergency"},"source":{"type":"string","description":"Requester (user_voice, ai_agent, strategist)"}},"required":["key","value"]}"""
        ),
        "query_policy" to AIToolDefinition(
            name = "query_policy",
            description = "Query current value and metadata of a specific policy.",
            parametersJson = """{"type":"object","properties":{"key":{"type":"string","description":"Policy key to query"}},"required":["key"]}"""
        ),
        "list_policies" to AIToolDefinition(
            name = "list_policies",
            description = "List all policies or filter by category (CADENCE, BUDGET, COMPANION, VISION, RUNNING, MEETING, FOCUS, AI_CONFIG, CAPACITY, SYSTEM, MISSION, EXPERT, RESILIENCE).",
            parametersJson = """{"type":"object","properties":{"category":{"type":"string","description":"Optional category filter"}}}"""
        )
    )

    fun getToolDefinition(name: String): AIToolDefinition? = tools[name]
    fun getAllToolDefinitions(): List<AIToolDefinition> = tools.values.toList()

    /**
     * 원격/동적 도구 등록 — RemoteToolExecutor에서 서버 도구 로드 후 호출.
     * 모든 프로바이더 (Gemini, Claude, Grok, OpenAI)에서 참조됨.
     */
    fun registerAdditionalTools(newTools: List<AIToolDefinition>) {
        newTools.forEach { tool ->
            tools[tool.name] = tool
        }
    }
}
