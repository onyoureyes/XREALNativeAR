package com.xreal.nativear.resilience

import android.util.Log
import com.xreal.nativear.context.SituationRecognizer
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.focus.FocusModeManager

/**
 * OperationalDirector — 상황 균형 조정가 (LAYER 2, Priority: NORMAL).
 *
 * ## ★ Phase E: SystemHarmony 리팩터링 후 역할 변경
 *
 * **이전**: 독립 30초 폐루프 제어 + FocusMode 자동 조정 (지휘자 역할)
 * **현재**: SystemConductor의 위성 섹션. 상황 분석 로직은 SystemConductor 내부로 이전됨.
 *
 * ## 현재 역할
 * - `start()`/`stop()` 생명주기만 유지 (AppBootstrapper 호환)
 * - 상황 기반 CapabilityTier 목표 + FocusMode 조정 로직:
 *   → SystemConductor의 30초 루프에서 직접 실행됨 (코드 이전)
 *
 * ## SystemHarmony에서의 위치
 * ```
 * SystemConductor (지휘자)
 *   ↑ getOperationalGoal() + adjustFocusMode() 로직을 내부로 흡수
 *   ← OperationalDirector는 이제 컨텍스트 제공자 역할만 수행
 * ```
 *
 * @see SystemConductor 실제 상황 분석 + FocusMode 조정 로직 위치
 * @see SystemHarmony 역할 정의 및 우선순위 규칙
 */
class OperationalDirector(
    private val eventBus: GlobalEventBus,
    private val failsafeController: FailsafeController,
    private val focusModeManager: FocusModeManager,
    private val situationRecognizer: SituationRecognizer
) {
    companion object {
        private const val TAG = "OperationalDirector"
    }

    fun start() {
        // ★ Phase E: 30초 루프가 SystemConductor로 이전됨.
        // 상황 분석(getOperationalGoal), FocusMode 조정(adjustFocusMode),
        // 화장실 키워드 감지 모두 SystemConductor 내부에서 실행.
        // OperationalDirector는 AppBootstrapper 호환을 위해 start()/stop()만 유지.
        Log.i(TAG, "OperationalDirector 시작 — 상황 분석 로직은 SystemConductor 위임됨 (SystemHarmony Phase E)")
    }

    fun stop() {
        Log.i(TAG, "OperationalDirector 종료")
    }
}
