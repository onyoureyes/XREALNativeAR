package com.xreal.ai

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.PriorityBlockingQueue

/**
 * InferenceScheduler: Manages a priority-based queue for AI model execution.
 */
class InferenceScheduler(private val scope: CoroutineScope) {
    private val TAG = "AI_SCHEDULER"
    
    data class InferenceTask(
        val priority: Int,
        val timestamp: Long = System.currentTimeMillis(),
        val action: suspend () -> Unit
    ) : Comparable<InferenceTask> {
        override fun compareTo(other: InferenceTask): Int {
            return if (this.priority != other.priority) {
                this.priority.compareTo(other.priority)
            } else {
                this.timestamp.compareTo(other.timestamp)
            }
        }
    }

    private val queue = PriorityBlockingQueue<InferenceTask>()
    private var isRunning = true

    init {
        startProcessing()
    }

    private fun startProcessing() {
        scope.launch(Dispatchers.Default) {
            while (isRunning) {
                try {
                    val task = queue.take()
                    task.action()
                } catch (e: Exception) {
                    Log.e(TAG, "Task Execution Error: ${e.message}")
                }
            }
        }
    }

    fun submit(priority: Int, action: suspend () -> Unit) {
        queue.offer(InferenceTask(priority, action = action))
    }

    fun stop() {
        isRunning = false
    }

    companion object {
        const val PRIORITY_CRITICAL = 1
        const val PRIORITY_HIGH = 2
        const val PRIORITY_MEDIUM = 3
        const val PRIORITY_LOW = 4
    }
}
