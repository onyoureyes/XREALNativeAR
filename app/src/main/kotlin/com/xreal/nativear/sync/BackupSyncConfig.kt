package com.xreal.nativear.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Configuration for async server backup.
 * Persisted in SharedPreferences so it survives app restarts.
 * Disabled by default — user must configure server URL and API key to enable.
 */
class BackupSyncConfig(context: Context) {
    private val TAG = "BackupSyncConfig"
    private val prefs: SharedPreferences =
        context.getSharedPreferences("xreal_backup_sync", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(value) = prefs.edit().putString("server_url", value).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value).apply()

    var syncIntervalMinutes: Long
        get() = prefs.getLong("sync_interval_minutes", 60)
        set(value) = prefs.edit().putLong("sync_interval_minutes", value).apply()

    var requireWifi: Boolean
        get() = prefs.getBoolean("require_wifi", false)
        set(value) = prefs.edit().putBoolean("require_wifi", value).apply()

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    val isConfigured: Boolean
        get() = enabled && serverUrl.isNotBlank() && apiKey.isNotBlank()

    /**
     * Configure and enable backup in one call.
     */
    fun configure(serverUrl: String, apiKey: String) {
        this.serverUrl = serverUrl
        this.apiKey = apiKey
        this.enabled = true
        Log.i(TAG, "Backup configured: $serverUrl (key: ${apiKey.take(8)}...)")
    }

    fun toSummary(): String = if (isConfigured) {
        "Backup: ON → $serverUrl (every ${syncIntervalMinutes}min, wifi=${requireWifi})"
    } else {
        "Backup: OFF"
    }
}
