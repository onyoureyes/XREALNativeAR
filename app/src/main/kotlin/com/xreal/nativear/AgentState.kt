package com.xreal.nativear

/**
 * AgentState: FSM for the AI Agent.
 * Tracks the conversational lifecycle.
 */
sealed interface AgentState {
    data object Idle : AgentState
    data object Listening : AgentState
    data object Thinking : AgentState
    data object Acting : AgentState
    data object Speaking : AgentState
}
