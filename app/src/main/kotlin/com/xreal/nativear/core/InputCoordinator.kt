package com.xreal.nativear.core

import android.util.Log
import com.xreal.nativear.GestureType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/**
 * InputCoordinator: Subscribes to InputEvent.Gesture and executes corresponding actions.
 * Replaces the old GestureHandler + UserAction pattern.
 */
class InputCoordinator(
    private val eventBus: GlobalEventBus,
    private val aiAgentManager: AIAgentManager
) {
    interface InputListener {
        fun onCycleCamera()
        fun onDailySummary()
        fun onSyncMemory()
        fun onOpenMemQuery()
        fun onConfirmAction(message: String)
        fun onCancelAction(message: String)
        fun processGeminiCommand(command: String)
        fun onLog(message: String)
    }

    private var listener: InputListener? = null

    fun setListener(l: InputListener) {
        this.listener = l
    }
    private val TAG = "InputCoordinator"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    init {
        subscribeToEvents()
        Log.i(TAG, "InputCoordinator initialized and subscribed to events")
    }
    
    private fun subscribeToEvents() {
        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is XRealEvent.InputEvent.Gesture -> handleGesture(event.type)
                    is XRealEvent.InputEvent.VoiceCommand -> {
                        Log.i(TAG, "Handling voice command: ${event.text}")
                        aiAgentManager.processWithGemini(event.text)
                    }
                    is XRealEvent.InputEvent.EnrichedVoiceCommand -> {
                        Log.i(TAG, "Handling enriched voice command: ${event.text} (Speaker: ${event.speaker}, Emotion: ${event.emotion})")
                        val enrichedContext = """
                            [Auditory Context]
                            Speaker: ${event.speaker}
                            Emotion: ${event.emotion} (Score: ${String.format("%.2f", event.emotionScore)})
                        """.trimIndent()
                        aiAgentManager.processWithGemini(event.text, enrichedContext)
                    }
                    else -> {} // Ignore other events
                }
            }
        }
    }
    
    private fun handleGesture(type: GestureType) {
        Log.i(TAG, "Handling gesture: $type")
        when (type) {
            GestureType.DOUBLE_TAP -> listener?.onCycleCamera()
            GestureType.TRIPLE_TAP -> listener?.onOpenMemQuery()
            GestureType.QUAD_TAP -> listener?.onSyncMemory()
            GestureType.NOD -> listener?.onConfirmAction("Confirmed via nod")
            GestureType.SHAKE -> listener?.onCancelAction("Cancelled via shake")
            else -> {} // Handle other gestures or future additions
        }
    }
    
    fun cleanup() {
        scope.cancel()
        Log.i(TAG, "InputCoordinator cleaned up")
    }
}
