package com.xreal.nativear

import android.content.Context
import android.util.Log
import com.xreal.nativear.memory.IMemoryAccess
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/**
 * MemoryRepository: High-level repository for managing memories using UnifiedMemoryDatabase.
 */
class MemoryRepository(
    private val context: Context,
    private val database: UnifiedMemoryDatabase,
    private val sceneDatabase: SceneDatabase,
    private val memorySearcher: MemorySearcher,
    private val memoryCompressor: MemoryCompressor,
    private val locationService: ILocationService,
    private val textEmbedder: TextEmbedder,
    private val eventBus: com.xreal.nativear.core.GlobalEventBus,
    private val emotionClassifier: EmotionClassifier,
    private val memorySaveHelper: IMemoryAccess
) : IMemoryService {
    private val TAG = "MemoryRepository"
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    
    init {
        subscribeToEmbeddingEvents()
        Log.i(TAG, "MemoryRepository initialized with EventBus subscription")
    }
    
    private fun subscribeToEmbeddingEvents() {
        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is com.xreal.nativear.core.XRealEvent.PerceptionEvent.VisualEmbedding -> {
                        storeVisualEmbedding(event)
                    }
                    is com.xreal.nativear.core.XRealEvent.InputEvent.AudioEmbedding -> {
                        // SceneDB only — text memory is saved by AudioAnalysisService via saveMemory()
                        storeAudioEmbedding(event)
                    }
                    is com.xreal.nativear.core.XRealEvent.PerceptionEvent.AudioEnvironment -> {
                        storeAudioEnvironment(event)
                    }
                    else -> {
                        // Ignore other events
                    }
                }
            }
        }
    }
    
    private suspend fun storeVisualEmbedding(event: com.xreal.nativear.core.XRealEvent.PerceptionEvent.VisualEmbedding) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                sceneDatabase.insertNode(
                    timestamp = event.timestamp,
                    label = event.label,
                    embedding = event.embedding,
                    lat = event.latitude,
                    lon = event.longitude
                )
                Log.d(TAG, "✅ Stored visual embedding: ${event.label}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to store visual embedding", e)
            }
        }
    }
    
    private suspend fun storeAudioEmbedding(event: com.xreal.nativear.core.XRealEvent.InputEvent.AudioEmbedding) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Convert ByteArray to FloatArray for emotion classification
                val embedding = byteArrayToFloatArray(event.audioEmbedding)
                
                // Classify emotion (using injected singleton)
                val (emotion, emotionScore) = emotionClassifier.classifyEmotion(embedding)
                
                sceneDatabase.insertVoiceLog(
                    timestamp = event.timestamp,
                    transcript = event.transcript,
                    audioEmbedding = event.audioEmbedding,
                    emotion = emotion,
                    emotionScore = emotionScore,
                    lat = event.latitude,
                    lon = event.longitude
                )
                Log.d(TAG, "✅ Stored audio embedding: \"${event.transcript}\" (emotion: $emotion, score: $emotionScore)")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to store audio embedding", e)
            }
        }
    }

    private suspend fun storeAudioEnvironment(event: com.xreal.nativear.core.XRealEvent.PerceptionEvent.AudioEnvironment) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val labelsJson = org.json.JSONArray(event.events.map { it.first }).toString()
                val scoresJson = org.json.JSONArray(event.events.map { it.second.toDouble() }).toString()

                sceneDatabase.insertAudioEvent(
                    timestamp = event.timestamp,
                    labels = labelsJson,
                    scores = scoresJson,
                    embedding = event.embedding,
                    lat = event.latitude,
                    lon = event.longitude
                )
                Log.d(TAG, "✅ Stored audio environment: $labelsJson")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to store audio environment", e)
            }
        }
    }

    private fun byteArrayToFloatArray(bytes: ByteArray): FloatArray {
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        buffer.order(java.nio.ByteOrder.nativeOrder())
        val floats = FloatArray(bytes.size / 4)
        buffer.asFloatBuffer().get(floats)
        return floats
    }
    
    override suspend fun saveMemory(content: String, role: String, metadata: String?, lat: Double?, lon: Double?) {
        Log.i(TAG, "Saving memory: $content")
        memorySaveHelper.saveMemory(
            content = content,
            role = role,
            metadata = metadata,
            personaId = null,
            lat = lat,
            lon = lon
        )
        memoryCompressor.checkAndCompress()
    }



    override suspend fun queryTemporal(startTime: Long, endTime: Long): String {
        val nodes = database.getNodesInTimeRange(startTime, endTime)
        return formatNodesAsJson(nodes)
    }

    override suspend fun querySpatial(lat: Double, lon: Double, radiusKm: Double): String {
        val nodes = database.getNodesInSpatialRange(lat, lon, radiusKm)
        return formatNodesAsJson(nodes)
    }

    override suspend fun queryKeyword(keyword: String): String {
        // Try semantic search first via vec0 KNN
        if (textEmbedder.isReady) {
            try {
                val queryEmbedding = textEmbedder.getEmbedding(keyword)
                val vecResults = database.searchByTextEmbedding(queryEmbedding, 20)
                if (vecResults.isNotEmpty()) {
                    val nodes = database.getNodesByIds(vecResults.map { it.first })
                    return formatNodesAsJson(nodes)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Semantic search failed, falling back to keyword: ${e.message}")
            }
        }
        // Fallback to LIKE keyword search
        val results = memorySearcher.search(keyword)
        return formatNodesAsJson(results.map { it.node })
    }

    override suspend fun queryVisual(bitmap: android.graphics.Bitmap): String {
        val results = memorySearcher.searchByImage(bitmap)
        return formatNodesAsJson(results.map { it.node })
    }

    override suspend fun queryEmotion(emotion: String): String {
        val logs = sceneDatabase.getVoiceLogsByEmotion(emotion)
        val array = org.json.JSONArray()
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        
        for (log in logs) {
            val obj = org.json.JSONObject()
            obj.put("id", log.id)
            obj.put("time", sdf.format(java.util.Date(log.timestamp)))
            obj.put("role", "USER_AUDIO")
            obj.put("content", log.transcript)
            obj.put("emotion", log.emotion)
            obj.put("score", log.emotionScore)
            array.put(obj)
        }
        return array.toString()
    }

    private fun formatNodesAsJson(nodes: List<UnifiedMemoryDatabase.MemoryNode>): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val array = org.json.JSONArray()
        for (node in nodes) {
            val obj = org.json.JSONObject()
            obj.put("id", node.id)
            obj.put("time", sdf.format(java.util.Date(node.timestamp)))
            obj.put("role", node.role)
            obj.put("content", node.content)
            obj.put("lat", node.latitude)
            obj.put("lon", node.longitude)
            array.put(obj)
        }
        return array.toString()
    }

    override fun getMemoryCount(level: Int): Int {
        return database.getCount(level)
    }

    override fun getRecentMemories(limit: Int): List<UnifiedMemoryDatabase.MemoryNode> {
        return database.getUnsummarizedNodes(0, limit)
    }

    override fun getAllMemories(): List<UnifiedMemoryDatabase.MemoryNode> {
        return database.getAllNodes()
    }
    
    // Scene Database Query Methods
    suspend fun findSimilarScenes(queryEmbedding: ByteArray, topK: Int = 5): List<Pair<SceneDatabase.SceneNode, Float>> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            sceneDatabase.findSimilarScenes(queryEmbedding, topK)
        }
    }
    
    suspend fun findSimilarVoiceLogs(queryAudioEmbedding: ByteArray, topK: Int = 5): List<Pair<SceneDatabase.VoiceLog, Float>> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            sceneDatabase.findSimilarVoiceLogs(queryAudioEmbedding, topK)
        }
    }
    
    fun cleanup() {
        scope.cancel()
        Log.i(TAG, "MemoryRepository cleaned up")
    }
}

