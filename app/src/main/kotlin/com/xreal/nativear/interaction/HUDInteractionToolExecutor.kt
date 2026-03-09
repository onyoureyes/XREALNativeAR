package com.xreal.nativear.interaction

import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.tools.IToolExecutor
import com.xreal.nativear.tools.ToolResult

/**
 * HUDInteractionToolExecutor — AI가 상호작용 규칙을 프로그래밍하는 도구.
 *
 * ## Gemini/AI가 사용 가능한 도구:
 *
 * ### `create_interaction_rule`
 * 새 상호작용 규칙 생성.
 * ```json
 * {
 *   "name": "탭하면 폭발",
 *   "trigger": "HAND_TAP",
 *   "target": "bottle",
 *   "actions": [
 *     {"type": "SHAKE", "duration": 0.3},
 *     {"type": "EXPLODE", "count": 8}
 *   ],
 *   "cooldown_ms": 3000
 * }
 * ```
 *
 * ### `remove_interaction_rule`
 * 기존 규칙 제거.
 *
 * ### `list_interaction_rules`
 * 현재 활성 규칙 목록 조회.
 *
 * ### `apply_physics`
 * 특정 HUD 요소에 물리 속성 적용.
 *
 * ### `trigger_animation`
 * 즉시 애니메이션 효과 트리거.
 *
 * ### `get_hand_status`
 * 현재 손 감지 상태 + 활성 제스처 조회.
 *
 * ### `create_activity`
 * 짧은 AR 액티비티 (미니게임) 생성.
 */
class HUDInteractionToolExecutor(
    private val interactionManager: HandInteractionManager,
    private val physicsEngine: HUDPhysicsEngine,
    private val templateManager: InteractionTemplateManager,
    private val eventBus: GlobalEventBus
) : IToolExecutor {

    override val supportedTools = setOf(
        "create_interaction_rule",
        "remove_interaction_rule",
        "list_interaction_rules",
        "apply_physics",
        "trigger_animation",
        "get_hand_status",
        "create_activity",
        "save_interaction_template"
    )

    override suspend fun execute(name: String, args: Map<String, Any?>): ToolResult {
        return when (name) {
            "create_interaction_rule" -> createRule(args)
            "remove_interaction_rule" -> removeRule(args)
            "list_interaction_rules" -> listRules()
            "apply_physics" -> applyPhysics(args)
            "trigger_animation" -> triggerAnimation(args)
            "get_hand_status" -> getHandStatus()
            "create_activity" -> createActivity(args)
            "save_interaction_template" -> saveTemplate(args)
            else -> ToolResult(false, "Unknown tool: $name")
        }
    }

    private fun createRule(args: Map<String, Any?>): ToolResult {
        try {
            val ruleName = args["name"] as? String ?: return ToolResult(false, "Missing 'name'")
            val triggerStr = args["trigger"] as? String ?: return ToolResult(false, "Missing 'trigger'")
            val targetFilter = args["target"] as? String ?: "*"
            val cooldownMs = (args["cooldown_ms"] as? Number)?.toLong() ?: 500L

            val trigger = try {
                TriggerType.valueOf(triggerStr)
            } catch (e: Exception) {
                return ToolResult(false, "Invalid trigger: $triggerStr. Valid: ${TriggerType.values().joinToString()}")
            }

            @Suppress("UNCHECKED_CAST")
            val actionsRaw = args["actions"] as? List<Map<String, Any?>>
                ?: return ToolResult(false, "Missing 'actions' list")

            val actions = actionsRaw.map { actionMap ->
                val typeStr = actionMap["type"] as? String
                    ?: return ToolResult(false, "Action missing 'type'")
                val actionType = try {
                    ActionType.valueOf(typeStr)
                } catch (e: Exception) {
                    return ToolResult(false, "Invalid action type: $typeStr. Valid: ${ActionType.values().joinToString()}")
                }
                val params = actionMap.filterKeys { it != "type" }
                    .filterValues { it != null }
                    .mapValues { it.value!! }
                RuleAction(actionType, params)
            }

            val rule = InteractionRule(
                id = "ai_rule_${System.currentTimeMillis()}",
                name = ruleName,
                trigger = trigger,
                targetFilter = targetFilter,
                actions = actions,
                priority = 10,  // AI 생성 규칙은 기본보다 높은 우선순위
                cooldownMs = cooldownMs
            )

            interactionManager.addRule(rule)

            return ToolResult(true,
                "Rule created: '$ruleName' (trigger=$triggerStr, target=$targetFilter, " +
                "${actions.size} actions). Total rules: ${interactionManager.getRuleCount()}")
        } catch (e: Exception) {
            return ToolResult(false, "Failed to create rule: ${e.message}")
        }
    }

    private fun removeRule(args: Map<String, Any?>): ToolResult {
        val ruleId = args["id"] as? String
            ?: args["name"] as? String
            ?: return ToolResult(false, "Missing 'id' or 'name'")

        interactionManager.removeRule(ruleId)
        return ToolResult(true, "Rule removed: $ruleId")
    }

    private fun listRules(): ToolResult {
        val count = interactionManager.getRuleCount()
        return ToolResult(true,
            "Active interaction rules: $count. " +
            "Physics bodies: ${physicsEngine.getAllBodies().size}. " +
            "Score: ${interactionManager.score}")
    }

    private fun applyPhysics(args: Map<String, Any?>): ToolResult {
        val elementId = args["id"] as? String ?: return ToolResult(false, "Missing 'id'")
        val x = (args["x"] as? Number)?.toFloat() ?: 50f
        val y = (args["y"] as? Number)?.toFloat() ?: 50f
        val gravity = args["gravity"] as? Boolean ?: false
        val bounciness = (args["bounciness"] as? Number)?.toFloat() ?: 0.5f
        val friction = (args["friction"] as? Number)?.toFloat() ?: 0.95f
        val lifetime = (args["lifetime"] as? Number)?.toFloat() ?: Float.MAX_VALUE

        physicsEngine.addBody(
            elementId, x, y,
            isGravity = gravity, bounciness = bounciness,
            friction = friction, lifetime = lifetime
        )

        val vx = (args["velocity_x"] as? Number)?.toFloat()
        val vy = (args["velocity_y"] as? Number)?.toFloat()
        if (vx != null || vy != null) {
            physicsEngine.setVelocity(elementId, vx ?: 0f, vy ?: 0f)
        }

        return ToolResult(true, "Physics applied to '$elementId' (gravity=$gravity, bounce=$bounciness)")
    }

    private fun triggerAnimation(args: Map<String, Any?>): ToolResult {
        val elementId = args["id"] as? String ?: return ToolResult(false, "Missing 'id'")
        val typeStr = args["type"] as? String ?: return ToolResult(false, "Missing 'type'")
        val duration = (args["duration"] as? Number)?.toFloat() ?: 1f
        val intensity = (args["intensity"] as? Number)?.toFloat() ?: 1f

        val animType = try {
            AnimationType.valueOf(typeStr)
        } catch (e: Exception) {
            return ToolResult(false, "Invalid animation type: $typeStr. Valid: ${AnimationType.values().joinToString()}")
        }

        physicsEngine.addAnimation(elementId, animType, duration, intensity)
        return ToolResult(true, "Animation '$typeStr' triggered on '$elementId' (${duration}s)")
    }

    private fun getHandStatus(): ToolResult {
        val isActive = interactionManager.isActive
        val bodies = physicsEngine.getAllBodies()
        val score = interactionManager.score

        return ToolResult(true,
            "Hand interaction active: $isActive, " +
            "Physics bodies: ${bodies.size}, " +
            "Score: $score, " +
            "Rules: ${interactionManager.getRuleCount()}")
    }

    private fun createActivity(args: Map<String, Any?>): ToolResult {
        val activityName = args["name"] as? String ?: "AR Activity"
        val description = args["description"] as? String ?: ""
        val durationSec = (args["duration_seconds"] as? Number)?.toInt() ?: 60

        @Suppress("UNCHECKED_CAST")
        val rules = args["rules"] as? List<Map<String, Any?>> ?: emptyList()

        // 규칙들 생성
        var rulesCreated = 0
        for (ruleMap in rules) {
            val result = createRule(ruleMap)
            if (result.success) rulesCreated++
        }

        return ToolResult(true,
            "Activity '$activityName' created: $description. " +
            "$rulesCreated rules set up. Duration: ${durationSec}s")
    }

    private fun saveTemplate(args: Map<String, Any?>): ToolResult {
        val ruleName = args["name"] as? String ?: return ToolResult(false, "Missing 'name'")
        val triggerStr = args["trigger"] as? String ?: return ToolResult(false, "Missing 'trigger'")
        val targetFilter = args["target"] as? String ?: "*"
        val persona = args["creator_persona"] as? String ?: ""

        @Suppress("UNCHECKED_CAST")
        val contextTags = (args["context_tags"] as? List<String>) ?: emptyList()

        @Suppress("UNCHECKED_CAST")
        val actionsRaw = args["actions"] as? List<Map<String, Any?>> ?: emptyList()

        val actions = actionsRaw.mapNotNull { actionMap ->
            try {
                val type = ActionType.valueOf(actionMap["type"] as? String ?: return@mapNotNull null)
                val params = actionMap.filterKeys { it != "type" }.filterValues { it != null }.mapValues { it.value!! }
                RuleAction(type, params)
            } catch (e: Exception) { null }
        }

        val trigger = try { TriggerType.valueOf(triggerStr) } catch (e: Exception) {
            return ToolResult(false, "Invalid trigger: $triggerStr")
        }

        val rule = InteractionRule(
            id = "tmpl_${System.currentTimeMillis()}",
            name = ruleName,
            trigger = trigger,
            targetFilter = targetFilter,
            actions = actions,
            priority = 5
        )

        templateManager.saveAsTemplate(rule, persona, contextTags)
        return ToolResult(true, "Template saved: '$ruleName' with ${actions.size} actions")
    }
}
