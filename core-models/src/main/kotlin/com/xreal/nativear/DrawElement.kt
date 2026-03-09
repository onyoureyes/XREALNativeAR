package com.xreal.nativear

/**
 * DrawElement: Sealed class hierarchy for AI-drawn overlay primitives.
 * Coordinates are percentage-based (0.0–100.0) for resolution independence.
 */
sealed class DrawElement {
    abstract val id: String
    abstract val color: String   // hex "#RRGGBB" or named: "red","cyan","yellow","green","white","orange"
    abstract val opacity: Float  // 0.0–1.0

    data class Text(
        override val id: String,
        override val color: String = "#00FFFF",
        override val opacity: Float = 1f,
        val x: Float,
        val y: Float,
        val text: String,
        val size: Float = 24f,
        val bold: Boolean = false
    ) : DrawElement()

    data class Rect(
        override val id: String,
        override val color: String = "#00FF00",
        override val opacity: Float = 0.6f,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val filled: Boolean = false,
        val strokeWidth: Float = 3f
    ) : DrawElement()

    data class Circle(
        override val id: String,
        override val color: String = "#FFFF00",
        override val opacity: Float = 0.8f,
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val filled: Boolean = false
    ) : DrawElement()

    data class Line(
        override val id: String,
        override val color: String = "#FFFFFF",
        override val opacity: Float = 1f,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val strokeWidth: Float = 3f
    ) : DrawElement()

    data class Arrow(
        override val id: String,
        override val color: String = "#FF6600",
        override val opacity: Float = 1f,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val strokeWidth: Float = 4f
    ) : DrawElement()

    data class Highlight(
        override val id: String,
        override val color: String = "#FFFF00",
        override val opacity: Float = 0.3f,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    ) : DrawElement()

    data class Polyline(
        override val id: String,
        override val color: String = "#00FF00",
        override val opacity: Float = 1f,
        val points: List<Pair<Float, Float>>,
        val strokeWidth: Float = 3f,
        val closed: Boolean = false
    ) : DrawElement()
}

/**
 * DrawCommand: Commands to manipulate the drawing layer on OverlayView.
 */
sealed class DrawCommand {
    data class Add(val element: DrawElement) : DrawCommand()
    data class Remove(val id: String) : DrawCommand()
    data class Modify(val id: String, val updates: Map<String, Any>) : DrawCommand()
    object ClearAll : DrawCommand()
}
