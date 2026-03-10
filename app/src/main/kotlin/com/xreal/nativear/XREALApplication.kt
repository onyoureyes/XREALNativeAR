package com.xreal.nativear

import android.app.Application
import android.util.Log
import com.xreal.nativear.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.opencv.android.OpenCVLoader

class XREALApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // ★ ExecutionFlowMonitor 크래시 핸들러 → AppBootstrapper.start()로 이동 (Koin 후 실행)
        // Koin init 중 크래시는 flow event가 0개이므로 dump할 것 없음 → 수용 가능

        // Initialize OpenCV native library BEFORE Koin (DI singletons may use OpenCV types)
        if (OpenCVLoader.initDebug()) {
            Log.i("XREALApplication", "OpenCV initialized successfully")
        } else {
            Log.e("XREALApplication", "OpenCV initialization FAILED")
        }

        startKoin {
            androidContext(this@XREALApplication)
            modules(appModule)
        }
    }
}
