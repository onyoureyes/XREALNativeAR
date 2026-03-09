package com.xreal.nativear

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * PersonSyncManager: Handles person data synchronization with the server.
 *
 * Current implementation: Local file-based staging (no server yet).
 * - Exports person profiles, face embeddings, and voice profiles to JSON
 * - Staged files can be uploaded when server is available
 * - Imports server responses (clustered person merges, updated models)
 *
 * Future: Replace file I/O with HTTP client (Retrofit/OkHttp) + server API.
 */
class PersonSyncManager(
    private val context: Context,
    private val sceneDatabase: SceneDatabase,
    private val cloudBackupManager: CloudBackupManager
) {
    private val TAG = "PersonSyncManager"
    private val SYNC_DIR = "person_sync"
    private val LAST_SYNC_KEY = "person_last_sync_time"

    private val prefs by lazy {
        context.getSharedPreferences("person_sync_prefs", Context.MODE_PRIVATE)
    }

    private var lastSyncTime: Long
        get() = prefs.getLong(LAST_SYNC_KEY, 0L)
        set(value) = prefs.edit().putLong(LAST_SYNC_KEY, value).apply()

    /**
     * Export face data for a specific person to a staged sync file.
     * Prepares embeddings + metadata for server upload.
     */
    suspend fun exportFaceData(personId: Long): File? {
        return try {
            val person = sceneDatabase.getPersonById(personId)
            if (person == null) {
                Log.w(TAG, "Person not found: $personId")
                return null
            }

            val syncDir = File(context.filesDir, SYNC_DIR).apply { mkdirs() }
            val file = File(syncDir, "person_${personId}_faces.json")

            val json = JSONObject().apply {
                put("personId", person.id)
                put("name", person.name)
                put("relationship", person.relationship)
                put("registeredAt", person.registeredAt)
                put("exportedAt", System.currentTimeMillis())

                // Face embeddings as base64
                val facesArray = JSONArray()
                // Note: face records fetched via DB query (future: add getFacesByPerson)
                put("faces", facesArray)
            }

            file.writeText(json.toString(2))
            Log.i(TAG, "Exported face data for ${person.name} (id=$personId)")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export face data", e)
            null
        }
    }

    /**
     * Delta sync: export all persons modified since last sync.
     * Creates a staged sync bundle ready for server upload.
     */
    suspend fun syncPersonProfiles(): SyncResult {
        return try {
            val syncTime = lastSyncTime
            val allPersons = sceneDatabase.getAllPersons()

            // Filter to recently registered persons (since last sync)
            val newPersons = allPersons.filter { it.registeredAt > syncTime }

            if (newPersons.isEmpty()) {
                Log.d(TAG, "No new persons since last sync")
                return SyncResult(success = true, uploadedCount = 0, message = "No changes")
            }

            val syncDir = File(context.filesDir, SYNC_DIR).apply { mkdirs() }
            val bundleFile = File(syncDir, "sync_bundle_${System.currentTimeMillis()}.json")

            val bundle = JSONObject().apply {
                put("syncTime", System.currentTimeMillis())
                put("lastSyncTime", syncTime)
                put("deviceId", android.provider.Settings.Secure.getString(
                    context.contentResolver, android.provider.Settings.Secure.ANDROID_ID))

                val personsArray = JSONArray()
                for (person in newPersons) {
                    personsArray.put(JSONObject().apply {
                        put("id", person.id)
                        put("name", person.name)
                        put("relationship", person.relationship)
                        put("registeredAt", person.registeredAt)
                        put("photoCount", person.photoCount)
                    })
                }
                put("persons", personsArray)
                put("totalPersonCount", allPersons.size)
            }

            bundleFile.writeText(bundle.toString(2))
            lastSyncTime = System.currentTimeMillis()

            Log.i(TAG, "Sync bundle created: ${newPersons.size} new persons")
            SyncResult(
                success = true,
                uploadedCount = newPersons.size,
                message = "Staged ${newPersons.size} persons for upload"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            SyncResult(success = false, uploadedCount = 0, message = e.message ?: "Unknown error")
        }
    }

    /**
     * Import server sync response (person merges, updated names, etc.).
     * Future: called after downloading server response.
     */
    suspend fun importServerResponse(responseJson: String) {
        try {
            val json = JSONObject(responseJson)
            val merges = json.optJSONArray("merges")
            if (merges != null) {
                for (i in 0 until merges.length()) {
                    val merge = merges.getJSONObject(i)
                    val personId = merge.getLong("personId")
                    val newName = merge.optString("name")
                    val relationship = merge.optString("relationship")
                    if (newName.isNotBlank()) {
                        sceneDatabase.updatePersonName(personId, newName, relationship.ifBlank { null })
                        Log.i(TAG, "Server merge: person $personId → $newName")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import server response", e)
        }
    }

    /**
     * Full sync: export + profiles + (future) model download.
     */
    suspend fun fullSync(): SyncResult {
        val profileResult = syncPersonProfiles()
        // Future: downloadUpdatedModel()
        return profileResult
    }

    /**
     * Get sync status info for diagnostics.
     */
    fun getSyncStatus(): JSONObject {
        val syncDir = File(context.filesDir, SYNC_DIR)
        val stagedFiles = syncDir.listFiles()?.size ?: 0
        return JSONObject().apply {
            put("lastSyncTime", lastSyncTime)
            put("stagedFiles", stagedFiles)
            put("totalPersons", sceneDatabase.getAllPersons().size)
        }
    }

    data class SyncResult(
        val success: Boolean,
        val uploadedCount: Int,
        val message: String
    )
}
