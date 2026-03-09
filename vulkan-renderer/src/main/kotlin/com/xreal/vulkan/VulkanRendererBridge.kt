package com.xreal.vulkan

import android.util.Log
import android.view.Surface

/**
 * VulkanRendererBridge -- Vulkan C++ 렌더러 JNI 래퍼.
 *
 * C++ 네이티브 라이브러리: libvulkan_renderer.so
 * 패턴 참조: VIOManager.kt (xreal-hardware-standalone)
 */
class VulkanRendererBridge {

    companion object {
        private const val TAG = "VulkanBridge"

        init {
            try {
                System.loadLibrary("vulkan_renderer")
                Log.i(TAG, "libvulkan_renderer.so loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libvulkan_renderer.so: ${e.message}")
            }
        }
    }

    // ── Phase 1: 코어 라이프사이클 ──

    /** Vulkan 초기화 (Surface에서 스왑체인 생성). 0=성공, 음수=에러. */
    external fun nativeInit(surface: Surface): Int

    /** 프레임 렌더 (렌더 스레드에서 호출). 0=성공. */
    external fun nativeRenderFrame(): Int

    /** 뷰 크기 변경 시 스왑체인 재생성. */
    external fun nativeResize(width: Int, height: Int)

    /** 리소스 해제. */
    external fun nativeDestroy()

    // ── Phase 2: 카메라 프레임 ──

    /** MJPEG 카메라 프레임 업로드 (카메라 스레드에서 호출 가능). */
    external fun nativeUploadCameraFrame(data: ByteArray, size: Int)

    /** 카메라 프레임 편의 메서드. */
    fun uploadCameraFrame(data: ByteArray) {
        nativeUploadCameraFrame(data, data.size)
    }

    // ── Phase 3: VIO 포즈 ──

    /** VIO 6-DoF 포즈 설정 (카메라→월드 변환, Hamilton 쿼터니언). */
    external fun nativeSetPose(x: Float, y: Float, z: Float,
                                qx: Float, qy: Float, qz: Float, qw: Float)

    /** 포즈 편의 메서드. */
    fun setPose(x: Float, y: Float, z: Float,
                qx: Float, qy: Float, qz: Float, qw: Float) {
        nativeSetPose(x, y, z, qx, qy, qz, qw)
    }

    // ── 편의 메서드 ──

    fun init(surface: Surface): Boolean {
        val result = nativeInit(surface)
        if (result != 0) {
            Log.e(TAG, "Vulkan init failed: $result")
        }
        return result == 0
    }

    fun renderFrame(): Boolean {
        return nativeRenderFrame() == 0
    }

    fun destroy() {
        nativeDestroy()
    }
}
