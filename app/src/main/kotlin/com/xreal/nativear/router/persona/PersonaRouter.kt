package com.xreal.nativear.router.persona

import android.util.Log
import com.xreal.nativear.ai.IPersonaService
import com.xreal.nativear.ai.ProviderId

/**
 * Routing priority levels that determine how many personas are invoked.
 */
enum class RoutingPriority {
    IMPORTANT,  // User explicit request or high-risk: all personas in parallel
    ROUTINE,    // Event triggers: 1-2 relevant personas only
    PREFETCH    // Predictive caching: budget-available personas only (max 2)
}

/**
 * Result of persona routing decision.
 */
data class PersonaRoutingDecision(
    val priority: RoutingPriority,
    val selectedPersonaIds: List<String>,
    val reason: String
)

/**
 * Decides which personas to invoke based on request importance and budget.
 */
class PersonaRouter(
    private val personaManager: IPersonaService,
    private val tokenBudgetTracker: TokenBudgetTracker
) {
    companion object {
        private const val TAG = "PersonaRouter"
        private const val ESTIMATED_TOKENS_PER_CALL = 1500
    }

    /**
     * Route a request to appropriate personas based on priority.
     *
     * @param query User query or event-generated query
     * @param triggerSource "user" for explicit user requests, "event" for auto-triggers, "prefetch" for predictive
     * @param suggestedPersonaId Specific persona suggested by trigger rule (for ROUTINE)
     */
    fun route(
        query: String,
        triggerSource: String,
        suggestedPersonaId: String? = null
    ): PersonaRoutingDecision {
        val priority = classifyPriority(query, triggerSource)
        val enabledPersonas = personaManager.getEnabledPersonas()

        if (enabledPersonas.isEmpty()) {
            return PersonaRoutingDecision(priority, emptyList(), "No enabled personas")
        }

        val selectedIds = when (priority) {
            RoutingPriority.IMPORTANT -> {
                // All enabled personas that are within budget
                val ids = enabledPersonas
                    .filter { tokenBudgetTracker.isWithinBudget(it.providerId, ESTIMATED_TOKENS_PER_CALL) }
                    .map { it.id }
                if (ids.isEmpty()) {
                    // Fallback to Gemini (highest budget) even if over
                    enabledPersonas.filter { it.providerId == ProviderId.GEMINI }.map { it.id }
                } else ids
            }

            RoutingPriority.ROUTINE -> {
                // Use suggested persona, plus one complementary if budget allows
                val primary = suggestedPersonaId?.let { id ->
                    enabledPersonas.find { it.id == id }
                }
                if (primary != null && tokenBudgetTracker.isWithinBudget(primary.providerId, ESTIMATED_TOKENS_PER_CALL)) {
                    listOf(primary.id)
                } else {
                    // Fallback to cheapest available persona
                    enabledPersonas
                        .filter { tokenBudgetTracker.isWithinBudget(it.providerId, ESTIMATED_TOKENS_PER_CALL) }
                        .take(1)
                        .map { it.id }
                }
            }

            RoutingPriority.PREFETCH -> {
                // Max 2 personas with remaining budget
                enabledPersonas
                    .filter { tokenBudgetTracker.isWithinBudget(it.providerId, ESTIMATED_TOKENS_PER_CALL) }
                    .sortedByDescending { tokenBudgetTracker.getRemainingBudget(it.providerId) }
                    .take(2)
                    .map { it.id }
            }
        }

        Log.i(TAG, "Route: priority=$priority, source=$triggerSource, selected=$selectedIds")

        return PersonaRoutingDecision(
            priority = priority,
            selectedPersonaIds = selectedIds,
            reason = "priority=$priority, source=$triggerSource, budget-filtered"
        )
    }

    private fun classifyPriority(query: String, triggerSource: String): RoutingPriority {
        return when {
            triggerSource == "user" -> RoutingPriority.IMPORTANT
            triggerSource == "prefetch" -> RoutingPriority.PREFETCH
            // Safety-related event triggers are IMPORTANT
            query.contains("안전") || query.contains("위험") || query.contains("차량") -> RoutingPriority.IMPORTANT
            else -> RoutingPriority.ROUTINE
        }
    }
}
