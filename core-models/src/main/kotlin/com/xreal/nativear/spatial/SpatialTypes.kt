package com.xreal.nativear.spatial

/** 앵커 유형 */
enum class AnchorType {
    OBJECT,
    OCR_TEXT,
    PROGRAMMATIC
}

/**
 * AnchorLabel2D — 화면에 투영된 앵커 라벨 (HUD 렌더링용).
 * 좌표는 퍼센트 기준 (0-100).
 */
data class AnchorLabel2D(
    val anchorId: String,
    val label: String,
    val screenXPercent: Float,
    val screenYPercent: Float,
    val distanceMeters: Float,
    val confidence: Float,
    val type: AnchorType,
    val isGhost: Boolean = false
)
