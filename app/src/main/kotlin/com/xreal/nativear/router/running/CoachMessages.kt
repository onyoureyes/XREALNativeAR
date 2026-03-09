package com.xreal.nativear.router.running

object CoachMessages {

    private val hudMessages = mapOf(
        // Biometric alerts (highest priority)
        "COACH_HR_DANGER" to "심박수 위험!",
        "COACH_SPO2_LOW"  to "산소 부족!",
        "COACH_OVERHEAT"  to "체온 주의!",
        "COACH_FATIGUE"   to "피로 감지!",
        // Biomechanical coaching
        "COACH_CADENCE"   to "발을 더 빠르게!",
        "COACH_VO"        to "몸을 낮게!",
        "COACH_GCT"       to "착지를 가볍게!",
        "COACH_STABILITY" to "시선을 전방으로!",
        "COACH_BALANCE"   to "균형 맞추세요!",
        "COACH_BREATHING" to "호흡 리듬 맞추세요!"
    )

    private val ttsMessages = mapOf(
        // Biometric alerts
        "COACH_HR_DANGER" to "심박수가 너무 높아요. 속도를 줄이세요",
        "COACH_SPO2_LOW"  to "혈중 산소가 낮아요. 천천히 호흡하세요",
        "COACH_OVERHEAT"  to "체온이 높아요. 수분을 섭취하고 그늘에서 쉬세요",
        "COACH_FATIGUE"   to "피로가 쌓이고 있어요. 페이스를 낮추세요",
        // Biomechanical coaching
        "COACH_CADENCE"   to "케이던스가 낮아요, 발을 더 빠르게 움직이세요",
        "COACH_VO"        to "수직 진동이 커요, 몸을 낮추고 앞으로 기울이세요",
        "COACH_GCT"       to "접지 시간이 길어요, 가볍게 착지하세요",
        "COACH_STABILITY" to "머리가 흔들려요, 시선을 전방에 고정하세요",
        "COACH_BALANCE"   to "좌우 밸런스가 안 맞아요, 팔을 균등하게 흔드세요",
        "COACH_BREATHING" to "호흡이 불규칙해요, 리듬을 맞추세요"
    )

    fun getHudMessage(action: String): String? = hudMessages[action]

    fun getTtsMessage(action: String): String? = ttsMessages[action]
}
