package com.xreal.nativear.deliberation

import java.util.UUID

/**
 * DeliberationTypes: Data classes for structured multi-expert AI deliberation.
 *
 * Enables multiple AI experts to propose, vote, and reach consensus
 * on complex decisions. All sessions are persisted to DB.
 */

data class DeliberationSession(
    val id: String = UUID.randomUUID().toString().take(12),
    val topic: String,
    val situation: String? = null,
    val participants: List<String>,
    val proposals: MutableList<Proposal> = mutableListOf(),
    val votes: MutableList<Vote> = mutableListOf(),
    val decision: Decision? = null,
    val status: DelibStatus = DelibStatus.COLLECTING_PROPOSALS,
    val contextSummary: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null
)

data class Proposal(
    val expertId: String,
    val content: String,
    val rationale: String,
    val confidence: Float,
    val pastEvidence: String? = null
)

data class Vote(
    val expertId: String,
    val proposalIndex: Int,
    val weight: Float,
    val comment: String? = null
)

data class Decision(
    val chosenProposalIndex: Int,
    val synthesizedAction: String,
    val dissent: String? = null,
    val confidence: Float
)

enum class DelibStatus {
    COLLECTING_PROPOSALS,
    VOTING,
    DECIDED,
    CANCELLED
}
