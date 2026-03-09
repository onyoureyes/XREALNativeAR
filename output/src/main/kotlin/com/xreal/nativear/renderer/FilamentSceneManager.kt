package com.xreal.nativear.renderer

import android.util.Log
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * Subscribes to EventBus events and routes them to FilamentRenderer.
 *
 * Events consumed:
 * - HeadPoseUpdated → FilamentRenderer.setPose() (camera transform)
 * - SpatialAnchorEvent (pacemaker_dot) → GhostRunnerEntity.setPose/setColor
 */
class FilamentSceneManager(
    private val scope: CoroutineScope,
    private val eventBus: GlobalEventBus,
    private val renderer: FilamentRenderer
) {
    companion object {
        private const val TAG = "FilamentSceneManager"
        private const val PACER_ANCHOR_ID = "pacemaker_dot"
    }

    fun start() {
        Log.i(TAG, "Starting FilamentSceneManager event subscriptions")

        // Subscribe to VIO pose updates
        scope.launch {
            eventBus.events
                .filterIsInstance<XRealEvent.PerceptionEvent.HeadPoseUpdated>()
                .collect { pose ->
                    renderer.setPose(
                        pose.x, pose.y, pose.z,
                        pose.qx, pose.qy, pose.qz, pose.qw
                    )
                }
        }

        // Subscribe to spatial anchor events for ghost runner
        scope.launch {
            eventBus.events
                .filterIsInstance<XRealEvent.PerceptionEvent.SpatialAnchorEvent>()
                .collect { event ->
                    if (event.anchorId == PACER_ANCHOR_ID) {
                        handlePacemakerAnchor(event)
                    }
                }
        }

        Log.i(TAG, "FilamentSceneManager subscriptions active")
    }

    private fun handlePacemakerAnchor(event: XRealEvent.PerceptionEvent.SpatialAnchorEvent) {
        val ghost = renderer.ghostRunner ?: return

        when (event.action) {
            "CREATED", "UPDATED" -> {
                ghost.setPose(event.worldX, event.worldY, event.worldZ)
                ghost.setVisible(true)

                // Parse pace difference from label to determine color
                // Label format: "▶ +Xm" (behind, red) or "◀ -Xm" (ahead, green)
                val label = event.label
                when {
                    label.contains("▶") || label.contains("+") -> {
                        // User is behind pacer → red tint
                        ghost.setColor(1.0f, 0.3f, 0.2f, 0.6f)
                    }
                    label.contains("◀") || label.contains("-") -> {
                        // User is ahead of pacer → green tint
                        ghost.setColor(0.2f, 1.0f, 0.4f, 0.6f)
                    }
                    label.contains("✓") -> {
                        // On pace → white/cyan
                        ghost.setColor(0.4f, 0.9f, 1.0f, 0.5f)
                    }
                    else -> {
                        ghost.setColor(0.2f, 1.0f, 0.4f, 0.6f)
                    }
                }
            }
            "REMOVED" -> {
                ghost.setVisible(false)
            }
        }
    }
}
