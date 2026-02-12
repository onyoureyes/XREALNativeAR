package com.xreal.nativear.core

import android.util.Log
import com.xreal.nativear.AIAgentManager
import com.xreal.nativear.VisionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * VisionCoordinator: Manages the flow of vision perception events.
 * Transitions logic from CoreEngine to a reactive, decoupled component.
 */
class VisionCoordinator(
    private val eventBus: GlobalEventBus,
    private val visionManager: VisionManager,
    private val aiAgentManager: AIAgentManager
) {
    private val TAG = "VisionCoordinator"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    interface VisionListener {
        fun onStatusUpdate(status: String)
        fun onDetections(results: List<com.xreal.nativear.Detection>)
    }
    
    private var listener: VisionListener? = null
    
    fun setListener(listener: VisionListener) {
        this.listener = listener
    }

    init {
        subscribeToEvents()
        Log.i(TAG, "VisionCoordinator initialized")
    }

    private fun subscribeToEvents() {
        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is XRealEvent.PerceptionEvent.OcrDetected -> {
                        listener?.onStatusUpdate("👁️ OCR: ${event.results.size} blocks")
                    }
                    is XRealEvent.PerceptionEvent.ObjectsDetected -> {
                        listener?.onDetections(event.results)
                        aiAgentManager.processDetections(event.results)
                    }
                    is XRealEvent.PerceptionEvent.SceneCaptured -> {
                        aiAgentManager.interpretScene(event.bitmap, event.ocrText)
                    }
                    is XRealEvent.ActionRequest.TriggerSnapshot -> {
                        visionManager.captureSceneSnapshot()
                    }
                    else -> {}
                }
            }
        }
    }
}
