package com.xreal.nativear

import android.util.Log
import com.xreal.nativear.ai.AICallGateway
import com.xreal.nativear.ai.AIMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class MemoryCompressor(
    private val database: UnifiedMemoryDatabase,
    private val aiRegistry: com.xreal.nativear.ai.IAICallService,
    private val batchProcessor: com.xreal.nativear.batch.BatchProcessor? = null,
    private val tokenBudgetTracker: com.xreal.nativear.router.persona.TokenBudgetTracker? = null
) {
    private val TAG = "MemoryCompressor"
    private val scope = CoroutineScope(Dispatchers.IO)


    /**
     * Checks if a summary is needed for a specific level.
     * Rule: Every 50 rows at Level X without a parent will be compressed into 1 row at Level X+1.
     * Uses BatchProcessor throttle to prevent cascade avalanche.
     */
    fun checkAndCompress(level: Int = 0) {
        // Throttle via BatchProcessor to prevent cascade avalanche
        if (batchProcessor != null && !batchProcessor.canRunCompression()) {
            Log.d(TAG, "Compression throttled by BatchProcessor (level $level)")
            return
        }

        scope.launch {
            val unsummarizedCount = database.getCount(level)
            if (unsummarizedCount >= 50) {
                Log.i(TAG, "Triggering compression for Level $level (Count: $unsummarizedCount)")
                batchProcessor?.markCompressionRan()
                compressLevel(level)
            }
        }
    }

    private suspend fun compressLevel(level: Int) {
        // Budget gate: compression is non-essential
        tokenBudgetTracker?.let { tracker ->
            val check = tracker.checkBudget(com.xreal.nativear.ai.ProviderId.GEMINI, estimatedTokens = 1500)
            if (!check.allowed) {
                Log.w(TAG, "Compression blocked by budget (L$level): ${check.reason}")
                return
            }
        }

        val nodes = database.getUnsummarizedNodes(level, 50)
        if (nodes.size < 50) return

        // ★ Phase I: 평균 importance (참고용 — 낮은 importance 노드부터 선택됨)
        val avgImportance = nodes.map { it.importanceScore }.average()

        val promptBuilder = StringBuilder()
        promptBuilder.append("You are an advanced Life Memory Aggregator. Below are 50 chronological fragments (Level $level) from a user's life, comprising visual interpretations and auditory logs.\n\n")
        promptBuilder.append("### TASK:\n")
        promptBuilder.append("1. FUSE these fragments into a single 'Event Situation' node for higher-level memory (Level ${level + 1}).\n")
        promptBuilder.append("2. WHO was there? WHERE did it happen? WHAT was the primary theme/intent?\n")
        promptBuilder.append("3. If there is a mix of visual cues and dialogue, synchronize them into a coherent narrative.\n")
        promptBuilder.append("4. Output a concise summary (max 100 words) and a JSON metadata block with 'entities', 'location_name', and 'importance' (0-1).\n")
        promptBuilder.append("(참고: 이 기억 묶음의 평균 중요도=${"%.2f".format(avgImportance)}/1.0 — 낮은 중요도 기억이 우선 압축됨)\n")
        promptBuilder.append("\n### FRAGMENTS:\n")

        nodes.forEach { node ->
            promptBuilder.append("- [${node.role}] ${node.content} (${node.metadata})\n")
        }

        // Call AI for Summary (registry 라우팅: 리모트$0 → 서버 → 엣지)
        val response = aiRegistry.quickText(
            messages = listOf(AIMessage(role = "user", content = promptBuilder.toString())),
            callPriority = AICallGateway.CallPriority.PROACTIVE,
            visibility = AICallGateway.VisibilityIntent.INTERNAL_ONLY,
            intent = "memory_compression_summary"
        ) ?: return
        val summaryText = response.text

        if (summaryText != null && !summaryText.startsWith("Error:")) {
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
                    put("avg_source_importance", avgImportance)  // ★ Phase I
                }.toString()
            )

            val parentId = database.insertNode(newNode)

            // Link children to parent
            database.setParentId(nodes.map { it.id }, parentId)

            Log.i(TAG, "Successfully compressed 50 L$level nodes into L${level + 1} node ID: $parentId")

            // Recursive check: Maybe Level X+1 now has 50 nodes?
            checkAndCompress(level + 1)
        } else {
            Log.e(TAG, "Compression failed, response was null or error")
        }
    }

    // ==================== Persona-aware Compression ====================

    fun checkAndCompressPersona(personaId: String, level: Int = 0) {
        scope.launch {
            val count = database.getCountByPersona(personaId, level)
            if (count >= 50) {
                Log.i(TAG, "Triggering persona compression for $personaId at Level $level (Count: $count)")
                compressPersonaLevel(personaId, level)
            }
        }
    }

    private suspend fun compressPersonaLevel(personaId: String, level: Int) {
        // Budget gate
        tokenBudgetTracker?.let { tracker ->
            val check = tracker.checkBudget(com.xreal.nativear.ai.ProviderId.GEMINI, estimatedTokens = 1200)
            if (!check.allowed) {
                Log.w(TAG, "Persona compression blocked by budget ($personaId L$level): ${check.reason}")
                return
            }
        }

        val nodes = database.getUnsummarizedNodesByPersona(personaId, level, 50)
        if (nodes.size < 50) return

        // ★ Phase I: 평균 importance
        val avgImportancePersona = nodes.map { it.importanceScore }.average()

        val promptBuilder = StringBuilder()
        promptBuilder.append("You are the '$personaId' persona's memory compressor. Below are 50 chronological fragments (Level $level) from your observations.\n\n")
        promptBuilder.append("### TASK:\n")
        promptBuilder.append("1. Compress into a single Level ${level + 1} insight.\n")
        promptBuilder.append("2. Focus on patterns, recurring themes, and key learnings relevant to your role.\n")
        promptBuilder.append("3. Output a concise summary (max 100 words) with actionable insights.\n")
        promptBuilder.append("(참고: 평균 중요도=${"%.2f".format(avgImportancePersona)}/1.0)\n")
        promptBuilder.append("\n### FRAGMENTS:\n")

        nodes.forEach { node ->
            promptBuilder.append("- [${node.role}] ${node.content}\n")
        }

        val response = aiRegistry.quickText(
            messages = listOf(AIMessage(role = "user", content = promptBuilder.toString())),
            callPriority = AICallGateway.CallPriority.PROACTIVE,
            visibility = AICallGateway.VisibilityIntent.INTERNAL_ONLY,
            intent = "persona_memory_compression"
        ) ?: return
        val summaryText = response.text
        if (summaryText == null || summaryText.startsWith("Error:")) return

        val avgLat = nodes.mapNotNull { it.latitude }.average().takeIf { !it.isNaN() }
        val avgLon = nodes.mapNotNull { it.longitude }.average().takeIf { !it.isNaN() }

        val newNode = UnifiedMemoryDatabase.MemoryNode(
            timestamp = System.currentTimeMillis(),
            role = "PERSONA_SUMMARY",
            content = summaryText,
            level = level + 1,
            latitude = avgLat,
            longitude = avgLon,
            personaId = personaId,
            metadata = JSONObject().apply {
                put("source_count", 50)
                put("source_level", level)
                put("persona_id", personaId)
                put("avg_source_importance", avgImportancePersona)  // ★ Phase I
            }.toString()
        )

        val parentId = database.insertNode(newNode)
        database.setParentId(nodes.map { it.id }, parentId)

        Log.i(TAG, "Compressed 50 L$level nodes for $personaId into L${level + 1} node ID: $parentId")
        checkAndCompressPersona(personaId, level + 1)
    }
}
