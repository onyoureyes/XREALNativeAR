package com.xreal.nativear.core

import android.content.Context
import android.util.Log
import com.xreal.nativear.*
import com.xreal.ai.UnifiedAIOrchestrator
import kotlinx.coroutines.*

/**
 * AppBootstrapper: Handles the lifecycle of the application services.
 * Replaces the bootstrapping logic of CoreEngine.
 */
class AppBootstrapper(
    private val context: Context,
    private val scope: CoroutineScope,
    private val hardwareManager: HardwareManager,
    private val voiceManager: VoiceManager,
    private val visionManager: VisionManager,
    private val locationManager: LocationManager,
    private val aiOrchestrator: UnifiedAIOrchestrator,
    private val imageEmbedder: ImageEmbedder,
    private val eventBus: GlobalEventBus,
    private val gestureManager: GestureManager,
    private val interactionTracker: InteractionTracker,
    private val speakerDiarizer: SpeakerDiarizer,
    private val personSyncManager: PersonSyncManager,
    private val strategistService: com.xreal.nativear.strategist.StrategistService
) {
    private val TAG = "AppBootstrapper"
    private var modelCleanupJob: kotlinx.coroutines.Job? = null
    private var personSyncJob: kotlinx.coroutines.Job? = null

    /**
     * Boot Level 시스템 — PolicyReader("system.boot_level") 기반 단계별 초기화.
     *
     * Level 1: DB + Policy (필수 인프라)
     * Level 2: HUD + TTS (사용자 피드백)
     * Level 3: 센서 + 하드웨어 + 자율행동 서비스 (입력/전략/미션)
     * Level 4: AI 모델 + 컨텍스트 (지능) + 학습/동기화
     *
     * 테스트 시 boot_level=1로 설정하면 DB+Policy만 초기화 → 레이어별 검증 가능.
     */
    private val bootLevel: Int get() =
        com.xreal.nativear.policy.PolicyReader.getInt("system.boot_level", 5)

    fun start() {
        // 0-logger. XRealLogger 프로덕션 구현 설정 (android.util.Log 위임)
        XRealLogger.impl = AndroidLogger

        val currentBootLevel = bootLevel
        val bootStartMs = System.currentTimeMillis()
        Log.i(TAG, "🚀 AppBootstrapper: Starting (boot_level=$currentBootLevel)")

        // ═══════════════════════════════════════════════════════
        // LEVEL 1: DB + Policy (필수 인프라) — 항상 실행
        // ═══════════════════════════════════════════════════════

        // 0. 크래시 핸들러 설치 (Koin 초기화 완료 후 → 인스턴스 정상 조회 보장)
        org.koin.java.KoinJavaComponent.getKoin().get<ExecutionFlowMonitor>().installCrashHandler()

        // 0-1. ErrorReporter — Koin에서 관리 (init은 no-op, 호환성용)
        ErrorReporter.init(eventBus)

        // 0-policy. PolicyManager — 정책 변경 요청 심사 + 음성 명령 구독
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.policy.PolicyManager>()?.start()
            val policyCount = org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.policy.PolicyRegistry>()?.listAll()?.size ?: 0
            Log.i(TAG, "PolicyManager started ($policyCount policies registered)")
        } catch (e: Exception) {
            Log.w(TAG, "PolicyManager not available: ${e.message}")
        }

        // 0b. ★ Phase H: 훈련 준비 타이머 기록
        try {
            val checker = org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.learning.TrainingReadinessChecker>()
            checker?.recordFirstLaunchIfNeeded()
        } catch (e: Exception) {
            Log.w(TAG, "TrainingReadinessChecker first_launch 기록 실패: ${e.message}")
        }

        // 6a. SystemErrorLogger — 에러 추적 파이프라인
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.monitoring.SystemErrorLogger>()?.start()
            Log.i(TAG, "SystemErrorLogger started")
        } catch (e: Exception) {
            Log.w(TAG, "SystemErrorLogger not available: ${e.message}")
        }

        // DB 무결성 체크 (비동기 — 부팅 차단 없음)
        scope.launch(Dispatchers.IO) {
            try {
                val helper = org.koin.java.KoinJavaComponent.getKoin()
                    .getOrNull<com.xreal.nativear.batch.DatabaseIntegrityHelper>()
                if (helper != null) {
                    val report = helper.runIntegrityCheck()
                    if (report.totalIssues > 0) {
                        Log.w(TAG, "DB 무결성 이슈 ${report.totalIssues}건 발견 — cleanupOrphans 실행")
                        helper.cleanupOrphans()
                    } else {
                        Log.i(TAG, "DB 무결성 체크 통과 (이슈 없음)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "DB 무결성 체크 실패 (비치명적): ${e.message}")
            }
        }

        Log.i(TAG, "✅ Level 1 complete (DB+Policy)")
        if (currentBootLevel < 2) { Log.i(TAG, "⏸ Boot stopped at level 1"); return }

        // ═══════════════════════════════════════════════════════
        // LEVEL 2: HUD + TTS (사용자 피드백)
        // ═══════════════════════════════════════════════════════

        // 0a. ★ DI 순환 의존성 지연 해결 — HUDTemplateEngine에 DebugHUD lazy 등록
        // (HUDTemplateEngine→DebugHUD→ExpertTeamManager→MissionConductor→MissionAgentRunner→MultiAIOrchestrator→ToolExecutorRegistry→HUDTemplateEngine 순환 방지)
        try {
            val koin = org.koin.java.KoinJavaComponent.getKoin()
            val hudEngine = koin.getOrNull<com.xreal.nativear.hud.HUDTemplateEngine>()
            val debugHud = koin.getOrNull<com.xreal.nativear.hud.DebugHUD>()
            if (hudEngine != null && debugHud != null) {
                hudEngine.registerRenderer(debugHud)
                Log.i(TAG, "✅ DebugHUD registered with HUDTemplateEngine (deferred)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "DebugHUD 등록 실패 (비치명적): ${e.message}")
        }

        // 0a-2. RunningCoachHUD + SpeedGraphOverlay → HUDTemplateEngine 등록
        try {
            val koin = org.koin.java.KoinJavaComponent.getKoin()
            val hudEngine = koin.getOrNull<com.xreal.nativear.hud.HUDTemplateEngine>()
            val runningCoachMgr = koin.getOrNull<com.xreal.nativear.running.RunningCoachManager>()
            if (hudEngine != null && runningCoachMgr != null) {
                hudEngine.registerRenderer(runningCoachMgr.hud)
                hudEngine.registerRenderer(runningCoachMgr.speedGraphOverlay)
                Log.i(TAG, "✅ RunningCoachHUD + SpeedGraphOverlay registered with HUDTemplateEngine")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Running HUD 등록 실패 (비치명적): ${e.message}")
        }

        Log.i(TAG, "✅ Level 2 complete (HUD+TTS)")
        if (currentBootLevel < 3) { Log.i(TAG, "⏸ Boot stopped at level 2"); return }

        // ═══════════════════════════════════════════════════════
        // LEVEL 3: 센서 + 하드웨어 (입력)
        // ═══════════════════════════════════════════════════════

        // 1. Model Initialization (AI Model Warehouse Phase)
        scope.launch(Dispatchers.IO) {
            Log.i(TAG, "📦 AI Model Warehouse: Preparing critical models...")
            val criticalModels = listOf("OCR", "SystemTTS", "LiteRT_YOLO", "ImageEmbedder", "Pose", "YAMNet", "BlazeFace", "FaceEmbedder", "FER")
            val success = aiOrchestrator.ensureModelsReady(criticalModels)

            if (success) {
                Log.i(TAG, "✅ All critical AI models are ready.")
            } else {
                Log.e(TAG, "⚠️ Some AI models failed to initialize.")
            }
        }

        // 2. Hardware and Sensors
        try {
            hardwareManager.startHardware()
        } catch (e: Exception) {
            Log.e(TAG, "hardwareManager.startHardware() 실패 (글래스 미연결?): ${e.message}", e)
            eventBus.publish(XRealEvent.SystemEvent.Error(
                code = "HARDWARE_START_ERROR",
                message = "하드웨어 초기화 실패: ${e.message?.take(100)}",
                throwable = e
            ))
        }

        // 3. GestureManager: Already initialized via constructor (subscribes to HeadPoseUpdated)
        Log.i(TAG, "🤚 GestureManager active — head gesture detection enabled")

        // 4. Location tracking
        try {
            locationManager.startLocationUpdates {
                scope.launch {
                    eventBus.publish(XRealEvent.ActionRequest.TriggerSnapshot)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "locationManager.startLocationUpdates() 실패 (GPS 권한?): ${e.message}", e)
            eventBus.publish(XRealEvent.SystemEvent.Error(
                code = "LOCATION_START_ERROR",
                message = "위치 추적 시작 실패: ${e.message?.take(100)}",
                throwable = e
            ))
        }

        // 5. Periodic model cleanup (every 5 minutes)
        modelCleanupJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(5 * 60 * 1000L)
                Log.d(TAG, "🧹 Running periodic model cleanup...")
                aiOrchestrator.releaseUnusedModels()
            }
        }

        // 6. Periodic person profile sync (every 30 minutes)
        personSyncJob = scope.launch(Dispatchers.IO) {
            delay(60 * 1000L) // Initial delay 1 minute
            while (isActive) {
                try {
                    val result = personSyncManager.syncPersonProfiles()
                    Log.d(TAG, "Person sync: ${result.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Person sync failed: ${e.message}")
                }
                delay(30 * 60 * 1000L)
            }
        }

        // (SystemErrorLogger는 Level 1에서 이미 시작)

        // 6d. ★ SystemConductor — 시스템 하모니 지휘자 (Phase E: 5개 모니터링 섹션 통합 조율)
        // OperationalDirector 30s 루프를 흡수, HarmonyDecision 발행, AI get_system_health 도구 지원
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.resilience.SystemConductor>()?.start()
            Log.i(TAG, "SystemConductor started (지휘자 활성 — 5개 분석 섹션 조율 시작)")
        } catch (e: Exception) {
            Log.w(TAG, "SystemConductor not available: ${e.message}")
        }

        // 6e. ★ SituationLifecycleManager — 상황 숙련도 사다리 (Phase F-1)
        // SituationChanged → 승/강급 추적 → ProcessingRing 결정 (MISSION_TEAM/API_SINGLE/WARMUP_CACHE/LOCAL_ML)
        // F-2(예측기), F-3(워밍업), F-6(ML학습) 연결점 — DB 영속: structured_data(situation_lifecycle)
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.companion.SituationLifecycleManager>()?.start()
            Log.i(TAG, "SituationLifecycleManager started (숙련도 사다리 활성 — 24개 상황 추적)")
        } catch (e: Exception) {
            Log.w(TAG, "SituationLifecycleManager not available: ${e.message}")
        }

        // 6f. ★ SituationPredictor — 24시간 상황 예측기 (Phase F-2)
        // SituationChanged → 요일+시간별 관찰 빈도 누적 → structured_data(situation_observations)
        // WorkManager(SituationPredictionWorker) 24h 주기 등록 → 매일 01:00 예측 생성
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.companion.SituationPredictor>()?.start()
            com.xreal.nativear.batch.SituationPredictionWorker.schedule(context)
            Log.i(TAG, "SituationPredictor started (24h 예측기 활성 + WorkManager 등록)")
        } catch (e: Exception) {
            Log.w(TAG, "SituationPredictor not available: ${e.message}")
        }

        // 6g. ★ AgentWarmupScheduler — 예측 기반 에이전트 워밍업 예약자 (Phase F-3)
        // SituationPredictor 예측 → 상황별 담당 에이전트 조회 → AgentWarmupWorker WorkManager 예약
        // SituationMasteryChanged(ROUTINE/MASTERED 진입) → 즉시 워밍업 활성화
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.companion.AgentWarmupScheduler>()?.start()
            Log.i(TAG, "AgentWarmupScheduler started (예측 기반 워밍업 예약 활성 — 5개 에이전트)")
        } catch (e: Exception) {
            Log.w(TAG, "AgentWarmupScheduler not available: ${e.message}")
        }

        // 6h. ★ KnowledgePrefetchWorker — 에이전트 도메인 지식 선제 적재 WorkManager 등록 (Phase F-4)
        // KnowledgePrefetcher 싱글톤은 Koin 자동 생성, WorkManager만 여기서 등록
        // knowledgeRefreshIntervalDays 주기로 각 에이전트 배경 지식 갱신 (네트워크 필요)
        try {
            com.xreal.nativear.batch.KnowledgePrefetchWorker.schedule(context)
            Log.i(TAG, "KnowledgePrefetchWorker scheduled (24h 주기, 네트워크 필요 도메인 지식 갱신)")
        } catch (e: Exception) {
            Log.w(TAG, "KnowledgePrefetchWorker scheduling failed: ${e.message}")
        }

        // 6b. Batch Processor (Fix 1: token optimization via task aggregation)
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.batch.BatchProcessor>()?.start()
            Log.i(TAG, "Batch processor started (dedup + translation cache + compression throttle)")
        } catch (e: Exception) {
            Log.w(TAG, "Batch processor not available: ${e.message}")
        }

        // 6c. LifeSessionManager — 생활 세션 생명주기 관리 (★ Phase C)
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.session.LifeSessionManager>()?.start()
            Log.i(TAG, "LifeSessionManager started (30분 비활성 자동 세션 관리)")
        } catch (e: Exception) {
            Log.w(TAG, "LifeSessionManager not available: ${e.message}")
        }

        // 6d. ★ Phase H: UserDNA 초기화 (첫 실행 시 기본값 저장)
        try {
            org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.profile.UserDNAManager>()?.loadDNA()  // 없으면 기본값 저장
            Log.i(TAG, "UserDNAManager initialized (사용자 성향 프로필 로드)")
        } catch (e: Exception) {
            Log.w(TAG, "UserDNAManager not available: ${e.message}")
        }

        // 6e. ★ Phase H: FeedbackSessionManager 시작 (음성 트리거 구독)
        try {
            org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.session.FeedbackSessionManager>()?.start()
            Log.i(TAG, "FeedbackSessionManager started (음성 피드백 세션 대기)")
        } catch (e: Exception) {
            Log.w(TAG, "FeedbackSessionManager not available: ${e.message}")
        }

        // 6f. ★ Phase H: 아침 가치 리포트 + WorkManager 22:00 예약
        try {
            org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.monitoring.DailyValueReporter>()?.publishMorningBriefing()
            Log.i(TAG, "DailyValueReporter morning briefing published")
        } catch (e: Exception) {
            Log.w(TAG, "DailyValueReporter not available: ${e.message}")
        }
        try {
            com.xreal.nativear.batch.DailyValueReportWorker.schedule(context)
            Log.i(TAG, "DailyValueReportWorker scheduled (매일 22:00 자동 실행)")
        } catch (e: Exception) {
            Log.w(TAG, "DailyValueReportWorker schedule 실패: ${e.message}")
        }

        // 6j. ★ Phase J: AIAgentManager VoiceFeedback 구독 (멀티-AI hit rate 피드백 루프)
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.AIAgentManager>()?.start()
            Log.i(TAG, "AIAgentManager started (VoiceFeedback → Multi-AI 피드백 루프 활성)")
        } catch (e: Exception) {
            Log.w(TAG, "AIAgentManager.start() 실패: ${e.message}")
        }

        // ★ ValueGatekeeper에 AIResourceRegistry 연결 (순환 의존성 방지 — setter 주입)
        try {
            val vg = org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.ai.ValueGatekeeper>()
            val registry = org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.ai.IAICallService>()
            if (vg != null && registry != null) {
                vg.setAIRegistry(registry)
            }
        } catch (e: Exception) {
            Log.w(TAG, "ValueGatekeeper AI 보강 연결 실패: ${e.message}")
        }

        // ── LEVEL 3 계속: 자율행동 서비스 (전략/미션/카메라/센서 릴레이) ──

        // ★ ProactiveScheduler 시작 (중앙 스케줄러)
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.ai.ProactiveScheduler>()?.start(scope)
            Log.i(TAG, "ProactiveScheduler started (중앙 자율행동 스케줄러)")
        } catch (e: Exception) {
            Log.w(TAG, "ProactiveScheduler not available: ${e.message}")
        }

        // 7. Strategist AI Meta-Observer (reflection every 5 min)
        try {
            strategistService.start()
            Log.i(TAG, "Strategist AI meta-observer started")
        } catch (e: Exception) {
            Log.e(TAG, "strategistService.start() 실패: ${e.message}", e)
            eventBus.publish(XRealEvent.SystemEvent.Error(
                code = "STRATEGIST_START_ERROR",
                message = "전략가 서비스 시작 실패: ${e.message?.take(100)}",
                throwable = e
            ))
        }

        // 7a. PolicyManager — LEVEL 1에서 이미 start() 완료 (중복 호출 제거됨)

        // 7b. ★ CameraStreamManager — 카메라 소스 선택 + 건강 모니터링 시작
        try {
            org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.camera.CameraStreamManager>()?.start()
            Log.i(TAG, "CameraStreamManager started (카메라 건강 모니터링 3초 주기)")
        } catch (e: Exception) {
            Log.w(TAG, "CameraStreamManager not available: ${e.message}")
        }

        // 8. Wear OS Data Receiver (Galaxy Watch sensor data)
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.wear.WearDataReceiver>()?.start()
            Log.i(TAG, "Wear OS data receiver started")
        } catch (e: Exception) {
            Log.w(TAG, "Wear OS data receiver not available: ${e.message}")
        }

        // 8a. Sensor Relay Receiver (Fold 3 SSE → 워치 센서 릴레이)
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.remote.SensorRelayReceiver>()?.start()
            Log.i(TAG, "Sensor relay receiver started (Fold 3 SSE)")
        } catch (e: Exception) {
            Log.w(TAG, "Sensor relay receiver not available: ${e.message}")
        }

        // 8b. Wear Audio Receiver (Galaxy Watch 마이크 — DeviceMode.AUDIO_ONLY 시 자동 활성화)
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.wear.WearAudioReceiver>()?.start()
            Log.i(TAG, "Wear audio receiver started (워치 마이크 대기 중)")
        } catch (e: Exception) {
            Log.w(TAG, "Wear audio receiver not available: ${e.message}")
        }

        // 8c. Resource Monitor (CPU/RAM/배터리 온도 — 30초 간격)
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.monitoring.ResourceMonitor>()?.start()
            Log.i(TAG, "Resource monitor started (CPU/RAM/온도 30초 간격 모니터링)")
        } catch (e: Exception) {
            Log.w(TAG, "Resource monitor not available: ${e.message}")
        }

        // 8d. Device Mode Manager (FULL_AR/HUD_ONLY/PHONE_CAM/AUDIO_ONLY 자동 전환)
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.monitoring.DeviceModeManager>()?.start()
            Log.i(TAG, "Device mode manager started (자원 기반 자동 모드 전환 활성)")
        } catch (e: Exception) {
            Log.w(TAG, "Device mode manager not available: ${e.message}")
        }

        // OperationalDirector → 삭제 (빈 껍데기, SystemConductor로 이전됨, 방안 B)

        // 8e. ConnectivityMonitor — 네트워크 상태 감지 (엣지 AI 전환 트리거)
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.edge.ConnectivityMonitor>()?.start()
            Log.i(TAG, "Connectivity monitor started (네트워크 상태 감지 — 엣지 AI 전환)")
        } catch (e: Exception) {
            Log.w(TAG, "Connectivity monitor not available: ${e.message}")
        }

        // 8f. EdgeModelManager — 엣지 LLM 모델 준비 (RESEARCH.md §2 LiteRT-LM v0.8.1)
        // alwaysLoaded 모델(270M, 1B) 백그라운드 로딩, E2B는 지연 로딩
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.edge.EdgeModelManager>()?.start()
            Log.i(TAG, "Edge model manager started (Gemma 270M + 1B 백그라운드 로딩)")
        } catch (e: Exception) {
            Log.w(TAG, "Edge model manager not available: ${e.message}")
        }

        // 8g. RemoteLLMPool — PC + 스팀덱 Remote LLM 어레이 헬스체크 (60초 주기)
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.ai.RemoteLLMPool>()?.startHealthCheck()
            Log.i(TAG, "Remote LLM Pool health check started (PC + SteamDeck, 60s 주기)")
        } catch (e: Exception) {
            Log.w(TAG, "Remote LLM Pool not available: ${e.message}")
        }

        // 9. Spatial Anchoring + Place Recognition System
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.spatial.PathTracker>()?.start()
            Log.i(TAG, "Path tracker started")
        } catch (e: Exception) {
            Log.w(TAG, "Path tracker not available: ${e.message}")
        }
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.spatial.PlaceRecognitionManager>()?.start()
            Log.i(TAG, "Place recognition manager started")
        } catch (e: Exception) {
            Log.w(TAG, "Place recognition manager not available: ${e.message}")
        }
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.spatial.SpatialAnchorManager>()?.start()
            Log.i(TAG, "Spatial anchor manager started")
        } catch (e: Exception) {
            Log.w(TAG, "Spatial anchor manager not available: ${e.message}")
        }

        // 9b~10. SpatialUIManager + HandInteractionManager → SituationAwareStarter로 이관
        // (HandsDetected 이벤트 시 lazy start)

        // 12. Proactive Memory Surfacing (DeepFocus + visual + temporal + voice triggers)
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.memory.ProactiveMemorySurfacer>()?.start()
            Log.i(TAG, "Proactive memory surfacer started")
        } catch (e: Exception) {
            Log.w(TAG, "Proactive memory surfacer not available: ${e.message}")
        }

        // 13. VIO Drift Correction System (기압계 Y + 자기 Yaw + 시각 루프 클로저 X,Z)
        try {
            val driftManager = org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.spatial.DriftCorrectionManager>()
            if (driftManager != null) {
                // Wire drift correction into HardwareManager
                hardwareManager.driftCorrectionManager = driftManager
                driftManager.start()
                Log.i(TAG, "VIO drift correction started (baro Y + mag yaw + visual loop XZ)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "VIO drift correction not available: ${e.message}")
        }

        Log.i(TAG, "✅ Level 3 complete (센서+하드웨어)")
        if (currentBootLevel < 4) { Log.i(TAG, "⏸ Boot stopped at level 3"); return }

        // ═══════════════════════════════════════════════════════
        // LEVEL 4: AI 모델 + 컨텍스트 (지능)
        // ═══════════════════════════════════════════════════════

        // 14. Context Engine + Situation Recognition (Phase 1: Expert Team AI)
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.context.ContextAggregator>()?.start()
            Log.i(TAG, "Context aggregator started")
        } catch (e: Exception) {
            Log.w(TAG, "Context aggregator not available: ${e.message}")
        }
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.context.SituationRecognizer>()?.start()
            Log.i(TAG, "Situation recognizer started (20+ LifeSituation classification)")
        } catch (e: Exception) {
            Log.w(TAG, "Situation recognizer not available: ${e.message}")
        }

        // 14b. Action Classifier (skeleton → behavior, ST-GCN + rule-based)
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.context.ActionClassifier>()?.start()
            Log.i(TAG, "Action classifier started (10 actions, skeleton sequence → behavior)")
        } catch (e: Exception) {
            Log.w(TAG, "Action classifier not available: ${e.message}")
        }

        // 14c. ★ SituationAwareStarter — 이벤트 기반 서비스 지연 시작 (Koin Lazy Phase 1)
        // ExpertTeamManager, HUDModeManager, RunningCoachManager, MeetingContextService,
        // RelationshipTracker, HandInteractionManager, 학습/백업 서비스 등 ~20개를 이벤트 트리거로 전환
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<SituationAwareStarter>()?.start()
            Log.i(TAG, "SituationAwareStarter started (이벤트 기반 lazy start — ~20개 서비스 지연)")
        } catch (e: Exception) {
            Log.w(TAG, "SituationAwareStarter not available: ${e.message}")
        }

        // 15~16: ExpertTeamManager + HUDModeManager → SituationAwareStarter로 이관됨
        // (첫 SituationChanged 이벤트 시 lazy start)

        // 17. Goal Tracker (Phase 6: hierarchical goal tracking)
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.goal.GoalTracker>()?.start()
            Log.i(TAG, "Goal tracker started (day/week/month/year hierarchy)")
        } catch (e: Exception) {
            Log.w(TAG, "Goal tracker not available: ${e.message}")
        }

        // 18. Outcome Tracker (Phase 7: effectiveness learning + strategy optimization)
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.learning.OutcomeTracker>()?.start()
            Log.i(TAG, "Outcome tracker started (intervention effectiveness learning)")
        } catch (e: Exception) {
            Log.w(TAG, "Outcome tracker not available: ${e.message}")
        }

        // 19. Briefing Service → StorytellerOrchestrator에 흡수 (방안 B)

        // 20~23: DebugHUD, FamiliarityEngine, RelationshipTracker, AgentPersonalityEvolution
        // → SituationAwareStarter로 이관 (5분 지연 or 이벤트 트리거)

        // 24a. PlanHUD (할일/일정 AR HUD 업데이트 루프 — 60초 갱신)
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.plan.PlanHUD>()?.start()
            Log.i(TAG, "PlanHUD started (60s todo/schedule update loop)")
        } catch (e: Exception) {
            Log.w(TAG, "PlanHUD not available: ${e.message}")
        }

        // 24. Meeting → SituationAwareStarter로 이관 (SituationChanged(MEETING/TEACHING) 트리거)

        // 25. 적응형 회복력 시스템 (Part C)
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.focus.FocusModeManager>()?.start()
            Log.i(TAG, "FocusModeManager started (DND/PRIVATE voice commands)")
        } catch (e: Exception) {
            Log.w(TAG, "FocusModeManager not available: ${e.message}")
        }
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.focus.PalmFaceGestureDetector>()?.start()
            Log.i(TAG, "PalmFaceGestureDetector started (palm-to-face PRIVATE gesture)")
        } catch (e: Exception) {
            Log.w(TAG, "PalmFaceGestureDetector not available: ${e.message}")
        }
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.resilience.DeviceHealthMonitor>()?.start()
            Log.i(TAG, "DeviceHealthMonitor started (30s hardware health polling)")
        } catch (e: Exception) {
            Log.w(TAG, "DeviceHealthMonitor not available: ${e.message}")
        }
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.resilience.FailsafeController>()?.start()
            Log.i(TAG, "FailsafeController started (capability tier management)")
        } catch (e: Exception) {
            Log.w(TAG, "FailsafeController not available: ${e.message}")
        }
        // OperationalDirector start() 제거 (방안 B — 빈 껍데기, DI도 삭제됨)
        try {
            org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.resilience.EmergencyOrchestrator>()?.start()
            Log.i(TAG, "EmergencyOrchestrator started (에러 우회 지휘 시스템 활성)")
        } catch (e: Exception) {
            Log.w(TAG, "EmergencyOrchestrator not available: ${e.message}")
        }
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.resource.ResourceRegistry>()?.start()
            Log.i(TAG, "ResourceRegistry started (resource state tracking)")
        } catch (e: Exception) {
            Log.w(TAG, "ResourceRegistry not available: ${e.message}")
        }
        try {
            org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.resilience.ResourceGuardian>()?.start()
            Log.i(TAG, "ResourceGuardian started (AI 기반 자원 수호 활성)")
        } catch (e: Exception) {
            Log.w(TAG, "ResourceGuardian not available: ${e.message}")
        }
        // NetworkCameraClient → SituationAwareStarter 5분 지연 시작
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.resource.ResourceProposalManager>()?.start()
            Log.i(TAG, "ResourceProposalManager started (AI proposal approval flow)")
        } catch (e: Exception) {
            Log.w(TAG, "ResourceProposalManager not available: ${e.message}")
        }
        try {
            // ResourceToolExecutor를 ToolExecutorRegistry에 lazy 등록
            val registry = org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.tools.ToolExecutorRegistry>()
            val resourceExecutor = org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.resource.ResourceToolExecutor>()
            if (registry != null && resourceExecutor != null) {
                registry.register(resourceExecutor)
                Log.i(TAG, "ResourceToolExecutor registered (list_resources/activate_resource/propose_resource_combo)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ResourceToolExecutor registration failed: ${e.message}")
        }
        // CompanionDeviceManager + DriveTrainingScheduler → SituationAwareStarter 5분 지연 시작

        // 26b. ★ Phase F-6: LocalMLBridge — 기존 RoutineClassifier 모델로 LOCAL_ML 즉시 활성화
        // 이전 세션에서 이미 다운로드된 모델이 있으면 MASTERED 상황에 LOCAL_ML 등록
        try {
            val classifier = org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.learning.RoutineClassifier>()
            val lifecycleManager = org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.companion.SituationLifecycleManager>()
            if (classifier != null && lifecycleManager != null) {
                val activated = com.xreal.nativear.companion.LocalMLBridge
                    .activateForMasteredSituations(classifier, lifecycleManager)
                Log.i(TAG, "LocalMLBridge 초기화: $activated 개 MASTERED 상황에 LOCAL_ML 활성화 " +
                    "(RoutineClassifier 모델 준비: ${classifier.isReady()})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "LocalMLBridge 초기화 실패 (비치명적): ${e.message}")
        }

        // 26c~27: BackupSyncScheduler, PredictionSyncService, OrchestratorClient, EvolutionBridge
        // → SituationAwareStarter 5분 지연 시작

        // 28. RemoteToolExecutor — PC 서버 원격 도구 로드 (비동기, 서버 불가 시 무시)
        try {
            val remoteExecutor = org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.tools.RemoteToolExecutor>()
            val registry = org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.tools.ToolExecutorRegistry>()
            if (remoteExecutor != null && registry != null) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val count = remoteExecutor.loadRemoteTools()
                        if (count > 0) {
                            registry.register(remoteExecutor)
                            // 모든 프로바이더 공통 도구 정의 등록 (단일 소스: ToolDefinitionRegistry)
                            org.koin.java.KoinJavaComponent.getKoin().get<com.xreal.nativear.ai.ToolDefinitionRegistry>().registerAdditionalTools(remoteExecutor.loadedToolDefinitions)
                            Log.i(TAG, "RemoteToolExecutor: $count 개 원격 도구 등록 완료 (all providers)")
                        } else {
                            Log.d(TAG, "RemoteToolExecutor: 서버 미연결 — 원격 도구 없음")
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "RemoteToolExecutor 로드 실패 (정상 — Tailscale 미연결): ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "RemoteToolExecutor init failed: ${e.message}")
        }

        // 29. Storyteller Orchestrator — 하루를 하나의 이야기로 엮는 내러티브 엔진
        try {
            org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.storyteller.StorytellerOrchestrator>()?.start()
            Log.i(TAG, "StorytellerOrchestrator started (내러티브 엔진 — 5분 리플렉션)")
        } catch (e: Exception) {
            Log.w(TAG, "StorytellerOrchestrator not available: ${e.message}")
        }

        val bootDurationMs = System.currentTimeMillis() - bootStartMs
        Log.i(TAG, "✅ Level 4 complete (AI+컨텍스트+자율행동)")
        Log.i(TAG, "✅ All boot levels complete (boot_level=$currentBootLevel, ${bootDurationMs}ms)")
    }

    fun release() {
        Log.i(TAG, "AppBootstrapper: Releasing services...")
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<SituationAwareStarter>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.storyteller.StorytellerOrchestrator>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.ai.ProactiveScheduler>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.ai.RemoteLLMPool>()?.stopHealthCheck() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.evolution.EvolutionBridge>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.companion.CompanionDeviceManager>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.remote.NetworkCameraClient>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.resource.ResourceProposalManager>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.resource.ResourceRegistry>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.resilience.ResourceGuardian>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.resilience.EmergencyOrchestrator>()?.stop() } catch (_: Exception) {}
        // OperationalDirector stop() 제거 (방안 B)
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.resilience.FailsafeController>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.resilience.DeviceHealthMonitor>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.focus.PalmFaceGestureDetector>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.focus.FocusModeManager>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.plan.PlanHUD>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.meeting.MeetingContextService>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.meeting.ReminderScheduler>()?.cancel() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.companion.RelationshipTracker>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.companion.FamiliarityEngine>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.hud.DebugHUD>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.expert.BriefingService>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.learning.OutcomeTracker>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.goal.GoalTracker>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.hud.HUDModeManager>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.expert.ExpertTeamManager>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.context.SituationRecognizer>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.context.ContextAggregator>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.memory.ProactiveMemorySurfacer>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.interaction.HandInteractionManager>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.spatial.DriftCorrectionManager>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.spatial.SpatialUIManager>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.spatial.SpatialAnchorManager>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.spatial.PlaceRecognitionManager>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.spatial.PathTracker>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.resilience.SystemConductor>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.companion.AgentWarmupScheduler>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.companion.SituationPredictor>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.companion.SituationLifecycleManager>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.session.LifeSessionManager>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.session.FeedbackSessionManager>()?.stop() } catch (_: Exception) {}  // ★ Phase H
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.batch.BatchProcessor>()?.stop() } catch (_: Exception) {}
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.monitoring.SystemErrorLogger>()?.stop() } catch (_: Exception) {}
        strategistService.release()
        personSyncJob?.cancel()
        modelCleanupJob?.cancel()
        locationManager.stopLocationUpdates()
        hardwareManager.stopHardware()
        voiceManager.stopListening()
    }
}
