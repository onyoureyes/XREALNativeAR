package com.xreal.nativear.tools

import android.util.Log
import com.xreal.nativear.ai.AIToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * RemoteToolExecutor — 이 PC 백업 서버의 원격 도구를 AI 에이전트에 제공.
 *
 * ## 흐름
 * 1. 앱 시작 시 GET /api/tools → 서버에 등록된 도구 목록 로드
 * 2. GeminiTools에 동적 등록 → AI가 도구 목록에서 발견
 * 3. AI가 도구 호출 → POST /api/tools/{name}/execute
 * 4. 서버에서 실행 (LLM 질의, 모델 학습 등) → 결과 반환
 *
 * ## 제공 도구 예시
 * - query_local_llm: gemma-3-12b (12B) 질의 ($0, ~39 tok/s)
 * - train_model: RoutineClassifier 학습 트리거
 * - search_memory_backup: 백업 DB 검색
 * - check_server_health: 서버 상태 확인
 * - request_new_capability: 새 도구 요청 → EvolutionBridge
 *
 * 서버 불가 시 graceful 실패 — 로컬 도구만 사용.
 */
class RemoteToolExecutor(
    private val httpClient: OkHttpClient
) : IToolExecutor {

    companion object {
        private const val TAG = "RemoteToolExecutor"
        private const val SERVER_URL = "http://100.101.127.124:8090"
        private const val API_KEY = "3ztg3rzTith-vViCK7FLe5SQNUqqACKTojyDX1oiNe0"
    }

    private val jsonMedia = "application/json".toMediaType()

    // 서버에서 로드한 도구 이름 → 서버 도구 여부
    private val remoteToolNames = mutableSetOf<String>()

    // 서버에서 로드한 AIToolDefinition 목록 (GeminiTools에 등록용)
    private val _loadedToolDefs = mutableListOf<AIToolDefinition>()
    val loadedToolDefinitions: List<AIToolDefinition> get() = _loadedToolDefs.toList()

    override val supportedTools: Set<String> get() = remoteToolNames

    /**
     * 서버에서 도구 목록 로드. AppBootstrapper에서 호출.
     * 서버 불가 시 빈 목록 — 로컬 도구만 사용.
     */
    suspend fun loadRemoteTools(): Int = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$SERVER_URL/api/tools")
                .addHeader("Authorization", "Bearer $API_KEY")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "서버 도구 로드 실패: ${response.code}")
                return@withContext 0
            }

            val json = JSONObject(response.body?.string() ?: "{}")
            val tools = json.optJSONArray("tools") ?: JSONArray()

            remoteToolNames.clear()
            _loadedToolDefs.clear()

            for (i in 0 until tools.length()) {
                val tool = tools.getJSONObject(i)
                val name = tool.getString("name")
                val desc = tool.getString("description")
                val params = tool.getString("parameters_json")

                remoteToolNames.add(name)
                _loadedToolDefs.add(AIToolDefinition(
                    name = name,
                    description = "[원격/PC] $desc",
                    parametersJson = params
                ))
            }

            Log.i(TAG, "원격 도구 ${remoteToolNames.size}개 로드: ${remoteToolNames.joinToString(", ")}")
            remoteToolNames.size
        } catch (e: Exception) {
            Log.d(TAG, "서버 접근 불가 (정상 — Tailscale 미연결): ${e.message}")
            0
        }
    }

    override suspend fun execute(name: String, args: Map<String, Any?>): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("arguments", JSONObject(args.mapValues { it.value?.toString() }))
                }

                val request = Request.Builder()
                    .url("$SERVER_URL/api/tools/$name/execute")
                    .addHeader("Authorization", "Bearer $API_KEY")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(jsonMedia))
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: "{}"

                if (!response.isSuccessful) {
                    Log.e(TAG, "원격 도구 실행 실패: $name → ${response.code}")
                    return@withContext ToolResult(false, "Remote tool error: ${response.code}")
                }

                val json = JSONObject(responseBody)
                val success = json.optBoolean("success", false)
                val data = json.opt("data")?.toString() ?: ""

                Log.i(TAG, "원격 도구 실행: $name → ${if (success) "OK" else "FAIL"}")
                ToolResult(success, data)
            } catch (e: Exception) {
                Log.e(TAG, "원격 도구 통신 실패: $name — ${e.message}")
                ToolResult(false, "Remote tool unavailable: ${e.message}")
            }
        }
}
