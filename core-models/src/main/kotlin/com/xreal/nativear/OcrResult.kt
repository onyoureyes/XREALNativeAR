package com.xreal.nativear

import android.graphics.Rect

/**
 * OcrResult — OCR 인식 결과 데이터.
 * 원래 OverlayView.OcrResult로 중첩되어 있었으나 모듈 분리를 위해 독립.
 */
data class OcrResult(val text: String, val box: Rect, val isValid: Boolean)
