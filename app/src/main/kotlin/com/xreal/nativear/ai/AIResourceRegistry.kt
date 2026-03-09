package com.xreal.nativear.ai

import android.graphics.Bitmap
import android.util.Log
import com.xreal.nativear.policy.PolicyReader
import com.xreal.nativear.router.persona.BudgetTier
import com.xreal.nativear.router.persona.TokenBudgetTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AIResourceRegistry — 통합 AI 프로바이더 라우팅.
 *
 * 모든 AI 호출 지점에서 `registry.routeText(...)` / `registry.routeVision(...)` 으로 통일.
 * 새 프로바이더 추가 시 이 레지스트리에만 등록하면 모든 호출 지점에서 자동 적용.
 *
 * ## 라우팅 우선순위 (기본 정책)
 * 리모트($0) → 서버(Cloud, $) → 엣지($0, on-device)
 *
 * ## 예산 연동
 * - NORMAL: 리모트 → 서버 → 엣지
 * - CAREFUL(90%+): 리모트 → 엣지 (서버 최소화)
 * - MINIMAL(95%+): 리모트 → 엣지만
 * - BLOCKED(100%): 리모트 → 엣지만 (필수 호출만)
 *
 * ## 능력 기반 필터링
 * - VisionTask → hasVision=true인 프로바이더만 후보
 * - ToolTask → hasToolCalling=true인 프로바이더만 후보
 */
class AIResourceRegistry(
    private val tokenBudgetTracker: TokenBudgetTracker? = null,
    private val remoteLLMPool: RemoteLLMPool? = null,
    private val aiCallGateway: AICallGateway? = null,
    private val valueGatekeeper: ValueGatekeeper? = null,
    private val storyPhaseController: com.xreal.nativear.storyteller.IStoryPhaseGate? = null
) : IAICallService {
    companion object {
        private const val TAG = "AIResourceRegistry"
    }

    // ── 프로바이더 능력 선언 ──

    data class ProviderCapability(
        val providerId: ProviderId,
        val provider: IAIProvider,
        val tier: ProviderTier,
        val hasVision: Boolean,
        val hasToolCalling: Boolean,
        val costPerToken: Float,       // $0 = 무료
        val qualityRank: Int,          // 높을수록 좋음 (1-10)
        val visionSender: (suspend (Bitmap, String, String?) -> AIResponse)? = null
    )

    enum class ProviderTier(val priority: Int, val label: String) {
        REMOTE(0, "리모트"),    // $0, Tailscale VPN
        CLOUD(1, "서버"),       // $, API key
        EDGE(2, "엣지")         // $0, on-device llama.cpp
    }

    // ── 등록된 프로바이더 ──
    private val capabilities = mutableListOf<ProviderCapability>()

    fun register(cap: ProviderCapability) {
        capabilities.add(cap)
        Log.d(TAG, "등록: ${cap.providerId} (${cap.tier.label}, vision=${cap.hasVision}, tools=${cap.hasToolCalling})")
    }

    fun getRegisteredCount() = capabilities.size

    // ── 라우팅 결과 ──

    data class RouteResult(
        val response: AIResponse,
        val providerId: ProviderId,
        val tier: ProviderTier,
        val label: String,       // UI 표시용: [리모트], [서버], [엣지] 등
        val latencyMs: Long
    )

    // ── 텍스트 라우팅 ──

    /**
     * 텍스트 AI 호출 — 통합 cascade 라우팅.
     * 리모트 → 서버 → 엣지 순서로 자동 폴백.
     */
    override suspend fun routeText(
        messages: List<AIMessage>,
        systemPrompt: String?,
        tools: List<AIToolDefinition>,
        temperature: Float?,
        maxTokens: Int?,
        isEssential: Boolean,
        callPriority: AICallGateway.CallPriority,
        visibility: AICallGateway.VisibilityIntent,
        intent: String
    ): RouteResult? {
        // ★ 상태 머신 0차 게이트: DORMANT/SLEEPING이면 AI 호출 자체 차단
        // (USER_COMMAND/SAFETY는 예외 — 사용자 의도/안전 최우선)
        val effectivePriority = if (isEssential) AICallGateway.CallPriority.SAFETY else callPriority
        storyPhaseController?.let { ctrl ->
            val phase = ctrl.currentPhase
            if (!phase.allowsAICalls &&
                effectivePriority != AICallGateway.CallPriority.USER_COMMAND &&
                effectivePriority != AICallGateway.CallPriority.SAFETY
            ) {
                Log.d(TAG, "routeText 상태 머신 차단: phase=$phase, intent=$intent")
                return null
            }
        }

        // ★ AICallGateway 중앙 게이트 체크
        var gateBlocked = false
        aiCallGateway?.let { gateway ->
            val gateDecision = gateway.checkGate(
                priority = effectivePriority,
                visibility = visibility,
                estimatedTokens = maxTokens ?: 500,
                intent = intent
            )
            if (!gateDecision.allowed) {
                Log.w(TAG, "routeText 게이트 차단 → 엣지 폴백: ${gateDecision.reason}")
                gateBlocked = true  // 클라우드 차단, 엣지만 시도
            } else if (effectivePriority == AICallGateway.CallPriority.PROACTIVE) {
                gateway.onProactiveCallStart()
            }
        }

        val needsTools = tools.isNotEmpty()
        val candidates = if (gateBlocked) {
            // 게이트 차단 시: 무료 tier(엣지/리모트)만 후보
            selectCandidates(hasVision = false, hasTools = needsTools, isEssential = isEssential)
                .filter { it.tier == ProviderTier.EDGE || it.tier == ProviderTier.REMOTE }
        } else {
            selectCandidates(hasVision = false, hasTools = needsTools, isEssential = isEssential)
        }

        for (cap in candidates) {
            try {
                val startMs = System.currentTimeMillis()
                val toolsForProvider = if (cap.hasToolCalling) tools else emptyList()

                val timeoutMs = getTimeoutMs(cap.tier)
                val response = withTimeoutOrNull(timeoutMs) {
                    cap.provider.sendMessage(messages, systemPrompt, toolsForProvider, temperature, maxTokens)
                } ?: continue

                val reply = response.text?.trim()
                if (reply.isNullOrBlank() || isErrorResponse(reply)) {
                    Log.w(TAG, "routeText: ${cap.providerId} 응답 불량 → 다음 후보")
                    recordFailure(cap)
                    continue
                }

                val latency = System.currentTimeMillis() - startMs
                recordSuccess(cap, latency)
                recordBudget(cap, reply)

                val result = RouteResult(
                    response = response,
                    providerId = cap.providerId,
                    tier = cap.tier,
                    label = tierLabel(cap.tier),
                    latencyMs = latency
                )
                // ★ ValueGatekeeper: 결과 기록 (가시성 이력 축적)
                valueGatekeeper?.recordOutcome(
                    intent = intent,
                    wasUserFacing = visibility == AICallGateway.VisibilityIntent.USER_FACING
                )
                if (callPriority == AICallGateway.CallPriority.PROACTIVE) {
                    aiCallGateway?.onProactiveCallEnd()
                }
                return result
            } catch (e: CancellationException) {
                if (callPriority == AICallGateway.CallPriority.PROACTIVE) {
                    aiCallGateway?.onProactiveCallEnd()
                }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "routeText: ${cap.providerId} 실패 → 다음: ${e.message}")
                recordFailure(cap)
            }
        }

        if (callPriority == AICallGateway.CallPriority.PROACTIVE) {
            aiCallGateway?.onProactiveCallEnd()
        }
        Log.e(TAG, "routeText: 모든 프로바이더 실패 (후보 ${candidates.size}개)")
        return null
    }

    // ── 비전 라우팅 ──

    /**
     * 비전/씬 해석 AI 호출 — 통합 cascade 라우팅.
     *
     * ## 우선순위 정책
     * 1. 리모트 텍스트($0) — OCR hint 있으면 텍스트로 장면 요약 (비용 $0)
     * 2. 서버 비전($) — isEssential=true일 때만 (비용 발생)
     * 3. 엣지 텍스트($0) — OCR hint 있으면 텍스트 폴백
     *
     * 서버 API는 씬 해석 같은 비필수 호출에 사용하지 않음.
     */
    override suspend fun routeVision(
        bitmap: Bitmap,
        textPrompt: String,
        systemPrompt: String?,
        ocrHint: String?,
        isEssential: Boolean,
        callPriority: AICallGateway.CallPriority,
        visibility: AICallGateway.VisibilityIntent,
        intent: String
    ): RouteResult? {
        // ★ AICallGateway 중앙 게이트 체크
        var gateBlocked = false
        aiCallGateway?.let { gateway ->
            val priority = if (isEssential) AICallGateway.CallPriority.SAFETY else callPriority
            val gateDecision = gateway.checkGate(
                priority = priority,
                visibility = visibility,
                estimatedTokens = 1000,  // 비전은 기본 1000 토큰 추정
                intent = intent
            )
            if (!gateDecision.allowed) {
                Log.w(TAG, "routeVision 게이트 차단 → 무료 tier 폴백: ${gateDecision.reason}")
                gateBlocked = true  // 클라우드 차단, 리모트/엣지만 시도
            }
        }

        // ── 1단계: 텍스트 전용 프로바이더 (리모트 $0, OCR hint 기반) ──
        if (!ocrHint.isNullOrBlank()) {
            val textCandidates = selectCandidates(hasVision = false, hasTools = false, isEssential = false)
                .filter { it.tier == ProviderTier.REMOTE }  // 리모트($0)만
            for (cap in textCandidates) {
                try {
                    val startMs = System.currentTimeMillis()
                    val timeoutMs = getTimeoutMs(cap.tier)
                    val textPromptWithOcr = "화면 텍스트: ${ocrHint.take(500)}\n\n$textPrompt"

                    val response = withTimeoutOrNull(timeoutMs) {
                        cap.provider.sendMessage(
                            messages = listOf(AIMessage("user", textPromptWithOcr)),
                            systemPrompt = systemPrompt
                        )
                    } ?: continue

                    val reply = response.text?.trim()
                    if (reply.isNullOrBlank() || isErrorResponse(reply)) {
                        Log.w(TAG, "routeVision: ${cap.providerId} 텍스트 응답 불량 → 다음")
                        recordFailure(cap)
                        continue
                    }

                    val latency = System.currentTimeMillis() - startMs
                    recordSuccess(cap, latency)
                    recordBudget(cap, reply)
                    Log.i(TAG, "routeVision: ${cap.providerId} 리모트 텍스트 성공 (${latency}ms)")

                    return RouteResult(
                        response = response,
                        providerId = cap.providerId,
                        tier = cap.tier,
                        label = tierLabel(cap.tier),
                        latencyMs = latency
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "routeVision: ${cap.providerId} 실패 → 다음: ${e.message}")
                    recordFailure(cap)
                }
            }
        }

        // ── 2단계: 비전 프로바이더 (서버 $, isEssential일 때만, 게이트 차단 시 스킵) ──
        if (isEssential && !gateBlocked) {
            val visionCandidates = selectCandidates(hasVision = true, hasTools = false, isEssential = true)
            for (cap in visionCandidates) {
                try {
                    val startMs = System.currentTimeMillis()
                    val timeoutMs = getTimeoutMs(cap.tier, isVision = true)

                    val response = withTimeoutOrNull(timeoutMs) {
                        when {
                            cap.visionSender != null -> cap.visionSender.invoke(bitmap, textPrompt, systemPrompt)
                            cap.provider is OpenAICompatibleProvider -> {
                                cap.provider.sendVisionMessage(bitmap, textPrompt, systemPrompt)
                            }
                            cap.provider is GeminiProvider -> {
                                cap.provider.sendVisionMessage(textPrompt, bitmap)
                            }
                            else -> null
                        }
                    } ?: continue

                    val reply = response.text?.trim()
                    if (reply.isNullOrBlank() || isErrorResponse(reply)) {
                        Log.w(TAG, "routeVision: ${cap.providerId} 비전 응답 불량 → 다음")
                        recordFailure(cap)
                        continue
                    }

                    val latency = System.currentTimeMillis() - startMs
                    recordSuccess(cap, latency)
                    recordBudget(cap, reply)

                    return RouteResult(
                        response = response,
                        providerId = cap.providerId,
                        tier = cap.tier,
                        label = tierLabel(cap.tier).removeSuffix("]") + "비전]",
                        latencyMs = latency
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "routeVision: ${cap.providerId} 비전 실패 → 다음: ${e.message}")
                    recordFailure(cap)
                }
            }
        }

        // ── 3단계: 엣지 텍스트 폴백 ($0, OCR hint 기반) ──
        if (!ocrHint.isNullOrBlank()) {
            val edgeCandidates = selectCandidates(hasVision = false, hasTools = false, isEssential = false)
                .filter { it.tier == ProviderTier.EDGE }
            for (cap in edgeCandidates) {
                try {
                    val startMs = System.currentTimeMillis()
                    val timeoutMs = getTimeoutMs(cap.tier)

                    val response = withTimeoutOrNull(timeoutMs) {
                        cap.provider.sendMessage(
                            messages = listOf(AIMessage("user", "화면 텍스트: ${ocrHint.take(300)}\n한 문장으로 요약:")),
                            systemPrompt = systemPrompt
                        )
                    } ?: continue

                    val reply = response.text?.trim()
                    if (reply.isNullOrBlank() || isErrorResponse(reply)) continue

                    val latency = System.currentTimeMillis() - startMs
                    recordSuccess(cap, latency)

                    return RouteResult(
                        response = response,
                        providerId = cap.providerId,
                        tier = cap.tier,
                        label = tierLabel(cap.tier),
                        latencyMs = latency
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "routeVision: ${cap.providerId} 엣지 실패: ${e.message}")
                    recordFailure(cap)
                }
            }
        }

        Log.e(TAG, "routeVision: 모든 프로바이더 실패 (ocrHint=${!ocrHint.isNullOrBlank()}, isEssential=$isEssential)")
        return null
    }

    // ── 후보 선택 (예산 + 능력 기반) ──

    private fun selectCandidates(
        hasVision: Boolean,
        hasTools: Boolean,
        isEssential: Boolean
    ): List<ProviderCapability> {
        val budgetTier = tokenBudgetTracker?.getBudgetTier(ProviderId.GEMINI) ?: BudgetTier.NORMAL
        val allowCloud = isEssential || budgetTier.ordinal < BudgetTier.MINIMAL.ordinal

        // ★ 정책 기반 tier 활성화 필터 — PolicyRegistry에서 실시간 읽기
        val cloudEnabled = PolicyReader.getBoolean("resource.cloud_enabled", false)
        val remoteEnabled = PolicyReader.getBoolean("resource.remote_enabled", true)
        val edgeEnabled = PolicyReader.getBoolean("resource.edge_enabled", true)

        return capabilities
            .filter { cap ->
                // ★ 정책 필터: tier 단위 on/off (최우선)
                when (cap.tier) {
                    ProviderTier.CLOUD -> if (!cloudEnabled) return@filter false
                    ProviderTier.REMOTE -> if (!remoteEnabled) return@filter false
                    ProviderTier.EDGE -> if (!edgeEnabled) return@filter false
                }
                // 능력 필터
                if (hasVision && !cap.hasVision) return@filter false
                if (hasTools && !cap.hasToolCalling) return@filter false
                // 가용성 필터
                if (!cap.provider.isAvailable && cap.tier != ProviderTier.EDGE) return@filter false
                // 예산 필터: 서버는 예산 상태에 따라 제외
                if (cap.tier == ProviderTier.CLOUD && !allowCloud) return@filter false
                true
            }
            .sortedWith(compareBy<ProviderCapability> {
                it.tier.priority  // 리모트(0) → 서버(1) → 엣지(2)
            }.thenByDescending {
                it.qualityRank    // 같은 tier 내에서 품질순
            })
    }

    // ── 헬퍼 ──

    private fun tierLabel(tier: ProviderTier): String = when (tier) {
        ProviderTier.REMOTE -> "[리모트]"
        ProviderTier.CLOUD -> "[서버]"
        ProviderTier.EDGE -> "[엣지]"
    }

    private fun getTimeoutMs(tier: ProviderTier, isVision: Boolean = false): Long {
        val base = when (tier) {
            ProviderTier.REMOTE -> PolicyReader.getLong("ai.remote_timeout_ms", 30_000L)
            ProviderTier.CLOUD -> PolicyReader.getLong("ai.cloud_timeout_ms", 30_000L)
            ProviderTier.EDGE -> PolicyReader.getLong("ai.edge_timeout_ms", 90_000L)
        }
        return if (isVision) (base * 1.5).toLong() else base
    }

    private fun isErrorResponse(text: String): Boolean {
        return text.startsWith("Error:") ||
                text.contains("Something unexpected happened", ignoreCase = true) ||
                text.contains("API error", ignoreCase = true) ||
                text.contains("응답 없음") ||
                text.contains("응답 생성 실패")
    }

    private fun recordSuccess(cap: ProviderCapability, latencyMs: Long) {
        if (cap.tier == ProviderTier.REMOTE) {
            remoteLLMPool?.recordSuccess(cap.providerId, latencyMs)
        }
    }

    private fun recordFailure(cap: ProviderCapability) {
        if (cap.tier == ProviderTier.REMOTE) {
            remoteLLMPool?.recordFailure(cap.providerId)
        }
    }

    private fun recordBudget(cap: ProviderCapability, reply: String) {
        val tokens = (reply.length / 4).coerceAtLeast(100)
        tokenBudgetTracker?.recordUsage(cap.providerId, tokens)
    }

    // ── 간편 호출 (기존 aiProvider.sendMessage() 대체용) ──

    /**
     * 단순 텍스트 AI 호출 — AIResponse만 반환 (cascade 라우팅 내부 처리).
     * 기존 `aiProvider.sendMessage()` 호출을 1:1 교체 가능.
     * 모든 프로바이더 실패 시 null 반환.
     */
    override suspend fun quickText(
        messages: List<AIMessage>,
        systemPrompt: String?,
        tools: List<AIToolDefinition>,
        temperature: Float?,
        maxTokens: Int?,
        isEssential: Boolean,
        callPriority: AICallGateway.CallPriority,
        visibility: AICallGateway.VisibilityIntent,
        intent: String
    ): AIResponse? {
        return routeText(messages, systemPrompt, tools, temperature, maxTokens, isEssential,
            callPriority, visibility, intent)?.response
    }

    /** 등록된 프로바이더 상태 요약 (디버그/HUD 용) */
    override fun getStatusSummary(): String = buildString {
        val grouped = capabilities.groupBy { it.tier }
        for ((tier, caps) in grouped) {
            val available = caps.filter { it.provider.isAvailable || it.tier == ProviderTier.EDGE }
            append("${tier.label}: ${available.size}/${caps.size} ")
        }
    }.trim()
}
