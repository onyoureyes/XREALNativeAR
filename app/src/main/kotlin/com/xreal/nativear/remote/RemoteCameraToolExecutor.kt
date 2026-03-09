package com.xreal.nativear.remote

import android.util.Log
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.tools.IToolExecutor
import com.xreal.nativear.tools.ToolResult

/**
 * RemoteCameraToolExecutor: Gemini tool executor for remote webcam streaming.
 *
 * Handles three tools:
 * - show_remote_camera: Start streaming from PC webcam → HUD PIP
 * - hide_remote_camera: Stop streaming
 * - configure_remote_camera: Change server URL, PIP position/size
 */
class RemoteCameraToolExecutor(
    private val remoteCameraService: RemoteCameraService,
    private val eventBus: GlobalEventBus
) : IToolExecutor {

    private val TAG = "RemoteCameraToolExec"

    override val supportedTools = setOf(
        "show_remote_camera",
        "hide_remote_camera",
        "configure_remote_camera"
    )

    override suspend fun execute(name: String, args: Map<String, Any?>): ToolResult {
        return when (name) {
            "show_remote_camera" -> handleShow(args)
            "hide_remote_camera" -> handleHide()
            "configure_remote_camera" -> handleConfigure(args)
            else -> ToolResult(false, "Unknown tool: $name")
        }
    }

    private suspend fun handleShow(args: Map<String, Any?>): ToolResult {
        val source = args["source"] as? String
        Log.i(TAG, "show_remote_camera: source=$source")

        // Publish ActionRequest to toggle PIP on
        eventBus.emit(XRealEvent.ActionRequest.ShowRemoteCamera(show = true, source = source))

        val url = source ?: remoteCameraService.serverUrl
        return ToolResult(
            success = true,
            data = "원격 카메라 스트리밍을 시작합니다. 서버: $url. HUD 우측 하단에 PIP 영상이 표시됩니다."
        )
    }

    private suspend fun handleHide(): ToolResult {
        Log.i(TAG, "hide_remote_camera")

        eventBus.emit(XRealEvent.ActionRequest.ShowRemoteCamera(show = false))

        return ToolResult(
            success = true,
            data = "원격 카메라 스트리밍을 중지했습니다."
        )
    }

    private fun handleConfigure(args: Map<String, Any?>): ToolResult {
        val url = args["server_url"] as? String
        val positionStr = args["position"] as? String
        val size = (args["size_percent"] as? Number)?.toFloat()

        val position = when (positionStr?.uppercase()) {
            "TOP_LEFT" -> PipPosition.TOP_LEFT
            "TOP_RIGHT" -> PipPosition.TOP_RIGHT
            "BOTTOM_LEFT" -> PipPosition.BOTTOM_LEFT
            "BOTTOM_RIGHT" -> PipPosition.BOTTOM_RIGHT
            "CENTER" -> PipPosition.CENTER
            else -> null
        }

        remoteCameraService.configure(
            position = position,
            sizePercent = size,
            url = url
        )

        Log.i(TAG, "configure_remote_camera: url=$url, position=$positionStr, size=$size")
        return ToolResult(
            success = true,
            data = "원격 카메라 설정이 변경되었습니다. URL=${remoteCameraService.serverUrl}, 위치=${remoteCameraService.pipPosition}, 크기=${remoteCameraService.pipSizePercent}%"
        )
    }
}
