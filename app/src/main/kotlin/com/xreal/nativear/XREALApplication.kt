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

        // ★ ExecutionFlowMonitor 크래시 핸들러 — 가장 먼저 설치 (네이티브 크래시 포함)
        // 크래시 시 마지막 50개 FlowEvent를 ASCII 타임라인으로 logcat 출력 (TAG=FlowMon)
        com.xreal.nativear.core.ExecutionFlowMonitor.installCrashHandler()

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
