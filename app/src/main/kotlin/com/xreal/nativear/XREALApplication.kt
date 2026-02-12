package com.xreal.nativear

import android.app.Application
import com.xreal.nativear.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class XREALApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        startKoin {
            androidContext(this@XREALApplication)
            modules(appModule)
        }
    }
}
