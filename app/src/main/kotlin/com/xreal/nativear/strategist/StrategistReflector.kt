package com.xreal.nativear.strategist

import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.ai.AIMessage
import org.json.JSONArray
import org.json.JSONObject

class StrategistReflector(
    private val aiRegistry: com.xreal.nativear.ai.IAICallService,
    private val directiveStore: DirectiveStore,
    private var cadenceContextProvider: (() -> String)? = null,
    private var digitalTwinContextProvider: (() -> String)? = null,
    private val tokenBudgetTracker: com.xreal.nativear.router.persona.TokenBudgetTracker? = null,
    // ★ Phase L: 싱글톤 PersonaManager — 사용자 컨텍스트(DNA·프로필·기억) 주입용
    private val personaManager: com.xreal.nativear.ai.IPersonaService? = null,
    // ★ Token Economy: 게이트웨이 + 가치판단 통계 주입
    private val aiCallGateway: com.xreal.nativear.ai.AICallGateway? = null,
    private val valueGatekeeper: com.xreal.nativear.ai.ValueGatekeeper? = null
) {
    private val TAG = "StrategistReflector"

    // ★ Phase 19: 마지막 Gemini 응답 원문 (PeerRequestReviewer가 peer_reviews 섹션 파싱용)
    @Volatile var lastRawResponse: String? = null

    companion object {
        const val SYSTEM_PROMPT = """당신은 AR 라이프로그 시스템의 메타 전략가입니다.

4개의 AI 페르소나가 사용자를 돕고 있습니다:
- vision_analyst (Gemini): 시각 정보 분석 (객체, OCR, 장면)
- context_predictor (OpenAI): 위치·시간·패턴 기반 상황 예측
- safety_monitor (Claude): 안전 모니터링, 위험 감지
- memory_curator (Grok): 기억 패턴 분석, 인사이트 생성

추가로 특수 대상에 대한 지시사항을 생성할 수 있습니다:
- cadence_controller: 센서/비전 캡처 주기를 제어하는 시스템 컴포넌트
- mission_conductor: 미션 팀 생성/관리 시스템 (새로운 상황에 맞는 미션 팀을 동적 생성)

당신의 역할:
1. 각 페르소나의 최근 응답과 정확도를 분석하세요
2. 라우터 결정 로그에서 패턴을 찾으세요
3. 사용자의 행동 패턴과 라이프로그 데이터를 관찰하세요
4. 각 페르소나의 성능을 개선할 "지시사항(directives)"을 생성하세요
5. 사용자 상태와 상황에 따라 기록 주기 조절 지시사항을 생성하세요
6. 기존 미션 템플릿(RUNNING_COACH, TRAVEL_GUIDE, EXPLORATION, DAILY_COMMUTE, SOCIAL_ENCOUNTER)으로 대응할 수 없는 새로운 상황이 감지되면 커스텀 미션 생성 지시사항을 생성하세요

출력 형식 (반드시 JSON 배열만 출력):
[
  {
    "target": "persona_id, cadence_controller, 또는 * (전체)",
    "instruction": "구체적인 행동 지시",
    "rationale": "이 지시를 내리는 근거 (관찰된 패턴)",
    "confidence": 0.0-1.0,
    "ttl_minutes": 60
  }
]

cadence_controller 지시사항 형식:
- "cadence:ocr_interval=1000" (OCR 간격 ms)
- "cadence:detect_interval=1000" (객체 감지 간격 ms)
- "cadence:step_threshold=10" (PDR 걸음 임계값)
- "cadence:frame_skip=1" (프레임 스킵)
- "cadence:increase_capture_rate" (전체 캡처율 60% 증가)
- "cadence:decrease_capture_rate" (전체 캡처율 50% 감소)

mission_conductor 지시사항 형식 (커스텀 미션 생성):
- instruction은 반드시 "create_mission:" 접두사 + JSON으로 구성
- 예시: "create_mission:{"mission_name":"study_coach","goals":["학습 진도 추적","집중도 모니터링"],"agents":[{"role_name":"focus_tracker","provider":"GEMINI","system_prompt":"학습 집중도를 모니터링합니다...","tools":["save_structured_data","query_structured_data","get_screen_objects"],"rules":["5분 간격 집중도 체크"]}]}"
- agents 배열: 최대 3개, 각 에이전트에 role_name/provider/system_prompt/tools/rules 필수
- provider: GEMINI, OPENAI, CLAUDE, GROK 중 선택
- 사용 가능한 도구: searchWeb, getWeather, get_screen_objects, take_snapshot, query_visual_memory, get_current_location, query_spatial_memory, query_temporal_memory, query_keyword_memory, query_emotion_memory, save_structured_data, query_structured_data, list_data_domains, get_running_stats, get_running_advice, get_directions

정책(policy) 조정 지시사항 형식:
- target: "policy"
- instruction: "policy:키=값" (예: "policy:gateway.max_calls_per_minute=5")
- 조정 가능한 GATEWAY 정책 키:
  - gateway.max_calls_per_minute (기본 10, 범위 1-60): 분당 최대 AI 호출 수
  - gateway.proactive_budget_cutoff (기본 0.70, 범위 0.1-1.0): PROACTIVE 예산 차단 비율
  - gateway.min_cloud_interval_ms (기본 60000, 범위 10000-600000): 최소 클라우드 호출 간격 ms
  - gateway.budget_scale_50_multiplier (기본 2.0, 범위 1.0-10.0): 예산 50%+ 간격 배율
  - gateway.budget_scale_70_multiplier (기본 4.0, 범위 1.0-20.0): 예산 70%+ 간격 배율
  - gateway.duplicate_window_ms (기본 120000, 범위 30000-600000): 중복 호출 감지 윈도우 ms
  - gateway.min_visibility_rate (기본 0.10, 범위 0.0-1.0): 최소 가시율 (이하면 차단)
  - gateway.min_useful_rate (기본 0.05, 범위 0.0-1.0): 최소 유용율 (이하면 차단)
- 가시율 낮은 intent → 간격 늘리기 또는 차단 임계값 조정
- 예산 소진 빠르면 → cutoff/multiplier 타이트하게
- 사용자 반응 좋으면 → 간격 줄이기, cutoff 완화

규칙:
- 한 사이클당 최대 5개 지시사항
- 구체적이고 실행 가능한 지시만 (모호한 것 금지)
- 이전 지시사항의 효과를 히트율 변화로 추적
- 불필요한 지시는 생성하지 마세요 (데이터 부족 시 빈 배열 반환)"""
    }

    /**
     * Run a reflection cycle: analyze all available data and produce directives.
     */
    /**
     * @param outcomeContext OutcomeTracker.getRecentOutcomeSummary() 결과.
     *   null이면 성과 데이터 없이 기존 동작 유지 (하위 호환).
     *   전달되면 buildContextPrompt에 포함 → Gemini가 "뭐가 통했는지" 보고 지시사항 생성.
     */
    suspend fun reflect(
        personaMemories: Map<String, List<UnifiedMemoryDatabase.MemoryNode>>,
        routerDecisions: List<UnifiedMemoryDatabase.MemoryNode>,
        recentEvents: List<UnifiedMemoryDatabase.MemoryNode>,
        hitRates: Map<String, Float?>,
        previousDirectives: List<Directive>,
        outcomeContext: String? = null   // ← 피드백 루프 핵심 데이터 (OutcomeTracker)
    ): List<Directive> {
        val contextPrompt = buildContextPrompt(
            personaMemories, routerDecisions, recentEvents, hitRates, previousDirectives,
            outcomeContext
        )

        // Skip if there's barely any data to analyze
        if (personaMemories.values.all { it.isEmpty() } && routerDecisions.isEmpty() && recentEvents.isEmpty()) {
            Log.d(TAG, "Insufficient data for reflection, skipping")
            return emptyList()
        }

        // Budget gate
        tokenBudgetTracker?.let { tracker ->
            val check = tracker.checkBudget(com.xreal.nativear.ai.ProviderId.GEMINI, estimatedTokens = 2048)
            if (!check.allowed) {
                Log.w(TAG, "Reflection blocked by budget: ${check.reason}")
                return emptyList()
            }
        }

        // ★ Phase L: 사용자 컨텍스트(DNA·프로필·기억) 추가 — 전략 분석을 사용자 맞춤화
        val contextAddendum = try {
            personaManager?.buildContextAddendum("strategist")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
        val enrichedContextPrompt = if (contextAddendum != null) {
            "$contextPrompt\n\n[사용자 컨텍스트 (분석에 반영할 것)]\n$contextAddendum"
        } else contextPrompt

        return try {
            val response = aiRegistry.quickText(
                messages = listOf(AIMessage(role = "user", content = enrichedContextPrompt)),
                systemPrompt = SYSTEM_PROMPT,
                temperature = 0.4f,
                maxTokens = 2048,
                callPriority = com.xreal.nativear.ai.AICallGateway.CallPriority.PROACTIVE,
                visibility = com.xreal.nativear.ai.AICallGateway.VisibilityIntent.INTERNAL_ONLY,
                intent = "strategist_reflection"
            ) ?: return emptyList()

            val text = response.text ?: return emptyList()
            lastRawResponse = text  // ★ Phase 19: 피어 요청 심사 결과 파싱용 보관
            tokenBudgetTracker?.recordUsage(com.xreal.nativear.ai.ProviderId.GEMINI, (text.length / 4).coerceAtLeast(300))

            // ★ Phase L: 전략 결정을 personaMemory에 저장 → 다음 주기에 자신의 이전 판단 참조
            try {
                val memService = org.koin.java.KoinJavaComponent.getKoin()
                    .getOrNull<com.xreal.nativear.ai.PersonaMemoryService>()
                memService?.savePersonaMemory(
                    personaId = "strategist",
                    content = "전략 결정: ${text.take(300)}",
                    role = "AI"
                )
            } catch (_: Exception) {}

            parseDirectives(text)
        } catch (e: Exception) {
            Log.e(TAG, "Reflection failed: ${e.message}", e)
            emptyList()
        }
    }

    private fun buildContextPrompt(
        personaMemories: Map<String, List<UnifiedMemoryDatabase.MemoryNode>>,
        routerDecisions: List<UnifiedMemoryDatabase.MemoryNode>,
        recentEvents: List<UnifiedMemoryDatabase.MemoryNode>,
        hitRates: Map<String, Float?>,
        previousDirectives: List<Directive>,
        outcomeContext: String? = null
    ): String = buildString {
        appendLine("=== 리플렉션 데이터 ===\n")

        // Persona memories
        appendLine("[페르소나별 최근 응답]")
        for ((personaId, memories) in personaMemories) {
            if (memories.isEmpty()) continue
            appendLine("## $personaId (최근 ${memories.size}건)")
            memories.take(10).forEach { node ->
                appendLine("- ${node.content.take(200)}")
            }
            appendLine()
        }

        // Hit rates
        appendLine("[페르소나 정확도]")
        for ((personaId, rate) in hitRates) {
            appendLine("- $personaId: ${rate?.let { "${String.format("%.0f", it * 100)}%" } ?: "데이터 없음"}")
        }
        appendLine()

        // Router decisions
        if (routerDecisions.isNotEmpty()) {
            appendLine("[라우터 결정 로그 (최근 ${routerDecisions.size}건)]")
            routerDecisions.take(20).forEach { node ->
                appendLine("- ${node.content.take(150)}")
            }
            appendLine()
        }

        // Recent events
        if (recentEvents.isNotEmpty()) {
            appendLine("[최근 라이프로그 이벤트 (최근 ${recentEvents.size}건)]")
            recentEvents.take(15).forEach { node ->
                appendLine("- [${node.role}] ${node.content.take(150)}")
            }
            appendLine()
        }

        // Previous directives
        if (previousDirectives.isNotEmpty()) {
            appendLine("[현재 활성 지시사항 (${previousDirectives.size}개)]")
            previousDirectives.forEach { d ->
                appendLine("- [${d.targetPersonaId}] ${d.instruction} (신뢰도: ${String.format("%.0f", d.confidence * 100)}%)")
            }
            appendLine()
        }

        // Cadence context
        cadenceContextProvider?.let { provider ->
            val ctx = provider()
            if (ctx.isNotBlank()) {
                appendLine("[현재 기록 주기 상태]")
                appendLine(ctx)
                appendLine()
            }
        }

        // Digital Twin context
        digitalTwinContextProvider?.let { provider ->
            val ctx = provider()
            if (ctx.isNotBlank()) {
                appendLine("[사용자 디지털 트윈 프로필]")
                appendLine(ctx)
                appendLine()
            }
        }

        // ★ 피드백 루프 핵심: OutcomeTracker 성과 데이터
        // "뭐가 통했고 뭐가 안 통했는가" → Gemini가 이걸 보고 지시사항을 개선
        if (!outcomeContext.isNullOrBlank()) {
            appendLine(outcomeContext)
            appendLine()
        }

        // ★ Token Economy: 게이트웨이 상태 + ValueGatekeeper 가치 통계
        appendLine("[AI 호출 경제 상태]")
        aiCallGateway?.let { gw ->
            appendLine("게이트웨이: ${gw.getStatusSummary()}")
        }
        valueGatekeeper?.let { vg ->
            val stats = vg.getIntentStats()
            if (stats.isNotBlank()) {
                appendLine(stats)
            }
            appendLine(vg.getStatusSummary())
        }
        tokenBudgetTracker?.let { tracker ->
            appendLine("글로벌 예산 사용률: ${String.format("%.0f%%", tracker.getGlobalUsageRatio() * 100)}")
        }
        // 현재 GATEWAY 정책 값
        appendLine("[현재 GATEWAY 정책]")
        appendLine("  max_calls_per_minute=${com.xreal.nativear.policy.PolicyReader.getInt("gateway.max_calls_per_minute", 10)}")
        appendLine("  proactive_budget_cutoff=${com.xreal.nativear.policy.PolicyReader.getFloat("gateway.proactive_budget_cutoff", 0.70f)}")
        appendLine("  min_cloud_interval_ms=${com.xreal.nativear.policy.PolicyReader.getLong("gateway.min_cloud_interval_ms", 60000)}")
        appendLine("  budget_scale_50_multiplier=${com.xreal.nativear.policy.PolicyReader.getFloat("gateway.budget_scale_50_multiplier", 2.0f)}")
        appendLine("  budget_scale_70_multiplier=${com.xreal.nativear.policy.PolicyReader.getFloat("gateway.budget_scale_70_multiplier", 4.0f)}")
        appendLine("  min_visibility_rate=${com.xreal.nativear.policy.PolicyReader.getFloat("gateway.min_visibility_rate", 0.10f)}")
        appendLine("  min_useful_rate=${com.xreal.nativear.policy.PolicyReader.getFloat("gateway.min_useful_rate", 0.05f)}")
        appendLine()

        appendLine("위 데이터를 분석하고, 페르소나 성능 개선 및 기록 주기 최적화를 위한 새로운 지시사항을 JSON 배열로 생성하세요.")
    }

    /**
     * Parse the Gemini response into a list of Directives.
     * Handles cases where the response may contain markdown code blocks.
     */
    private fun parseDirectives(text: String): List<Directive> {
        // Extract JSON array from response (may be wrapped in ```json ... ```)
        val jsonText = text
            .replace("```json", "")
            .replace("```", "")
            .trim()

        return try {
            val array = JSONArray(jsonText)
            val directives = mutableListOf<Directive>()

            for (i in 0 until minOf(array.length(), 5)) { // Max 5
                val obj = array.getJSONObject(i)
                val ttlMinutes = obj.optInt("ttl_minutes", 60)
                val now = System.currentTimeMillis()

                directives.add(
                    Directive(
                        targetPersonaId = obj.optString("target", "*"),
                        instruction = obj.optString("instruction", ""),
                        rationale = obj.optString("rationale", ""),
                        confidence = obj.optDouble("confidence", 0.5).toFloat(),
                        createdAt = now,
                        expiresAt = now + ttlMinutes * 60_000L,
                        sourcePattern = obj.optString("rationale", "")
                    )
                )
            }

            Log.i(TAG, "Parsed ${directives.size} directives from reflection")
            directives.filter { it.instruction.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse directives JSON: ${e.message}")
            emptyList()
        }
    }
}
