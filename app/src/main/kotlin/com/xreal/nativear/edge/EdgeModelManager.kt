package com.xreal.nativear.edge

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import kotlin.coroutines.cancellation.CancellationException
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * EdgeModelManager — llama.cpp JNI 엔진 캐시 및 생명주기 관리.
 *
 * ## 로딩 전략
 * - alwaysLoaded (270M, 1B): 앱 시작 후 백그라운드 자동 로딩
 * - E2B: 명시적 요청 or API 3회 실패 시 지연 로딩
 * - E2B 10분 유휴 → 자동 언로드
 * - ResourceAlert.CRITICAL → E2B 즉시 언로드
 *
 * ## 모델 파일 위치
 * 1순위: context.filesDir/edge_models/{fileName}  (배포 시 다운로드)
 * 2순위: /data/local/tmp/edge_models/{fileName}    (개발 단계 adb push)
 */
class EdgeModelManager(
    private val context: Context,
    private val eventBus: GlobalEventBus
) {
    companion object {
        private const val TAG = "EdgeModelManager"
        private const val E2B_IDLE_TIMEOUT_MS = 10 * 60 * 1000L  // 10분
        private const val MODEL_DIR_ADB = "/data/local/tmp/edge_models"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // tier → llama.cpp 모델 핸들 (jlong, 0 = 미로딩)
    private val handleCache = ConcurrentHashMap<EdgeModelTier, Long>()
    private val loadingJobs = ConcurrentHashMap<EdgeModelTier, Job>()
    private val readyStates = ConcurrentHashMap<EdgeModelTier, Boolean>()

    private var e2bIdleJob: Job? = null
    private var started = false

    /** 앱 시작 시 호출 — llama.cpp backend 초기화 + alwaysLoaded 모델 백그라운드 준비 */
    fun start() {
        if (started) return
        started = true

        // llama.cpp backend 초기화
        LlamaCppBridge.init()

        // alwaysLoaded 모델 (270M, 1B) 백그라운드 로딩
        Log.i(TAG, "모델 탐색 시작 — filesDir=${context.filesDir.absolutePath}")
        EdgeModelTier.values().filter { it.alwaysLoaded }.forEach { tier ->
            scope.launch {
                val modelFile = getModelFile(tier)
                if (modelFile != null) {
                    Log.i(TAG, "${tier.name} 모델 파일 발견: ${modelFile.absolutePath} (${modelFile.length()}B, readable=${modelFile.canRead()})")
                    loadModel(tier)
                } else {
                    Log.w(TAG, "모델 파일 없음: ${tier.modelFileName} — Wi-Fi 연결 시 다운로드 예약")
                    scheduleDownloadIfNeeded(tier)
                }
            }
        }

        // ResourceAlert.CRITICAL → E2B 즉시 언로드
        scope.launch {
            eventBus.events.collect { event ->
                try {
                    if (event is XRealEvent.SystemEvent.ResourceAlert) {
                        val sevStr = event.severity.name
                        if (sevStr == "CRITICAL") {
                            Log.w(TAG, "ResourceAlert.CRITICAL — E2B 즉시 언로드")
                            unloadModel(EdgeModelTier.EMERGENCY_E2B)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "ResourceAlert 처리 오류 (루프 유지됨): ${e.message}", e)
                }
            }
        }

        Log.i(TAG, "EdgeModelManager started — llama.cpp CPU 모드")
    }

    /** 앱 종료 시 호출 */
    fun stop() {
        EdgeModelTier.values().forEach { unloadModel(it) }
        LlamaCppBridge.cleanup()
        started = false
        Log.i(TAG, "EdgeModelManager stopped")
    }

    /**
     * 해당 티어의 llama.cpp 핸들 반환 (캐시 or 로딩).
     * @return 핸들 (0L = 실패)
     */
    suspend fun getOrLoad(tier: EdgeModelTier): Long {
        // 1. 캐시에 있으면 반환
        val cached = handleCache[tier]
        if (cached != null && cached != 0L) return cached

        // 2. 모델 파일 확인
        val modelFile = getModelFile(tier) ?: run {
            Log.w(TAG, "모델 파일 없음: ${tier.modelFileName}")
            scheduleDownloadIfNeeded(tier)
            return 0L
        }

        // 3. 로딩
        return loadModel(tier)
    }

    /** 해당 티어 준비 여부 */
    fun isReady(tier: EdgeModelTier): Boolean = readyStates[tier] == true

    /** 모델 언로드 (RAM 해제) */
    fun unloadModel(tier: EdgeModelTier) {
        loadingJobs[tier]?.cancel()
        loadingJobs.remove(tier)
        e2bIdleJob?.cancel()

        val handle = handleCache.remove(tier)
        if (handle != null && handle != 0L) {
            LlamaCppBridge.unloadModel(handle)
            Log.i(TAG, "모델 언로드 완료: $tier")
        }
        readyStates[tier] = false
        publishState(tier, "UNLOADED")
    }

    // =========================================================================
    // 내부 구현
    // =========================================================================

    private suspend fun loadModel(tier: EdgeModelTier): Long {
        // 이미 로딩 중이면 대기
        if (loadingJobs.containsKey(tier)) {
            loadingJobs[tier]?.join()
            return handleCache[tier] ?: 0L
        }

        val modelFile = getModelFile(tier) ?: return 0L

        publishState(tier, "LOADING")
        Log.i(TAG, "엣지 모델 로딩 시작: ${tier.name} (${tier.estimatedSizeMb}MB, ctx=${tier.contextSize}, threads=${tier.nThreads})")

        var result = 0L
        val job = scope.launch {
            try {
                result = withContext(Dispatchers.IO) {
                    LlamaCppBridge.loadModel(
                        modelPath = modelFile.absolutePath,
                        nCtx = tier.contextSize,
                        nThreads = tier.nThreads
                    )
                }
                if (result != 0L) {
                    handleCache[tier] = result
                    readyStates[tier] = true
                    val vocab = LlamaCppBridge.getVocabSize(result)
                    publishState(tier, "READY")
                    Log.i(TAG, "엣지 모델 준비 완료: ${tier.name} (vocab=$vocab)")

                    // E2B 유휴 타이머 시작
                    if (tier == EdgeModelTier.EMERGENCY_E2B) {
                        startE2bIdleTimer()
                    }
                } else {
                    publishState(tier, "FAILED")
                    Log.e(TAG, "엣지 모델 로딩 실패: ${tier.name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "엣지 모델 로딩 예외: ${tier.name} — ${e.message}")
                publishState(tier, "FAILED")
            } finally {
                loadingJobs.remove(tier)
            }
        }

        loadingJobs[tier] = job
        job.join()
        return handleCache[tier] ?: 0L
    }

    /** E2B 유휴 타이머 — 10분 미사용 시 자동 언로드 */
    private fun startE2bIdleTimer() {
        e2bIdleJob?.cancel()
        e2bIdleJob = scope.launch {
            delay(E2B_IDLE_TIMEOUT_MS)
            if (isActive) {
                Log.i(TAG, "E2B 10분 유휴 — 자동 언로드")
                unloadModel(EdgeModelTier.EMERGENCY_E2B)
            }
        }
    }

    /** E2B 사용 시 유휴 타이머 리셋 (EdgeLLMProvider에서 호출) */
    fun resetE2bIdleTimer() {
        if (isReady(EdgeModelTier.EMERGENCY_E2B)) {
            startE2bIdleTimer()
        }
    }

    /**
     * 모델 파일 위치 탐색.
     * AGENT_1B의 경우 LoRA fine-tuned 모델 우선:
     *   0순위: context.filesDir/edge_models/Qwen3-1.7B-Q4_K_M-lora.gguf (LoRA 학습 결과)
     * 공통:
     *   1순위: context.filesDir/edge_models/{fileName}  (배포 / WorkManager 다운로드)
     *   2순위: /data/local/tmp/edge_models/{fileName}    (개발 adb push — SELinux 허용 시)
     *   3순위: /sdcard/edge_models/{fileName}            (외부 저장소 — 대부분 접근 가능)
     *   4순위: context.getExternalFilesDir("edge_models") (앱 전용 외부 저장소)
     */
    private fun getModelFile(tier: EdgeModelTier): File? {
        // AGENT_1B: LoRA fine-tuned 모델 우선
        if (tier == EdgeModelTier.AGENT_1B) {
            val loraFile = File(context.filesDir, "edge_models/Qwen3-1.7B-Q4_K_M-lora.gguf")
            if (loraFile.exists() && loraFile.length() > 0) {
                Log.i(TAG, "LoRA fine-tuned 모델 사용: ${loraFile.name}")
                return loraFile
            }
        }

        val candidates = listOf(
            File(context.filesDir, "edge_models/${tier.modelFileName}"),
            File("$MODEL_DIR_ADB/${tier.modelFileName}"),
            File("/sdcard/edge_models/${tier.modelFileName}"),
            context.getExternalFilesDir("edge_models")?.let { File(it, tier.modelFileName) }
        )

        for (file in candidates) {
            if (file != null && file.exists() && file.length() > 0) {
                Log.d(TAG, "모델 파일 발견: ${file.absolutePath} (${file.length() / (1024*1024)}MB)")
                return file
            }
        }

        // 어떤 경로에서도 못 찾음 — 탐색 결과 로깅
        val searchedPaths = candidates.mapNotNull { it?.absolutePath }.joinToString(", ")
        Log.w(TAG, "모델 파일 없음: ${tier.modelFileName} — 탐색 경로: $searchedPaths")
        return null
    }

    /**
     * Wi-Fi 연결 시 자동 다운로드 예약 (WorkManager).
     */
    fun scheduleDownloadIfNeeded(tier: EdgeModelTier) {
        val outputFile = File(context.filesDir, "edge_models/${tier.modelFileName}")
        if (outputFile.exists() && outputFile.length() > 0) return  // 이미 있음

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)  // Wi-Fi only
            .build()

        val request = OneTimeWorkRequestBuilder<EdgeModelDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    EdgeModelDownloadWorker.KEY_TIER to tier.name,
                    EdgeModelDownloadWorker.KEY_URL to tier.downloadUrl,
                    EdgeModelDownloadWorker.KEY_FILENAME to tier.modelFileName
                )
            )
            .addTag("edge_model_download_${tier.name}")
            .build()

        WorkManager.getInstance(context).enqueue(request)
        Log.i(TAG, "다운로드 예약: ${tier.name} (Wi-Fi only) — ${tier.estimatedSizeMb}MB")
    }

    private fun publishState(tier: EdgeModelTier, state: String, progress: Int = 0) {
        scope.launch {
            eventBus.publish(
                XRealEvent.SystemEvent.EdgeModelStateChanged(
                    tier = tier.name,
                    state = state,
                    progressPercent = progress
                )
            )
        }
    }
}
