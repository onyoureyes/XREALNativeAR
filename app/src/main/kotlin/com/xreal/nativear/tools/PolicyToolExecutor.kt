package com.xreal.nativear.tools

import android.util.Log
import com.xreal.nativear.policy.PolicyManager
import com.xreal.nativear.policy.PolicyRegistry

/**
 * PolicyToolExecutor — AI 에이전트용 정책 조회/변경 도구.
 *
 * 도구:
 *   request_policy_change — 정책 오버라이드 요청
 *   query_policy — 특정 정책 현재 값 조회
 *   list_policies — 카테고리별 정책 목록 조회
 */
class PolicyToolExecutor(
    private val policyRegistry: PolicyRegistry,
    private val policyManager: PolicyManager
) : IToolExecutor {
    private val TAG = "PolicyToolExecutor"

    override val supportedTools = setOf(
        "request_policy_change",
        "query_policy",
        "list_policies"
    )

    override suspend fun execute(name: String, args: Map<String, Any?>): ToolResult {
        return try {
            when (name) {
                "request_policy_change" -> requestPolicyChange(args)
                "query_policy" -> queryPolicy(args)
                "list_policies" -> listPolicies(args)
                else -> ToolResult(false, "Unknown tool: $name")
            }
        } catch (e: Exception) {
            Log.e(TAG, "$name 실행 실패: ${e.message}", e)
            ToolResult(false, "Policy tool error: ${e.message}")
        }
    }

    private fun requestPolicyChange(args: Map<String, Any?>): ToolResult {
        val key = args["key"]?.toString() ?: return ToolResult(false, "Missing 'key' parameter")
        val value = args["value"]?.toString() ?: return ToolResult(false, "Missing 'value' parameter")
        val rationale = args["rationale"]?.toString() ?: "AI 에이전트 요청"
        val priority = (args["priority"] as? Number)?.toInt() ?: 0
        val source = args["source"]?.toString() ?: "ai_agent"

        // user_voice 소스면 즉시 적용
        if (source == "user_voice") {
            val success = policyRegistry.set(key, value, source)
            return if (success) {
                ToolResult(true, "정책 변경 완료: $key = $value (사용자 직접 요청)")
            } else {
                val entry = policyRegistry.getEntry(key)
                ToolResult(false, "정책 변경 실패: $key (유효 범위: ${entry?.min}~${entry?.max})")
            }
        }

        val request = PolicyManager.PolicyChangeRequest(
            policyKey = key,
            newValue = value,
            requesterPersonaId = source,
            rationale = rationale,
            priority = priority
        )

        val submitted = policyManager.submitRequest(request)
        return if (submitted) {
            ToolResult(true, "정책 변경 요청 제출: $key → $value (심사 대기 중)")
        } else {
            ToolResult(false, "정책 변경 요청 실패")
        }
    }

    private fun queryPolicy(args: Map<String, Any?>): ToolResult {
        val key = args["key"]?.toString() ?: return ToolResult(false, "Missing 'key' parameter")
        val entry = policyRegistry.getEntry(key)
            ?: return ToolResult(false, "알 수 없는 정책 키: $key")

        val result = buildString {
            appendLine("정책: ${entry.key}")
            appendLine("카테고리: ${entry.category}")
            appendLine("설명: ${entry.description}")
            appendLine("기본값: ${entry.defaultValue}")
            appendLine("현재값: ${entry.effectiveValue}")
            appendLine("오버라이드: ${if (entry.isOverridden) "${entry.overrideValue} (${entry.overrideSource})" else "없음"}")
            appendLine("범위: ${entry.min ?: "제한없음"} ~ ${entry.max ?: "제한없음"}")
        }
        return ToolResult(true, result.trimEnd())
    }

    private fun listPolicies(args: Map<String, Any?>): ToolResult {
        val categoryName = args["category"]?.toString()

        val policies = if (categoryName != null) {
            val category = try {
                com.xreal.nativear.policy.PolicyCategory.valueOf(categoryName.uppercase())
            } catch (e: Exception) {
                return ToolResult(false, "알 수 없는 카테고리: $categoryName. 유효값: ${com.xreal.nativear.policy.PolicyCategory.entries.joinToString()}")
            }
            policyRegistry.listByCategory(category)
        } else {
            policyRegistry.listAll()
        }

        if (policies.isEmpty()) {
            return ToolResult(true, "등록된 정책 없음")
        }

        val result = buildString {
            appendLine("정책 목록 (${policies.size}개):")
            policies.forEach { entry ->
                val overrideMarker = if (entry.isOverridden) " [오버라이드: ${entry.overrideSource}]" else ""
                appendLine("  ${entry.key} = ${entry.effectiveValue}$overrideMarker — ${entry.description}")
            }
        }
        return ToolResult(true, result.trimEnd())
    }
}
