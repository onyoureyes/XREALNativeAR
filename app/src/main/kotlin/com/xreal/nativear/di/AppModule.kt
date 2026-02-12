package com.xreal.nativear.di

import com.xreal.nativear.*
import com.xreal.nativear.core.*
import com.xreal.ai.UnifiedAIOrchestrator
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.util.concurrent.Executors

val appModule = module {
    // Core dependencies
    single { UnifiedMemoryDatabase(androidContext()) }
    single { MemoryCompressor(get(), get()) }

    single { MemorySearcher(androidContext(), get()) }
    single { MemoryRepository(androidContext(), get(), get(), get(), get(), get()) }
    single<IMemoryService> { get<MemoryRepository>() }

    single { GeminiClient("AIzaSyAQbpllbZGGrBNjgbt8T96ZqGCeEsvn3cU") }
    single { UnifiedAIOrchestrator(androidContext()) }

    // Models & Helpers
    single { TextEmbedder(androidContext()) }
    
    // Converted LocationManager to Single (Shared Instance)
    single { 
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        LocationManager(androidContext(), scope, get(), get()) 
    }

    single<ILocationService> { get<LocationManager>() }


    single { com.xreal.hardware.XRealHardwareManager(androidContext()) }



    
    // Engine Components (Restored Phase 4+)
    single { WhisperEngine(androidContext()) }
    single { 
        val model = LiteRTWrapper(androidContext())
        get<UnifiedAIOrchestrator>().registerModel("LiteRT_YOLO", model)
        model
    }
    single { ImageEmbedder(androidContext()) }
    single { CloudBackupManager(androidContext()) }
    single { EmotionClassifier() }
    single { EmotionTTSService(androidContext(), get()) }

    
    // Services
    single { NaverService() }
    single<ISearchService> { get<NaverService>() }
    single<INavigationService> { get<NaverService>() }
    single { WeatherService(androidContext()) }
    single<IWeatherService> { get<WeatherService>() }
    
    // Models
    single { 
        val model = SystemTTSAdapter(androidContext())
        get<UnifiedAIOrchestrator>().registerModel("SystemTTS", model)
        model
    }
    single { 
        val model = OCRModel() 
        get<UnifiedAIOrchestrator>().registerModel("OCR", model)
        model
    }
    single { 
        val model = PoseEstimationModel(androidContext())
        get<UnifiedAIOrchestrator>().registerModel("PoseEstimation", model)
        model
    }
    // Managers
    factory { (context: android.content.Context, cameraExecutor: java.util.concurrent.ExecutorService) ->
        VisionManager(context, cameraExecutor, get(), get(), get(), get(), get(), get())
    }
    factory<IVisionService> { (context: android.content.Context, cameraExecutor: java.util.concurrent.ExecutorService) ->
        get<VisionManager> { org.koin.core.parameter.parametersOf(context, cameraExecutor) }
    }

    factory { 
        VoiceManager(androidContext(), get(), get())
    }
    factory<IVoiceService> { 
        get<VoiceManager>()
    }

    factory { (context: android.content.Context, scope: kotlinx.coroutines.CoroutineScope) ->
        HardwareManager(context, scope, get(), get())
    }

    factory { (context: android.content.Context, scope: androidx.lifecycle.LifecycleCoroutineScope, callback: AIAgentManager.AIAgentCallback) ->
        AIAgentManager(
            context = context,
            scope = scope,
            geminiClient = get(),
            memoryService = get(),
            searchService = get(),
            weatherService = get(),
            navigationService = get(),
            visionService = get { org.koin.core.parameter.parametersOf(context, java.util.concurrent.Executors.newSingleThreadExecutor(), callback) },
            aiOrchestrator = get(),
            locationService = get(),
            cloudBackupManager = get(),
            eventBus = get(),
            callback = callback
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
            eventBus = get()
        )
    }

    // Core Objects
    single { GlobalEventBus() }
    
    // Coordinators
    single { com.xreal.nativear.core.InputCoordinator(get()) }
    
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
            aiAgentManager = get()
        )
    }
}


