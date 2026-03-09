package com.xreal.nativear.core

import android.util.Log
import com.xreal.nativear.AIAgentManager
import com.xreal.nativear.VisionManager
import com.xreal.nativear.companion.AICallAction
import com.xreal.nativear.companion.NoveltyEngine
import com.xreal.nativear.companion.SituationLifecycleManager
import com.xreal.nativear.companion.TokenOptimizer
import com.xreal.nativear.context.LifeSituation
import com.xreal.nativear.context.SituationRecognizer
import com.xreal.nativear.focus.AITrigger
import com.xreal.nativear.focus.FocusModeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * VisionCoordinator: Manages the flow of vision perception events.
 *
 * Architecture Principle 2: "Detect Before Call"
 * - Before dispatching AI analysis, checks scene change via TokenOptimizer
 * - SKIP: scene unchanged + cache valid → no AI call, no memory write
 * - MINIMAL: minor change → only process changed objects
 * - STANDARD: new scene → full processing
 * - ENRICHED: routine + novelty timing → deep analysis
 *
 * This gate prevents redundant "I see a flower pot" × 47 writes.
 *
 * ## FocusMode 게이트 (적응형 회복력 시스템)
 * - PRIVATE 모드: 능동적 비전 분석 중단 (canAIAct(PROACTIVE_VISION) = false)
 * - DND 모드: 능동적 비전 분석 억제
 *
 * ## Phase F-5: 숙련도 사다리 연결
 * - LOCAL_ML 처리링: TokenOptimizer SKIP (RoutineClassifier 위임)
 * - WARMUP_CACHE 처리링: isRoutine=true → TokenOptimizer ENRICHED 경로 (캐시 콘텐츠 주입)
 * - NoveltyEngine.shouldInjectNovelty() → isNoveltyTime 실주입 (기존 TODO 해결)
 */
class VisionCoordinator(
    private val eventBus: GlobalEventBus,
    private val visionManager: VisionManager,
    private val aiAgentManager: AIAgentManager,
    private val tokenOptimizer: TokenOptimizer? = null,
    private val focusModeManager: FocusModeManager? = null,
    // ★ Phase F-5: 숙련도 사다리 + 노블티 연결
    private val situationLifecycleManager: SituationLifecycleManager? = null,
    private val noveltyEngine: NoveltyEngine? = null,
    private val situationRecognizer: SituationRecognizer? = null,
    private val locationManager: com.xreal.nativear.LocationManager? = null
) {
    private val TAG = "VisionCoordinator"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Gate statistics
    private var totalObjectEvents = 0
    private var skippedEvents = 0
    private var minimalEvents = 0

    interface VisionListener {
        fun onStatusUpdate(status: String)
        fun onDetections(results: List<com.xreal.nativear.Detection>)
        fun onPoseDetected(results: List<com.xreal.nativear.PoseKeypoint>)
        fun onOcrDetected(results: List<com.xreal.nativear.OcrResult>, width: Int, height: Int)
    }

    private var listener: VisionListener? = null

    fun setListener(listener: VisionListener) {
        this.listener = listener
    }

    init {
        subscribeToEvents()
        Log.i(TAG, "VisionCoordinator initialized (TokenOptimizer: ${if (tokenOptimizer != null) "ON" else "OFF"}, FocusMode: ${if (focusModeManager != null) "ON" else "OFF"})")
    }

    private fun subscribeToEvents() {
        scope.launch {
            // ★ 회복력: collect 루프를 try-catch로 보호.
            // try-catch 없으면 단 한 번의 예외로 collect가 종료되어
            // 물체 감지/OCR/장면 인식 파이프라인이 영구 정지됨 ("AR 눈 멀음").
            // CancellationException은 반드시 재발행 (scope 취소 신호 방해 금지).
            eventBus.events.collect { event ->
                try {
                    when (event) {
                        is XRealEvent.PerceptionEvent.OcrDetected -> {
                            listener?.onStatusUpdate("OCR: ${event.results.size} blocks")
                            listener?.onOcrDetected(event.results, event.width, event.height)
                        }
                        is XRealEvent.PerceptionEvent.ObjectsDetected -> {
                            handleObjectsDetected(event)
                        }
                        is XRealEvent.PerceptionEvent.SceneCaptured -> {
                            // SceneCaptured is user-triggered — check USER_COMMAND gate
                            if (focusModeManager == null || focusModeManager.canAIAct(AITrigger.USER_COMMAND)) {
                                aiAgentManager.interpretScene(event.bitmap, event.ocrText)
                            } else {
                                Log.d(TAG, "SceneCaptured 억제됨 (FocusMode: ${focusModeManager.currentMode})")
                            }
                        }
                        is XRealEvent.PerceptionEvent.PoseDetected -> {
                            listener?.onPoseDetected(event.keypoints)
                        }
                        is XRealEvent.ActionRequest.TriggerSnapshot -> {
                            // FocusMode PRIVATE/DND 시 자동 스냅샷 억제
                            if (focusModeManager == null || focusModeManager.canAIAct(AITrigger.PROACTIVE_VISION)) {
                                Log.i(TAG, "TriggerSnapshot received — capturing scene")
                                visionManager.captureSceneSnapshot()
                            } else {
                                Log.d(TAG, "TriggerSnapshot 억제됨 (FocusMode: ${focusModeManager.currentMode})")
                            }
                        }
                        else -> {}
                    }
                } catch (e: CancellationException) {
                    throw e  // scope 취소 신호는 반드시 재발행
                } catch (e: Exception) {
                    // 이벤트 처리 실패 — 루프는 계속 유지 (비전 파이프라인 정지 방지)
                    Log.e(TAG, "비전 이벤트 처리 오류 (루프 유지됨): ${event::class.simpleName} — ${e.message}", e)
                    eventBus.publish(XRealEvent.SystemEvent.Error(
                        code = "VISION_COORDINATOR_ERROR",
                        message = "${event::class.simpleName} 처리 실패: ${e.message?.take(100)}",
                        throwable = e
                    ))
                }
            }
        }
    }

    /**
     * Gate ObjectsDetected through TokenOptimizer before dispatching to AIAgentManager.
     *
     * Flow:
     * ObjectsDetected → extract labels → TokenOptimizer.shouldCallAI()
     *   → SKIP: scene unchanged, don't write to memory (save tokens + DB)
     *   → MINIMAL: minor change, only process changed objects
     *   → STANDARD/ENRICHED: full processing
     */
    private fun handleObjectsDetected(event: XRealEvent.PerceptionEvent.ObjectsDetected) {
        listener?.onDetections(event.results)
        totalObjectEvents++

        // FocusMode 게이트: PRIVATE/DND 시 능동적 비전 분석 억제
        if (focusModeManager != null && !focusModeManager.canAIAct(AITrigger.PROACTIVE_VISION)) {
            skippedEvents++
            if (skippedEvents % 30 == 0) {
                Log.d(TAG, "ObjectsDetected 억제됨 (FocusMode: ${focusModeManager.currentMode}) — $skippedEvents/$totalObjectEvents")
            }
            return
        }

        if (tokenOptimizer == null) {
            // ★ P0-1: null이면 무조건 AI 호출 대신 SKIP — 토큰 예산 수분 내 소진 방지
            skippedEvents++
            if (skippedEvents % 30 == 0) {
                Log.w(TAG, "ObjectsDetected SKIPPED (tokenOptimizer null) — $skippedEvents/$totalObjectEvents")
            }
            return
        }

        // Extract labels from detections (confidence > 0.50 for scene hash)
        val labels = event.results
            .filter { it.confidence > 0.50f }
            .map { it.label }
            .toSet()

        if (labels.isEmpty()) return

        // ★ Phase F-5: 숙련도 사다리 → 처리링 결정
        val currentSituation = situationRecognizer?.currentSituation?.value ?: LifeSituation.UNKNOWN
        val ring = situationLifecycleManager?.getProcessingRing(currentSituation)
            ?: SituationLifecycleManager.ProcessingRing.API_SINGLE

        // LOCAL_ML 처리링: 온디바이스 RoutineClassifier 위임, VisionCoordinator 비전 분석 SKIP
        if (ring == SituationLifecycleManager.ProcessingRing.LOCAL_ML) {
            skippedEvents++
            if (skippedEvents % 20 == 0) {
                Log.d(TAG, "ObjectsDetected LOCAL_ML SKIP: ${currentSituation.displayName} → RoutineClassifier 처리")
            }
            return
        }

        // WARMUP_CACHE = 패턴 확립됨 (isRoutine=true), isNoveltyTime은 NoveltyEngine에서 실주입
        val isRoutine = ring == SituationLifecycleManager.ProcessingRing.WARMUP_CACHE
        val isNoveltyTime = noveltyEngine?.shouldInjectNovelty(currentSituation) ?: false

        // TokenOptimizer decides: SKIP / MINIMAL / STANDARD / ENRICHED
        val decision = tokenOptimizer.shouldCallAI(
            currentLabels = labels,
            lat = locationManager?.getCurrentLocation()?.latitude,
            lon = locationManager?.getCurrentLocation()?.longitude,
            isRoutine = isRoutine,
            isNoveltyTime = isNoveltyTime,
            situation = currentSituation.name
        )

        when (decision.action) {
            AICallAction.SKIP -> {
                skippedEvents++
                // Scene unchanged — don't write redundant "I see a X" to memory
                if (skippedEvents % 10 == 0) {
                    Log.d(TAG, "ObjectsDetected SKIPPED (total: $skippedEvents/$totalObjectEvents, " +
                            "rate: ${String.format("%.0f", skippedEvents.toFloat() / totalObjectEvents * 100)}%)")
                }
            }
            AICallAction.MINIMAL -> {
                minimalEvents++
                // Minor change — only process the new/changed objects
                val previousLabels = decision.cachedResult?.split(",")?.toSet() ?: emptySet()
                val changedDetections = event.results.filter { det ->
                    det.label in (labels - previousLabels)
                }
                if (changedDetections.isNotEmpty()) {
                    aiAgentManager.processDetections(changedDetections)
                }
                Log.d(TAG, "ObjectsDetected MINIMAL: ${changedDetections.size} changed objects")
            }
            AICallAction.STANDARD, AICallAction.ENRICHED -> {
                // Full processing
                aiAgentManager.processDetections(event.results)
                Log.d(TAG, "ObjectsDetected ${decision.action}: ${decision.reason}")
            }
        }
    }

    // ─── Statistics ───

    fun getGateStats(): String {
        val skipRate = if (totalObjectEvents > 0)
            skippedEvents.toFloat() / totalObjectEvents * 100 else 0f
        val optimizerStats = tokenOptimizer?.getOptimizationStats()
        return buildString {
            appendLine("VisionCoordinator Gate:")
            appendLine("  Total events: $totalObjectEvents")
            appendLine("  Skipped: $skippedEvents (${String.format("%.1f", skipRate)}%)")
            appendLine("  Minimal: $minimalEvents")
            if (optimizerStats != null) {
                appendLine("  Est. tokens saved: ${optimizerStats.estimatedTokensSaved}")
            }
        }
    }
}
