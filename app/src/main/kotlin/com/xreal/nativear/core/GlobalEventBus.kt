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

    private val _events = MutableSharedFlow<XRealEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<XRealEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun publish(event: XRealEvent) {
        // Non-blocking emit. If buffer is full, drops oldest.
        // Zero allocation of new coroutines.
        _events.tryEmit(event)
    }

    // Direct synchronous emit for high-frequency events (e.g. HeadPose) if needed
    suspend fun emit(event: XRealEvent) {
        _events.emit(event)
    }
}
