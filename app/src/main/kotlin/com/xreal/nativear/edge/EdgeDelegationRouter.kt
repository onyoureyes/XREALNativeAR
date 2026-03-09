package com.xreal.nativear.edge

import android.util.Log
import com.xreal.nativear.ai.AIMessage
import com.xreal.nativear.ai.ProviderId
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * EdgeDelegationRouter — AI 호출 경로 결정 (서버 AI vs 엣지 AI).
 *
 * RESEARCH.md §2, §6 참조.
 *
 * ## 라우팅 로직
 * ```
 * 온라인 일반:
 *   쿼리 → 270M 분류 (~5-15ms)
 *     SIMPLE  → EDGE_AGENT (1B, 무료)
 *     COMPLEX → 서버 AI 정상 처리
 *
 * 오프라인:
 *   모든 쿼리 → EDGE_AGENT (1B) 기본
 *              → EDGE_EMERGENCY (E2B) 복잡/멀티모달
 *
 * 강제 엣지 모드 (사용자 명령 or 서버 3회 연속 실패):
 *   모든 쿼리 → 엣지 AI
 *   "범블비 엣지 모드" → E2B 사전 로딩
 * ```
 *
 * ## 통합
 * AIAgentManager.processWithGemini() 진입 전 resolvePersonaId() 호출.
 * 서버 성공/실패 시 recordServerSuccess()/recordServerFailure() 호출.
 * 서버 성공 후 recordServerLatency(personaId, latencyMs) 호출 → SLA 자동 엣지 전환.
 */
class EdgeDelegationRouter(
    private val edgeModelManager: EdgeModelManager,
    private val connectivityMonitor: ConnectivityMonitor,
    private val routerProvider: EdgeLLMProvider,  // 270M 복잡도 분류용
    private val eventBus: GlobalEventBus
) {
    companion object {
        private const val TAG = "EdgeDelegationRouter"
        private const val SERVER_FAIL_THRESHOLD = 3  // 연속 실패 임계값

        // ★ 레이턴시 SLA
        private const val LATENCY_SLA_MS = 8_000L   // 8초 초과 = 느린 응답
        private const val LATENCY_WINDOW_SIZE = 5    // 최근 N회 측정값 유지

        // 270M 분류 시스템 프롬프트
        private const val ROUTER_SYSTEM_PROMPT = """다음 질문을 분류하세요.
단순한 정보 조회, 텍스트 요약, 간단한 포매팅, 날씨/시간/변환 질문 = SIMPLE
복잡한 추론, 분석, 창작, 코드 생성, 여러 단계 추론 필요 = COMPLEX
한 단어만 답하세요: SIMPLE 또는 COMPLEX"""
    }

    /** 사용자가 명시적으로 엣지 모드 강제 활성화 */
    @Volatile
    var isUserForcedEdge = false
        private set

    /** 서버 연속 실패 카운터 */
    @Volatile
    private var serverFailCount = 0

    /** 최근 레이턴시 롤링 윈도우 (personaId → 최근 N회 ms) — SLA 판단용 */
    private val latencyWindows = ConcurrentHashMap<String, ArrayDeque<Long>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // NetworkStateChanged 이벤트 구독
        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is XRealEvent.SystemEvent.NetworkStateChanged -> {
                        if (!event.isOnline) {
                            Log.i(TAG, "오프라인 감지 — 엣지 AI 자동 전환 준비")
                        } else {
                            Log.i(TAG, "온라인 복귀 — 서버 AI 재개 가능")
                            if (!isUserForcedEdge) serverFailCount = 0
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * 어떤 PersonaId를 사용할지 결정.
     *
     * @param query 사용자 쿼리
     * @param defaultServerPersonaId 온라인 정상 시 사용할 서버 AI persona ID
     * @return 실제 사용할 persona ID ("edge_assistant", "edge_emergency", or defaultServerPersonaId)
     */
    suspend fun resolvePersonaId(query: String, defaultServerPersonaId: String): String {
        // 1. 오프라인 → 엣지 강제
        if (!connectivityMonitor.isOnline) {
            Log.d(TAG, "오프라인 → edge 라우팅")
            return selectBestEdgePersona(query)
        }

        // 2. 사용자 강제 or 서버 3회 연속 실패
        if (isUserForcedEdge || serverFailCount >= SERVER_FAIL_THRESHOLD) {
            Log.d(TAG, "강제 엣지 (userForced=$isUserForcedEdge, fails=$serverFailCount) → edge 라우팅")
            return selectBestEdgePersona(query)
        }

        // 2b. ★ 응답 속도 SLA 초과 → 엣지 전환 (느린 응답 = 실질적 실패)
        if (isServerTooSlow(defaultServerPersonaId)) {
            Log.d(TAG, "SLA 초과 ($defaultServerPersonaId avg >${LATENCY_SLA_MS}ms) → edge 라우팅")
            return selectBestEdgePersona(query)
        }

        // 3. 270M 라우터로 복잡도 분류 (온라인에서도 동작 — 서버 AI 절약)
        if (edgeModelManager.isReady(EdgeModelTier.ROUTER_270M)) {
            return classifyAndRoute(query, defaultServerPersonaId)
        }

        // 4. 270M 미준비 → 서버 AI
        return defaultServerPersonaId
    }

    /** 서버 AI 성공 시 호출 */
    fun recordServerSuccess() {
        if (serverFailCount > 0) {
            Log.d(TAG, "서버 AI 성공 — 실패 카운터 리셋")
            serverFailCount = 0
        }
    }

    /** 서버 AI 실패 시 호출 */
    fun recordServerFailure() {
        serverFailCount++
        Log.w(TAG, "서버 AI 실패 ($serverFailCount/${SERVER_FAIL_THRESHOLD})")
        if (serverFailCount >= SERVER_FAIL_THRESHOLD) {
            Log.w(TAG, "서버 AI ${SERVER_FAIL_THRESHOLD}회 연속 실패 → 엣지 AI 자동 전환")
            scope.launch {
                eventBus.publish(
                    XRealEvent.ActionRequest.SpeakTTS("서버 연결 불안정 — 엣지 AI 모드 전환")
                )
            }
        }
    }

    /**
     * 사용자 명령으로 엣지 모드 강제 활성화.
     * "범블비 엣지 모드" 음성 명령 시 호출.
     */
    fun enableForcedEdge() {
        isUserForcedEdge = true
        Log.i(TAG, "사용자 강제 엣지 모드 활성화 — E2B 사전 로딩")
        // E2B 사전 로딩
        scope.launch {
            edgeModelManager.getOrLoad(EdgeModelTier.EMERGENCY_E2B)
        }
    }

    /** 강제 엣지 모드 비활성화 (온라인 복귀 등) */
    fun disableForcedEdge() {
        isUserForcedEdge = false
        serverFailCount = 0
        Log.i(TAG, "엣지 모드 해제 — 서버 AI 재개")
    }

    /**
     * 서버 AI 응답 레이턴시 기록 (SLA 기반 자동 엣지 전환용).
     *
     * AIAgentManager에서 서버 AI 성공 후 즉시 호출.
     * 최근 [LATENCY_WINDOW_SIZE]회 평균이 [LATENCY_SLA_MS] 초과 시 다음 resolvePersonaId()에서 엣지 전환.
     *
     * @param personaId 응답한 서버 AI persona ID (e.g. "gemini")
     * @param latencyMs sendMessage() 소요 시간 (AIResponse.latencyMs)
     */
    fun recordServerLatency(personaId: String, latencyMs: Long) {
        if (latencyMs <= 0) return
        val window = latencyWindows.getOrPut(personaId) { ArrayDeque() }
        synchronized(window) {
            window.addLast(latencyMs)
            if (window.size > LATENCY_WINDOW_SIZE) window.removeFirst()
        }
        if (latencyMs > LATENCY_SLA_MS) {
            Log.w(TAG, "느린 응답 감지: $personaId ${latencyMs}ms > SLA ${LATENCY_SLA_MS}ms")
            scope.launch {
                eventBus.publish(XRealEvent.SystemEvent.DebugLog(
                    "⚠️ AI 응답 지연: $personaId ${latencyMs / 1000}s (SLA ${LATENCY_SLA_MS / 1000}s 초과)"
                ))
            }
        }
    }

    // =========================================================================
    // 내부 유틸리티
    // =========================================================================

    /**
     * 최근 N회 평균 레이턴시가 SLA를 초과하는지 확인.
     * 데이터 부족 시(3회 미만) false 반환 — 보수적 판단, 초기 오탐 방지.
     */
    private fun isServerTooSlow(personaId: String): Boolean {
        val window = latencyWindows[personaId] ?: return false
        if (window.size < 3) return false
        val avgMs = synchronized(window) { window.average().toLong() }
        if (avgMs > LATENCY_SLA_MS) {
            Log.w(TAG, "SLA 초과 판정: $personaId avg=${avgMs}ms > ${LATENCY_SLA_MS}ms")
        }
        return avgMs > LATENCY_SLA_MS
    }

    /** 상황에 맞는 최적 엣지 persona 선택 */
    private suspend fun selectBestEdgePersona(query: String): String {
        // E2B 강제 or 이미 준비된 경우
        if (isUserForcedEdge && edgeModelManager.isReady(EdgeModelTier.EMERGENCY_E2B)) {
            return "edge_emergency"
        }
        // 기본: 1B
        return "edge_assistant"
    }

    /**
     * 270M으로 쿼리 복잡도 분류 후 라우팅.
     * SIMPLE → 1B (엣지, 무료), COMPLEX → 서버 AI
     */
    private suspend fun classifyAndRoute(query: String, defaultPersonaId: String): String {
        return try {
            val classifyMessages = listOf(
                AIMessage(role = "user", content = "질문: $query")
            )
            val response = routerProvider.sendMessage(
                messages = classifyMessages,
                systemPrompt = ROUTER_SYSTEM_PROMPT,
                temperature = 0.1f,
                maxTokens = 5
            )
            val classification = response.text?.trim()?.uppercase() ?: "COMPLEX"

            if (classification.contains("SIMPLE")) {
                Log.d(TAG, "270M 분류: SIMPLE → edge_assistant (1B)")
                "edge_assistant"
            } else {
                Log.d(TAG, "270M 분류: COMPLEX → 서버 AI ($defaultPersonaId)")
                defaultPersonaId
            }
        } catch (e: Exception) {
            Log.w(TAG, "270M 분류 실패: ${e.message} → 서버 AI")
            defaultPersonaId
        }
    }
}
