package com.xreal.nativear.strategist

import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import org.json.JSONArray
import org.json.JSONObject

class DirectiveStore(
    private val database: UnifiedMemoryDatabase,
    private val memorySaveHelper: com.xreal.nativear.memory.IMemoryAccess? = null
) {
    private val TAG = "DirectiveStore"

    // In-memory cache: personaId -> active directives
    private val activeDirectives = mutableMapOf<String, MutableList<Directive>>()

    /**
     * Get active (non-expired) directives for a persona.
     * Returns both targeted directives and wildcard ("*") directives.
     */
    fun getDirectivesForPersona(personaId: String): List<Directive> {
        pruneExpired()
        val targeted = activeDirectives[personaId] ?: emptyList()
        val wildcard = activeDirectives["*"] ?: emptyList()
        return (targeted + wildcard).sortedByDescending { it.confidence }
    }

    /**
     * Replace all active directives with new ones from a reflection cycle.
     */
    fun updateDirectives(newDirectives: List<Directive>) {
        synchronized(activeDirectives) {
            // Don't clear old — merge by replacing same-target duplicates
            for (directive in newDirectives) {
                val list = activeDirectives.getOrPut(directive.targetPersonaId) { mutableListOf() }
                // Remove older directives for same target with similar instruction
                list.removeAll { existing ->
                    existing.instruction == directive.instruction || existing.isExpired
                }
                list.add(directive)
            }
            Log.i(TAG, "Updated directives: ${newDirectives.size} new, ${getActiveDirectiveCount()} total active")
        }
    }

    /**
     * Remove expired directives from cache.
     */
    fun pruneExpired() {
        synchronized(activeDirectives) {
            activeDirectives.values.forEach { list ->
                list.removeAll { it.isExpired }
            }
            activeDirectives.entries.removeAll { it.value.isEmpty() }
        }
    }

    /**
     * Persist current active directives to UnifiedMemoryDatabase.
     */
    fun persistToDatabase(directives: List<Directive>) {
        for (directive in directives) {
            try {
                if (memorySaveHelper != null) {
                    kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                        memorySaveHelper.saveMemory(
                            content = "[${directive.targetPersonaId}] ${directive.instruction}",
                            role = "STRATEGIST",
                            metadata = directive.toJson().toString(),
                            personaId = "strategist"
                        )
                    }
                } else {
                    val node = UnifiedMemoryDatabase.MemoryNode(
                        timestamp = directive.createdAt,
                        role = "STRATEGIST",
                        content = "[${directive.targetPersonaId}] ${directive.instruction}",
                        personaId = "strategist",
                        metadata = directive.toJson().toString()
                    )
                    database.insertNode(node)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist directive ${directive.id}: ${e.message}")
            }
        }
        Log.d(TAG, "Persisted ${directives.size} directives to DB")
    }

    /**
     * Load non-expired directives from DB on app restart.
     */
    fun loadFromDatabase() {
        try {
            val nodes = database.getNodesByPersonaId("strategist", limit = 50)
            var restored = 0

            for (node in nodes) {
                try {
                    val json = JSONObject(node.metadata ?: continue)
                    val directive = Directive.fromJson(json)
                    if (!directive.isExpired) {
                        val list = activeDirectives.getOrPut(directive.targetPersonaId) { mutableListOf() }
                        list.add(directive)
                        restored++
                    }
                } catch (e: Exception) {
                    // Skip malformed entries
                }
            }
            Log.i(TAG, "Restored $restored active directives from DB")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load directives from DB: ${e.message}")
        }
    }

    /**
     * 단일 Directive 추가 (PeerRequestReviewer / EmergencyOrchestrator 전용).
     * updateDirectives()와 달리 기존 지시사항을 삭제하지 않고 순수 추가.
     */
    fun addDirective(directive: Directive) {
        synchronized(activeDirectives) {
            val list = activeDirectives.getOrPut(directive.targetPersonaId) { mutableListOf() }
            list.removeAll { it.isExpired }
            list.add(directive)
            Log.d(TAG, "Directive 추가: [${directive.targetPersonaId}] '${directive.instruction.take(50)}'")
        }
    }

    fun getActiveDirectiveCount(): Int {
        return activeDirectives.values.sumOf { it.size }
    }

    fun getAllActiveDirectives(): List<Directive> {
        pruneExpired()
        return activeDirectives.values.flatten()
    }
}
