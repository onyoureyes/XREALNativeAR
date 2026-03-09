package com.xreal.nativear.context

/**
 * BiometricTrendTracker: 바이오메트릭 시계열 트렌드 분석.
 *
 * 심박수/HRV의 5분 롤링 윈도우를 유지하고
 * 트렌드(상승/하강/안정), 평균, 변동성을 계산.
 * ContextAggregator가 buildSnapshot() 시 호출.
 */
class BiometricTrendTracker {

    private data class Sample(val value: Float, val timestamp: Long)

    private val hrSamples = ArrayDeque<Sample>(MAX_SAMPLES)
    private val hrvSamples = ArrayDeque<Sample>(MAX_SAMPLES)

    fun addHeartRate(bpm: Float) {
        val now = System.currentTimeMillis()
        hrSamples.addLast(Sample(bpm, now))
        pruneOld(hrSamples, now)
    }

    fun addHrv(rmssd: Float) {
        val now = System.currentTimeMillis()
        hrvSamples.addLast(Sample(rmssd, now))
        pruneOld(hrvSamples, now)
    }

    /** 5분 평균 심박수 */
    fun getHrAvg(): Int? {
        if (hrSamples.size < 2) return null
        return hrSamples.map { it.value }.average().toInt()
    }

    /** 5분 평균 HRV */
    fun getHrvAvg(): Float? {
        if (hrvSamples.size < 2) return null
        return hrvSamples.map { it.value }.average().toFloat()
    }

    /**
     * 심박수 트렌드 분석.
     * 최근 절반 평균 vs 이전 절반 평균 비교.
     * @return "RISING", "FALLING", "STABLE", or null (데이터 부족)
     */
    fun getHrTrend(): String? {
        if (hrSamples.size < 4) return null
        val mid = hrSamples.size / 2
        val firstHalf = hrSamples.toList().subList(0, mid).map { it.value }.average()
        val secondHalf = hrSamples.toList().subList(mid, hrSamples.size).map { it.value }.average()
        val diff = secondHalf - firstHalf
        return when {
            diff > TREND_THRESHOLD -> "RISING"
            diff < -TREND_THRESHOLD -> "FALLING"
            else -> "STABLE"
        }
    }

    /** 심박수 변동 계수 (높을수록 불안정) */
    fun getHrVariability(): Float? {
        if (hrSamples.size < 3) return null
        val values = hrSamples.map { it.value }
        val mean = values.average()
        if (mean < 1.0) return null
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return (Math.sqrt(variance) / mean).toFloat()
    }

    /** 추정 안정 시 심박수 (최저 10% 평균) */
    fun getEstimatedRestingHr(): Int? {
        if (hrSamples.size < 10) return null
        val sorted = hrSamples.map { it.value }.sorted()
        val bottom10pct = sorted.take(maxOf(1, sorted.size / 10))
        return bottom10pct.average().toInt()
    }

    private fun pruneOld(samples: ArrayDeque<Sample>, now: Long) {
        while (samples.size > MAX_SAMPLES || (samples.isNotEmpty() && now - samples.first().timestamp > WINDOW_MS)) {
            samples.removeFirst()
        }
    }

    companion object {
        private const val WINDOW_MS = 5 * 60 * 1000L  // 5분 윈도우
        private const val MAX_SAMPLES = 300  // 최대 300개 (1초 간격 기준 5분)
        private const val TREND_THRESHOLD = 5.0  // bpm 차이 임계값
    }
}
