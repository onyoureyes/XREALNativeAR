package com.xreal.nativear.spatial

import com.xreal.nativear.core.XRealLogger
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * SpatialUIManager — 3D 공간 UI 관리자.
 *
 * ## 핵심 기능
 *
 * ### 1. 위치 안정화 (Jitter Filter)
 * 프레임 간 앵커 라벨 위치의 미세 떨림을 제거하는 EMA(지수이동평균) 필터.
 * - 정적 앵커: α=0.3 (강한 스무딩)
 * - 이동 중인 앵커: α=0.7 (빠른 응답)
 * - 새 앵커 최초 출현: 필터 없이 즉시 표시
 *
 * ### 2. 깊이 기반 렌더링 (Depth-Sorted)
 * - 먼 앵커 → 작고 투명 / 가까운 앵커 → 크고 불투명
 * - Z-정렬: 먼 것 먼저 그리기 (painter's algorithm)
 * - 근접 오클루전: 가까운 라벨이 먼 라벨과 겹치면 먼 라벨 추가 투명
 *
 * ### 3. 시선 포커스 시스템 (Gaze Focus)
 * - 화면 중앙(50%, 50%)에서 가장 가까운 앵커 = "포커스 앵커"
 * - 포커스 앵커: 강조 표시 (밝은 테두리, 확대 텍스트)
 * - FocusedAnchorChanged 이벤트 발행 → AI가 해당 앵커 컨텍스트 제공
 * - 시선 체류 시간 추적: 2초 이상 주시 → DeepFocus 이벤트
 *
 * ### 4. 월드 앵커 콘텐츠 패널
 * - AI가 특정 앵커에 대해 정보를 제공할 때, 해당 앵커 옆에 부착
 * - 패널은 앵커와 함께 3D 이동 (OverlayView에서 렌더링)
 * - 자동 만료 (10초) 또는 시선 이탈 시 페이드아웃
 */
class SpatialUIManager(
    private val eventBus: GlobalEventBus
) {
    companion object {
        private const val TAG = "SpatialUIManager"

        // ── 안정화 파라미터 ──
        private const val EMA_ALPHA_STATIC = 0.3f    // 정적 앵커 스무딩 강도
        private const val EMA_ALPHA_MOVING = 0.7f    // 이동 앵커 스무딩 강도
        private const val MOVING_THRESHOLD_PCT = 5.0f // 5% 이상 이동 = "이동 중"

        // ── 시선 포커스 파라미터 ──
        private const val FOCUS_RADIUS_PCT = 15.0f    // 화면 중앙 15% 이내 = 포커스 후보
        private const val DEEP_FOCUS_DWELL_MS = 2000L // 2초 주시 → DeepFocus
        private const val FOCUS_HYSTERESIS_PCT = 5.0f  // 포커스 전환 히스테리시스 (떨림 방지)

        // ── 오클루전 파라미터 ──
        private const val OCCLUSION_OVERLAP_PCT = 8.0f  // 8% 겹침 → 오클루전 적용
        private const val OCCLUSION_ALPHA_FACTOR = 0.4f  // 가려진 라벨 투명도 배율

        // ── 콘텐츠 패널 ──
        private const val PANEL_EXPIRY_MS = 10_000L   // 10초 자동 만료
        private const val PANEL_MAX_ACTIVE = 3         // 최대 동시 패널 수
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── 위치 스무딩 상태 ──
    data class SmoothedState(
        var x: Float,
        var y: Float,
        var distance: Float,
        var lastRawX: Float,
        var lastRawY: Float,
        var frameCount: Int = 0,
        var lastUpdateTime: Long = System.currentTimeMillis()
    )
    private val smoothedPositions = ConcurrentHashMap<String, SmoothedState>()

    // ── 시선 포커스 ──
    private var focusedAnchorId: String? = null
    private var focusDwellStart = 0L
    private var deepFocusTriggered = false

    // ── 월드 앵커 콘텐츠 패널 ──
    data class AnchorContentPanel(
        val anchorId: String,
        val title: String,
        val content: String,
        val createdAt: Long = System.currentTimeMillis(),
        val expiryMs: Long = PANEL_EXPIRY_MS,
        var alpha: Float = 0f,  // 0→1 페이드인
        val color: Int = 0xFF00CCFF.toInt()  // 기본 청록
    ) {
        val isExpired: Boolean get() = System.currentTimeMillis() - createdAt > expiryMs
        val age: Long get() = System.currentTimeMillis() - createdAt
    }
    private val contentPanels = ConcurrentHashMap<String, AnchorContentPanel>()

    // ── 처리된 라벨 캐시 ──
    @Volatile
    var processedLabels: List<EnhancedAnchorLabel> = emptyList()
        private set

    // ══════════════════════════════════════════════
    //  Enhanced Label (렌더링용 확장 라벨)
    // ══════════════════════════════════════════════

    data class EnhancedAnchorLabel(
        val original: AnchorLabel2D,
        val smoothedX: Float,          // EMA 스무딩된 X (0-100)
        val smoothedY: Float,          // EMA 스무딩된 Y (0-100)
        val renderAlpha: Float,        // 최종 투명도 (0-1, 깊이+신뢰도+오클루전 종합)
        val renderScale: Float,        // 텍스트 크기 배율 (깊이 + 포커스)
        val isFocused: Boolean,        // 시선 포커스 여부
        val isDeepFocused: Boolean,    // 2초 이상 주시
        val isOccluded: Boolean,       // 오클루전 상태
        val depthOrder: Int,           // Z-정렬 순서 (0 = 가장 먼 것)
        val contentPanel: AnchorContentPanel? = null  // 부착된 콘텐츠 패널
    )

    // ══════════════════════════════════════════════
    //  Public API
    // ══════════════════════════════════════════════

    fun start() {
        scope.launch {
            eventBus.events.collect { event ->
                try {
                    when (event) {
                        is XRealEvent.ActionRequest.AnchorLabelsUpdate -> {
                            processLabels(event.visibleLabels)
                        }
                        else -> {}
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    XRealLogger.impl.e(TAG, "이벤트 처리 오류 (루프 유지됨): ${event::class.simpleName} — ${e.message}", e)
                }
            }
        }
        XRealLogger.impl.i(TAG, "SpatialUIManager started — stabilization + gaze focus + depth rendering active")
    }

    fun stop() {
        scope.cancel()
        smoothedPositions.clear()
        contentPanels.clear()
        XRealLogger.impl.i(TAG, "SpatialUIManager stopped")
    }

    /**
     * 특정 앵커에 콘텐츠 패널 부착.
     * AI가 앵커 관련 정보를 생성했을 때 호출.
     */
    fun attachContentPanel(anchorId: String, title: String, content: String, color: Int = 0xFF00CCFF.toInt()) {
        // 최대 패널 수 제한
        if (contentPanels.size >= PANEL_MAX_ACTIVE) {
            val oldest = contentPanels.values.minByOrNull { it.createdAt }
            if (oldest != null) contentPanels.remove(oldest.anchorId)
        }

        contentPanels[anchorId] = AnchorContentPanel(
            anchorId = anchorId,
            title = title,
            content = content,
            color = color
        )
        XRealLogger.impl.d(TAG, "Content panel attached to anchor $anchorId: $title")
    }

    /**
     * 콘텐츠 패널 제거.
     */
    fun removeContentPanel(anchorId: String) {
        contentPanels.remove(anchorId)
    }

    /**
     * 현재 포커스된 앵커 ID.
     */
    fun getFocusedAnchorId(): String? = focusedAnchorId

    // ══════════════════════════════════════════════
    //  Core Processing Pipeline
    // ══════════════════════════════════════════════

    private fun processLabels(rawLabels: List<AnchorLabel2D>) {
        val now = System.currentTimeMillis()

        // 만료된 콘텐츠 패널 제거
        contentPanels.entries.removeIf { it.value.isExpired }

        // Step 1: 깊이 정렬 (먼 것 → 가까운 것)
        val sorted = rawLabels.sortedByDescending { it.distanceMeters }

        // Step 2: EMA 스무딩
        val smoothed = sorted.map { label ->
            applySmoothing(label, now)
        }

        // Step 3: 시선 포커스 계산
        val focusId = computeGazeFocus(smoothed, now)

        // Step 4: 오클루전 감지
        val occludedSet = computeOcclusion(smoothed)

        // Step 5: EnhancedAnchorLabel 생성
        val enhanced = smoothed.mapIndexed { index, (label, sx, sy) ->
            val isFocused = label.anchorId == focusId
            val isDeepFocused = isFocused && deepFocusTriggered

            // 깊이 기반 투명도: 가까울수록 높음 (1m=1.0, 15m+=0.3)
            val depthAlpha = (1.0f - (label.distanceMeters - 1f) / 20f).coerceIn(0.3f, 1.0f)
            val baseAlpha = if (label.isGhost) 0.4f else label.confidence
            val occlusionMul = if (label.anchorId in occludedSet) OCCLUSION_ALPHA_FACTOR else 1.0f
            val focusMul = if (isFocused) 1.0f else 0.85f
            val renderAlpha = (baseAlpha * depthAlpha * occlusionMul * focusMul).coerceIn(0.15f, 1.0f)

            // 포커스 앵커 확대
            val renderScale = when {
                isDeepFocused -> 1.4f
                isFocused -> 1.2f
                else -> 1.0f
            }

            // 콘텐츠 패널 연결
            val panel = contentPanels[label.anchorId]
            // 패널 페이드인 애니메이션
            if (panel != null && panel.alpha < 1.0f) {
                panel.alpha = (panel.alpha + 0.05f).coerceAtMost(1.0f)
            }

            EnhancedAnchorLabel(
                original = label,
                smoothedX = sx,
                smoothedY = sy,
                renderAlpha = renderAlpha,
                renderScale = renderScale,
                isFocused = isFocused,
                isDeepFocused = isDeepFocused,
                isOccluded = label.anchorId in occludedSet,
                depthOrder = index,
                contentPanel = panel
            )
        }

        processedLabels = enhanced

        // 사라진 앵커의 스무딩 상태 정리
        val activeIds = rawLabels.map { it.anchorId }.toSet()
        smoothedPositions.keys.removeIf { it !in activeIds }
    }

    // ══════════════════════════════════════════════
    //  EMA Position Smoothing
    // ══════════════════════════════════════════════

    /**
     * 지수이동평균(EMA) 위치 스무딩.
     *
     * @return (원본 라벨, 스무딩된 X, 스무딩된 Y)
     */
    private fun applySmoothing(label: AnchorLabel2D, now: Long): Triple<AnchorLabel2D, Float, Float> {
        val existing = smoothedPositions[label.anchorId]

        if (existing == null) {
            // 첫 출현 — 즉시 표시
            smoothedPositions[label.anchorId] = SmoothedState(
                x = label.screenXPercent,
                y = label.screenYPercent,
                distance = label.distanceMeters,
                lastRawX = label.screenXPercent,
                lastRawY = label.screenYPercent
            )
            return Triple(label, label.screenXPercent, label.screenYPercent)
        }

        // 이동 거리 판단
        val dx = Math.abs(label.screenXPercent - existing.lastRawX)
        val dy = Math.abs(label.screenYPercent - existing.lastRawY)
        val movement = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

        // 이동 속도에 따라 스무딩 강도 조절
        val alpha = if (movement > MOVING_THRESHOLD_PCT) EMA_ALPHA_MOVING else EMA_ALPHA_STATIC

        // EMA 적용
        existing.x = existing.x + alpha * (label.screenXPercent - existing.x)
        existing.y = existing.y + alpha * (label.screenYPercent - existing.y)
        existing.distance = existing.distance + alpha * (label.distanceMeters - existing.distance)
        existing.lastRawX = label.screenXPercent
        existing.lastRawY = label.screenYPercent
        existing.frameCount++
        existing.lastUpdateTime = now

        return Triple(label, existing.x, existing.y)
    }

    // ══════════════════════════════════════════════
    //  Gaze Focus Detection
    // ══════════════════════════════════════════════

    /**
     * 화면 중앙에서 가장 가까운 앵커를 포커스 앵커로 선택.
     * 히스테리시스: 현재 포커스 앵커가 살짝 벗어나도 즉시 전환하지 않음.
     */
    private fun computeGazeFocus(
        labels: List<Triple<AnchorLabel2D, Float, Float>>,
        now: Long
    ): String? {
        if (labels.isEmpty()) {
            if (focusedAnchorId != null) {
                focusedAnchorId = null
                focusDwellStart = 0L
                deepFocusTriggered = false
            }
            return null
        }

        // 화면 중앙 (50, 50)으로부터 거리 계산
        val centerX = 50f
        val centerY = 50f

        data class Candidate(val id: String, val dist: Float)
        val candidates = labels.mapNotNull { (label, sx, sy) ->
            val dist = Math.sqrt(((sx - centerX) * (sx - centerX) + (sy - centerY) * (sy - centerY)).toDouble()).toFloat()
            if (dist <= FOCUS_RADIUS_PCT) Candidate(label.anchorId, dist)
            else null
        }.sortedBy { it.dist }

        val nearest = candidates.firstOrNull()

        // 히스테리시스: 현재 포커스가 여전히 후보에 있고, 새 후보와 차이가 작으면 유지
        val currentFocused = focusedAnchorId
        if (currentFocused != null && nearest != null) {
            val currentCandidate = candidates.find { it.id == currentFocused }
            if (currentCandidate != null && currentCandidate.dist <= FOCUS_RADIUS_PCT + FOCUS_HYSTERESIS_PCT) {
                // 현재 포커스 유지
                updateDwellTime(currentFocused, now)
                return currentFocused
            }
        }

        // 새 포커스
        val newFocusId = nearest?.id
        if (newFocusId != focusedAnchorId) {
            val prevId = focusedAnchorId
            focusedAnchorId = newFocusId
            focusDwellStart = now
            deepFocusTriggered = false

            // 포커스 변경 이벤트 발행
            scope.launch {
                eventBus.publish(XRealEvent.PerceptionEvent.FocusedAnchorChanged(
                    anchorId = newFocusId,
                    previousAnchorId = prevId,
                    timestamp = now
                ))
            }

            if (newFocusId != null) {
                XRealLogger.impl.d(TAG, "Gaze focus changed → $newFocusId")
            }
        } else if (newFocusId != null) {
            updateDwellTime(newFocusId, now)
        }

        return newFocusId
    }

    private fun updateDwellTime(anchorId: String, now: Long) {
        if (focusDwellStart > 0 && !deepFocusTriggered) {
            val dwellTime = now - focusDwellStart
            if (dwellTime >= DEEP_FOCUS_DWELL_MS) {
                deepFocusTriggered = true
                scope.launch {
                    eventBus.publish(XRealEvent.PerceptionEvent.DeepFocusTriggered(
                        anchorId = anchorId,
                        dwellTimeMs = dwellTime,
                        timestamp = now
                    ))
                }
                XRealLogger.impl.i(TAG, "Deep focus triggered on anchor $anchorId (${dwellTime}ms)")
            }
        }
    }

    // ══════════════════════════════════════════════
    //  Occlusion Detection
    // ══════════════════════════════════════════════

    /**
     * 간단한 2D 바운딩 박스 오클루전.
     * 가까운 라벨이 먼 라벨과 겹치면 먼 라벨을 "가려짐" 처리.
     */
    private fun computeOcclusion(
        labels: List<Triple<AnchorLabel2D, Float, Float>>
    ): Set<String> {
        val occluded = mutableSetOf<String>()

        // labels는 이미 거리 내림차순 (먼 것 먼저)
        for (i in labels.indices) {
            for (j in i + 1 until labels.size) {
                val (farLabel, farX, farY) = labels[i]
                val (nearLabel, nearX, nearY) = labels[j]

                // 가까운 것이 먼 것과 겹치는지 확인
                val dx = Math.abs(farX - nearX)
                val dy = Math.abs(farY - nearY)

                if (dx < OCCLUSION_OVERLAP_PCT && dy < OCCLUSION_OVERLAP_PCT) {
                    // 먼 것을 가려짐 처리
                    occluded.add(farLabel.anchorId)
                }
            }
        }

        return occluded
    }
}
