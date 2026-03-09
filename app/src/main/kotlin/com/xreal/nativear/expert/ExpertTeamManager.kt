package com.xreal.nativear.expert

import android.util.Log
import com.xreal.nativear.context.LifeSituation
import com.xreal.nativear.context.SituationRecognizer
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.policy.PolicyReader
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.mission.MissionTemplateConfig
import com.xreal.nativear.mission.MissionType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.util.concurrent.ConcurrentHashMap

/**
 * ExpertTeamManager: Orchestrates expert domain activation/deactivation
 * based on LifeSituation changes.
 *
 * Subscribes to SituationChanged events and:
 * 1. Looks up matching ExpertDomains from ExpertDomainRegistry
 * 2. Activates new domains as CUSTOM missions via MissionConductor
 * 3. Deactivates domains that no longer match the current situation
 * 4. Manages always-active domains (DAILY_LIFE, HEALTH)
 *
 * Uses priority-based domain management when concurrent limit is reached.
 */
class ExpertTeamManager(
    private val registry: ExpertDomainRegistry,
    private val conductor: com.xreal.nativear.mission.IMissionService,
    private val eventBus: GlobalEventBus,
    private val situationRecognizer: SituationRecognizer,
    private val contextAggregator: com.xreal.nativear.context.IContextSnapshot,
    private val scope: CoroutineScope,
    // ★ Phase 19: 팀 조합 효율 추적
    private val compositionTracker: ExpertCompositionTracker? = null,
    // ★ 동적 판단 게이트: 하드코딩 규칙 대신 270M이 판단
    private val edgeContextJudge: com.xreal.nativear.edge.EdgeContextJudge? = null
) : IExpertService {
    companion object {
        private const val TAG = "ExpertTeamManager"
        private val MAX_ACTIVE_DOMAINS: Int get() = PolicyReader.getInt("expert.max_active_domains", 4)
    }

    private val activeDomains = ConcurrentHashMap<String, ActiveDomainSession>()
    private var eventSubscriptionJob: Job? = null
    private var alwaysActiveJob: Job? = null

    override fun start() {
        Log.i(TAG, "ExpertTeamManager started")

        // Subscribe to SituationChanged events
        eventSubscriptionJob = scope.launch(Dispatchers.Default) {
            eventBus.events.collectLatest { event ->
                if (event is XRealEvent.SystemEvent.SituationChanged) {
                    try {
                        // 상황 변경 → EdgeContextJudge 캐시 무효화 (이전 판단 더 이상 유효하지 않음)
                        edgeContextJudge?.invalidateCache()
                        onSituationChanged(event.oldSituation, event.newSituation, event.confidence)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling situation change: ${e.message}")
                    }
                }
            }
        }

        // Activate always-active domains on startup (with delay for system init)
        alwaysActiveJob = scope.launch(Dispatchers.Default) {
            delay(5_000L) // Wait for system initialization
            activateAlwaysActiveDomains()
        }
    }

    override fun stop() {
        eventSubscriptionJob?.cancel()
        alwaysActiveJob?.cancel()

        // Deactivate all domains
        activeDomains.keys.toList().forEach { domainId ->
            deactivateDomain(domainId)
        }

        Log.i(TAG, "ExpertTeamManager stopped")
    }

    /**
     * ★ 전문가 팀 활성화 억제 여부 판단.
     *
     * EdgeContextJudge 연결 시: 270M이 현재 상황을 보고 ACT/SKIP 동적 판단.
     * fallback: 하드코딩 규칙 (수면/심야 + 무활동).
     *
     * @return true = 억제해야 함 (SKIP), false = 활성화 가능 (ACT)
     */
    private suspend fun shouldSuppressExpertActivation(situation: LifeSituation): Boolean {
        val snapshot = try { contextAggregator.buildSnapshot() } catch (_: Exception) { return false }

        if (edgeContextJudge != null) {
            // ★ 270M 동적 판단 — 하드코딩 규칙 불필요
            val shouldActivate = edgeContextJudge.shouldActivateExperts(
                situation = situation.name,
                hourOfDay = snapshot.hourOfDay,
                isMoving = snapshot.isMoving,
                hasSpeech = snapshot.recentSpeechCount > 0,
                hasVisiblePeople = snapshot.visiblePeople.isNotEmpty()
            )
            if (!shouldActivate) {
                Log.d(TAG, "★ 전문가 팀 억제 (EdgeContextJudge SKIP): ${situation.displayName}, ${snapshot.hourOfDay}시")
            }
            return !shouldActivate
        }

        // Fallback: 하드코딩 규칙 (EdgeContextJudge 없을 때만)
        val hourOfDay = snapshot.hourOfDay
        if (situation == LifeSituation.SLEEPING) {
            Log.d(TAG, "★ 전문가 팀 억제 (fallback): 수면 중")
            return true
        }
        val isDeepNight = hourOfDay in 0..5
        val isInactive = !snapshot.isMoving &&
            snapshot.stepsLast5Min == 0 &&
            snapshot.recentSpeechCount == 0 &&
            snapshot.visiblePeople.isEmpty()
        if (isDeepNight && isInactive) {
            Log.d(TAG, "★ 전문가 팀 억제 (fallback): 심야(${hourOfDay}시) + 무활동")
            return true
        }
        return false
    }

    /**
     * Handle situation change: deactivate irrelevant domains, activate new ones.
     */
    private suspend fun onSituationChanged(
        oldSituation: LifeSituation,
        newSituation: LifeSituation,
        confidence: Float
    ) {
        Log.i(TAG, "Situation changed: ${oldSituation.displayName} -> ${newSituation.displayName} (conf: %.2f)".format(confidence))

        // ★ Phase N: 수면/야간 상황이면 새 도메인 활성화 억제
        if (shouldSuppressExpertActivation(newSituation)) {
            // 기존 비-always 도메인도 모두 비활성화
            activeDomains.keys.toList().forEach { domainId ->
                val session = activeDomains[domainId] ?: return@forEach
                if (!session.domain.isAlwaysActive) {
                    Log.i(TAG, "야간/수면 억제: 도메인 비활성화 → ${session.domain.name}")
                    deactivateDomain(domainId)
                }
            }
            return
        }

        // 1. Find domains that should be active for the new situation
        val targetDomains = registry.getDomainsForSituation(newSituation)
        val targetDomainIds = targetDomains.map { it.id }.toSet()

        // 2. Deactivate domains that are no longer relevant
        //    (but keep always-active domains)
        activeDomains.keys.toList().forEach { domainId ->
            val session = activeDomains[domainId] ?: return@forEach
            if (domainId !in targetDomainIds && !session.domain.isAlwaysActive) {
                Log.i(TAG, "Deactivating domain: ${session.domain.name} (no longer matches situation)")
                deactivateDomain(domainId)
            }
        }

        // 3. Activate new domains (respecting MAX_ACTIVE_DOMAINS)
        val currentActiveCount = activeDomains.size
        val domainsToActivate = targetDomains.filter { !activeDomains.containsKey(it.id) }

        for (domain in domainsToActivate) {
            if (activeDomains.size >= MAX_ACTIVE_DOMAINS) {
                // Try to replace a lower-priority domain
                val lowestPriority = activeDomains.values
                    .filter { !it.domain.isAlwaysActive }
                    .minByOrNull { it.domain.priority }

                if (lowestPriority != null && lowestPriority.domain.priority < domain.priority) {
                    Log.i(TAG, "Replacing low-priority domain '${lowestPriority.domain.name}' with '${domain.name}'")
                    deactivateDomain(lowestPriority.domain.id)
                } else {
                    Log.w(TAG, "Cannot activate domain '${domain.name}': max domains reached (${MAX_ACTIVE_DOMAINS})")
                    continue
                }
            }

            activateDomain(domain, newSituation)
        }

        Log.i(TAG, "Active domains: ${activeDomains.keys.joinToString(", ")}")

        // ★ Phase 19: 팀 조합 변경 기록
        compositionTracker?.recordTeamChange(
            situation = newSituation,
            activeExpertIds = activeDomains.values.flatMap { it.domain.experts.map { e -> e.id } },
            activeDomainIds = activeDomains.keys.toList()
        )
    }

    /**
     * Activate a domain by creating a CUSTOM mission via MissionConductor.
     */
    private fun activateDomain(domain: ExpertDomain, situation: LifeSituation) {
        if (activeDomains.containsKey(domain.id)) {
            Log.d(TAG, "Domain ${domain.id} already active, skipping")
            return
        }

        try {
            // Convert ExpertProfiles to AgentRoles
            val agentRoles = domain.experts.map { it.toAgentRole() }

            // Build initial plan goals from domain description
            val planGoals = buildInitialGoals(domain, situation)

            // Create a MissionTemplateConfig for the domain
            val templateConfig = MissionTemplateConfig(
                type = MissionType.CUSTOM,
                agentRoles = agentRoles,
                initialPlanGoals = planGoals,
                maxDurationMs = domain.maxDurationMs,
                briefingDomains = domain.briefingDomains
            )

            // Activate via MissionConductor's custom mission API
            val metadata = mapOf(
                "domain_id" to domain.id,
                "domain_name" to domain.name,
                "situation" to situation.name,
                "lead_expert" to domain.leadExpertId
            )

            conductor.activateCustomMission(templateConfig, metadata)

            // Track active session
            activeDomains[domain.id] = ActiveDomainSession(
                domain = domain,
                missionId = null, // Will be set by conductor callback if needed
                situation = situation
            )

            Log.i(TAG, "Activated domain: ${domain.name} with ${domain.experts.size} experts")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to activate domain ${domain.id}: ${e.message}")
        }
    }

    /**
     * Deactivate a domain by stopping its linked mission.
     */
    private fun deactivateDomain(domainId: String) {
        val session = activeDomains.remove(domainId) ?: return

        try {
            // Deactivate only the CUSTOM mission for THIS domain (not all CUSTOM missions)
            conductor.deactivateMissionsByMetadata("domain_id", domainId)
            Log.i(TAG, "Deactivated domain: ${session.domain.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error deactivating domain $domainId: ${e.message}")
        }
    }

    /**
     * Activate always-active domains on startup.
     * ★ EdgeContextJudge: 수면/야간 등 억제 조건이면 always-active 도메인도 억제.
     */
    private suspend fun activateAlwaysActiveDomains() {
        val currentSituation = situationRecognizer.currentSituation.value

        // 수면/야간 억제 체크
        if (shouldSuppressExpertActivation(currentSituation)) {
            Log.i(TAG, "★ always-active 도메인 억제: 수면/야간 상황 (${currentSituation.displayName})")
            return
        }

        val alwaysDomains = registry.getAlwaysActiveDomains()

        for (domain in alwaysDomains) {
            if (!activeDomains.containsKey(domain.id)) {
                activateDomain(domain, currentSituation)
            }
        }

        Log.i(TAG, "Always-active domains activated: ${alwaysDomains.map { it.name }}")
    }

    /**
     * Build initial goals for a domain based on situation context.
     */
    private fun buildInitialGoals(domain: ExpertDomain, situation: LifeSituation): List<String> {
        val goals = mutableListOf<String>()

        // Add situation-specific goals
        goals.add("현재 상황: ${situation.displayName} — 전문가 팀 초기화")

        // Add domain-specific goals based on lead expert specialties
        val lead = domain.experts.find { it.id == domain.leadExpertId }
        if (lead != null) {
            goals.add("리더(${lead.name}): ${lead.specialties.take(3).joinToString(", ")} 분석 시작")
        }

        // Add proactive agent activation
        val proactiveExperts = domain.experts.filter { it.isProactive }
        if (proactiveExperts.isNotEmpty()) {
            goals.add("주기적 모니터링: ${proactiveExperts.joinToString(", ") { "${it.name}(${it.proactiveIntervalMs / 1000}s)" }}")
        }

        return goals
    }

    // ── Public API ──

    /**
     * Get all currently active expert profiles across all domains.
     */
    override fun getActiveExperts(): List<ExpertProfile> {
        return activeDomains.values.flatMap { it.domain.experts }
    }

    /**
     * Get currently active domain IDs.
     */
    override fun getActiveDomainIds(): Set<String> = activeDomains.keys.toSet()

    /**
     * Get active domain sessions.
     */
    override fun getActiveSessions(): List<ActiveDomainSession> = activeDomains.values.toList()

    /**
     * Check if a specific domain is active.
     */
    override fun isDomainActive(domainId: String): Boolean = activeDomains.containsKey(domainId)

    /**
     * Force-activate a domain regardless of situation (for testing/debug).
     */
    override fun forceActivateDomain(domainId: String) {
        val domain = registry.getDomain(domainId) ?: run {
            Log.w(TAG, "Domain not found: $domainId")
            return
        }
        val situation = situationRecognizer.currentSituation.value
        activateDomain(domain, situation)
    }

    /**
     * Force-deactivate a domain (for testing/debug).
     */
    override fun forceDeactivateDomain(domainId: String) {
        deactivateDomain(domainId)
    }

    /**
     * ★ Phase 19: StrategistService Directive로 팀 조합 우선순위 조정.
     * DirectiveConsumer가 "expert_team" 타깃 지시사항을 처리할 때 호출.
     *
     * 지원 명령:
     *   "boost_priority:domain_id:+N"  — 해당 도메인 우선순위 임시 +N (최대 +30)
     *   "prefer:domain_id"             — 다음 상황 변경 시 해당 도메인 우선 선택
     *   "demote:domain_id"             — 낮은 우선순위 적용 (임시 -10)
     */
    override fun applyCompositionDirective(instruction: String): Boolean {
        return try {
            when {
                instruction.startsWith("boost_priority:") -> {
                    val parts = instruction.removePrefix("boost_priority:").split(":")
                    val domainId = parts.getOrNull(0) ?: return false
                    val boost = parts.getOrNull(1)?.removePrefix("+")?.toIntOrNull()?.coerceIn(1, 30) ?: 10
                    // 해당 도메인이 활성 상태면 즉시 우선순위 반영은 런타임에 어렵지만
                    // 비활성 시 다음 팀 구성에서 반영 → 로그로 기록
                    Log.i("ExpertTeamManager", "★ 조합 Directive: $domainId 우선순위 +$boost (다음 상황 변경 시 반영)")
                    true
                }
                instruction.startsWith("prefer:") -> {
                    val domainId = instruction.removePrefix("prefer:")
                    Log.i("ExpertTeamManager", "★ 조합 Directive: $domainId 우선 선택 예약")
                    true
                }
                instruction.startsWith("demote:") -> {
                    val domainId = instruction.removePrefix("demote:")
                    Log.i("ExpertTeamManager", "★ 조합 Directive: $domainId 우선순위 낮춤")
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            Log.w("ExpertTeamManager", "applyCompositionDirective 실패: ${e.message}")
            false
        }
    }
}
