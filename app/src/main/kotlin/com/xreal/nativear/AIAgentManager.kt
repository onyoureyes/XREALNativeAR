package com.xreal.nativear

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * AIAgentManager: Orchestrates AI interactions, tool calling, and memory.
 */
class AIAgentManager(
    private val context: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val geminiClient: GeminiClient,
    private val memoryService: IMemoryService,
    private val searchService: ISearchService,
    private val weatherService: IWeatherService,
    private val navigationService: INavigationService,
    private val visionService: IVisionService,
    private val aiOrchestrator: UnifiedAIOrchestrator,
    private val locationService: ILocationService,
    private val cloudBackupManager: CloudBackupManager,
    private val eventBus: com.xreal.nativear.core.GlobalEventBus,
    private val callback: AIAgentCallback
) {





    private val TAG = "AIAgentManager"
    var isGeminiBusy = false
        private set

    // For throttling spatial indexing
    private val recentlyIndexedObjects = mutableMapOf<String, Long>()
    private val INDEX_THROTTLE_MS = 60000L // 1 minute

    interface AIAgentCallback {
        fun onCentralMessage(text: String)
        fun onGeminiResponse(reply: String)
        fun onSearchResults(resultsJson: String)
        fun showSnapshotFeedback()
        fun onGetLatestBitmap(): Bitmap?
    }




    fun processWithGemini(userText: String, externalContext: String? = null) {
        if (isGeminiBusy) return
        
        scope.launch {
            isGeminiBusy = true
            callback.onCentralMessage("Thinking...")
            
            // Context Building
            val loc = locationService.getCurrentLocation()
            val fullPrompt = if (externalContext != null) {
                "$externalContext\n\nContext: $contextInfo\nUser: $userText"
            } else {
                "$contextInfo\n\nUser: $userText"
            }
            memoryService.saveMemory(userText, "USER")

            
            var currentResponse = geminiClient.sendMessage(fullPrompt)
            var hops = 0
            
            while (currentResponse?.functionCalls?.isNotEmpty() == true && hops < 3) {
                hops++
                val toolCalls = currentResponse.functionCalls
                val toolResults = mutableMapOf<String, String>()
                
                for (call in toolCalls) {
                    val result = handleToolCall(call.name, call.args)
                    toolResults[call.name] = result
                    
                    // Specific: If it's a memory query, pass the raw data to UI
                    if (call.name.startsWith("query_")) {
                        callback.onSearchResults(result)
                    }
                }
                
                // Send results back to Gemini for final summary
                currentResponse = geminiClient.sendMessage("Tool Results: $toolResults")
            }

            
            val reply = currentResponse?.text ?: if (hops > 0) "I've processed your request." else GeminiPrompts.getClarificationPrompt(userText)
            memoryService.saveMemory(reply, "AI")
            
            callback.onGeminiResponse(reply)
            eventBus.publish(com.xreal.nativear.core.XRealEvent.ActionRequest.SpeakTTS(reply))

            
            // Async Task Extraction
            extractTasksInBackground(userText + "\n" + reply)
            
            // isGeminiBusy = false // Replaced by flow update above
        }
    }

    private fun extractTasksInBackground(interaction: String) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val prompt = GeminiPrompts.getTaskExtractionPrompt(interaction)
            val response = geminiClient.sendMessage(prompt)
            val extracted = response?.text ?: ""
            if (extracted.contains("[할 일]")) {
                Log.i(TAG, "Extracted Task: $extracted")
                // TODO: Save to a specialized task table if needed
            }
        }
    }


    private suspend fun handleToolCall(name: String, args: Map<String, Any?>): String {
        Log.i(TAG, "Dispatching Tool: $name")
        return when {
            name == "searchWeb" -> {
                val query = args["query"] as? String ?: ""
                searchService.searchWeb(query)
            }

            name == "getWeather" -> {
                val location = args["location"] as? String ?: ""
                weatherService.getWeather(location)
            }
            name == "setVisionControl" -> {
                val feature = args["feature"] as? String ?: ""
                val enabled = args["enabled"] as? Boolean ?: false
                if (feature == "OCR") visionService.setOcrEnabled(enabled)
                if (feature == "POSE") visionService.setPoseEnabled(enabled)
                "Vision feature $feature set to $enabled"
            }
            name == "get_directions" -> {
                val dest = args["destination"] as? String ?: ""
                val origin = args["origin"] as? String ?: "current location"
                navigationService.getDirections(origin, dest)
            }
            name == "take_snapshot" -> {
                visionService.captureSceneSnapshot()
                "Snapshot capture triggered."
            }
            name == "get_current_location" -> {
                val loc = locationService.getCurrentLocation()
                if (loc != null) {
                    "Current Location: Lat ${loc.latitude}, Lon ${loc.longitude}, Speed ${loc.speed}m/s"
                } else {
                    "Location unavailable."
                }
            }

            name == "sync_memory" -> {
                cloudBackupManager.syncToCloud(memoryService as MemoryRepository)
                "Sync triggered."
            }

            name == "query_visual_memory" -> {
                val bitmap = callback.onGetLatestBitmap()
                if (bitmap != null) {
                    memoryService.queryVisual(bitmap)
                } else {
                    "Error: Could not capture current view."
                }
            }
            name == "query_temporal_memory" -> {
                val start = parseTimeToLong(args["start_time"] as? String ?: "")
                val end = parseTimeToLong(args["end_time"] as? String ?: "")
                memoryService.queryTemporal(start, end)
            }
            name == "query_spatial_memory" -> {
                val lat = (args["latitude"] as? Number)?.toDouble() ?: 0.0
                val lon = (args["longitude"] as? Number)?.toDouble() ?: 0.0
                val radius = (args["radius_km"] as? Number)?.toDouble() ?: 0.5
                memoryService.querySpatial(lat, lon, radius)
            }
            name == "query_keyword_memory" -> {
                val keyword = args["keyword"] as? String ?: ""
                memoryService.queryKeyword(keyword)
            }
            name == "query_emotion_memory" -> {
                val emotion = args["emotion"] as? String ?: ""
                memoryService.queryEmotion(emotion)
            }

            else -> "Unknown tool: $name"
        }
    }

    private fun parseTimeToLong(timeStr: String): Long {
        return try {
            when {
                timeStr.contains("today", ignoreCase = true) -> System.currentTimeMillis()
                timeStr.contains("now", ignoreCase = true) -> System.currentTimeMillis()
                else -> {
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    sdf.parse(timeStr)?.time ?: System.currentTimeMillis()
                }
            }
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }


    fun interpretScene(bitmap: Bitmap, ocrText: String) {
        callback.showSnapshotFeedback()
        callback.onCentralMessage("Interpreting scene...")
        
        scope.launch {
            val prompt = GeminiPrompts.getSceneInterpretationPrompt(ocrText)
            val response = geminiClient.sendMessage(prompt, bitmap)
            val reply = response?.text ?: "I couldn't analyze the scene."
            
            // Background Indexing via MemoryService
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                memoryService.saveMemory(reply, "CAMERA", "{\"trigger\":\"MANUAL_STABILITY\"}")
            }
            
            callback.onGeminiResponse("Scene: $reply")
            eventBus.publish(com.xreal.nativear.core.XRealEvent.ActionRequest.SpeakTTS(reply))
        }
    }

    fun processDetections(results: List<Detection>) {
        if (results.isEmpty()) return
        
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val currentTime = System.currentTimeMillis()
            
            for (detection in results) {
                if (detection.confidence > 0.70f) {
                    val lastIndexed = recentlyIndexedObjects[detection.label] ?: 0L
                    if (currentTime - lastIndexed > INDEX_THROTTLE_MS) {
                        Log.i(TAG, "indexing Detection: ${detection.label}")
                        recentlyIndexedObjects[detection.label] = currentTime
                        
                        val metadata = org.json.JSONObject().apply {
                            put("confidence", detection.confidence)
                            put("x", detection.x)
                            put("y", detection.y)
                            put("trigger", "AUTO_DETECTION")
                        }.toString()
                        
                        memoryService.saveMemory(
                            content = "I see a ${detection.label}.",
                            role = "CAMERA",
                            metadata = metadata
                        )
                    }
                }
            }
        }
    }
}

