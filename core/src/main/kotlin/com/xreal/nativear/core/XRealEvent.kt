package com.xreal.nativear.core

import android.graphics.Bitmap
import com.xreal.nativear.Detection
import com.xreal.nativear.FaceInfo
import com.xreal.nativear.DrawCommand
import com.xreal.nativear.OcrResult
import com.xreal.nativear.PoseKeypoint
import com.xreal.nativear.hand.GestureEvent
import com.xreal.nativear.hand.HandData
import com.xreal.nativear.spatial.AnchorLabel2D

/**
 * XRealEvent — 시스템 전체 이벤트 계층 구조 (Sealed Class Hierarchy).
 *
 * ## 설계 의도
 * 모든 컴포넌트 간 통신을 **타입 안전한 이벤트**로 정의.
 * Kotlin sealed class로 when 절에서 exhaustive matching을 보장하여
 * 새 이벤트 추가 시 모든 소비자에서 컴파일 에러로 누락을 방지.
 *
 * ## 4대 카테고리
 * | 카테고리 | 방향 | 설명 |
 * |---------|------|------|
 * | **InputEvent** | 사용자→시스템 | 음성 명령, 제스처, 터치 |
 * | **PerceptionEvent** | 센서→시스템 | GPS, IMU, 카메라, 워치 바이오, 러닝 역학 |
 * | **SystemEvent** | 시스템→전체 | 배터리, 온도, 네트워크, 세션 상태 |
 * | **ActionRequest** | 시스템→출력 | TTS, HUD 그리기, 메모리 저장 |
 *
 * ## 이벤트 흐름 예시 (러닝 코치)
 * ```
 * Step Detector → PdrStepUpdate → PositionFusionEngine
 * Phone GPS     → PhoneGps     → PositionFusionEngine → FusedPositionUpdate → RunningRouteTracker
 * Watch         → WatchHeartRate → RunningCoachManager → FormRouter → InterventionRouter → SpeakTTS
 * Pressure      → FloorChange   → RunningRouteTracker + RunningCoachHUD
 * ```
 *
 * @see GlobalEventBus 이벤트 발행/구독 허브
 */
sealed class XRealEvent {

    /**
     * InputEvent — 사용자 입력 이벤트.
     * 음성 명령(Whisper STT), IMU 제스처(NOD/SHAKE), 터치 입력.
     * InputCoordinator가 구독하여 적절한 핸들러로 라우팅.
     */
    sealed class InputEvent : XRealEvent() {
        data class VoiceCommand(val text: String) : InputEvent()
        data class Gesture(val type: GestureType) : InputEvent()
        data class Touch(val x: Float, val y: Float) : InputEvent()
        data class AudioLevel(val level: Float) : InputEvent()

        // Audio Embedding Event for Whisper-based lifelog
        data class AudioEmbedding(
            val transcript: String,
            val audioEmbedding: ByteArray,
            val timestamp: Long,
            val latitude: Double?,
            val longitude: Double?,
            val emotion: String? = null
        ) : InputEvent()

        data class EnrichedVoiceCommand(
            val text: String,
            val speaker: String,
            val emotion: String,
            val emotionScore: Float = 1.0f
        ) : InputEvent()

        // Voice commands specifically for running
        data class RunningVoiceCommand(
            val command: RunningCommandType
        ) : InputEvent()

        // Voice feedback for OutcomeTracker (Fix 2: voice → outcome pipeline)
        data class VoiceFeedback(
            val text: String,
            val sentiment: FeedbackSentiment,
            val confidenceScore: Float = 1.0f
        ) : InputEvent()
    }

    /**
     * PerceptionEvent — 센서 및 AI 모델의 인식 결과.
     *
     * 하드웨어 센서(IMU, GPS, 기압계, 워치), AI 모델(YOLO, OCR, Whisper),
     * 그리고 융합 엔진(Kalman filter, PDR)의 출력.
     * VisionCoordinator, RunningCoachManager 등이 구독.
     */
    sealed class PerceptionEvent : XRealEvent() {
        data class ObjectsDetected(val results: List<Detection>) : PerceptionEvent()
        data class OcrDetected(val results: List<OcrResult>, val width: Int, val height: Int) : PerceptionEvent()
        data class SceneCaptured(val bitmap: Bitmap, val ocrText: String) : PerceptionEvent()
        data class LocationUpdated(val lat: Double, val lon: Double, val address: String?) : PerceptionEvent()
        data class HeadPoseUpdated(
            val x: Float = 0f, val y: Float = 0f, val z: Float = 0f,
            val qx: Float, val qy: Float, val qz: Float, val qw: Float,
            val is6DoF: Boolean = false
        ) : PerceptionEvent()
        data class FpsUpdated(val fps: Double) : PerceptionEvent()
        data class PoseDetected(val keypoints: List<PoseKeypoint>) : PerceptionEvent()

        // Action classification from skeleton sequence (ST-GCN or rule-based)
        data class ActionClassified(
            val action: String,         // e.g. "SITTING", "STANDING", "WALKING", "REACHING"
            val confidence: Float,      // 0.0-1.0
            val previousAction: String? = null,
            val timestamp: Long = System.currentTimeMillis()
        ) : PerceptionEvent()

        // Visual Embedding Event for Image-based lifelog (no bitmap — only embedding data)
        data class VisualEmbedding(
            val embedding: ByteArray,
            val label: String,
            val timestamp: Long,
            val latitude: Double?,
            val longitude: Double?
        ) : PerceptionEvent()

        // Audio Environment Event for YAMNet-based ambient sound classification
        data class AudioEnvironment(
            val events: List<Pair<String, Float>>,  // [(label, score), ...]
            val embedding: ByteArray,                // 1024d YAMNet embedding
            val timestamp: Long,
            val latitude: Double?,
            val longitude: Double?
        ) : PerceptionEvent()

        // Face detection + person identification events
        data class FacesDetected(
            val faces: List<FaceInfo>,
            val timestamp: Long
        ) : PerceptionEvent()

        data class PersonIdentified(
            val personId: Long,
            val personName: String?,
            val confidence: Float,
            val faceEmbedding: ByteArray,
            val timestamp: Long,
            val latitude: Double?,
            val longitude: Double?
        ) : PerceptionEvent()

        // Running dynamics computed from phone accelerometer + step detector
        data class RunningDynamics(
            val cadence: Float,              // steps per minute
            val verticalOscillation: Float,  // cm, peak-to-peak vertical bounce
            val groundContactTime: Float,    // milliseconds
            val groundReactionForce: Float,  // in g-force units
            val timestamp: Long
        ) : PerceptionEvent()

        // Head stability metrics from HeadPoseUpdated quaternion variance
        data class HeadStability(
            val pitchVariance: Float,     // degrees, lower = more stable
            val yawVariance: Float,       // degrees, lateral sway
            val lateralBalance: Float,    // -1.0 (left lean) to 1.0 (right lean), 0 = balanced
            val stabilityScore: Float,    // 0-100 composite score
            val timestamp: Long
        ) : PerceptionEvent()

        // Breathing rate from audio analysis
        data class BreathingMetrics(
            val breathsPerMinute: Float,
            val isRegular: Boolean,       // regular vs irregular pattern
            val confidence: Float,        // detection confidence 0-1
            val timestamp: Long
        ) : PerceptionEvent()

        // Watch biometric data (from Galaxy Watch via Wear OS Data Layer)
        data class WatchHeartRate(
            val bpm: Float,
            val timestamp: Long
        ) : PerceptionEvent()

        data class WatchHrv(
            val rmssd: Float,
            val sdnn: Float,
            val meanRR: Float,
            val timestamp: Long
        ) : PerceptionEvent()

        data class WatchGps(
            val latitude: Double,
            val longitude: Double,
            val altitude: Double,
            val accuracy: Float,
            val speed: Float,
            val timestamp: Long
        ) : PerceptionEvent()

        data class WatchSkinTemperature(
            val temperature: Float,
            val ambientTemperature: Float = 0f,
            val timestamp: Long
        ) : PerceptionEvent()

        data class WatchSpO2(
            val spo2: Int,
            val timestamp: Long
        ) : PerceptionEvent()

        data class WatchAccelerometer(
            val x: Float,
            val y: Float,
            val z: Float,
            val timestamp: Long
        ) : PerceptionEvent()

        // Running route data from GPS accumulation
        data class RunningRouteUpdate(
            val distanceMeters: Float,
            val paceMinPerKm: Float,      // minutes per kilometer
            val currentSpeedMps: Float,   // m/s instantaneous
            val elevationGainMeters: Float,
            val elapsedSeconds: Long,
            val latitude: Double,
            val longitude: Double,
            val timestamp: Long,
            val positionMode: PositionMode = PositionMode.GPS_GOOD,
            val floorDelta: Int = 0
        ) : PerceptionEvent()

        // PDR step update from HardwareManager (local ENU meters, not lat/lon)
        data class PdrStepUpdate(
            val pdrX: Double,       // cumulative East meters from origin
            val pdrY: Double,       // cumulative North meters from origin
            val timestamp: Long
        ) : PerceptionEvent()

        // Phone GPS published from LocationManager for position fusion
        data class PhoneGps(
            val latitude: Double,
            val longitude: Double,
            val altitude: Double,
            val accuracy: Float,
            val speed: Float,
            val timestamp: Long
        ) : PerceptionEvent()

        // Fused position from GPS+PDR Kalman filter
        data class FusedPositionUpdate(
            val latitude: Double,
            val longitude: Double,
            val altitude: Double,
            val speed: Float,
            val heading: Float,     // radians
            val accuracy: Float,    // estimated fused accuracy (meters)
            val mode: PositionMode,
            val floorDelta: Int,    // cumulative floor changes since session start
            val timestamp: Long
        ) : PerceptionEvent()

        // Barometric floor change detection
        data class FloorChange(
            val deltaFloors: Int,
            val direction: FloorDirection,
            val pressureHpa: Float,
            val cumulativeFloors: Int,
            val timestamp: Long
        ) : PerceptionEvent()

        // Spatial anchor lifecycle events (created/updated/removed)
        data class SpatialAnchorEvent(
            val anchorId: String,
            val action: String,  // "CREATED", "UPDATED", "REMOVED"
            val label: String,
            val worldX: Float, val worldY: Float, val worldZ: Float,
            val timestamp: Long
        ) : PerceptionEvent()

        // Hand tracking events (MediaPipe Hands 21-landmark)
        data class HandsDetected(
            val hands: List<HandData>,
            val timestamp: Long
        ) : PerceptionEvent()

        data class HandGestureDetected(
            val gestures: List<GestureEvent>,
            val timestamp: Long
        ) : PerceptionEvent()

        /** 시선 포커스 앵커 변경 — 화면 중앙에 가장 가까운 앵커가 바뀔 때 발행 */
        data class FocusedAnchorChanged(
            val anchorId: String?,          // 새 포커스 앵커 ID (null = 포커스 해제)
            val previousAnchorId: String?,  // 이전 포커스 앵커 ID
            val timestamp: Long
        ) : PerceptionEvent()

        /** 딥 포커스 — 앵커를 2초 이상 주시 시 발행 (AI 컨텍스트 제공 트리거) */
        data class DeepFocusTriggered(
            val anchorId: String,
            val dwellTimeMs: Long,
            val timestamp: Long
        ) : PerceptionEvent()

        /**
         * DriftCorrectionApplied — VIO 드리프트 보정 적용 이벤트.
         *
         * DriftCorrectionManager가 보정을 적용할 때 발행.
         * 디버그 HUD, 로깅, Strategist AI가 소비.
         *
         * @param source 보정 소스 ("BAROMETER", "MAGNETOMETER", "LOOP_CLOSURE")
         * @param correctionX/Y/Z 이번 보정량 (미터)
         * @param correctionYawDeg 이번 yaw 보정량 (도)
         * @param totalOffsetX/Y/Z 누적 오프셋 (미터)
         * @param totalYawCorrectionDeg 누적 yaw 보정 (도)
         */
        data class DriftCorrectionApplied(
            val source: String,
            val correctionX: Float,
            val correctionY: Float,
            val correctionZ: Float,
            val correctionYawDeg: Float,
            val totalOffsetX: Float,
            val totalOffsetY: Float,
            val totalOffsetZ: Float,
            val totalYawCorrectionDeg: Float,
            val timestamp: Long
        ) : PerceptionEvent()
    }

    /**
     * SystemEvent — 시스템 상태 업데이트.
     * 배터리, 온도, 네트워크, 러닝 세션 상태, 유저 상태 전이, 미션 상태 변경.
     * RunningSessionState는 UserStateTracker와 MissionDetectorRouter가 소비.
     */
    sealed class SystemEvent : XRealEvent() {
        data class BatteryLevel(val percent: Int) : SystemEvent()
        data class ThermalState(val isOverheated: Boolean) : SystemEvent()
        data class NetworkStatus(val isConnected: Boolean) : SystemEvent()
        data class DebugLog(val message: String) : SystemEvent()
        data class VoiceActivity(val isSpeaking: Boolean) : SystemEvent()
        data class VisionStateChanged(val isFrozen: Boolean) : SystemEvent()
        data class Error(
            val code: String,
            val message: String,
            val throwable: Throwable? = null,
            val severity: ErrorSeverity = ErrorSeverity.WARNING  // 기본값 WARNING → 기존 호출부 하위호환
        ) : SystemEvent()
        data class RunningSessionState(
            val state: RunningState,
            val elapsedSeconds: Long,
            val totalDistanceMeters: Float
        ) : SystemEvent()
        data class UserStateChanged(
            val oldState: String,
            val newState: String,
            val timestamp: Long = System.currentTimeMillis()
        ) : SystemEvent()
        data class MissionStateChanged(
            val missionId: String,
            val missionType: String,
            val oldState: String,
            val newState: String,
            val timestamp: Long = System.currentTimeMillis()
        ) : SystemEvent()

        /** Emitted by SituationRecognizer when life situation changes (Phase 1) */
        data class SituationChanged(
            val oldSituation: com.xreal.nativear.context.LifeSituation,
            val newSituation: com.xreal.nativear.context.LifeSituation,
            val confidence: Float,
            val timestamp: Long = System.currentTimeMillis()
        ) : SystemEvent()

        /**
         * SituationMasteryChanged — SituationLifecycleManager가 숙련도 변화 시 발행 (Phase F-1).
         *
         * ## 주요 구독자
         * - F-3 AgentWarmupScheduler: ROUTINE/MASTERED 승급 → 워밍업 자동 예약
         * - F-6 RoutineClassifier: MASTERED 승급 → TFLite 학습 데이터 수집 시작
         * - StrategistService: 숙련도 전환 → 장기 전략 업데이트
         *
         * [oldLevel] / [newLevel]: MasteryLevel.name (UNKNOWN/LEARNING/ROUTINE/MASTERED)
         * [processingRing]: ProcessingRing.name (MISSION_TEAM/API_SINGLE/WARMUP_CACHE/LOCAL_ML)
         */
        data class SituationMasteryChanged(
            val situation: com.xreal.nativear.context.LifeSituation,
            val oldLevel: String,           // MasteryLevel.name
            val newLevel: String,           // MasteryLevel.name
            val totalObservations: Int,
            val processingRing: String,     // ProcessingRing.name — 변경 후 적용될 처리 링
            val timestamp: Long = System.currentTimeMillis()
        ) : SystemEvent()

        /** Emitted by PlanManager when a todo is completed */
        data class TodoCompleted(
            val todoId: String,
            val todoTitle: String,
            val parentGoalId: String?,
            val category: String?,
            val timestamp: Long = System.currentTimeMillis()
        ) : SystemEvent()

        /**
         * DeviceModeChanged — DeviceModeManager가 운영 모드를 전환할 때 발행.
         * HardwareManager, VisionManager, AudioAnalysisService 등이 소비하여
         * 모드에 맞는 파이프라인을 활성화/비활성화.
         */
        data class DeviceModeChanged(
            val previousMode: DeviceMode,
            val newMode: DeviceMode,
            val reason: String,          // 전환 이유 (예: "CPU_OVERLOAD", "GLASSES_REMOVED", "DIRECTIVE")
            val timestamp: Long = System.currentTimeMillis()
        ) : SystemEvent()

        /**
         * ResourceAlert — ResourceMonitor가 임계값 초과 시 발행.
         * DeviceModeManager가 소비하여 자동 모드 다운그레이드 결정.
         * StrategistService가 소비하여 케이던스 절감 지시사항 생성.
         */
        data class ResourceAlert(
            val cpuPercent: Int,         // 0-100 CPU 사용률
            val ramUsedMb: Int,          // 사용 중인 RAM (MB)
            val ramTotalMb: Int,         // 전체 사용 가능 RAM (MB)
            val batteryTempC: Float,     // 배터리 온도 (섭씨)
            val severity: ResourceSeverity,
            val timestamp: Long = System.currentTimeMillis()
        ) : SystemEvent()

        /**
         * WatchAudioChunk — Galaxy Watch에서 마이크 오디오 청크 수신.
         * WearAudioReceiver → AudioAnalysisService로 라우팅.
         */
        data class WatchAudioChunk(
            val pcmData: ByteArray,      // 16kHz mono PCM, 500ms 청크
            val timestamp: Long
        ) : SystemEvent()

        /**
         * NetworkStateChanged — ConnectivityMonitor가 네트워크 상태 변경 시 발행.
         * EdgeDelegationRouter가 소비하여 오프라인 시 엣지 AI로 자동 전환.
         * RESEARCH.md §12 GlobalEventBus 참조.
         */
        data class NetworkStateChanged(
            val isOnline: Boolean,
            val networkType: String,     // "WIFI", "MOBILE", "NONE"
            val timestamp: Long = System.currentTimeMillis()
        ) : SystemEvent()

        /**
         * EdgeModelStateChanged — EdgeModelManager가 엣지 모델 상태 변경 시 발행.
         * HUD, AppBootstrapper 등이 소비하여 "모델 준비 중..." 표시.
         * RESEARCH.md §2 LiteRT-LM 참조.
         */
        data class EdgeModelStateChanged(
            val tier: String,            // "ROUTER_270M", "AGENT_1B", "EMERGENCY_E2B"
            val state: String,           // "LOADING", "READY", "UNLOADED", "DOWNLOADING", "FAILED"
            val progressPercent: Int = 0, // 다운로드 진행률 (DOWNLOADING 상태 시)
            val timestamp: Long = System.currentTimeMillis()
        ) : SystemEvent()

        /**
         * FocusModeChanged — FocusModeManager가 집중/개인 모드 전환 시 발행.
         * VisionCoordinator, AIAgentManager, CoachEngine, MissionConductor 등이 소비.
         */
        data class FocusModeChanged(
            val mode: FocusMode,
            val reason: String,          // "palm_face_gesture", "voice_command", "situation", "auto"
            val timestamp: Long = System.currentTimeMillis()
        ) : SystemEvent()

        /**
         * DeviceHealthUpdated — DeviceHealthMonitor가 30초 주기로 하드웨어 상태 발행.
         * FailsafeController가 소비하여 CapabilityTier를 결정.
         */
        data class DeviceHealthUpdated(
            val glassesConnected: Boolean,
            val glassesFrameRateFps: Float,
            val watchConnected: Boolean,
            val watchHrValid: Boolean,
            val networkOnline: Boolean,
            val networkType: String,         // "WIFI"/"MOBILE"/"NONE"
            val batteryPercent: Int,
            val isCharging: Boolean,
            val thermalStatus: Int,          // ThermalStatus 0=NONE, 3=SEVERE, 5=SHUTDOWN
            val edgeLlmReady: Boolean,
            val fold3Connected: Boolean,
            val fold3RamAvailMb: Int,
            val timestamp: Long = System.currentTimeMillis()
        ) : SystemEvent()

        /**
         * CapabilityTierChanged — FailsafeController가 능력 티어 변경 시 발행.
         * 모든 컴포넌트가 소비하여 자신의 동작 수준 조정.
         */
        data class CapabilityTierChanged(
            val tier: CapabilityTier,
            val previousTier: CapabilityTier,
            val reason: String,
            val timestamp: Long = System.currentTimeMillis()
        ) : SystemEvent()

        /**
         * ResourceActivated — ResourceRegistry가 리소스 상태 변경 시 발행.
         * VisionManager, OutputCoordinator 등이 소비하여 활성 소스 전환.
         */
        data class ResourceActivated(
            val resourceType: String,    // ResourceType.name()
            val displayName: String,
            val activatedBy: String,     // "ai_request"/"failsafe"/"user"
            val isActive: Boolean,
            val timestamp: Long = System.currentTimeMillis()
        ) : SystemEvent()

        /**
         * SessionChanged — LifeSessionManager가 세션 시작/종료 시 발행.
         * PersonaManager, MemorySaveHelper 등이 소비하여 session_id 자동 주입.
         */
        data class SessionChanged(
            val sessionId: String,
            val started: Boolean,        // true = 시작, false = 종료
            val situation: String? = null,
            val timestamp: Long = System.currentTimeMillis()
        ) : SystemEvent()

        /**
         * HarmonyDecision — SystemConductor가 30초 루프마다 발행하는 최종 시스템 결정.
         *
         * 5개 분석 섹션(FailsafeController/DeviceModeManager/OperationalDirector/
         * EmergencyOrchestrator/StrategistService)의 제안을 충돌 해결 후 단일 권위 결정으로 발행.
         *
         * ## 구독 권장
         * - 새 서비스: CapabilityTierChanged 대신 이 이벤트를 구독 (충돌 해결됨)
         * - 기존 서비스: 기존 CapabilityTierChanged/DeviceModeChanged 구독 유지 가능 (하위 호환)
         *
         * ## goalTierHint
         * OperationalDirector의 상황 목표 등급. 실제 tier != goalTierHint일 경우
         * StrategistService가 적응 전략 생성 가능.
         */
        data class HarmonyDecision(
            val tier: CapabilityTier,
            val mode: DeviceMode,
            val winningSection: String,      // SystemHarmony.SystemSection.name
            val winningReason: String,
            val goalTierHint: String? = null, // OperationalDirector의 상황 목표 등급 (StrategistService 참고용)
            val overriddenSections: List<String> = emptyList(),
            val timestamp: Long = System.currentTimeMillis()
        ) : SystemEvent()

        /**
         * OutcomeRecorded — Gap I: OutcomeTracker가 결과 기록 즉시 발행.
         * PersonaManager, AgentPersonalityEvolution 등이 실시간 피드백으로 활용.
         * 5분 반성 주기를 기다리지 않고 즉시 전략 조정 가능.
         */
        data class OutcomeRecorded(
            val interventionId: String,
            val expertId: String,
            val outcome: String,          // "FOLLOWED", "DISMISSED", "IGNORED"
            val situation: String,
            val action: String,
            val timestamp: Long = System.currentTimeMillis()
        ) : SystemEvent()
    }

    /**
     * ActionRequest — 시스템 출력 요청.
     * OutputCoordinator가 구독하여 TTS, HUD 그리기, 메모리 저장 등을 실행.
     * DrawingCommand는 OverlayView 위에 AR HUD 요소를 렌더링.
     */
    sealed class ActionRequest : XRealEvent() {
        /**
         * TTS 음성 출력 요청.
         * @param important true이면 quietMode에서도 항상 출력 (사용자 직접 명령 응답, 긴급 알림)
         *                  false이면 quietMode 활성화 시 무음 처리 (상태 알림, 시스템 자동 메시지)
         */
        data class SpeakTTS(val text: String, val important: Boolean = false) : ActionRequest()
        data class ShowMessage(val text: String) : ActionRequest()
        data class UpdateOverlay(val blocks: List<OcrResult>) : ActionRequest()
        data class SaveMemory(val content: String, val role: String, val metadata: String? = null) : ActionRequest()
        object TriggerSnapshot : ActionRequest()
        data class DrawingCommand(val command: DrawCommand) : ActionRequest()

        /** SpatialAnchorManager → OverlayView: 화면에 투영된 앵커 라벨 업데이트 */
        data class AnchorLabelsUpdate(
            val visibleLabels: List<AnchorLabel2D>
        ) : ActionRequest()

        /** RemoteCameraToolExecutor → OutputCoordinator: 원격 카메라 PIP 토글 */
        data class ShowRemoteCamera(
            val show: Boolean,
            val source: String? = null  // Optional server URL override
        ) : ActionRequest()
    }
}

enum class GestureType {
    TAP, NOD, SHAKE, TILT, DOUBLE_TAP, TRIPLE_TAP, QUAD_TAP
}

enum class RunningState { ACTIVE, PAUSED, STOPPED }
enum class RunningCommandType { START, STOP, PAUSE, RESUME, LAP, STATUS }
enum class PositionMode { GPS_GOOD, GPS_DEGRADED, PDR_ONLY, GPS_RECOVERED }
enum class FloorDirection { UP, DOWN }
enum class FeedbackSentiment { POSITIVE, NEGATIVE, NEUTRAL }

/**
 * DeviceMode — 시스템 운영 모드.
 * CPU/GPU 부하와 사용 환경에 따라 DeviceModeManager가 전환.
 *
 * FULL_AR    : AR 안경 + SLAM + VIO + 비전 + AI — 최대 기능, 최고 부하
 * HUD_ONLY   : AR 안경 HUD + 폰 후면카메라 — SLAM 비활성, GPU 절약
 * PHONE_CAM  : 안경 없음 + 폰 카메라 + 이어폰 — 교실 거치 사용
 * AUDIO_ONLY : 카메라 없음 + 마이크만 — 이동 중, 쉬는 시간
 */
enum class DeviceMode(val label: String) {
    FULL_AR("AR 안경 풀가동"),
    HUD_ONLY("HUD + 폰 카메라"),
    PHONE_CAM("폰 카메라만"),
    AUDIO_ONLY("오디오 전용")
}

/**
 * ResourceSeverity — ResourceAlert 심각도.
 * NORMAL  : 정상 범위 (CPU<70%, 배터리온도<42C)
 * WARNING : 주의 (CPU 70-85% 또는 온도 42-46C) → 케이던스 절감 제안
 * CRITICAL: 위험 (CPU>85% 또는 온도>46C) → 자동 모드 다운그레이드
 */
enum class ResourceSeverity { NORMAL, WARNING, CRITICAL }

/**
 * ErrorSeverity — ErrorReporter 심각도 (Phase G).
 * CRITICAL : 즉시 AI 트리아지 → EmergencyOrchestrator rerouteRules 적용
 * WARNING  : 60초 dedup + 분당 10건 제한 → EmergencyOrchestrator 카운터 집계만
 * INFO     : logcat만 — EventBus 발행 없음
 */
enum class ErrorSeverity { CRITICAL, WARNING, INFO }

/**
 * FocusMode — 사용자 집중/개인 모드.
 * FocusModeManager가 관리. VoiceCommand, PalmFaceGesture로 전환.
 *
 * NORMAL    : 기본 — 모든 AI 프로액티브 동작
 * DND       : 방해 금지 — 사용자 명령 응답만, 능동적 AI 억제
 * PRIVATE   : 프라이버시 — 안전 알림만 허용 (화장실, 수면)
 * EMERGENCY : 비상 — 최소 생명 안전 기능만
 */
enum class FocusMode { NORMAL, DND, PRIVATE, EMERGENCY }

/**
 * CapabilityTier — 시스템 능력 단계.
 * FailsafeController가 DeviceHealthReport를 평가하여 결정.
 * ordinal 순서대로 기능 제한 심화.
 */
enum class CapabilityTier(val description: String) {
    TIER_0_FULL("전체 기능 — 모든 AI, HUD, 비전, Watch"),
    TIER_1_NO_NETWORK("엣지 AI만 — 네트워크 없음"),
    TIER_2_NO_GLASSES("폰 화면 HUD — 안경 없음, AI 정상"),
    TIER_3_NO_WATCH("Watch 데이터 없음 — 생체 신호 제한"),
    TIER_4_LOW_POWER("절전 모드 — 비전 OFF, 30s AI 간격"),
    TIER_5_EDGE_ONLY("엣지 LLM 전용 — 서버 AI 불가"),
    TIER_6_MINIMAL("최소 동작 보장 — 오디오 + TTS + HUD 상태만")
}
