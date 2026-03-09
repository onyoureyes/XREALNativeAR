package com.xreal.nativear.core

import android.util.Log
import com.xreal.nativear.AIAgentManager
import com.xreal.nativear.core.GestureType
import com.xreal.nativear.focus.PalmFaceGestureDetector
import com.xreal.nativear.resource.ResourceProposalManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/**
 * InputCoordinator: Subscribes to InputEvent.Gesture and executes corresponding actions.
 * Replaces the old GestureHandler + UserAction pattern.
 *
 * ## 추가 라우팅 (적응형 회복력 시스템)
 * - HandsDetected → PalmFaceGestureDetector (손바닥 PRIVATE 모드 제스처)
 * - VoiceCommand → ResourceProposalManager.onUserVoiceResponse() (리소스 제안 승인/거부)
 */
class InputCoordinator(
    private val eventBus: GlobalEventBus,
    private val aiAgentManager: AIAgentManager,
    private val palmFaceGestureDetector: PalmFaceGestureDetector? = null,
    private val resourceProposalManager: ResourceProposalManager? = null
) {
    interface InputListener {
        fun onCycleCamera()
        fun onDailySummary()
        fun onSyncMemory()
        fun onOpenMemQuery()
        fun onConfirmAction(message: String)
        fun onCancelAction(message: String)
        fun processGeminiCommand(command: String)
        fun onLog(message: String)
    }

    private var listener: InputListener? = null

    fun setListener(l: InputListener) {
        this.listener = l
    }
    private val TAG = "InputCoordinator"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        subscribeToEvents()
        Log.i(TAG, "InputCoordinator initialized and subscribed to events")
    }

    private fun subscribeToEvents() {
        scope.launch {
            // ★ 회복력: collect 루프를 try-catch로 보호.
            // try-catch 없으면 단 한 번의 예외로 collect가 종료되어 음성·제스처 처리가 영구 정지됨.
            // CancellationException은 반드시 재발행 (scope 취소 신호 방해 금지).
            eventBus.events.collect { event ->
                try {
                    when (event) {
                        is XRealEvent.InputEvent.Gesture -> handleGesture(event.type)
                        is XRealEvent.InputEvent.VoiceCommand -> {
                            Log.i(TAG, "Handling voice command: ${event.text}")
                            // 리소스 제안 응답 먼저 처리 (AI 호출보다 우선)
                            resourceProposalManager?.onUserVoiceResponse(event.text)
                            aiAgentManager.processWithGemini(event.text)
                        }
                        is XRealEvent.InputEvent.EnrichedVoiceCommand -> {
                            Log.i(TAG, "Handling enriched voice command: ${event.text} (Speaker: ${event.speaker}, Emotion: ${event.emotion})")
                            // 리소스 제안 응답 먼저 처리
                            resourceProposalManager?.onUserVoiceResponse(event.text)
                            val enrichedContext = """
                                [Auditory Context]
                                Speaker: ${event.speaker}
                                Emotion: ${event.emotion} (Score: ${String.format("%.2f", event.emotionScore)})
                            """.trimIndent()
                            aiAgentManager.processWithGemini(event.text, enrichedContext)
                        }
                        is XRealEvent.PerceptionEvent.HandsDetected -> {
                            // 손바닥-얼굴 근접 제스처 → PalmFaceGestureDetector (PRIVATE 모드)
                            // PalmFaceGestureDetector는 EventBus를 직접 구독하므로 여기서는 로그만
                            if (event.hands.isNotEmpty()) {
                                Log.d(TAG, "HandsDetected → PalmFaceGestureDetector 처리 중 (${event.hands.size}개 손)")
                            }
                        }
                        else -> {} // Ignore other events
                    }
                } catch (e: CancellationException) {
                    throw e  // scope 취소 신호는 반드시 재발행
                } catch (e: Exception) {
                    // 이벤트 처리 실패 — 루프는 계속 유지 (시스템 정지 방지)
                    Log.e(TAG, "이벤트 처리 오류 (루프 유지됨): ${event::class.simpleName} — ${e.message}", e)
                    eventBus.publish(XRealEvent.SystemEvent.Error(
                        code = "INPUT_COORDINATOR_ERROR",
                        message = "${event::class.simpleName} 처리 실패: ${e.message?.take(100)}",
                        throwable = e
                    ))
                }
            }
        }
    }

    private fun handleGesture(type: GestureType) {
        Log.i(TAG, "Handling gesture: $type")
        when (type) {
            GestureType.DOUBLE_TAP -> listener?.onCycleCamera()
            GestureType.TRIPLE_TAP -> listener?.onOpenMemQuery()
            GestureType.QUAD_TAP -> listener?.onSyncMemory()
            GestureType.NOD -> {
                // ★ P1-4: 예산 오버라이드 대기 중이면 우선 처리
                if (aiAgentManager.handleBudgetOverrideGesture(true)) return
                listener?.onConfirmAction("Confirmed via nod")
            }
            GestureType.SHAKE -> {
                // ★ P1-4: 예산 오버라이드 대기 중이면 우선 처리
                if (aiAgentManager.handleBudgetOverrideGesture(false)) return
                listener?.onCancelAction("Cancelled via shake")
            }
            GestureType.TILT -> {
                // TILT는 MeetingContextService가 EventBus에서 직접 처리
                // InputCoordinator는 로그만 남김
                Log.i(TAG, "TILT gesture detected — delegated to MeetingContextService")
            }
            else -> {} // Handle other gestures or future additions
        }
    }

    fun cleanup() {
        scope.cancel()
        Log.i(TAG, "InputCoordinator cleaned up")
    }
}
