package com.xreal.nativear

import android.util.Log
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PersonRepository: Manages the person pool and face identification pipeline.
 *
 * Flow:
 *   Face embedding (192d) → vec_person_faces KNN search
 *     → cosine distance < threshold → existing person matched
 *     → cosine distance >= threshold → new person registered ("Unknown #N")
 */
class PersonRepository(
    private val sceneDatabase: SceneDatabase,
    private val faceEmbedder: FaceEmbedder,
    private val eventBus: GlobalEventBus,
    private val locationService: ILocationService
) {
    private val TAG = "PersonRepository"

    // Cosine distance threshold for face recognition
    // Lower = stricter matching. With cosine metric, distance = 1 - similarity
    // 0.6 distance ≈ 0.4 similarity — fairly lenient for initial registration
    private val RECOGNITION_THRESHOLD = 0.6f

    private var unknownCounter = 0

    /**
     * Identify or register a face from its embedding.
     * @param faceEmbedding L2-normalized 192d float array
     * @param faceCropPath Optional path to saved face crop image
     * @return Pair(personId, isNewPerson)
     */
    suspend fun identifyOrRegister(faceEmbedding: FloatArray, faceCropPath: String? = null): Pair<Long, Boolean> {
        val embeddingBytes = floatArrayToByteArray(faceEmbedding)

        // KNN search against known faces
        val matches = sceneDatabase.findSimilarFaces(embeddingBytes, topK = 1)

        if (matches.isNotEmpty()) {
            val (bestMatch, distance) = matches[0]
            if (distance < RECOGNITION_THRESHOLD) {
                // Known person — add this face to their profile
                sceneDatabase.insertPersonFace(bestMatch.personId, embeddingBytes, faceCropPath)
                val person = sceneDatabase.getPersonById(bestMatch.personId)
                Log.i(TAG, "Recognized: ${person?.name ?: "Unknown"} (id=${bestMatch.personId}, distance=$distance)")

                // Publish PersonIdentified event
                val loc = locationService.getCurrentLocation()
                eventBus.publish(XRealEvent.PerceptionEvent.PersonIdentified(
                    personId = bestMatch.personId,
                    personName = person?.name,
                    confidence = 1f - distance,
                    faceEmbedding = embeddingBytes,
                    timestamp = System.currentTimeMillis(),
                    latitude = loc?.latitude,
                    longitude = loc?.longitude
                ))

                return bestMatch.personId to false
            }
        }

        // New person — register
        unknownCounter++
        val name = "Unknown #$unknownCounter"
        val personId = sceneDatabase.insertPerson(name)
        sceneDatabase.insertPersonFace(personId, embeddingBytes, faceCropPath)

        Log.i(TAG, "New person registered: $name (id=$personId)")

        val loc = locationService.getCurrentLocation()
        eventBus.publish(XRealEvent.PerceptionEvent.PersonIdentified(
            personId = personId,
            personName = name,
            confidence = 0f,
            faceEmbedding = embeddingBytes,
            timestamp = System.currentTimeMillis(),
            latitude = loc?.latitude,
            longitude = loc?.longitude
        ))

        return personId to true
    }

    suspend fun labelPerson(personId: Long, name: String, relationship: String? = null) {
        sceneDatabase.updatePersonName(personId, name, relationship)
        Log.i(TAG, "Person labeled: id=$personId → $name ($relationship)")
    }

    fun getPersonProfile(personId: Long): SceneDatabase.PersonRecord? {
        return sceneDatabase.getPersonById(personId)
    }

    fun getAllPersons(): List<SceneDatabase.PersonRecord> {
        return sceneDatabase.getAllPersons()
    }

    private fun floatArrayToByteArray(floats: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * 4)
        buffer.order(ByteOrder.nativeOrder())
        for (f in floats) buffer.putFloat(f)
        return buffer.array()
    }
}
