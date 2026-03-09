package com.xreal.nativear.focus

import android.util.Log
import com.xreal.nativear.core.FocusMode
import com.xreal.nativear.core.GlobalEventBus
import kotlin.coroutines.cancellation.CancellationException
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.hand.HandData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * PalmFaceGestureDetector — 손바닥-얼굴 근접 제스처 감지.
 *
 * ## 작동 방식
 * HandsDetected 이벤트에서 손 랜드마크를 수신.
 * 손바닥이 얼굴 방향으로 ~15cm 이내 접근 후 1초 유지 시 PRIVATE 모드 활성화.
 * 손을 내리면 NORMAL 복귀.
 *
 * ## 랜드마크 기반 거리 추정
 * MediaPipe Hands 21개 랜드마크 사용:
 * - 손목(0), 중지끝(12) 벡터 → 손바닥 크기 추정
 * - 손바닥 크기와 z값(화면 깊이)으로 카메라와의 거리 추정
 * - 얼굴은 HeadPoseUpdated에서 위치 추정 (없으면 화면 중심 기준)
 *
 * ## 제스처 판정 기준
 * - 손바닥 면적이 일정 크기 이상 (가까이 있음)
 * - 손바닥이 카메라(얼굴) 방향을 향함 (중지 끝 z < 손목 z)
 * - 위 조건이 1000ms 지속
 * - 해제: 손이 감지되지 않거나 멀어지면 NORMAL 복귀 (2초 딜레이)
 */
class PalmFaceGestureDetector(
    private val eventBus: GlobalEventBus,
    private val focusModeManager: FocusModeManager
) {
    companion object {
        private const val TAG = "PalmFaceGesture"
        private const val HOLD_DURATION_MS = 1000L   // 1초 유지 시 PRIVATE 활성화
        private const val RELEASE_DELAY_MS = 2000L   // 손 내린 후 2초 딜레이로 NORMAL 복귀
        private const val PALM_SIZE_THRESHOLD = 0.18f // 정규화된 손 크기 임계값 (0~1)
        private const val FACING_Z_THRESHOLD = 0.05f  // 손바닥이 카메라 향할 때 z 차이
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var palmNearFaceStartMs = 0L
    @Volatile private var lastHandDetectedMs = 0L
    @Volatile private var isPrivateActive = false

    fun start() {
        scope.launch {
            eventBus.events.collect { event ->
                try {
                    when (event) {
                        is XRealEvent.PerceptionEvent.HandsDetected -> processHands(event)
                        else -> {}
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "이벤트 처리 오류 (루프 유지됨): ${event::class.simpleName} — ${e.message}", e)
                }
            }
        }
        // 주기적으로 손 미감지 시 NORMAL 복귀 체크
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(500L)
                checkReleaseCondition()
            }
        }
        Log.d(TAG, "PalmFaceGestureDetector 시작")
    }

    fun stop() {
        Log.d(TAG, "PalmFaceGestureDetector 종료")
    }

    private fun processHands(event: XRealEvent.PerceptionEvent.HandsDetected) {
        lastHandDetectedMs = System.currentTimeMillis()

        if (event.hands.isEmpty()) {
            // 손이 감지 안 되면 근접 타이머 리셋
            palmNearFaceStartMs = 0L
            return
        }

        val isPalmFacing = event.hands.any { hand -> isPalmFacingCamera(hand) }

        if (isPalmFacing) {
            if (palmNearFaceStartMs == 0L) {
                palmNearFaceStartMs = System.currentTimeMillis()
                Log.d(TAG, "손바닥 얼굴 방향 감지 — 타이머 시작")
            } else {
                val holdMs = System.currentTimeMillis() - palmNearFaceStartMs
                if (holdMs >= HOLD_DURATION_MS && !isPrivateActive) {
                    Log.i(TAG, "손바닥 제스처 확인 (${holdMs}ms 유지) — PRIVATE 활성화")
                    isPrivateActive = true
                    focusModeManager.setMode(FocusMode.PRIVATE, "palm_face_gesture")
                }
            }
        } else {
            // 손이 있지만 얼굴 방향 안 향함
            palmNearFaceStartMs = 0L
        }
    }

    /**
     * 손바닥이 카메라(얼굴) 방향을 향하고 가까이 있는지 판정.
     *
     * MediaPipe Hands 21 랜드마크:
     * - index 0: WRIST
     * - index 9: MIDDLE_FINGER_MCP (손바닥 중심)
     * - index 12: MIDDLE_FINGER_TIP (중지 끝)
     *
     * 손바닥 크기 = 손목(0)과 중지MCP(9) 사이 정규화 거리
     * 방향 = 손가락 끝(12)의 z가 손목(0)의 z보다 작으면 카메라 향함
     */
    private fun isPalmFacingCamera(hand: HandData): Boolean {
        val landmarks = hand.landmarks
        if (landmarks.size < 13) return false

        val wrist = landmarks[0]
        val middleMcp = landmarks[9]
        val middleTip = landmarks[12]

        // 손 크기 추정 (손목~중지MCP 정규화 거리)
        val palmSize = Math.hypot(
            (middleMcp.x - wrist.x).toDouble(),
            (middleMcp.y - wrist.y).toDouble()
        ).toFloat()

        // 크기 임계값: 충분히 가까이 있는가
        if (palmSize < PALM_SIZE_THRESHOLD) return false

        // 방향: 중지끝 z < 손목 z → 손바닥이 카메라 방향
        val facingCamera = (wrist.z - middleTip.z) > FACING_Z_THRESHOLD

        return facingCamera
    }

    private fun checkReleaseCondition() {
        if (!isPrivateActive) return
        val timeSinceHand = System.currentTimeMillis() - lastHandDetectedMs
        if (timeSinceHand > RELEASE_DELAY_MS && lastHandDetectedMs > 0L) {
            Log.i(TAG, "손 제스처 해제 — NORMAL 복귀")
            isPrivateActive = false
            palmNearFaceStartMs = 0L
            // PRIVATE 상태인 경우에만 복귀 (사용자가 음성으로 다른 모드 설정했을 수 있음)
            if (focusModeManager.currentMode == FocusMode.PRIVATE) {
                focusModeManager.setMode(FocusMode.NORMAL, "palm_face_release")
            }
        }
    }
}
