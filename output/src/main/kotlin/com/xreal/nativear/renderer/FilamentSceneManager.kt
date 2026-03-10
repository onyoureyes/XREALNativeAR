package com.xreal.nativear.renderer

import com.xreal.nativear.core.XRealLogger
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
        XRealLogger.impl.i(TAG, "Starting FilamentSceneManager event subscriptions")

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

        XRealLogger.impl.i(TAG, "FilamentSceneManager subscriptions active")
    }

    private fun handlePacemakerAnchor(event: XRealEvent.PerceptionEvent.SpatialAnchorEvent) {
        val ghost = renderer.ghostRunner ?: return

        when (event.action) {
            "CREATED", "UPDATED" -> {
                ghost.setPose(event.worldX, event.worldY, event.worldZ)
                ghost.setVisible(true)

                // 라벨 → 색상 매핑 (PaceColorMapper로 위임)
                val color = PaceColorMapper.mapLabelToColor(event.label)
                ghost.setColor(color.r, color.g, color.b, color.a)
            }
            "REMOVED" -> {
                ghost.setVisible(false)
            }
        }
    }
}
