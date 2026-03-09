package com.xreal.nativear.tools

import android.util.Log
import com.xreal.nativear.expert.ExpertPeerRequest
import com.xreal.nativear.expert.ExpertPeerRequestStore
import com.xreal.nativear.expert.ExpertRequestType

/**
 * ExpertSelfAdvocacyToolExecutor — 전문가 AI가 자신의 역량 강화를 요청하는 도구 집합.
 *
 * ## 지원 도구 (GeminiTools에 선언됨)
 *
 * ### request_prompt_addition
 * 전문가가 현재 미션에서 더 나은 성과를 위해 자신의 시스템 프롬프트에
 * 추가할 맥락/지침을 팀장(StrategistReflector)에게 요청.
 * 예: "지난 30일 이 아이의 OT 기록 요약이 필요해. 현재 세션 목표와 직접 연관됨."
 *
 * ### request_tool_access
 * 특정 도구에 대한 임시 접근 권한 요청.
 * 예: "query_spatial_memory 도구가 필요해. 장소 기반 패턴 분석을 위해."
 *
 * ### request_expert_collaboration
 * 다른 전문가 AI와의 협업 세션 요청.
 * 예: "speech_lang_pathologist와 협력해서 아이의 발화-집중도 상관관계를 분석하고 싶어."
 *
 * ## 흐름
 * 도구 호출 → ExpertPeerRequestStore.submitRequest() → PENDING
 * → StrategistService 5분 주기 (피크타임 제외) → Gemini 심사 → 적용
 */
class ExpertSelfAdvocacyToolExecutor(
    private val requestStore: ExpertPeerRequestStore
) : IToolExecutor {
    private val TAG = "ExpertSelfAdvocacyTool"

    override val supportedTools = setOf(
        "request_prompt_addition",
        "request_tool_access",
        "request_expert_collaboration"
    )

    override suspend fun execute(name: String, args: Map<String, Any?>): ToolResult {
        return when (name) {
            "request_prompt_addition"     -> requestPromptAddition(args)
            "request_tool_access"         -> requestToolAccess(args)
            "request_expert_collaboration" -> requestExpertCollaboration(args)
            else -> ToolResult(false, "지원하지 않는 도구: $name")
        }
    }

    // ─── request_prompt_addition ───
    private fun requestPromptAddition(args: Map<String, Any?>): ToolResult {
        val expertId  = args["expert_id"]?.toString() ?: return ToolResult(false, "expert_id 누락")
        val content   = args["content"]?.toString() ?: return ToolResult(false, "content 누락")
        val rationale = args["rationale"]?.toString() ?: return ToolResult(false, "rationale 누락")
        val situation = args["situation"]?.toString()
        val durationH = args["duration_hours"]?.toString()?.toLongOrNull() ?: 48L
        val expiresAt = System.currentTimeMillis() + durationH * 3600_000L

        if (content.length > 500) {
            return ToolResult(false, "content가 너무 깁니다 (최대 500자). 핵심만 요약하세요.")
        }

        val request = ExpertPeerRequest(
            requestingExpertId = expertId,
            requestType = ExpertRequestType.PROMPT_ADDITION,
            content = content,
            rationale = rationale,
            situation = situation,
            expiresAt = expiresAt
        )

        val id = requestStore.submitRequest(request)
        return if (id == "DUPLICATE") {
            ToolResult(true, "유사한 요청이 이미 검토 중입니다.")
        } else if (id == "ERROR") {
            ToolResult(false, "요청 저장 실패. 나중에 다시 시도하세요.")
        } else {
            Log.i(TAG, "프롬프트 추가 요청 제출: $expertId / ID=$id")
            ToolResult(true, "요청이 접수되었습니다 (ID: $id). 팀장(StrategistService)이 다음 반성 주기에 심사합니다.")
        }
    }

    // ─── request_tool_access ───
    private fun requestToolAccess(args: Map<String, Any?>): ToolResult {
        val expertId    = args["expert_id"]?.toString() ?: return ToolResult(false, "expert_id 누락")
        val toolName    = args["tool_name"]?.toString() ?: return ToolResult(false, "tool_name 누락")
        val rationale   = args["rationale"]?.toString() ?: return ToolResult(false, "rationale 누락")
        val taskContext = args["task_context"]?.toString()
        val durationH   = args["duration_hours"]?.toString()?.toLongOrNull() ?: 24L
        val expiresAt   = System.currentTimeMillis() + durationH * 3600_000L

        val content = "도구 접근 요청: $toolName" +
            (taskContext?.let { " — 작업 맥락: $it" } ?: "")

        val request = ExpertPeerRequest(
            requestingExpertId = expertId,
            requestType = ExpertRequestType.TOOL_ACCESS,
            content = content,
            rationale = rationale,
            expiresAt = expiresAt
        )

        val id = requestStore.submitRequest(request)
        return if (id == "DUPLICATE") {
            ToolResult(true, "$toolName 도구에 대한 요청이 이미 검토 중입니다.")
        } else {
            Log.i(TAG, "도구 접근 요청 제출: $expertId → $toolName / ID=$id")
            ToolResult(true, "$toolName 도구 접근 요청이 접수되었습니다. 팀장 심사 후 부여됩니다.")
        }
    }

    // ─── request_expert_collaboration ───
    private fun requestExpertCollaboration(args: Map<String, Any?>): ToolResult {
        val requestingId    = args["requesting_expert_id"]?.toString() ?: return ToolResult(false, "requesting_expert_id 누락")
        val targetExpertId  = args["target_expert_id"]?.toString() ?: return ToolResult(false, "target_expert_id 누락")
        val collaborationType = args["collaboration_type"]?.toString() ?: "JOINT_ANALYSIS"
        val reason          = args["reason"]?.toString() ?: return ToolResult(false, "reason 누락")

        val content = "협업 요청: $targetExpertId 와 $collaborationType"

        val request = ExpertPeerRequest(
            requestingExpertId = requestingId,
            requestType = ExpertRequestType.EXPERT_COLLABORATION,
            content = content,
            rationale = reason
        )

        val id = requestStore.submitRequest(request)
        return if (id == "DUPLICATE") {
            ToolResult(true, "$targetExpertId 와의 협업 요청이 이미 검토 중입니다.")
        } else {
            Log.i(TAG, "전문가 협업 요청 제출: $requestingId → $targetExpertId / ID=$id")
            ToolResult(true, "$targetExpertId 와의 협업 요청이 접수되었습니다. 팀장이 협업 세션을 구성합니다.")
        }
    }
}
