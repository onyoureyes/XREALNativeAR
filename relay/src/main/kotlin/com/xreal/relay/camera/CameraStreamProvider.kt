package com.xreal.relay.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
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
        val width = imageProxy.width
        val height = imageProxy.height
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        // NV21: Y plane + interleaved VU
        val nv21 = ByteArray(width * height + width * height / 2)

        // Y plane — rowStride가 width와 다를 수 있음
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, width * height)
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, row * width, width)
            }
        }

        // UV planes — pixelStride에 따라 처리
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        var offset = width * height

        if (uvPixelStride == 2) {
            // pixelStride=2: U,V가 이미 인터리빙 (대부분 기기)
            // vBuffer가 VUVU... 형태 → NV21에 직접 복사 가능
            if (uvRowStride == width) {
                vBuffer.get(nv21, offset, width * chromaHeight)
            } else {
                for (row in 0 until chromaHeight) {
                    vBuffer.position(row * uvRowStride)
                    vBuffer.get(nv21, offset + row * width, width)
                }
            }
        } else {
            // pixelStride=1: U,V가 별도 planar → 수동 인터리빙
            for (row in 0 until chromaHeight) {
                for (col in 0 until chromaWidth) {
                    val uvIndex = row * uvRowStride + col * uvPixelStride
                    nv21[offset++] = vBuffer.get(uvIndex)  // V first (NV21)
                    nv21[offset++] = uBuffer.get(uvIndex)  // then U
                }
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream(width * height / 4)
        yuvImage.compressToJpeg(Rect(0, 0, width, height), jpegQuality, out)
        return out.toByteArray()
    }

    val isRunning: Boolean get() = running.get()
}
