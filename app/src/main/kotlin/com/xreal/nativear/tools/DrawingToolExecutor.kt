package com.xreal.nativear.tools

import com.xreal.nativear.DrawCommand
import com.xreal.nativear.DrawElement
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent

class DrawingToolExecutor(
    private val eventBus: GlobalEventBus
) : IToolExecutor {

    override val supportedTools = setOf("draw_element", "remove_drawing", "modify_drawing", "clear_drawings")

    override suspend fun execute(name: String, args: Map<String, Any?>): ToolResult {
        return when (name) {
            "draw_element" -> {
                val type = args["type"] as? String ?: "text"
                val id = args["id"] as? String ?: "draw_${System.currentTimeMillis()}"
                val x = (args["x"] as? Number)?.toFloat() ?: 50f
                val y = (args["y"] as? Number)?.toFloat() ?: 50f
                val color = args["color"] as? String
                val opacity = (args["opacity"] as? Number)?.toFloat()

                val element: DrawElement = when (type.lowercase()) {
                    "text" -> DrawElement.Text(
                        id = id, x = x, y = y,
                        text = args["text"] as? String ?: "",
                        color = color ?: "#00FFFF",
                        opacity = opacity ?: 1f,
                        size = (args["size"] as? Number)?.toFloat() ?: 24f,
                        bold = args["bold"] as? Boolean ?: false
                    )
                    "rect" -> DrawElement.Rect(
                        id = id, x = x, y = y,
                        width = (args["width"] as? Number)?.toFloat() ?: 10f,
                        height = (args["height"] as? Number)?.toFloat() ?: 10f,
                        color = color ?: "#00FF00",
                        opacity = opacity ?: 0.6f,
                        filled = args["filled"] as? Boolean ?: false
                    )
                    "circle" -> DrawElement.Circle(
                        id = id, cx = x, cy = y,
                        radius = (args["radius"] as? Number)?.toFloat() ?: 5f,
                        color = color ?: "#FFFF00",
                        opacity = opacity ?: 0.8f,
                        filled = args["filled"] as? Boolean ?: false
                    )
                    "line" -> DrawElement.Line(
                        id = id, x1 = x, y1 = y,
                        x2 = (args["x2"] as? Number)?.toFloat() ?: (x + 10f),
                        y2 = (args["y2"] as? Number)?.toFloat() ?: y,
                        color = color ?: "#FFFFFF",
                        opacity = opacity ?: 1f
                    )
                    "arrow" -> DrawElement.Arrow(
                        id = id, x1 = x, y1 = y,
                        x2 = (args["x2"] as? Number)?.toFloat() ?: (x + 10f),
                        y2 = (args["y2"] as? Number)?.toFloat() ?: y,
                        color = color ?: "#FF6600",
                        opacity = opacity ?: 1f
                    )
                    "highlight" -> DrawElement.Highlight(
                        id = id, x = x, y = y,
                        width = (args["width"] as? Number)?.toFloat() ?: 10f,
                        height = (args["height"] as? Number)?.toFloat() ?: 10f,
                        color = color ?: "#FFFF00",
                        opacity = opacity ?: 0.3f
                    )
                    else -> DrawElement.Text(id = id, x = x, y = y, text = "?", color = "#FFFFFF", opacity = 1f)
                }
                eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(DrawCommand.Add(element)))
                ToolResult(true, "Drawing added: $type (id=$id)")
            }
            "remove_drawing" -> {
                val id = args["id"] as? String ?: ""
                eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(DrawCommand.Remove(id)))
                ToolResult(true, "Drawing removed: $id")
            }
            "modify_drawing" -> {
                val id = args["id"] as? String ?: ""
                @Suppress("UNCHECKED_CAST")
                val updates = args.filterKeys { it != "id" }.filterValues { it != null } as Map<String, Any>
                eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(DrawCommand.Modify(id, updates)))
                ToolResult(true, "Drawing modified: $id")
            }
            "clear_drawings" -> {
                eventBus.publish(XRealEvent.ActionRequest.DrawingCommand(DrawCommand.ClearAll))
                ToolResult(true, "All drawings cleared.")
            }
            else -> ToolResult(false, "Unsupported tool: $name")
        }
    }
}
