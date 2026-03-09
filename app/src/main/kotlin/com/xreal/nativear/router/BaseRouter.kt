package com.xreal.nativear.router

import android.util.Log
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.*

abstract class BaseRouter(
    override val id: String,
    protected val eventBus: GlobalEventBus,
    protected val decisionLogger: DecisionLogger
) : IRouter {

    protected val TAG = "Router[$id]"
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val config = RouterConfig()
    override val metrics = RouterMetrics()

    private var collectionJob: Job? = null

    protected abstract fun shouldProcess(event: XRealEvent): Boolean
    protected abstract fun act(decision: RouterDecision)

    override fun start() {
        collectionJob = scope.launch {
            eventBus.events.collect { event ->
                try {
                    if (shouldProcess(event)) {
                        processEvent(event)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "이벤트 처리 오류 (루프 유지됨): ${event::class.simpleName} — ${e.message}", e)
                }
            }
        }
        Log.i(TAG, "Started")
    }

    private fun processEvent(event: XRealEvent) {
        val startNs = System.nanoTime()
        val decision = evaluate(event)
        val elapsedNs = System.nanoTime() - startNs

        if (decision != null) {
            val isSuppressed = decision.action == "SUPPRESS" || decision.action == "FORM_OK"
            metrics.record(decision.confidence, elapsedNs, isSuppressed)
            decisionLogger.log(decision)

            if (!isSuppressed) {
                act(decision)
            }
        }
    }

    override fun stop() {
        collectionJob?.cancel()
        collectionJob = null
        Log.i(TAG, "Stopped")
    }

    override fun updateConfig(newConfig: RouterConfig) {
        config.mergeFrom(newConfig)
        Log.i(TAG, "Config updated: thresholds=${config.thresholds}")
    }

    override fun release() {
        stop()
        scope.cancel()
        Log.i(TAG, "Released")
    }
}
