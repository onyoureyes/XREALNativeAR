package com.xreal.nativear.learning

import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase

/**
 * TrainingReadinessChecker — Colab 모델 학습 준비도 게이트.
 *
 * ## 활성화 조건 (AND)
 * 1. 앱 최초 실행 후 90일 이상 경과
 * 2. ai_interventions 기록 ≥ 500건 (OutcomeTracker 데이터 충분)
 * 3. memory_nodes(level=0) ≥ 1000개 (충분한 학습 데이터)
 *
 * ## 사용 흐름
 * - AppBootstrapper가 first_launch_ms를 structured_data에 기록 (최초 1회)
 * - ModelSyncWorker.doWork()에서 canProceed() 게이트 확인
 * - DebugHUD / TTS용 getReadinessReport() 제공
 *
 * ## Koin 등록
 * AppModule.kt: single { TrainingReadinessChecker(database = get()) }
 */
class TrainingReadinessChecker(
    private val database: UnifiedMemoryDatabase
) {
    private val TAG = "TrainingReadinessChecker"

    companion object {
        private const val MIN_DAYS = 90L
        private const val MIN_OUTCOME_RECORDS = 500
        private const val MIN_MEMORY_NODES = 1000
        const val DOMAIN = "training_state"
        const val KEY_FIRST_LAUNCH_MS = "first_launch_ms"
        const val KEY_READINESS_UNLOCKED_MS = "readiness_unlocked_ms"
    }

    /**
     * ModelSyncWorker 실행 허용 여부.
     * 모든 조건 충족 시 true + readiness_unlocked_ms 기록.
     */
    fun canProceed(): Boolean {
        val ready = isDaysConditionMet() && isRowCountConditionMet()
        if (ready) recordUnlockTimestampIfNeeded()
        return ready
    }

    /**
     * DebugHUD / TTS용 현재 준비도 상태 문자열.
     */
    fun getReadinessReport(): String {
        val firstLaunchMs = getFirstLaunchMs()
        val elapsedDays = if (firstLaunchMs > 0L)
            (System.currentTimeMillis() - firstLaunchMs) / 86_400_000L
        else 0L

        val outcomes = database.getOutcomeRecordCount()
        val memories = database.getMemoryNodeCount()
        val ready = canProceed()

        return buildString {
            appendLine("[훈련 준비도 — TrainingReadinessChecker]")
            appendLine("  경과일: $elapsedDays / $MIN_DAYS 일 ${if (elapsedDays >= MIN_DAYS) "✅" else "⏳"}")
            appendLine("  결과 기록: $outcomes / $MIN_OUTCOME_RECORDS 건 ${if (outcomes >= MIN_OUTCOME_RECORDS) "✅" else "⏳"}")
            appendLine("  메모리 노드: $memories / $MIN_MEMORY_NODES 개 ${if (memories >= MIN_MEMORY_NODES) "✅" else "⏳"}")
            appendLine("  종합 상태: ${if (ready) "✅ 훈련 준비됨" else "⏳ 조건 미충족 — 대기 중"}")
        }
    }

    /**
     * 앱 최초 실행 타임스탬프 기록. AppBootstrapper에서 1회 호출.
     * 이미 기록된 경우 무시.
     */
    fun recordFirstLaunchIfNeeded() {
        if (database.getStructuredDataExact(DOMAIN, KEY_FIRST_LAUNCH_MS) == null) {
            database.upsertStructuredData(DOMAIN, KEY_FIRST_LAUNCH_MS,
                System.currentTimeMillis().toString())
            Log.i(TAG, "first_launch_ms 기록 완료 — 훈련 준비 타이머 시작")
        }
    }

    // ─── 내부 조건 검사 ───

    private fun isDaysConditionMet(): Boolean {
        val firstLaunch = getFirstLaunchMs()
        if (firstLaunch <= 0L) return false
        val elapsed = (System.currentTimeMillis() - firstLaunch) / 86_400_000L
        return elapsed >= MIN_DAYS
    }

    private fun isRowCountConditionMet(): Boolean =
        database.getOutcomeRecordCount() >= MIN_OUTCOME_RECORDS &&
        database.getMemoryNodeCount() >= MIN_MEMORY_NODES

    private fun getFirstLaunchMs(): Long =
        database.getStructuredDataExact(DOMAIN, KEY_FIRST_LAUNCH_MS)
            ?.value?.toLongOrNull() ?: 0L

    private fun recordUnlockTimestampIfNeeded() {
        if (database.getStructuredDataExact(DOMAIN, KEY_READINESS_UNLOCKED_MS) == null) {
            database.upsertStructuredData(DOMAIN, KEY_READINESS_UNLOCKED_MS,
                System.currentTimeMillis().toString())
            Log.i(TAG, "✅ 훈련 준비도 조건 달성 — ModelSync 활성화 기록됨")
        }
    }
}
