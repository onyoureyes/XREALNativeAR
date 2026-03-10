package com.xreal.nativear.interaction

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * HUDPhysicsEngine — HUD 요소를 위한 2D 물리 시뮬레이션.
 *
 * ## 물리 특성
 * - **중력**: Y축 아래 방향 (100 %/s²)
 * - **마찰**: 속도에 비례하는 감속 (friction 계수)
 * - **반발**: 화면 경계에서 튕김 (bounciness 계수)
 * - **스프링**: 앵커 포인트로 돌아가는 복원력
 * - **수명**: 생성 후 일정 시간 경과 시 자동 소멸
 *
 * ## 좌표계
 * - 퍼센트 좌표 (0~100), OverlayView의 DrawElement과 동일
 * - 좌상단 (0,0), 우하단 (100,100)
 *
 * ## 사용법
 * ```kotlin
 * val engine = HUDPhysicsEngine()
 * engine.addBody("label_1", 50f, 30f, isGravity = true, bounciness = 0.7f)
 * engine.applyForce("label_1", 0f, -200f)  // 위로 던지기
 *
 * // 매 프레임 (16ms 간격)
 * engine.update(0.016f)
 * val body = engine.getBody("label_1")  // 업데이트된 위치
 * ```
 */
class HUDPhysicsEngine {

    companion object {
        private const val TAG = "HUDPhysics"

        /** 중력 가속도 (퍼센트/초²) — 화면 높이의 100% = 1초에 떨어지는 거리 */
        const val GRAVITY = 150f

        /** 최소 속도 (이하면 정지) */
        const val MIN_VELOCITY = 0.5f

        /** 화면 경계 마진 (퍼센트) */
        const val SCREEN_MARGIN = 2f

        /** 최대 속도 제한 (퍼센트/초) */
        const val MAX_VELOCITY = 500f
    }

    // ── 물리 바디 풀 ──
    private val bodies = mutableMapOf<String, PhysicsBody>()

    // ── 활성 애니메이션 ──
    private val animations = mutableListOf<ActiveAnimation>()

    // ── Public API ──

    /**
     * 물리 바디 추가.
     *
     * @param id 고유 식별자 (앵커 ID 또는 DrawElement ID)
     * @param x 초기 X 좌표 (퍼센트)
     * @param y 초기 Y 좌표 (퍼센트)
     */
    fun addBody(
        id: String,
        x: Float, y: Float,
        mass: Float = 1f,
        friction: Float = 0.95f,
        bounciness: Float = 0.5f,
        isStatic: Boolean = false,
        isGravity: Boolean = false,
        anchorX: Float? = null,
        anchorY: Float? = null,
        springK: Float = 2.0f,
        lifetime: Float = Float.MAX_VALUE
    ): PhysicsBody {
        val body = PhysicsBody(
            id = id, x = x, y = y,
            mass = mass, friction = friction, bounciness = bounciness,
            isStatic = isStatic, isGravityEnabled = isGravity,
            anchorX = anchorX, anchorY = anchorY, springK = springK,
            lifetime = lifetime
        )
        bodies[id] = body
        return body
    }

    /** 바디 제거 */
    fun removeBody(id: String) {
        bodies.remove(id)
        animations.removeAll { it.targetId == id }
    }

    /** 바디 조회 */
    fun getBody(id: String): PhysicsBody? = bodies[id]

    /** 모든 바디 */
    fun getAllBodies(): List<PhysicsBody> = bodies.values.toList()

    /** 힘 적용 (순간 충격) */
    fun applyForce(id: String, forceX: Float, forceY: Float) {
        val body = bodies[id] ?: return
        if (body.isStatic) return
        body.vx += forceX / body.mass
        body.vy += forceY / body.mass
    }

    /** 속도 직접 설정 */
    fun setVelocity(id: String, vx: Float, vy: Float) {
        val body = bodies[id] ?: return
        body.vx = vx
        body.vy = vy
    }

    /** 위치 직접 설정 (물리 무시 이동) */
    fun setPosition(id: String, x: Float, y: Float) {
        val body = bodies[id] ?: return
        body.x = x
        body.y = y
    }

    /** 중력 토글 */
    fun setGravity(id: String, enabled: Boolean) {
        bodies[id]?.isGravityEnabled = enabled
    }

    /** 스프링 앵커 설정 (null이면 스프링 해제) */
    fun setAnchor(id: String, anchorX: Float?, anchorY: Float?, springK: Float = 2f) {
        val body = bodies[id] ?: return
        body.anchorX = anchorX
        body.anchorY = anchorY
        body.springK = springK
    }

    /** 애니메이션 추가 */
    fun addAnimation(targetId: String, type: AnimationType, duration: Float, intensity: Float = 1f, params: Map<String, Float> = emptyMap()) {
        // 같은 타입 애니메이션이 이미 있으면 교체
        animations.removeAll { it.targetId == targetId && it.type == type }
        animations.add(ActiveAnimation(targetId, type, duration, intensity = intensity, params = params))
    }

    /** 특정 요소의 활성 애니메이션 */
    fun getAnimations(targetId: String): List<ActiveAnimation> =
        animations.filter { it.targetId == targetId }

    /** 전체 클리어 */
    fun clear() {
        bodies.clear()
        animations.clear()
    }

    /**
     * 물리 시뮬레이션 업데이트 (매 프레임 호출).
     *
     * @param dt 프레임 간 시간 (초)
     * @return 수명 만료된 바디 ID 리스트
     */
    fun update(dt: Float): List<String> {
        val expired = mutableListOf<String>()
        val clampedDt = dt.coerceIn(0.001f, 0.05f)  // 최소/최대 dt 클램핑

        for (body in bodies.values) {
            if (body.isStatic) continue

            // 수명 체크
            body.age += clampedDt
            if (body.age >= body.lifetime) {
                expired.add(body.id)
                continue
            }

            // 1. 중력
            if (body.isGravityEnabled) {
                body.ay = GRAVITY
            }

            // 2. 스프링 복원력
            val ax = body.anchorX
            val ay = body.anchorY
            if (ax != null && ay != null) {
                val dx = ax - body.x
                val dy = ay - body.y
                body.ax += dx * body.springK
                body.ay += dy * body.springK
            }

            // 3. 가속도 → 속도
            body.vx += body.ax * clampedDt
            body.vy += body.ay * clampedDt

            // 4. 마찰
            body.vx *= body.friction
            body.vy *= body.friction

            // 5. 속도 클램핑
            body.vx = body.vx.coerceIn(-MAX_VELOCITY, MAX_VELOCITY)
            body.vy = body.vy.coerceIn(-MAX_VELOCITY, MAX_VELOCITY)

            // 6. 최소 속도 → 정지
            if (abs(body.vx) < MIN_VELOCITY) body.vx = 0f
            if (abs(body.vy) < MIN_VELOCITY) body.vy = 0f

            // 7. 위치 업데이트
            body.x += body.vx * clampedDt
            body.y += body.vy * clampedDt

            // 8. 경계 반발 (화면 안에서 튕김)
            if (body.x < SCREEN_MARGIN) {
                body.x = SCREEN_MARGIN
                body.vx = abs(body.vx) * body.bounciness
            }
            if (body.x > 100f - SCREEN_MARGIN) {
                body.x = 100f - SCREEN_MARGIN
                body.vx = -abs(body.vx) * body.bounciness
            }
            if (body.y < SCREEN_MARGIN) {
                body.y = SCREEN_MARGIN
                body.vy = abs(body.vy) * body.bounciness
            }
            if (body.y > 100f - SCREEN_MARGIN) {
                body.y = 100f - SCREEN_MARGIN
                body.vy = -abs(body.vy) * body.bounciness
                // 바닥에서 거의 정지 상태면 완전 정지
                if (abs(body.vy) < 5f) body.vy = 0f
            }

            // 9. 가속도 리셋 (매 프레임 재계산)
            body.ax = 0f
            body.ay = if (body.isGravityEnabled) GRAVITY else 0f
        }

        // 만료 바디 제거
        for (id in expired) {
            bodies.remove(id)
        }

        // 애니메이션 업데이트
        updateAnimations(clampedDt)

        return expired
    }

    /**
     * 애니메이션 변위 계산.
     * OverlayView에서 요소 위치에 더할 오프셋.
     */
    fun getAnimationOffset(targetId: String): Pair<Float, Float> {
        var offsetX = 0f
        var offsetY = 0f

        for (anim in animations) {
            if (anim.targetId != targetId) continue
            when (anim.type) {
                AnimationType.SHAKE -> {
                    val freq = anim.params["frequency"] ?: 15f
                    val amplitude = anim.intensity * 3f * (1f - anim.progress)
                    offsetX += sin(anim.elapsed * freq * 2 * Math.PI.toFloat()) * amplitude
                }
                AnimationType.BOUNCE -> {
                    val freq = anim.params["frequency"] ?: 3f
                    val amplitude = anim.intensity * 5f * (1f - anim.progress)
                    offsetY += abs(sin(anim.elapsed * freq * Math.PI.toFloat())) * amplitude
                }
                else -> {} // 다른 애니메이션은 오프셋 없음
            }
        }
        return offsetX to offsetY
    }

    /**
     * 애니메이션 스케일 배수 계산.
     */
    fun getAnimationScale(targetId: String): Float {
        var scale = 1f
        for (anim in animations) {
            if (anim.targetId != targetId) continue
            if (anim.type == AnimationType.SCALE_PULSE) {
                val freq = anim.params["frequency"] ?: 2f
                scale *= 1f + anim.intensity * 0.3f * sin(anim.elapsed * freq * 2 * Math.PI.toFloat())
            }
        }
        return scale
    }

    /**
     * 애니메이션 투명도 배수 계산.
     */
    fun getAnimationAlpha(targetId: String): Float {
        var alpha = 1f
        for (anim in animations) {
            if (anim.targetId != targetId) continue
            when (anim.type) {
                AnimationType.FADE_OUT -> alpha *= (1f - anim.progress)
                AnimationType.FADE_IN -> alpha *= anim.progress
                AnimationType.GLOW -> alpha *= 0.7f + 0.3f * sin(anim.elapsed * 4f * Math.PI.toFloat())
                else -> {}
            }
        }
        return alpha.coerceIn(0f, 1f)
    }

    // ── 내부 ──

    private fun updateAnimations(dt: Float) {
        val iter = animations.iterator()
        while (iter.hasNext()) {
            val anim = iter.next()
            anim.elapsed += dt
            if (anim.isComplete) {
                iter.remove()
            }
        }
    }
}
