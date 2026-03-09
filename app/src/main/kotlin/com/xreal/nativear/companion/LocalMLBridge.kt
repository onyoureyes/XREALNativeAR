package com.xreal.nativear.companion

import android.util.Log
import com.xreal.nativear.context.LifeSituation
import com.xreal.nativear.learning.RoutineClassifier

/**
 * LocalMLBridge — RoutineClassifier ↔ SituationLifecycleManager 연결 브리지 (Phase F-6).
 *
 * ## 역할
 * TFLite 온디바이스 모델(RoutineClassifier)이 준비되었을 때,
 * SituationLifecycleManager에 `localModelPath`를 등록하여
 * MASTERED 상황의 ProcessingRing을 `LOCAL_ML`로 승격.
 *
 * ## LOCAL_ML 처리링 활성화 조건
 * 1. RoutineClassifier 모델 파일 존재 (`filesDir/models/routine_classifier.tflite`)
 * 2. 해당 상황의 masteryLevel == MASTERED
 * 3. localModelPath가 아직 미등록 (중복 등록 방지)
 *
 * ## 호출 시점
 * - [AppBootstrapper.start()] → 앱 시작 시 기존 모델 확인
 * - [ModelSyncWorker.doWork()] → 새 모델 다운로드 후 핫스왑
 *
 * ## 설계 원칙
 * - RoutineClassifier는 상황별 전용 모델이 아닌 단일 공유 모델
 *   (situation을 입력 피처로 받아 모든 상황 커버)
 * - 따라서 모델 하나가 준비되면 → 모든 MASTERED 상황에 등록
 * - 모델 없으면 MASTERED 상황도 WARMUP_CACHE 폴백 (기존 동작 유지)
 */
object LocalMLBridge {

    private const val TAG = "LocalMLBridge"

    /**
     * RoutineClassifier가 준비된 경우 모든 MASTERED 상황에 LOCAL_ML 활성화.
     *
     * 이미 localModelPath가 등록된 상황은 건너뜀 (멱등성 보장).
     *
     * @param routineClassifier 온디바이스 분류기
     * @param lifecycleManager 숙련도 사다리 관리자
     * @return 새로 LOCAL_ML로 승격된 상황 수
     */
    fun activateForMasteredSituations(
        routineClassifier: RoutineClassifier,
        lifecycleManager: SituationLifecycleManager
    ): Int {
        if (!routineClassifier.isReady()) {
            Log.d(TAG, "RoutineClassifier 모델 없음 — LOCAL_ML 활성화 보류 " +
                "(Colab 학습 후 ModelSyncWorker 자동 다운로드)")
            return 0
        }

        val modelPath = RoutineClassifier.MODEL_RELATIVE_PATH
        var activatedCount = 0

        LifeSituation.values()
            .filter { it != LifeSituation.UNKNOWN && it != LifeSituation.CUSTOM }
            .forEach { situation ->
                val record = lifecycleManager.getRecord(situation) ?: return@forEach

                // MASTERED 상황만 대상
                if (record.masteryLevel != SituationLifecycleManager.MasteryLevel.MASTERED) {
                    return@forEach
                }

                // 이미 등록됐으면 스킵 (멱등성)
                if (record.localModelPath == modelPath) {
                    return@forEach
                }

                // LOCAL_ML 활성화
                lifecycleManager.setLocalModelPath(situation, modelPath)
                activatedCount++
                Log.i(TAG, "LOCAL_ML 활성화: ${situation.displayName} → $modelPath")
            }

        if (activatedCount > 0) {
            Log.i(TAG, "LOCAL_ML 활성화 완료: $activatedCount 개 상황 " +
                "(RoutineClassifier → MASTERED 상황 온디바이스 추론)")
        } else {
            Log.d(TAG, "LOCAL_ML 활성화 대상 없음 " +
                "(MASTERED 상황 없거나 이미 모두 등록됨)")
        }

        return activatedCount
    }

    /**
     * 상태 요약 문자열 (디버깅/AI 도구용).
     */
    fun getStatusSummary(
        routineClassifier: RoutineClassifier,
        lifecycleManager: SituationLifecycleManager
    ): String = buildString {
        appendLine("[LocalMLBridge — Phase F-6]")
        appendLine("RoutineClassifier 준비: ${routineClassifier.isReady()}")
        val masteredSituations = LifeSituation.values()
            .filter { it != LifeSituation.UNKNOWN && it != LifeSituation.CUSTOM }
            .mapNotNull { s -> lifecycleManager.getRecord(s)?.takeIf {
                it.masteryLevel == SituationLifecycleManager.MasteryLevel.MASTERED
            }?.let { s to it } }
        appendLine("MASTERED 상황 수: ${masteredSituations.size}")
        val localMLCount = masteredSituations.count { (_, r) -> r.localModelPath != null }
        appendLine("LOCAL_ML 활성 수: $localMLCount / ${masteredSituations.size}")
        if (masteredSituations.isNotEmpty()) {
            masteredSituations.forEach { (s, r) ->
                val status = if (r.localModelPath != null) "🤖 LOCAL_ML" else "⚡ WARMUP_CACHE(폴백)"
                appendLine("  ${s.displayName}: $status")
            }
        }
    }.trim()
}
