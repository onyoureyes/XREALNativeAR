package com.xreal.nativear.remote

import android.graphics.Bitmap
import android.util.Log
import com.xreal.nativear.VisionManager
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.resource.ResourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * NetworkCameraClient — 네트워크 카메라(학교 웹캠 등)를 AI 비전 파이프라인에 연결.
 *
 * ## 역할
 * - `ResourceActivated(CAMERA_NETWORK_ENDPOINT, isActive=true)` 이벤트 수신 → MJPEG 스트리밍 시작
 * - 수신 프레임 → `VisionManager.feedExternalFrame()` → OCR/객체감지/씬분석 파이프라인
 * - `VisionManager.isExternalFrameSourceActive = true` → CameraX 프레임 우회 + PreviewView 숨김
 * - `ResourceActivated(CAMERA_NETWORK_ENDPOINT, isActive=false)` → 스트리밍 중단 + CameraX 복원
 *
 * ## URL 관리
 * `RemoteCameraService.serverUrl`을 공유 URL 소스로 사용.
 * AI가 `configure_remote_camera(server_url=...)` 도구로 URL 설정 →
 * `NetworkCameraClient`가 `CAMERA_NETWORK_ENDPOINT` 활성화 시 해당 URL 사용.
 *
 * ## 프레임 흐름
 * ```
 * 학교 웹캠 서버 (MJPEG HTTP)
 *     ↓ MjpegStreamClient
 * NetworkCameraClient
 *     ├─ VisionManager.feedExternalFrame() ← AI 분석용
 *     └─ onFrameListener (선택)           ← PIP 표시용
 * ```
 *
 * ## 사용 예시
 * 1. AI: `configure_remote_camera(server_url="http://192.168.1.50:8554")` — URL 저장
 * 2. AI: `activate_resource("CAMERA_NETWORK_ENDPOINT")` — 이 클라이언트 자동 시작
 * 3. AI: 이후 모든 비전 분석이 웹캠 프레임 기반으로 동작
 * 4. AI: `deactivate_resource("CAMERA_NETWORK_ENDPOINT")` — CameraX 복원
 */
class NetworkCameraClient(
    private val visionManager: VisionManager,
    private val remoteCameraService: RemoteCameraService,  // URL 소스
    private val httpClient: OkHttpClient,
    private val eventBus: GlobalEventBus
) {
    private val TAG = "NetworkCameraClient"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mjpegClient: MjpegStreamClient? = null

    @Volatile private var _isStreaming = false
    val isStreaming: Boolean get() = _isStreaming

    // PIP 표시용 선택적 콜백 (OverlayView로 프레임 전달 시 사용)
    var onFrameListener: ((Bitmap) -> Unit)? = null
    var onErrorListener: ((String) -> Unit)? = null

    // =========================================================================
    // 생명주기
    // =========================================================================

    /**
     * EventBus 구독 시작. AppBootstrapper에서 호출.
     */
    fun start() {
        scope.launch {
            eventBus.events.collect { event ->
                try {
                    when (event) {
                        is XRealEvent.SystemEvent.ResourceActivated -> {
                            if (event.resourceType == ResourceType.CAMERA_NETWORK_ENDPOINT.name) {
                                if (event.isActive) startStream() else stopStream()
                            }
                        }
                        else -> {}
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "이벤트 처리 오류: ${e.message}", e)
                }
            }
        }
        Log.i(TAG, "NetworkCameraClient 시작 — ResourceActivated 이벤트 대기")
    }

    fun stop() {
        stopStream()
        Log.i(TAG, "NetworkCameraClient 종료")
    }

    // =========================================================================
    // 스트리밍 제어
    // =========================================================================

    /**
     * 네트워크 카메라 스트리밍 시작.
     * URL은 `RemoteCameraService.serverUrl`에서 가져옴 (`configure_remote_camera` 도구로 설정).
     */
    fun startStream(url: String? = null) {
        val targetUrl = url ?: remoteCameraService.serverUrl
        if (targetUrl.isBlank() || targetUrl == "http://100.64.88.46:8554") {
            // 기본 예시 URL은 유효하지 않을 수 있음 — 경고만 하고 계속 시도
            Log.w(TAG, "기본 URL 사용 중: $targetUrl. configure_remote_camera로 URL을 변경하세요.")
        }

        stopStream()
        _isStreaming = true

        // ★ 비전 파이프라인 전환: CameraX 프레임 우회, PreviewView 숨김
        visionManager.isExternalFrameSourceActive = true
        Log.i(TAG, "네트워크 카메라 활성화: $targetUrl → feedExternalFrame 연결")

        val videoUrl = if (targetUrl.endsWith("/video")) targetUrl else "$targetUrl/video"

        mjpegClient = MjpegStreamClient(httpClient).also { client ->
            client.start(
                url = videoUrl,
                scope = scope,
                onFrame = { bitmap ->
                    // AI 비전 파이프라인으로 프레임 전달
                    visionManager.feedExternalFrame(bitmap)
                    // PIP 표시용 선택적 콜백
                    onFrameListener?.invoke(bitmap)
                },
                onError = { error ->
                    Log.w(TAG, "스트림 오류: $error")
                    onErrorListener?.invoke(error)

                    // 연결 완전 실패 시 CameraX 복원
                    if (error.contains("Max retries")) {
                        Log.e(TAG, "네트워크 카메라 연결 실패 → CameraX 복원")
                        _isStreaming = false
                        visionManager.isExternalFrameSourceActive = false
                        scope.launch {
                            eventBus.publish(
                                XRealEvent.SystemEvent.Error(
                                    code = "NETWORK_CAMERA_FAILED",
                                    message = "네트워크 카메라 연결 실패: $error. CameraX로 복원됩니다."
                                )
                            )
                        }
                    }
                }
            )
        }
    }

    /**
     * 스트리밍 중단 및 CameraX 복원.
     */
    fun stopStream() {
        if (!_isStreaming) return
        Log.i(TAG, "네트워크 카메라 비활성화 → CameraX 복원")
        _isStreaming = false
        mjpegClient?.stop()
        mjpegClient = null

        // CameraX 복원: PreviewView 다시 표시
        visionManager.isExternalFrameSourceActive = false
    }

    fun release() {
        stopStream()
    }

    // =========================================================================
    // 상태 조회
    // =========================================================================

    /**
     * 현재 스트리밍 중인 URL 반환 (디버그/상태 조회용).
     */
    fun getCurrentUrl(): String = if (_isStreaming) remoteCameraService.serverUrl else "(비활성)"

    fun getStatusSummary(): String = buildString {
        appendLine("NetworkCameraClient 상태:")
        appendLine("  스트리밍: $_isStreaming")
        appendLine("  URL: ${remoteCameraService.serverUrl}")
        appendLine("  VisionManager 외부소스: ${visionManager.isExternalFrameSourceActive}")
    }.trimEnd()
}
