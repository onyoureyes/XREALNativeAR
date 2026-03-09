package com.xreal.relay.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.xreal.relay.server.RelayHttpServer
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * CameraX → JPEG 프레임 → RelayHttpServer MJPEG 브로드캐스트.
 *
 * Fold 4의 MjpegStreamClient와 호환:
 *   multipart/x-mixed-replace; boundary=frame
 */
class CameraStreamProvider(
    private val context: Context,
    private val httpServer: RelayHttpServer
) {
    private val TAG = "CameraStreamProvider"

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private val running = AtomicBoolean(false)

    // 프레임 제어
    private val targetFps = 15
    private val frameIntervalMs = 1000L / targetFps
    private val lastFrameTime = AtomicLong(0)

    // JPEG 품질 (0-100)
    private val jpegQuality = 70

    // 통계
    var frameCount = 0L
        private set

    fun start(lifecycleOwner: LifecycleOwner) {
        if (running.getAndSet(true)) return

        cameraExecutor = Executors.newSingleThreadExecutor()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCamera(lifecycleOwner)
                Log.i(TAG, "카메라 스트림 시작 (${targetFps}fps, JPEG q=$jpegQuality)")
            } catch (e: Exception) {
                Log.e(TAG, "카메라 초기화 실패: ${e.message}", e)
                running.set(false)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        running.set(false)
        cameraProvider?.unbindAll()
        cameraProvider = null
        cameraExecutor?.shutdown()
        cameraExecutor = null
        Log.i(TAG, "카메라 스트림 종료 (총 ${frameCount}프레임)")
    }

    private fun bindCamera(lifecycleOwner: LifecycleOwner) {
        val provider = cameraProvider ?: return

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor!!) { imageProxy ->
            try {
                if (!running.get() || httpServer.videoClients.isEmpty()) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                // 프레임 레이트 제한
                val now = System.currentTimeMillis()
                if (now - lastFrameTime.get() < frameIntervalMs) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                lastFrameTime.set(now)

                val jpegData = imageProxyToJpeg(imageProxy)
                if (jpegData != null) {
                    httpServer.broadcastVideoFrame(jpegData)
                    frameCount++
                }
            } catch (e: Exception) {
                Log.w(TAG, "프레임 처리 오류: ${e.message}")
            } finally {
                imageProxy.close()
            }
        }

        // 후면 카메라 사용
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
        } catch (e: Exception) {
            Log.e(TAG, "카메라 바인딩 실패: ${e.message}", e)
        }
    }

    private fun imageProxyToJpeg(imageProxy: ImageProxy): ByteArray? {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        // YUV_420_888 → NV21: V 먼저, 그다음 U
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            imageProxy.width,
            imageProxy.height,
            null
        )

        val out = ByteArrayOutputStream(imageProxy.width * imageProxy.height / 4)
        yuvImage.compressToJpeg(
            Rect(0, 0, imageProxy.width, imageProxy.height),
            jpegQuality,
            out
        )
        return out.toByteArray()
    }

    val isRunning: Boolean get() = running.get()
}
