package com.xreal.nativear.learning

import android.content.Context
import android.util.Log
import com.xreal.nativear.companion.AICallAction
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * RoutineClassifier — .tflite 온디바이스 AI 호출 결정 분류기.
 *
 * ## 모델 위치
 * - `filesDir/models/routine_classifier.tflite` (ModelSyncWorker가 다운로드)
 * - 모델 없으면 `null` 반환 → TokenOptimizer 기존 로직 그대로 동작
 *
 * ## 입력 (5 float, 정규화됨)
 * | 인덱스 | 피처 | 범위 |
 * |--------|------|------|
 * | 0 | situation_idx | 0~30 / 30.0 |
 * | 1 | hour_normalized | hourOfDay / 23.0 |
 * | 2 | userstate_idx | 0~7 / 7.0 |
 * | 3 | log1p(pattern_count) | log1p(count) / log1p(100) |
 * | 4 | outcome_ema | 0.0~1.0 |
 *
 * ## 출력 (4 float, softmax)
 * [p_SKIP, p_MINIMAL, p_STANDARD, p_ENRICHED]
 *
 * ## 재로딩
 * ModelSyncWorker가 새 모델 다운로드 후 `reload()` 호출 → 핫스왑
 *
 * ## LiteRT V1 API
 * `org.tensorflow.lite.Interpreter` (ai-common/UnifiedAIOrchestrator 패턴 동일)
 */
class RoutineClassifier(private val context: Context) {

    companion object {
        private const val TAG = "RoutineClassifier"
        const val MODEL_RELATIVE_PATH = "models/routine_classifier.tflite"

        // 입력 피처 정규화 상수
        private const val MAX_SITUATION_IDX = 30f
        private const val MAX_HOUR = 23f
        private const val MAX_USER_STATE_IDX = 7f
        private val LOG1P_100 = Math.log1p(100.0).toFloat()

        // 상황명 → 인덱스 매핑 (Colab 노트북과 동일 순서 유지)
        private val SITUATION_INDEX = mapOf(
            "RUNNING" to 0, "GYM_WORKOUT" to 1, "WALKING_EXERCISE" to 2,
            "COMMUTING" to 3, "TRAVELING_NEW_PLACE" to 4, "TRAVELING_TRANSIT" to 5,
            "IN_MEETING" to 6, "STUDYING" to 7, "LANGUAGE_LEARNING" to 8,
            "TEACHING" to 9, "AT_DESK_WORKING" to 10, "GUITAR_PRACTICE" to 11,
            "READING" to 12, "COOKING" to 13, "SOCIAL_GATHERING" to 14,
            "SHOPPING" to 15, "DINING_OUT" to 16, "PHONE_CALL" to 17,
            "RELAXING_HOME" to 18, "MORNING_ROUTINE" to 19,
            "EVENING_WIND_DOWN" to 20, "LUNCH_BREAK" to 21,
            "SLEEPING_PREP" to 22, "UNKNOWN" to 23, "CUSTOM" to 24
        )

        // 사용자 상태 → 인덱스 매핑
        private val USER_STATE_INDEX = mapOf(
            "FOCUSED" to 0, "ACTIVE" to 1, "NORMAL" to 2, "TIRED" to 3,
            "STRESSED" to 4, "IDLE" to 5, "MOVING" to 6, "UNKNOWN" to 7
        )

        // 출력 인덱스 → AICallAction (Colab 노트북 LABEL_ORDER와 동일)
        private val OUTPUT_ACTIONS = arrayOf(
            AICallAction.SKIP,
            AICallAction.MINIMAL,
            AICallAction.STANDARD,
            AICallAction.ENRICHED
        )
    }

    @Volatile private var interpreter: Interpreter? = null
    private val modelFile: File get() = File(context.filesDir, MODEL_RELATIVE_PATH)

    init {
        tryLoadModel()
    }

    fun isReady(): Boolean = interpreter != null

    /**
     * ModelSyncWorker 다운로드 완료 후 핫스왑 호출.
     */
    fun reload() {
        Log.i(TAG, "RoutineClassifier 재로딩...")
        interpreter?.close()
        interpreter = null
        tryLoadModel()
    }

    /**
     * 상황 기반 AI 호출 결정 분류.
     * @return null = 모델 없음 (TokenOptimizer 기존 로직 사용)
     */
    fun classify(
        situation: String?,
        hourOfDay: Int,
        userState: String?,
        patternCount: Int,
        outcomeEma: Float
    ): AICallAction? {
        val interp = interpreter ?: return null  // 모델 없으면 null

        return try {
            // 1. 입력 피처: Array[1][5] (batch_size=1, features=5)
            val input = buildInputVector(situation, hourOfDay, userState, patternCount, outcomeEma)

            // 2. 출력 버퍼: Array[1][4] (softmax 4클래스)
            val output = Array(1) { FloatArray(4) }

            // 3. LiteRT V1 추론 — runForMultipleInputsOutputs로 run 이름 충돌 방지
            // (org.tensorflow.lite.Interpreter.run()은 Kotlin T.run {}과 이름 충돌)
            interp.runForMultipleInputsOutputs(
                arrayOf<Any>(input),
                mapOf(0 to output as Any)
            )

            // 4. argmax → AICallAction
            val probs = output[0]
            val maxIdx = probs.indices.maxByOrNull { probs[it] } ?: 2  // 기본값 STANDARD
            val action = OUTPUT_ACTIONS[maxIdx]

            Log.d(TAG, "분류 결과: $action (${formatProbs(probs)}) " +
                    "situation=$situation, hour=$hourOfDay, pattern=$patternCount")
            action

        } catch (e: Exception) {
            Log.e(TAG, "classify 예외: ${e.message}")
            null
        }
    }

    // ─── 내부 구현 ───

    private fun tryLoadModel() {
        if (!modelFile.exists()) {
            Log.d(TAG, "모델 파일 없음: ${modelFile.absolutePath} — Colab 학습 후 ModelSyncWorker 대기")
            return
        }

        try {
            val buffer = loadModelFile()
            val options = Interpreter.Options().apply {
                setNumThreads(2)
                setUseNNAPI(false)  // 분류기는 CPU로 충분 (5 float 입력)
            }
            interpreter = Interpreter(buffer, options)
            Log.i(TAG, "RoutineClassifier 로딩 완료: ${modelFile.length() / 1024}KB")
        } catch (e: Exception) {
            Log.e(TAG, "모델 로딩 실패: ${e.message}")
            interpreter = null
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fileInputStream = modelFile.inputStream()
        val fileChannel: FileChannel = fileInputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            0, fileChannel.size()
        )
    }

    private fun buildInputVector(
        situation: String?,
        hourOfDay: Int,
        userState: String?,
        patternCount: Int,
        outcomeEma: Float
    ): Array<FloatArray> {
        val situationIdx = (SITUATION_INDEX[situation] ?: 23).toFloat()
        val userStateIdx = (USER_STATE_INDEX[userState] ?: 7).toFloat()
        val log1pPattern = Math.log1p(patternCount.toDouble()).toFloat()

        return Array(1) {
            floatArrayOf(
                situationIdx / MAX_SITUATION_IDX,
                hourOfDay.toFloat() / MAX_HOUR,
                userStateIdx / MAX_USER_STATE_IDX,
                log1pPattern / LOG1P_100,
                outcomeEma.coerceIn(0f, 1f)
            )
        }
    }

    private fun formatProbs(probs: FloatArray): String {
        return probs.mapIndexed { i, p ->
            "${OUTPUT_ACTIONS[i].name}=${String.format("%.2f", p)}"
        }.joinToString(", ")
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
