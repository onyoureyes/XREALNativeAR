package com.xreal.nativear.context

/**
 * IContextSnapshot: 현재 상황 스냅샷 제공 인터페이스.
 * 구현체: ContextAggregator
 */
interface IContextSnapshot {
    fun start()
    fun stop()
    fun buildSnapshot(): ContextSnapshot
}
