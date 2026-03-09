package com.xreal.nativear.interaction

/**
 * InteractionTypes — 상호작용 시스템 데이터 타입.
 */

/**
 * HUD 요소의 물리 상태.
 * HUDPhysicsEngine이 매 프레임 업데이트.
 */
data class PhysicsBody(
    val id: String,
    var x: Float,              // 퍼센트 좌표 (0~100)
    var y: Float,
    var vx: Float = 0f,        // 속도 (퍼센트/초)
    var vy: Float = 0f,
    var ax: Float = 0f,        // 가속도 (퍼센트/초²)
    var ay: Float = 0f,
    var mass: Float = 1f,      // 질량 (상대값)
    var friction: Float = 0.95f,  // 마찰 계수 (0=즉시 정지, 1=무마찰)
    var bounciness: Float = 0.5f, // 반발 계수 (0=반사 없음, 1=완전 탄성)
    var isStatic: Boolean = false, // true이면 물리 시뮬레이션 무시
    var isGravityEnabled: Boolean = false,  // 중력 적용 여부
    var anchorX: Float? = null,  // 스프링 앵커 X (null이면 자유)
    var anchorY: Float? = null,  // 스프링 앵커 Y
    var springK: Float = 2.0f,   // 스프링 상수 (앵커로 돌아가는 힘)
    var lifetime: Float = Float.MAX_VALUE,  // 잔여 수명 (초)
    var age: Float = 0f         // 생성 후 경과 시간 (초)
) {
    /** 현재 속도 크기 */
    val speed: Float get() = kotlin.math.sqrt(vx * vx + vy * vy)

    /** 앵커로부터의 거리 */
    val anchorDistance: Float
        get() {
            val ax = anchorX ?: return 0f
            val ay = anchorY ?: return 0f
            val dx = x - ax; val dy = y - ay
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
}

/**
 * 애니메이션 효과 타입.
 */
enum class AnimationType {
    SHAKE,          // 흔들기 (좌우 진동)
    BOUNCE,         // 튀기 (위아래 반복)
    FADE_IN,        // 투명도 증가
    FADE_OUT,       // 투명도 감소 → 소멸
    SCALE_PULSE,    // 크기 맥동
    GLOW,           // 발광 효과 (색상 주기적 밝기 변화)
    EXPLODE,        // 폭발 (중심에서 파편 분산)
    TRAIL,          // 궤적 (이전 위치에 잔상)
    SPIN,           // 회전
    COLOR_SHIFT     // 색상 전환
}

/**
 * 활성 애니메이션 인스턴스.
 */
data class ActiveAnimation(
    val targetId: String,       // 대상 요소 ID
    val type: AnimationType,
    val duration: Float,        // 총 지속 시간 (초)
    var elapsed: Float = 0f,    // 경과 시간 (초)
    val intensity: Float = 1f,  // 강도 (0~1)
    val params: Map<String, Float> = emptyMap()  // 추가 파라미터
) {
    val progress: Float get() = (elapsed / duration).coerceIn(0f, 1f)
    val isComplete: Boolean get() = elapsed >= duration
}

/**
 * 상호작용 규칙의 트리거 타입.
 */
enum class TriggerType {
    HAND_TAP,       // 손으로 탭
    HAND_PINCH,     // 손으로 핀치 (잡기)
    HAND_SWIPE,     // 손으로 스와이프
    HAND_POINT,     // 손으로 가리키기 (호버)
    HAND_FIST,      // 주먹
    HAND_OPEN_PALM, // 손바닥 활짝
    HAND_DRAW,      // 검지 드로잉
    PROXIMITY,      // 가까이 다가감 (앵커 거리 기반)
    TIME_ELAPSED,   // 시간 경과
    VOICE_COMMAND,  // 음성 명령
    COLLISION       // 다른 요소와 충돌
}

/**
 * 상호작용 규칙의 액션 타입.
 */
enum class ActionType {
    SHAKE,          // 흔들기
    FALL,           // 떨어뜨리기 (중력 활성화)
    EXPLODE,        // 폭발 분산
    BOUNCE,         // 튀기기
    MOVE_TO,        // 특정 위치로 이동
    GRAB,           // 핀치로 잡기 (손에 붙기)
    THROW,          // 던지기 (핀치 해제 시 관성)
    FADE_OUT,       // 사라지기
    GLOW,           // 발광
    COLOR_CHANGE,   // 색상 변경
    SCALE,          // 크기 변경
    SPAWN,          // 새 요소 생성
    SPEAK_TTS,      // TTS 출력
    PLAY_SOUND,     // 효과음 (미래)
    DRAW_TRAIL,     // 궤적 남기기
    SCORE_POINT,    // 점수 획득 (게이미피케이션)
    CHAIN           // 다른 규칙 연쇄 트리거
}

/**
 * 상호작용 규칙 (트리거 → 액션 매핑).
 *
 * 예: "person 라벨을 탭하면 흔들린 후 떨어진다"
 * ```
 * InteractionRule(
 *   trigger = TriggerType.HAND_TAP,
 *   targetFilter = "person",
 *   actions = listOf(
 *     RuleAction(ActionType.SHAKE, mapOf("duration" to 0.5)),
 *     RuleAction(ActionType.FALL, mapOf("delay" to 0.5))
 *   )
 * )
 * ```
 */
data class InteractionRule(
    val id: String,
    val name: String,
    val trigger: TriggerType,
    val targetFilter: String = "*",  // "*"=모든 앵커, 또는 특정 라벨
    val conditions: Map<String, Any> = emptyMap(),  // 추가 조건 (거리, 시간 등)
    val actions: List<RuleAction>,
    val priority: Int = 0,  // 높을수록 우선
    val cooldownMs: Long = 500L,  // 같은 대상에 재적용 쿨다운
    val isRepeatable: Boolean = true  // false이면 1회만 실행
)

/**
 * 규칙 내 개별 액션.
 */
data class RuleAction(
    val type: ActionType,
    val params: Map<String, Any> = emptyMap()  // 타입별 파라미터
) {
    fun getFloat(key: String, default: Float = 0f): Float =
        (params[key] as? Number)?.toFloat() ?: default

    fun getString(key: String, default: String = ""): String =
        params[key] as? String ?: default

    fun getInt(key: String, default: Int = 0): Int =
        (params[key] as? Number)?.toInt() ?: default
}

/**
 * 상호작용 템플릿 (DB 캐시용).
 */
data class InteractionTemplate(
    val id: Long = 0,
    val name: String,
    val triggerType: String,
    val targetFilter: String = "*",
    val actionsJson: String,  // JSON 직렬화된 RuleAction 리스트
    val useCount: Int = 0,
    val successRate: Float = 1.0f,
    val contextTags: String = "",  // 쉼표 구분 태그 (장소, 활동 등)
    val creatorPersona: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis()
)
