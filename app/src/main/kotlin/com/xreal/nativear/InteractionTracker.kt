package com.xreal.nativear

import android.util.Log
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * InteractionTracker: Links speech events to visible persons.
 *
 * Strategy: "Camera-visible person = current speaker" (simple attribution).
 * - PersonIdentified event → cache person in a 5-second window
 * - AudioEmbedding (Whisper) event → attribute to cached person
 * - Records interactions in SceneDatabase
 */
class InteractionTracker(
    private val sceneDatabase: SceneDatabase,
    private val eventBus: GlobalEventBus,
    private val locationService: ILocationService,
    private val scope: CoroutineScope
) {
    private val TAG = "InteractionTracker"

    // Recent person cache (5-second window)
    private data class PersonCache(
        val personId: Long,
        val personName: String?,
        val expression: String? = null,
        val expressionScore: Float? = null,
        val timestamp: Long
    )

    @Volatile
    private var recentPerson: PersonCache? = null
    private val PERSON_WINDOW_MS = 5000L

    init {
        subscribeToEvents()
        Log.i(TAG, "InteractionTracker initialized")
    }

    private fun subscribeToEvents() {
        // Subscribe to PersonIdentified events
        scope.launch(Dispatchers.Default) {
            eventBus.events.collect { event ->
                when (event) {
                    is XRealEvent.PerceptionEvent.PersonIdentified -> {
                        recentPerson = PersonCache(
                            personId = event.personId,
                            personName = event.personName,
                            timestamp = event.timestamp
                        )
                        Log.d(TAG, "Person cached: ${event.personName} (id=${event.personId})")
                    }
                    is XRealEvent.InputEvent.AudioEmbedding -> {
                        handleSpeechEvent(event)
                    }
                    is XRealEvent.PerceptionEvent.FacesDetected -> {
                        // Update expression info for the most recent person
                        val topFace = event.faces.firstOrNull { it.expression != null }
                        if (topFace != null) {
                            val cached = recentPerson
                            if (cached != null && isWithinWindow(cached.timestamp)) {
                                recentPerson = cached.copy(
                                    expression = topFace.expression,
                                    expressionScore = topFace.expressionScore
                                )
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun handleSpeechEvent(event: XRealEvent.InputEvent.AudioEmbedding) {
        val cached = recentPerson
        if (cached == null || !isWithinWindow(cached.timestamp)) {
            Log.d(TAG, "No recent person for speech attribution")
            return
        }

        // Attribute this speech to the cached person
        scope.launch(Dispatchers.IO) {
            try {
                val loc = locationService.getCurrentLocation()
                sceneDatabase.insertInteraction(
                    personId = cached.personId,
                    timestamp = event.timestamp,
                    transcript = event.transcript,
                    expression = cached.expression,
                    expressionScore = cached.expressionScore,
                    audioEmotion = event.emotion,
                    sceneNodeId = null,
                    audioEventId = null,
                    lat = loc?.latitude,
                    lon = loc?.longitude
                )
                Log.i(TAG, "Interaction recorded: ${cached.personName} said \"${event.transcript.take(50)}\"")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record interaction", e)
            }
        }
    }

    private fun isWithinWindow(timestamp: Long): Boolean {
        return System.currentTimeMillis() - timestamp < PERSON_WINDOW_MS
    }
}
