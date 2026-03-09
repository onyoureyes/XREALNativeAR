package com.xreal.nativear.running

import android.util.Log
import com.xreal.nativear.AIAgentManager
import com.xreal.nativear.WeatherService
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.policy.PolicyReader
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.router.running.CoachMessages
import kotlinx.coroutines.*

/**
 * RunningCoachPersona — Gemini AI 기반 보조 러닝 코칭.
 *
 * ## 역할 분리
 * **실시간 코칭**: FormRouter → InterventionRouter → HUD/TTS (즉각 반응, 규칙 기반)
 * **AI 보조 코칭**: 이 클래스 (5분 간격, 컨텍스트 분석, 다양한 표현)
 *
 * ## 호출 시나리오
 * 1. **정기 코칭** (5분마다): 전체 역학 스냅샷 + 라우터 분석 → Gemini → TTS
 * 2. **포스트런 분석**: 세션 종료 후 총평 + 개선점
 * 3. **에스컬레이션 조언**: InterventionRouter에서 3회 동일 이슈 감지 시
 *    → 기존 정적 메시지와 다른 관점의 구체적 조언 요청
 *
 * ## 에러 핸들링
 * 모든 Gemini 호출에 try-catch. 에스컬레이션 시 Gemini 실패하면
 * CoachMessages 정적 메시지로 fallback.
 *
 * @see RunningCoachManager.executeIntervention 에스컬레이션 트리거
 * @see CoachMessages 정적 HUD/TTS 메시지
 */
class RunningCoachPersona(
    private val eventBus: GlobalEventBus,
    private val aiAgentManager: AIAgentManager,
    private val weatherService: WeatherService,
    private val runningCoachManager: RunningCoachManager
) {
    private val TAG = "RunningCoachPersona"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var coachingJob: Job? = null

    companion object {
        const val COACHING_SYSTEM_PROMPT = """당신은 전문 러닝 코치입니다. AR 글래스를 착용한 사용자가 달리기를 하고 있습니다.

주요 원칙:
- 매우 짧게 (15자 이내) 한 문장으로 코칭하세요.
- 숫자와 데이터 기반으로 구체적인 조언을 하세요.
- 격려와 동기 부여를 해주세요.
- 달리기 중이므로 간결하고 이해하기 쉬운 표현만 사용하세요.
- [라우터 분석] 섹션의 자동 감지 결과를 참고하되, 더 맥락 있는 조언을 해주세요.
- 같은 문제가 반복되면 다른 표현으로 설명하세요.

역학 코칭:
- 케이던스 170-180spm이 이상적. 160 이하면 "발을 더 빠르게" 조언.
- 수직 진동(vertical oscillation) 8cm 이상이면 "몸을 낮게" 조언.
- 머리 안정도 80 이상이 좋음. 60 이하면 "시선을 전방으로" 조언.
- 호흡 불규칙 시 "호흡 리듬 맞추세요" 조언.
- 접지시간(GCT) 300ms 이상이면 "착지를 가볍게" 조언.

생체 데이터 코칭 (워치):
- HR Zone 1-2 (50-70% maxHR): 회복/유산소 기초, 대화 가능한 강도.
- HR Zone 3 (70-80%): 유산소 훈련, 약간 힘든 정도.
- HR Zone 4 (80-90%): 역치 훈련, 대화 어려움.
- HR Zone 5 (90%+): 최대 강도, 장시간 유지 불가.
- HRV RMSSD < 20ms: 피로 누적, 회복 구간 권장.
- SpO2 < 95%: 호흡 주의. < 92%: 즉시 속도 줄이기.
- 피부온도 > 38.5°C: 과열 위험, 수분보충 + 그늘 이동.
- HR drift(같은 페이스에서 HR 지속 상승): 탈수 또는 피로 징후."""

        // Reduced from 60s to 5 min: routers handle real-time coaching now
        val COACHING_INTERVAL_MS: Long get() = PolicyReader.getLong("running.coaching_interval_ms", 300_000L)
    }

    fun startCoaching() {
        coachingJob = scope.launch {
            delay(30_000L) // Wait 30s before first coaching
            while (isActive) {
                generateCoachingAdvice()
                delay(COACHING_INTERVAL_MS)
            }
        }
    }

    private fun generateCoachingAdvice() {
        val snapshot = getCurrentDynamicsSnapshot()

        val context = buildString {
            appendLine("[현재 러닝 데이터]")
            appendLine("시간: ${snapshot.elapsedMin}분 ${snapshot.elapsedSec}초")
            appendLine("거리: ${String.format("%.2f", snapshot.distanceKm)}km")
            appendLine("케이던스: ${snapshot.cadence.toInt()} spm")
            appendLine("수직 진동: ${String.format("%.1f", snapshot.verticalOscillation)}cm")
            appendLine("접지시간: ${snapshot.gct.toInt()}ms")
            appendLine("지면반발력: ${String.format("%.1f", snapshot.grf)}G")
            appendLine("머리 안정도: ${snapshot.headStability.toInt()}/100")
            appendLine("좌우 밸런스: ${String.format("%.2f", snapshot.lateralBalance)}")
            if (snapshot.breathingBpm > 0) appendLine("호흡수: ${snapshot.breathingBpm.toInt()} BPM")

            // Watch biometrics
            if (snapshot.heartRateBpm > 0 || snapshot.spo2Percent > 0 || snapshot.skinTempCelsius > 0) {
                appendLine()
                appendLine("[워치 생체 데이터]")
                if (snapshot.heartRateBpm > 0) appendLine("심박수: ${snapshot.heartRateBpm.toInt()} bpm")
                if (snapshot.hrvRmssd > 0) appendLine("HRV(RMSSD): ${String.format("%.1f", snapshot.hrvRmssd)}ms")
                if (snapshot.spo2Percent > 0) appendLine("SpO2: ${snapshot.spo2Percent}%")
                if (snapshot.skinTempCelsius > 0) appendLine("피부온도: ${String.format("%.1f", snapshot.skinTempCelsius)}°C")
            }

            // Router analysis summary
            appendLine()
            appendLine("[라우터 분석]")
            appendLine(getRouterSummary())
        }

        val prompt = "$context\n\n위 데이터와 라우터 분석을 기반으로 한 문장의 러닝 코칭을 해주세요."
        try {
            aiAgentManager.processWithGemini(prompt, COACHING_SYSTEM_PROMPT)
        } catch (e: Exception) {
            Log.w(TAG, "Gemini coaching advice failed: ${e.message}")
        }
    }

    fun generatePostRunAnalysis(summary: RunningSession.SessionSummary) {
        scope.launch {
            val analysisPrompt = buildString {
                appendLine("러닝 세션이 끝났습니다. 종합 분석을 해주세요:")
                appendLine("총 거리: ${String.format("%.2f", summary.totalDistanceMeters / 1000f)}km")
                appendLine("총 시간: ${summary.totalDurationMs / 60000}분")
                val avgMin = summary.avgPaceMinPerKm.toInt()
                val avgSec = ((summary.avgPaceMinPerKm - avgMin) * 60).toInt()
                appendLine("평균 페이스: ${avgMin}'${String.format("%02d", avgSec)}\"")
                appendLine("랩 수: ${summary.laps.size}")
                for (lap in summary.laps) {
                    appendLine("  Lap ${lap.number}: ${lap.durationMs / 1000}초, ${String.format("%.0f", lap.distanceMeters)}m")
                }
                appendLine()
                appendLine("[라우터 분석]")
                appendLine(getRouterSummary())
                appendLine("\n3문장 이내로 오늘의 러닝을 평가하고, 다음에 개선할 점을 알려주세요.")
            }

            try {
                aiAgentManager.processWithGemini(analysisPrompt, COACHING_SYSTEM_PROMPT)
            } catch (e: Exception) {
                Log.w(TAG, "Gemini post-run analysis failed: ${e.message}")
            }
        }
    }

    fun generateTargetedAdvice(issueAction: String, metadata: Map<String, Any>) {
        scope.launch {
            val targetedPrompt = buildString {
                appendLine("사용자의 ${issueAction} 문제가 지속적으로 감지되었습니다.")
                appendLine("구체적 수치: $metadata")
                val existingMsg = CoachMessages.getTtsMessage(issueAction) ?: "기본 조언"
                appendLine("기존 '$existingMsg' 조언을 이미 했지만 개선이 없습니다.")
                appendLine("다른 관점에서 15자 이내로 구체적 조언을 해주세요.")
            }
            try {
                aiAgentManager.processWithGemini(targetedPrompt, COACHING_SYSTEM_PROMPT)
            } catch (e: Exception) {
                Log.w(TAG, "Gemini targeted advice failed: ${e.message}")
                // Fallback to static message
                val fallback = CoachMessages.getTtsMessage(issueAction)
                if (fallback != null) {
                    eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(fallback))
                }
            }
        }
    }

    private fun getRouterSummary(): String {
        val formMetrics = runningCoachManager.getFormRouterMetrics()
        val interventionMetrics = runningCoachManager.getInterventionRouterMetrics()

        return buildString {
            appendLine("- FormRouter: ${formMetrics.totalDecisions}회 분석, 억제율 ${String.format("%.0f", formMetrics.suppressionRate() * 100)}%")
            appendLine("- InterventionRouter: ${interventionMetrics.totalDecisions}회 판단, 실제 개입 ${interventionMetrics.totalDecisions - interventionMetrics.totalSuppressed}회")
        }
    }

    private fun getCurrentDynamicsSnapshot(): DynamicsSnapshot {
        val session = runningCoachManager.session
        val analyzer = runningCoachManager.dynamicsAnalyzer
        val elapsed = session.getElapsedSeconds()
        val stability = analyzer.computeHeadStability()

        return DynamicsSnapshot(
            elapsedMin = (elapsed / 60).toInt(),
            elapsedSec = (elapsed % 60).toInt(),
            distanceKm = session.totalDistanceMeters / 1000f,
            cadence = analyzer.computeCadence(),
            verticalOscillation = analyzer.computeVerticalOscillation(),
            gct = analyzer.computeGroundContactTime(),
            grf = analyzer.computeGroundReactionForce(),
            headStability = stability?.stabilityScore ?: 0f,
            lateralBalance = stability?.lateralBalance ?: 0f,
            breathingBpm = 0f, // Updated by BreathingMetrics events
            heartRateBpm = runningCoachManager.lastWatchHr,
            hrvRmssd = runningCoachManager.lastWatchHrv,
            spo2Percent = runningCoachManager.lastWatchSpO2,
            skinTempCelsius = runningCoachManager.lastWatchSkinTemp
        )
    }

    data class DynamicsSnapshot(
        val elapsedMin: Int, val elapsedSec: Int,
        val distanceKm: Float,
        val cadence: Float, val verticalOscillation: Float,
        val gct: Float, val grf: Float, val headStability: Float,
        val lateralBalance: Float, val breathingBpm: Float,
        // Watch biometrics
        val heartRateBpm: Float = 0f,
        val hrvRmssd: Float = 0f,
        val spo2Percent: Int = 0,
        val skinTempCelsius: Float = 0f
    )

    fun stopCoaching() {
        coachingJob?.cancel()
    }

    fun release() {
        stopCoaching()
        scope.cancel()
    }
}
