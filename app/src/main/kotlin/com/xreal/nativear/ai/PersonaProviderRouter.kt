package com.xreal.nativear.ai

import android.util.Log
import com.xreal.nativear.edge.ConnectivityMonitor
import com.xreal.nativear.router.persona.TokenBudgetTracker

/**
 * PersonaProviderRouter — 4-Tier AI 협연 라우터.
 *
 * ## 4-Tier 아키텍처
 * | Tier   | Provider            | 품질 | 비용   | 지연     |
 * |--------|---------------------|------|--------|----------|
 * | Cloud  | Gemini/GPT/etc      | ★★★ | $3/day | ~1-3s    |
 * | Remote | LLM Pool (PC+Steam) | ★★☆ | $0     | ~200ms   |
 * | Edge   | Qwen3 1.7B CPU      | ★☆☆ | $0     | ~50ms    |
 *
 * ## Remote LLM Pool (NEW)
 * - PC (gemma-3-12b, 39tok/s) + 스팀덱 (gemma-3-4b, 20tok/s)
 * - 클라우드 API처럼 어레이로 활용: health check + smart routing + failover
 * - 복잡한 작업 → 큰 모델(12B), 단순 작업 → 작은 모델(4B) 자동 분배
 *
 * ## 라우팅 흐름
 * - 예산 정상: Remote Pool → Cloud → Edge
 * - 예산 주의: Remote Pool → Cloud(최소) → Edge
 * - 예산 위험: Remote Pool → Edge (클라우드 차단)
 * - 오프라인: Remote Pool(Tailscale) → Edge
 *
 * Edge LLM = 최후의 보루 (Remote Pool 전체 + Cloud 전체 불가 시에만)
 */
class PersonaProviderRouter(
    private val providers: Map<ProviderId, IAIProvider>,
    private val tokenBudgetTracker: TokenBudgetTracker? = null,
    private val connectivityMonitor: ConnectivityMonitor? = null,
    private val remoteLLMPool: RemoteLLMPool? = null
) {
    private val TAG = "PersonaProviderRouter"

    companion object {
        // 클라우드 프로바이더 우선순위
        private val CLOUD_CHAIN = listOf(
            ProviderId.GEMINI, ProviderId.OPENAI, ProviderId.GROK, ProviderId.CLAUDE
        )
        // 엣지 fallback (최후의 보루)
        private val EDGE_CHAIN = listOf(
            ProviderId.EDGE_AGENT, ProviderId.EDGE_EMERGENCY
        )
    }

    /**
     * Persona에 최적의 ProviderId를 반환.
     *
     * Remote LLM Pool이 가용하면 항상 우선 사용 ($0, 충분한 품질).
     * Pool 내 서버 선택은 RemoteLLMPool이 복잡도/health 기반으로 결정.
     */
    fun resolve(persona: Persona): ProviderId {
        val preferred = persona.providerId
        val isOnline = connectivityMonitor?.isOnline ?: true
        val budgetPercent = tokenBudgetTracker?.getBudgetUsagePercent() ?: 0f

        // ★ 오프라인: Remote Pool(Tailscale는 VPN이므로 가능할 수 있음) → Edge
        if (!isOnline) {
            // Tailscale VPN은 인터넷 없어도 동작 가능
            val remoteServer = remoteLLMPool?.pickServer(estimateComplexity(persona))
            if (remoteServer != null) {
                Log.i(TAG, "[Offline+VPN] ${persona.id}: Remote LLM ${remoteServer.providerId}")
                return remoteServer.providerId
            }
            return resolveEdge(persona) ?: preferred
        }

        // ★ 예산 위험 (95%+): Remote Pool → Edge만 (클라우드 차단)
        if (budgetPercent >= 95f) {
            val remoteServer = remoteLLMPool?.pickServer(estimateComplexity(persona))
            if (remoteServer != null) {
                Log.i(TAG, "[BudgetCritical] ${persona.id}: Remote LLM ${remoteServer.providerId} (\$0)")
                return remoteServer.providerId
            }
            return resolveEdge(persona) ?: preferred
        }

        // ★ 예산 주의 (80%+): Remote Pool → Cloud(최소) → Edge
        if (budgetPercent >= 80f) {
            val remoteServer = remoteLLMPool?.pickServer(estimateComplexity(persona))
            if (remoteServer != null) {
                Log.i(TAG, "[BudgetSaver] ${persona.id}: Remote LLM ${remoteServer.providerId} (예산 ${budgetPercent.toInt()}%)")
                return remoteServer.providerId
            }
            // Remote 불가 → 클라우드 최소 모드
            for (fallback in CLOUD_CHAIN) {
                if (canUse(fallback, persona.maxTokens)) {
                    Log.w(TAG, "[BudgetTight] ${persona.id}: Remote 불가 → Cloud $fallback (${budgetPercent.toInt()}%)")
                    return fallback
                }
            }
            return resolveEdge(persona) ?: preferred
        }

        // ★ 예산 정상 (<80%): Remote Pool 최우선 → Cloud → Edge
        val complexity = estimateComplexity(persona)
        val remoteServer = remoteLLMPool?.pickServer(complexity)
        if (remoteServer != null) {
            if (preferred != remoteServer.providerId) {
                Log.i(TAG, "[RemoteFirst] ${persona.id}: $preferred → ${remoteServer.providerId} " +
                        "(${complexity.name}, \$0)")
            }
            return remoteServer.providerId
        }

        // Remote Pool 전체 불가 → Cloud fallback
        if (canUse(preferred, persona.maxTokens)) return preferred
        for (fallback in CLOUD_CHAIN) {
            if (fallback == preferred) continue
            if (canUse(fallback, persona.maxTokens)) {
                Log.w(TAG, "[CloudFailover] ${persona.id}: Remote 전체 불가, $preferred → $fallback")
                return fallback
            }
        }

        // Cloud + Remote 모두 불가 → Edge (최후의 보루)
        return resolveEdge(persona) ?: preferred
    }

    /**
     * 작업 복잡도 추정 — Remote Pool 라우팅 힌트.
     */
    private fun estimateComplexity(persona: Persona): RemoteLLMPool.TaskComplexity {
        // 페르소나 maxTokens로 복잡도 추정
        return when {
            persona.maxTokens >= 2048 -> RemoteLLMPool.TaskComplexity.COMPLEX
            persona.maxTokens >= 1024 -> RemoteLLMPool.TaskComplexity.MODERATE
            else -> RemoteLLMPool.TaskComplexity.SIMPLE
        }
    }

    private fun resolveEdge(persona: Persona): ProviderId? {
        for (edgeId in EDGE_CHAIN) {
            if (canUse(edgeId, persona.maxTokens)) {
                Log.w(TAG, "[EdgeLastResort] ${persona.id}: Cloud+Remote 전체 불가 → $edgeId")
                return edgeId
            }
        }
        Log.e(TAG, "[AllBlocked] ${persona.id}: 모든 AI 불가")
        return null
    }

    private fun canUse(providerId: ProviderId, estimatedTokens: Int): Boolean {
        val provider = providers[providerId] ?: return false
        if (!provider.isAvailable) return false
        val budget = tokenBudgetTracker?.checkBudget(providerId, estimatedTokens = estimatedTokens)
        return budget?.allowed != false
    }

    fun getStatusReport(): String = buildString {
        val isOnline = connectivityMonitor?.isOnline ?: true
        appendLine("네트워크: ${if (isOnline) connectivityMonitor?.networkType ?: "ONLINE" else "OFFLINE"}")

        // Remote LLM Pool
        remoteLLMPool?.let {
            appendLine(it.getStatusReport())
        }

        appendLine("[클라우드]")
        for (id in CLOUD_CHAIN) {
            val p = providers[id] ?: continue
            val tier = tokenBudgetTracker?.getBudgetTier(id)?.displayName ?: "N/A"
            appendLine("  $id: available=${p.isAvailable}, budget=$tier")
        }
        appendLine("[엣지 (최후의 보루)]")
        for (id in EDGE_CHAIN) {
            val p = providers[id] ?: continue
            appendLine("  $id: available=${p.isAvailable}")
        }
    }.trimEnd()
}
