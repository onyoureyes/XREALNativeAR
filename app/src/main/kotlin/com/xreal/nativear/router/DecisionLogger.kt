package com.xreal.nativear.router

import android.util.Log
import com.xreal.nativear.IMemoryService
import kotlinx.coroutines.*
import org.json.JSONObject

class DecisionLogger(
    private val memoryService: IMemoryService
) {
    private val TAG = "DecisionLogger"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val buffer = mutableListOf<RouterDecision>()
    private val FLUSH_INTERVAL_MS = 30_000L
    private val MAX_BUFFER_SIZE = 50

    private var flushJob: Job? = null

    fun start() {
        flushJob = scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }
        Log.i(TAG, "DecisionLogger started (flush every ${FLUSH_INTERVAL_MS / 1000}s)")
    }

    fun log(decision: RouterDecision) {
        synchronized(buffer) {
            // H4 FIX: Drop oldest when buffer full and flush is busy
            if (buffer.size >= MAX_BUFFER_SIZE) {
                buffer.removeAt(0) // Drop oldest to prevent unbounded growth
                scope.launch { flush() }
            }
            buffer.add(decision)
        }
    }

    private suspend fun flush() {
        val snapshot: List<RouterDecision>
        synchronized(buffer) {
            if (buffer.isEmpty()) return
            snapshot = buffer.toList()
            buffer.clear()
        }

        for (decision in snapshot) {
            try {
                val metadata = JSONObject().apply {
                    put("type", "ROUTER_DECISION")
                    put("router_id", decision.routerId)
                    put("action", decision.action)
                    put("confidence", decision.confidence.toDouble())
                    put("priority", decision.priority)
                    put("reason", decision.reason)
                    for ((key, value) in decision.metadata) {
                        when (value) {
                            is Number -> put(key, value.toDouble())
                            is Boolean -> put(key, value)
                            else -> put(key, value.toString())
                        }
                    }
                }.toString()

                val content = "[${decision.routerId}] ${decision.action}: ${decision.reason}"

                memoryService.saveMemory(
                    content = content,
                    role = "ROUTER",
                    metadata = metadata
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist decision: ${e.message}")
            }
        }
        Log.d(TAG, "Flushed ${snapshot.size} router decisions to memory DB")
    }

    fun release() {
        // C3 FIX: Non-blocking final flush (avoids ANR from runBlocking on Main thread)
        flushJob?.cancel()
        CoroutineScope(Dispatchers.IO).launch {
            try { flush() } catch (_: Exception) { }
        }
        scope.cancel()
        Log.i(TAG, "DecisionLogger released")
    }
}
