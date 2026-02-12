package com.xreal.nativear.core

import android.util.Log
import com.xreal.nativear.GestureType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * InputCoordinator: Subscribes to InputEvent.Gesture and executes corresponding actions.
 * Replaces the old GestureHandler + UserAction pattern.
 */
class InputCoordinator(
    private val eventBus: GlobalEventBus,
    private val onCycleCamera: () -> Unit,
    private val onDailySummary: () -> Unit,
    private val onSyncMemory: () -> Unit,
    private val onOpenMemQuery: () -> Unit,
    private val onConfirmAction: () -> Unit,
    private val onCancelAction: () -> Unit
) {
    private val TAG = "InputCoordinator"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    init {
        subscribeToGestures()
        Log.i(TAG, "InputCoordinator initialized and subscribed to gesture events")
    }
    
    private fun subscribeToGestures() {
        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is XRealEvent.InputEvent.Gesture -> handleGesture(event.type)
                    else -> {} // Ignore other events
                }
            }
        }
    }
    
    private fun handleGesture(type: GestureType) {
        Log.i(TAG, "Handling gesture: $type")
        when (type) {
            GestureType.DOUBLE_TAP -> onCycleCamera()
            GestureType.TRIPLE_TAP -> onOpenMemQuery()
            GestureType.QUAD_TAP -> onSyncMemory()
            GestureType.NOD -> onConfirmAction()
            GestureType.SHAKE -> onCancelAction()
        }
    }
    
    fun cleanup() {
        scope.cancel()
        Log.i(TAG, "InputCoordinator cleaned up")
    }
}
