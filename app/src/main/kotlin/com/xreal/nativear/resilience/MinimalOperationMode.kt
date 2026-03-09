package com.xreal.nativear.resilience

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.xreal.nativear.core.CapabilityTier
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.DrawCommand
import com.xreal.nativear.DrawElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MinimalOperationMode — TIER_6_MINIMAL 최소 운영 보장.
 *
 * ## 보장 기능 (AI, 네트워크, 안경 없이도 동작)
 * 1. **오디오 라이프로깅**: AudioCaptureManager는 계속 동작 (파일 저장만)
 * 2. **에러 로그**: filesDir/error_log_YYYYMMDD.txt (파일 기반, API 불필요)
 * 3. **개선사항 큐**: 오프라인 큐 → 온라인 복귀 시 Gemini 일괄 처리
 * 4. **TTS 알림**: Android TTS API (네트워크 불필요)
 * 5. **하드코딩 응답**: "지금은 최소 모드입니다. 배터리/네트워크를 확인해주세요."
 * 6. **HUD 상태**: "⚡ 최소 모드" 텍스트 (OverlayView DrawingCommand)
 */
class MinimalOperationMode(
    private val context: Context,
    private val eventBus: GlobalEventBus
) {
    companion object {
        private const val TAG = "MinimalOperationMode"
        private const val HUD_ELEMENT_ID = "minimal_mode_status"
        val HARDCODED_RESPONSES = mapOf(
            "상태" to "⚡ 최소 모드 중. 배터리/네트워크를 확인해주세요.",
            "help" to "현재 오프라인 최소 모드입니다. 오디오 녹음과 기본 TTS만 활성화 상태입니다.",
            "배터리" to "배터리가 매우 낮습니다. 충전기에 연결해주세요.",
            "default" to "지금은 최소 모드입니다. 네트워크나 배터리를 확인해주세요."
        )
    }

    @Volatile var isActive = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val errorLogFile: File by lazy {
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        File(context.filesDir, "error_log_$dateStr.txt")
    }

    // 개선사항 오프라인 큐
    private val improvementQueue = mutableListOf<String>()

    fun activate() {
        if (isActive) return
        isActive = true
        Log.w(TAG, "MinimalOperationMode 활성화 — TIER_6_MINIMAL")

        scope.launch(Dispatchers.Main) {
            // 1. TTS 알림
            eventBus.publish(XRealEvent.ActionRequest.SpeakTTS("최소 모드 전환됨. 배터리나 네트워크를 확인해주세요."))
        }

        scope.launch {
            // 2. HUD 상태 표시 "⚡ 최소 모드"
            publishHudStatus(true)

            // 3. 에러 로그 기록
            logError("MinimalOperationMode 활성화됨")

            // 4. 불필요한 서비스 중단 신호 (CapabilityTierChanged 이벤트로 통보됨)
            // VisionCoordinator, MissionConductor 등은 CapabilityTierChanged 구독으로 자체 처리
        }
    }

    fun deactivate() {
        if (!isActive) return
        isActive = false
        Log.i(TAG, "MinimalOperationMode 비활성화 — 정상 복귀")

        scope.launch {
            // HUD 상태 제거
            publishHudStatus(false)
            eventBus.publish(XRealEvent.ActionRequest.SpeakTTS("시스템 복구됨. 일반 모드로 전환합니다."))

            // 개선사항 큐 처리 (온라인 복귀 시)
            flushImprovementQueue()
        }
    }

    /**
     * 에러 로그 파일에 기록 (API 불필요, 파일 시스템만 사용).
     */
    fun logError(message: String) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            errorLogFile.appendText("[$timestamp] $message\n")
        } catch (e: Exception) {
            Log.e(TAG, "에러 로그 기록 실패: ${e.message}")
        }
    }

    /**
     * 개선사항 큐에 추가 (오프라인 상태에서 누적).
     */
    fun queueImprovement(note: String) {
        synchronized(improvementQueue) {
            improvementQueue.add("${System.currentTimeMillis()}: $note")
            Log.d(TAG, "개선사항 큐 추가 (총 ${improvementQueue.size}개): $note")
        }
    }

    /**
     * 하드코딩 응답 반환.
     * 오프라인 상태에서 AI 없이 음성 명령에 최소 응답.
     */
    fun getHardcodedResponse(query: String): String {
        val lower = query.lowercase()
        return when {
            lower.contains("상태") -> HARDCODED_RESPONSES["상태"]!!
            lower.contains("배터리") -> HARDCODED_RESPONSES["배터리"]!!
            lower.contains("help") || lower.contains("도움") -> HARDCODED_RESPONSES["help"]!!
            else -> HARDCODED_RESPONSES["default"]!!
        }
    }

    // =========================================================================
    // 내부 유틸리티
    // =========================================================================

    private suspend fun publishHudStatus(show: Boolean) {
        if (show) {
            eventBus.publish(
                XRealEvent.ActionRequest.DrawingCommand(
                    DrawCommand.Add(
                        DrawElement.Text(
                            id = HUD_ELEMENT_ID,
                            x = 2f, y = 95f,
                            text = "⚡ 최소 모드",
                            size = 14f,
                            color = "#FF6600",  // 주황색
                            opacity = 0.9f
                        )
                    )
                )
            )
        } else {
            eventBus.publish(
                XRealEvent.ActionRequest.DrawingCommand(
                    DrawCommand.Remove(HUD_ELEMENT_ID)
                )
            )
        }
    }

    private fun flushImprovementQueue() {
        synchronized(improvementQueue) {
            if (improvementQueue.isEmpty()) return
            Log.i(TAG, "개선사항 큐 ${improvementQueue.size}개 처리 시작")
            // 개선사항들을 하나의 SaveMemory 이벤트로 발행
            val combined = improvementQueue.joinToString("\n")
            scope.launch {
                eventBus.publish(
                    XRealEvent.ActionRequest.SaveMemory(
                        content = "⚡ 최소 모드 중 수집된 개선사항:\n$combined",
                        role = "system",
                        metadata = "minimal_mode_improvements"
                    )
                )
            }
            improvementQueue.clear()
        }
    }
}
