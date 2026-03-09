package com.xreal.nativear.context

/**
 * LifeSituation: Expanded life situation classification (24 types).
 *
 * 주의: fromUserState()는 cadence.UserState에 의존하므로
 * cadence 패키지의 확장 함수로 분리됨 (UserState.toLifeSituation()).
 */
enum class LifeSituation(val displayName: String, val icon: String) {
    // Daily routine
    MORNING_ROUTINE("아침 준비", "sunrise"),
    COMMUTING("출퇴근", "walk"),
    AT_DESK_WORKING("업무", "laptop"),
    IN_MEETING("미팅", "handshake"),
    LUNCH_BREAK("점심 시간", "food"),
    EVENING_WIND_DOWN("저녁 마무리", "moon"),

    // Exercise
    RUNNING("러닝", "runner"),
    GYM_WORKOUT("헬스", "gym"),
    WALKING_EXERCISE("산책/걷기", "walking"),

    // Hobbies
    GUITAR_PRACTICE("기타 연습", "guitar"),
    READING("독서", "book"),
    COOKING("요리", "cook"),

    // Social
    SOCIAL_GATHERING("모임", "group"),
    PHONE_CALL("전화 통화", "phone"),

    // Travel
    TRAVELING_NEW_PLACE("새 장소 탐색", "plane"),
    TRAVELING_TRANSIT("이동 중", "bus"),

    // Learning
    STUDYING("공부", "study"),
    LANGUAGE_LEARNING("외국어 학습", "language"),

    // Shopping/Outing
    SHOPPING("쇼핑", "cart"),
    DINING_OUT("외식", "dining"),

    // Education
    TEACHING("수업/교실", "school"),

    // Other
    RELAXING_HOME("집에서 휴식", "home"),
    SLEEPING_PREP("취침 준비", "sleep"),
    SLEEPING("수면 중", "zzz"),
    UNKNOWN("미분류", "question"),
    CUSTOM("사용자 정의", "gear")
}

/**
 * TimeSlot: Time-of-day classification for context-aware decisions.
 */
enum class TimeSlot(val displayName: String) {
    EARLY_MORNING("새벽"),
    MORNING("아침"),
    AFTERNOON("오후"),
    EVENING("저녁"),
    NIGHT("밤"),
    LATE_NIGHT("심야");

    companion object {
        fun fromHour(hour: Int): TimeSlot = when (hour) {
            in 0..3 -> LATE_NIGHT
            in 4..6 -> EARLY_MORNING
            in 7..11 -> MORNING
            in 12..16 -> AFTERNOON
            in 17..20 -> EVENING
            in 21..23 -> NIGHT
            else -> LATE_NIGHT
        }
    }
}
