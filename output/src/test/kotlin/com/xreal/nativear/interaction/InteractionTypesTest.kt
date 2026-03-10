package com.xreal.nativear.interaction

import org.junit.Test
import org.junit.Assert.*
import kotlin.math.sqrt

/**
 * InteractionTypes 데이터 타입 테스트.
 */
class InteractionTypesTest {

    // ── PhysicsBody ──

    @Test
    fun `PhysicsBody 생성 기본값`() {
        val body = PhysicsBody(id = "test", x = 10f, y = 20f)
        assertEquals(0f, body.vx, 0.01f)
        assertEquals(0f, body.vy, 0.01f)
        assertEquals(1f, body.mass, 0.01f)
        assertEquals(0.95f, body.friction, 0.01f)
        assertEquals(0.5f, body.bounciness, 0.01f)
        assertFalse(body.isStatic)
        assertFalse(body.isGravityEnabled)
        assertNull(body.anchorX)
        assertNull(body.anchorY)
        assertEquals(Float.MAX_VALUE, body.lifetime, 0.01f)
        assertEquals(0f, body.age, 0.01f)
    }

    @Test
    fun `PhysicsBody speed 계산`() {
        val body = PhysicsBody(id = "speed", x = 0f, y = 0f, vx = 3f, vy = 4f)
        assertEquals(5f, body.speed, 0.01f)
    }

    @Test
    fun `PhysicsBody speed 정지 시 0`() {
        val body = PhysicsBody(id = "still", x = 0f, y = 0f)
        assertEquals(0f, body.speed, 0.01f)
    }

    @Test
    fun `PhysicsBody anchorDistance 계산`() {
        val body = PhysicsBody(
            id = "anchored", x = 53f, y = 54f,
            anchorX = 50f, anchorY = 50f
        )
        assertEquals(5f, body.anchorDistance, 0.01f)
    }

    @Test
    fun `PhysicsBody anchorDistance 앵커 없으면 0`() {
        val body = PhysicsBody(id = "free", x = 50f, y = 50f)
        assertEquals(0f, body.anchorDistance, 0.01f)
    }

    // ── AnimationType ──

    @Test
    fun `AnimationType 전체 10개 값`() {
        assertEquals(10, AnimationType.entries.size)
    }

    @Test
    fun `AnimationType 필수 값 포함`() {
        val types = AnimationType.entries.map { it.name }.toSet()
        assertTrue(types.contains("SHAKE"))
        assertTrue(types.contains("BOUNCE"))
        assertTrue(types.contains("FADE_IN"))
        assertTrue(types.contains("FADE_OUT"))
        assertTrue(types.contains("SCALE_PULSE"))
        assertTrue(types.contains("GLOW"))
        assertTrue(types.contains("EXPLODE"))
        assertTrue(types.contains("TRAIL"))
        assertTrue(types.contains("SPIN"))
        assertTrue(types.contains("COLOR_SHIFT"))
    }

    // ── ActiveAnimation ──

    @Test
    fun `ActiveAnimation progress 계산`() {
        val anim = ActiveAnimation(
            targetId = "t", type = AnimationType.SHAKE,
            duration = 2.0f, elapsed = 1.0f
        )
        assertEquals(0.5f, anim.progress, 0.01f)
    }

    @Test
    fun `ActiveAnimation progress 클램핑`() {
        val anim = ActiveAnimation(
            targetId = "t", type = AnimationType.SHAKE,
            duration = 1.0f, elapsed = 5.0f
        )
        assertEquals(1.0f, anim.progress, 0.01f)
    }

    @Test
    fun `ActiveAnimation isComplete`() {
        val incomplete = ActiveAnimation("t", AnimationType.SHAKE, 1.0f, 0.5f)
        assertFalse(incomplete.isComplete)

        val complete = ActiveAnimation("t", AnimationType.SHAKE, 1.0f, 1.5f)
        assertTrue(complete.isComplete)
    }

    // ── TriggerType ──

    @Test
    fun `TriggerType 전체 11개 값`() {
        assertEquals(11, TriggerType.entries.size)
    }

    // ── ActionType ──

    @Test
    fun `ActionType 전체 17개 값`() {
        assertEquals(17, ActionType.entries.size)
    }

    // ── InteractionRule ──

    @Test
    fun `InteractionRule 생성 기본값`() {
        val rule = InteractionRule(
            id = "rule_1",
            name = "탭 흔들기",
            trigger = TriggerType.HAND_TAP,
            actions = listOf(RuleAction(ActionType.SHAKE))
        )
        assertEquals("*", rule.targetFilter)
        assertEquals(0, rule.priority)
        assertEquals(500L, rule.cooldownMs)
        assertTrue(rule.isRepeatable)
    }

    @Test
    fun `InteractionRule 커스텀 파라미터`() {
        val rule = InteractionRule(
            id = "rule_2",
            name = "핀치 잡기",
            trigger = TriggerType.HAND_PINCH,
            targetFilter = "person",
            actions = listOf(
                RuleAction(ActionType.GRAB),
                RuleAction(ActionType.GLOW, mapOf("duration" to 1.0))
            ),
            priority = 5,
            cooldownMs = 1000L,
            isRepeatable = false
        )
        assertEquals("person", rule.targetFilter)
        assertEquals(5, rule.priority)
        assertEquals(1000L, rule.cooldownMs)
        assertFalse(rule.isRepeatable)
        assertEquals(2, rule.actions.size)
    }

    // ── RuleAction ──

    @Test
    fun `RuleAction getFloat 정상 동작`() {
        val action = RuleAction(ActionType.SHAKE, mapOf("duration" to 0.5f))
        assertEquals(0.5f, action.getFloat("duration"), 0.01f)
    }

    @Test
    fun `RuleAction getFloat 기본값 반환`() {
        val action = RuleAction(ActionType.SHAKE)
        assertEquals(1.0f, action.getFloat("missing", 1.0f), 0.01f)
    }

    @Test
    fun `RuleAction getString 정상 동작`() {
        val action = RuleAction(ActionType.SPEAK_TTS, mapOf("text" to "hello"))
        assertEquals("hello", action.getString("text"))
    }

    @Test
    fun `RuleAction getString 기본값 반환`() {
        val action = RuleAction(ActionType.SPEAK_TTS)
        assertEquals("default", action.getString("missing", "default"))
    }

    @Test
    fun `RuleAction getInt 정상 동작`() {
        val action = RuleAction(ActionType.SCORE_POINT, mapOf("points" to 10))
        assertEquals(10, action.getInt("points"))
    }

    // ── InteractionTemplate ──

    @Test
    fun `InteractionTemplate 생성 기본값`() {
        val template = InteractionTemplate(
            name = "기본 템플릿",
            triggerType = "HAND_TAP",
            actionsJson = "[]"
        )
        assertEquals(0L, template.id)
        assertEquals("*", template.targetFilter)
        assertEquals(0, template.useCount)
        assertEquals(1.0f, template.successRate, 0.01f)
        assertEquals("", template.contextTags)
        assertEquals("", template.creatorPersona)
    }
}
