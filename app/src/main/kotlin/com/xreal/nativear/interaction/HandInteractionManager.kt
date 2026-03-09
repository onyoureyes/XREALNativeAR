package com.xreal.nativear.interaction

import android.util.Log
import com.xreal.nativear.DrawCommand
import com.xreal.nativear.DrawElement
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.hand.GestureEvent
import com.xreal.nativear.hand.HandData
import com.xreal.nativear.hand.HandGestureRecognizer
import com.xreal.nativear.hand.HandGestureType
import com.xreal.nativear.spatial.AnchorLabel2D
import kotlinx.coroutines.*
import kotlin.math.sqrt

/**
 * HandInteractionManager — 손↔HUD 요소 상호작용 오케스트레이터.
 *
 * ## 역할
 * 1. 손 제스처 이벤트 수신
 * 2. 화면 좌표 기반 앵커 라벨/DrawElement 충돌 감지
 * 3. InteractionRule 매칭 → 액션 실행
 * 4. HUDPhysicsEngine으로 물리 효과 적용
 * 5. 결과를 DrawCommand/TTS로 출력
 *
 * ## 이벤트 흐름
 * ```
 * HandsDetected → HandGestureRecognizer → GestureEvent
 *   ↓
 * hitTest(gesture.screenXY, anchorLabels) → 충돌 앵커 찾기
 *   ↓
 * matchRule(gesture.type, anchor.label) → InteractionRule
 *   ↓
 * executeActions(rule.actions) → PhysicsEngine + DrawCommand + TTS
 *   ↓
 * PhysicsEngine.update() → 위치 업데이트 → DrawCommand.Modify
 * ```
 */
class HandInteractionManager(
    private val eventBus: GlobalEventBus,
    private val physicsEngine: HUDPhysicsEngine,
    private val gestureRecognizer: HandGestureRecognizer,
    private val log: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "HandInteraction"

        /** 히트 테스트 반경 (퍼센트 좌표) — 손가락 끝↔앵커 라벨 */
        const val HIT_RADIUS_PCT = 5f

        /** 물리 업데이트 주기 (ms) */
        const val PHYSICS_UPDATE_INTERVAL_MS = 16L  // ~60fps

        /** 잡힌 요소의 손 추적 보간 속도 */
        const val GRAB_SMOOTHING = 0.3f

        /** 던지기 속도 배수 */
        const val THROW_VELOCITY_MULTIPLIER = 2f

        /** 자동 클린업 간격 (초) — 물리 바디 만료 체크 */
        const val CLEANUP_INTERVAL_SEC = 5f
    }

    // ── 상태 ──

    /** 현재 화면에 보이는 앵커 라벨 (SpatialAnchorManager에서 업데이트) */
    @Volatile
    private var currentAnchorLabels: List<AnchorLabel2D> = emptyList()

    /** 활성 상호작용 규칙 */
    private val rules = mutableListOf<InteractionRule>()

    /** 잡힌 요소 (handIndex → elementId) */
    private val grabbedElements = mutableMapOf<Int, String>()

    /** 규칙 쿨다운 추적 (elementId+ruleId → lastTriggerTime) */
    private val cooldowns = mutableMapOf<String, Long>()

    /** 물리 루프 Job */
    private var physicsJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** 호버 상태 추적 (handIndex → hoveredElementId) */
    private val hoveredElements = mutableMapOf<Int, String>()

    /** 점수 (게이미피케이션) */
    var score: Int = 0
        private set

    @Volatile
    var isActive = false
        private set

    // ── Public API ──

    /** 시작 — 물리 루프 + 이벤트 구독 */
    fun start() {
        if (isActive) return
        isActive = true

        // 기본 상호작용 규칙 등록
        registerDefaultRules()

        // 물리 업데이트 루프
        physicsJob = scope.launch {
            while (isActive) {
                val expired = physicsEngine.update(PHYSICS_UPDATE_INTERVAL_MS / 1000f)

                // 만료 바디 → DrawCommand.Remove
                for (id in expired) {
                    eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(
                        DrawCommand.Remove("phys_$id")
                    ))
                }

                delay(PHYSICS_UPDATE_INTERVAL_MS)
            }
        }

        // 이벤트 구독
        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is XRealEvent.PerceptionEvent.HandsDetected -> {
                        processHands(event.hands, event.timestamp)
                    }
                    is XRealEvent.ActionRequest.AnchorLabelsUpdate -> {
                        currentAnchorLabels = event.visibleLabels
                    }
                    else -> {}
                }
            }
        }

        log("HandInteractionManager started (${rules.size} rules)")
    }

    /** 정지 */
    fun stop() {
        isActive = false
        physicsJob?.cancel()
        physicsEngine.clear()
        grabbedElements.clear()
        hoveredElements.clear()
        cooldowns.clear()
    }

    /** 앵커 라벨 목록 업데이트 (SpatialAnchorManager에서 호출) */
    fun updateAnchorLabels(labels: List<AnchorLabel2D>) {
        currentAnchorLabels = labels
    }

    /** 상호작용 규칙 추가 */
    fun addRule(rule: InteractionRule) {
        rules.add(rule)
        rules.sortByDescending { it.priority }
    }

    /** 상호작용 규칙 제거 */
    fun removeRule(id: String) {
        rules.removeAll { it.id == id }
    }

    /** 모든 규칙 교체 */
    fun setRules(newRules: List<InteractionRule>) {
        rules.clear()
        rules.addAll(newRules.sortedByDescending { it.priority })
    }

    /** 현재 활성 규칙 수 */
    fun getRuleCount(): Int = rules.size

    // ── 핵심 처리 ──

    private fun processHands(hands: List<HandData>, timestamp: Long) {
        // 1. 제스처 인식
        val gestures = gestureRecognizer.recognize(hands)

        // ★ P1-3: HandGestureDetected 이벤트 발행 → OutputCoordinator가 HUD에 표시
        if (gestures.isNotEmpty()) {
            eventBus.publish(XRealEvent.PerceptionEvent.HandGestureDetected(
                gestures = gestures,
                timestamp = timestamp
            ))
        }

        // 2. 각 제스처에 대해 상호작용 처리
        for (gesture in gestures) {
            processGesture(gesture)
        }

        // 3. 잡힌 요소 위치 업데이트 (핀치 이동 시)
        updateGrabbedElements(hands)

        // 4. 호버 피드백
        updateHoverFeedback(hands)
    }

    private fun processGesture(gesture: GestureEvent) {
        val now = gesture.timestamp

        // 히트 테스트: 제스처 위치에 가장 가까운 앵커 라벨 찾기
        val hitTarget = hitTest(gesture.screenX, gesture.screenY)

        // 특수 제스처 처리 (히트 여부 무관)
        when (gesture.gesture) {
            HandGestureType.DRAW -> {
                // 검지 궤적 → 화면에 선 그리기
                val trail = gestureRecognizer.currentDrawTrail
                if (trail.size >= 2) {
                    val last = trail[trail.size - 1]
                    val prev = trail[trail.size - 2]
                    val lineId = "draw_trail_${trail.size}"
                    eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(
                        DrawCommand.Add(DrawElement.Line(
                            id = lineId,
                            x1 = prev.first, y1 = prev.second,
                            x2 = last.first, y2 = last.second,
                            color = "#FF6600", opacity = 0.8f,
                            strokeWidth = 4f
                        ))
                    ))
                }
                return
            }
            HandGestureType.FIST -> {
                // 주먹 → 드로잉 궤적 클리어 + 전체 인터랙션 요소 클리어
                gestureRecognizer.clearDrawTrail()
                clearDrawTrails()
                return
            }
            else -> {}
        }

        // 히트 대상 없으면 스킵 (대상 필요 제스처)
        if (hitTarget == null) return

        // 매칭 규칙 찾기
        val triggerType = gestureToTrigger(gesture.gesture) ?: return
        val matchedRule = findMatchingRule(triggerType, hitTarget.label, now, hitTarget.anchorId)
            ?: return

        // 규칙 실행
        executeRule(matchedRule, hitTarget, gesture)

        // 쿨다운 기록
        cooldowns["${hitTarget.anchorId}_${matchedRule.id}"] = now
    }

    private fun executeRule(rule: InteractionRule, target: AnchorLabel2D, gesture: GestureEvent) {
        log("Rule triggered: ${rule.name} on '${target.label}' by ${gesture.gesture}")

        for (action in rule.actions) {
            executeAction(action, target, gesture)
        }
    }

    private fun executeAction(action: RuleAction, target: AnchorLabel2D, gesture: GestureEvent) {
        val elementId = target.anchorId

        when (action.type) {
            ActionType.SHAKE -> {
                val duration = action.getFloat("duration", 0.5f)
                physicsEngine.addAnimation(elementId, AnimationType.SHAKE, duration, intensity = 1f)
            }

            ActionType.FALL -> {
                val delay = action.getFloat("delay", 0f)
                scope.launch {
                    if (delay > 0) delay((delay * 1000).toLong())
                    // 물리 바디 생성 (또는 기존 바디에 중력 활성화)
                    val body = physicsEngine.getBody(elementId)
                    if (body != null) {
                        body.isGravityEnabled = true
                        body.anchorX = null  // 스프링 해제
                        body.anchorY = null
                        body.lifetime = 3f  // 3초 후 소멸
                    } else {
                        physicsEngine.addBody(
                            elementId, target.screenXPercent, target.screenYPercent,
                            isGravity = true, bounciness = 0.4f, lifetime = 3f
                        )
                    }
                    physicsEngine.addAnimation(elementId, AnimationType.FADE_OUT, 3f)
                }
            }

            ActionType.BOUNCE -> {
                val force = action.getFloat("force", -150f)
                val body = physicsEngine.getBody(elementId)
                    ?: physicsEngine.addBody(
                        elementId, target.screenXPercent, target.screenYPercent,
                        bounciness = 0.7f,
                        anchorX = target.screenXPercent,
                        anchorY = target.screenYPercent,
                        springK = 3f
                    )
                physicsEngine.applyForce(elementId, 0f, force)
                physicsEngine.addAnimation(elementId, AnimationType.BOUNCE, 1.5f)
            }

            ActionType.EXPLODE -> {
                // 폭발: 작은 파편 생성
                val count = action.getInt("count", 5)
                for (i in 0 until count) {
                    val angle = (i.toFloat() / count) * 2 * Math.PI.toFloat()
                    val speed = 100f + (Math.random() * 100).toFloat()
                    val fragId = "${elementId}_frag_$i"
                    physicsEngine.addBody(
                        fragId, target.screenXPercent, target.screenYPercent,
                        isGravity = true, bounciness = 0.3f, lifetime = 2f,
                        friction = 0.98f
                    )
                    physicsEngine.setVelocity(
                        fragId,
                        kotlin.math.cos(angle) * speed,
                        kotlin.math.sin(angle) * speed - 100f
                    )
                    physicsEngine.addAnimation(fragId, AnimationType.FADE_OUT, 2f)

                    // 파편 DrawElement 생성
                    eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(
                        DrawCommand.Add(DrawElement.Circle(
                            id = "phys_$fragId",
                            cx = target.screenXPercent,
                            cy = target.screenYPercent,
                            radius = 0.5f,
                            color = "#FF4444",
                            opacity = 0.9f,
                            filled = true
                        ))
                    ))
                }
                physicsEngine.addAnimation(elementId, AnimationType.FADE_OUT, 0.3f)
            }

            ActionType.GRAB -> {
                // 핀치로 잡기
                grabbedElements[gesture.handIndex] = elementId
                val body = physicsEngine.getBody(elementId)
                if (body != null) {
                    body.isGravityEnabled = false
                    body.anchorX = null
                    body.anchorY = null
                    body.vx = 0f
                    body.vy = 0f
                } else {
                    physicsEngine.addBody(
                        elementId, target.screenXPercent, target.screenYPercent,
                        isStatic = true
                    )
                }
                physicsEngine.addAnimation(elementId, AnimationType.SCALE_PULSE, 0.5f, intensity = 0.5f)
            }

            ActionType.THROW -> {
                // 핀치 해제 시 관성으로 던지기
                val body = physicsEngine.getBody(elementId)
                if (body != null) {
                    body.isStatic = false
                    body.isGravityEnabled = true
                    body.vx *= THROW_VELOCITY_MULTIPLIER
                    body.vy *= THROW_VELOCITY_MULTIPLIER
                    body.lifetime = 5f
                    body.age = 0f
                }
                grabbedElements.entries.removeAll { it.value == elementId }
                physicsEngine.addAnimation(elementId, AnimationType.FADE_OUT, 5f)
            }

            ActionType.GLOW -> {
                val duration = action.getFloat("duration", 2f)
                physicsEngine.addAnimation(elementId, AnimationType.GLOW, duration)
            }

            ActionType.FADE_OUT -> {
                val duration = action.getFloat("duration", 1f)
                physicsEngine.addAnimation(elementId, AnimationType.FADE_OUT, duration)
            }

            ActionType.COLOR_CHANGE -> {
                val color = action.getString("color", "#FF0000")
                eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(
                    DrawCommand.Modify(elementId, mapOf("color" to color))
                ))
            }

            ActionType.SCALE -> {
                val duration = action.getFloat("duration", 1f)
                physicsEngine.addAnimation(elementId, AnimationType.SCALE_PULSE, duration)
            }

            ActionType.SPEAK_TTS -> {
                val text = action.getString("text", target.label)
                eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(text))
            }

            ActionType.SCORE_POINT -> {
                val points = action.getInt("points", 1)
                score += points
                log("Score: $score (+$points)")
                // 점수 표시
                eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(
                    DrawCommand.Add(DrawElement.Text(
                        id = "score_popup_${System.currentTimeMillis()}",
                        x = gesture.screenX, y = gesture.screenY - 5f,
                        text = "+$points",
                        color = "#FFD700", opacity = 1f,
                        size = 30f, bold = true
                    ))
                ))
            }

            ActionType.SPAWN -> {
                val label = action.getString("label", "new")
                val color = action.getString("color", "#FFFFFF")
                val spawnId = "spawn_${System.currentTimeMillis()}"
                eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(
                    DrawCommand.Add(DrawElement.Text(
                        id = spawnId,
                        x = gesture.screenX, y = gesture.screenY,
                        text = label, color = color, opacity = 1f,
                        size = 20f, bold = false
                    ))
                ))
                physicsEngine.addBody(spawnId, gesture.screenX, gesture.screenY,
                    isGravity = true, bounciness = 0.6f, lifetime = 10f)
            }

            ActionType.MOVE_TO -> {
                val toX = action.getFloat("x", 50f)
                val toY = action.getFloat("y", 50f)
                val body = physicsEngine.getBody(elementId)
                if (body != null) {
                    body.anchorX = toX
                    body.anchorY = toY
                    body.springK = 5f
                }
            }

            ActionType.DRAW_TRAIL -> {} // DRAW 제스처에서 이미 처리
            ActionType.CHAIN -> {} // 연쇄 규칙 (향후 구현)
            ActionType.PLAY_SOUND -> {} // 효과음 (향후 구현)
        }
    }

    // ── 히트 테스트 ──

    private fun hitTest(screenX: Float, screenY: Float): AnchorLabel2D? {
        var bestTarget: AnchorLabel2D? = null
        var bestDist = HIT_RADIUS_PCT

        for (label in currentAnchorLabels) {
            val dx = screenX - label.screenXPercent
            val dy = screenY - label.screenYPercent
            val dist = sqrt(dx * dx + dy * dy)
            if (dist < bestDist) {
                bestDist = dist
                bestTarget = label
            }
        }
        return bestTarget
    }

    // ── 규칙 매칭 ──

    private fun findMatchingRule(trigger: TriggerType, label: String, now: Long, elementId: String): InteractionRule? {
        for (rule in rules) {
            if (rule.trigger != trigger) continue
            if (rule.targetFilter != "*" && !label.contains(rule.targetFilter, ignoreCase = true)) continue

            // 쿨다운 체크
            val cooldownKey = "${elementId}_${rule.id}"
            val lastTrigger = cooldowns[cooldownKey] ?: 0L
            if (now - lastTrigger < rule.cooldownMs) continue

            return rule
        }
        return null
    }

    // ── 잡기 업데이트 ──

    private fun updateGrabbedElements(hands: List<HandData>) {
        val toRelease = mutableListOf<Int>()

        for ((handIndex, elementId) in grabbedElements) {
            if (handIndex >= hands.size) {
                toRelease.add(handIndex)
                continue
            }

            val hand = hands[handIndex]
            val (fingerX, fingerY) = hand.indexTipPercent
            val body = physicsEngine.getBody(elementId)
            if (body != null) {
                // 부드러운 추적
                body.x += (fingerX - body.x) * GRAB_SMOOTHING
                body.y += (fingerY - body.y) * GRAB_SMOOTHING
                // 속도 추정 (던지기용)
                body.vx = (fingerX - body.x) / 0.016f * 0.3f
                body.vy = (fingerY - body.y) / 0.016f * 0.3f
            }
        }

        for (h in toRelease) {
            val elementId = grabbedElements.remove(h) ?: continue
            // 잡기 해제 → 던지기 또는 복원
            val body = physicsEngine.getBody(elementId)
            if (body != null && body.speed > 30f) {
                // 빠른 이동 중 해제 → 던지기
                body.isStatic = false
                body.isGravityEnabled = true
                body.lifetime = 5f
            } else if (body != null) {
                // 느린 이동 → 원래 위치로 복원
                body.isStatic = false
            }
        }
    }

    // ── 호버 피드백 ──

    private fun updateHoverFeedback(hands: List<HandData>) {
        for ((index, hand) in hands.withIndex()) {
            val (px, py) = hand.indexTipPercent
            val target = hitTest(px, py)
            val prevHovered = hoveredElements[index]

            if (target != null && target.anchorId != prevHovered) {
                // 새 호버 → 글로우 효과
                physicsEngine.addAnimation(target.anchorId, AnimationType.GLOW, 0.5f, intensity = 0.5f)
                hoveredElements[index] = target.anchorId
            } else if (target == null && prevHovered != null) {
                hoveredElements.remove(index)
            }
        }
    }

    // ── 유틸리티 ──

    private fun gestureToTrigger(gesture: HandGestureType): TriggerType? = when (gesture) {
        HandGestureType.TAP -> TriggerType.HAND_TAP
        HandGestureType.PINCH -> TriggerType.HAND_PINCH
        HandGestureType.PINCH_MOVE -> TriggerType.HAND_PINCH
        HandGestureType.POINT -> TriggerType.HAND_POINT
        HandGestureType.FIST -> TriggerType.HAND_FIST
        HandGestureType.OPEN_PALM -> TriggerType.HAND_OPEN_PALM
        HandGestureType.SWIPE_LEFT, HandGestureType.SWIPE_RIGHT,
        HandGestureType.SWIPE_UP, HandGestureType.SWIPE_DOWN -> TriggerType.HAND_SWIPE
        HandGestureType.DRAW -> TriggerType.HAND_DRAW
        else -> null
    }

    private fun clearDrawTrails() {
        // draw_trail_* 요소 모두 제거
        eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(DrawCommand.ClearAll))
    }

    // ── 기본 규칙 등록 ──

    private fun registerDefaultRules() {
        // 탭 → 흔들기 + 바운스
        addRule(InteractionRule(
            id = "default_tap_shake",
            name = "탭하면 흔들기",
            trigger = TriggerType.HAND_TAP,
            targetFilter = "*",
            actions = listOf(
                RuleAction(ActionType.SHAKE, mapOf("duration" to 0.5f)),
                RuleAction(ActionType.BOUNCE, mapOf("force" to -100f))
            ),
            priority = 0
        ))

        // 핀치 → 잡기
        addRule(InteractionRule(
            id = "default_pinch_grab",
            name = "핀치로 잡기",
            trigger = TriggerType.HAND_PINCH,
            targetFilter = "*",
            actions = listOf(RuleAction(ActionType.GRAB)),
            priority = 1
        ))

        // 스와이프 → 떨어뜨리기
        addRule(InteractionRule(
            id = "default_swipe_fall",
            name = "스와이프로 떨어뜨리기",
            trigger = TriggerType.HAND_SWIPE,
            targetFilter = "*",
            actions = listOf(
                RuleAction(ActionType.SHAKE, mapOf("duration" to 0.3f)),
                RuleAction(ActionType.FALL, mapOf("delay" to 0.3f))
            ),
            priority = 0,
            cooldownMs = 2000L
        ))

        // 손바닥 → 발광
        addRule(InteractionRule(
            id = "default_palm_glow",
            name = "손바닥으로 발광",
            trigger = TriggerType.HAND_OPEN_PALM,
            targetFilter = "*",
            actions = listOf(RuleAction(ActionType.GLOW, mapOf("duration" to 2f))),
            priority = 0
        ))

        log("Registered ${rules.size} default interaction rules")
    }
}
