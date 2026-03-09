package com.xreal.nativear.resilience

import android.util.Log
import com.xreal.nativear.core.ErrorSeverity
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import java.util.concurrent.atomic.AtomicInteger
import com.xreal.nativear.edge.EdgeDelegationRouter
import com.xreal.nativear.evolution.CapabilityManager
import com.xreal.nativear.evolution.CapabilityRequest
import com.xreal.nativear.evolution.CapabilityType
import com.xreal.nativear.evolution.RequestPriority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * EmergencyOrchestrator — AI 추론 기반 에러 상황 우회 지휘자.
 *
 * ## 역할 (3-레이어 응급 지휘 체계 Layer 2)
 * - SystemEvent.Error 구독 → 패턴 분류 → 우회 경로 결정
 * - 사용자 TTS 알림 후 3초 오버라이드 대기 (사용자가 "취소"하면 롤백)
 * - 신규 패턴 감지 → CapabilityManager BUG_REPORT 자동 제출
 * - getRecentErrorContext(): StrategistService 반성 주기에 에러 컨텍스트 제공
 *   → Gemini가 Directive 생성 시 "최근 에러 패턴"을 보고 전략적 우회 지시 가능
 *
 * ## 아키텍처 위치
 * ```
 * ⚡ Layer 1: FailsafeController (0ms, 하드코딩)  — 하드웨어 장애
 * 🔄 Layer 2: EmergencyOrchestrator (3s, 규칙+AI)  — 에러 우회  ← 여기
 * 🎯 Layer 3: StrategistService (5min, AI 추론)  — 전략 업데이트
 * ```
 *
 * ## 우회 규칙 (rerouteRules)
 * 에러 코드 → RerouteAction 매핑. 향후 AI(StrategistService)가 Directive로 새 규칙 추가 가능.
 *
 * ## 설계 원칙 (CLAUDE.md Rule 5)
 * - EventBus 구독 패턴: start()/stop()으로 구독 생명주기 관리
 * - Koin singleton으로 등록 (AppModule.kt)
 * - AppBootstrapper에서 start()/stop() 호출
 */
class EmergencyOrchestrator(
    private val eventBus: GlobalEventBus,
    private val edgeDelegationRouter: EdgeDelegationRouter? = null,
    private val capabilityManager: CapabilityManager? = null
) {
    private val TAG = "EmergencyOrchestrator"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ─── 우회 규칙 테이블 ───
    // 에러 코드 → RerouteAction 매핑 (하드코딩 초기 규칙)
    // StrategistService가 Directive로 새 규칙 주입 가능 (향후 확장)
    private val rerouteRules: Map<String, RerouteAction> = mapOf(
        "SERVER_AI_ERROR"           to RerouteAction.SWITCH_TO_EDGE,
        "GEMINI_API_TIMEOUT"        to RerouteAction.SWITCH_TO_EDGE,
        "NETWORK_UNAVAILABLE"       to RerouteAction.SWITCH_TO_EDGE,
        "EDGE_AI_ERROR"             to RerouteAction.USE_CACHED_RESPONSE,
        "VISION_COORDINATOR_ERROR"  to RerouteAction.PAUSE_VISION_30S,
        "INPUT_COORDINATOR_ERROR"   to RerouteAction.LOG_AND_CONTINUE
    )

    // 최근 에러 컨텍스트 버퍼 (StrategistService.runReflectionCycle()이 읽어감)
    private val recentErrors = mutableListOf<ErrorRecord>()
    private val MAX_RECENT_ERRORS = 20

    // WARNING 집계 카운터 (Phase G — AI 트리아지 없이 통계만)
    private val warningCount = AtomicInteger(0)

    // 사용자 오버라이드 플래그 (TTS 안내 후 "취소" 음성 감지 시 true)
    @Volatile private var userOverrideActive = false

    data class ErrorRecord(
        val code: String,
        val message: String,
        val timestampMs: Long = System.currentTimeMillis(),
        val rerouteApplied: RerouteAction? = null,
        val severity: ErrorSeverity = ErrorSeverity.WARNING  // Phase G
    )

    /**
     * 에러 우회 액션 정의.
     * @param userMessage TTS로 사용자에게 알릴 메시지
     * @param notifyUser true이면 TTS + 3초 오버라이드 대기 수행
     */
    enum class RerouteAction(val userMessage: String, val notifyUser: Boolean = false) {
        SWITCH_TO_EDGE(
            userMessage = "서버 연결 실패. 온디바이스 AI로 전환합니다",
            notifyUser = true
        ),
        USE_CACHED_RESPONSE(
            userMessage = "AI 응답 캐시를 사용합니다",
            notifyUser = false
        ),
        PAUSE_VISION_30S(
            userMessage = "비전 파이프라인을 30초 재시작합니다",
            notifyUser = true
        ),
        LOG_AND_CONTINUE(
            userMessage = "",
            notifyUser = false
        ),
        REPORT_ONLY(
            userMessage = "",
            notifyUser = false
        )
    }

    // ─── 생명주기 ───

    fun start() {
        scope.launch {
            eventBus.events.collect { event ->
                try {
                    when (event) {
                        is XRealEvent.SystemEvent.Error -> handleError(event)

                        // 사용자 오버라이드: TTS 안내 후 "취소/아니야/그냥 둬" 음성 감지
                        is XRealEvent.InputEvent.VoiceCommand -> {
                            val text = event.text.lowercase()
                            if (text.contains("취소") || text.contains("아니야") || text.contains("그냥 둬")) {
                                userOverrideActive = true
                                Log.i(TAG, "사용자 오버라이드 감지: '${event.text}'")
                            }
                        }
                        else -> {}
                    }
                } catch (e: CancellationException) {
                    throw e  // scope 취소 신호는 반드시 재발행
                } catch (e: Exception) {
                    Log.e(TAG, "EmergencyOrchestrator 이벤트 처리 오류: ${e.message}")
                }
            }
        }
        Log.i(TAG, "EmergencyOrchestrator started — 에러 우회 지휘 시스템 활성")
    }

    fun stop() {
        scope.cancel()
        Log.i(TAG, "EmergencyOrchestrator stopped")
    }

    // ─── 에러 처리 핵심 로직 ───

    private suspend fun handleError(error: XRealEvent.SystemEvent.Error) {
        // Phase G: severity 기반 분기
        // WARNING → 카운터 집계만 (AI 트리아지 없음)
        // CRITICAL → 기존 rerouteRules AI 트리아지 실행
        if (error.severity == ErrorSeverity.WARNING) {
            val count = warningCount.incrementAndGet()
            synchronized(recentErrors) {
                recentErrors.add(0, ErrorRecord(
                    code = error.code,
                    message = error.message,
                    severity = ErrorSeverity.WARNING
                ))
                if (recentErrors.size > MAX_RECENT_ERRORS) recentErrors.removeAt(recentErrors.lastIndex)
            }
            Log.d(TAG, "WARNING 집계: ${error.code} (누적 ${count}건)")
            return
        }

        // CRITICAL 전용: 기존 rerouteRules 트리아지 로직
        val action = rerouteRules[error.code] ?: RerouteAction.REPORT_ONLY
        val isKnownPattern = rerouteRules.containsKey(error.code)

        Log.w(TAG, "⚠️ CRITICAL 에러 수신: ${error.code} → 우회: $action (알려진 패턴: $isKnownPattern)")

        // 에러 컨텍스트 버퍼에 기록 (StrategistService가 다음 반성 주기에 읽어감)
        synchronized(recentErrors) {
            recentErrors.add(0, ErrorRecord(
                code = error.code,
                message = error.message,
                rerouteApplied = if (action != RerouteAction.REPORT_ONLY) action else null,
                severity = ErrorSeverity.CRITICAL
            ))
            if (recentErrors.size > MAX_RECENT_ERRORS) recentErrors.removeAt(recentErrors.lastIndex)
        }

        // 사용자 알림이 필요한 경우 — TTS + 3초 오버라이드 대기
        if (action.notifyUser) {
            userOverrideActive = false
            // important=true: 긴급 상황 알림 → 조용히 모드에서도 항상 출력
            eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(
                "${action.userMessage}. 취소하려면 '취소'라고 말씀하세요.", important = true
            ))
            delay(3000L)

            if (userOverrideActive) {
                Log.i(TAG, "사용자 오버라이드 — 우회 취소됨: ${error.code}")
                userOverrideActive = false
                eventBus.publish(XRealEvent.SystemEvent.DebugLog(
                    "[EmergencyOrchestrator] 우회 취소 (사용자 요청): ${error.code}"
                ))
                return
            }
        }

        // 우회 액션 실행
        executeReroute(action, error.code)

        // 신규 패턴이면 BUG_REPORT 자동 제출
        if (!isKnownPattern) {
            submitBugReport(error)
        }
    }

    private suspend fun executeReroute(action: RerouteAction, errorCode: String) {
        when (action) {
            RerouteAction.SWITCH_TO_EDGE -> {
                edgeDelegationRouter?.recordServerFailure()
                Log.i(TAG, "✅ 서버 AI 실패 기록 → 엣지 AI 경로 전환: $errorCode")
                eventBus.publish(XRealEvent.SystemEvent.DebugLog("🔄 $errorCode → 엣지 AI 전환됨"))
            }

            RerouteAction.USE_CACHED_RESPONSE -> {
                Log.i(TAG, "✅ 캐시 응답 사용 모드: $errorCode")
                eventBus.publish(XRealEvent.SystemEvent.DebugLog("📋 $errorCode → 캐시 응답 사용"))
            }

            RerouteAction.PAUSE_VISION_30S -> {
                Log.i(TAG, "✅ 비전 파이프라인 30초 일시 중지: $errorCode")
                eventBus.publish(XRealEvent.SystemEvent.DebugLog("⏸️ $errorCode → 비전 30s 중지"))
                delay(30_000L)
                eventBus.publish(XRealEvent.SystemEvent.DebugLog("▶️ 비전 파이프라인 재개 (30s 경과)"))
            }

            RerouteAction.LOG_AND_CONTINUE -> {
                Log.d(TAG, "에러 로그 기록 후 계속: $errorCode")
            }

            RerouteAction.REPORT_ONLY -> {
                Log.d(TAG, "신규 에러 패턴 기록 (우회 규칙 없음): $errorCode")
            }
        }
    }

    private fun submitBugReport(error: XRealEvent.SystemEvent.Error) {
        try {
            capabilityManager?.submitRequest(
                CapabilityRequest(
                    title = "[EmergencyOrchestrator] 신규 에러 패턴: ${error.code}",
                    description = "알려진 우회 패턴 없음. 에러 메시지: ${error.message.take(200)}\n" +
                        "스택트레이스: ${error.throwable?.stackTraceToString()?.take(300) ?: "없음"}",
                    requestingExpertId = "emergency_orchestrator",
                    type = CapabilityType.BUG_REPORT,
                    priority = RequestPriority.HIGH
                )
            )
            Log.i(TAG, "신규 패턴 BUG_REPORT 제출됨: ${error.code}")
        } catch (e: Exception) {
            Log.w(TAG, "BUG_REPORT 제출 실패: ${e.message}")
        }
    }

    // ─── StrategistService 인터페이스 ───

    /**
     * 최근 30분 에러 컨텍스트 요약.
     * StrategistService.runReflectionCycle()에서 호출하여 Gemini에 전달.
     * Gemini가 "최근 SERVER_AI_ERROR 3회 발생, 엣지 AI로 전환 중"을 인지하고
     * 전략적 지시사항(Directive) 생성에 반영.
     *
     * @return 에러 패턴 요약 문자열, 최근 에러 없으면 null
     */
    fun getRecentErrorContext(): String? {
        val errors = synchronized(recentErrors) { recentErrors.toList() }
        if (errors.isEmpty()) return null

        val now = System.currentTimeMillis()
        val recent = errors.filter { now - it.timestampMs < 30 * 60_000L }  // 30분 이내
        if (recent.isEmpty()) return null

        val criticals = recent.filter { it.severity == ErrorSeverity.CRITICAL }
        val warnings  = recent.filter { it.severity == ErrorSeverity.WARNING }

        return buildString {
            appendLine("[최근 30분 에러 패턴 — EmergencyOrchestrator]")
            // CRITICAL: 상세 기록 (AI 트리아지 대상)
            appendLine("CRITICAL: ${criticals.size}건")
            criticals.groupBy { it.code }.forEach { (code, records) ->
                val latestAction = records.mapNotNull { it.rerouteApplied }.lastOrNull()
                appendLine("  $code: ${records.size}회 (우회: ${latestAction ?: "없음"})")
            }
            // WARNING: 코드별 카운트 요약 (상세 생략)
            if (warnings.isNotEmpty()) {
                appendLine("WARNING: ${warnings.size}건 (코드별: ${warnings.groupBy { it.code }.keys})")
            }
            appendLine("전체 에러 ${recent.size}건 / ${recent.groupBy { it.code }.size}가지 유형")
        }
    }

    /**
     * Gap C: StrategistService → DirectiveConsumer → EmergencyOrchestrator 피드백 루프.
     * Gemini가 분석한 우회 경로를 즉시 학습하여 다음 동일 에러 발생 시 즉시 적용.
     *
     * @param rule "ERROR_CODE→ACTION" 형식 (예: "SERVER_AI_ERROR→SWITCH_TO_EDGE")
     */
    fun applyRerouteRule(rule: String) {
        try {
            val parts = rule.split("→", "->")
            if (parts.size != 2) {
                Log.w(TAG, "잘못된 reroute 규칙 형식: '$rule' (ERROR_CODE→ACTION 필요)")
                return
            }
            val errorCode = parts[0].trim()
            val actionStr = parts[1].trim()
            learnedRerouteRules[errorCode] = actionStr
            Log.i(TAG, "★ Gemini 학습 우회 규칙 등록: $errorCode → $actionStr")
        } catch (e: Exception) {
            Log.w(TAG, "reroute 규칙 적용 실패: ${e.message}")
        }
    }

    /** Gemini가 분석한 우회 규칙 캐시 (에러코드 → 권장 액션) */
    private val learnedRerouteRules = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 학습된 우회 규칙 조회 (handleError에서 사용) */
    fun getLearnedAction(errorCode: String): String? = learnedRerouteRules[errorCode]

    /**
     * 에러 통계 요약 (DebugHUD 또는 로깅용).
     */
    fun getStats(): String {
        val errors = synchronized(recentErrors) { recentErrors.toList() }
        val grouped = errors.groupBy { it.code }
        return buildString {
            appendLine("EmergencyOrchestrator Stats:")
            appendLine("  총 에러 기록: ${errors.size}개")
            grouped.forEach { (code, records) ->
                appendLine("  $code: ${records.size}회")
            }
        }
    }
}
