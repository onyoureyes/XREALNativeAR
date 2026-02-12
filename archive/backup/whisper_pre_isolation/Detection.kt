package com.xreal.nativear

data class Detection(
    val label: String,
    val confidence: Float,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    override fun toString(): String {
        return "$label (${(confidence * 100).toInt()}%) at [$x, $y, $width, $height]"
    }
}
