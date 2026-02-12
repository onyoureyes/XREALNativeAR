package com.xreal.nativear.core

import com.xreal.nativear.IVisionService
import com.xreal.nativear.VisionManager

/**
 * VisionServiceDelegate: Delegates IVisionService calls to VisionManager lazily.
 */
class VisionServiceDelegate(
    private val getVisionManager: () -> VisionManager?
) : IVisionService {
    override fun setOcrEnabled(enabled: Boolean) {
        getVisionManager()?.setOcrEnabled(enabled)
    }
    
    override fun setPoseEnabled(enabled: Boolean) {
        getVisionManager()?.setPoseEnabled(enabled)
    }
    
    override fun setSceneCaptureEnabled(enabled: Boolean) {
        getVisionManager()?.setSceneCaptureEnabled(enabled)
    }
    
    override fun captureSceneSnapshot() {
        getVisionManager()?.captureSceneSnapshot()
    }
    
    override fun cycleCamera() {
        getVisionManager()?.cycleCamera()
    }
}
