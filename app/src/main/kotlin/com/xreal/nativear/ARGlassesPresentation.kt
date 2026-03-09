package com.xreal.nativear

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Display

/**
 * ARGlassesPresentation — XREAL Light AR 안경 전용 HUD 렌더러.
 *
 * ## 동작 원리
 * XREAL Light는 USB-C DisplayPort Alt Mode로 연결 → Android가 보조 디스플레이로 인식.
 * `android.app.Presentation`을 통해 이 보조 디스플레이에 독립적인 레이아웃 렌더링 가능.
 *
 * ## 웨이브가이드 디스플레이 특성
 * - 검정(#000000) = 완전 투명 → 현실 세계 보임
 * - 밝은 색상 = 부유하는 오버레이로 보임
 * - 해상도: 1920x1080 (XREAL Light 기준)
 *
 * ## 사용법
 * MainActivity에서 DisplayManager.DisplayListener를 통해 XREAL 디스플레이 감지 후
 * ARGlassesPresentation(context, display).show() 호출.
 * 이후 onDrawingCommand() 이벤트를 overlayView.applyDrawCommand()로 포워딩.
 *
 * @see ArHudActivity (hardware-test-app의 참조 구현)
 * @see NrealPresentation (hardware-test-app의 경량 버전)
 */
class ARGlassesPresentation(context: Context, display: Display) : Presentation(context, display) {

    private val TAG = "ARGlassesPresentation"

    /** AR 안경에 렌더링되는 전체 HUD 오버레이 뷰 */
    lateinit var overlayView: OverlayView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.presentation_ar_glasses)
        overlayView = findViewById(R.id.arGlassesOverlay)
        Log.i(TAG, "AR Glasses HUD 초기화 완료 (${display.width}x${display.height})")
    }

    /** DrawingCommand 이벤트를 AR 안경 OverlayView에 적용 */
    fun applyDrawCommand(command: DrawCommand) {
        overlayView.applyDrawCommand(command)
    }

    /** AI 에이전트 중앙 메시지를 AR 안경에 표시 */
    fun setCentralMessage(text: String) {
        overlayView.setCentralMessage(text)
    }

    /** 객체 감지 결과를 AR 안경에 표시 */
    fun updateDetections(results: List<Detection>) {
        overlayView.detections = results
    }

    /** OCR 결과를 AR 안경에 표시 */
    fun setOcrResults(results: List<OcrResult>, width: Int, height: Int) {
        overlayView.setOcrResults(results, width, height)
    }

    /** 앵커 레이블을 AR 안경에 표시 */
    fun updateAnchorLabels(labels: List<com.xreal.nativear.spatial.AnchorLabel2D>) {
        overlayView.updateAnchorLabels(labels)
    }
}
