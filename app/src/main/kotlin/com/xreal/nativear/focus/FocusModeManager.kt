package com.xreal.nativear.focus

import android.util.Log
import com.xreal.nativear.core.FocusMode
import com.xreal.nativear.core.GlobalEventBus
import kotlin.coroutines.cancellation.CancellationException
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * FocusModeManager — 사용자 집중/개인 모드 관리.
 *
 * ## 모드 전환 트리거
 * - 음성 명령: "방해 금지", "조용히", "혼자 있고 싶어" → DND
 * - 음성 명령: "완전 개인 시간", "화장실", "자리 비울게" → PRIVATE
 * - 음성 명령: "범블비 일어나", "모드 해제", "돌아와" → NORMAL
 * - 손바닥 제스처 (PalmFaceGestureDetector): 얼굴 접근 1초 → PRIVATE
 * - OperationalDirector: 상황(BATHROOM) → PRIVATE 자동 전환
 *
 * ## AI 동작 게이트
 * [canAIAct] 함수: 모든 AI 능동 트리거 전 반드시 호출.
 * - DND: 사용자 명시 명령만 허용
 * - PRIVATE: 생명 안전(낙상/심박 이상)만 허용
 */
class FocusModeManager(
    private val eventBus: GlobalEventBus
) {
    companion object {
        private const val TAG = "FocusModeManager"

        // 한국어 DND 트리거 키워드
        private val DND_KEYWORDS = listOf(
            "방해 금지", "방해금지", "조용히", "혼자 있고 싶어", "혼자있고싶어",
            "건드리지 마", "그냥 있을게", "쉴게", "잠깐 쉬어"
        )
        // 한국어 PRIVATE 트리거 키워드
        private val PRIVATE_KEYWORDS = listOf(
            "완전 개인 시간", "완전개인시간", "화장실", "욕실", "개인 용변",
            "자리 비울게", "잠깐 자리 비워", "잠깐 비워", "혼자 있을게"
        )
        // 한국어 NORMAL 복귀 키워드
        private val NORMAL_KEYWORDS = listOf(
            "범블비 일어나", "모드 해제", "돌아와", "깨어나", "다시 시작",
            "정상 모드", "활성화", "준비해"
        )
    }

    @Volatile
    var currentMode: FocusMode = FocusMode.NORMAL
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            eventBus.events.collect { event ->
                try {
                    when (event) {
                        is XRealEvent.InputEvent.VoiceCommand -> handleVoiceCommand(event.text)
                        is XRealEvent.InputEvent.EnrichedVoiceCommand -> handleVoiceCommand(event.text)
                        else -> {}
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "이벤트 처리 오류 (루프 유지됨): ${event::class.simpleName} — ${e.message}", e)
                }
            }
        }
        Log.i(TAG, "FocusModeManager 시작 — 현재 모드: NORMAL")
    }

    fun stop() {
        Log.i(TAG, "FocusModeManager 종료")
    }

    /**
     * 모드 전환. PalmFaceGestureDetector, OperationalDirector에서도 호출.
     */
    fun setMode(mode: FocusMode, reason: String) {
        val previous = currentMode
        if (previous == mode) return
        currentMode = mode
        Log.i(TAG, "FocusMode 전환: $previous → $mode (이유: $reason)")
        scope.launch {
            eventBus.publish(XRealEvent.SystemEvent.FocusModeChanged(mode, reason))
            // 모드 전환 TTS 안내
            val announcement = when (mode) {
                FocusMode.DND -> "방해 금지 모드. 필요할 때 불러주세요."
                FocusMode.PRIVATE -> "개인 모드 활성화. 생명 안전 알림만 유지합니다."
                FocusMode.NORMAL -> "일반 모드 복귀."
                FocusMode.EMERGENCY -> "비상 모드."
            }
            if (previous != FocusMode.NORMAL || mode != FocusMode.DND) {
                // DND는 조용히 전환 (TTS가 방해가 되므로 짧게만)
                eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(announcement))
            }
        }
    }

    /**
     * AI 능동 행동 허용 여부.
     *
     * @param trigger 트리거 종류
     * @return true = AI 동작 허용, false = 억제
     */
    fun canAIAct(trigger: AITrigger): Boolean {
        return when (currentMode) {
            FocusMode.NORMAL -> true
            FocusMode.DND -> when (trigger) {
                AITrigger.USER_COMMAND -> true      // 사용자 직접 명령은 항상 허용
                AITrigger.SAFETY_ALERT -> true      // 낙상/심박 이상 등 안전 알림 허용
                AITrigger.PROACTIVE_VISION,
                AITrigger.PROACTIVE_COACHING,
                AITrigger.PROACTIVE_MEETING,
                AITrigger.PROACTIVE_MISSION -> false // 능동적 AI는 억제
            }
            FocusMode.PRIVATE -> when (trigger) {
                AITrigger.SAFETY_ALERT -> true
                else -> false  // PRIVATE: 안전 알림만
            }
            FocusMode.EMERGENCY -> when (trigger) {
                AITrigger.SAFETY_ALERT, AITrigger.USER_COMMAND -> true
                else -> false
            }
        }
    }

    // =========================================================================
    // 내부 유틸리티
    // =========================================================================

    private fun handleVoiceCommand(text: String) {
        val lower = text.lowercase()

        // NORMAL 복귀 명령 우선 체크
        if (NORMAL_KEYWORDS.any { lower.contains(it) }) {
            setMode(FocusMode.NORMAL, "voice_command")
            return
        }

        // PRIVATE 모드
        if (PRIVATE_KEYWORDS.any { lower.contains(it) }) {
            setMode(FocusMode.PRIVATE, "voice_command")
            return
        }

        // DND 모드
        if (DND_KEYWORDS.any { lower.contains(it) }) {
            setMode(FocusMode.DND, "voice_command")
            return
        }
    }
}

/**
 * AITrigger — AI 능동 행동 트리거 종류.
 * FocusModeManager.canAIAct()에 전달하여 허용 여부 결정.
 */
enum class AITrigger {
    USER_COMMAND,        // 사용자 직접 명령 (음성, 제스처)
    SAFETY_ALERT,        // 생명 안전 (낙상, 심박 이상)
    PROACTIVE_VISION,    // 비전 AI 능동 분석
    PROACTIVE_COACHING,  // 러닝/학습 코칭
    PROACTIVE_MEETING,   // 회의 자료 분석
    PROACTIVE_MISSION    // 미션 에이전트 능동 행동
}
