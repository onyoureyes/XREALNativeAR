package com.xreal.nativear.monitoring

import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DailyValueReporter — 일일 AI 유용성 지표 측정 및 저장.
 *
 * ## 역할 (Phase H)
 * - 매일 22:00 DailyValueReportWorker가 generateAndSaveDailyReport() 호출
 * - 다음날 앱 시작 시 publishMorningBriefing()으로 어제 요약 DebugLog 발행
 * - 7일 평균 valuePerTokenKrw 추적 → AI 투자 ROI 가시화
 *
 * ## 지표 설명
 * - expertConsultations: ai_interventions 당일 건수 (전문가 상담 횟수)
 * - dataDecisions: ai_activity_log.was_accepted=1 당일 건수 (AI 제안 채택 횟수)
 * - memoriesReferenced: ai_activity_log 당일 호출 건수 (컨텍스트 활용 추정)
 * - goalProgressPct: 활성 목표 진행률 평균
 * - tokenCostKrw: 당일 API 비용 (USD → KRW 환산)
 * - valuePerTokenKrw: (전문가상담+채택) / 비용 — 가치 대비 비용 지표
 *
 * ## Koin 등록
 * AppModule.kt: single { DailyValueReporter(get(), get(), getOrNull(), get()) }
 */
class DailyValueReporter(
    private val database: UnifiedMemoryDatabase,
    private val tokenEconomyManager: TokenEconomyManager,
    private val eventBus: GlobalEventBus
) {
    private val TAG = "DailyValueReporter"
    private val KRW_PER_DOLLAR = 1380f
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /**
     * 오늘 날짜 리포트 생성 + DB 저장 + DebugLog 요약 발행.
     * DailyValueReportWorker.doWork()에서 호출.
     */
    suspend fun generateAndSaveDailyReport(): UnifiedMemoryDatabase.DailyValueReport {
        val today = dateFormat.format(Date())
        val stats = tokenEconomyManager.getTodayUsage()

        val expertConsultations = countExpertConsultationsToday(today)
        val dataDecisions = countDataDecisionsToday(today)
        val totalActions = expertConsultations + dataDecisions
        val costKrw = stats.totalCostUsd * KRW_PER_DOLLAR
        val valuePerToken = if (costKrw > 0f && totalActions > 0)
            totalActions.toFloat() / costKrw
        else 0f

        val report = UnifiedMemoryDatabase.DailyValueReport(
            reportDate           = today,
            expertConsultations  = expertConsultations,
            dataDecisions        = dataDecisions,
            memoriesReferenced   = stats.totalCalls,  // 오늘 AI 호출 총 건수
            goalProgressPct      = calculateGoalProgressPct(),
            tokenCostKrw         = costKrw,
            valuePerTokenKrw     = valuePerToken,
            aiSummary            = null  // FeedbackSessionManager 세션 후 업데이트 가능
        )

        database.insertOrUpdateDailyReport(report)
        publishEveningSummary(report)
        Log.i(TAG, "일일 가치 리포트 저장: $today | 비용=${costKrw}원 | 가치/비용=${valuePerToken}")
        return report
    }

    /**
     * 앱 시작 시 어제 리포트 DebugHUD 요약 발행.
     * AppBootstrapper 또는 Phase H 서비스 초기화 시점에 호출.
     */
    fun publishMorningBriefing() {
        scope.launch {
            try {
                val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DATE, -1) }
                val yesterday = dateFormat.format(cal.time)
                val report = database.getDailyReport(yesterday) ?: return@launch
                val weekAvg = database.getAverageValueScore(7)

                val msg = buildString {
                    append("📊 어제(${yesterday}) 가치 리포트 | ")
                    append("전문가상담 ${report.expertConsultations}회 | ")
                    append("AI제안채택 ${report.dataDecisions}회 | ")
                    append("비용 ${"%.0f".format(report.tokenCostKrw)}원 | ")
                    append("가치/비용 ${"%.3f".format(report.valuePerTokenKrw)}/원")
                    if (weekAvg > 0f) append(" (7일평균 ${"%.3f".format(weekAvg)}/원)")
                }
                eventBus.publish(XRealEvent.SystemEvent.DebugLog(msg))
                Log.i(TAG, "Morning briefing published: $msg")
            } catch (e: Exception) {
                Log.w(TAG, "publishMorningBriefing 실패: ${e.message}")
            }
        }
    }

    /**
     * 피드백 점수를 오늘 리포트에 업데이트.
     * FeedbackSessionManager에서 세션 완료 후 호출.
     */
    fun updateFeedbackScore(score: Float) {
        scope.launch {
            try {
                val today = dateFormat.format(Date())
                val existing = database.getDailyReport(today)
                if (existing != null) {
                    database.insertOrUpdateDailyReport(existing.copy(feedbackScore = score))
                    Log.i(TAG, "피드백 점수 업데이트: $today → $score")
                }
            } catch (e: Exception) {
                Log.w(TAG, "updateFeedbackScore 실패: ${e.message}")
            }
        }
    }

    /**
     * 최근 N일 요약 텍스트 (StrategistService 컨텍스트 주입용).
     */
    fun getRecentSummary(days: Int = 7): String {
        val reports = database.getRecentReports(days)
        if (reports.isEmpty()) return "[가치 리포트 없음]"
        return buildString {
            appendLine("[최근 ${days}일 AI 가치 요약 — DailyValueReporter]")
            val avgCost = if (reports.isNotEmpty()) reports.map { it.tokenCostKrw }.average() else 0.0
            val avgValue = if (reports.isNotEmpty()) reports.map { it.valuePerTokenKrw }.average() else 0.0
            val totalConsultations = reports.sumOf { it.expertConsultations }
            val totalDecisions = reports.sumOf { it.dataDecisions }
            appendLine("  전문가 상담 총 ${totalConsultations}회 | AI 제안 채택 총 ${totalDecisions}회")
            appendLine("  평균 일일 비용: ${"%.0f".format(avgCost)}원 | 평균 가치/비용: ${"%.3f".format(avgValue)}/원")
            val latestFeedback = reports.firstOrNull { it.feedbackScore >= 0f }?.feedbackScore
            if (latestFeedback != null) appendLine("  최근 피드백 점수: ${"%.2f".format(latestFeedback)}")
        }
    }

    // ─── 내부 집계 쿼리 ───

    private fun countExpertConsultationsToday(date: String): Int {
        return try {
            // ai_interventions 테이블 당일 기록 건수
            val todayStart = getTodayStartMs(date)
            val todayEnd = todayStart + 86_400_000L
            val db = database.readableDatabase
            val cursor = db.rawQuery(
                "SELECT COUNT(*) FROM ai_interventions WHERE timestamp >= ? AND timestamp < ?",
                arrayOf(todayStart.toString(), todayEnd.toString())
            )
            var count = 0
            if (cursor.moveToFirst()) count = cursor.getInt(0)
            cursor.close()
            count
        } catch (e: Exception) {
            Log.w(TAG, "countExpertConsultationsToday 실패: ${e.message}")
            0
        }
    }

    private fun countDataDecisionsToday(date: String): Int {
        return try {
            // ai_activity_log.was_accepted=1 당일 건수
            val todayStart = getTodayStartMs(date)
            val todayEnd = todayStart + 86_400_000L
            val db = database.readableDatabase
            val cursor = db.rawQuery(
                "SELECT COUNT(*) FROM ai_activity_log WHERE was_accepted = 1 AND timestamp >= ? AND timestamp < ?",
                arrayOf(todayStart.toString(), todayEnd.toString())
            )
            var count = 0
            if (cursor.moveToFirst()) count = cursor.getInt(0)
            cursor.close()
            count
        } catch (e: Exception) {
            Log.w(TAG, "countDataDecisionsToday 실패: ${e.message}")
            0
        }
    }

    private fun calculateGoalProgressPct(): Float {
        return try {
            // user_goals 테이블 progress_pct 평균 (IN_PROGRESS 상태만)
            val db = database.readableDatabase
            val cursor = db.rawQuery(
                "SELECT AVG(progress_pct) FROM user_goals WHERE status = 1", null  // 1 = IN_PROGRESS
            )
            var avg = 0f
            if (cursor.moveToFirst() && !cursor.isNull(0)) avg = cursor.getFloat(0)
            cursor.close()
            avg
        } catch (e: Exception) {
            Log.w(TAG, "calculateGoalProgressPct 실패: ${e.message}")
            0f
        }
    }

    private fun getTodayStartMs(dateStr: String): Long {
        return try {
            dateFormat.parse(dateStr)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun publishEveningSummary(report: UnifiedMemoryDatabase.DailyValueReport) {
        scope.launch {
            try {
                val msg = "📊 [${report.reportDate}] 오늘 AI 비용 ${"%.0f".format(report.tokenCostKrw)}원 | " +
                    "전문가 상담 ${report.expertConsultations}회 | " +
                    "AI 제안 채택 ${report.dataDecisions}회 | " +
                    "가치/비용 ${"%.3f".format(report.valuePerTokenKrw)}/원"
                eventBus.publish(XRealEvent.SystemEvent.DebugLog(msg))
            } catch (e: Exception) {
                Log.w(TAG, "publishEveningSummary 실패: ${e.message}")
            }
        }
    }
}
