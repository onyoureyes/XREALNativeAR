package com.xreal.hardware

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.xreal.hardware.depth.StereoDepthEngine

/**
 * XRealHardwareManager — XREAL Light 하드웨어 파사드 (Facade Pattern).
 *
 * ## 아키텍처
 * ```
 * XRealHardwareManager (이 클래스)
 *   ├── USBDeviceRouter  → USB 디바이스 탐색 + 권한 관리
 *   ├── OV580Manager     → SLAM 스테레오 카메라 (640x480 @30fps) + IMU (1kHz)
 *   ├── MCUManager       → 디스플레이, 버튼, 하트비트 (250ms), RGB 전원 제어
 *   ├── RGBCameraUVC     → USB Host API + JNI ISOC 스트리밍 (1280x960 MJPEG @30fps)
 *   └── VIOManager       → OpenVINS 6-DoF Visual-Inertial Odometry
 * ```
 *
 * ## USB 디바이스 (XREAL Light)
 * | 디바이스 | VID | PID | 버스 |
 * |---------|-----|-----|------|
 * | MCU (STM32) | 0x0486 | 0x573C | USB2 |
 * | OV580 (스테레오) | 0x05A9 | 0x0680 | USB2 |
 * | Audio (Realtek) | 0x0BDA | 0x4B77 | USB2 |
 * | RGB Camera | 0x0817 | 0x0909 | USB3 |
 *
 * ## RGB 카메라 시작 시퀀스
 * 1. MCUManager.activate() → "1:h:1" 명령으로 RGB 카메라 전원 ON
 * 2. USB3 버스에 카메라 출현까지 ~3-7초 대기 (re-scan 스케줄링)
 * 3. USBDeviceRouter.scanForDevice()로 탐지
 * 4. RGBCameraUVC.start() → UVC Probe/Commit → JNI ISOC 스트리밍
 *
 * ## Re-scan 안전장치
 * - 카메라 발견 즉시 pendingRescanRunnables 전체 취소 (cancelPendingRescans)
 * - rescannForRGBCamera에서 probeAndLog 제거 (interface 충돌 방지)
 * - 각 re-scan은 rgbCameraDevice null 체크로 보호
 *
 * @see com.xreal.nativear.HardwareManager 상위 앱 레벨 매니저 (이 파사드를 사용)
 */
class XRealHardwareManager(private val context: Context) {
    private val TAG = "XRealHardwareManager"
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Logging ──
    private var screenLogCallback: ((String) -> Unit)? = null
    fun setScreenLogCallback(callback: (String) -> Unit) { screenLogCallback = callback }
    private fun sLog(msg: String) {
        Log.i(TAG, msg)
        mainHandler.post { screenLogCallback?.invoke(msg) }
    }

    // ── Native JNI bridge (for MCU display driver) ──
    companion object {
        init { System.loadLibrary("xreal-hardware") }
    }
    private external fun nativeActivate(fd: Int): Int
    private external fun nativeStartCamera(surface: Surface): Int
    private external fun nativeStopCamera()
    private external fun nativeStartIMU(fd: Int): Int
    private external fun nativeStopIMU()

    // ── Sub-managers ──
    private val router = USBDeviceRouter(context, ::sLog)
    private var ov580: OV580Manager? = null
    private var mcu: MCUManager? = null
    private var activeFd = -1

    // Connections (kept for lifecycle management)
    private var ov580Connection: UsbDeviceConnection? = null
    private var mcuConnection: UsbDeviceConnection? = null

    // RGB Camera (USB Host API UVC)
    private var rgbCameraUvc: RGBCameraUVC? = null
    private var rgbCameraDevice: UsbDevice? = null
    private var rgbCameraConnection: UsbDeviceConnection? = null
    private val pendingRescanRunnables = mutableListOf<Runnable>()

    // ── IMU Listener (delegate to OV580Manager) ──
    interface IMUListener {
        fun onOrientationUpdate(qx: Float, qy: Float, qz: Float, qw: Float)
    }
    var imuListener: IMUListener? = null
    fun setIMUListener(listener: IMUListener) { imuListener = listener }

    // ── SLAM Camera Listener ──
    var slamCameraListener: OV580SlamCamera.SlamFrameListener? = null

    // ── Vision Frame Listener (feeds SLAM frames to vision pipeline) ──
    var visionFrameListener: OV580SlamCamera.SlamFrameListener? = null

    // ── RGB Camera Listener ──
    var rgbCameraListener: RGBCameraUVC.RGBFrameListener? = null

    // ── VIO (6-DoF pose estimation) ──
    private var vioManager: VIOManager? = null
    private var vioFrameSkip = 0
    var vioPoseListener: VIOManager.PoseListener? = null

    // ── Main Entry Point ──

    fun findAndActivate(onReady: () -> Unit) {
        sLog("=== XRealHardwareManager findAndActivate ===")

        // ★ 이전 연결 정리 (재호출 시 USB connection 누수 방지)
        ov580Connection?.close()
        ov580Connection = null
        mcuConnection?.close()
        mcuConnection = null

        router.scanAndOpen { type, device, connection ->
            sLog(">>> Device delivered: ${type.name} <<<")

            when (type) {
                NrealDeviceType.OV580 -> {
                    sLog("Creating OV580Manager...")
                    ov580Connection = connection
                    val mgr = OV580Manager(device, connection, ::sLog)
                    mgr.imuListener = imuListener?.let { listener ->
                        object : OV580Manager.IMUListener {
                            override fun onOrientationUpdate(qx: Float, qy: Float, qz: Float, qw: Float) {
                                listener.onOrientationUpdate(qx, qy, qz, qw)
                            }
                        }
                    }
                    // Wire raw IMU → VIO pipeline
                    mgr.rawImuListener = object : OV580Manager.RawIMUListener {
                        override fun onRawIMU(gx: Float, gy: Float, gz: Float,
                                              ax: Float, ay: Float, az: Float,
                                              timestampUs: Long) {
                            vioManager?.feedIMU(gx, gy, gz, ax, ay, az, timestampUs)
                        }
                    }
                    mgr.slamCameraListener = slamCameraListener
                    mgr.activateIMU()
                    ov580 = mgr
                    sLog("OV580Manager ready (ov580 != null: ${ov580 != null})")
                }

                NrealDeviceType.MCU -> {
                    sLog("Creating MCUManager...")
                    mcuConnection = connection
                    activeFd = connection.fileDescriptor
                    sLog("MCU FD: $activeFd")
                    // ★ C-1 FIX: nativeActivate() 제거 — MCU FD에 OV580 HID 명령을 보내는 잘못된 코드
                    // OV580 활성화는 OV580Manager.activateIMU()에서 올바른 FD로 처리됨
                    // val result = nativeActivate(activeFd)  // 삭제: 잘못된 디바이스에 HID 명령

                    val mcuMgr = MCUManager(device, connection, ::sLog)
                    mcuMgr.activate {
                        sLog("MCU activate callback fired (SDK Works + RGB Power ON sent)")

                        // If RGB camera not found during initial scan,
                        // schedule re-scan after MCU powers it on (USB3 device may appear with delay)
                        if (rgbCameraDevice == null) {
                            sLog("RGB Camera not in initial scan. Scheduling re-scans after MCU activation...")
                            pendingRescanRunnables.clear()
                            val delays = longArrayOf(3000, 7000, 12000, 20000, 30000, 45000)
                            for (delay in delays) {
                                val runnable = Runnable {
                                    if (rgbCameraDevice == null) {
                                        rescannForRGBCamera()
                                    }
                                }
                                pendingRescanRunnables.add(runnable)
                                mainHandler.postDelayed(runnable, delay)
                            }
                        }
                    }
                    mcu = mcuMgr
                }

                NrealDeviceType.AUDIO -> {
                    sLog("Audio device found (not managed)")
                    connection.close()
                }

                NrealDeviceType.RGB_CAMERA -> {
                    sLog("RGB Camera USB device found! VID=0x${"%04X".format(device.vendorId)}")
                    sLog("Keeping connection for USB Host API UVC streaming.")
                    rgbCameraDevice = device
                    rgbCameraConnection = connection

                    // ★ FIX: probeAndLog() 제거 — VS interface claim/release가 커널 UVC 드라이버 재부착 유발
                    // start() 시점에 UVC 협상을 수행하므로 사전 probe 불필요
                    val uvc = RGBCameraUVC { msg -> sLog("[RGB] $msg") }
                    rgbCameraUvc = uvc
                }
            }
        }

        // Call onReady after scanAndOpen has processed all devices synchronously
        sLog("findAndActivate complete. OV580=${ov580 != null}, MCU=${mcu != null}, RGB=${rgbCameraDevice != null}")
        onReady()
    }

    /**
     * Re-scan for RGB camera after MCU activation.
     * The RGB camera may appear on USB3 after MCU sends power-on command.
     */
    /**
     * Re-scan for RGB camera after MCU activation.
     * The RGB camera appears on USB3 ~3-7s after MCU sends "1:h:1" power-on command.
     *
     * IMPORTANT: Do NOT call probeAndLog() here — it claims/releases the VS interface
     * for diagnostics only, which can interfere with subsequent start() by allowing
     * the kernel UVC driver to re-attach between release and re-claim.
     */
    private fun rescannForRGBCamera() {
        sLog("=== RGB Camera Re-Scan ===")
        router.scanForDevice(NrealDeviceType.RGB_CAMERA) { type, device, connection ->
            sLog("RGB Camera found on re-scan! VID=0x${"%04X".format(device.vendorId)}")
            // ★ 이전 연결 해제 (재스캔 시 USB connection 누수 방지)
            rgbCameraConnection?.close()
            rgbCameraDevice = device
            rgbCameraConnection = connection

            // Create UVC handler (no probe — start() will handle UVC negotiation)
            val uvc = RGBCameraUVC { msg -> sLog("[RGB] $msg") }
            rgbCameraUvc = uvc

            // Cancel pending re-scan runnables (camera found, no more re-scans needed)
            cancelPendingRescans()

            // Auto-start streaming if a listener is already registered
            if (rgbCameraListener != null) {
                sLog("RGB Camera: Auto-starting stream (listener already set)")
                uvc.listener = rgbCameraListener
                uvc.start(device, connection)
            }
        }
    }

    /** Cancel all pending RGB camera re-scan runnables (called when camera is found). */
    private fun cancelPendingRescans() {
        if (pendingRescanRunnables.isNotEmpty()) {
            sLog("Cancelling ${pendingRescanRunnables.size} pending RGB re-scan runnables")
            for (runnable in pendingRescanRunnables) {
                mainHandler.removeCallbacks(runnable)
            }
            pendingRescanRunnables.clear()
        }
    }

    // ── Public API (delegates) ──

    fun isActivated(): Boolean = activeFd != -1 || ov580 != null

    fun startCamera(surface: Surface) {
        if (activeFd == -1) return
        nativeStartCamera(surface)
    }

    fun stopCamera() = nativeStopCamera()

    fun startIMU() {
        if (activeFd != -1) nativeStartIMU(activeFd)
    }

    fun stopIMU() = nativeStopIMU()

    fun startSlamCamera() {
        val mgr = ov580
        if (mgr == null) {
            sLog("SLAM: OV580 not activated yet! Call 'Find & Activate XREAL' first.")
            return
        }
        mgr.slamCameraListener = slamCameraListener
        mgr.startCamera()
    }

    fun stopSlamCamera() {
        ov580?.stopCamera()
    }

    // ── RGB Camera API (USB Host UVC) ──

    fun startRGBCamera() {
        val device = rgbCameraDevice
        val connection = rgbCameraConnection
        if (device == null || connection == null) {
            sLog("RGB Camera: Not found yet. Triggering immediate re-scan...")
            sLog("RGB Camera: device=${device != null}, connection=${connection != null}")
            // Try to find it now - user pressed button, camera may have just appeared
            rescannForRGBCamera()
            // Schedule follow-up attempts
            mainHandler.postDelayed({
                if (rgbCameraDevice != null && rgbCameraConnection != null) {
                    sLog("RGB Camera appeared! Starting stream...")
                    val uvc2 = rgbCameraUvc ?: RGBCameraUVC { msg -> sLog("[RGB] $msg") }.also { rgbCameraUvc = it }
                    uvc2.listener = rgbCameraListener
                    uvc2.start(rgbCameraDevice!!, rgbCameraConnection!!)
                } else {
                    sLog("RGB Camera still not found after re-scan.")
                }
            }, 2000)
            return
        }

        val uvc = rgbCameraUvc ?: RGBCameraUVC { msg -> sLog("[RGB] $msg") }.also { rgbCameraUvc = it }
        uvc.listener = rgbCameraListener
        uvc.start(device, connection)
    }

    fun stopRGBCamera() {
        rgbCameraUvc?.stop()
    }

    // ── Stereo Depth Engine ──
    private val stereoDepthEngine = StereoDepthEngine()

    /** 스테레오 깊이 추정 엔진 접근 (앱 모듈에서 SpatialAnchorManager에 전달) */
    fun getStereoDepthEngine(): StereoDepthEngine = stereoDepthEngine

    // ── VIO API (6-DoF) ──

    /**
     * Initialize and start the VIO pipeline.
     * Requires OV580 to be activated first (IMU + SLAM camera).
     * Also starts the SLAM camera if not already running, and
     * feeds stereo frames to VIO (every 2nd frame = ~15fps).
     */
    fun startVIO() {
        val mgr = ov580
        if (mgr == null) {
            sLog("VIO: OV580 not activated yet!")
            return
        }

        // Initialize VIOManager
        val vio = VIOManager(::sLog)
        if (!vio.init()) {
            sLog("VIO: Failed to initialize!")
            return
        }
        vio.poseListener = vioPoseListener
        vioManager = vio

        // Start SLAM camera for stereo frames (if not already running)
        mgr.slamCameraListener = object : OV580SlamCamera.SlamFrameListener {
            override fun onFrame(frame: OV580SlamCamera.SlamFrame) {
                // Forward to external SLAM listener if set
                slamCameraListener?.onFrame(frame)

                // Forward to vision frame listener if set
                visionFrameListener?.onFrame(frame)

                // Feed stereo frames to depth engine (every frame — SAD is sparse/lazy)
                stereoDepthEngine.updateStereoFrames(frame.left, frame.right, frame.timestamp)

                // Feed every 2nd frame to VIO (~15fps effective)
                if (vioFrameSkip++ % 2 == 0) {
                    vioManager?.feedStereoFrame(
                        frame.left, frame.right,
                        640, 480, // SLAM camera is always 640x480
                        frame.timestamp
                    )
                }
            }
        }
        mgr.startCamera()

        // Start VIO processing
        vio.start()
        sLog("VIO started (6-DoF pose estimation)")
    }

    fun stopVIO() {
        vioManager?.stop()
        sLog("VIO stopped")
    }

    fun scanAndLogDeviceDetails(logCallback: (String) -> Unit) {
        router.scanAndLogDetails(logCallback)
    }

    // ── Lifecycle ──

    fun release() {
        stereoDepthEngine.release()

        vioManager?.release()
        vioManager = null

        rgbCameraUvc?.stop()
        rgbCameraUvc = null

        ov580?.release()
        ov580 = null
        mcu?.release()
        mcu = null
        router.release()

        stopIMU()
        stopCamera()

        mcuConnection?.close()
        mcuConnection = null
        ov580Connection?.close()
        ov580Connection = null
        rgbCameraConnection?.close()
        rgbCameraConnection = null
        rgbCameraDevice = null
        activeFd = -1

        sLog("XRealHardwareManager released")
    }

    // Called from Native code via JNI
    fun onNativeIMU(qx: Float, qy: Float, qz: Float, qw: Float) {
        mainHandler.post {
            imuListener?.onOrientationUpdate(qx, qy, qz, qw)
        }
    }
}
