package com.xreal.nativear.policy

import android.util.Log
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * PolicyManager — 정책 변경 요청 심사 및 라우팅.
 *
 * ## 자동 승인 조건
 * - source = "user_voice" (사용자 직접 음성 명령)
 * - priority >= 2 (긴급)
 *
 * ## 심사 대기
 * - 나머지 → pendingQueue → StrategistService 5분 주기 심사에 합류
 *
 * ## 음성 명령 처리
 * EventBus 구독 → "정책"/"설정" 키워드 감지 → PolicyToolExecutor 위임
 */
class PolicyManager(
    private val policyRegistry: PolicyRegistry,
    private val eventBus: GlobalEventBus
) {
    private val TAG = "PolicyManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var eventJob: Job? = null

    // 심사 대기 큐
    private val pendingQueue = ConcurrentLinkedQueue<PolicyChangeRequest>()

    data class PolicyChangeRequest(
        val requestId: String = UUID.randomUUID().toString().take(8),
        val policyKey: String,
        val newValue: String,
        val requesterPersonaId: String,
        val rationale: String,
        val priority: Int = 0,  // 0=일반, 1=권장, 2=긴급
        val timestamp: Long = System.currentTimeMillis()
    )

    fun start() {
        eventJob = scope.launch {
            eventBus.events.collect { event ->
                try {
                    when (event) {
                        is XRealEvent.InputEvent.VoiceCommand -> {
                            handleVoiceCommand(event.text)
                        }
                        else -> { /* 무시 */ }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "이벤트 처리 오류 (루프 유지됨): ${event::class.simpleName} — ${e.message}", e)
                }
            }
        }
        Log.i(TAG, "PolicyManager started (음성 명령 구독 활성)")
    }

    /**
     * 정책 변경 요청 제출.
     * auto-approve: user_voice, priority>=2
     * 나머지: 심사 대기열
     */
    fun submitRequest(request: PolicyChangeRequest): Boolean {
        // Gap J: 확장된 자동 승인 조건 — 5분 대기 최소화
        val autoApprove = request.requesterPersonaId == "user_voice" ||
                request.priority >= 2 ||
                request.requesterPersonaId == "strategist" ||  // AI 반성 결과 → 이미 분석 완료
                request.requesterPersonaId == "system" ||      // 시스템 내부 조정
                isSmallChange(request)                         // 현재값 대비 ±20% 이내 변경

        if (autoApprove) {
            val success = policyRegistry.set(
                request.policyKey,
                request.newValue,
                request.requesterPersonaId,
                ttlMs = 0L
            )
            if (success) {
                Log.i(TAG, "정책 요청 자동 승인: ${request.policyKey}=${request.newValue} (${request.requesterPersonaId})")
                scope.launch {
                    eventBus.publish(XRealEvent.SystemEvent.DebugLog(
                        "정책 변경 승인: ${request.policyKey} = ${request.newValue}"
                    ))
                }
            }
            return success
        }

        // 심사 대기열에 추가
        pendingQueue.add(request)
        Log.d(TAG, "정책 요청 대기열 추가: ${request.policyKey}=${request.newValue} by ${request.requesterPersonaId}")

        // 대기열 크기 제한 (최대 50)
        while (pendingQueue.size > 50) pendingQueue.poll()
        return true
    }

    /**
     * Gap J: 현재값 대비 ±20% 이내 변경이면 안전한 미세 조정으로 간주.
     * 숫자 타입 정책에만 적용.
     */
    private fun isSmallChange(request: PolicyChangeRequest): Boolean {
        return try {
            val entry = policyRegistry.getEntry(request.policyKey) ?: return false
            if (entry.valueType != PolicyValueType.INT &&
                entry.valueType != PolicyValueType.LONG &&
                entry.valueType != PolicyValueType.FLOAT) return false

            val current = policyRegistry.get(request.policyKey)?.toDoubleOrNull() ?: return false
            val newVal = request.newValue.toDoubleOrNull() ?: return false
            if (current == 0.0) return false

            val changeRatio = Math.abs(newVal - current) / Math.abs(current)
            changeRatio <= 0.20  // 20% 이내
        } catch (_: Exception) { false }
    }

    /**
     * StrategistService 반성 주기에서 호출 — 심사 컨텍스트 생성.
     * 빈 문자열이면 심사할 것 없음.
     */
    fun buildReviewContext(): String? {
        if (pendingQueue.isEmpty()) return null

        val pending = pendingQueue.toList()
        return buildString {
            appendLine("[정책 변경 심사 요청 (${pending.size}건)]")
            pending.forEach { req ->
                val entry = policyRegistry.getEntry(req.policyKey)
                val current = policyRegistry.get(req.policyKey) ?: "N/A"
                appendLine("  - ${req.policyKey}: $current → ${req.newValue}")
                appendLine("    요청자: ${req.requesterPersonaId}, 이유: ${req.rationale}")
                if (entry != null) {
                    appendLine("    범위: ${entry.min}~${entry.max}, 기본값: ${entry.defaultValue}")
                }
            }
            appendLine("각 요청에 대해 'approve' 또는 'reject'로 판단하세요.")
            appendLine("형식: policy_decisions:[{\"key\":\"...\",\"action\":\"approve|reject\"}]")
        }.trimEnd()
    }

    /**
     * StrategistService가 Gemini 응답에서 policy_decisions 파싱 후 호출.
     * @param decisions key→approve/reject 맵
     */
    fun applyReviewDecisions(decisions: Map<String, String>) {
        val pending = pendingQueue.toList()
        pendingQueue.clear()

        var approved = 0
        var rejected = 0

        for (req in pending) {
            val action = decisions[req.policyKey]
            if (action == "approve") {
                policyRegistry.set(req.policyKey, req.newValue, req.requesterPersonaId)
                approved++
            } else {
                rejected++
            }
        }

        if (approved > 0 || rejected > 0) {
            Log.i(TAG, "정책 심사 결과: 승인 $approved, 거부 $rejected / 전체 ${pending.size}")
        }
    }

    fun getPendingCount(): Int = pendingQueue.size

    /**
     * 음성 명령에서 정책/설정 키워드 감지.
     * 간단한 패턴: "OCR 간격 5초로", "예산 임계값 90%로"
     */
    private fun handleVoiceCommand(text: String) {
        if (!text.contains("정책") && !text.contains("설정") &&
            !text.contains("간격") && !text.contains("임계값")) return

        Log.d(TAG, "정책 관련 음성 감지: $text")
        // 구체적 파싱은 AIAgentManager의 도구 호출로 위임 (request_policy_change 도구)
        // 여기서는 로깅만 — AI가 도구로 submitRequest() 호출
    }

    fun stop() {
        eventJob?.cancel()
        scope.cancel()
    }
}
