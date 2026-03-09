package com.xreal.nativear.storyteller

import android.util.Log
import com.xreal.nativear.policy.PolicyReader
import java.util.concurrent.atomic.AtomicReference

/**
 * StoryPhase — 시스템 전체 상태 머신.
 *
 * 모든 자율 행동의 유일한 허가 주체.
 * 게이트/예산/케이던스는 2차 안전장치. 이 상태 머신이 1차 통제.
 *
 * ## 상태 전이
 * ```
 * DORMANT ──(움직임/음성/터치)──→ AWAKENING
 * AWAKENING ──(센서 준비 완료)──→ OBSERVING
 * OBSERVING ──(상황 인식 완료)──→ NARRATING
 * NARRATING ──(리플렉션 간격 도달)──→ REFLECTING ──(완료)──→ NARRATING
 * NARRATING ──(비활성 타이머)──→ DORMANT
 * NARRATING ──(저녁 시간대)──→ WINDING_DOWN
 * WINDING_DOWN ──(요약 완료)──→ SLEEPING
 * SLEEPING ──(아침 시간대 + 움직임)──→ AWAKENING
 * ANY ──(사용자 음성 명령)──→ NARRATING (강제 전이)
 * ```
 *
 * ## 상태별 허용 행동
 * - DORMANT: 센서 수집만 (AI 호출 0건)
 * - AWAKENING: 센서 초기화 + 규칙 기반 분류 (AI 호출 0건)
 * - OBSERVING: 상황 인식 AI 1회
 * - NARRATING: 전체 서비스 파이프라인 (순차적)
 * - REFLECTING: 리플렉션 AI 1회
 * - WINDING_DOWN: 하루 요약 AI 1회
 * - SLEEPING: 아무것도 안 함 (AI 호출 0건)
 */
enum class StoryPhase {
    /** 비활성 — 테이블 위, 주머니 속. AI 호출 0건. */
    DORMANT,

    /** 깨어남 — 센서 초기화 중. AI 호출 0건. */
    AWAKENING,

    /** 관찰 중 — 상황 인식 AI 1회 허용. */
    OBSERVING,

    /** 진행 중 — 전체 서비스 파이프라인 허용. */
    NARRATING,

    /** 리플렉션 — 주기적 반성 AI 1회. */
    REFLECTING,

    /** 하루 마무리 — 요약 생성. */
    WINDING_DOWN,

    /** 수면 — 아무것도 안 함. AI 호출 0건. */
    SLEEPING;

    /** 이 상태에서 ProactiveScheduler 태스크 실행이 허용되는가 */
    val allowsProactiveTasks: Boolean get() = this == NARRATING

    /** 이 상태에서 AI 호출이 허용되는가 (어떤 종류든) */
    val allowsAICalls: Boolean get() = when (this) {
        DORMANT, AWAKENING, SLEEPING -> false
        OBSERVING, NARRATING, REFLECTING, WINDING_DOWN -> true
    }

    /** 이 상태에서 deepClassify (AI 상황 인식)가 허용되는가 */
    val allowsDeepClassify: Boolean get() = this == OBSERVING || this == NARRATING

    /** 이 상태에서 센서 수집이 허용되는가 */
    val allowsSensorCollection: Boolean get() = this != SLEEPING
}

/**
 * StoryPhaseController — 상태 전이 관리 + 글로벌 접근점.
 *
 * Koin single로 등록. 모든 서비스가 `phaseController.currentPhase`를 참조하여
 * 자신의 행동이 허용되는지 확인.
 */
class StoryPhaseController : IStoryPhaseGate {
    companion object {
        private const val TAG = "StoryPhaseController"

        // 비활성 판정 기준 (센서 변화 없이 이 시간 경과 시 DORMANT 전이)
        private val INACTIVITY_TO_DORMANT_MS: Long get() =
            PolicyReader.getLong("storyteller.inactivity_dormant_ms", 180_000L) // 3분

        // AWAKENING → OBSERVING 전이 대기 시간 (센서 안정화)
        private val AWAKENING_STABILIZE_MS: Long get() =
            PolicyReader.getLong("storyteller.awakening_stabilize_ms", 5_000L) // 5초

        // 수면 시간대
        private val SLEEP_START_HOUR: Int get() =
            PolicyReader.getInt("storyteller.sleep_start_hour", 23)
        private val SLEEP_END_HOUR: Int get() =
            PolicyReader.getInt("storyteller.sleep_end_hour", 6)
    }

    private val _phase = AtomicReference(StoryPhase.DORMANT)

    /** 현재 상태 (모든 서비스에서 읽기 전용 참조) */
    override val currentPhase: StoryPhase get() = _phase.get()

    // 전이 시각 추적
    @Volatile override var lastTransitionMs: Long = System.currentTimeMillis()
        private set
    @Volatile override var lastActivityMs: Long = System.currentTimeMillis()
        private set
    @Volatile override var awakeningStartMs: Long = 0L
        private set

    // 전이 리스너 (StorytellerOrchestrator가 등록)
    private var onPhaseChanged: ((old: StoryPhase, new: StoryPhase) -> Unit)? = null

    override fun setPhaseListener(listener: (old: StoryPhase, new: StoryPhase) -> Unit) {
        onPhaseChanged = listener
    }

    // ── 전이 메서드 (조건 검사 포함) ──

    /**
     * 센서 활동 감지 (움직임, 음성, 터치 등).
     * DORMANT/SLEEPING → AWAKENING 전이.
     */
    override fun onActivityDetected() {
        lastActivityMs = System.currentTimeMillis()

        when (_phase.get()) {
            StoryPhase.DORMANT -> {
                transitionTo(StoryPhase.AWAKENING)
                awakeningStartMs = System.currentTimeMillis()
            }
            StoryPhase.SLEEPING -> {
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                if (hour !in SLEEP_START_HOUR..23 && hour !in 0 until SLEEP_END_HOUR) {
                    // 수면 시간대가 아니면 깨어남
                    transitionTo(StoryPhase.AWAKENING)
                    awakeningStartMs = System.currentTimeMillis()
                }
            }
            else -> { /* 이미 활성 상태 — lastActivityMs 갱신만 */ }
        }
    }

    /**
     * 사용자 음성 명령 감지 → 강제 NARRATING 전이.
     * 어떤 상태에서든 즉시 전이 (사용자 의도 최우선).
     */
    override fun onUserCommand() {
        lastActivityMs = System.currentTimeMillis()
        val current = _phase.get()
        if (current != StoryPhase.NARRATING) {
            transitionTo(StoryPhase.NARRATING)
        }
    }

    /**
     * 센서 안정화 완료 (AWAKENING → OBSERVING).
     * StorytellerOrchestrator tick에서 호출.
     */
    override fun checkAwakeningComplete(): Boolean {
        if (_phase.get() != StoryPhase.AWAKENING) return false
        if (System.currentTimeMillis() - awakeningStartMs >= AWAKENING_STABILIZE_MS) {
            transitionTo(StoryPhase.OBSERVING)
            return true
        }
        return false
    }

    /**
     * 상황 인식 완료 (OBSERVING → NARRATING).
     * SituationRecognizer에서 deepClassify 성공 후 호출.
     */
    override fun onSituationRecognized() {
        if (_phase.get() == StoryPhase.OBSERVING) {
            transitionTo(StoryPhase.NARRATING)
        }
    }

    /**
     * 리플렉션 시작/완료.
     */
    override fun enterReflecting() {
        if (_phase.get() == StoryPhase.NARRATING) {
            transitionTo(StoryPhase.REFLECTING)
        }
    }

    override fun exitReflecting() {
        if (_phase.get() == StoryPhase.REFLECTING) {
            transitionTo(StoryPhase.NARRATING)
        }
    }

    /**
     * 하루 마무리 (NARRATING → WINDING_DOWN).
     */
    override fun enterWindingDown() {
        val current = _phase.get()
        if (current == StoryPhase.NARRATING || current == StoryPhase.REFLECTING) {
            transitionTo(StoryPhase.WINDING_DOWN)
        }
    }

    /**
     * 수면 전이 (WINDING_DOWN → SLEEPING).
     */
    override fun enterSleeping() {
        if (_phase.get() == StoryPhase.WINDING_DOWN) {
            transitionTo(StoryPhase.SLEEPING)
        }
    }

    /**
     * 비활성 검사 — 주기적으로 호출 (ProactiveScheduler tick 또는 StorytellerOrchestrator).
     * 일정 시간 센서 변화 없으면 DORMANT 전이.
     */
    override fun checkInactivity() {
        val current = _phase.get()
        if (current == StoryPhase.NARRATING || current == StoryPhase.OBSERVING) {
            val elapsed = System.currentTimeMillis() - lastActivityMs
            if (elapsed >= INACTIVITY_TO_DORMANT_MS) {
                Log.i(TAG, "비활성 ${elapsed / 1000}초 → DORMANT")
                transitionTo(StoryPhase.DORMANT)
            }
        }
    }

    // ── 내부 ──

    private fun transitionTo(newPhase: StoryPhase) {
        val old = _phase.getAndSet(newPhase)
        if (old != newPhase) {
            lastTransitionMs = System.currentTimeMillis()
            Log.i(TAG, "상태 전이: $old → $newPhase")
            try {
                onPhaseChanged?.invoke(old, newPhase)
            } catch (e: Exception) {
                Log.e(TAG, "전이 리스너 오류: ${e.message}", e)
            }
        }
    }

    /** 진단용 */
    override fun getStatus(): String {
        val phase = _phase.get()
        val sinceLast = (System.currentTimeMillis() - lastTransitionMs) / 1000
        val sinceActivity = (System.currentTimeMillis() - lastActivityMs) / 1000
        return "Phase: $phase (${sinceLast}s), activity: ${sinceActivity}s ago"
    }
}
