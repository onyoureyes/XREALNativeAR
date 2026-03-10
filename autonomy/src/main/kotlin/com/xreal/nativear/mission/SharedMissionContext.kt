package com.xreal.nativear.mission

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Blackboard-pattern shared store for inter-agent communication within a mission.
 *
 * Thread-safe: all agent runners may write concurrently.
 * Other agents' recent outputs are injected into prompts so they can react to each other.
 */
class SharedMissionContext {

    // ── Agent outputs (append-only log per role) ──

    private val agentOutputs = ConcurrentHashMap<String, CopyOnWriteArrayList<AgentOutput>>()

    fun addAgentOutput(agentRoleName: String, content: String, timestamp: Long = System.currentTimeMillis()) {
        agentOutputs.getOrPut(agentRoleName) { CopyOnWriteArrayList() }
            .add(AgentOutput(agentRoleName, content, timestamp))
    }

    fun getAgentOutputs(agentRoleName: String, limit: Int = 5): List<AgentOutput> {
        return agentOutputs[agentRoleName]
            ?.sortedByDescending { it.timestamp }
            ?.take(limit)
            ?: emptyList()
    }

    fun getAllRecentOutputs(limit: Int = 10): List<AgentOutput> {
        return agentOutputs.values
            .flatten()
            .sortedByDescending { it.timestamp }
            .take(limit)
    }

    // ── Key-value shared knowledge ──

    private val sharedKnowledge = ConcurrentHashMap<String, String>()

    fun putKnowledge(key: String, value: String) {
        sharedKnowledge[key] = value
    }

    fun getKnowledge(key: String): String? = sharedKnowledge[key]

    fun getAllKnowledge(): Map<String, String> = sharedKnowledge.toMap()

    // ── Volatile state snapshots (written by Conductor, read by agents) ──

    @Volatile var userStateSnapshot: String = ""
    @Volatile var currentLocation: String = ""

    // ── Summary builder (injected into agent prompts) ──

    fun buildContextSummary(): String = buildString {
        if (userStateSnapshot.isNotBlank()) {
            appendLine("[사용자 상태] $userStateSnapshot")
        }
        if (currentLocation.isNotBlank()) {
            appendLine("[현재 위치] $currentLocation")
        }

        val knowledge = getAllKnowledge()
        if (knowledge.isNotEmpty()) {
            appendLine("[공유 지식]")
            knowledge.entries.take(10).forEach { (k, v) ->
                appendLine("- $k: ${v.take(200)}")
            }
        }

        val outputs = getAllRecentOutputs(8)
        if (outputs.isNotEmpty()) {
            appendLine("[다른 에이전트의 최근 결과]")
            outputs.forEach { o ->
                appendLine("- [${o.agentRoleName}] ${o.content.take(200)}")
            }
        }
    }

    fun clear() {
        agentOutputs.clear()
        sharedKnowledge.clear()
        userStateSnapshot = ""
        currentLocation = ""
    }
}

data class AgentOutput(
    val agentRoleName: String,
    val content: String,
    val timestamp: Long
)
