package com.xreal.nativear.deliberation

import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.context.ContextSnapshot
import com.xreal.nativear.context.LifeSituation
import com.xreal.nativear.core.GlobalEventBus
import org.json.JSONArray
import org.json.JSONObject

/**
 * DeliberationManager: Manages structured AI deliberation sessions.
 *
 * Orchestrates: 1) Proposal collection → 2) Voting → 3) Decision → 4) DB persist
 * All sessions are stored in deliberation_sessions table for analysis and learning.
 */
class DeliberationManager(
    private val database: UnifiedMemoryDatabase,
    private val eventBus: GlobalEventBus
) {
    companion object {
        private const val TAG = "DeliberationManager"
    }

    private val activeSessions = mutableMapOf<String, DeliberationSession>()

    /**
     * Start a new deliberation session.
     */
    fun startDeliberation(
        topic: String,
        participants: List<String>,
        situation: LifeSituation? = null,
        contextSummary: String? = null
    ): DeliberationSession {
        val session = DeliberationSession(
            topic = topic,
            situation = situation?.name,
            participants = participants,
            contextSummary = contextSummary
        )
        activeSessions[session.id] = session
        Log.i(TAG, "Started deliberation: ${session.id} — \"$topic\" with ${participants.size} participants")
        return session
    }

    /**
     * Add a proposal to an active session.
     */
    fun addProposal(sessionId: String, proposal: Proposal): Boolean {
        val session = activeSessions[sessionId] ?: return false
        if (session.status != DelibStatus.COLLECTING_PROPOSALS) return false
        session.proposals.add(proposal)
        Log.d(TAG, "Proposal added by ${proposal.expertId}: ${proposal.content.take(50)}")
        return true
    }

    /**
     * Move to voting phase.
     */
    fun startVoting(sessionId: String): Boolean {
        val session = activeSessions[sessionId] ?: return false
        if (session.proposals.isEmpty()) return false
        activeSessions[sessionId] = session.copy(status = DelibStatus.VOTING)
        return true
    }

    /**
     * Add a vote.
     */
    fun addVote(sessionId: String, vote: Vote): Boolean {
        val session = activeSessions[sessionId] ?: return false
        if (session.status != DelibStatus.VOTING) return false
        session.votes.add(vote)
        return true
    }

    /**
     * Finalize deliberation: tally votes, produce decision, persist to DB.
     */
    fun finalize(sessionId: String): Decision? {
        val session = activeSessions[sessionId] ?: return null
        if (session.proposals.isEmpty()) return null

        // Tally weighted votes
        val scores = FloatArray(session.proposals.size)
        for (vote in session.votes) {
            if (vote.proposalIndex in scores.indices) {
                scores[vote.proposalIndex] += vote.weight
            }
        }

        // If no votes, pick highest confidence proposal
        val winnerIndex = if (session.votes.isEmpty()) {
            session.proposals.indices.maxByOrNull { session.proposals[it].confidence } ?: 0
        } else {
            scores.indices.maxByOrNull { scores[it] } ?: 0
        }

        val winner = session.proposals[winnerIndex]
        val dissents = session.proposals
            .filterIndexed { i, _ -> i != winnerIndex }
            .joinToString("; ") { "${it.expertId}: ${it.content.take(30)}" }
            .takeIf { it.isNotBlank() }

        val decision = Decision(
            chosenProposalIndex = winnerIndex,
            synthesizedAction = winner.content,
            dissent = dissents,
            confidence = winner.confidence
        )

        val finalSession = session.copy(
            decision = decision,
            status = DelibStatus.DECIDED,
            endedAt = System.currentTimeMillis()
        )
        activeSessions[sessionId] = finalSession

        // Persist to DB
        persistSession(finalSession)

        Log.i(TAG, "Deliberation ${session.id} decided: ${winner.content.take(50)} (conf: ${winner.confidence})")
        return decision
    }

    /**
     * Quick consensus: leader decides, others review.
     */
    fun quickConsensus(
        topic: String,
        leaderProposal: Proposal,
        reviewers: List<String>,
        situation: LifeSituation? = null
    ): Decision {
        val session = startDeliberation(topic, listOf(leaderProposal.expertId) + reviewers, situation)
        addProposal(session.id, leaderProposal)
        startVoting(session.id)
        // Leader auto-votes for own proposal
        addVote(session.id, Vote(leaderProposal.expertId, 0, 1.0f))
        return finalize(session.id) ?: Decision(0, leaderProposal.content, null, leaderProposal.confidence)
    }

    // ─── DB Persistence ───

    private fun persistSession(session: DeliberationSession) {
        try {
            val db = database.writableDatabase
            val values = android.content.ContentValues().apply {
                put("id", session.id)
                put("topic", session.topic)
                put("situation", session.situation)
                put("participants", JSONArray(session.participants).toString())
                put("proposals", JSONArray().apply {
                    session.proposals.forEach { p ->
                        put(JSONObject().apply {
                            put("expertId", p.expertId)
                            put("content", p.content)
                            put("rationale", p.rationale)
                            put("confidence", p.confidence.toDouble())
                            p.pastEvidence?.let { put("pastEvidence", it) }
                        })
                    }
                }.toString())
                put("votes", JSONArray().apply {
                    session.votes.forEach { v ->
                        put(JSONObject().apply {
                            put("expertId", v.expertId)
                            put("proposalIndex", v.proposalIndex)
                            put("weight", v.weight.toDouble())
                            v.comment?.let { put("comment", it) }
                        })
                    }
                }.toString())
                session.decision?.let { d ->
                    put("decision", JSONObject().apply {
                        put("chosenProposalIndex", d.chosenProposalIndex)
                        put("synthesizedAction", d.synthesizedAction)
                        d.dissent?.let { put("dissent", it) }
                        put("confidence", d.confidence.toDouble())
                    }.toString())
                }
                put("status", session.status.ordinal)
                put("context_summary", session.contextSummary)
                put("started_at", session.startedAt)
                put("ended_at", session.endedAt)
            }
            db.insertWithOnConflict("deliberation_sessions", null, values,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            Log.d(TAG, "Persisted deliberation: ${session.id}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist deliberation: ${e.message}")
        }
    }

    // ─── Query API ───

    fun getRecentDeliberations(limit: Int = 10): List<DeliberationSession> {
        // Returns from active sessions (in-memory); for DB queries use direct SQL
        return activeSessions.values.sortedByDescending { it.startedAt }.take(limit)
    }

    fun getActiveSession(sessionId: String): DeliberationSession? = activeSessions[sessionId]
}
