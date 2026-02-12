package com.xreal.nativear.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * GlobalEventBus: The "Nerve Center" of the distributed architecture.
 * Decouples Producers (Managers) from Consumers (Coordinators).
 */
class GlobalEventBus {

    private val _events = MutableSharedFlow<XRealEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<XRealEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun publish(event: XRealEvent) {
        scope.launch {
            _events.emit(event)
        }
    }

    // Direct synchronous emit for high-frequency events (e.g. HeadPose) if needed
    suspend fun emit(event: XRealEvent) {
        _events.emit(event)
    }
}
