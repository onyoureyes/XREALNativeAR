package com.xreal.nativear.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * GlobalEventBus — 전체 시스템의 중추 신경계 (Central Nervous System).
 *
 * ## 설계 의도
 * XREAL AR 라이프로그 앱의 모든 컴포넌트를 **완전히 디커플링**하기 위한 Pub/Sub 허브.
 * 하드웨어 센서(IMU, GPS, 카메라), AI 파이프라인, 러닝 코치, 미션 시스템 등
 * 모든 모듈이 직접 참조 없이 이벤트만으로 통신한다.
 *
 * ## 아키텍처 흐름
 * ```
 * [Producer]                    [Consumer]
 * HardwareManager  ──publish──▶  ┌─ InputCoordinator (제스처/음성)
 * LocationManager  ──publish──▶  ├─ VisionCoordinator (객체/OCR)
 * AudioAnalysisService ─────▶  ├─ OutputCoordinator (TTS/HUD)
 * WearDataReceiver ──publish──▶  ├─ RunningCoachManager (러닝 코칭)
 * VisionManager    ──publish──▶  ├─ UserStateTracker (상태 전이)
 *                                └─ MissionDetectorRouter (미션 트리거)
 * ```
 *
 * ## 설계 결정
 * - **replay = 0**: 새 구독자에게 과거 이벤트를 전달하지 않음 (실시간 센서 데이터 특성상 불필요)
 * - **buffer = 64**: IMU 1kHz + 카메라 30fps + GPS 등 동시 이벤트를 흡수
 * - **DROP_OLDEST**: 소비자가 느려도 생산자가 절대 블로킹되지 않음 (센서 데이터 유실 허용)
 * - **tryEmit() 우선**: 코루틴 할당 없이 논블로킹 발행 (고빈도 이벤트에 필수)
 *
 * @see XRealEvent 이벤트 계층 구조 (Input / Perception / System / ActionRequest)
 */
class GlobalEventBus {

    private val _events = MutableSharedFlow<XRealEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<XRealEvent> = _events.asSharedFlow()

    /**
     * 논블로킹 이벤트 발행. 버퍼가 가득 차면 가장 오래된 이벤트를 드롭.
     * 코루틴 할당이 없으므로 센서 콜백이나 JNI 콜백에서 안전하게 호출 가능.
     */
    fun publish(event: XRealEvent) {
        SequenceTracer.bus("publish → ${event::class.simpleName}")
        _events.tryEmit(event)
    }

    /**
     * 서스펜드 함수 기반 발행. 백프레셔를 존중하며, 버퍼 공간이 확보될 때까지 대기.
     * 드롭 불가한 중요 이벤트(예: 세션 종료)에 사용.
     */
    suspend fun emit(event: XRealEvent) {
        SequenceTracer.bus("emit → ${event::class.simpleName}")
        _events.emit(event)
    }
}
