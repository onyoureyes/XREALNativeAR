package com.xreal.nativear

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * HardwareManager — XREAL 하드웨어와 폰 센서의 통합 관리자.
 *
 * ## 관리 대상
 * | 하드웨어 | 역할 | 프로토콜 |
 * |---------|------|---------|
 * | MCU (STM32) | 디스플레이, 버튼, 하트비트, RGB 카메라 전원 | HID, 64B 패킷, CRC-32 |
 * | OV580 | SLAM 스테레오 카메라 (640x480 @ 30fps) + IMU (1kHz) | Bulk USB |
 * | RGB 카메라 | 1280x960 MJPEG @ 30fps | ISOC USB3 (JNI) |
 * | 폰 가속도계 | 러닝 역학 분석 (VO, GCT, GRF) | Android SensorManager |
 * | 폰 걸음감지기 | PDR + 케이던스 | Android TYPE_STEP_DETECTOR |
 *
 * ## 비전 파이프라인 우선순위
 * RGB 카메라 활성 시 → VisionManager에 MJPEG 프레임 공급 (컬러, 고해상도)
 * RGB 비활성 시 → OV580 SLAM 좌안 → StereoRectifier → VisionManager (흑백)
 *
 * ## PDR (Pedestrian Dead Reckoning)
 * 걸음감지기 + VIO 쿼터니언에서 추출한 yaw → PdrStepUpdate 이벤트 발행.
 * PositionFusionEngine이 구독하여 GPS 손실 시 위치 추적에 활용.
 * 보폭 기본값 0.65m (PositionFusionEngine에서 GPS 양호 시 자동보정).
 *
 * ## VIO (Visual-Inertial Odometry)
 * OV580 IMU + 스테레오 프레임 → OpenVINS → 6-DoF 포즈 추정.
 * HeadPoseUpdated(is6DoF=true) 이벤트 발행 → GestureManager + 러닝 코치.
 *
 * @see com.xreal.hardware.XRealHardwareManager 하드웨어 파사드 (MCU, OV580, RGB)
 * @see PositionFusionEngine PDR 이벤트 소비자
 */
class HardwareManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val eventBus: com.xreal.nativear.core.GlobalEventBus,
    private val xrealHardwareManager: com.xreal.hardware.XRealHardwareManager,
    private val visionManager: VisionManager,
    private val cadenceConfig: com.xreal.nativear.cadence.CadenceConfig,
    private val userStateTracker: com.xreal.nativear.cadence.UserStateTracker,
    // ★ CameraStreamManager: 소스 선택 + 건강 + fallback (null이면 기존 직접 호출 동작)
    private val cameraStreamManager: com.xreal.nativear.camera.CameraStreamManager? = null
) : android.hardware.SensorEventListener, com.xreal.hardware.XRealHardwareManager.IMUListener {
    private val TAG = "HardwareManager"

    // SLAM-to-Vision adapter (lazy to avoid OpenCV native crash before initDebug)
    private var _stereoRectifier: com.xreal.hardware.StereoRectifier? = null
    private val stereoRectifier: com.xreal.hardware.StereoRectifier
        get() {
            if (_stereoRectifier == null) _stereoRectifier = com.xreal.hardware.StereoRectifier()
            return _stereoRectifier!!
        }
    private var visionReuseBitmap: Bitmap? = null
    private var visionFrameSkip = 0

    // Filament 3D 렌더러 프레임 수신 (카메라 배경 텍스처)
    var filamentRenderer: com.xreal.nativear.renderer.FilamentRenderer? = null

    // RGB-to-Vision adapter (higher priority than SLAM — color 1280x960 MJPEG)
    @Volatile private var rgbVisionActive = false
    private var rgbVisionFrameSkip = 0
    private var rgbVisionBitmap: Bitmap? = null
    private val rgbDecodeOptions = android.graphics.BitmapFactory.Options().apply {
        inMutable = true
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    /**
     * CONFLATED 채널: JNI ISOC 콜백 스레드에서 논블로킹으로 프레임 핸드오프.
     * Channel.CONFLATED = 최신 프레임만 유지 → 처리 지연 시 자동 드롭.
     * 이 채널이 없으면 URB reaping 루프가 블로킹 → 200 REAPURB 실패 → 카메라 크래시.
     */
    private val rgbFrameChannel = Channel<ByteArray>(capacity = Channel.CONFLATED)

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
    private val stepDetector = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_DETECTOR)

    // Running Coach: lazy inject to avoid circular dependency
    private val runningCoachManager: com.xreal.nativear.running.RunningCoachManager? by lazy {
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull<com.xreal.nativear.running.RunningCoachManager>() } catch (e: Exception) { null }
    }

    // Drift correction (set by AppBootstrapper after construction)
    var driftCorrectionManager: com.xreal.nativear.spatial.DriftCorrectionManager? = null

    // SLAM frame feed counter for drift correction thumbnails
    private var slamFrameFeedCount = 0

    // DeviceHealthMonitor가 참조: MCU/IMU 연결 여부 판단용
    // onOrientationUpdate()에서 갱신 (IMU = MCU 살아있음 지표)
    @Volatile var lastMcuHeartbeatMs: Long = 0L

    // PDR State
    private var currentYaw = 0.0f
    private var pdrX = 0.0f
    private var pdrY = 0.0f
    private var accumulatedSteps = 0
    private val STRIDE_LENGTH = 0.65f

    // Stability State
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var stabilityStartTime = 0L
    private var hasTriggeredForCurrentStability = false

    interface HardwareCallback {
        fun onStabilityProgress(progress: Int)
    }
    var callback: HardwareCallback? = null

    fun startHardware() {
        Log.i(TAG, "Starting Hardware Manager...")
        accelerometer?.also { sensorManager.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_UI) }
        stepDetector?.also { sensorManager.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_UI) }

        // Debug: Log all connected USB devices before activation
        Log.i(TAG, "=== Pre-Activation USB Device Scan ===")
        xrealHardwareManager.scanAndLogDeviceDetails { msg -> Log.i(TAG, msg) }

        // ★ RGB 프레임 비동기 처리 코루틴 (CONFLATED 채널 소비자)
        // JNI ISOC 스레드는 trySend()만 하고 즉시 반환 → URB reaping 루프 블로킹 방지
        scope.launch(Dispatchers.Default) {
            for (data in rgbFrameChannel) {
                processRgbFrameData(data)
            }
        }

        // Set up RGB camera frame listener → Vision pipeline
        // ★ 수정: JNI 스레드는 trySend()만 → 즉시 반환 (논블로킹)
        xrealHardwareManager.rgbCameraListener = object : com.xreal.hardware.RGBCameraUVC.RGBFrameListener {
            private var callbackFrameCount = 0
            override fun onFrame(frame: com.xreal.hardware.RGBCameraUVC.RGBFrame) {
                callbackFrameCount++
                if (callbackFrameCount <= 3 || callbackFrameCount % 100 == 0) {
                    Log.i(TAG, "RGB Frame #${frame.frameNumber}: ${frame.data.size} bytes, ${frame.width}x${frame.height}")
                }
                // ★ 핵심: JNI 스레드는 채널에 넣고 즉시 반환
                // CONFLATED = 처리 중이면 이전 미처리 프레임은 버리고 최신으로 교체
                rgbFrameChannel.trySend(frame.data)
            }
        }

        // Set up SLAM-to-Vision frame adapter (feeds left eye to VisionManager)
        // Note: RGB camera takes priority when available (color, higher res)
        xrealHardwareManager.visionFrameListener = object : com.xreal.hardware.OV580SlamCamera.SlamFrameListener {
            override fun onFrame(frame: com.xreal.hardware.OV580SlamCamera.SlamFrame) {
                // Feed SLAM frame to drift correction (every ~300 frames ≈ 10 seconds @ 30fps)
                if (slamFrameFeedCount++ % 300 == 0) {
                    driftCorrectionManager?.feedSlamFrame(frame.left)
                }

                if (rgbVisionActive) return  // RGB camera active — skip SLAM vision feed
                if (visionFrameSkip++ % cadenceConfig.current.slamFrameInterval != 0) return

                if (visionReuseBitmap == null) {
                    visionReuseBitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
                    Log.i(TAG, "SLAM Vision: Allocated reusable bitmap 640x480")
                }

                if (stereoRectifier.rectifyLeftInto(frame.left, visionReuseBitmap!!)) {
                    // ★ 복사본 전달 — VisionManager 비동기 처리 중 다음 프레임이 원본 덮어쓰기 방지
                    val frameCopy = visionReuseBitmap!!.copy(visionReuseBitmap!!.config ?: Bitmap.Config.ARGB_8888, false)
                    if (cameraStreamManager != null) {
                        cameraStreamManager.onSlamFrame(frameCopy)
                    } else {
                        visionManager.feedExternalFrame(frameCopy)
                    }
                }
            }
        }

        // Set up VIO 6-DoF pose listener (with drift correction)
        xrealHardwareManager.vioPoseListener = object : com.xreal.hardware.VIOManager.PoseListener {
            override fun onPoseUpdate(x: Float, y: Float, z: Float,
                                       qx: Float, qy: Float, qz: Float, qw: Float,
                                       timestamp: Double) {
                // Apply drift correction before publishing
                val dcm = driftCorrectionManager
                val cx: Float; val cy: Float; val cz: Float
                val cqx: Float; val cqy: Float; val cqz: Float; val cqw: Float

                if (dcm != null && dcm.isActive) {
                    val corrected = dcm.applyCorrection(x, y, z, qx, qy, qz, qw)
                    cx = corrected.x; cy = corrected.y; cz = corrected.z
                    cqx = corrected.qx; cqy = corrected.qy; cqz = corrected.qz; cqw = corrected.qw
                } else {
                    cx = x; cy = y; cz = z
                    cqx = qx; cqy = qy; cqz = qz; cqw = qw
                }

                eventBus.publish(com.xreal.nativear.core.XRealEvent.PerceptionEvent.HeadPoseUpdated(
                    x = cx, y = cy, z = cz,
                    qx = cqx, qy = cqy, qz = cqz, qw = cqw,
                    is6DoF = true
                ))
                // Update yaw for PDR fallback (use corrected quaternion)
                currentYaw = Math.atan2(2.0 * (cqw * cqz + cqx * cqy), 1.0 - 2.0 * (cqy * cqy + cqz * cqz)).toFloat()
            }
        }

        // IMU Integration — Kotlin OV580Manager가 IMU를 관리 (native IMU는 사용 안 함)
        // ★ C-2 FIX: nativeStartIMU()는 MCU FD로 잘못된 HID interface를 claim → crash
        //   OV580Manager.activateIMU()가 올바른 OV580 FD로 HID interface 2를 claim + Madgwick AHRS
        //   native_hw.cpp의 IMU 구현은 레거시 — 오프셋, 스케일 모두 다름 (hardware.md 참조)
        xrealHardwareManager.setIMUListener(this)
        xrealHardwareManager.findAndActivate {
            if (!xrealHardwareManager.isActivated()) {
                Log.w(TAG, "findAndActivate completed but NO hardware found. Keeping phone camera active.")
                eventBus.publish(com.xreal.nativear.core.XRealEvent.SystemEvent.DebugLog("No XREAL hardware detected"))
                return@findAndActivate
            }

            Log.i(TAG, "★ XREAL Activated (MCU+OV580) — AR 카메라로 전환")
            // ★ C-2 FIX: startIMU() 호출 제거 — OV580Manager.activateIMU()에서 이미 IMU 시작됨
            // xrealHardwareManager.startIMU()  // 삭제: native IMU는 MCU FD 사용 → crash
            eventBus.publish(com.xreal.nativear.core.XRealEvent.SystemEvent.DebugLog("XREAL Hardware Activated — AR center camera 전환"))

            // Start VIO 6-DoF pipeline (IMU + SLAM camera → OpenVINS)
            // VIO 초기화 실패 시 (네이티브 크래시 포함) 앱이 죽지 않도록 보호
            Log.i(TAG, "Starting VIO 6-DoF pipeline...")
            try {
                xrealHardwareManager.startVIO()
            } catch (e: Exception) {
                Log.e(TAG, "VIO 초기화 실패 — VIO 없이 계속 (SLAM+RGB만 사용): ${e.message}", e)
                eventBus.publish(com.xreal.nativear.core.XRealEvent.SystemEvent.Error(
                    code = "VIO_INIT_ERROR",
                    message = "VIO 6-DoF 초기화 실패: ${e.message?.take(100)}",
                    throwable = e
                ))
            }

            // Switch vision input: CameraStreamManager 또는 직접 전환
            if (cameraStreamManager != null) {
                cameraStreamManager.onGlassesConnected()
                // ★ RGB ISOC 재시작 콜백 등록 — 스트림 끊김 시 자동 복구
                cameraStreamManager.rgbRestartCallback = {
                    Log.i(TAG, "=== RGB ISOC 재시작 (CameraStreamManager 요청) ===")
                    xrealHardwareManager.stopRGBCamera()
                    Thread.sleep(500)  // USB 인터페이스 해제 대기
                    xrealHardwareManager.startRGBCamera()
                }
            } else {
                visionManager.isExternalFrameSourceActive = true
            }
            Log.i(TAG, "SLAM Vision: External frame source activated")

            // ★ RGB 카메라 시작: backoff 재시도 (기존 하드코딩 5초 대체)
            scope.launch {
                val maxAttempts = com.xreal.nativear.policy.PolicyReader.getInt("camera.rgb_retry_max_attempts", 3)
                val initialDelay = com.xreal.nativear.policy.PolicyReader.getLong("camera.rgb_retry_initial_delay_ms", 3000L)
                val backoff = com.xreal.nativear.policy.PolicyReader.getFloat("camera.rgb_retry_backoff_factor", 1.5f)

                var currentDelay = initialDelay
                for (attempt in 1..maxAttempts) {
                    delay(currentDelay)
                    Log.i(TAG, "=== RGB Camera Start Attempt $attempt/$maxAttempts (${currentDelay}ms 대기 후) ===")
                    xrealHardwareManager.scanAndLogDeviceDetails { msg -> Log.i(TAG, "[POST] $msg") }
                    try {
                        xrealHardwareManager.startRGBCamera()
                        Log.i(TAG, "RGB Camera start command sent (attempt $attempt)")
                        break  // 명령 전송 성공 → 루프 종료 (실제 프레임 도착은 onRgbFrame에서 확인)
                    } catch (e: Exception) {
                        Log.w(TAG, "RGB Camera start failed (attempt $attempt/$maxAttempts): ${e.message}")
                        if (attempt == maxAttempts) {
                            Log.e(TAG, "RGB Camera start 최종 실패 — SLAM fallback 유지")
                            eventBus.publish(com.xreal.nativear.core.XRealEvent.SystemEvent.DebugLog(
                                "RGB 카메라 시작 실패 (${maxAttempts}회 재시도) — SLAM 모드 유지"
                            ))
                        }
                    }
                    currentDelay = (currentDelay * backoff).toLong()
                }
            }
        }
    }

    /**
     * RGB 프레임 실제 처리 — Dispatchers.Default 코루틴에서 실행 (JNI 스레드 아님).
     * Filament 업로드 + Vision 파이프라인 피딩을 담당.
     */
    private fun processRgbFrameData(data: ByteArray) {
        // Filament: 매 프레임 MJPEG 전달 (Filament 내부에서 디코딩)
        filamentRenderer?.uploadCameraFrame(data)

        // Feed to Vision pipeline (every Nth frame = ~10fps)
        if (rgbVisionFrameSkip++ % cadenceConfig.current.rgbFrameInterval != 0) return

        try {
            // Reuse bitmap across frames to avoid GC pressure
            rgbDecodeOptions.inBitmap = rgbVisionBitmap
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(
                data, 0, data.size, rgbDecodeOptions
            )
            if (bitmap != null) {
                rgbVisionBitmap = bitmap
                if (!rgbVisionActive) {
                    rgbVisionActive = true
                    Log.i(TAG, "RGB Vision: Active — RGB camera takes over from SLAM for vision")
                    cameraStreamManager?.onRgbConnected()
                }
                // ★ CameraStreamManager 경유 (건강 추적 + fallback 판단)
                if (cameraStreamManager != null) {
                    cameraStreamManager.onRgbFrame(bitmap)
                } else {
                    visionManager.feedExternalFrame(bitmap)
                }
            }
        } catch (e: Exception) {
            // inBitmap reuse can fail if size changes; retry without reuse next time
            rgbVisionBitmap = null
            rgbDecodeOptions.inBitmap = null
            Log.w(TAG, "RGB Vision decode error (will retry): ${e.message}")
        }
    }

    fun stopHardware() {
        Log.i(TAG, "Stopping Hardware Manager...")
        if (cameraStreamManager != null) {
            cameraStreamManager.onGlassesDisconnected()
        } else {
            visionManager.isExternalFrameSourceActive = false
        }
        rgbVisionActive = false
        rgbFrameChannel.close()
        sensorManager.unregisterListener(this)
        xrealHardwareManager.stopVIO()
        xrealHardwareManager.stopRGBCamera()
        // ★ C-2 FIX: stopIMU() 제거 — native IMU는 시작하지 않았으므로 중지할 필요 없음
        // OV580Manager.release()에서 Kotlin IMU 스레드를 정리함
        // xrealHardwareManager.stopIMU()  // 삭제
        xrealHardwareManager.stopCamera()
        visionReuseBitmap?.recycle()
        visionReuseBitmap = null
        rgbVisionBitmap = null  // Don't recycle — may be inBitmap reference
        _stereoRectifier?.release()
        _stereoRectifier = null
    }

    override fun onOrientationUpdate(qx: Float, qy: Float, qz: Float, qw: Float) {
        // IMU 업데이트 = MCU 생존 지표 (DeviceHealthMonitor에서 3s 초과 시 glasses disconnected 판단)
        lastMcuHeartbeatMs = System.currentTimeMillis()

        eventBus.publish(com.xreal.nativear.core.XRealEvent.PerceptionEvent.HeadPoseUpdated(qx = qx, qy = qy, qz = qz, qw = qw))

        // Convert to Euler to get Yaw for PDR
        // Yaw (Y-axis rotation in some conventions, here Z-up usually)
        currentYaw = Math.atan2(2.0 * (qw * qz + qx * qy), 1.0 - 2.0 * (qy * qy + qz * qz)).toFloat()
    }

    override fun onSensorChanged(event: android.hardware.SensorEvent?) {
        if (event == null) return
        
        if (event.sensor.type == android.hardware.Sensor.TYPE_STEP_DETECTOR) {
            runningCoachManager?.onStepDetected()
            userStateTracker.onStepDetected()
            accumulatedSteps++
            
            // Update PDR
            val dx = (STRIDE_LENGTH * Math.cos(currentYaw.toDouble())).toFloat()
            val dy = (STRIDE_LENGTH * Math.sin(currentYaw.toDouble())).toFloat()
            pdrX += dx
            pdrY += dy
            eventBus.publish(com.xreal.nativear.core.XRealEvent.PerceptionEvent.PdrStepUpdate(pdrX.toDouble(), pdrY.toDouble(), System.currentTimeMillis()))

            val stepThreshold = cadenceConfig.current.pdrStepThreshold
            val progress = ((accumulatedSteps.toDouble() / stepThreshold) * 100).toInt().coerceAtMost(100)
            if (accumulatedSteps >= stepThreshold) {
                accumulatedSteps = 0
                eventBus.publish(com.xreal.nativear.core.XRealEvent.ActionRequest.TriggerSnapshot)
            }
            return
        }

        if (event.sensor.type == android.hardware.Sensor.TYPE_ACCELEROMETER) {
            runningCoachManager?.onAccelerometerData(event.values[0], event.values[1], event.values[2])
            val delta = Math.abs(lastX - event.values[0]) + Math.abs(lastY - event.values[1]) + Math.abs(lastZ - event.values[2])
            if (delta > cadenceConfig.current.stabilityAccelThreshold) {
                stabilityStartTime = 0L
                hasTriggeredForCurrentStability = false
                callback?.onStabilityProgress(0)
            } else {
                if (!hasTriggeredForCurrentStability) {
                    if (stabilityStartTime == 0L) stabilityStartTime = System.currentTimeMillis()
                    val duration = System.currentTimeMillis() - stabilityStartTime
                    val progress = (duration / 20.0).toInt().coerceAtMost(100)
                    if (duration >= cadenceConfig.current.stabilityDurationMs) {
                        eventBus.publish(com.xreal.nativear.core.XRealEvent.ActionRequest.TriggerSnapshot)
                        hasTriggeredForCurrentStability = true
                    }
                }
            }
            lastX = event.values[0]
            lastY = event.values[1]
            lastZ = event.values[2]
        }
    }

    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
}


