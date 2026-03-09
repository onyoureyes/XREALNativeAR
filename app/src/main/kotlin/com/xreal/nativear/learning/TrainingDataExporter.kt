package com.xreal.nativear.learning

import android.content.Context
import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TrainingDataExporter — decision_log DB → CSV 파일 변환.
 *
 * ## 출력 컬럼
 * situation, hour_of_day, user_state, pattern_count, tokens_used, outcome, satisfaction
 *
 * ## 라벨 매핑 (Colab 학습용)
 * - FOLLOWED + pattern_count >= 3  → SKIP   (루틴, DB 재사용 대상)
 * - FOLLOWED + pattern_count < 3   → MINIMAL (첫 성공)
 * - DISMISSED / IGNORED            → STANDARD (개입 불필요)
 * - tokens_used > 300 + FOLLOWED   → ENRICHED (깊은 분석 가치 있음)
 *
 * ## 조건
 * - outcome != 'PENDING' (결과 확정된 것만)
 * - timestamp > now - 90일
 * - 최소 50행 이상이어야 업로드 의미 있음
 */
class TrainingDataExporter(
    private val context: Context,
    private val database: UnifiedMemoryDatabase
) {
    companion object {
        private const val TAG = "TrainingDataExporter"
        const val MIN_ROWS_FOR_UPLOAD = 50      // 이 이하면 업로드 스킵
        private const val WINDOW_DAYS = 90      // 최근 90일
        private const val TOKENS_ENRICHED_THRESHOLD = 300

        // CSV 헤더
        private val CSV_HEADER = "situation,hour_of_day,user_state,pattern_count,tokens_used,outcome,satisfaction,label\n"
    }

    /**
     * decision_log를 읽어 CSV 파일로 변환.
     * @return CSV 파일 (캐시 디렉터리), 실패 시 null
     */
    fun exportToCsv(): File? {
        return try {
            val rows = queryDecisionLog()
            if (rows.isEmpty()) {
                Log.w(TAG, "decision_log가 비어있음 — CSV 생성 스킵")
                return null
            }

            val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val csvFile = File(context.cacheDir, "training_export_$dateStr.csv")

            csvFile.bufferedWriter().use { writer ->
                writer.write(CSV_HEADER)
                rows.forEach { row ->
                    val label = computeLabel(row)
                    writer.write(buildCsvRow(row, label))
                }
            }

            Log.i(TAG, "CSV 생성 완료: ${rows.size}행 → ${csvFile.absolutePath} (${csvFile.length() / 1024}KB)")
            csvFile

        } catch (e: Exception) {
            Log.e(TAG, "exportToCsv 예외: ${e.message}")
            null
        }
    }

    /**
     * 업로드 가능한 행 수 확인 (50 미만이면 스킵 권장).
     */
    fun getExportableRowCount(): Int {
        return try {
            val cutoffMs = System.currentTimeMillis() - WINDOW_DAYS * 24 * 60 * 60 * 1000L
            val db = database.readableDatabase
            val cursor = db.rawQuery(
                "SELECT COUNT(*) FROM decision_log WHERE outcome != 'PENDING' AND timestamp > ?",
                arrayOf(cutoffMs.toString())
            )
            cursor.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "행 수 조회 실패: ${e.message}")
            0
        }
    }

    // ─── 내부 구현 ───

    private data class DecisionRow(
        val situation: String,
        val hourOfDay: Int,
        val userState: String,
        val patternCount: Int,
        val tokensUsed: Int,
        val outcome: String,
        val satisfaction: Float
    )

    private fun queryDecisionLog(): List<DecisionRow> {
        val cutoffMs = System.currentTimeMillis() - WINDOW_DAYS * 24 * 60 * 60 * 1000L
        val rows = mutableListOf<DecisionRow>()

        try {
            val db = database.readableDatabase
            val cursor = db.rawQuery(
                """
                SELECT situation, hour_of_day, user_state, pattern_count,
                       tokens_used, outcome, satisfaction
                FROM decision_log
                WHERE outcome != 'PENDING'
                  AND timestamp > ?
                ORDER BY timestamp DESC
                """.trimIndent(),
                arrayOf(cutoffMs.toString())
            )

            cursor.use {
                while (it.moveToNext()) {
                    rows.add(
                        DecisionRow(
                            situation    = it.getString(0) ?: "UNKNOWN",
                            hourOfDay    = it.getInt(1),
                            userState    = it.getString(2) ?: "NORMAL",
                            patternCount = it.getInt(3),
                            tokensUsed   = it.getInt(4),
                            outcome      = it.getString(5) ?: "IGNORED",
                            satisfaction = it.getFloat(6)
                        )
                    )
                }
            }
            Log.d(TAG, "decision_log 조회: ${rows.size}행")
        } catch (e: Exception) {
            Log.e(TAG, "decision_log 쿼리 실패: ${e.message}")
        }

        return rows
    }

    /**
     * 라벨 결정 로직.
     * tokens_used > 300 + FOLLOWED 조건을 ENRICHED로 먼저 체크 (고비용 가치 있는 호출).
     */
    private fun computeLabel(row: DecisionRow): String {
        return when {
            row.outcome == "FOLLOWED" && row.tokensUsed > TOKENS_ENRICHED_THRESHOLD -> "ENRICHED"
            row.outcome == "FOLLOWED" && row.patternCount >= 3 -> "SKIP"
            row.outcome == "FOLLOWED" && row.patternCount < 3  -> "MINIMAL"
            row.outcome == "DISMISSED" || row.outcome == "IGNORED" -> "STANDARD"
            else -> "STANDARD"  // PARTIAL 등 기타
        }
    }

    private fun buildCsvRow(row: DecisionRow, label: String): String {
        // CSV 이스케이프: 쉼표/따옴표가 포함된 필드는 따옴표로 감싸기
        val situation = row.situation.csvEscape()
        val userState = row.userState.csvEscape()
        return "$situation,${row.hourOfDay},$userState,${row.patternCount}," +
                "${row.tokensUsed},${row.outcome},${row.satisfaction},$label\n"
    }

    private fun String.csvEscape(): String {
        return if (this.contains(',') || this.contains('"') || this.contains('\n')) {
            "\"${this.replace("\"", "\"\"")}\""
        } else this
    }
}
