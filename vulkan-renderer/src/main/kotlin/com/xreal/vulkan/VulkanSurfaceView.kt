package com.xreal.vulkan

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * VulkanSurfaceView -- Vulkan 렌더링용 SurfaceView.
 *
 * SurfaceView는 전용 하드웨어 컴포지터 레이어를 가지므로
 * OverlayView (Canvas 2D) 아래에 배치하여 3D 컨텐츠 렌더링.
 *
 * 전용 렌더 스레드에서 Vulkan 프레임 루프 실행.
 * VSync (FIFO present mode)로 프레임 속도 제어.
 */
class VulkanSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "VulkanSurface"
    }

    val bridge = VulkanRendererBridge()
    private var renderThread: Thread? = null
    @Volatile private var isRendering = false
    @Volatile private var isPaused = false

    init {
        holder.addCallback(this)
        // SurfaceView를 Z-order 최하단에 배치 (OverlayView가 위에 오도록)
        setZOrderOnTop(false)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "Surface created")
        val surface = holder.surface

        if (bridge.init(surface)) {
            Log.i(TAG, "Vulkan initialized, starting render loop")
            startRenderLoop()
        } else {
            Log.e(TAG, "Vulkan initialization failed!")
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.i(TAG, "Surface changed: ${width}x${height}, format=$format")
        bridge.nativeResize(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(TAG, "Surface destroyed, stopping render loop")
        stopRenderLoop()
        bridge.destroy()
    }

    private fun startRenderLoop() {
        isRendering = true
        renderThread = Thread({
            Log.i(TAG, "Render thread started")
            while (isRendering) {
                if (!isPaused) {
                    if (!bridge.renderFrame()) {
                        // 렌더 실패 시 짧은 대기 후 재시도
                        Thread.sleep(16)
                    }
                } else {
                    Thread.sleep(100)
                }
            }
            Log.i(TAG, "Render thread exited")
        }, "VulkanRender").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun stopRenderLoop() {
        isRendering = false
        renderThread?.join(2000)
        renderThread = null
    }

    /** Activity onPause 시 호출 */
    fun onPause() {
        isPaused = true
    }

    /** Activity onResume 시 호출 */
    fun onResume() {
        isPaused = false
    }

    /** Activity onDestroy 시 호출 */
    fun release() {
        stopRenderLoop()
        bridge.destroy()
    }
}
