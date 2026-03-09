package com.xreal.nativear.router

data class RouterDecision(
    val routerId: String,
    val action: String,
    val confidence: Float,
    val reason: String,
    val priority: Int = 0,
    val metadata: Map<String, Any> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

data class RouterConfig(
    val thresholds: MutableMap<String, Float> = mutableMapOf(),
    val weights: MutableMap<String, Float> = mutableMapOf(),
    val flags: MutableMap<String, Boolean> = mutableMapOf()
) {
    fun mergeFrom(other: RouterConfig) {
        thresholds.putAll(other.thresholds)
        weights.putAll(other.weights)
        flags.putAll(other.flags)
    }
}

class RouterMetrics {
    @Volatile var totalDecisions: Long = 0L
        private set
    @Volatile var totalSuppressed: Long = 0L
        private set
    @Volatile var avgConfidence: Float = 0f
        private set
    @Volatile var avgLatencyNs: Long = 0L
        private set
    @Volatile var lastDecisionTime: Long = 0L
        private set

    private var confidenceAccum: Double = 0.0
    private var latencyAccum: Long = 0L

    @Synchronized
    fun record(confidence: Float, latencyNs: Long, wasSuppressed: Boolean) {
        totalDecisions++
        if (wasSuppressed) totalSuppressed++
        confidenceAccum += confidence
        latencyAccum += latencyNs
        avgConfidence = (confidenceAccum / totalDecisions).toFloat()
        avgLatencyNs = latencyAccum / totalDecisions
        lastDecisionTime = System.currentTimeMillis()
    }

    fun suppressionRate(): Float =
        if (totalDecisions > 0) totalSuppressed.toFloat() / totalDecisions else 0f

    fun toMap(): Map<String, Any> = mapOf(
        "total_decisions" to totalDecisions,
        "total_suppressed" to totalSuppressed,
        "avg_confidence" to avgConfidence,
        "avg_latency_us" to (avgLatencyNs / 1000),
        "suppression_rate" to suppressionRate()
    )
}
