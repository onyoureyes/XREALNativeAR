package com.xreal.nativear.learning

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import org.koin.java.KoinJavaComponent.getKoin

/**
 * DriveUploadWorker — decision_log CSV를 Google Drive에 주기적으로 업로드.
 *
 * ## BackupSyncWorker.kt 패턴 재사용
 * - CoroutineWorker (WorkManager)
 * - Koin으로 의존성 주입 (getKoin().get())
 * - Constraints: Wi-Fi + battery not low
 *
 * ## 흐름
 * 1. TrainingDataExporter.exportToCsv() → CSV 파일
 * 2. 행 수 < MIN_ROWS → Result.success() 조기 종료
 * 3. DriveApiClient.uploadFile() → "xreal_training_data/" 폴더
 * 4. EventBus.publish(DebugLog("업로드 완료: N행"))
 * 5. CSV 파일 삭제 (캐시 정리)
 *
 * Constraints: Wi-Fi + battery not low (DriveTrainingScheduler에서 설정)
 */
class DriveUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DriveUploadWorker"
        const val FOLDER_TRAINING_DATA = "xreal_training_data"
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "학습 데이터 업로드 작업 시작")

        return try {
            val driveClient = getKoin().getOrNull<DriveApiClient>() ?: run {
                Log.e(TAG, "DriveApiClient 없음 — 작업 실패")
                return Result.failure()
            }
            val authManager = getKoin().getOrNull<GoogleDriveAuthManager>() ?: run {
                Log.w(TAG, "GoogleDriveAuthManager 없음 — 작업 스킵")
                return Result.success()
            }

            // 1. 인증 확인
            if (!authManager.isAuthenticated() && !authManager.isRefreshable()) {
                Log.w(TAG, "Drive 인증 없음 — 업로드 스킵 (로그인 필요)")
                return Result.success()
            }

            var uploadCount = 0

            // ─── Part A: MLP Classifier CSV 업로드 ───
            val csvExporter = getKoin().getOrNull<TrainingDataExporter>()
            if (csvExporter != null) {
                val rowCount = csvExporter.getExportableRowCount()
                if (rowCount >= TrainingDataExporter.MIN_ROWS_FOR_UPLOAD) {
                    val csvFile = csvExporter.exportToCsv()
                    if (csvFile != null) {
                        val fileId = driveClient.uploadFile(
                            file = csvFile,
                            folderName = FOLDER_TRAINING_DATA,
                            mimeType = "text/csv"
                        )
                        csvFile.delete()
                        if (fileId != null) {
                            Log.i(TAG, "CSV 업로드 완료: ${rowCount}행 (fileId=$fileId)")
                            uploadCount++
                        }
                    }
                } else {
                    Log.d(TAG, "CSV 데이터 부족 ($rowCount < ${TrainingDataExporter.MIN_ROWS_FOR_UPLOAD}행)")
                }
            }

            // ─── Part B: LoRA JSONL 업로드 ───
            val jsonlExporter = getKoin().getOrNull<ConversationDataExporter>()
            if (jsonlExporter != null) {
                val convCount = jsonlExporter.getExportableConversationCount()
                if (convCount >= ConversationDataExporter.MIN_ROWS_FOR_UPLOAD) {
                    val jsonlFile = jsonlExporter.exportToJsonl()
                    if (jsonlFile != null) {
                        val fileId = driveClient.uploadFile(
                            file = jsonlFile,
                            folderName = FOLDER_TRAINING_DATA,
                            mimeType = "application/jsonl"
                        )
                        jsonlFile.delete()
                        if (fileId != null) {
                            Log.i(TAG, "JSONL 업로드 완료: ${convCount}개 대화 (fileId=$fileId)")
                            uploadCount++
                        }
                    }
                } else {
                    Log.d(TAG, "JSONL 대화 데이터 부족 ($convCount < ${ConversationDataExporter.MIN_ROWS_FOR_UPLOAD}개)")
                }

                // ─── Part C: DPO 선호도 쌍 업로드 ───
                val dpoFile = jsonlExporter.exportDPOJsonl()
                if (dpoFile != null) {
                    val fileId = driveClient.uploadFile(
                        file = dpoFile,
                        folderName = FOLDER_TRAINING_DATA,
                        mimeType = "application/jsonl"
                    )
                    dpoFile.delete()
                    if (fileId != null) {
                        Log.i(TAG, "DPO 선호도 쌍 업로드 완료 (fileId=$fileId)")
                        uploadCount++
                    }
                }

                // ─── Part D: 감정 연결 대화 업로드 ───
                val emotionFile = jsonlExporter.exportEmotionLinkedJsonl()
                if (emotionFile != null) {
                    val fileId = driveClient.uploadFile(
                        file = emotionFile,
                        folderName = FOLDER_TRAINING_DATA,
                        mimeType = "application/jsonl"
                    )
                    emotionFile.delete()
                    if (fileId != null) {
                        Log.i(TAG, "감정 대화 업로드 완료 (fileId=$fileId)")
                        uploadCount++
                    }
                }

                // ─── Part E: 대화 일지 (관계 맥락) 업로드 ───
                val journalFile = jsonlExporter.exportConversationJournalJsonl()
                if (journalFile != null) {
                    val fileId = driveClient.uploadFile(
                        file = journalFile,
                        folderName = FOLDER_TRAINING_DATA,
                        mimeType = "application/jsonl"
                    )
                    journalFile.delete()
                    if (fileId != null) {
                        Log.i(TAG, "대화 일지 업로드 완료 (fileId=$fileId)")
                        uploadCount++
                    }
                }
            }

            // EventBus 알림
            if (uploadCount > 0) {
                try {
                    val eventBus = getKoin().getOrNull<GlobalEventBus>()
                    eventBus?.publish(
                        XRealEvent.SystemEvent.DebugLog(
                            "DriveUploadWorker: ${uploadCount}개 학습 데이터 업로드 완료"
                        )
                    )
                } catch (_: Exception) {}
            }

            Log.i(TAG, "업로드 작업 완료: ${uploadCount}개 파일")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "DriveUploadWorker 예외: ${e.message}")
            Result.retry()
        }
    }
}
