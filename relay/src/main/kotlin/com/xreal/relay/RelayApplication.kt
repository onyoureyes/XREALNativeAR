package com.xreal.relay

import android.app.Application
import android.util.Log

class RelayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("RelayApp", "XREAL Relay Application started")
    }
}
