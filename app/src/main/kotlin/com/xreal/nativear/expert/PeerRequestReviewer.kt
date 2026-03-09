package com.xreal.nativear.expert

import android.util.Log
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.strategist.Directive
import com.xreal.nativear.strategist.DirectiveStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * PeerRequestReviewer — 독립 서비스 아님. StrategistService/Reflector의 유틸 클래스.
 *
 * StrategistReflector.reflect() 내에서 Gemini에게 전문가 요청 심사를 포함시키고,
 * 결과를 이 클래스가 파싱해 적용함.
 * → 신규 Gemini API 호출 0개. 기존 5분 주기 호출에 편승.
 *
 * ## 사용법 (StrategistService.runReflectionCycle 내부)
 * ```kotlin
 * val pending = peerRequestStore.getPendingRequests()
 * val reviewContext = peerRequestReviewer.buildReviewContext(pending)  // 프롬프트 추가
 * // ...reflect()에 reviewContext 포함...
 * if (pending.isNotEmpty()) {
 *     peerRequestReviewer.applyReviewDecisions(rawReflectionResponse, pending)
 * }
 * ```
 */
class PeerRequestReviewer(
    private val requestStore: ExpertPeerRequestStore,
    private val directiveStore: DirectiveStore,
    private val dynamicProfileStore: ExpertDynamicProfileStore,
    private val eventBus: GlobalEventBus
) {
    private val TAG = "PeerRequestReviewer"
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * StrategistReflector 프롬프트에 포함할 전문가 요청 심사 컨텍스트 생성.
     * Gemini가 전략 지시사항과 함께 요청 심사 결정을 JSON으로 반환하도록 유도.
     */
    fun buildReviewContext(pending: List<ExpertPeerRequest>): String {
        if (pending.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine("=== [전문가 AI 팀원 역량 강화 요청 — 팀장 심사 필요] ===")
            appendLine("다음 요청들을 심사하고, 전략 지시사항 JSON 배열 끝에 'peer_reviews' 키로 결과를 추가해주세요.")
            appendLine()
            pending.forEachIndexed { i, req ->
                appendLine("요청 ${i + 1}:")
                appendLine("  ID: ${req.id}")
                appendLine("  전문가: ${req.requestingExpertId}")
                appendLine("  유형: ${req.requestType.displayName} (${req.requestType.name})")
                appendLine("  내용: ${req.content}")
                appendLine("  이유: ${req.rationale}")
                req.situation?.let { appendLine("  상황: $it") }
                appendLine()
            }
            appendLine("심사 기준:")
            appendLine("  1. 현재 미션/목적 수행에 실질적으로 도움이 되는가?")
            appendLine("  2. 보안/프라이버시/안전 위험이 없는가?")
            appendLine("  3. 기존 지침과 충돌하지 않는가?")
            appendLine()
            appendLine("peer_reviews 형식 (전략 지시사항 JSON에 추가):")
            appendLine("""  "peer_reviews": [""")
            appendLine("""    {"id":"요청ID","status":"APPROVED","notes":"이유","approved_content":"적용내용","expires_hours":48},""")
            appendLine("""    {"id":"요청ID","status":"REJECTED","notes":"거부이유"},""")
            appendLine("""    {"id":"요청ID","status":"MODIFIED","notes":"수정이유","approved_content":"수정된내용","expires_hours":24}""")
            appendLine("""  ]""")
        }
    }

    /**
     * Gemini 반성 응답에서 peer_reviews 섹션을 파싱해 요청 상태 업데이트 및 동적 적용.
     *
     * @param rawResponse StrategistReflector가 반환한 전체 응답 텍스트
     * @param pendingRequests 심사 대상이었던 요청들 (ID 검증용)
     */
    fun applyReviewDecisions(rawResponse: String, pendingRequests: List<ExpertPeerRequest>) {
        if (pendingRequests.isEmpty()) return

        val pendingIds = pendingRequests.map { it.id }.toSet()
        val reviews = extractPeerReviews(rawResponse)

        if (reviews.isEmpty()) {
            Log.d(TAG, "peer_reviews 섹션 없음 (Gemini가 포함하지 않았음) — 다음 주기로 이월")
            return
        }

        var applied = 0
        var rejected = 0

        for (review in reviews) {
            val id = review.optString("id") ?: continue
            if (id !in pendingIds) continue  // 이번 배치에 없는 요청은 무시

            val status = review.optString("status", "")
            val notes = review.optString("notes", "")
            val approvedContent = review.optString("approved_content", "")
            val expiresHours = review.optLong("expires_hours", 48L)
            val expiresAt = System.currentTimeMillis() + expiresHours * 3600_000L

            val originalRequest = pendingRequests.find { it.id == id } ?: continue

            when (status.uppercase()) {
                "APPROVED" -> {
                    val finalContent = approvedContent.ifBlank { originalRequest.content }
                    requestStore.approveRequest(id, notes, finalContent)
                    applyApproval(originalRequest, finalContent, expiresAt)
                    applied++
                }
                "MODIFIED" -> {
                    if (approvedContent.isBlank()) {
                        Log.w(TAG, "MODIFIED 응답에 approved_content 없음: $id")
                        requestStore.rejectRequest(id, "MODIFIED 요청에 수정 내용 없음")
                        rejected++
                    } else {
                        requestStore.approveModified(id, notes, approvedContent)
                        applyApproval(originalRequest, approvedContent, expiresAt)
                        applied++
                    }
                }
                "REJECTED" -> {
                    requestStore.rejectRequest(id, notes)
                    rejected++
                    scope.launch {
                        eventBus.publish(XRealEvent.SystemEvent.DebugLog(
                            "❌ [팀장거부] ${originalRequest.requestingExpertId}: ${notes.take(60)}"
                        ))
                    }
                }
            }
        }

        Log.i(TAG, "피어 요청 심사 완료: 승인 $applied, 거부 $rejected / 전체 ${pendingRequests.size}")
    }

    private fun applyApproval(request: ExpertPeerRequest, finalContent: String, expiresAt: Long) {
        when (request.requestType) {
            ExpertRequestType.PROMPT_ADDITION -> {
                // DirectiveStore에 타깃 전문가 향 Directive 추가
                val directive = Directive(
                    targetPersonaId = request.requestingExpertId,
                    instruction = finalContent,
                    rationale = "팀장 승인 요청 (${request.id}): ${request.rationale.take(80)}",
                    confidence = 0.8f,
                    expiresAt = expiresAt
                )
                directiveStore.addDirective(directive)
                dynamicProfileStore.addPromptAddition(
                    request.requestingExpertId, finalContent, expiresAt, request.id
                )
                scope.launch {
                    eventBus.publish(XRealEvent.SystemEvent.DebugLog(
                        "✅ [팀장승인] ${request.requestingExpertId}: 프롬프트 추가 — '${finalContent.take(40)}'"
                    ))
                }
            }

            ExpertRequestType.TOOL_ACCESS -> {
                // 도구 이름 추출 (finalContent: "도구 접근 요청: tool_name ...")
                val toolName = finalContent.removePrefix("도구 접근 요청: ").split(" ").firstOrNull()
                    ?: request.content.split(":").getOrNull(1)?.trim()
                    ?: finalContent.take(30)

                dynamicProfileStore.addTool(request.requestingExpertId, toolName, expiresAt)
                scope.launch {
                    eventBus.publish(XRealEvent.SystemEvent.DebugLog(
                        "✅ [팀장승인] ${request.requestingExpertId}: 도구 접근 → $toolName"
                    ))
                }
            }

            ExpertRequestType.EXPERT_COLLABORATION -> {
                // 협업 세션은 MissionConductor 없이 Directive로 알림만
                val directive = Directive(
                    targetPersonaId = request.requestingExpertId,
                    instruction = "협업 승인됨: $finalContent",
                    rationale = "팀장 협업 승인 (${request.id})",
                    confidence = 0.75f,
                    expiresAt = expiresAt
                )
                directiveStore.addDirective(directive)
                scope.launch {
                    eventBus.publish(XRealEvent.SystemEvent.DebugLog(
                        "✅ [팀장승인] ${request.requestingExpertId}: 협업 → $finalContent"
                    ))
                }
            }

            ExpertRequestType.CONTEXT_EXPANSION -> {
                val directive = Directive(
                    targetPersonaId = request.requestingExpertId,
                    instruction = finalContent,
                    rationale = "팀장 컨텍스트 확장 승인 (${request.id})",
                    confidence = 0.8f,
                    expiresAt = expiresAt
                )
                directiveStore.addDirective(directive)
            }
        }
    }

    /**
     * Gemini 응답 텍스트에서 peer_reviews JSON 배열 추출.
     * 응답 형식이 일정하지 않을 수 있어 다양한 패턴 시도.
     */
    private fun extractPeerReviews(rawResponse: String): List<JSONObject> {
        // 1. "peer_reviews": [...] 패턴 탐색
        val peerReviewsPattern = Regex(""""peer_reviews"\s*:\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL)
        val match = peerReviewsPattern.find(rawResponse) ?: return emptyList()

        return try {
            val arr = JSONArray(match.groupValues[1])
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (e: Exception) {
            Log.d(TAG, "peer_reviews 파싱 실패: ${e.message}")
            emptyList()
        }
    }
}
