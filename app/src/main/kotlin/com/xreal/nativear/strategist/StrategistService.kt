package com.xreal.nativear.strategist

import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.ai.IPersonaService
import com.xreal.nativear.ai.PersonaMemoryService
import com.xreal.nativear.core.ErrorReporter
import kotlinx.coroutines.*

class StrategistService(
    private val reflector: StrategistReflector,
    private val directiveStore: DirectiveStore,
    private val personaMemoryService: PersonaMemoryService,
    private val personaManager: IPersonaService,
    private val database: UnifiedMemoryDatabase,
    private val digitalTwinBuilder: com.xreal.nativear.cadence.DigitalTwinBuilder? = null,
    private val expertTeamManager: com.xreal.nativear.expert.IExpertService? = null,
    private val analyticsService: com.xreal.nativear.analytics.SystemAnalyticsService? = null,
    // DirectiveConsumer: 반영 주기 완료 후 지시사항을 실제 컴포넌트에 적용
    private val directiveConsumer: DirectiveConsumer? = null,
    // ★ Phase 19: 전문가 자기진화 시스템
    private val compositionTracker: com.xreal.nativear.expert.ExpertCompositionTracker? = null,
    private val peerRequestStore: com.xreal.nativear.expert.ExpertPeerRequestStore? = null,
    private val peerRequestReviewer: com.xreal.nativear.expert.PeerRequestReviewer? = null,
    // ★ AdaptiveReflectionTrigger — 5분 고정 상수 대체, 이벤트 기반 동적 반성 주기
    private val adaptiveReflectionTrigger: com.xreal.nativear.agent.AdaptiveReflectionTrigger? = null,
    // ★ ProactiveScheduler — 중앙 스케줄러 (자체 while 루프 대체)
    private val proactiveScheduler: com.xreal.nativear.ai.ProactiveScheduler? = null
) {
    private val TAG = "StrategistService"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reflectionJob: Job? = null

    companion object {
        // ★ Policy Department: PolicyRegistry shadow read
        val REFLECTION_INTERVAL_MS: Long get() =
            com.xreal.nativear.policy.PolicyReader.getLong("system.reflection_interval_ms", 5 * 60 * 1000L)
        val INITIAL_DELAY_MS: Long get() =
            com.xreal.nativear.policy.PolicyReader.getLong("system.reflection_initial_delay_ms", 60_000L)

        val BASE_PERSONA_IDS = listOf("vision_analyst", "context_predictor", "safety_monitor", "memory_curator")
        val EVENT_ROLES = listOf("USER", "CAMERA", "WHISPER")
    }

    fun start() {
        // Load persisted directives from last session
        directiveStore.loadFromDatabase()
        digitalTwinBuilder?.loadFromDatabase()
        Log.i(TAG, "Loaded ${directiveStore.getActiveDirectiveCount()} persisted directives")

        // AdaptiveReflectionTrigger: 이벤트 기반 동적 반성 주기 시작
        adaptiveReflectionTrigger?.start()

        // ★ ProactiveScheduler 사용 시: 중앙 스케줄러에 태스크 등록 (자체 while 루프 불필요)
        if (proactiveScheduler != null) {
            proactiveScheduler.register(
                com.xreal.nativear.ai.ProactiveScheduler.ProactiveTask(
                    id = "strategist_reflection",
                    intervalMs = REFLECTION_INTERVAL_MS,
                    priority = com.xreal.nativear.ai.ProactiveScheduler.TaskPriority.LOW,
                    isUserFacing = false,
                    estimatedTokens = 2048,
                    action = {
                        try {
                            val hadChanges = runReflectionCycle()
                            adaptiveReflectionTrigger?.onReflectionComplete(hadChanges)
                        } catch (e: Exception) {
                            ErrorReporter.report(TAG, "Reflection cycle failed", e)
                        }
                    }
                )
            )
            Log.i(TAG, "Strategist reflection registered with ProactiveScheduler")
            return
        }

        // 폴백: ProactiveScheduler 없으면 기존 자체 루프
        reflectionJob = scope.launch {
            delay(INITIAL_DELAY_MS)
            val intervalSource = if (adaptiveReflectionTrigger != null) "adaptive" else "fixed ${REFLECTION_INTERVAL_MS / 1000}s"
            Log.i(TAG, "Strategist reflection loop started (interval: $intervalSource)")

            while (isActive) {
                try {
                    val hadChanges = runReflectionCycle()
                    // AdaptiveReflectionTrigger에 반성 결과 피드백
                    adaptiveReflectionTrigger?.onReflectionComplete(hadChanges)
                } catch (e: Exception) {
                    ErrorReporter.report(TAG, "Reflection cycle failed", e)
                }
                // 동적 간격: AdaptiveReflectionTrigger 있으면 이벤트 기반, 없으면 고정 PolicyReader
                val nextInterval = adaptiveReflectionTrigger?.getNextInterval() ?: REFLECTION_INTERVAL_MS
                Log.d(TAG, "Next reflection in ${nextInterval / 1000}s")
                delay(nextInterval)
            }
        }
    }

    private suspend fun runReflectionCycle(): Boolean {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "Starting reflection cycle...")

        // Rebuild digital twin profile each cycle
        digitalTwinBuilder?.rebuildProfile()

        // 1. Gather persona memories (recent 15 per persona) — dynamic list
        val activePersonaIds = getActivePersonaIds()
        val personaMemories = activePersonaIds.associateWith { personaId ->
            try {
                personaMemoryService.getRecentMemories(personaId, limit = 15)
            } catch (e: Exception) {
                emptyList()
            }
        }

        // 2. Gather router decisions
        val routerDecisions = try {
            database.getNodesByRole("ROUTER", limit = 30)
        } catch (e: Exception) {
            emptyList()
        }

        // 3. Gather recent user/camera/whisper events
        val recentEvents = try {
            database.getRecentNodesByRoles(EVENT_ROLES, limit = 20)
        } catch (e: Exception) {
            emptyList()
        }

        // 4. Gather hit rates
        val hitRates = activePersonaIds.associateWith { personaMemoryService.getHitRate(it) }

        // 5. Get current active directives for context
        val currentDirectives = directiveStore.getAllActiveDirectives()

        // 6. 피드백 루프: OutcomeTracker에서 전략 성과 데이터 수집
        //    이전 지시사항이 실제로 효과가 있었는지 Gemini가 볼 수 있게 전달
        val outcomeContext = try {
            org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.learning.OutcomeTracker>()
                ?.getRecentOutcomeSummary()
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "OutcomeTracker 조회 실패 (피드백 루프): ${e.message}")
            null
        }

        // 6a. ★ 에러 컨텍스트: EmergencyOrchestrator에서 최근 에러 패턴 수집
        //     Gemini가 "최근 30분 내 SERVER_AI_ERROR 3회 발생"을 인지하고
        //     전략적 지시사항(Directive) 생성에 반영할 수 있게 전달
        val errorContext = try {
            org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.resilience.EmergencyOrchestrator>()
                ?.getRecentErrorContext()
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "EmergencyOrchestrator 에러 컨텍스트 조회 실패: ${e.message}")
            null
        }

        // 7a. ★ Phase 19: 전문가 팀 조합 효율 데이터
        val compositionInsights = try {
            compositionTracker?.getCompositionInsights()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "조합 효율 데이터 조회 실패: ${e.message}")
            null
        }

        // 7b. ★ Phase 19: 피어 요청 결과 요약 (효과 측정된 것)
        val peerRequestSummary = try {
            peerRequestStore?.getRecentRequestSummary()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "피어 요청 요약 조회 실패: ${e.message}")
            null
        }

        // 7c. ★ Phase 19: 피크타임이 아닌 경우에만 미결 요청 심사 포함
        //     (달리기/출퇴근/회의 중에는 배치 보류 → 다음 주기로 이월)
        val currentUserState = try {
            org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.cadence.UserStateTracker>()
                ?.state?.value
        } catch (_: Exception) { null }
        val isPeakTime = currentUserState?.let { state ->
            state.name in setOf("RUNNING", "COMMUTING", "IN_MEETING", "INTENSIVE_FOCUS")
        } ?: false

        val pendingPeerRequests = if (!isPeakTime) {
            peerRequestStore?.getPendingRequests() ?: emptyList()
        } else {
            Log.d(TAG, "피크타임($currentUserState) — 피어 요청 심사 이월")
            emptyList()
        }
        val reviewContext = if (pendingPeerRequests.isNotEmpty()) {
            peerRequestReviewer?.buildReviewContext(pendingPeerRequests)
        } else null

        // ★ Phase J: 멀티-AI 조합 효과 분석 (최근 7일)
        val multiAIStatsContext = try {
            val stats = database.getMultiAISessionStats(days = 7)
            if (stats.isNotEmpty()) buildString {
                appendLine("[멀티-AI 가치 분석 (최근 7일)]")
                stats.forEach { stat ->
                    val personas = try {
                        val arr = org.json.JSONArray(stat.personaIds)
                        (0 until arr.length()).map { i -> arr.getString(i) }.joinToString("+")
                    } catch (_: Exception) { stat.personaIds }
                    val helpRate = if (stat.helpfulRate >= 0) "${(stat.helpfulRate * 100).toInt()}% 긍정" else "미측정"
                    appendLine("  $personas: ${stat.totalSessions}회 · $helpRate · 합의 ${String.format("%.2f", stat.avgConsensus)}")
                }
            }.trimEnd() else null
        } catch (e: Exception) {
            Log.w(TAG, "멀티-AI 통계 조회 실패: ${e.message}")
            null
        }

        // ★ Phase M: 도구 사용 통계 — Gemini가 도구 활용 지시사항 생성 가능
        val toolUsageSummary = try {
            org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.tools.ToolUsageTracker>()
                ?.getSummary()
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }

        // ★ Policy Department: 정책 변경 심사 요청 수집
        val policyReviewContext = try {
            org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.policy.PolicyManager>()
                ?.buildReviewContext()
        } catch (e: Exception) {
            Log.w(TAG, "정책 심사 컨텍스트 조회 실패: ${e.message}")
            null
        }

        // ★ Gap C: 이전 지시사항 실행 결과 (DirectiveConsumer → StrategistService 피드백)
        val directiveExecutionContext = directiveConsumer?.lastExecutionResult?.let { result ->
            buildString {
                appendLine("[이전 지시사항 실행 결과]")
                appendLine("  적용: ${result.applied}건, 실패: ${result.failed}건")
                if (result.appliedTargets.isNotEmpty()) {
                    appendLine("  적용 대상: ${result.appliedTargets.distinct().joinToString(", ")}")
                }
            }.trimEnd()
        }

        // outcomeContext + errorContext + 조합 효율 + 피어 요청 결과 + 심사 요청 + 멀티-AI 통계 + 도구 통계 + 정책 심사 + 실행결과 통합
        val fullOutcomeContext = listOfNotNull(
            outcomeContext, errorContext, compositionInsights, peerRequestSummary, reviewContext, multiAIStatsContext,
            toolUsageSummary,  // ★ Phase M
            policyReviewContext,  // ★ Policy Department
            directiveExecutionContext  // ★ Gap C: 지시사항 실행 피드백
        )
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }

        // 8. Reflect
        val newDirectives = reflector.reflect(
            personaMemories = personaMemories,
            routerDecisions = routerDecisions,
            recentEvents = recentEvents,
            hitRates = hitRates,
            previousDirectives = currentDirectives,
            outcomeContext = fullOutcomeContext  // ★ 성과 + 에러 컨텍스트 통합 전달
        )

        if (newDirectives.isNotEmpty()) {
            // Update in-memory store
            directiveStore.updateDirectives(newDirectives)
            // Persist to database
            directiveStore.persistToDatabase(newDirectives)
        }

        // ★ Phase 19: 피어 요청 심사 결과 적용 (Gemini 응답에 peer_reviews 포함된 경우)
        if (pendingPeerRequests.isNotEmpty()) {
            try {
                // reflector의 마지막 응답 원문에서 peer_reviews 섹션 파싱
                peerRequestReviewer?.applyReviewDecisions(
                    reflector.lastRawResponse ?: "",
                    pendingPeerRequests
                )
            } catch (e: Exception) {
                Log.w(TAG, "피어 요청 심사 결과 적용 실패: ${e.message}")
            }
        }

        // ★ Policy Department: Gemini 응답에서 policy_decisions 파싱 후 적용
        if (policyReviewContext != null) {
            try {
                val rawResponse = reflector.lastRawResponse ?: ""
                val policyDecisions = parsePolicyDecisions(rawResponse)
                if (policyDecisions.isNotEmpty()) {
                    org.koin.java.KoinJavaComponent.getKoin()
                        .getOrNull<com.xreal.nativear.policy.PolicyManager>()
                        ?.applyReviewDecisions(policyDecisions)
                }
            } catch (e: Exception) {
                Log.w(TAG, "정책 심사 결과 적용 실패: ${e.message}")
            }
        }

        // Prune expired
        directiveStore.pruneExpired()
        // ★ Phase 19: 동적 프로필 만료 정리
        try {
            org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.expert.ExpertDynamicProfileStore>()
                ?.pruneExpired()
        } catch (_: Exception) { }

        // 10. ★ 핵심: 모든 활성 지시사항을 실제 컴포넌트에 즉시 적용
        //     cadence_controller → CadenceConfig, mission_conductor → MissionConductor,
        //     device_mode → DeviceModeManager, resource → VisionCoordinator
        try {
            directiveConsumer?.consumeAll()
        } catch (e: Exception) {
            Log.w(TAG, "Directive consumption failed: ${e.message}")
        }

        // 10. Edge delegation analysis (once daily, evening)
        if (shouldRunEdgeAnalysis()) {
            try {
                val report = analyticsService?.generateEdgeDelegationReport()
                if (!report.isNullOrBlank()) {
                    Log.i(TAG, "Edge delegation report generated: ${report.take(100)}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Edge analysis failed: ${e.message}")
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        val hadMeaningfulChanges = newDirectives.isNotEmpty()
        Log.i(TAG, "Reflection cycle complete: ${newDirectives.size} new directives, " +
            "${directiveStore.getActiveDirectiveCount()} total active, ${elapsed}ms, " +
            "personas analyzed: ${activePersonaIds.size}")
        return hadMeaningfulChanges
    }

    /**
     * Get dynamic list of persona IDs to analyze.
     * Includes base 4 personas + all active domain expert IDs.
     */
    private fun getActivePersonaIds(): List<String> {
        val domainExperts = try {
            expertTeamManager?.getActiveExperts()?.map { it.id } ?: emptyList()
        } catch (_: Exception) { emptyList() }
        return (BASE_PERSONA_IDS + domainExperts).distinct()
    }

    private var lastEdgeAnalysisDate = ""

    private fun shouldRunEdgeAnalysis(): Boolean {
        val now = java.util.Calendar.getInstance()
        val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(now.time)
        // Run once daily between 18:00-22:00
        return (hour in 18..22 && today != lastEdgeAnalysisDate && analyticsService != null)
            .also { if (it) lastEdgeAnalysisDate = today }
    }

    /**
     * Gemini 응답 원문에서 policy_decisions JSON 배열 파싱.
     * 형식: policy_decisions:[{"key":"cadence.ocr_interval_ms","action":"approve"}]
     */
    private fun parsePolicyDecisions(rawResponse: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val marker = "policy_decisions:"
            val idx = rawResponse.indexOf(marker)
            if (idx < 0) return result

            val jsonStart = rawResponse.indexOf('[', idx)
            val jsonEnd = rawResponse.indexOf(']', jsonStart)
            if (jsonStart < 0 || jsonEnd < 0) return result

            val arr = org.json.JSONArray(rawResponse.substring(jsonStart, jsonEnd + 1))
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val key = obj.optString("key", "")
                val action = obj.optString("action", "")
                if (key.isNotBlank() && action.isNotBlank()) {
                    result[key] = action
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "policy_decisions 파싱 실패 (정상 — 없을 수 있음): ${e.message}")
        }
        return result
    }

    fun release() {
        reflectionJob?.cancel()
        scope.cancel()
        Log.i(TAG, "StrategistService released")
    }
}
