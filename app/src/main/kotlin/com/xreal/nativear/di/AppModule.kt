package com.xreal.nativear.di

import com.xreal.nativear.*
import com.xreal.nativear.core.*
import com.xreal.ai.UnifiedAIOrchestrator
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.util.concurrent.Executors

val appModule = module {
    // Core dependencies — Databases
    single { UnifiedMemoryDatabase(androidContext()) }
    single { SceneDatabase(androidContext()) }
    single { MemoryCompressor(get(), get<com.xreal.nativear.ai.IAICallService>(), getOrNull(), getOrNull()) }

    // ★ Policy Department: 정책 레지스트리 + 매니저
    single {
        com.xreal.nativear.policy.PolicyRegistry(get<UnifiedMemoryDatabase>()).apply {
            initialize()
        }
    }
    // IPolicyStore → PolicyRegistry 바인딩 (PolicyReader가 :core에서 IPolicyStore로 탐색)
    single<com.xreal.nativear.policy.IPolicyStore> { get<com.xreal.nativear.policy.PolicyRegistry>() }
    // ISpatialDatabase → SceneDatabase 바인딩 (:spatial 모듈이 ISpatialDatabase로 DB 접근)
    single<com.xreal.nativear.spatial.ISpatialDatabase> { get<SceneDatabase>() }
    single { com.xreal.nativear.policy.PolicyManager(get<com.xreal.nativear.policy.PolicyRegistry>(), get<com.xreal.nativear.core.GlobalEventBus>()) }

    // ★ Phase I: MemoryImportanceScorer — 규칙 기반 중요도 계산기 (AI 비용 없음)
    single { com.xreal.nativear.memory.MemoryImportanceScorer() }

    // MemorySaveHelper: shared insert pipeline (embedding + location + node + vec)
    single { com.xreal.nativear.memory.MemorySaveHelper(get<UnifiedMemoryDatabase>(), get<TextEmbedder>(), get<ILocationService>(), get<com.xreal.nativear.memory.MemoryImportanceScorer>()) }

    // MemorySearcher: uses shared UnifiedMemoryDatabase singleton (not a new instance)
    single { MemorySearcher(get<UnifiedMemoryDatabase>(), get()) }
    // MemoryRepository: receives both databases via constructor
    single { MemoryRepository(androidContext(), get<UnifiedMemoryDatabase>(), get<SceneDatabase>(), get(), get(), get(), get(), get(), get<EmotionClassifier>(), get<com.xreal.nativear.memory.MemorySaveHelper>()) }
    // IMemoryService는 IMemoryStore로 대체됨 — 소비자가 없으므로 바인딩 제거

    // ★ Memory Abstraction Layer: IMemoryStore + IMemoryCompaction
    single<com.xreal.nativear.memory.api.IMemoryStore> {
        com.xreal.nativear.memory.impl.SqliteMemoryStore(
            get<UnifiedMemoryDatabase>(),
            get<com.xreal.nativear.memory.MemorySaveHelper>(),
            get<MemorySearcher>(),
            get<TextEmbedder>()
        )
    }
    single<com.xreal.nativear.memory.api.IMemoryCompaction> {
        com.xreal.nativear.memory.impl.SqliteMemoryCompaction(
            get<UnifiedMemoryDatabase>(),
            get<MemoryCompressor>()
        )
    }

    single { UnifiedAIOrchestrator(androidContext()) }

    // --- Missing Registrations (Fixed) ---
    // NaverService implements both ISearchService and INavigationService
    single { NaverService() }
    single<ISearchService> { get<NaverService>() }
    single<INavigationService> { get<NaverService>() }

    // WeatherService implements IWeatherService
    single { WeatherService(androidContext()) }
    single<IWeatherService> { get<WeatherService>() }

    // SystemTTSAdapter (needed by VoiceManager and EmotionTTSService)
    single { 
        val model = SystemTTSAdapter(androidContext())
        get<UnifiedAIOrchestrator>().registerModel("SystemTTS", model)
        model
    }

    // LocationManager: IMemoryService injected lazily inside to break circular dependency
    single {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        LocationManager(androidContext(), scope, get<NaverService>(), get<com.xreal.nativear.core.GlobalEventBus>())
    }

    single<ILocationService> { get<LocationManager>() }

    // Position fusion engine (GPS+PDR Kalman filter)
    single { com.xreal.nativear.running.PositionFusionEngine(get()) }

    // Barometric floor detector
    single { com.xreal.nativear.running.BarometricFloorDetector(androidContext(), get()) }

    // Track anchor service (GPS lap detection + spatial anchors for running)
    single {
        com.xreal.nativear.running.RunningTrackAnchorService(
            eventBus = get(),
            spatialAnchorManager = get(),
            session = get<com.xreal.nativear.running.RunningCoachManager>().session
        )
    }

    // Pacemaker service (target pace ghost runner)
    single {
        com.xreal.nativear.running.PacemakerService(
            eventBus = get(),
            spatialAnchorManager = get()
        )
    }

    single { com.xreal.hardware.XRealHardwareManager(androidContext()) }

    // HardwareManager: Changed from parameterized factory to single
    // ★ CameraStreamManager — 카메라 소스 선택 + 건강 모니터링 + 자동 fallback
    single { com.xreal.nativear.camera.CameraStreamManager(get<VisionManager>(), get()) }

    single {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        HardwareManager(androidContext(), scope, get(), get(), get<VisionManager>(), get(), get(), getOrNull())
    }


    // Asset Loader (Context 추상화 — input 모듈의 Android 의존 제거)
    single<com.xreal.nativear.core.IAssetLoader> { com.xreal.nativear.core.AndroidAssetLoader(androidContext()) }

    // Engine Components
    // Note: WhisperEngine (standalone module) is created directly by AudioAnalysisService/WhisperLifelogService.
    // It does not need Koin DI or Orchestrator registration.
    single {
        val model = LiteRTWrapper(get())
        get<UnifiedAIOrchestrator>().registerModel("LiteRT_YOLO", model)
        model
    }
    single {
        val model = ImageEmbedder(get())
        get<UnifiedAIOrchestrator>().registerModel("ImageEmbedder", model)
        model
    }
    single { CloudBackupManager(androidContext(), get()) }
    single { PersonSyncManager(androidContext(), get(), get()) }
    single { 
        val model = EmotionClassifier()
        get<UnifiedAIOrchestrator>().registerModel("Emotion", model)
        model
    }
    single {
        val model = OCRModel()
        get<UnifiedAIOrchestrator>().registerModel("OCR", model)
        model
    }
    single { 
        val model = WavLMAdapter(androidContext())
        get<UnifiedAIOrchestrator>().registerModel("WavLM", model)
        model
    }
    single { 
        val model = PoseEstimationModel(get())
        get<UnifiedAIOrchestrator>().registerModel("Pose", model)
        model
    }
    single { EmotionTTSService(get()) }
    single {
        val model = AudioEventClassifier(get())
        get<UnifiedAIOrchestrator>().registerModel("YAMNet", model)
        model
    }
    single {
        val model = FaceDetector(get())
        get<UnifiedAIOrchestrator>().registerModel("BlazeFace", model)
        model
    }
    single {
        val model = FaceEmbedder(get())
        get<UnifiedAIOrchestrator>().registerModel("FaceEmbedder", model)
        model
    }
    single { PersonRepository(get(), get(), get(), get()) }
    single {
        val model = FacialExpressionClassifier(get())
        get<UnifiedAIOrchestrator>().registerModel("FER", model)
        model
    }
    single {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        InteractionTracker(get(), get(), get(), scope)
    }
    single {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        SpeakerDiarizer(get(), get(), get(), scope)
    }

    // Helpers
    single { 
        val model = TextEmbedder(androidContext())
        get<UnifiedAIOrchestrator>().registerModel("TextEmbedder", model)
        model
    }
    single {
        // Create a dedicated scope for VisionManager
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        VisionManager(
            androidContext(),
            Executors.newSingleThreadExecutor(), // Use internal executor
            get(), get(), get(), get(), get(), get(),
            scope,
            get(), get(), get(), get(),
            get(),
            get<com.xreal.nativear.hand.HandTrackingModel>()  // Hand tracking model (optional)
        )
    }
    single<IVisionService> { get<VisionManager>() }

    // SpeakerEmbeddingAdapter (3D-Speaker ECAPA-TDNN, 192-dim)
    // WavLM (370MB, 768-dim) 대체 — 20MB, sherpa-onnx JNI 경유
    single {
        val adapter = com.xreal.whisper.SpeakerEmbeddingAdapter()
        val modelsDir = androidContext().getExternalFilesDir("models")
        if (modelsDir != null) {
            val modelDir = com.xreal.whisper.SpeakerEmbeddingAdapter.findModelDir(modelsDir)
            if (modelDir != null) {
                adapter.initialize(modelDir)
            }
        }
        adapter
    }

    single {
        VoiceManager(androidContext(), get(), get())
    }
    single<IVoiceService> {
        get<VoiceManager>()
    }

    // SystemAnalyticsService (Phase 17: singleton data mining service, 0 AI calls)
    single {
        com.xreal.nativear.analytics.SystemAnalyticsService(
            database = get<UnifiedMemoryDatabase>(),
            sceneDatabase = get<SceneDatabase>(),
            eventBus = get()
        )
    }

    // ★ Phase M: 도구 사용 통계 추적기 (싱글톤)
    single { com.xreal.nativear.tools.ToolUsageTracker() }

    // 원격 도구 실행기 (PC 서버의 도구를 AI 에이전트에 제공)
    single { com.xreal.nativear.tools.RemoteToolExecutor(get()) }

    // Tool Executor Registry (domain-specific executors)
    single {
        com.xreal.nativear.tools.ToolExecutorRegistry(
            toolUsageTracker = get()  // ★ Phase M
        ).apply {
            // 개별 executor 등록 실패가 다른 executor에 영향 주지 않도록 격리
            fun safeRegister(name: String, block: () -> com.xreal.nativear.tools.IToolExecutor) {
                try { register(block()) } catch (e: Exception) {
                    android.util.Log.w("ToolExecutorRegistry", "$name 등록 실패 (비치명적): ${e.message}")
                }
            }
            safeRegister("Web") { com.xreal.nativear.tools.WebToolExecutor(get(), get(), get()) }
            safeRegister("Memory") { com.xreal.nativear.tools.MemoryToolExecutor(
                memoryStore = get(),
                memorySearcher = get(),
                sceneDatabase = get(),
                cloudBackupManager = get(),
                bitmapProvider = { try { get<VisionManager>().getLatestBitmap() } catch (e: Exception) { null } }
            ) }
            safeRegister("Vision") { com.xreal.nativear.tools.VisionToolExecutor(
                visionService = get(),
                screenObjectsProvider = { "[]" } // Updated by MainActivity callback
            ) }
            safeRegister("Drawing") { com.xreal.nativear.tools.DrawingToolExecutor(get()) }
            safeRegister("Running") { com.xreal.nativear.tools.RunningToolExecutor() }
            safeRegister("System") { com.xreal.nativear.tools.SystemToolExecutor(get()) }
            safeRegister("Data") { com.xreal.nativear.tools.DataToolExecutor(get()) }
            safeRegister("HUDInteraction") { com.xreal.nativear.interaction.HUDInteractionToolExecutor(
                interactionManager = get(),
                physicsEngine = get(),
                templateManager = get(),
                eventBus = get()
            ) }
            safeRegister("Planner") { com.xreal.nativear.plan.PlannerToolExecutor(get()) }
            safeRegister("HUD") { com.xreal.nativear.hud.HUDToolExecutor(get(), get()) }
            safeRegister("Capability") { com.xreal.nativear.evolution.CapabilityToolExecutor(get()) }
            safeRegister("Analytics") { com.xreal.nativear.tools.AnalyticsToolExecutor(get()) }
            safeRegister("RemoteCamera") { com.xreal.nativear.remote.RemoteCameraToolExecutor(get(), get()) }
            // ★ Phase 19: 전문가 AI 자기진화 도구 (request_prompt_addition 등 3개)
            safeRegister("ExpertSelfAdvocacy") { com.xreal.nativear.tools.ExpertSelfAdvocacyToolExecutor(
                get<com.xreal.nativear.expert.ExpertPeerRequestStore>()
            ) }
            // ★ Policy Department: 정책 조회/변경 도구
            safeRegister("Policy") { com.xreal.nativear.tools.PolicyToolExecutor(get(), get()) }
            // ResourceToolExecutor는 DI 사이클 방지를 위해 lazy 등록
            // (ResourceRegistry/ResourceProposalManager가 이 블록보다 늦게 정의됨)
        }
    }

    single {
        AIAgentManager(
            context = androidContext(),
            memoryStore = get(),
            searchService = get(),
            weatherService = get(),
            navigationService = get(),
            visionService = get(),
            aiOrchestrator = get(),
            locationService = get(),
            cloudBackupManager = get(),
            eventBus = get(),
            toolExecutorRegistry = get(),
            tokenBudgetTracker = get()
        )
    }

    // --- Multi-AI Provider System ---

    // Shared OkHttpClient for REST providers
    single {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    // --- Remote Camera Service (PC Webcam → XREAL HUD PIP) ---
    single {
        com.xreal.nativear.remote.RemoteCameraService(
            httpClient = get(),
            eventBus = get()
        )
    }

    // ★ NetworkCameraClient: 네트워크 카메라 → AI 비전 파이프라인 연결
    // ResourceActivated(CAMERA_NETWORK_ENDPOINT) 이벤트 구독 → MJPEG → feedExternalFrame()
    single {
        com.xreal.nativear.remote.NetworkCameraClient(
            visionManager = get(),
            remoteCameraService = get(),
            httpClient = get(),
            eventBus = get()
        )
    }

    // --- Sensor Relay Receiver (Fold 3 SSE → XRealEvent) ---
    // 서버 주소는 SharedPreferences에 영속 저장 (Tailscale IP 기본값, WiFi 변경 무관)
    single {
        com.xreal.nativear.remote.SensorRelayReceiver(
            context = androidContext(),
            httpClient = get(),
            eventBus = get()
        )
    }

    // --- Backup Sync System ---
    single {
        com.xreal.nativear.sync.BackupSyncConfig(androidContext()).apply {
            serverUrl = "http://100.101.127.124:8090"  // Tailscale IP (desktop-j7918pn)
            apiKey = "3ztg3rzTith-vViCK7FLe5SQNUqqACKTojyDX1oiNe0"
            syncIntervalMinutes = 60
            requireWifi = false
            enabled = true
        }
    }
    single { com.xreal.nativear.sync.SyncApiClient(httpClient = get(), config = get()) }
    single { com.xreal.nativear.sync.BackupSyncScheduler(context = androidContext(), config = get()) }
    single { com.xreal.nativear.sync.PredictionSyncService(
        database = get<UnifiedMemoryDatabase>(),
        eventBus = get<GlobalEventBus>(),
        syncConfig = get<com.xreal.nativear.sync.BackupSyncConfig>()
    ) }

    // --- Wear OS Data Receiver ---
    single { com.xreal.nativear.wear.WearDataReceiver(androidContext(), get()) }

    // --- Wear Audio Receiver (Galaxy Watch 마이크 오디오 수신) ---
    single {
        com.xreal.nativear.wear.WearAudioReceiver(
            context = androidContext(),
            eventBus = get(),
            deviceModeManager = getOrNull()
        )
    }

    // --- ★ SystemErrorLogger (Phase 18: 에러 추적 파이프라인) ---
    // SystemEvent.Error 구독 → DB error_logs 저장 → 반복 시 BUG_REPORT 자동 제출
    single {
        com.xreal.nativear.monitoring.SystemErrorLogger(
            eventBus = get(),
            database = get()
        )
    }

    // --- Resource Monitor + Device Mode Manager (Phase 18) ---
    single {
        com.xreal.nativear.monitoring.ResourceMonitor(
            context = androidContext(),
            eventBus = get(),
            intervalMs = 30_000L
        )
    }
    single {
        com.xreal.nativear.monitoring.DeviceModeManager(
            eventBus = get(),
            cadenceConfig = get<com.xreal.nativear.cadence.CadenceConfig>(),
            resourceMonitor = get()
        )
    }

    // API Key Manager
    single { com.xreal.nativear.ai.AIKeyManager(androidContext()) }

    // AI Providers (Koin named qualifiers)
    // ★ Phase B: 모든 프로바이더에 전달할 AIToolDefinition 목록 (ToolDefinitionRegistry 단일 소스)
    single { com.xreal.nativear.ai.ToolDefinitionRegistry() }
    single<List<com.xreal.nativear.ai.AIToolDefinition>> { get<com.xreal.nativear.ai.ToolDefinitionRegistry>().getAllToolDefinitions() }

    // ★ Phase C: 생활 세션 관리자
    single {
        com.xreal.nativear.session.LifeSessionManager(
            database = get<UnifiedMemoryDatabase>(),
            eventBus = get()
        )
    }

    single<com.xreal.nativear.ai.IAIProvider>(named("gemini")) {
        val keyManager = get<com.xreal.nativear.ai.AIKeyManager>()
        com.xreal.nativear.ai.GeminiProvider(
            com.xreal.nativear.ai.ProviderConfig(
                providerId = com.xreal.nativear.ai.ProviderId.GEMINI,
                apiKey = keyManager.getApiKey(com.xreal.nativear.ai.ProviderId.GEMINI)
                    ?: "AIzaSyAQbpllbZGGrBNjgbt8T96ZqGCeEsvn3cU",
                model = "gemini-2.5-flash"
            )
        ).also { provider ->
            // Register Gemini SDK tool declarations for tool-calling conversations
            provider.registeredTools = GeminiTools.getAllTools()
        }
    }

    single<com.xreal.nativear.ai.IAIProvider>(named("openai")) {
        val keyManager = get<com.xreal.nativear.ai.AIKeyManager>()
        com.xreal.nativear.ai.OpenAIProvider(
            com.xreal.nativear.ai.ProviderConfig(
                providerId = com.xreal.nativear.ai.ProviderId.OPENAI,
                apiKey = keyManager.getApiKey(com.xreal.nativear.ai.ProviderId.OPENAI)
                    ?: "REDACTED_OPENAI_KEY",
                model = "gpt-4.1-mini"
            ),
            get()
        )
    }

    single<com.xreal.nativear.ai.IAIProvider>(named("claude")) {
        val keyManager = get<com.xreal.nativear.ai.AIKeyManager>()
        com.xreal.nativear.ai.ClaudeProvider(
            com.xreal.nativear.ai.ProviderConfig(
                providerId = com.xreal.nativear.ai.ProviderId.CLAUDE,
                apiKey = keyManager.getApiKey(com.xreal.nativear.ai.ProviderId.CLAUDE)
                    ?: "REDACTED_CLAUDE_KEY",
                model = "claude-sonnet-4-6"
            ),
            get()
        )
    }

    single<com.xreal.nativear.ai.IAIProvider>(named("grok")) {
        val keyManager = get<com.xreal.nativear.ai.AIKeyManager>()
        com.xreal.nativear.ai.GrokProvider(
            com.xreal.nativear.ai.ProviderConfig(
                providerId = com.xreal.nativear.ai.ProviderId.GROK,
                apiKey = keyManager.getApiKey(com.xreal.nativear.ai.ProviderId.GROK)
                    ?: "REDACTED_GROK_KEY",
                model = "grok-3-fast"
            ),
            get()
        )
    }

    // ─── 로컬 LLM 서버 (llama.cpp/KoboldCpp, Tailscale 경유, $0 비용) ───
    single<com.xreal.nativear.ai.IAIProvider>(named("local")) {
        com.xreal.nativear.ai.LocalServerProvider(
            com.xreal.nativear.ai.ProviderConfig(
                providerId = com.xreal.nativear.ai.ProviderId.LOCAL,
                apiKey = "not-needed",
                model = "gemma-3-12b-it",
                baseUrl = "http://100.101.127.124:8080/v1/chat/completions",
                maxTokens = 2048,
                temperature = 0.7f
            ),
            get()
        )
    }

    // ─── 스팀덱 LLM 서버 (llama.cpp, gemma-3-4b, Tailscale 경유, $0 비용) ───
    single<com.xreal.nativear.ai.IAIProvider>(named("steamdeck")) {
        com.xreal.nativear.ai.LocalServerProvider(
            com.xreal.nativear.ai.ProviderConfig(
                providerId = com.xreal.nativear.ai.ProviderId.LOCAL_STEAMDECK,
                apiKey = "not-needed",
                model = "gemma-3-4b-it-Q4_K_M.gguf",
                baseUrl = "http://100.98.177.14:8080/v1/chat/completions",
                maxTokens = 2048,
                temperature = 0.7f
            ),
            get()
        )
    }

    // ─── 음성 PC LLM 서버 (Gemma-3 4B, RX570 Vulkan, Tailscale 경유, $0 비용) ───
    single<com.xreal.nativear.ai.IAIProvider>(named("speech_pc")) {
        com.xreal.nativear.ai.LocalServerProvider(
            com.xreal.nativear.ai.ProviderConfig(
                providerId = com.xreal.nativear.ai.ProviderId.LOCAL_SPEECH_PC,
                apiKey = "not-needed",
                model = "gemma-3-4b-it",
                baseUrl = "http://100.121.84.80:8179/v1/chat/completions",
                maxTokens = 2048,
                temperature = 0.7f
            ),
            get()
        )
    }

    // ─── 엣지 AI 시스템 (LiteRT-LM v0.8.1, RESEARCH.md §2 참조) ───

    // ConnectivityMonitor — 네트워크 상태 감지
    single { com.xreal.nativear.edge.ConnectivityMonitor(androidContext(), get()) }

    // EdgeModelManager — LiteRT-LM Engine 캐시 및 생명주기
    single { com.xreal.nativear.edge.EdgeModelManager(androidContext(), get()) }

    // 엣지 LLM 프로바이더 (3-Tier)
    // RESEARCH.md §2 LiteRT-LM v0.8.1, §4 Gemma 스펙 참조
    single<com.xreal.nativear.ai.IAIProvider>(named("edge_router")) {
        com.xreal.nativear.edge.EdgeLLMProvider(
            tier = com.xreal.nativear.edge.EdgeModelTier.ROUTER_270M,
            edgeModelManager = get()
        )
    }
    single<com.xreal.nativear.ai.IAIProvider>(named("edge_agent")) {
        com.xreal.nativear.edge.EdgeLLMProvider(
            tier = com.xreal.nativear.edge.EdgeModelTier.AGENT_1B,
            edgeModelManager = get()
        )
    }
    single<com.xreal.nativear.ai.IAIProvider>(named("edge_emergency")) {
        com.xreal.nativear.edge.EdgeLLMProvider(
            tier = com.xreal.nativear.edge.EdgeModelTier.EMERGENCY_E2B,
            edgeModelManager = get()
        )
    }

    // EdgeDelegationRouter — AI 호출 경로 결정 (서버 vs 엣지)
    single {
        com.xreal.nativear.edge.EdgeDelegationRouter(
            edgeModelManager = get(),
            connectivityMonitor = get(),
            routerProvider = get<com.xreal.nativear.ai.IAIProvider>(named("edge_router"))
                as com.xreal.nativear.edge.EdgeLLMProvider,
            eventBus = get()
        )
    }

    // ★ EdgeContextJudge — 270M 기반 meta-결정 게이트 (하드코딩 규칙 대체)
    // ExpertTeamManager, MissionAgentRunner에 주입 → "지금 AI 필요한가?" 동적 판단
    single {
        com.xreal.nativear.edge.EdgeContextJudge(
            routerProvider = get<com.xreal.nativear.ai.IAIProvider>(named("edge_router"))
                as com.xreal.nativear.edge.EdgeLLMProvider
        )
    }

    // Provider map — 엣지 프로바이더 포함 (RESEARCH.md §11 IAIProvider 참조)
    single {
        mapOf(
            com.xreal.nativear.ai.ProviderId.GEMINI to get<com.xreal.nativear.ai.IAIProvider>(named("gemini")),
            com.xreal.nativear.ai.ProviderId.OPENAI to get<com.xreal.nativear.ai.IAIProvider>(named("openai")),
            com.xreal.nativear.ai.ProviderId.CLAUDE to get<com.xreal.nativear.ai.IAIProvider>(named("claude")),
            com.xreal.nativear.ai.ProviderId.GROK to get<com.xreal.nativear.ai.IAIProvider>(named("grok")),
            com.xreal.nativear.ai.ProviderId.LOCAL to get<com.xreal.nativear.ai.IAIProvider>(named("local")),
            com.xreal.nativear.ai.ProviderId.LOCAL_STEAMDECK to get<com.xreal.nativear.ai.IAIProvider>(named("steamdeck")),
            com.xreal.nativear.ai.ProviderId.EDGE_ROUTER to get<com.xreal.nativear.ai.IAIProvider>(named("edge_router")),
            com.xreal.nativear.ai.ProviderId.EDGE_AGENT to get<com.xreal.nativear.ai.IAIProvider>(named("edge_agent")),
            com.xreal.nativear.ai.ProviderId.EDGE_EMERGENCY to get<com.xreal.nativear.ai.IAIProvider>(named("edge_emergency"))
        )
    }

    // ★ Remote LLM Pool — PC + 스팀덱 + 추후 서버를 어레이로 관리
    single {
        com.xreal.nativear.ai.RemoteLLMPool(
            servers = mapOf(
                com.xreal.nativear.ai.ProviderId.LOCAL to
                    get<com.xreal.nativear.ai.IAIProvider>(named("local")),
                com.xreal.nativear.ai.ProviderId.LOCAL_STEAMDECK to
                    get<com.xreal.nativear.ai.IAIProvider>(named("steamdeck")),
                com.xreal.nativear.ai.ProviderId.LOCAL_SPEECH_PC to
                    get<com.xreal.nativear.ai.IAIProvider>(named("speech_pc"))
            )
        )
    }

    // ★ ValueGatekeeper — 통계 기반 AI 호출 가치 판단기
    single {
        com.xreal.nativear.ai.ValueGatekeeper(
            tokenBudgetTracker = getOrNull(),
            eventBus = get()
        )
    }

    // ★ AICallGateway — 모든 클라우드 AI 호출의 중앙 게이트 싱글톤
    single {
        com.xreal.nativear.ai.AICallGateway(
            tokenBudgetTracker = getOrNull(),
            valueGatekeeper = get()
        )
    }

    // ★ ProactiveScheduler — 모든 자율행동 AI 호출의 중앙 스케줄러
    single {
        com.xreal.nativear.ai.ProactiveScheduler(
            aiCallGateway = get(),
            storyPhaseController = get<com.xreal.nativear.storyteller.IStoryPhaseGate>()
        )
    }

    // ★ AIResourceRegistry — 통합 AI 프로바이더 라우팅 싱글톤
    single {
        com.xreal.nativear.ai.AIResourceRegistry(
            tokenBudgetTracker = getOrNull(),
            remoteLLMPool = get(),
            aiCallGateway = get(),
            valueGatekeeper = get(),
            storyPhaseController = get<com.xreal.nativear.storyteller.IStoryPhaseGate>()
        ).apply {
            // ── 리모트 ($0, Tailscale VPN) ──
            register(com.xreal.nativear.ai.AIResourceRegistry.ProviderCapability(
                providerId = com.xreal.nativear.ai.ProviderId.LOCAL,
                provider = get<com.xreal.nativear.ai.IAIProvider>(named("local")),
                tier = com.xreal.nativear.ai.AIResourceRegistry.ProviderTier.REMOTE,
                hasVision = true,       // mmproj-model-f16.gguf 탑재 → 멀티모달
                hasToolCalling = false,
                costPerToken = 0f,
                qualityRank = 8         // gemma-3-12b
            ))
            register(com.xreal.nativear.ai.AIResourceRegistry.ProviderCapability(
                providerId = com.xreal.nativear.ai.ProviderId.LOCAL_STEAMDECK,
                provider = get<com.xreal.nativear.ai.IAIProvider>(named("steamdeck")),
                tier = com.xreal.nativear.ai.AIResourceRegistry.ProviderTier.REMOTE,
                hasVision = false,      // llama.cpp mmproj 미탑재 → 텍스트 전용
                hasToolCalling = false,
                costPerToken = 0f,
                qualityRank = 5         // gemma-3-4b
            ))
            // 보조 PC (RX570, Gemma-3 4B, $0 — 음성서버 겸용 PC의 LLM)
            register(com.xreal.nativear.ai.AIResourceRegistry.ProviderCapability(
                providerId = com.xreal.nativear.ai.ProviderId.LOCAL_SPEECH_PC,
                provider = get<com.xreal.nativear.ai.IAIProvider>(named("speech_pc")),
                tier = com.xreal.nativear.ai.AIResourceRegistry.ProviderTier.REMOTE,
                hasVision = false,
                hasToolCalling = false,
                costPerToken = 0f,
                qualityRank = 4         // gemma-3-4b (12b보다 낮음)
            ))

            // ── 서버 ($, Cloud API) ──
            register(com.xreal.nativear.ai.AIResourceRegistry.ProviderCapability(
                providerId = com.xreal.nativear.ai.ProviderId.GEMINI,
                provider = get<com.xreal.nativear.ai.IAIProvider>(named("gemini")),
                tier = com.xreal.nativear.ai.AIResourceRegistry.ProviderTier.CLOUD,
                hasVision = true,       // GeminiProvider.sendVisionMessage()
                hasToolCalling = true,  // GeminiTools
                costPerToken = 0.01f,
                qualityRank = 9         // gemini-2.5-flash
            ))
            register(com.xreal.nativear.ai.AIResourceRegistry.ProviderCapability(
                providerId = com.xreal.nativear.ai.ProviderId.OPENAI,
                provider = get<com.xreal.nativear.ai.IAIProvider>(named("openai")),
                tier = com.xreal.nativear.ai.AIResourceRegistry.ProviderTier.CLOUD,
                hasVision = false,      // gpt-4.1-mini — 비전 미사용
                hasToolCalling = true,
                costPerToken = 0.015f,
                qualityRank = 7
            ))
            register(com.xreal.nativear.ai.AIResourceRegistry.ProviderCapability(
                providerId = com.xreal.nativear.ai.ProviderId.CLAUDE,
                provider = get<com.xreal.nativear.ai.IAIProvider>(named("claude")),
                tier = com.xreal.nativear.ai.AIResourceRegistry.ProviderTier.CLOUD,
                hasVision = false,
                hasToolCalling = true,
                costPerToken = 0.02f,
                qualityRank = 8
            ))
            register(com.xreal.nativear.ai.AIResourceRegistry.ProviderCapability(
                providerId = com.xreal.nativear.ai.ProviderId.GROK,
                provider = get<com.xreal.nativear.ai.IAIProvider>(named("grok")),
                tier = com.xreal.nativear.ai.AIResourceRegistry.ProviderTier.CLOUD,
                hasVision = false,
                hasToolCalling = true,
                costPerToken = 0.01f,
                qualityRank = 6
            ))

            // ── 엣지 ($0, on-device llama.cpp) ──
            register(com.xreal.nativear.ai.AIResourceRegistry.ProviderCapability(
                providerId = com.xreal.nativear.ai.ProviderId.EDGE_AGENT,
                provider = get<com.xreal.nativear.ai.IAIProvider>(named("edge_agent")),
                tier = com.xreal.nativear.ai.AIResourceRegistry.ProviderTier.EDGE,
                hasVision = false,      // Qwen3 텍스트 전용
                hasToolCalling = false,
                costPerToken = 0f,
                qualityRank = 4         // Qwen3-1.7B-Q4
            ))
            register(com.xreal.nativear.ai.AIResourceRegistry.ProviderCapability(
                providerId = com.xreal.nativear.ai.ProviderId.EDGE_EMERGENCY,
                provider = get<com.xreal.nativear.ai.IAIProvider>(named("edge_emergency")),
                tier = com.xreal.nativear.ai.AIResourceRegistry.ProviderTier.EDGE,
                hasVision = false,      // Qwen3 텍스트 전용 (OCR 폴백은 visionSender로)
                hasToolCalling = false,
                costPerToken = 0f,
                qualityRank = 3         // Qwen3-1.7B-Q8
            ))
            // EDGE_ROUTER(0.6B)는 분류 전용이므로 범용 라우팅에서 제외
        }
    }

    // ★ IAICallService — 모든 소비자가 이 인터페이스를 통해 AI 호출
    single<com.xreal.nativear.ai.IAICallService> { get<com.xreal.nativear.ai.AIResourceRegistry>() }

    // ★ 도메인 인터페이스 바인딩 (10개 인터페이스 → 구체 싱글톤 매핑)
    single<com.xreal.nativear.ai.IPersonaService> { get<com.xreal.nativear.ai.PersonaManager>() }
    // IMemoryAccess는 IMemoryStore로 대체됨 — 소비자가 없으므로 바인딩 제거

    // Persona Memory Service
    single {
        com.xreal.nativear.ai.PersonaMemoryService(
            database = get<UnifiedMemoryDatabase>(),
            memoryStore = get(),
            memoryCompressor = get<MemoryCompressor>()
        )
    }

    // ★ Policy Department — 중복 제거됨 (상단 17행 블록에서 등록)

    // ★ GoalOrientedAgentLoop — ReAct + Reflexion 기반 심층 추론 에이전트
    single {
        com.xreal.nativear.agent.AgentIdentityStore(
            database = get<UnifiedMemoryDatabase>()
        )
    }
    single {
        com.xreal.nativear.agent.GoalOrientedAgentLoop(
            aiRegistry = get<com.xreal.nativear.ai.IAICallService>(),
            toolRegistry = get<com.xreal.nativear.tools.ToolExecutorRegistry>(),
            agentIdentity = get(),
            eventBus = get(),
            personaManager = getOrNull()
        )
    }
    single {
        com.xreal.nativear.agent.AdaptiveReflectionTrigger(
            eventBus = get()
        )
    }

    // Strategist System
    single { com.xreal.nativear.strategist.DirectiveStore(get<UnifiedMemoryDatabase>(), get<com.xreal.nativear.memory.api.IMemoryStore>()) }
    single {
        com.xreal.nativear.strategist.StrategistReflector(
            aiRegistry = get<com.xreal.nativear.ai.IAICallService>(),
            directiveStore = get<com.xreal.nativear.strategist.DirectiveStore>(),
            cadenceContextProvider = {
                val tracker = get<com.xreal.nativear.cadence.UserStateTracker>()
                val config = get<com.xreal.nativear.cadence.CadenceConfig>()
                val p = config.current
                "사용자 상태: ${tracker.state.value}\n" +
                "PDR 걸음 임계값: ${p.pdrStepThreshold}, OCR 간격: ${p.ocrIntervalMs}ms, " +
                "감지 간격: ${p.detectIntervalMs}ms, 포즈 간격: ${p.poseIntervalMs}ms, " +
                "프레임 스킵: ${p.frameSkip}, 임베딩 간격: ${p.visualEmbeddingIntervalMs}ms"
            },
            digitalTwinContextProvider = {
                val dtb = get<com.xreal.nativear.cadence.DigitalTwinBuilder>()
                val profileSummary = dtb.profile.value.toSummaryString()
                val predictionSummary = try {
                    getOrNull<com.xreal.nativear.sync.PredictionSyncService>()?.getProfileSummary()
                } catch (_: Exception) { null }
                if (predictionSummary != null) {
                    "$profileSummary\n\n[PC 서버 예측 상세]\n$predictionSummary"
                } else profileSummary
            },
            tokenBudgetTracker = get(),
            // ★ Phase L: PersonaManager 싱글톤 — 사용자 컨텍스트(DNA·프로필·기억) 주입
            personaManager = get<com.xreal.nativear.ai.PersonaManager>(),
            // ★ Token Economy: 게이트웨이 + 가치판단 통계 주입
            aiCallGateway = get(),
            valueGatekeeper = get()
        )
    }

    // Persona Manager (with Strategist DirectiveStore + Familiarity + Personality injection)
    single {
        com.xreal.nativear.ai.PersonaManager(
            personaMemoryService = get<com.xreal.nativear.ai.PersonaMemoryService>(),
            providers = get(),
            directiveStore = get<com.xreal.nativear.strategist.DirectiveStore>(),
            familiarityEngine = get<com.xreal.nativear.companion.FamiliarityEngine>(),
            agentEvolution = get<com.xreal.nativear.companion.AgentPersonalityEvolution>(),
            userProfileManager = get<com.xreal.nativear.meeting.UserProfileManager>(),
            dynamicProfileStore = get<com.xreal.nativear.expert.ExpertDynamicProfileStore>(),  // ★ Phase 19
            userDnaManager = get<com.xreal.nativear.profile.UserDNAManager>()  // ★ Phase H
        )
    }

    // Token Budget Tracker
    single { com.xreal.nativear.router.persona.TokenBudgetTracker(analyticsService = get()) }

    // ★ Phase N: 도구 건강 모니터링 (실패율 기반 자동 격리)
    single { com.xreal.nativear.tools.ToolHealthMonitor(get()) }

    // ★ Phase N + 오프라인 개선: 동적 Provider 선택 라우터 (Remote LLM Pool + failover + 예산 + 네트워크)
    single {
        com.xreal.nativear.ai.PersonaProviderRouter(
            providers = get(),
            tokenBudgetTracker = get(),
            connectivityMonitor = get(),
            remoteLLMPool = get()   // ★ PC + 스팀덱 + 추후 서버 어레이
        )
    }

    // Multi-AI Orchestrator
    single {
        com.xreal.nativear.ai.MultiAIOrchestrator(
            personaManager = get<com.xreal.nativear.ai.PersonaManager>(),
            personaMemoryService = get<com.xreal.nativear.ai.PersonaMemoryService>(),
            providers = get(),
            eventBus = get(),
            toolExecutorRegistry = get(),
            tokenBudgetTracker = get(),
            analyticsService = get(),
            personaProviderRouter = get<com.xreal.nativear.ai.PersonaProviderRouter>(),  // ★ Phase N
            toolHealthMonitor = get<com.xreal.nativear.tools.ToolHealthMonitor>(),        // ★ Phase N
            remoteLLMPool = get<com.xreal.nativear.ai.RemoteLLMPool>()                   // ★ Remote LLM 어레이
        )
    }

    // Digital Twin Builder
    single {
        com.xreal.nativear.cadence.DigitalTwinBuilder(
            memoryDatabase = get<UnifiedMemoryDatabase>(),
            sceneDatabase = get<SceneDatabase>(),
            memoryStore = get<com.xreal.nativear.memory.api.IMemoryStore>(),
            predictionSyncService = getOrNull<com.xreal.nativear.sync.PredictionSyncService>()
        )
    }

    // --- Context Engine + Situation Recognition (Phase 1) ---
    single {
        com.xreal.nativear.context.ContextAggregator(
            eventBus = get(),
            locationManager = get<LocationManager>(),
            userStateTracker = get(),
            digitalTwinBuilder = get(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob()),
            planManager = get<com.xreal.nativear.plan.PlanManager>(),
            userProfileManager = get<com.xreal.nativear.meeting.UserProfileManager>(),
            naverService = getOrNull()
        )
    }
    single<com.xreal.nativear.context.IContextSnapshot> { get<com.xreal.nativear.context.ContextAggregator>() }
    single {
        com.xreal.nativear.context.SituationRecognizer(
            contextAggregator = get(),
            eventBus = get(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob()),
            userProfileManager = get(),
            aiRegistry = get<com.xreal.nativear.ai.IAICallService>(),
            storyPhaseController = get<com.xreal.nativear.storyteller.IStoryPhaseGate>()
        )
    }

    // --- Expert Domain Registry + Team Manager (Phase 2) ---
    single { com.xreal.nativear.expert.ExpertDomainRegistry() }

    // ★ Phase 19: 전문가 자기진화 시스템 — 신규 싱글톤
    single { com.xreal.nativear.expert.ExpertDynamicProfileStore() }
    single { com.xreal.nativear.expert.ExpertPeerRequestStore(get<UnifiedMemoryDatabase>()) }
    single {
        com.xreal.nativear.expert.ExpertCompositionTracker(
            database = get<UnifiedMemoryDatabase>(),
            outcomeTracker = getOrNull<com.xreal.nativear.learning.OutcomeTracker>()
        )
    }
    single {
        com.xreal.nativear.expert.PeerRequestReviewer(
            requestStore = get<com.xreal.nativear.expert.ExpertPeerRequestStore>(),
            directiveStore = get<com.xreal.nativear.strategist.DirectiveStore>(),
            dynamicProfileStore = get<com.xreal.nativear.expert.ExpertDynamicProfileStore>(),
            eventBus = get()
        )
    }

    single {
        com.xreal.nativear.expert.ExpertTeamManager(
            registry = get(),
            conductor = get(),
            eventBus = get(),
            situationRecognizer = get(),
            contextAggregator = get(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob()),
            compositionTracker = get<com.xreal.nativear.expert.ExpertCompositionTracker>(),  // ★ Phase 19
            edgeContextJudge = get<com.xreal.nativear.edge.EdgeContextJudge>()  // ★ 동적 판단 게이트
        )
    }
    single<com.xreal.nativear.expert.IExpertService> { get<com.xreal.nativear.expert.ExpertTeamManager>() }

    // --- Plan Manager + Plan HUD (Phase 3) ---
    single { com.xreal.nativear.plan.PlanManager(database = get(), eventBus = get()) }
    single<com.xreal.nativear.plan.IPlanService> { get<com.xreal.nativear.plan.PlanManager>() }
    single {
        com.xreal.nativear.plan.PlanHUD(
            planManager = get(),
            eventBus = get(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        )
    }

    // --- Meeting Assistant System ---
    single { com.xreal.nativear.meeting.UserProfileManager(database = get()) }
    single {
        com.xreal.nativear.meeting.ScheduleExtractor(
            aiRegistry = get<com.xreal.nativear.ai.IAICallService>(),
            planManager = get(),
            eventBus = get(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()),
            tokenBudgetTracker = get(),
            personaManager = get<com.xreal.nativear.ai.PersonaManager>(),  // ★ Phase M
            cadenceConfig = get()
        )
    }
    single {
        com.xreal.nativear.meeting.MeetingContextService(
            aiRegistry = get<com.xreal.nativear.ai.IAICallService>(),
            eventBus = get(),
            contextAggregator = get(),
            situationRecognizer = get(),
            scheduleExtractor = get(),
            userProfileManager = get(),
            tokenOptimizer = get<com.xreal.nativear.companion.TokenOptimizer>(),
            tokenBudgetTracker = get(),
            personaManager = get<com.xreal.nativear.ai.PersonaManager>(),  // ★ Phase M
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob()),
            cadenceConfig = get()
        )
    }
    single { com.xreal.nativear.meeting.ReminderScheduler(context = androidContext()) }

    // --- HUD Mode System + Template Engine (Phase 4) ---
    single {
        com.xreal.nativear.hud.HUDTemplateEngine(eventBus = get()).also { engine ->
            // Register domain HUD renderers for template-driven lifecycle
            engine.registerRenderer(get<com.xreal.nativear.plan.PlanHUD>())
            // ★ DebugHUD는 AppBootstrapper.start()에서 lazy 등록 — 순환 의존성 방지
            // HUDTemplateEngine → DebugHUD → ExpertTeamManager → MissionConductor
            //   → MissionAgentRunner → MultiAIOrchestrator → ToolExecutorRegistry → HUDTemplateEngine
        }
    }
    single {
        com.xreal.nativear.hud.HUDModeManager(
            templateEngine = get(),
            eventBus = get(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        )
    }

    // --- Deliberation Manager (Phase 5) ---
    single {
        com.xreal.nativear.deliberation.DeliberationManager(
            database = get(),
            eventBus = get()
        )
    }

    // --- Goal Tracker (Phase 6) ---
    single {
        com.xreal.nativear.goal.GoalTracker(
            database = get(),
            eventBus = get(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        )
    }
    single<com.xreal.nativear.goal.IGoalService> { get<com.xreal.nativear.goal.GoalTracker>() }

    // --- Outcome Tracker (Phase 7) ---
    single {
        com.xreal.nativear.learning.OutcomeTracker(
            database = get(),
            eventBus = get(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob()),
            agentEvolution = get(),   // ★ 순환 해제됨: AgentPersonalityEvolution이 OutcomeTracker 미참조 → 안전
            analyticsService = get()
        )
    }
    single<com.xreal.nativear.learning.IOutcomeRecorder> { get<com.xreal.nativear.learning.OutcomeTracker>() }

    // --- Briefing Service (Phase 8) → StorytellerOrchestrator에 흡수 (방안 B) ---
    // 브리핑 로직은 StorytellerOrchestrator.deliverMorningBriefing/EveningReview로 이전됨

    // --- Token Economy Manager (Phase 9) ---
    single { com.xreal.nativear.monitoring.TokenEconomyManager(database = get()) }

    // --- Debug HUD (Phase 10) ---
    single {
        com.xreal.nativear.hud.DebugHUD(
            eventBus = get(),
            tokenEconomy = get(),
            expertTeamManager = get(),
            deliberationManager = get(),
            situationRecognizer = get(),
            outcomeTracker = get(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        )
    }

    // --- Self-Improving AI Pipeline (Phase 11) ---
    single {
        com.xreal.nativear.evolution.CapabilityManager(
            database = get(),
            eventBus = get(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        )
    }
    single {
        com.xreal.nativear.evolution.EvolutionBridge(
            capabilityManager = get(),
            database = get(),
            domainRegistry = get(),
            tokenEconomy = get(),
            eventBus = get(),
            httpClient = get()
        )
    }

    // --- Batch Processor (Fix 1: token optimization) ---
    single {
        com.xreal.nativear.batch.BatchProcessor(
            tokenEconomy = get(),
            eventBus = get(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        )
    }

    // --- Database Integrity Helper (Fix 3: DB integration) ---
    single {
        com.xreal.nativear.batch.DatabaseIntegrityHelper(
            unifiedDb = get(),
            sceneDb = get(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        )
    }

    // --- Familiarity Engine + Analysis Cache (Phase 12) ---
    single {
        com.xreal.nativear.companion.FamiliarityEngine(
            database = get(),
            sceneDatabase = get(),
            eventBus = get(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        )
    }
    single { com.xreal.nativear.companion.AnalysisCacheManager() }

    // --- Relationship Intelligence (Phase 13) ---
    single {
        com.xreal.nativear.companion.RelationshipTracker(
            database = get(),
            sceneDatabase = get(),
            familiarityEngine = get(),
            eventBus = get(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        )
    }

    // --- Novelty Engine + Token Optimizer (Phase 14) ---
    single {
        com.xreal.nativear.companion.NoveltyEngine(
            familiarityEngine = get(),
            analysisCacheManager = get(),
            tokenEconomy = get(),
            eventBus = get(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        )
    }
    single {
        com.xreal.nativear.companion.TokenOptimizer(
            tokenEconomy = get(),
            analysisCacheManager = get(),
            familiarityEngine = get(),
            // ★ CapabilityTier 공급자: FailsafeController.currentTier를 lazy 참조
            // FailsafeController가 없으면 null → 기존 동작 유지 (TIER_0_FULL)
            capabilityTierProvider = {
                try {
                    getKoin().getOrNull<com.xreal.nativear.resilience.FailsafeController>()
                        ?.currentTier ?: com.xreal.nativear.core.CapabilityTier.TIER_0_FULL
                } catch (_: Exception) {
                    com.xreal.nativear.core.CapabilityTier.TIER_0_FULL
                }
            }
        )
    }

    // --- Coach AI Engine (Phase 15) ---
    single {
        com.xreal.nativear.companion.CoachEngine(
            familiarityEngine = get(),
            relationshipTracker = get(),
            goalTracker = get(),
            outcomeTracker = get(),
            database = get(),
            eventBus = get(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        )
    }

    // --- Agent Personality Evolution + Meta Manager (Phase 16) ---
    single {
        com.xreal.nativear.companion.AgentPersonalityEvolution(
            database = get(),
            tokenEconomy = get(),
            eventBus = get(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        )
    }
    single {
        com.xreal.nativear.companion.AgentMetaManager(
            agentEvolution = get(),
            outcomeTracker = get(),
            tokenEconomy = get(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        )
    }

    // DirectiveConsumer (전략가 지시사항 → CadenceConfig/MissionConductor/DeviceModeManager)
    single {
        com.xreal.nativear.strategist.DirectiveConsumer(
            directiveStore = get<com.xreal.nativear.strategist.DirectiveStore>(),
            cadenceConfig = get<com.xreal.nativear.cadence.CadenceConfig>(),
            missionConductor = getOrNull<com.xreal.nativear.mission.MissionConductor>(),
            deviceModeManager = getOrNull<com.xreal.nativear.monitoring.DeviceModeManager>(),
            eventBus = get(),
            // ★ Phase 19: 전문가 팀 조합 지시사항 처리
            expertTeamManager = getOrNull<com.xreal.nativear.expert.ExpertTeamManager>()
        )
    }

    // Strategist Service (with Digital Twin integration + DirectiveConsumer)
    single {
        com.xreal.nativear.strategist.StrategistService(
            reflector = get<com.xreal.nativear.strategist.StrategistReflector>(),
            directiveStore = get<com.xreal.nativear.strategist.DirectiveStore>(),
            personaMemoryService = get<com.xreal.nativear.ai.PersonaMemoryService>(),
            personaManager = get<com.xreal.nativear.ai.PersonaManager>(),
            database = get<UnifiedMemoryDatabase>(),
            digitalTwinBuilder = get(),
            expertTeamManager = get(),
            analyticsService = get(),
            directiveConsumer = get(),  // ★ 지시사항 소비자 연결 (핵심 수정)
            // ★ Phase 19: 전문가 자기진화 시스템
            compositionTracker = get<com.xreal.nativear.expert.ExpertCompositionTracker>(),
            peerRequestStore = get<com.xreal.nativear.expert.ExpertPeerRequestStore>(),
            peerRequestReviewer = get<com.xreal.nativear.expert.PeerRequestReviewer>(),
            // ★ AdaptiveReflectionTrigger — 이벤트 기반 동적 반성 주기
            adaptiveReflectionTrigger = get<com.xreal.nativear.agent.AdaptiveReflectionTrigger>(),
            // ★ ProactiveScheduler — 중앙 스케줄러 (자체 while 루프 대체)
            proactiveScheduler = get<com.xreal.nativear.ai.ProactiveScheduler>()
        )
    }

    // --- Adaptive Cadence System ---
    single { com.xreal.nativear.cadence.CadenceConfig() }
    single {
        com.xreal.nativear.cadence.UserStateTracker(
            eventBus = get(),
            decisionLogger = get(),
            locationService = get(),
            cadenceConfig = get()
        ).also { tracker ->
            tracker.start()
            // Wire location familiarity checker (lazy to avoid circular dependency)
            tracker.locationFamiliarityChecker = { lat, lon ->
                try { get<com.xreal.nativear.cadence.DigitalTwinBuilder>().isLocationFamiliar(lat, lon) } catch (_: Exception) { false }
            }
        }
    }
    single {
        com.xreal.nativear.cadence.AdaptiveCadenceController(
            userStateTracker = get(),
            cadenceConfig = get(),
            directiveStore = get()
        ).also { it.start() }
    }

    // Translation + Needs Detection
    single {
        com.xreal.nativear.cadence.TranslationService(
            aiRegistry = get<com.xreal.nativear.ai.IAICallService>(),
            batchProcessor = get(),
            tokenBudgetTracker = get()
        )
    }
    single {
        com.xreal.nativear.cadence.NeedsDetector(
            eventBus = get(),
            decisionLogger = get(),
            translationService = get(),
            cadenceConfig = get(),
            userStateTracker = get()
        ).also { it.start() }
    }

    // --- Mission-Based Sub-Agent Team System ---
    single {
        com.xreal.nativear.mission.MissionAgentRunner(
            orchestrator = get(),
            personaManager = get(),
            eventBus = get(),
            outcomeTracker = get(),
            edgeContextJudge = get<com.xreal.nativear.edge.EdgeContextJudge>(),  // ★ 동적 판단 게이트
            goalAgentLoop = getOrNull()  // ★ GoalOrientedAgentLoop 심층 추론
        )
    }
    single {
        com.xreal.nativear.mission.MissionConductor(
            agentRunner = get(),
            aiRegistry = get<com.xreal.nativear.ai.IAICallService>(),
            tokenBudgetTracker = get(),
            eventBus = get(),
            userStateTracker = get(),
            database = get(),
            analyticsService = get(),
            // ★ Phase L: PersonaManager 싱글톤 — 사용자 컨텍스트(DNA·프로필·기억) 주입
            personaManager = get<com.xreal.nativear.ai.PersonaManager>(),
            // ★ Fix 3: OutcomeTracker — replan 시 피드백 루프 주입
            outcomeTracker = get()
        )
    }
    single<com.xreal.nativear.mission.IMissionService> { get<com.xreal.nativear.mission.MissionConductor>() }
    single {
        com.xreal.nativear.mission.MissionDetectorRouter(
            eventBus = get(),
            decisionLogger = get(),
            userStateTracker = get(),
            conductor = get()
        ).also { it.start() }
    }
    single {
        com.xreal.nativear.mission.MissionDirectiveHandler(
            directiveStore = get(),
            conductor = get()
        ).also { it.start() }
    }

    // Router Infrastructure
    single { com.xreal.nativear.router.DecisionLogger(get<com.xreal.nativear.memory.api.IMemoryStore>()).also { it.start() } }

    // Persona Router (smart persona selection with budget awareness)
    single {
        com.xreal.nativear.router.persona.PersonaRouter(
            personaManager = get<com.xreal.nativear.ai.PersonaManager>(),
            tokenBudgetTracker = get()
        )
    }

    // Persona Trigger Router (event-based auto-dispatch)
    single {
        com.xreal.nativear.router.persona.PersonaTriggerRouter(
            eventBus = get(),
            decisionLogger = get(),
            orchestrator = get(),
            personaRouter = get()
        ).also { it.start() }
    }

    // Running Coach Routers
    single { com.xreal.nativear.router.running.FormRouter(eventBus = get(), decisionLogger = get()) }
    single { com.xreal.nativear.router.running.IntensityRouter(eventBus = get(), decisionLogger = get()) }
    single { com.xreal.nativear.router.running.InterventionRouter(eventBus = get(), decisionLogger = get()) }

    // Running Coach (with router dependencies)
    single {
        com.xreal.nativear.running.RunningCoachManager(
            context = androidContext(),
            eventBus = get(),
            locationManager = get(),
            weatherService = get(),
            formRouter = get(),
            intensityRouter = get(),
            interventionRouter = get(),
            database = get()
        )
    }

    // --- Spatial Anchoring System ---
    single {
        com.xreal.nativear.spatial.PathTracker(
            eventBus = get(),
            log = { msg -> android.util.Log.i("PathTracker", msg) }
        )
    }

    single {
        com.xreal.nativear.spatial.AnchorPersistence(
            spatialDatabase = get<com.xreal.nativear.spatial.ISpatialDatabase>(),
            log = { msg -> android.util.Log.i("AnchorPersist", msg) }
        )
    }

    single {
        com.xreal.nativear.spatial.PlaceRecognitionManager(
            spatialDatabase = get<com.xreal.nativear.spatial.ISpatialDatabase>(),
            imageEmbedder = get(),
            pathTracker = get(),
            anchorPersistence = get(),
            eventBus = get(),
            log = { msg -> android.util.Log.i("PlaceRecog", msg) }
        )
    }

    single {
        com.xreal.nativear.spatial.SpatialAnchorManager(
            eventBus = get(),
            stereoDepthEngine = get<com.xreal.hardware.XRealHardwareManager>().getStereoDepthEngine(),
            log = { msg -> android.util.Log.i("SpatialAnchor", msg) },
            placeRecognitionManager = get()
        )
    }

    // --- Spatial UI Manager (3D 공간 UI 전환) ---
    single {
        com.xreal.nativear.spatial.SpatialUIManager(eventBus = get())
    }

    // --- Proactive Memory Surfacing ---
    single {
        com.xreal.nativear.memory.ProactiveMemorySurfacer(
            eventBus = get(),
            memoryStore = get(),
            sceneDatabase = get(),
            locationService = get(),
            spatialUIManager = get(),
            spatialAnchorManager = get()
        )
    }

    // --- Hand Tracking + Interactive AR System ---
    single {
        val model = com.xreal.nativear.hand.HandTrackingModel(get())
        get<UnifiedAIOrchestrator>().registerModel("HandTracking", model)
        model
    }

    single { com.xreal.nativear.hand.HandGestureRecognizer(
        log = { msg -> android.util.Log.i("HandGesture", msg) }
    ) }

    single { com.xreal.nativear.interaction.HUDPhysicsEngine() }

    single {
        com.xreal.nativear.interaction.HandInteractionManager(
            eventBus = get(),
            physicsEngine = get(),
            gestureRecognizer = get(),
            log = { msg -> android.util.Log.i("HandInteraction", msg) }
        )
    }

    single {
        com.xreal.nativear.interaction.InteractionTemplateManager(
            sceneDatabase = get(),
            log = { msg -> android.util.Log.i("InteractionTmpl", msg) }
        )
    }

    // --- VIO Drift Correction System ---
    single {
        com.xreal.nativear.spatial.MagneticHeadingProvider(androidContext())
    }

    single {
        com.xreal.nativear.spatial.VisualLoopCloser(
            imageEmbedder = get(),
            log = { msg -> android.util.Log.i("LoopCloser", msg) }
        )
    }

    single {
        com.xreal.nativear.spatial.DriftCorrectionManager(
            eventBus = get(),
            magneticHeadingProvider = get(),
            visualLoopCloser = get(),
            log = { msg -> android.util.Log.i("DriftCorrect", msg) }
        )
    }

    // Bootstrapper
    factory { (scope: kotlinx.coroutines.CoroutineScope) ->
        AppBootstrapper(
            context = androidContext(),
            scope = scope,
            hardwareManager = get(),
            voiceManager = get(),
            visionManager = get(),
            locationManager = get(),
            aiOrchestrator = get(),
            imageEmbedder = get(),
            eventBus = get(),
            gestureManager = get(),
            interactionTracker = get(),
            speakerDiarizer = get(),
            personSyncManager = get(),
            strategistService = get()
        )
    }

    // GestureManager: Head gesture + multi-tap detection
    single { GestureManager(get()) }

    // Core Objects
    single { GlobalEventBus() }
    single { ExecutionFlowMonitor() }
    single { SequenceTracer(get()) }
    single { ErrorReporter(get()) }

    // LifeTornadoEngine — 10년 인생 시뮬레이션 🌪️
    single { com.xreal.nativear.core.LifeTornadoEngine(get()) }

    // ChaosMonkey — 카오스 엔지니어링 / ADB 트리거 이벤트 폭풍 테스트
    single { com.xreal.nativear.core.ChaosMonkey(get(), get()) }
    
    // Coordinators
    single {
        com.xreal.nativear.core.InputCoordinator(
            eventBus = get(),
            aiAgentManager = get(),
            palmFaceGestureDetector = getOrNull(),
            resourceProposalManager = getOrNull()
        )
    }
    
    single { VisionServiceDelegate { null } }
    
    single { 
        com.xreal.nativear.core.OutputCoordinator(
            context = androidContext(),
            eventBus = get(),
            voiceManager = get()
        )
    }

    single {
        com.xreal.nativear.core.VisionCoordinator(
            eventBus = get(),
            visionManager = get(),
            aiAgentManager = get(),
            tokenOptimizer = get<com.xreal.nativear.companion.TokenOptimizer>(),
            focusModeManager = getOrNull(),
            // ★ Phase F-5: 숙련도 사다리 + 노블티 연결
            // isRoutine/isNoveltyTime TODO 해결 — SituationLifecycleManager + NoveltyEngine 실주입
            situationLifecycleManager = getOrNull(),
            noveltyEngine = getOrNull(),
            situationRecognizer = getOrNull(),
            locationManager = getOrNull()
        )
    }

    // ─── 적응형 회복력 시스템 (Part C) ───

    // FocusMode 시스템
    single { com.xreal.nativear.focus.FocusModeManager(get()) }
    single {
        com.xreal.nativear.focus.PalmFaceGestureDetector(
            eventBus = get(),
            focusModeManager = get()
        )
    }

    // Resilience 패키지
    single {
        com.xreal.nativear.resilience.DeviceHealthMonitor(
            eventBus = get(),
            hardwareManager = get(),
            visionManager = get(),
            edgeModelManager = get(),
            connectivityMonitor = get()
        )
    }
    single {
        com.xreal.nativear.resilience.FailsafeController(
            eventBus = get(),
            edgeDelegationRouter = getOrNull(),
            minimalOperationMode = getOrNull()
        )
    }
    // OperationalDirector → 삭제 (빈 껍데기, SystemConductor로 이전됨, 방안 B)
    // ★ Phase E: SystemConductor — 시스템 하모니 지휘자 (단일 권한자)
    // 5개 모니터링 섹션의 충돌을 해결하고 HarmonyDecision을 발행하는 통합 조율자
    single {
        com.xreal.nativear.resilience.SystemConductor(
            eventBus = get(),
            situationRecognizer = getOrNull(),
            focusModeManager = getOrNull()
        )
    }

    // ★ Phase F-1: SituationLifecycleManager — 상황별 숙련도 사다리 관리자
    // 24개 LifeSituation의 UNKNOWN→LEARNING→ROUTINE→MASTERED 전환 추적
    // AI 처리 링(MISSION_TEAM→API_SINGLE→WARMUP_CACHE→LOCAL_ML) 결정
    // F-2 SituationPredictor, F-3 AgentWarmupWorker, F-6 RoutineClassifier 연결점
    single {
        com.xreal.nativear.companion.SituationLifecycleManager(
            eventBus = get(),
            database = get()
        )
    }

    // ★ Phase F-2: SituationPredictor — 24시간 상황 예측기
    // 요일+시간별 상황 관찰 빈도 누적 → 확률 계산 → 다음 24h 예측 목록 생성
    // WorkManager(SituationPredictionWorker)가 매일 01:00 generatePredictions() 실행
    // F-3 AgentWarmupScheduler가 예측 결과 소비 → 워밍업 WorkManager 예약
    single {
        com.xreal.nativear.companion.SituationPredictor(
            eventBus = get(),
            database = get(),
            lifecycleManager = get(),
            digitalTwinBuilder = get()
        )
    }

    // ★ Phase F-3: AgentWarmupScheduler — 예측 기반 에이전트 워밍업 예약자
    // SituationPredictor 예측 결과 + SituationMasteryChanged 이벤트 구독
    // → 상황 X분 전에 AgentWarmupWorker(WorkManager) 예약
    // → 담당 에이전트별 warmupPromptTemplate → Gemini 호출 → MemorySaveHelper 저장
    single {
        com.xreal.nativear.companion.AgentWarmupScheduler(
            context = androidContext(),
            eventBus = get(),
            predictor = get()
        )
    }

    // ★ Phase F-4: KnowledgePrefetcher — 에이전트 도메인 지식 선제 적재
    // 각 에이전트의 knowledgeRefreshIntervalDays 주기로 배경 지식 사전 생성
    // role="KNOWLEDGE", personaId=agentId → PersonaManager 메모리 주입 경로 재사용
    // WorkManager(KnowledgePrefetchWorker) 24h 주기 (네트워크 필요, 배터리 부족 시 건너뜀)
    single {
        com.xreal.nativear.companion.KnowledgePrefetcher(
            aiRegistry = get<com.xreal.nativear.ai.IAICallService>(),
            memoryStore = get(),
            database = get()
        )
    }

    single {
        com.xreal.nativear.resilience.MinimalOperationMode(
            context = androidContext(),
            eventBus = get()
        )
    }
    single {
        // ★ EmergencyOrchestrator — AI 추론 기반 에러 상황 우회 지휘자 (Phase 19)
        // Layer 2: FailsafeController(즉각) → EmergencyOrchestrator(3s) → StrategistService(5min)
        com.xreal.nativear.resilience.EmergencyOrchestrator(
            eventBus = get(),
            edgeDelegationRouter = getOrNull(),
            capabilityManager = getOrNull()
        )
    }

    // ★ ResourceGuardian — AI 기반 자원 수호자 (좀비 루프 근절, 정책 기반 자원 관리)
    single {
        com.xreal.nativear.resilience.ResourceGuardian(
            eventBus = get(),
            policyRegistry = get(),
            aiRegistry = getOrNull()
        )
    }

    // Resource 패키지 (자재 창고 시스템)
    single {
        com.xreal.nativear.resource.ResourceRegistry(
            eventBus = get()
        )
    }
    single {
        com.xreal.nativear.resource.ResourceProposalManager(
            eventBus = get(),
            resourceRegistry = get()
        )
    }
    single {
        com.xreal.nativear.resource.ResourceToolExecutor(
            resourceRegistry = get(),
            resourceProposalManager = get()
        )
    }

    // CompanionDeviceManager (Nearby Connections)
    // ★ Phase 2: visionManager + deviceHealthMonitor 추가 — 카메라 프레임 → feedExternalFrame, fold3Connected 동기화
    single {
        com.xreal.nativear.companion.CompanionDeviceManager(
            context = androidContext(),
            eventBus = get(),
            resourceRegistry = get(),
            visionManager = get(),
            deviceHealthMonitor = get()
        )
    }

    // ─── 학습 파이프라인 (decision_log → Drive → Colab → .tflite → RoutineClassifier) ───
    single {
        com.xreal.nativear.learning.GoogleDriveAuthManager(
            context = androidContext(),
            httpClient = get()
        ).also { manager ->
            // local.properties → BuildConfig → EncryptedSharedPreferences 자동 저장
            val clientId = com.xreal.nativear.BuildConfig.GOOGLE_DRIVE_CLIENT_ID
            val clientSecret = com.xreal.nativear.BuildConfig.GOOGLE_DRIVE_CLIENT_SECRET
            if (clientId.isNotEmpty()) {
                manager.setClientCredentials(clientId, clientSecret)
                android.util.Log.i("AppModule", "GoogleDriveAuthManager: client_id 설정됨 (secret=${if (clientSecret.isNotEmpty()) "있음" else "없음"})")
            }
        }
    }
    single {
        com.xreal.nativear.learning.DriveApiClient(
            httpClient = get(),
            authManager = get()
        )
    }
    single {
        com.xreal.nativear.learning.TrainingDataExporter(
            context = androidContext(),
            database = get()
        )
    }
    single {
        com.xreal.nativear.learning.ConversationDataExporter(
            context = androidContext(),
            database = get()
        )
    }
    single {
        com.xreal.nativear.learning.RoutineClassifier(
            context = androidContext()
        )
    }
    single {
        com.xreal.nativear.learning.DriveTrainingScheduler(
            context = androidContext()
        )
    }

    // ─── ★ Phase H: 훈련 준비도 + 일일 가치 리포트 + 피드백 세션 + UserDNA ───

    // H-1: 훈련 준비도 게이트 (90일 + 데이터 충분 조건)
    single { com.xreal.nativear.learning.TrainingReadinessChecker(database = get()) }

    // H-4: 사용자 성향 DNA 관리자
    single { com.xreal.nativear.profile.UserDNAManager(database = get()) }

    // H-2a: 일일 가치 리포트 측정/저장
    single {
        com.xreal.nativear.monitoring.DailyValueReporter(
            database = get<UnifiedMemoryDatabase>(),
            tokenEconomyManager = get<com.xreal.nativear.monitoring.TokenEconomyManager>(),
            eventBus = get()
        )
    }

    // H-3: 음성 트리거 피드백 세션
    single {
        com.xreal.nativear.session.FeedbackSessionManager(
            aiRegistry = get<com.xreal.nativear.ai.IAICallService>(),
            userDnaManager = get<com.xreal.nativear.profile.UserDNAManager>(),
            dailyValueReporter = get<com.xreal.nativear.monitoring.DailyValueReporter>(),
            eventBus = get(),
            personaManager = get<com.xreal.nativear.ai.PersonaManager>()  // ★ Phase M
        )
    }

    // ─── ★ StoryPhaseController — 시스템 전체 상태 머신 (자율행동 유일한 허가 주체) ───
    single { com.xreal.nativear.storyteller.StoryPhaseController() }
    single<com.xreal.nativear.storyteller.IStoryPhaseGate> { get<com.xreal.nativear.storyteller.StoryPhaseController>() }

    // ─── ★ Storyteller Orchestrator — 내러티브 + 브리핑 + 세션 + 상태 머신 통합 엔진 ───
    // BriefingService, LifeSessionManager 역할 흡수 (방안 B)
    single {
        com.xreal.nativear.storyteller.StorytellerOrchestrator(
            eventBus = get(),
            contextAggregator = get(),
            aiRegistry = get<com.xreal.nativear.ai.IAICallService>(),
            memoryStore = get(),
            memorySearcher = get(),
            proactiveScheduler = get(),
            planManager = get(),
            goalTracker = get(),
            outcomeTracker = get(),
            phaseController = get()
        )
    }
}


