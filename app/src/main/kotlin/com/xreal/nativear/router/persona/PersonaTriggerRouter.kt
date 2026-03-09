package com.xreal.nativear.router.persona

import android.util.Log
import com.xreal.nativear.ai.MultiAIOrchestrator
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.router.BaseRouter
import com.xreal.nativear.router.DecisionLogger
import com.xreal.nativear.router.RouterDecision
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Router that auto-dispatches personas based on event-trigger rules.
 * Extends BaseRouter to participate in the router infrastructure.
 */
class PersonaTriggerRouter(
    eventBus: GlobalEventBus,
    decisionLogger: DecisionLogger,
    private val orchestrator: MultiAIOrchestrator,
    private val personaRouter: PersonaRouter? = null,
    private val rules: List<TriggerRule> = PredefinedTriggers.getDefaultRules()
) : BaseRouter("persona_trigger", eventBus, decisionLogger) {

    private val lastTriggerTimes = ConcurrentHashMap<String, Long>()

    override fun shouldProcess(event: XRealEvent): Boolean {
        return rules.any { rule ->
            rule.eventType.isInstance(event) && rule.condition(event)
        }
    }

    override fun evaluate(event: XRealEvent): RouterDecision? {
        val now = System.currentTimeMillis()
        val matchingRules = rules
            .filter { rule ->
                rule.eventType.isInstance(event) && rule.condition(event)
            }
            .filter { rule ->
                val key = "${rule.personaId}:${rule.eventType.simpleName}"
                val lastTime = lastTriggerTimes[key] ?: 0L
                now - lastTime >= rule.cooldownMs
            }
            .sortedByDescending { it.priority }

        if (matchingRules.isEmpty()) return null

        val topRule = matchingRules.first()
        val key = "${topRule.personaId}:${topRule.eventType.simpleName}"
        lastTriggerTimes[key] = now

        return RouterDecision(
            routerId = id,
            action = "TRIGGER_PERSONA",
            confidence = 0.8f,
            reason = "Event ${topRule.eventType.simpleName} → persona ${topRule.personaId}",
            priority = topRule.priority,
            metadata = mapOf(
                "personaId" to topRule.personaId,
                "eventType" to (topRule.eventType.simpleName ?: "Unknown"),
                "speakResult" to topRule.speakResult,
                "query" to topRule.queryBuilder(event),
                "context" to (topRule.contextBuilder?.invoke(event) ?: "")
            )
        )
    }

    override fun act(decision: RouterDecision) {
        val personaId = decision.metadata["personaId"] as? String ?: return
        val query = decision.metadata["query"] as? String ?: return
        val context = decision.metadata["context"] as? String
        val speakResult = decision.metadata["speakResult"] as? Boolean ?: false

        // Budget check via PersonaRouter
        val routingDecision = personaRouter?.route(
            query = query,
            triggerSource = "event",
            suggestedPersonaId = personaId
        )
        if (routingDecision != null && routingDecision.selectedPersonaIds.isEmpty()) {
            Log.w(TAG, "Budget exhausted, skipping persona trigger for $personaId")
            return
        }
        val targetPersonaId = routingDecision?.selectedPersonaIds?.firstOrNull() ?: personaId

        Log.i(TAG, "Triggering persona $targetPersonaId: ${query.take(60)}...")

        scope.launch {
            try {
                val response = orchestrator.dispatchSingle(
                    query = query,
                    personaId = targetPersonaId,
                    context = context
                )

                Log.i(TAG, "Persona $personaId responded: ${response.text?.take(100)}")

                if (speakResult && !response.text.isNullOrBlank() && !response.text.startsWith("Error")) {
                    eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(response.text))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Persona trigger failed for $personaId: ${e.message}")
            }
        }
    }
}
