package com.xreal.nativear.running

import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.*

/**
 * BreathingAnalyzer — 오디오 기반 호흡률 검출기.
 *
 * ## 작동 원리
 * 1. AudioAnalysisService의 WhisperEngine에서 10초 16kHz PCM 획득
 * 2. 50ms 윈도우로 진폭 엔벨로프 계산 (RMS 에너지)
 * 3. 자기상관(autocorrelation)으로 주기성 검출
 * 4. 10-60 BPM 범위에서 호흡률 추정
 * 5. BreathingMetrics 이벤트 발행 → FormRouter
 *
 * ## 오디오 소스
 * audioProvider 클로저를 통해 WhisperEngine에서 PCM을 가져옴.
 * RunningCoachManager.init에서 AudioAnalysisService.audioProvider로 연결.
 * BreathingAnalyzer 자체는 마이크에 직접 접근하지 않음.
 *
 * ## 분석 주기
 * 5초마다 분석 (러닝 중 호흡 변화 속도에 맞춤)
 *
 * @see FormRouter.COACH_BREATHING 호흡 불규칙 시 코칭 메시지
 */
class BreathingAnalyzer(
    private val eventBus: GlobalEventBus
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var analysisJob: Job? = null

    // Audio provider: set by RunningCoachManager to get 16kHz PCM from WhisperEngine
    var audioProvider: (() -> ShortArray?)? = null

    companion object {
        const val SAMPLE_RATE = 16000
        const val MIN_BPM = 10f
        const val MAX_BPM = 60f
    }

    fun start() {
        analysisJob = scope.launch {
            while (isActive) {
                delay(5000L)
                analyzeBreathing()
            }
        }
    }

    private suspend fun analyzeBreathing() {
        val audio = audioProvider?.invoke() ?: return
        if (audio.size < SAMPLE_RATE * 4) return // Need at least 4 seconds

        // Convert ShortArray to float amplitude
        val floatAudio = FloatArray(audio.size) { audio[it].toFloat() / 32768f }

        // 1. Compute amplitude envelope (downsample to ~20Hz)
        val envelope = computeAmplitudeEnvelope(floatAudio, windowSize = 800) // 50ms window

        // 2. Autocorrelation to find periodicity
        val (bpm, confidence) = findBreathingRate(envelope)

        if (confidence > 0.3f && bpm in MIN_BPM..MAX_BPM) {
            val isRegular = confidence > 0.5f

            eventBus.publish(XRealEvent.PerceptionEvent.BreathingMetrics(
                breathsPerMinute = bpm,
                isRegular = isRegular,
                confidence = confidence,
                timestamp = System.currentTimeMillis()
            ))
        }
    }

    private fun computeAmplitudeEnvelope(audio: FloatArray, windowSize: Int): FloatArray {
        val downsampled = FloatArray(audio.size / windowSize)
        for (i in downsampled.indices) {
            val start = i * windowSize
            val end = minOf(start + windowSize, audio.size)
            var sum = 0f
            for (j in start until end) sum += Math.abs(audio[j])
            downsampled[i] = sum / (end - start)
        }
        return downsampled
    }

    private fun findBreathingRate(envelope: FloatArray): Pair<Float, Float> {
        val dsRate = 20 // approximate rate after downsampling
        val minLag = dsRate * 60 / MAX_BPM.toInt()  // ~20 samples
        val maxLag = dsRate * 60 / MIN_BPM.toInt()   // ~120 samples

        if (envelope.size < maxLag + 1) return Pair(0f, 0f)

        var bestLag = minLag
        var bestCorr = -1f
        val mean = envelope.average().toFloat()

        for (lag in minLag..minOf(maxLag, envelope.size / 2)) {
            var corr = 0f
            var norm1 = 0f
            var norm2 = 0f
            val n = envelope.size - lag
            for (i in 0 until n) {
                val a = envelope[i] - mean
                val b = envelope[i + lag] - mean
                corr += a * b
                norm1 += a * a
                norm2 += b * b
            }
            val normalized = if (norm1 > 0 && norm2 > 0) corr / Math.sqrt((norm1 * norm2).toDouble()).toFloat() else 0f
            if (normalized > bestCorr) {
                bestCorr = normalized
                bestLag = lag
            }
        }

        val bpm = if (bestLag > 0) (dsRate.toFloat() * 60f) / bestLag else 0f
        return Pair(bpm, bestCorr)
    }

    fun stop() {
        analysisJob?.cancel()
    }

    fun release() {
        stop()
        scope.cancel()
    }
}
