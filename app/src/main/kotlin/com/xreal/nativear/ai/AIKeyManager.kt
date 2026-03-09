package com.xreal.nativear.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AIKeyManager(context: Context) {
    private val TAG = "AIKeyManager"

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "ai_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w(TAG, "EncryptedSharedPreferences unavailable, using standard prefs: ${e.message}")
        context.getSharedPreferences("ai_keys", Context.MODE_PRIVATE)
    }

    fun setApiKey(providerId: ProviderId, key: String) {
        prefs.edit().putString("key_${providerId.name}", key).apply()
        Log.i(TAG, "API key set for ${providerId.name}")
    }

    fun getApiKey(providerId: ProviderId): String? {
        return prefs.getString("key_${providerId.name}", null)
    }

    fun hasApiKey(providerId: ProviderId): Boolean {
        return getApiKey(providerId)?.isNotBlank() == true
    }

    fun clearApiKey(providerId: ProviderId) {
        prefs.edit().remove("key_${providerId.name}").apply()
    }

    fun getAllConfiguredProviders(): List<ProviderId> {
        return ProviderId.values().filter { hasApiKey(it) }
    }
}
