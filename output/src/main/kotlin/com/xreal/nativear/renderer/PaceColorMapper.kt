package com.xreal.nativear.renderer

/**
 * 페이스메이커 라벨 → RGBA 색상 매핑.
 *
 * 라벨 형식:
 * - "▶ +Xm" (뒤처짐) → 빨간 계열
 * - "◀ -Xm" (앞서감) → 초록 계열
 * - "✓" (정상 페이스) → 시안 계열
 * - 기타 → 기본 초록 계열
 */
object PaceColorMapper {

    /** RGBA 색상 값 (각 0.0~1.0) */
    data class Color(val r: Float, val g: Float, val b: Float, val a: Float)

    // 사전 정의된 색상 상수
    val RED = Color(1.0f, 0.3f, 0.2f, 0.6f)
    val GREEN = Color(0.2f, 1.0f, 0.4f, 0.6f)
    val CYAN = Color(0.4f, 0.9f, 1.0f, 0.5f)
    val DEFAULT = GREEN

    /**
     * 페이스메이커 라벨 문자열로부터 색상을 결정한다.
     *
     * @param label 페이스메이커 앵커의 라벨 (예: "▶ +3m", "◀ -2m", "✓")
     * @return 매핑된 RGBA 색상
     */
    fun mapLabelToColor(label: String): Color {
        return when {
            label.contains("▶") || label.contains("+") -> RED
            label.contains("◀") || label.contains("-") -> GREEN
            label.contains("✓") -> CYAN
            else -> DEFAULT
        }
    }
}
