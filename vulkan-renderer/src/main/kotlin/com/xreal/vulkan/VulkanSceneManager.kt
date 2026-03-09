package com.xreal.vulkan

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * VulkanSceneManager -- EventBus 이벤트 → Vulkan 렌더러 브릿지.
 *
 * HeadPoseUpdated 이벤트를 구독하여 VIO 6-DoF 포즈를
 * VulkanRendererBridge.setPose()로 전달.
 *
 * @param scope 코루틴 스코프 (lifecycleScope)
 * @param eventFlow GlobalEventBus.events SharedFlow
 * @param bridge VulkanRendererBridge 인스턴스
 */
class VulkanSceneManager(
    private val scope: CoroutineScope,
    private val eventFlow: SharedFlow<Any>,
    private val bridge: VulkanRendererBridge
) {
    companion object {
        private const val TAG = "VulkanScene"
    }

    private var poseCount = 0L

    fun start() {
        Log.i(TAG, "VulkanSceneManager started — subscribing to HeadPoseUpdated")
        scope.launch {
            eventFlow.collect { event ->
                // 리플렉션 없이 클래스 이름으로 필터링 (모듈 간 의존성 방지)
                val className = event::class.simpleName
                if (className == "HeadPoseUpdated") {
                    handlePoseEvent(event)
                }
            }
        }
    }

    private fun handlePoseEvent(event: Any) {
        try {
            // 리플렉션으로 필드 접근 (vulkan-renderer 모듈은 app 모듈에 직접 의존하지 않음)
            val cls = event::class.java
            val x = cls.getMethod("getX").invoke(event) as Float
            val y = cls.getMethod("getY").invoke(event) as Float
            val z = cls.getMethod("getZ").invoke(event) as Float
            val qx = cls.getMethod("getQx").invoke(event) as Float
            val qy = cls.getMethod("getQy").invoke(event) as Float
            val qz = cls.getMethod("getQz").invoke(event) as Float
            val qw = cls.getMethod("getQw").invoke(event) as Float

            bridge.setPose(x, y, z, qx, qy, qz, qw)

            poseCount++
            if (poseCount <= 3 || poseCount % 100 == 0L) {
                Log.i(TAG, "Pose #$poseCount: (${String.format("%.3f", x)}, ${String.format("%.3f", y)}, ${String.format("%.3f", z)})")
            }
        } catch (e: Exception) {
            if (poseCount == 0L) {
                Log.w(TAG, "Failed to extract pose from event: ${e.message}")
            }
        }
    }
}
