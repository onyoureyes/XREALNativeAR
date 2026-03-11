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
import com.qualcomm.qti.QnnDelegate
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
            // Probe which backend is available (NPU → GPU → CPU)
            if (useAcceleration) {
                activeBackend = try {
                    probeQnnDelegate()
                    InferenceBackend.QNN_NPU
                } catch (e: Exception) {
                    Log.d(TAG, "QNN NPU 불가: ${e.message}")
                    try {
                        val probe = GpuDelegate()
                        probe.close()
                        InferenceBackend.GPU
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
     * MUST be called separately for each model — delegates cannot be shared across Interpreters.
     *
     * Priority: QNN NPU (Hexagon HTP, ~47x) → GPU (Adreno 730, ~10x) → CPU (4 threads)
     * NPU 우선: INT8 양자화 모델(YOLO 등)은 NPU에서 최대 성능, GPU는 float 모델에 적합.
     */
    private fun createInterpreterOptions(): Interpreter.Options {
        if (!useAcceleration) {
            return Interpreter.Options().apply { setNumThreads(4) }
        }
        // 1순위: QNN NPU (Hexagon HTP) — INT8 모델 최적, ~47x speedup
        try {
            val options = QnnDelegate.Options().apply {
                setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
                setSkelLibraryDir(context.applicationInfo.nativeLibraryDir)
            }
            return Interpreter.Options().apply {
                addDelegate(QnnDelegate(options))
                setNumThreads(1)
                Log.d(TAG, "QNN NPU (Hexagon HTP) applied to new interpreter")
            }
        } catch (e: Exception) {
            Log.w(TAG, "QNN NPU delegate unavailable: ${e.message}")
        }
        // 2순위: GPU delegate (Adreno 730) — float 모델에 적합
        try {
            return Interpreter.Options().apply {
                addDelegate(GpuDelegate())
                setNumThreads(1)
                Log.d(TAG, "GPU delegate (Adreno 730) applied to new interpreter")
            }
        } catch (e: Exception) {
            Log.w(TAG, "GPU delegate unavailable: ${e.message}")
        }
        // 3순위: CPU fallback
        return Interpreter.Options().apply {
            setNumThreads(4)
            Log.d(TAG, "CPU-only (4 threads) applied to new interpreter")
        }
    }

    /**
     * QNN delegate probe (initializeEngine에서 가용성 확인용).
     * @throws Exception if QNN is not available
     */
    private fun probeQnnDelegate() {
        val options = QnnDelegate.Options().apply {
            setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
            setSkelLibraryDir(context.applicationInfo.nativeLibraryDir)
        }
        val probe = QnnDelegate(options)
        probe.close()
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
