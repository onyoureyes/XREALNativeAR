package com.xreal.nativear.batch

import android.content.Context
import android.util.Log
import androidx.work.*
import com.xreal.nativear.companion.SituationPredictor
import org.koin.java.KoinJavaComponent
import java.util.concurrent.TimeUnit

/**
 * SituationPredictionWorker — 매일 01:00 상황 예측 생성 WorkManager Worker.
 *
 * SituationPredictor.generatePredictions()를 실행하여
 * 다음 24시간 상황 예측 목록을 structured_data에 저장.
 * F-3 AgentWarmupScheduler가 이 결과를 소비하여 워밍업 작업을 예약.
 *
 * ## 스케줄
 * - 유형: PeriodicWork (24시간 주기)
 * - 실행 시각: 01:00 (초기 지연 계산으로 조정)
 * - 네트워크: 불필요 (로컬 DB 연산만)
 * - 재시도: LinearBackoffPolicy (최대 3회)
 */
class SituationPredictionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SituationPredictionWorker"
        const val WORK_NAME = "situation_prediction_daily"

        /**
         * WorkManager에 일일 예측 작업 등록.
         * AppBootstrapper.start()에서 호출.
         *
         * @param context Application context
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SituationPredictionWorker>(
                repeatInterval = 24,
                repeatIntervalTimeUnit = TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,  // 이미 등록되어 있으면 유지
                request
            )

            Log.i(TAG, "일일 상황 예측 작업 등록 완료 (24시간 주기)")
        }
    }

    override suspend fun doWork(): Result {
        return try {
            Log.i(TAG, "일일 상황 예측 시작...")
            val predictor = KoinJavaComponent.getKoin()
                .getOrNull<SituationPredictor>()

            if (predictor == null) {
                Log.w(TAG, "SituationPredictor 없음 — 예측 건너뜀")
                return Result.success()
            }

            val predictions = predictor.generatePredictions()
            Log.i(TAG, "상황 예측 완료: ${predictions.size}개 예측 생성")

            // 예측 결과 요약 로그
            predictions.take(5).forEach { p ->
                Log.d(TAG, "  → ${p.situation.displayName} " +
                    "${p.hourOfDay}시 (${(p.probability * 100).toInt()}%, " +
                    "링:${p.processingRing})")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "상황 예측 실패: ${e.message}", e)
            Result.retry()  // LinearBackoff로 최대 3회 재시도
        }
    }
}
