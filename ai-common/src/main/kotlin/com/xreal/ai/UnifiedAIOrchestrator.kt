package com.xreal.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
// QNN LiteRT Delegate v2.34.0 — NNAPI Android 15 deprecated 대체
// RESEARCH.md §3 QNN LiteRT Delegate 참조
// import com.qualcomm.qti.tflite.QnnDelegate  // 컴파일 타임 import (app 모듈에서는 사용 가능)
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.PriorityBlockingQueue

/**
 * IAIModel: Standardized interface for all AI models (Whisper, OCR, etc.)
 * to allow unified management and resource sharing.
 */
interface IAIModel {
    val isReady: Boolean
    val isLoaded: Boolean
    val priority: Int
    suspend fun prepare(options: Interpreter.Options): Boolean
    fun release()
}

/**
 * AIModelWarehouse (UnifiedAIOrchestrator): Manages hardware acceleration (GPU/NNAPI)
 * and model lifecycle for all registered AI components.
 * Migrated from Play Services TFLite to standalone LiteRT V1.
 */
class UnifiedAIOrchestrator(private val context: Context) {
    private val TAG = "AI_ORCHESTRATOR"

    private var isInitialized = AtomicBoolean(false)
    private var useAcceleration = true

    // Internal Scheduler used only by Orchestrator
    private val scheduler = InferenceScheduler(CoroutineScope(Dispatchers.Default + SupervisorJob()))

    private val activeModels = mutableMapOf<String, IAIModel>()
    private val lastUsedTime = mutableMapOf<String, Long>()

    // Backend probe result (checked once at init, cached for logging only)
    // GpuDelegate MUST NOT be shared across Interpreter instances — create fresh per model
    // RESEARCH.md §3 QNN: NNAPI deprecated (Android 15) → QNN LiteRT Delegate 사용
    enum class InferenceBackend { GPU, QNN_NPU, NNAPI_NPU, CPU }
    var activeBackend: InferenceBackend = InferenceBackend.CPU
        private set

    init {
        initializeEngine()
    }

    private fun initializeEngine() {
        Log.i(TAG, "Initializing Unified AI Engine (LiteRT V1 standalone)...")
        try {
            // Probe which backend is available (probe with a throw-away delegate)
            if (useAcceleration) {
                activeBackend = try {
                    val probe = GpuDelegate()
                    probe.close()
                    InferenceBackend.GPU
                } catch (e: Exception) {
                    // GPU 불가 → QNN NPU 시도 (NNAPI Android 15 deprecated 대체)
                    // RESEARCH.md §3 QNN LiteRT Delegate v2.34.0 참조
                    try {
                        probeQnnDelegate()
                        InferenceBackend.QNN_NPU
                    } catch (e2: Exception) {
                        InferenceBackend.CPU
                    }
                }
            }
            Log.i(TAG, "✅ LiteRT engine initialized — backend: $activeBackend")
            isInitialized.set(true)
            notifyModelsReady()
        } catch (e: Exception) {
            Log.e(TAG, "LiteRT Init Failed: ${e.message}")
            isInitialized.set(true)
            notifyModelsReady()
        }
    }

    private fun notifyModelsReady() {
        synchronized(activeModels) {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            activeModels.values.forEach { model ->
                scope.launch {
                    // CRITICAL: createInterpreterOptions() called per-model to get FRESH delegate instances.
                    // Sharing a GpuDelegate across multiple Interpreters is not supported by LiteRT.
                    model.prepare(createInterpreterOptions())
                }
            }
        }
    }

    /**
     * Creates a fresh Interpreter.Options for a single model.
     * MUST be called separately for each model — GpuDelegate cannot be shared.
     *
     * Priority: GPU (Adreno 730) → QNN NPU (Hexagon HTP) → CPU (4 threads)
     * Expected speedups vs CPU: GPU ~10x, QNN NPU ~47x (YOLOv8n), ~100x (simple models)
     *
     * RESEARCH.md §1 LiteRT, §3 QNN LiteRT Delegate v2.34.0 참조.
     * NNAPI: Android 15에서 deprecated → QNN delegate로 교체.
     */
    private fun createInterpreterOptions(): Interpreter.Options {
        if (!useAcceleration) {
            return Interpreter.Options().apply { setNumThreads(4) }
        }
        // 1순위: GPU delegate (Adreno 730) — fresh instance required per Interpreter
        try {
            return Interpreter.Options().apply {
                addDelegate(GpuDelegate())
                setNumThreads(1) // GPU handles compute; 1 CPU thread for scheduling
                Log.d(TAG, "GPU delegate (Adreno 730) applied to new interpreter")
            }
        } catch (e: Exception) {
            Log.w(TAG, "GPU delegate unavailable: ${e.message}")
        }
        // 2순위: QNN NPU (Hexagon HTP) — NNAPI Android 15 deprecated 대체
        // RESEARCH.md §3: skelLibraryDir 필수, API 31+ 권장
        // 리플렉션으로 addDelegate 호출 (타입 안전성 우회)
        try {
            val qnnDelegate = createQnnOptions()
            if (qnnDelegate != null) {
                val interpOptions = Interpreter.Options().apply { setNumThreads(1) }
                // Interpreter.Options.addDelegate(Delegate) — 리플렉션으로 호출
                val delegateClass = Class.forName("org.tensorflow.lite.Delegate")
                interpOptions.javaClass
                    .getMethod("addDelegate", delegateClass)
                    .invoke(interpOptions, qnnDelegate)
                Log.d(TAG, "QNN NPU (Hexagon HTP) applied to new interpreter")
                return interpOptions
            }
        } catch (e: Exception) {
            Log.w(TAG, "QNN NPU delegate unavailable: ${e.message}")
        }
        // 3순위: CPU fallback
        return Interpreter.Options().apply {
            setNumThreads(4)
            Log.d(TAG, "CPU-only (4 threads) applied to new interpreter")
        }
    }

    /**
     * QNN Delegate Options 생성 (리플렉션 — ai-common 모듈에서 직접 import 불가 시 대응).
     * RESEARCH.md §3: com.qualcomm.qti.tflite.QnnDelegate
     * - BackendType.HTP_BACKEND 명시
     * - setSkelLibraryDir(nativeLibraryDir) 필수
     */
    private fun createQnnOptions(): Any? {
        return try {
            val qnnDelegateClass = Class.forName("com.qualcomm.qti.tflite.QnnDelegate")
            val optionsClass = Class.forName("com.qualcomm.qti.tflite.QnnDelegate\$Options")
            val backendTypeClass = Class.forName("com.qualcomm.qti.tflite.QnnDelegate\$Options\$BackendType")

            val options = optionsClass.newInstance()
            val htpBackend = backendTypeClass.getField("HTP_BACKEND").get(null)

            optionsClass.getMethod("setBackendType", backendTypeClass).invoke(options, htpBackend)
            // skelLibraryDir: Context 필요 → context.applicationInfo.nativeLibraryDir
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            optionsClass.getMethod("setSkelLibraryDir", String::class.java)
                .invoke(options, nativeLibDir)

            // QnnDelegate(options) 생성
            qnnDelegateClass.getConstructor(optionsClass).newInstance(options)
        } catch (e: ClassNotFoundException) {
            Log.d(TAG, "QNN delegate 클래스 없음 (qnn-litert-delegate 미포함): ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "QNN delegate 생성 실패: ${e.message}")
            null
        }
    }

    /**
     * QNN delegate probe (initializeEngine에서 가용성 확인용).
     * @throws Exception if QNN is not available
     */
    private fun probeQnnDelegate() {
        val probe = createQnnOptions()
            ?: throw Exception("QNN delegate 생성 실패")
        // 프로브만 생성 (Interpreter 초기화 없이 delegate 생성 성공 = 가용)
        try {
            probe.javaClass.getMethod("close").invoke(probe)
        } catch (e: Exception) { /* close 메서드 없을 수 있음 — 무시 */ }
    }

    fun setAccelerationMode(enabled: Boolean) {
        if (useAcceleration != enabled) {
            useAcceleration = enabled
            Log.i(TAG, "Acceleration Mode Changed: $enabled")
            if (isInitialized.get()) {
                notifyModelsReady()
            }
        }
    }

    fun registerModel(name: String, model: IAIModel) {
        synchronized(activeModels) {
            activeModels[name] = model
            // ★ 등록 시 lastUsedTime 초기화 — releaseUnusedModels가 즉시 해제하는 버그 방지
            // 이전: lastUsedTime 미설정 → 기본값 0 → 등록 직후 cleanup에서 해제됨
            synchronized(lastUsedTime) { lastUsedTime[name] = System.currentTimeMillis() }
            if (isInitialized.get()) {
                CoroutineScope(Dispatchers.IO).launch {
                    model.prepare(createInterpreterOptions())
                }
            }
        }
    }

    /**
     * 특정 모델의 마지막 사용 시간을 현재로 갱신.
     * VisionManager처럼 모델을 직접 호출하는 경우 releaseUnusedModels에서 해제되지 않도록 사용.
     */
    fun markModelUsed(name: String) {
        synchronized(lastUsedTime) {
            if (activeModels.containsKey(name)) {
                lastUsedTime[name] = System.currentTimeMillis()
            }
        }
    }

    fun submitTask(priority: Int, modelName: String? = null, action: suspend () -> Unit) {
        // Track model usage time if model name provided
        modelName?.let { name ->
            synchronized(lastUsedTime) {
                lastUsedTime[name] = System.currentTimeMillis()
            }
        }
        scheduler.submit(priority, action)
    }
    
    /**
     * Unregister and release a specific model.
     */
    fun unregisterModel(name: String) {
        synchronized(activeModels) {
            activeModels[name]?.release()
            activeModels.remove(name)
            lastUsedTime.remove(name)
            Log.i(TAG, "Model unregistered: $name")
        }
    }
    
    /**
     * Release models that haven't been used for a specified duration.
     * @param unusedThresholdMs Time in milliseconds (default: 5 minutes)
     */
    fun releaseUnusedModels(unusedThresholdMs: Long = 5 * 60 * 1000) {
        val now = System.currentTimeMillis()
        synchronized(activeModels) {
            val toRemove = activeModels.keys.filter { modelName ->
                val lastUsed = lastUsedTime[modelName] ?: 0
                (now - lastUsed) > unusedThresholdMs
            }
            toRemove.forEach { modelName ->
                Log.i(TAG, "Releasing unused model: $modelName (idle for ${(now - (lastUsedTime[modelName] ?: 0)) / 1000}s)")
                unregisterModel(modelName)
            }
        }
    }
    
    /**
     * Warmup specified models by initializing them if not already initialized.
     * Reduces first-execution latency.
     */
    suspend fun warmupModels(modelNames: List<String>) {
        if (!isInitialized.get()) {
            Log.w(TAG, "Cannot warmup models: Orchestrator not initialized")
            return
        }
        
        val options = createInterpreterOptions()
        synchronized(activeModels) {
            modelNames.forEach { name ->
                activeModels[name]?.let { model ->
                    Log.i(TAG, "Warming up model: $name")
                    CoroutineScope(Dispatchers.IO).launch {
                        model.prepare(options)
                    }
                    // Mark as recently used
                    lastUsedTime[name] = System.currentTimeMillis()
                }
            }
        }
    }

    /**
     * Ensure targeted models are ready for use.
     */
    suspend fun ensureModelsReady(modelNames: List<String>): Boolean {
        if (!isInitialized.get()) return false
        val options = createInterpreterOptions()
        return withContext(Dispatchers.IO) {
            modelNames.map { name ->
                async {
                    activeModels[name]?.prepare(options) ?: false
                }
            }.awaitAll().all { it }
        }
    }

    fun close() {
        scheduler.stop()
        synchronized(activeModels) {
            activeModels.values.forEach { it.release() }
            activeModels.clear()
        }
        // Delegates are now created per-Interpreter (not shared), so no global cleanup needed
        isInitialized.set(false)
    }
}

/**
 * InferenceScheduler: Manages a priority-based queue for AI model execution.
 * (Merged from InferenceScheduler.kt to resolve visibility issues)
 */
private class InferenceScheduler(private val scope: CoroutineScope) {
    private val TAG = "AI_SCHEDULER"
    
    data class InferenceTask(
        val priority: Int,
        val timestamp: Long = System.currentTimeMillis(),
        val action: suspend () -> Unit
    ) : Comparable<InferenceTask> {
        override fun compareTo(other: InferenceTask): Int {
            return if (this.priority != other.priority) {
                this.priority.compareTo(other.priority)
            } else {
                this.timestamp.compareTo(other.timestamp)
            }
        }
    }

    private val queue = PriorityBlockingQueue<InferenceTask>()
    private var isRunning = true

    init {
        startProcessing()
    }

    private fun startProcessing() {
        scope.launch(Dispatchers.Default) {
            while (isRunning) {
                try {
                    val task = queue.take()
                    task.action()
                } catch (e: Exception) {
                    Log.e(TAG, "Task Execution Error: ${e.message}")
                }
            }
        }
    }

    fun submit(priority: Int, action: suspend () -> Unit) {
        queue.offer(InferenceTask(priority, action = action))
    }

    fun stop() {
        isRunning = false
    }

    companion object {
        const val PRIORITY_CRITICAL = 1
        const val PRIORITY_HIGH = 2
        const val PRIORITY_MEDIUM = 3
        const val PRIORITY_LOW = 4
    }
}
