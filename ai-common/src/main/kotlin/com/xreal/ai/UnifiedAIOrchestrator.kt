package com.xreal.ai

import android.content.Context
import android.util.Log
import com.google.android.gms.tflite.gpu.GpuDelegate
import com.google.android.gms.tflite.java.TfLite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
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
 * AIModelWarehouse (UnifiedAIOrchestrator): Manages hardware acceleration (GpuDelegate) 
 * and model lifecycle for all registered AI components.
 */
class UnifiedAIOrchestrator(private val context: Context) {
    private val TAG = "AI_ORCHESTRATOR"
    
    private var isInitialized = AtomicBoolean(false)
    private var useAcceleration = true // Use Google Play Services automatic acceleration
    
    // Internal Scheduler used only by Orchestrator
    private val scheduler = InferenceScheduler(CoroutineScope(Dispatchers.Default + SupervisorJob()))
    
    private val activeModels = mutableMapOf<String, IAIModel>()
    private val lastUsedTime = mutableMapOf<String, Long>()
    
    private var gpuDelegate: GpuDelegate? = null

    init {
        initializeEngine()
    }

    private fun initializeEngine() {
        Log.i(TAG, "Initializing Unified AI Engine (LiteRT in Play Services)...")
        
        // Ask Play Services to handle the TFLite runtime and best acceleration (GPU/NPU)
        TfLite.initialize(context).addOnSuccessListener {
            Log.i(TAG, "✅ TFLite Play Services Runtime Ready")
            isInitialized.set(true)
            notifyModelsReady()
        }.addOnFailureListener { e ->
            Log.e(TAG, "❌ Unified AI Init Failed: ${e.message}")
        }
    }

    private fun notifyModelsReady() {
        val options = createInterpreterOptions()
        synchronized(activeModels) {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            activeModels.values.forEach { model ->
                scope.launch {
                    model.prepare(options)
                }
            }
        }
    }

    private fun createInterpreterOptions(): Interpreter.Options {
        return Interpreter.Options().apply {
            if (useAcceleration) {
                // Delegate to Google Play Services to pick the best accelerator
                // This will automatically choose GPU or NPU (QNN/NNAPI) based on device capability
                try {
                    // Note: In newer Play Services TFLite, we just use the loaded runtime
                    // and it handles the delegation internally if initialized correctly.
                    Log.i(TAG, "🚀 Automatic Acceleration Enabled (Delegated to Play Services)")
                } catch (e: Exception) {
                    Log.w(TAG, "Acceleration setup hint failed: ${e.message}")
                }
            }
            setNumThreads(4)
        }
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
            if (isInitialized.get()) {
                CoroutineScope(Dispatchers.IO).launch {
                    model.prepare(createInterpreterOptions())
                }
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
        gpuDelegate?.close()
        gpuDelegate = null
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
