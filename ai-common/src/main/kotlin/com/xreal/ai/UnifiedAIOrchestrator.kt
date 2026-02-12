package com.xreal.ai

import android.content.Context
import android.util.Log
import com.google.android.gms.tflite.gpu.GpuDelegate
import com.google.android.gms.tflite.java.TfLite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.tensorflow.lite.Interpreter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * IAIModel: Standardized interface for all AI models (Whisper, OCR, etc.)
 * to allow unified management and resource sharing.
 */
interface IAIModel {
    fun initialize(interpreterOptions: Interpreter.Options)
    fun release()
    val priority: Int
}

/**
 * UnifiedAIOrchestrator: Manages hardware acceleration (GpuDelegate) 
 * and model lifecycle for all registered AI components.
 */
class UnifiedAIOrchestrator(private val context: Context) {
    private val TAG = "AI_ORCHESTRATOR"
    
    private var isInitialized = AtomicBoolean(false)
    private var gpuDelegate: GpuDelegate? = null
    
    private val scheduler = InferenceScheduler(CoroutineScope(Dispatchers.Default + SupervisorJob()))
    private val activeModels = mutableMapOf<String, IAIModel>()
    private val lastUsedTime = mutableMapOf<String, Long>()

    init {
        initializeEngine()
    }

    private fun initializeEngine() {
        Log.i(TAG, "Initializing Unified AI Engine (LiteRT Path A)...")
        TfLite.initialize(context).addOnSuccessListener {
            Log.i(TAG, "✅ TFLite Play Services Runtime Ready")
            gpuDelegate = GpuDelegate()
            isInitialized.set(true)
            notifyModelsReady()
        }.addOnFailureListener { e ->
            Log.e(TAG, "❌ Unified AI Init Failed: ${e.message}")
        }
    }

    private fun notifyModelsReady() {
        val options = createInterpreterOptions()
        synchronized(activeModels) {
            activeModels.values.forEach { it.initialize(options) }
        }
    }

    private fun createInterpreterOptions(): Interpreter.Options {
        return Interpreter.Options().apply {
            gpuDelegate?.let { addDelegate(it) }
            setNumThreads(4)
        }
    }

    fun registerModel(name: String, model: IAIModel) {
        synchronized(activeModels) {
            activeModels[name] = model
            if (isInitialized.get()) {
                model.initialize(createInterpreterOptions())
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
        
        synchronized(activeModels) {
            modelNames.forEach { name ->
                activeModels[name]?.let { model ->
                    Log.i(TAG, "Warming up model: $name")
                    // Mark as recently used
                    lastUsedTime[name] = System.currentTimeMillis()
                }
            }
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
