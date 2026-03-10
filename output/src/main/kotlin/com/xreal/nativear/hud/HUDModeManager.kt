package com.xreal.nativear.hud

import com.xreal.nativear.core.XRealLogger
import com.xreal.nativear.context.LifeSituation
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * HUDModeManager: Orchestrates automatic HUD mode switching
 * based on SituationChanged events.
 *
 * Subscribes to EventBus and delegates to HUDTemplateEngine
 * for template selection and rendering.
 */
class HUDModeManager(
    private val templateEngine: HUDTemplateEngine,
    private val eventBus: GlobalEventBus,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "HUDModeManager"
    }

    private var subscriptionJob: Job? = null

    fun start() {
        XRealLogger.impl.i(TAG, "HUDModeManager started")

        // Subscribe to situation changes
        subscriptionJob = scope.launch(Dispatchers.Default) {
            eventBus.events.collectLatest { event ->
                when (event) {
                    is XRealEvent.SystemEvent.SituationChanged -> {
                        try {
                            templateEngine.onSituationChanged(event.newSituation)
                            XRealLogger.impl.d(TAG, "HUD switched for situation: ${event.newSituation.displayName}")
                        } catch (e: Exception) {
                            XRealLogger.impl.e(TAG, "Error switching HUD: ${e.message}")
                        }
                    }
                    else -> { /* ignore */ }
                }
            }
        }

        // Activate default template
        templateEngine.switchMode(HUDMode.DEFAULT)
    }

    fun stop() {
        subscriptionJob?.cancel()
        templateEngine.deactivateAll()
        XRealLogger.impl.i(TAG, "HUDModeManager stopped")
    }

    /**
     * Manually switch HUD mode (e.g., from voice command or gesture).
     */
    fun switchMode(mode: HUDMode) {
        templateEngine.switchMode(mode)
    }

    /**
     * Toggle debug HUD (QUAD_TAP gesture handler).
     */
    fun toggleDebug() {
        if (templateEngine.currentMode.value == HUDMode.DEBUG) {
            templateEngine.switchMode(HUDMode.DEFAULT)
        } else {
            templateEngine.switchMode(HUDMode.DEBUG)
        }
    }
}
