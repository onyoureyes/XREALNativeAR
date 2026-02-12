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
    single { MemoryRepository(androidContext(), get(), get(), get(), get()) }

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
    single<IMemoryService> { MemoryRepository(androidContext(), get()) }
    single { CloudBackupManager(androidContext()) }

    
    // Services
    single { NaverService() }
    single<ISearchService> { get<NaverService>() }
    single<INavigationService> { get<NaverService>() }
    single { WeatherService() }
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
    factory { (context: android.content.Context, cameraExecutor: java.util.concurrent.ExecutorService, callback: VisionManager.VisionCallback) ->
        VisionManager(context, cameraExecutor, callback, get(), get(), get())
    }
    factory<IVisionService> { (context: android.content.Context, cameraExecutor: java.util.concurrent.ExecutorService, callback: VisionManager.VisionCallback) ->
        get<VisionManager> { org.koin.core.parameter.parametersOf(context, cameraExecutor, callback) }
    }

    factory { (context: android.content.Context, callback: VoiceManager.VoiceCallback) ->
        VoiceManager(context, get(), callback)
    }
    factory<IVoiceService> { (context: android.content.Context, callback: VoiceManager.VoiceCallback) ->
        get<VoiceManager> { org.koin.core.parameter.parametersOf(context, callback) }
    }

    factory { (context: android.content.Context, scope: androidx.lifecycle.LifecycleCoroutineScope, callback: HardwareManager.HardwareCallback) ->
        HardwareManager(context, scope, callback, get())
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
            callback = callback

        )
    }




    
    // Core Objects
    single { GlobalEventBus() }
    single { GestureHandler { action -> /* Handled in CoreEngine */ } }
    single { VisionServiceDelegate { null /* Placeholder, set by CoreEngine */ } }

}


