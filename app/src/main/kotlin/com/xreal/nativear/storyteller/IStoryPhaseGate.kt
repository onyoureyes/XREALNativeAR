package com.xreal.nativear.storyteller

/**
 * IStoryPhaseGate — 상태 머신 읽기 + 전이 트리거 인터페이스.
 *
 * ai ↔ storyteller 순환 의존 해소:
 * - AIResourceRegistry는 IStoryPhaseGate만 의존 (storyteller 패키지 참조 최소화)
 * - StorytellerOrchestrator는 구체 StoryPhaseController 사용 (같은 패키지)
 *
 * 구현체: StoryPhaseController
 */
interface IStoryPhaseGate {
    /** 현재 상태 (모든 서비스에서 읽기 전용 참조) */
    val currentPhase: StoryPhase

    val lastTransitionMs: Long
    val lastActivityMs: Long
    val awakeningStartMs: Long

    // ── 전이 트리거 ──

    /** 센서 활동 감지 (움직임, 음성, 터치) → DORMANT/SLEEPING → AWAKENING */
    fun onActivityDetected()

    /** 사용자 음성 명령 → 강제 NARRATING */
    fun onUserCommand()

    /** AWAKENING → OBSERVING (센서 안정화 완료 시) */
    fun checkAwakeningComplete(): Boolean

    /** OBSERVING → NARRATING (상황 인식 완료 시) */
    fun onSituationRecognized()

    /** NARRATING → REFLECTING */
    fun enterReflecting()

    /** REFLECTING → NARRATING */
    fun exitReflecting()

    /** NARRATING → WINDING_DOWN */
    fun enterWindingDown()

    /** WINDING_DOWN → SLEEPING */
    fun enterSleeping()

    /** 비활성 검사 → DORMANT 전이 */
    fun checkInactivity()

    /** 전이 리스너 등록 */
    fun setPhaseListener(listener: (old: StoryPhase, new: StoryPhase) -> Unit)

    /** 진단 */
    fun getStatus(): String
}
