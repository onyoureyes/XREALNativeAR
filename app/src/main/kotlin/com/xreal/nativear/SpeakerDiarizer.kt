package com.xreal.nativear

import android.util.Log
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SpeakerDiarizer: Voice-based speaker identification.
 *
 * Flow:
 *   AudioEmbedding event → vec_person_voices KNN search
 *     → cosine distance < threshold → known speaker
 *     → cosine distance >= threshold → try linking with recent face match
 *
 * Integration:
 *   - Subscribes to AudioEmbedding events
 *   - When a speaker is identified, publishes EnrichedVoiceCommand with speaker info
 *   - Works with InteractionTracker for multi-source (face + voice) person fusion
 */
class SpeakerDiarizer(
    private val sceneDatabase: SceneDatabase,
    private val personRepository: PersonRepository,
    private val eventBus: GlobalEventBus,
    private val scope: CoroutineScope
) {
    private val TAG = "SpeakerDiarizer"

    // Cosine distance threshold for voice matching
    // Lower = stricter. 0.5 distance ≈ 0.5 similarity
    private val VOICE_MATCH_THRESHOLD = 0.5f

    // Cache of recently identified person from face pipeline (set by InteractionTracker integration)
    @Volatile
    var recentFacePersonId: Long? = null
    @Volatile
    var recentFaceTimestamp: Long = 0L
    private val FACE_VOICE_WINDOW_MS = 5000L

    init {
        subscribeToEvents()
        Log.i(TAG, "SpeakerDiarizer initialized")
    }

    private fun subscribeToEvents() {
        scope.launch(Dispatchers.Default) {
            eventBus.events.collect { event ->
                when (event) {
                    is XRealEvent.InputEvent.AudioEmbedding -> {
                        handleAudioEmbedding(event)
                    }
                    is XRealEvent.PerceptionEvent.PersonIdentified -> {
                        // Cache face-identified person for cross-modal linking
                        recentFacePersonId = event.personId
                        recentFaceTimestamp = event.timestamp
                    }
                    else -> {}
                }
            }
        }
    }

    private fun handleAudioEmbedding(event: XRealEvent.InputEvent.AudioEmbedding) {
        scope.launch(Dispatchers.IO) {
            try {
                val speakerId = identifySpeaker(event.audioEmbedding)

                if (speakerId != null) {
                    // Known speaker — register this voice sample
                    registerVoice(speakerId, event.audioEmbedding, event.transcript)
                    val person = personRepository.getPersonProfile(speakerId)
                    Log.i(TAG, "Speaker identified: ${person?.name} (id=$speakerId)")

                    // Publish enriched voice command with speaker info
                    eventBus.publish(XRealEvent.InputEvent.EnrichedVoiceCommand(
                        text = event.transcript,
                        speaker = person?.name ?: "Unknown",
                        emotion = "", // Will be enriched by EmotionClassifier
                        emotionScore = 0f
                    ))
                } else {
                    // Unknown voice — try linking with recent face detection
                    val facePersonId = recentFacePersonId
                    if (facePersonId != null && isWithinFaceWindow()) {
                        registerVoice(facePersonId, event.audioEmbedding, event.transcript)
                        val person = personRepository.getPersonProfile(facePersonId)
                        Log.i(TAG, "Voice linked to face-identified person: ${person?.name} (id=$facePersonId)")
                    } else {
                        Log.d(TAG, "Unidentified speaker — no voice or face match")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Speaker diarization failed", e)
            }
        }
    }

    /**
     * Identify speaker from audio embedding via KNN search.
     * @return person_id if matched, null otherwise
     */
    private fun identifySpeaker(audioEmbedding: ByteArray): Long? {
        val matches = sceneDatabase.findSimilarVoices(audioEmbedding, topK = 1)
        if (matches.isNotEmpty()) {
            val (bestMatch, distance) = matches[0]
            if (distance < VOICE_MATCH_THRESHOLD) {
                return bestMatch.personId
            }
        }
        return null
    }

    /**
     * Register a voice sample for a person.
     */
    private fun registerVoice(personId: Long, audioEmbedding: ByteArray, transcript: String?) {
        sceneDatabase.insertPersonVoice(personId, audioEmbedding, transcript)
    }

    private fun isWithinFaceWindow(): Boolean {
        return System.currentTimeMillis() - recentFaceTimestamp < FACE_VOICE_WINDOW_MS
    }
}
