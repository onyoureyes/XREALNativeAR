package com.xreal.nativear.resource

import android.util.Log
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * ResourceProposalManager — AI 리소스 제안 → 사용자 승인 흐름 관리.
 *
 * ## 흐름
 * 1. AI(`propose_resource_combo` 도구) → `propose()` 호출
 * 2. HUD 카드 + TTS 안내: "카메라 두 개를 추가하면 ... 활성화할까요?"
 * 3. 사용자 "응/네" 음성 → `onUserVoiceResponse()` → 승인 처리
 * 4. 사용자 "아니오/됐어" → 거부 처리
 * 5. 30초 내 응답 없으면 자동 만료
 *
 * ## 고전력 리소스 개별 승인
 * `requestApproval()`: suspend 함수, 사용자 응답까지 대기 (최대 30s)
 */
class ResourceProposalManager(
    private val eventBus: GlobalEventBus,
    private val resourceRegistry: ResourceRegistry
) {
    companion object {
        private const val TAG = "ResourceProposalManager"
        private const val PROPOSAL_TIMEOUT_MS = 30_000L   // 30초 승인 대기
        private const val APPROVAL_TIMEOUT_MS = 30_000L   // 개별 승인 30초 대기
        const val PROPOSAL_HUD_ID = "res_proposal_card"
        val APPROVAL_KEYWORDS = listOf(
            "응", "네", "예", "그래", "좋아", "활성화", "확인", "맞아", "해줘", "켜줘"
        )
        val REJECTION_KEYWORDS = listOf(
            "아니", "아니오", "됐어", "취소", "싫어", "필요없어", "꺼줘", "안돼"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 현재 활성 제안 (한 번에 하나만)
    @Volatile private var pendingProposal: PendingProposal? = null

    // 개별 승인 대기 콜백
    @Volatile private var pendingApprovalContinuation: ((Boolean) -> Unit)? = null
    @Volatile private var pendingApprovalType: ResourceType? = null

    fun start() {
        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    // 음성 명령으로 제안 승인/거부
                    is XRealEvent.InputEvent.VoiceCommand -> handleVoiceForProposal(event.text)
                    is XRealEvent.InputEvent.EnrichedVoiceCommand -> handleVoiceForProposal(event.text)
                    // NOD 제스처 → 승인
                    is XRealEvent.InputEvent.Gesture -> {
                        if (event.type == com.xreal.nativear.core.GestureType.NOD) handleApprovalGesture(true)
                        if (event.type == com.xreal.nativear.core.GestureType.SHAKE) handleApprovalGesture(false)
                    }
                    else -> {}
                }
            }
        }
        Log.d(TAG, "ResourceProposalManager 시작")
    }

    fun stop() {
        pendingProposal = null
        pendingApprovalContinuation = null
        Log.d(TAG, "ResourceProposalManager 종료")
    }

    // =========================================================================
    // AI 리소스 조합 제안
    // =========================================================================

    /**
     * AI의 propose_resource_combo 도구 호출 시 사용.
     * HUD 카드 + TTS 안내 표시.
     */
    fun propose(
        resources: List<ResourceType>,
        explanation: String,
        benefit: String,
        scenario: String = ""
    ) {
        // 기존 제안이 있으면 교체
        pendingProposal?.let { prev ->
            Log.d(TAG, "기존 제안 대체: ${prev.resources.map { it.name }} → ${resources.map { it.name }}")
            hideProposalHUD()
        }

        val proposal = PendingProposal(resources, explanation, benefit, scenario)
        pendingProposal = proposal

        // HUD 카드 표시
        val resourceNames = resources.joinToString(", ") { it.displayName }
        val hudText = buildProposalText(resources, explanation, benefit, scenario)
        showProposalHUD(hudText)

        // TTS 안내 (짧게)
        val ttsText = buildProposalTTS(resources, benefit)
        scope.launch {
            eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(ttsText))
        }

        Log.i(TAG, "리소스 제안: $resourceNames — $benefit")

        // 30초 타임아웃
        scope.launch {
            kotlinx.coroutines.delay(PROPOSAL_TIMEOUT_MS)
            if (pendingProposal === proposal) {
                Log.d(TAG, "리소스 제안 타임아웃 — 자동 만료")
                pendingProposal = null
                hideProposalHUD()
            }
        }
    }

    /**
     * 고전력 리소스 개별 승인 요청.
     * suspend 함수 — 사용자 응답까지 대기 (최대 30s).
     *
     * @return true = 승인, false = 거부/타임아웃
     */
    suspend fun requestApproval(type: ResourceType, reason: String): Boolean {
        return try {
            withTimeout(APPROVAL_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    pendingApprovalType = type
                    pendingApprovalContinuation = { approved ->
                        pendingApprovalContinuation = null
                        pendingApprovalType = null
                        if (continuation.isActive) continuation.resume(approved)
                    }

                    // TTS + HUD 표시
                    val msg = "\"${type.displayName}\" 활성화 승인 필요: $reason\n음성으로 \"네\" 또는 \"아니오\"라고 말해주세요."
                    scope.launch {
                        eventBus.publish(XRealEvent.ActionRequest.SpeakTTS("${type.displayName}을 활성화하면 ${reason} 허용하시겠어요?"))
                    }
                    showApprovalHUD(type, reason)

                    Log.i(TAG, "개별 승인 대기: ${type.displayName}")
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.d(TAG, "개별 승인 타임아웃: ${type.displayName} → 거부 처리")
            hideApprovalHUD()
            false
        }
    }

    /**
     * 사용자 음성 응답 처리 (InputCoordinator에서 호출).
     * "응/네/그래/활성화/확인" → 승인, "아니/됐어/취소/싫어" → 거부
     */
    fun onUserVoiceResponse(text: String) {
        handleVoiceForProposal(text)
    }

    // =========================================================================
    // 내부 처리
    // =========================================================================

    private fun handleVoiceForProposal(text: String) {
        val lower = text.lowercase()
        val isApproval = APPROVAL_KEYWORDS.any { lower.contains(it) }
        val isRejection = REJECTION_KEYWORDS.any { lower.contains(it) }

        if (!isApproval && !isRejection) return

        // 개별 승인 대기 중?
        pendingApprovalContinuation?.let { cont ->
            Log.d(TAG, "개별 승인 응답: ${if (isApproval) "승인" else "거부"}")
            hideApprovalHUD()
            cont(isApproval)
            return
        }

        // 일반 제안 대기 중?
        pendingProposal?.let { proposal ->
            Log.d(TAG, "리소스 제안 응답: ${if (isApproval) "승인" else "거부"}")
            pendingProposal = null
            hideProposalHUD()
            if (isApproval) {
                activateProposedResources(proposal)
            } else {
                scope.launch {
                    eventBus.publish(XRealEvent.ActionRequest.SpeakTTS("알겠습니다. 현재 상태를 유지합니다."))
                }
            }
        }
    }

    private fun handleApprovalGesture(approved: Boolean) {
        // 개별 승인 대기 중?
        pendingApprovalContinuation?.let { cont ->
            Log.d(TAG, "제스처 승인: ${if (approved) "NOD(승인)" else "SHAKE(거부)"}")
            hideApprovalHUD()
            cont(approved)
            return
        }

        // 일반 제안 대기 중?
        pendingProposal?.let { proposal ->
            pendingProposal = null
            hideProposalHUD()
            if (approved) {
                activateProposedResources(proposal)
            }
        }
    }

    private fun activateProposedResources(proposal: PendingProposal) {
        scope.launch {
            var activatedCount = 0
            proposal.resources.forEach { type ->
                when (resourceRegistry.activate(type, "user_approved_proposal")) {
                    is ActivationResult.Success -> activatedCount++
                    is ActivationResult.NeedsUserApproval -> {
                        // 조합 승인 = 이미 일괄 승인된 것으로 간주
                        resourceRegistry.activate(type, "user_approved_combo")
                        activatedCount++
                    }
                    else -> Log.w(TAG, "${type.displayName} 활성화 실패")
                }
            }
            val msg = if (activatedCount > 0) {
                "${activatedCount}개 리소스 활성화 완료. ${proposal.benefit}"
            } else {
                "리소스 활성화에 실패했습니다."
            }
            eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(msg))
            Log.i(TAG, "리소스 조합 활성화: $activatedCount/${proposal.resources.size}개 성공")
        }
    }

    // =========================================================================
    // HUD 표시
    // =========================================================================

    private fun showProposalHUD(text: String) {
        scope.launch {
            // 반투명 배경 카드
            eventBus.publish(
                XRealEvent.ActionRequest.DrawingCommand(
                    com.xreal.nativear.DrawCommand.Add(
                        com.xreal.nativear.DrawElement.Rect(
                            id = "${PROPOSAL_HUD_ID}_bg",
                            x = 10f, y = 60f, width = 80f, height = 35f,
                            color = "#1A1A2E", filled = true, opacity = 0.85f
                        )
                    )
                )
            )
            eventBus.publish(
                XRealEvent.ActionRequest.DrawingCommand(
                    com.xreal.nativear.DrawCommand.Add(
                        com.xreal.nativear.DrawElement.Text(
                            id = PROPOSAL_HUD_ID,
                            x = 50f, y = 68f,
                            text = text,
                            size = 16f, color = "#E8E8FF", opacity = 1f
                        )
                    )
                )
            )
        }
    }

    private fun hideProposalHUD() {
        scope.launch {
            eventBus.publish(
                XRealEvent.ActionRequest.DrawingCommand(
                    com.xreal.nativear.DrawCommand.Remove("${PROPOSAL_HUD_ID}_bg")
                )
            )
            eventBus.publish(
                XRealEvent.ActionRequest.DrawingCommand(
                    com.xreal.nativear.DrawCommand.Remove(PROPOSAL_HUD_ID)
                )
            )
        }
    }

    private fun showApprovalHUD(type: ResourceType, reason: String) {
        scope.launch {
            eventBus.publish(
                XRealEvent.ActionRequest.DrawingCommand(
                    com.xreal.nativear.DrawCommand.Add(
                        com.xreal.nativear.DrawElement.Text(
                            id = "res_approval_hud",
                            x = 50f, y = 85f,
                            text = "⚡ ${type.displayName} 활성화 — \"네\" / \"아니오\"",
                            size = 18f, color = "#FFD700", opacity = 1f
                        )
                    )
                )
            )
        }
    }

    private fun hideApprovalHUD() {
        scope.launch {
            eventBus.publish(
                XRealEvent.ActionRequest.DrawingCommand(
                    com.xreal.nativear.DrawCommand.Remove("res_approval_hud")
                )
            )
        }
    }

    // =========================================================================
    // 텍스트 빌드 헬퍼
    // =========================================================================

    private fun buildProposalText(
        resources: List<ResourceType>,
        explanation: String,
        benefit: String,
        scenario: String
    ): String {
        val resourceList = resources.joinToString(", ") { it.displayName }
        val title = if (scenario.isNotEmpty()) "[$scenario] " else ""
        return "${title}제안: $resourceList\n이유: $explanation\n기대: $benefit\n→ \"네\" 수락 / \"아니오\" 거부"
    }

    private fun buildProposalTTS(resources: List<ResourceType>, benefit: String): String {
        return when {
            resources.size == 1 -> "${resources[0].displayName}을 활성화하면 $benefit 활성화할까요?"
            resources.size == 2 -> "${resources[0].displayName}과 ${resources[1].displayName}을 함께 사용하면 $benefit 활성화할까요?"
            else -> "리소스 ${resources.size}개를 추가하면 $benefit 활성화할까요?"
        }
    }

    // =========================================================================
    // 내부 모델
    // =========================================================================

    private data class PendingProposal(
        val resources: List<ResourceType>,
        val explanation: String,
        val benefit: String,
        val scenario: String,
        val createdMs: Long = System.currentTimeMillis()
    )

}
