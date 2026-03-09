package com.xreal.nativear.router

import com.xreal.nativear.core.XRealEvent

interface IRouter {
    val id: String
    val config: RouterConfig
    val metrics: RouterMetrics

    fun start()
    fun stop()
    fun evaluate(event: XRealEvent): RouterDecision?
    fun updateConfig(newConfig: RouterConfig)
    fun release()
}
