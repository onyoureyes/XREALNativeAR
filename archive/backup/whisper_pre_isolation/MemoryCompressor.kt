package com.xreal.nativear

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class MemoryCompressor(private val context: Context, private val geminiClient: GeminiClient) {
    private val TAG = "MemoryCompressor"
    private val database = UnifiedMemoryDatabase(context)
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Checks if a summary is needed for a specific level.
     * Rule: Every 50 rows at Level X without a parent will be compressed into 1 row at Level X+1.
     */
    fun checkAndCompress(level: Int = 0) {
        scope.launch {
            val unsummarizedCount = database.getCount(level)
            if (unsummarizedCount >= 50) {
                Log.i(TAG, "Triggering compression for Level $level (Count: $unsummarizedCount)")
                compressLevel(level)
            }
        }
    }

    private suspend fun compressLevel(level: Int) {
        val nodes = database.getUnsummarizedNodes(level, 50)
        if (nodes.size < 50) return

        val promptBuilder = StringBuilder()
        promptBuilder.append("You are an advanced Life Memory Aggregator. Below are 50 chronological fragments (Level $level) from a user's life, comprising visual interpretations and auditory logs.\n\n")
        promptBuilder.append("### TASK:\n")
        promptBuilder.append("1. FUSE these fragments into a single 'Event Situation' node for higher-level memory (Level ${level + 1}).\n")
        promptBuilder.append("2. WHO was there? WHERE did it happen? WHAT was the primary theme/intent?\n")
        promptBuilder.append("3. If there is a mix of visual cues and dialogue, synchronize them into a coherent narrative.\n")
        promptBuilder.append("4. Output a concise summary (max 100 words) and a JSON metadata block with 'entities', 'location_name', and 'importance' (0-1).\n")
        promptBuilder.append("\n### FRAGMENTS:\n")
        
        nodes.forEach { node ->
            promptBuilder.append("- [${node.role}] ${node.content} (${node.metadata})\n")
        }

        // Call Gemini for Summary
        val (response, error) = geminiClient.generateText(promptBuilder.toString())
        val summaryText = response?.text()
        
        if (summaryText != null) {
            // Create the Level X+1 node
            val avgLat = nodes.mapNotNull { it.latitude }.average().takeIf { !it.isNaN() }
            val avgLon = nodes.mapNotNull { it.longitude }.average().takeIf { !it.isNaN() }
            
            val newNode = UnifiedMemoryDatabase.MemoryNode(
                timestamp = System.currentTimeMillis(),
                role = "SYSTEM_SUMMARY",
                content = summaryText,
                level = level + 1,
                latitude = avgLat,
                longitude = avgLon,
                metadata = JSONObject().apply {
                    put("source_count", 50)
                    put("source_level", level)
                }.toString()
            )
            
            val parentId = database.insertNode(newNode)
            
            // Link children to parent
            database.setParentId(nodes.map { it.id }, parentId)
            
            Log.i(TAG, "Successfully compressed 50 L$level nodes into L${level + 1} node ID: $parentId")
            
            // Recursive check: Maybe Level X+1 now has 50 nodes?
            checkAndCompress(level + 1)
        } else {
            Log.e(TAG, "Compression failed: $error")
        }
    }
}
