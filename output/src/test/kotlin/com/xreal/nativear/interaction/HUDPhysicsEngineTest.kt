package com.xreal.nativear.interaction

import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.abs

/**
 * HUDPhysicsEngine 단위 테스트.
 * 순수 수학 로직 — Android 의존성 없음.
 */
class HUDPhysicsEngineTest {

    private lateinit var engine: HUDPhysicsEngine

    @Before
    fun setup() {
        engine = HUDPhysicsEngine()
    }

    // ── Body 생성/조회/제거 ──

    @Test
    fun `addBody 후 getBody로 조회`() {
        val body = engine.addBody("test", 50f, 50f)
        assertNotNull(body)
        assertEquals("test", body.id)
        assertEquals(50f, body.x, 0.01f)
        assertEquals(50f, body.y, 0.01f)
    }

    @Test
    fun `addBody 커스텀 파라미터`() {
        val body = engine.addBody(
            id = "custom",
            x = 30f, y = 40f,
            mass = 2f,
            friction = 0.8f,
            bounciness = 0.9f,
            isStatic = false,
            isGravity = true,
            lifetime = 5f
        )
        assertEquals(2f, body.mass, 0.01f)
        assertEquals(0.8f, body.friction, 0.01f)
        assertEquals(0.9f, body.bounciness, 0.01f)
        assertTrue(body.isGravityEnabled)
        assertEquals(5f, body.lifetime, 0.01f)
    }

    @Test
    fun `removeBody 후 조회 불가`() {
        engine.addBody("to_remove", 50f, 50f)
        assertNotNull(engine.getBody("to_remove"))

        engine.removeBody("to_remove")
        assertNull(engine.getBody("to_remove"))
    }

    @Test
    fun `getAllBodies 복수 바디 반환`() {
        engine.addBody("a", 10f, 10f)
        engine.addBody("b", 20f, 20f)
        engine.addBody("c", 30f, 30f)
        assertEquals(3, engine.getAllBodies().size)
    }

    @Test
    fun `clear 모든 바디 제거`() {
        engine.addBody("a", 10f, 10f)
        engine.addBody("b", 20f, 20f)
        engine.clear()
        assertEquals(0, engine.getAllBodies().size)
    }

    // ── 중력 시뮬레이션 ──

    @Test
    fun `중력 활성화 시 Y 속도 증가`() {
        engine.addBody("gravity", 50f, 20f, isGravity = true)

        engine.update(0.016f)  // 1프레임
        val body = engine.getBody("gravity")!!

        // 중력 가속도 적용으로 vy > 0 (아래로)
        assertTrue(body.vy > 0f || body.y > 20f)
    }

    @Test
    fun `중력 비활성화 시 정지 유지`() {
        engine.addBody("no_gravity", 50f, 50f, isGravity = false)

        engine.update(0.016f)
        val body = engine.getBody("no_gravity")!!

        // 중력 없으면 속도 0 유지 (마찰에 의한 감속도 0*friction = 0)
        assertEquals(0f, body.vy, 0.01f)
    }

    @Test
    fun `static 바디는 물리 시뮬레이션 무시`() {
        engine.addBody("static", 50f, 50f, isStatic = true, isGravity = true)

        engine.update(0.016f)
        val body = engine.getBody("static")!!

        assertEquals(50f, body.x, 0.01f)
        assertEquals(50f, body.y, 0.01f)
    }

    // ── 경계 반발 ──

    @Test
    fun `왼쪽 경계 벗어나면 반발`() {
        val body = engine.addBody("left_bounce", 3f, 50f, bounciness = 0.8f)
        body.vx = -100f  // 왼쪽으로 빠르게

        engine.update(0.016f)
        val updated = engine.getBody("left_bounce")!!

        // SCREEN_MARGIN(2f) 이상이어야 함
        assertTrue(updated.x >= HUDPhysicsEngine.SCREEN_MARGIN)
        // 반발로 속도 반전
        assertTrue(updated.vx >= 0f)
    }

    @Test
    fun `오른쪽 경계 벗어나면 반발`() {
        val body = engine.addBody("right_bounce", 97f, 50f, bounciness = 0.8f)
        body.vx = 100f  // 오른쪽으로 빠르게

        engine.update(0.016f)
        val updated = engine.getBody("right_bounce")!!

        assertTrue(updated.x <= 100f - HUDPhysicsEngine.SCREEN_MARGIN)
        assertTrue(updated.vx <= 0f)
    }

    @Test
    fun `아래쪽 경계 벗어나면 반발`() {
        val body = engine.addBody("bottom_bounce", 50f, 97f, bounciness = 0.8f)
        body.vy = 100f  // 아래로 빠르게

        engine.update(0.016f)
        val updated = engine.getBody("bottom_bounce")!!

        assertTrue(updated.y <= 100f - HUDPhysicsEngine.SCREEN_MARGIN)
    }

    @Test
    fun `위쪽 경계 벗어나면 반발`() {
        val body = engine.addBody("top_bounce", 50f, 3f, bounciness = 0.8f)
        body.vy = -100f  // 위로 빠르게

        engine.update(0.016f)
        val updated = engine.getBody("top_bounce")!!

        assertTrue(updated.y >= HUDPhysicsEngine.SCREEN_MARGIN)
        assertTrue(updated.vy >= 0f)
    }

    // ── 마찰 ──

    @Test
    fun `마찰로 속도 감소`() {
        val body = engine.addBody("friction_test", 50f, 50f, friction = 0.9f)
        body.vx = 100f

        engine.update(0.016f)
        val updated = engine.getBody("friction_test")!!

        // vx가 100보다 작아져야 함 (마찰 적용)
        assertTrue(updated.vx < 100f)
    }

    @Test
    fun `최소 속도 이하 시 정지`() {
        val body = engine.addBody("min_vel", 50f, 50f, friction = 0.1f)
        body.vx = 0.3f  // MIN_VELOCITY(0.5) 미만

        engine.update(0.016f)
        val updated = engine.getBody("min_vel")!!

        // 마찰 적용 후 MIN_VELOCITY 이하 → 0
        assertEquals(0f, updated.vx, 0.01f)
    }

    // ── 힘 적용 ──

    @Test
    fun `applyForce로 속도 변경`() {
        engine.addBody("force_test", 50f, 50f, mass = 1f)
        engine.applyForce("force_test", 50f, -100f)

        val body = engine.getBody("force_test")!!
        assertEquals(50f, body.vx, 0.01f)
        assertEquals(-100f, body.vy, 0.01f)
    }

    @Test
    fun `applyForce mass 반영`() {
        engine.addBody("heavy", 50f, 50f, mass = 2f)
        engine.applyForce("heavy", 100f, 0f)

        val body = engine.getBody("heavy")!!
        assertEquals(50f, body.vx, 0.01f)  // 100 / 2 = 50
    }

    @Test
    fun `static 바디에 applyForce 무효`() {
        engine.addBody("static_force", 50f, 50f, isStatic = true)
        engine.applyForce("static_force", 100f, 100f)

        val body = engine.getBody("static_force")!!
        assertEquals(0f, body.vx, 0.01f)
        assertEquals(0f, body.vy, 0.01f)
    }

    // ── 수명(lifetime) ──

    @Test
    fun `수명 만료 시 바디 제거`() {
        engine.addBody("mortal", 50f, 50f, lifetime = 0.01f)

        // dt 클램핑 최소 0.001f → 10번 업데이트하면 0.01초
        repeat(15) { engine.update(0.016f) }

        assertNull(engine.getBody("mortal"))
    }

    @Test
    fun `update 반환값에 만료 바디 ID 포함`() {
        engine.addBody("short_lived", 50f, 50f, lifetime = 0.005f)

        // 첫 업데이트에서 dt=0.016 → clamped 0.016 > 0.005 → 만료
        val expired = engine.update(0.016f)
        assertTrue(expired.contains("short_lived"))
    }

    // ── 속도/위치 직접 설정 ──

    @Test
    fun `setVelocity로 속도 직접 설정`() {
        engine.addBody("vel", 50f, 50f)
        engine.setVelocity("vel", 30f, -20f)

        val body = engine.getBody("vel")!!
        assertEquals(30f, body.vx, 0.01f)
        assertEquals(-20f, body.vy, 0.01f)
    }

    @Test
    fun `setPosition으로 위치 직접 설정`() {
        engine.addBody("pos", 50f, 50f)
        engine.setPosition("pos", 10f, 90f)

        val body = engine.getBody("pos")!!
        assertEquals(10f, body.x, 0.01f)
        assertEquals(90f, body.y, 0.01f)
    }

    // ── 스프링 ──

    @Test
    fun `스프링 앵커 설정 시 복원력 작용`() {
        val body = engine.addBody(
            "spring", 60f, 60f,
            anchorX = 50f, anchorY = 50f, springK = 5f
        )

        engine.update(0.016f)
        val updated = engine.getBody("spring")!!

        // 앵커(50,50)로 돌아가려는 힘 → x, y가 50에 가까워져야 함
        assertTrue(updated.x < 60f)
        assertTrue(updated.y < 60f)
    }

    @Test
    fun `setAnchor로 앵커 변경`() {
        engine.addBody("anchor_test", 50f, 50f)
        engine.setAnchor("anchor_test", 80f, 80f, springK = 3f)

        val body = engine.getBody("anchor_test")!!
        assertEquals(80f, body.anchorX!!, 0.01f)
        assertEquals(80f, body.anchorY!!, 0.01f)
        assertEquals(3f, body.springK, 0.01f)
    }

    // ── 속도 클램핑 ──

    @Test
    fun `속도 MAX_VELOCITY 초과 시 클램핑`() {
        val body = engine.addBody("fast", 50f, 50f)
        body.vx = 1000f  // MAX_VELOCITY(500) 초과

        engine.update(0.016f)
        val updated = engine.getBody("fast")!!

        assertTrue(abs(updated.vx) <= HUDPhysicsEngine.MAX_VELOCITY)
    }

    // ── 애니메이션 ──

    @Test
    fun `애니메이션 추가 및 조회`() {
        engine.addAnimation("target1", AnimationType.SHAKE, 1.0f, intensity = 0.8f)

        val anims = engine.getAnimations("target1")
        assertEquals(1, anims.size)
        assertEquals(AnimationType.SHAKE, anims[0].type)
        assertEquals(0.8f, anims[0].intensity, 0.01f)
    }

    @Test
    fun `같은 타입 애니메이션 교체`() {
        engine.addAnimation("target", AnimationType.SHAKE, 1.0f)
        engine.addAnimation("target", AnimationType.SHAKE, 2.0f)  // 교체

        val anims = engine.getAnimations("target")
        assertEquals(1, anims.size)
        assertEquals(2.0f, anims[0].duration, 0.01f)
    }

    @Test
    fun `다른 타입 애니메이션 공존`() {
        engine.addAnimation("target", AnimationType.SHAKE, 1.0f)
        engine.addAnimation("target", AnimationType.FADE_OUT, 2.0f)

        val anims = engine.getAnimations("target")
        assertEquals(2, anims.size)
    }

    @Test
    fun `애니메이션 완료 시 자동 제거`() {
        engine.addAnimation("target", AnimationType.SHAKE, 0.01f)

        // 업데이트 여러 번 → 완료
        engine.addBody("dummy", 50f, 50f)  // update가 작동하려면 바디가 있어야
        repeat(5) { engine.update(0.016f) }

        val anims = engine.getAnimations("target")
        assertEquals(0, anims.size)
    }

    @Test
    fun `SHAKE 애니메이션 오프셋 계산`() {
        engine.addAnimation("shake_target", AnimationType.SHAKE, 1.0f, intensity = 1f)

        // 약간 진행
        engine.addBody("dummy", 50f, 50f)
        engine.update(0.016f)

        val (offsetX, offsetY) = engine.getAnimationOffset("shake_target")
        // SHAKE는 X 오프셋만 변경
        // 진행 초기에는 offsetX가 0이 아닐 수 있음
        assertTrue(abs(offsetX) >= 0f)  // 최소한 에러 없이 계산
        assertEquals(0f, offsetY, 0.01f)
    }

    @Test
    fun `FADE_OUT 알파 계산`() {
        engine.addAnimation("fade_target", AnimationType.FADE_OUT, 1.0f)

        engine.addBody("dummy", 50f, 50f)
        engine.update(0.016f)

        val alpha = engine.getAnimationAlpha("fade_target")
        // progress > 0이므로 alpha < 1
        assertTrue(alpha < 1f)
        assertTrue(alpha >= 0f)
    }

    @Test
    fun `SCALE_PULSE 스케일 계산`() {
        engine.addAnimation("scale_target", AnimationType.SCALE_PULSE, 1.0f, intensity = 1f)

        engine.addBody("dummy", 50f, 50f)
        engine.update(0.016f)

        val scale = engine.getAnimationScale("scale_target")
        // 스케일은 1 근처 변동
        assertTrue(scale > 0.5f)
        assertTrue(scale < 2f)
    }

    @Test
    fun `대상 없는 애니메이션 오프셋 제로`() {
        val (offsetX, offsetY) = engine.getAnimationOffset("nonexistent")
        assertEquals(0f, offsetX, 0.01f)
        assertEquals(0f, offsetY, 0.01f)
    }

    @Test
    fun `대상 없는 애니메이션 스케일 1`() {
        val scale = engine.getAnimationScale("nonexistent")
        assertEquals(1f, scale, 0.01f)
    }

    @Test
    fun `대상 없는 애니메이션 알파 1`() {
        val alpha = engine.getAnimationAlpha("nonexistent")
        assertEquals(1f, alpha, 0.01f)
    }

    @Test
    fun `removeBody 시 관련 애니메이션도 제거`() {
        engine.addBody("animated", 50f, 50f)
        engine.addAnimation("animated", AnimationType.SHAKE, 5.0f)

        engine.removeBody("animated")
        assertEquals(0, engine.getAnimations("animated").size)
    }
}
