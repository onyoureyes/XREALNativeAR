package com.xreal.nativear.learning

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File

/**
 * ModelSyncWorker — Google Drive에서 최신 .tflite 모델을 매일 체크/다운로드.
 *
 * ## EdgeModelDownloadWorker.kt 패턴 재사용
 *
 * ## 흐름
 * 1. DriveApiClient.findLatestModel("xreal_models", "routine_classifier")
 * 2. 로컬 파일 수정 시간과 비교
 * 3. 신규이면 downloadFile() → filesDir/models/routine_classifier.tflite
 * 4. RoutineClassifier.reload() → 즉시 적용
 * 5. EventBus.publish(DebugLog("새 분류기 모델 적용됨"))
 *
 * Constraints: Wi-Fi (DriveTrainingScheduler에서 설정)
 */
class ModelSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ModelSyncWorker"
        const val FOLDER_MODELS = "xreal_models"
        const val MODEL_PREFIX = "routine_classifier"
        const val MODEL_LOCAL_PATH = "models/routine_classifier.tflite"
        // LoRA fine-tuned GGUF 모델
        const val LORA_MODEL_PREFIX = "qwen3_lora"
        const val LORA_MODEL_LOCAL_DIR = "edge_models"
        const val LORA_MODEL_FILENAME = "Qwen3-1.7B-Q4_K_M-lora.gguf"
        // 로컬 백업 서버 (Tailscale, $0 비용)
        private const val LOCAL_SERVER_URL = "http://100.101.127.124:8090"
        private const val LOCAL_SERVER_API_KEY = "3ztg3rzTith-vViCK7FLe5SQNUqqACKTojyDX1oiNe0"
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "모델 동기화 작업 시작")

        return try {
            val classifier = getKoin().getOrNull<RoutineClassifier>() ?: run {
                Log.w(TAG, "RoutineClassifier 없음 — 작업 스킵")
                return Result.success()
            }

            // 1b. ★ Phase H: 훈련 준비도 게이트 (90일 + 데이터 조건)
            val readinessChecker = getKoin().getOrNull<TrainingReadinessChecker>()
            if (readinessChecker != null && !readinessChecker.canProceed()) {
                Log.i(TAG, "훈련 준비 조건 미충족 — 모델 동기화 건너뜀\n${readinessChecker.getReadinessReport()}")
                return Result.success()
            }

            val localFile = File(applicationContext.filesDir, MODEL_LOCAL_PATH)

            // ★ 1차 시도: 로컬 백업 서버 (Tailscale, 무료)
            val downloadedFromLocal = tryDownloadFromLocalServer(localFile)

            // ★ 2차 시도 (폴백): Google Drive
            val success = if (!downloadedFromLocal) {
                Log.d(TAG, "로컬 서버 불가 → Google Drive 시도")
                tryDownloadFromDrive(localFile)
            } else true

            if (!success) {
                Log.d(TAG, "사용 가능한 새 모델 없음")
                return Result.success()
            }

            // 5. RoutineClassifier 핫스왑 (reload)
            classifier.reload()
            Log.i(TAG, "새 분류기 모델 적용됨 (${localFile.length() / 1024}KB)")

            // 5b. ★ Phase F-6: 새 모델 로딩 후 MASTERED 상황에 LOCAL_ML 즉시 활성화
            try {
                val lifecycleManager = getKoin()
                    .getOrNull<com.xreal.nativear.companion.SituationLifecycleManager>()
                if (lifecycleManager != null) {
                    val activated = com.xreal.nativear.companion.LocalMLBridge
                        .activateForMasteredSituations(classifier, lifecycleManager)
                    Log.i(TAG, "LocalMLBridge: $activated 개 MASTERED 상황에 LOCAL_ML 활성화")
                }
            } catch (e: Exception) {
                Log.w(TAG, "LocalMLBridge 활성화 실패 (비치명적): ${e.message}")
            }

            // 6. EventBus 알림
            try {
                val eventBus = getKoin().getOrNull<GlobalEventBus>()
                eventBus?.publish(
                    XRealEvent.SystemEvent.DebugLog(
                        "ModelSyncWorker: 새 RoutineClassifier 모델 적용됨"
                    )
                )
            } catch (_: Exception) {}

            // ─── Part B: LoRA fine-tuned GGUF 모델 동기화 ───
            syncLoraModel()

            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "ModelSyncWorker 예외: ${e.message}")
            Result.retry()
        }
    }

    /**
     * 로컬 백업 서버에서 모델 다운로드 시도 (Tailscale 경유)
     * 서버에 모델이 있고 로컬보다 신규이면 다운로드
     */
    private fun tryDownloadFromLocalServer(localFile: File): Boolean {
        return try {
            val httpClient = getKoin().get<OkHttpClient>()

            // 모델 정보 조회
            val infoRequest = Request.Builder()
                .url("$LOCAL_SERVER_URL/api/models/$MODEL_PREFIX/info")
                .addHeader("Authorization", "Bearer $LOCAL_SERVER_API_KEY")
                .build()
            val infoResponse = httpClient.newCall(infoRequest).execute()
            if (!infoResponse.isSuccessful) {
                Log.d(TAG, "로컬 서버 모델 정보 없음: ${infoResponse.code}")
                return false
            }
            val infoJson = org.json.JSONObject(infoResponse.body?.string() ?: "{}")
            val remoteModifiedAt = infoJson.optLong("modified_at", 0)

            // 수정 시간 비교
            if (localFile.exists() && localFile.lastModified() >= remoteModifiedAt) {
                Log.d(TAG, "로컬 모델이 최신 — 서버 다운로드 불필요")
                return false
            }

            // 다운로드
            val downloadRequest = Request.Builder()
                .url("$LOCAL_SERVER_URL/api/models/$MODEL_PREFIX/download")
                .addHeader("Authorization", "Bearer $LOCAL_SERVER_API_KEY")
                .build()
            val downloadResponse = httpClient.newCall(downloadRequest).execute()
            if (!downloadResponse.isSuccessful) {
                Log.w(TAG, "로컬 서버 모델 다운로드 실패: ${downloadResponse.code}")
                return false
            }

            localFile.parentFile?.mkdirs()
            downloadResponse.body?.byteStream()?.use { input ->
                localFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "로컬 서버에서 모델 다운로드 완료: ${localFile.length() / 1024}KB")
            true
        } catch (e: Exception) {
            Log.d(TAG, "로컬 서버 접근 실패 (정상 — Tailscale 미연결): ${e.message}")
            false
        }
    }

    /**
     * LoRA fine-tuned GGUF 모델 동기화.
     * Drive "xreal_models/qwen3_lora_v*.gguf" 에서 최신 모델 확인 → 다운로드.
     * 다운로드 성공 시 EdgeModelManager에 알림.
     */
    private fun syncLoraModel() {
        try {
            val loraFile = File(applicationContext.filesDir, "$LORA_MODEL_LOCAL_DIR/$LORA_MODEL_FILENAME")

            // 1차: 로컬 서버
            val fromLocal = tryDownloadLoraFromLocalServer(loraFile)
            // 2차: Drive
            val fromDrive = if (!fromLocal) tryDownloadLoraFromDrive(loraFile) else true

            if (fromLocal || fromDrive) {
                Log.i(TAG, "LoRA 모델 다운로드 완료: ${loraFile.length() / (1024*1024)}MB")
                // EdgeModelManager에 알림 → 다음 추론 시 LoRA 모델 사용
                try {
                    val eventBus = getKoin().getOrNull<GlobalEventBus>()
                    eventBus?.publish(
                        XRealEvent.SystemEvent.DebugLog(
                            "ModelSyncWorker: LoRA fine-tuned 모델 적용 가능 ($LORA_MODEL_FILENAME)"
                        )
                    )
                    // EdgeModelManager 재시작 (LoRA 모델 우선 로딩)
                    val edgeManager = getKoin().getOrNull<com.xreal.nativear.edge.EdgeModelManager>()
                    if (edgeManager != null) {
                        edgeManager.unloadModel(com.xreal.nativear.edge.EdgeModelTier.AGENT_1B)
                        Log.i(TAG, "AGENT_1B 언로드 → 다음 추론 시 LoRA 모델로 재로딩")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "LoRA 모델 알림 실패 (비치명적): ${e.message}")
                }
            } else {
                Log.d(TAG, "LoRA 모델 없음 — 기본 모델 유지")
            }
        } catch (e: Exception) {
            Log.w(TAG, "LoRA 동기화 실패 (비치명적): ${e.message}")
        }
    }

    private fun tryDownloadLoraFromLocalServer(loraFile: File): Boolean {
        return try {
            val httpClient = getKoin().get<OkHttpClient>()
            val infoRequest = Request.Builder()
                .url("$LOCAL_SERVER_URL/api/models/$LORA_MODEL_PREFIX/info")
                .addHeader("Authorization", "Bearer $LOCAL_SERVER_API_KEY")
                .build()
            val infoResponse = httpClient.newCall(infoRequest).execute()
            if (!infoResponse.isSuccessful) return false

            val infoJson = org.json.JSONObject(infoResponse.body?.string() ?: "{}")
            val remoteModifiedAt = infoJson.optLong("modified_at", 0)
            if (loraFile.exists() && loraFile.lastModified() >= remoteModifiedAt) return false

            val downloadRequest = Request.Builder()
                .url("$LOCAL_SERVER_URL/api/models/$LORA_MODEL_PREFIX/download")
                .addHeader("Authorization", "Bearer $LOCAL_SERVER_API_KEY")
                .build()
            val downloadResponse = httpClient.newCall(downloadRequest).execute()
            if (!downloadResponse.isSuccessful) return false

            loraFile.parentFile?.mkdirs()
            downloadResponse.body?.byteStream()?.use { input ->
                loraFile.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (e: Exception) {
            Log.d(TAG, "LoRA 로컬 서버 접근 실패: ${e.message}")
            false
        }
    }

    private fun tryDownloadLoraFromDrive(loraFile: File): Boolean {
        return try {
            val driveClient = getKoin().getOrNull<DriveApiClient>() ?: return false
            val authManager = getKoin().getOrNull<GoogleDriveAuthManager>() ?: return false
            if (!authManager.isAuthenticated() && !authManager.isRefreshable()) return false

            val driveModel = driveClient.findLatestModel(FOLDER_MODELS, LORA_MODEL_PREFIX) ?: return false
            if (loraFile.exists() && loraFile.lastModified() >= driveModel.modifiedTimeMs) return false

            Log.i(TAG, "Drive에서 LoRA 모델 발견: ${driveModel.name}")
            loraFile.parentFile?.mkdirs()
            driveClient.downloadFile(driveModel.fileId, loraFile)
        } catch (e: Exception) {
            Log.w(TAG, "LoRA Drive 접근 실패: ${e.message}")
            false
        }
    }

    /**
     * Google Drive에서 모델 다운로드 시도 (기존 로직)
     */
    private fun tryDownloadFromDrive(localFile: File): Boolean {
        return try {
            val driveClient = getKoin().getOrNull<DriveApiClient>() ?: return false
            val authManager = getKoin().getOrNull<GoogleDriveAuthManager>() ?: return false

            if (!authManager.isAuthenticated() && !authManager.isRefreshable()) {
                Log.w(TAG, "Drive 인증 없음")
                return false
            }

            val driveModel = driveClient.findLatestModel(FOLDER_MODELS, MODEL_PREFIX) ?: run {
                Log.d(TAG, "Drive에 모델 없음")
                return false
            }

            if (localFile.exists() && localFile.lastModified() >= driveModel.modifiedTimeMs) {
                Log.d(TAG, "로컬 모델이 최신 — Drive 다운로드 불필요")
                return false
            }

            Log.i(TAG, "Drive에서 새 모델 발견: ${driveModel.name}")
            val success = driveClient.downloadFile(driveModel.fileId, localFile)
            if (!success) {
                Log.e(TAG, "Drive 모델 다운로드 실패")
            }
            success
        } catch (e: Exception) {
            Log.w(TAG, "Drive 접근 실패: ${e.message}")
            false
        }
    }
}
