package com.xreal.nativear.router.persona

import com.xreal.nativear.core.XRealEvent
import kotlin.reflect.KClass

/**
 * Defines when a specific persona should be automatically triggered by an event.
 */
data class TriggerRule(
    val personaId: String,
    val eventType: KClass<out XRealEvent>,
    val condition: (XRealEvent) -> Boolean,
    val queryBuilder: (XRealEvent) -> String,
    val contextBuilder: ((XRealEvent) -> String)? = null,
    val cooldownMs: Long = 30_000,
    val priority: Int = 0,
    val speakResult: Boolean = false
)
