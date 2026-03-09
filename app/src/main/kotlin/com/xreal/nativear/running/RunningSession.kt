package com.xreal.nativear.running

import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.RunningState
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.*

/**
 * RunningSession — 러닝 세션 상태 머신 (ACTIVE ↔ PAUSED → STOPPED).
 *
 * ## 역할
 * - 세션 라이프사이클 (시작, 일시정지, 재개, 종료)
 * - 1초 해상도 타이머 (elapsedMs)
 * - 랩(Lap) 기록 (거리, 시간, 페이스)
 * - RunningSessionState 이벤트 발행 → UserStateTracker, MissionDetectorRouter
 *
 * ## 거리 추적 주의사항
 * totalDistanceMeters는 외부에서 갱신됨 (RunningCoachManager.hudUpdateLoop에서
 * `session.totalDistanceMeters = routeTracker.totalDistanceMeters` 매 1초 동기화).
 */
class RunningSession(
    private val eventBus: GlobalEventBus
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var state: RunningState = RunningState.STOPPED
        private set
    var startTimeMs: Long = 0L
        private set
    var elapsedMs: Long = 0L
        private set
    var totalDistanceMeters: Float = 0f

    data class Lap(val number: Int, val durationMs: Long, val distanceMeters: Float, val avgPace: Float)
    val laps = mutableListOf<Lap>()
    private var lapStartTime: Long = 0L
    private var lapStartDistance: Float = 0f

    private var timerJob: Job? = null

    fun start() {
        if (state == RunningState.ACTIVE) return
        state = RunningState.ACTIVE
        startTimeMs = System.currentTimeMillis()
        lapStartTime = startTimeMs
        lapStartDistance = 0f
        totalDistanceMeters = 0f
        elapsedMs = 0L
        laps.clear()
        startTimer()
        publishState()
    }

    fun pause() {
        if (state != RunningState.ACTIVE) return
        state = RunningState.PAUSED
        timerJob?.cancel()
        publishState()
    }

    fun resume() {
        if (state != RunningState.PAUSED) return
        state = RunningState.ACTIVE
        startTimer()
        publishState()
    }

    fun stop(): SessionSummary {
        state = RunningState.STOPPED
        timerJob?.cancel()
        publishState()
        return SessionSummary(
            totalDurationMs = elapsedMs,
            totalDistanceMeters = totalDistanceMeters,
            laps = laps.toList(),
            avgPaceMinPerKm = if (totalDistanceMeters > 0) (elapsedMs / 60000f) / (totalDistanceMeters / 1000f) else 0f
        )
    }

    fun lap() {
        if (state != RunningState.ACTIVE) return
        val now = System.currentTimeMillis()
        val lapDuration = now - lapStartTime
        val lapDistance = totalDistanceMeters - lapStartDistance
        val lapPace = if (lapDistance > 0) (lapDuration / 60000f) / (lapDistance / 1000f) else 0f
        laps.add(Lap(laps.size + 1, lapDuration, lapDistance, lapPace))
        lapStartTime = now
        lapStartDistance = totalDistanceMeters
    }

    fun getElapsedSeconds(): Long = elapsedMs / 1000

    private fun startTimer() {
        timerJob = scope.launch {
            while (isActive) {
                delay(1000L)
                elapsedMs += 1000L
            }
        }
    }

    private fun publishState() {
        scope.launch {
            eventBus.publish(XRealEvent.SystemEvent.RunningSessionState(state, elapsedMs / 1000, totalDistanceMeters))
        }
    }

    data class SessionSummary(
        val totalDurationMs: Long,
        val totalDistanceMeters: Float,
        val laps: List<Lap>,
        val avgPaceMinPerKm: Float
    )

    fun release() {
        timerJob?.cancel()
        scope.cancel()
    }
}
