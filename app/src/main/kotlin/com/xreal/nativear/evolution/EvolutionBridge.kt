package com.xreal.nativear.evolution

import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.expert.ExpertDomainRegistry
import com.xreal.nativear.monitoring.TokenEconomyManager
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * EvolutionBridge: 앱 ↔ 백업 서버 간 자동화된 자가 진화 브릿지.
 *
 * ## 자동화 흐름 (서버 API 기반)
 * 1. 앱에서 에러/성능 이슈/개선 요청 발생
 * 2. CapabilityManager가 CapabilityRequest 생성
 * 3. EvolutionBridge.syncToServer()가 승인된 요청을 서버 API로 전송
 * 4. 이 PC의 Claude Code가 서버 GET /api/evolution/pending 으로 폴링
 * 5. Claude Code가 코드 수정 → 빌드 → APK 배포
 * 6. 서버 POST /api/evolution/{id}/resolve 로 완료 표시
 *
 * ## 에러 패턴 자동 수집
 * EventBus의 SystemEvent.Error를 구독하여 반복 에러를 자동으로
 * 개선 요청(BUG_REPORT)으로 변환 → 서버 전송.
 *
 * ## 폴백
 * 서버 접근 불가 시 기존 파일 기반(/sdcard/xreal/evolution/) 폴백 유지.
 */
class EvolutionBridge(
    private val capabilityManager: CapabilityManager,
    private val database: UnifiedMemoryDatabase,
    private val domainRegistry: ExpertDomainRegistry,
    private val tokenEconomy: TokenEconomyManager,
    private val eventBus: GlobalEventBus,
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "EvolutionBridge"
        // 파일 기반 폴백
        private const val EVOLUTION_DIR = "/sdcard/xreal/evolution"
        private const val APPROVED_FILE = "$EVOLUTION_DIR/approved_requests.json"
        private const val RESULT_FILE = "$EVOLUTION_DIR/implementation_result.json"
        private const val SNAPSHOT_FILE = "$EVOLUTION_DIR/system_snapshot.json"
        // 서버 API (Tailscale)
        private const val SERVER_URL = "http://100.101.127.124:8090"
        private const val API_KEY = "3ztg3rzTith-vViCK7FLe5SQNUqqACKTojyDX1oiNe0"
        // 에러 패턴 자동 수집 임계값
        private const val ERROR_AUTO_REPORT_THRESHOLD = 5  // 같은 에러 5회 → 자동 요청
        private const val SYNC_INTERVAL_MS = 30 * 60 * 1000L  // 30분마다 동기화
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jsonMedia = "application/json".toMediaType()

    // 에러 카운터: code → count
    private val errorCounter = mutableMapOf<String, Int>()
    // 이미 리포트한 에러 코드 (중복 방지)
    private val reportedErrors = mutableSetOf<String>()

    private var syncJob: Job? = null

    /**
     * EventBus 구독 시작 + 주기적 서버 동기화
     */
    fun start() {
        // 에러 이벤트 자동 수집
        scope.launch {
            eventBus.events.collect { event ->
                try {
                    if (event is XRealEvent.SystemEvent.Error) {
                        onErrorEvent(event)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Error event processing failed: ${e.message}")
                }
            }
        }

        // 주기적 서버 동기화 (30분)
        syncJob = scope.launch {
            while (isActive) {
                delay(SYNC_INTERVAL_MS)
                try {
                    syncToServer()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Periodic sync failed: ${e.message}")
                }
            }
        }

        Log.i(TAG, "EvolutionBridge started (auto-sync every 30min)")
    }

    fun stop() {
        syncJob?.cancel()
        scope.cancel()
    }

    // ─── 에러 패턴 자동 감지 ───

    private fun onErrorEvent(error: XRealEvent.SystemEvent.Error) {
        val code = error.code
        val count = errorCounter.getOrDefault(code, 0) + 1
        errorCounter[code] = count

        // 임계값 도달 + 아직 미리포트 → 자동 BUG_REPORT 생성
        if (count >= ERROR_AUTO_REPORT_THRESHOLD && code !in reportedErrors) {
            reportedErrors.add(code)
            Log.i(TAG, "Auto bug report: $code (${count}x)")

            val request = CapabilityRequest(
                requestingExpertId = "EvolutionBridge",
                type = CapabilityType.BUG_REPORT,
                title = "Recurring error: $code",
                description = "Error '${error.message}' occurred $count times. " +
                    "Severity: ${error.severity}. Auto-generated bug report.",
                currentLimitation = error.message,
                priority = if (error.severity == com.xreal.nativear.core.ErrorSeverity.CRITICAL)
                    RequestPriority.HIGH else RequestPriority.NORMAL
            )
            capabilityManager.submitRequest(request)

            // 즉시 서버에 전송 시도
            scope.launch {
                try { sendRequestToServer(request) } catch (_: Exception) {}
            }
        }
    }

    // ─── 서버 API 동기화 ───

    /**
     * 승인된 요청을 서버로 전송 + 시스템 스냅샷 전송
     */
    fun syncToServer(): Int {
        var synced = 0
        try {
            val approved = capabilityManager.getApprovedRequests()
            val pending = capabilityManager.getPendingRequests()
            val allToSync = approved + pending

            for (req in allToSync) {
                try {
                    if (sendRequestToServer(req)) synced++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to sync request ${req.id}: ${e.message}")
                }
            }

            if (synced > 0) {
                Log.i(TAG, "Synced $synced/${allToSync.size} requests to server")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Server sync failed, falling back to file: ${e.message}")
            // 파일 기반 폴백
            exportApprovedRequests()
        }
        return synced
    }

    private fun sendRequestToServer(req: CapabilityRequest): Boolean {
        val body = JSONObject().apply {
            put("type", req.type.name.lowercase())
            put("category", mapTypeToCategory(req.type))
            put("title", req.title)
            put("description", req.description)
            put("priority", mapPriorityToInt(req.priority))
            put("context", JSONObject().apply {
                put("request_id", req.id)
                put("requesting_expert", req.requestingExpertId)
                put("requesting_domain", req.requestingDomainId ?: "")
                put("current_limitation", req.currentLimitation ?: "")
                put("expected_benefit", req.expectedBenefit ?: "")
                put("situation", req.situation ?: "")
                put("app_status", req.status.name)
                put("timestamp", req.timestamp)
            })
        }

        val request = Request.Builder()
            .url("$SERVER_URL/api/evolution/request")
            .addHeader("Authorization", "Bearer $API_KEY")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        val response = httpClient.newCall(request).execute()
        return response.isSuccessful
    }

    private fun mapTypeToCategory(type: CapabilityType): String = when (type) {
        CapabilityType.NEW_TOOL, CapabilityType.TOOL_ENHANCEMENT -> "ai"
        CapabilityType.NEW_DATA_SOURCE, CapabilityType.NEW_SENSOR -> "system"
        CapabilityType.PROMPT_IMPROVEMENT -> "ai"
        CapabilityType.HUD_WIDGET -> "hud"
        CapabilityType.WORKFLOW_AUTOMATION -> "system"
        CapabilityType.BUG_REPORT -> "system"
        CapabilityType.PERFORMANCE_ISSUE -> "system"
    }

    private fun mapPriorityToInt(priority: RequestPriority): Int = when (priority) {
        RequestPriority.CRITICAL -> 3
        RequestPriority.HIGH -> 2
        RequestPriority.NORMAL -> 1
        RequestPriority.LOW -> 0
        RequestPriority.WISHLIST -> 0
    }

    // ─── 파일 기반 폴백 (서버 불가 시) ───

    /**
     * Export approved capability requests for Claude Code to process.
     */
    fun exportApprovedRequests(): Int {
        try {
            ensureDirectory()
            val approved = capabilityManager.getApprovedRequests()
            if (approved.isEmpty()) {
                Log.d(TAG, "No approved requests to export")
                return 0
            }

            val json = JSONArray()
            approved.forEach { req ->
                json.put(JSONObject().apply {
                    put("id", req.id)
                    put("type", req.type.name)
                    put("title", req.title)
                    put("description", req.description)
                    put("current_limitation", req.currentLimitation ?: "")
                    put("expected_benefit", req.expectedBenefit ?: "")
                    put("priority", req.priority.name)
                    put("requesting_expert", req.requestingExpertId)
                    put("requesting_domain", req.requestingDomainId ?: "")
                    put("situation", req.situation ?: "")
                    put("timestamp", req.timestamp)
                })
            }

            File(APPROVED_FILE).writeText(json.toString(2))
            Log.i(TAG, "Exported ${approved.size} approved requests to $APPROVED_FILE")
            return approved.size
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export requests: ${e.message}")
            return 0
        }
    }

    /**
     * Import implementation results from Claude Code.
     */
    fun importImplementationResult(): Boolean {
        try {
            val file = File(RESULT_FILE)
            if (!file.exists()) {
                Log.d(TAG, "No implementation result file found")
                return false
            }

            val json = JSONObject(file.readText())
            val requestId = json.getString("request_id")
            val success = json.getBoolean("success")
            val notes = json.optString("notes", "")

            if (success) {
                capabilityManager.updateStatus(requestId, RequestStatus.IMPLEMENTED, notes)
                Log.i(TAG, "Implementation imported: $requestId — SUCCESS")
            } else {
                val error = json.optString("error", "Unknown error")
                capabilityManager.updateStatus(requestId, RequestStatus.APPROVED,
                    "Implementation failed: $error")
                Log.w(TAG, "Implementation failed: $requestId — $error")
            }

            // Clean up result file
            file.delete()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import result: ${e.message}")
            return false
        }
    }

    /**
     * Export system snapshot for Claude Code context.
     */
    fun exportSystemSnapshot(): Boolean {
        try {
            ensureDirectory()

            val snapshot = JSONObject().apply {
                // Existing tools
                put("existing_tools", JSONArray().apply {
                    listOf(
                        "searchWeb", "getWeather", "get_directions",
                        "get_screen_objects", "take_snapshot", "query_visual_memory",
                        "query_keyword_memory", "query_temporal_memory", "query_emotion_memory",
                        "query_spatial_memory", "save_memory",
                        "draw_element", "modify_element", "remove_element", "clear_drawing",
                        "save_structured_data", "query_structured_data", "get_current_location", "get_time_info",
                        "set_cadence_profile", "check_model_status", "get_system_stats", "restart_model",
                        "get_running_stats", "control_running_session", "get_running_advice",
                        "hud_register_interactive", "hud_update_element", "hud_remove_element",
                        "hud_apply_template", "hud_get_state", "hud_animate",
                        "hud_register_physics_body", "hud_set_focus_target",
                        "create_todo", "complete_todo", "list_todos",
                        "create_schedule", "get_schedule", "get_daily_summary",
                        "switch_hud_mode", "compose_hud_template", "add_hud_widget", "remove_hud_widget",
                        "request_capability"
                    ).forEach { put(it) }
                })

                // Existing domains
                put("existing_domains", JSONArray().apply {
                    domainRegistry.getAllDomains().forEach { domain ->
                        put(JSONObject().apply {
                            put("id", domain.id)
                            put("name", domain.name)
                            put("experts", domain.experts.size)
                            put("required_tools", JSONArray(domain.requiredTools.toList()))
                        })
                    }
                })

                // Token economy
                val usage = tokenEconomy.getTodayUsage()
                put("today_usage", JSONObject().apply {
                    put("calls", usage.totalCalls)
                    put("input_tokens", usage.totalInputTokens)
                    put("output_tokens", usage.totalOutputTokens)
                    put("cost_usd", usage.totalCostUsd.toDouble())
                })

                // Pending requests
                put("pending_requests", JSONArray().apply {
                    capabilityManager.getPendingRequests().forEach { req ->
                        put(JSONObject().apply {
                            put("id", req.id)
                            put("title", req.title)
                            put("type", req.type.name)
                            put("priority", req.priority.name)
                        })
                    }
                })

                // Error patterns (auto-collected)
                put("error_patterns", JSONObject().apply {
                    errorCounter.entries.sortedByDescending { it.value }.take(20).forEach {
                        put(it.key, it.value)
                    }
                })

                // Stats
                val stats = capabilityManager.getRequestStats()
                put("request_stats", JSONObject().apply {
                    put("total", stats.total)
                    put("pending", stats.pending)
                    put("approved", stats.approved)
                    put("implemented", stats.implemented)
                    put("rejected", stats.rejected)
                })

                put("exported_at", System.currentTimeMillis())
            }

            File(SNAPSHOT_FILE).writeText(snapshot.toString(2))

            // 서버에도 전송 시도
            try {
                sendSnapshotToServer(snapshot)
            } catch (_: Exception) {}

            Log.i(TAG, "System snapshot exported")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export snapshot: ${e.message}")
            return false
        }
    }

    private fun sendSnapshotToServer(snapshot: JSONObject) {
        val request = Request.Builder()
            .url("$SERVER_URL/api/sync/structured-data")
            .addHeader("Authorization", "Bearer $API_KEY")
            .post(JSONObject().apply {
                put("records", JSONArray().apply {
                    put(JSONObject().apply {
                        put("domain", "evolution")
                        put("data_key", "system_snapshot")
                        put("value", snapshot.toString())
                        put("created_at", System.currentTimeMillis())
                        put("updated_at", System.currentTimeMillis())
                    })
                })
            }.toString().toRequestBody(jsonMedia))
            .build()
        httpClient.newCall(request).execute().close()
    }

    private fun ensureDirectory() {
        val dir = File(EVOLUTION_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
    }
}
