package com.xreal.nativear.spatial

import com.xreal.hardware.depth.StereoDepthEngine
import com.xreal.nativear.Detection
import com.xreal.nativear.OcrResult
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * SpatialAnchorManager — 3D 공간 앵커 관리자.
 *
 * ## 핵심 역할
 * 1. **앵커 생성**: 감지(YOLO/OCR) + 깊이 추정 → 3D 월드 좌표 앵커
 * 2. **앵커 재투영**: VIO 포즈 업데이트마다 월드→카메라→화면 좌표로 재투영
 * 3. **앵커 관리**: 중복 병합, 만료 정리, 관측 횟수 추적
 *
 * ## 데이터 흐름
 * ```
 * HeadPoseUpdated ──→ currentPose 갱신 → reprojectAnchors() → AnchorLabelsUpdate
 *                                                                      ↓
 * ObjectsDetected ──→ estimateDepth() → unproject → cameraToWorld ──→ anchors map
 *                                                                      ↓
 * OcrDetected ──────→ estimateDepth() → unproject → cameraToWorld ──→ anchors map
 *                                                                      ↓
 *                                                                OverlayView 렌더링
 * ```
 *
 * ## 깊이 추정 3단계 폴백
 * ```
 * 1단계: 스테레오 디스패리티 (StereoDepthEngine — SAD 또는 SGBM)
 * 2단계: bbox 크기 기반 (DepthPriors.estimateDepthFromBbox)
 * 3단계: 카테고리 기본값 (DepthPriors.getCategoryDepth)
 * ```
 *
 * @param eventBus 전역 이벤트 버스
 * @param stereoDepthEngine 스테레오 깊이 추정 엔진 (SAD/SGBM)
 * @param log 로깅 콜백
 */
class SpatialAnchorManager(
    private val eventBus: GlobalEventBus,
    private val stereoDepthEngine: StereoDepthEngine,
    private val log: (String) -> Unit,
    private val placeRecognitionManager: PlaceRecognitionManager? = null
) {
    companion object {
        /** 최대 앵커 수 (가장 오래된 것부터 제거) */
        const val MAX_ANCHORS = 100

        /** 같은 라벨 + 이 거리(m) 이내 → 기존 앵커 업데이트 (OBJECT) */
        const val MERGE_DISTANCE_M = 1.5f

        /** OCR 앵커 중복 병합 거리 (간판 텍스트는 정밀 위치 필요) */
        const val OCR_MERGE_DISTANCE_M = 0.5f

        /** 최소 감지 신뢰도 (이하 무시) */
        const val MIN_CONFIDENCE = 0.5f

        /** 재투영 스로틀 간격 (ms) — 30fps 이상에서 간격 제한 */
        const val REPROJECT_INTERVAL_MS = 33L

        /** 만료 정리 주기 (ms) */
        const val PRUNE_INTERVAL_MS = 10_000L

        /** 최소 깊이 (미터) — 이보다 가까우면 노이즈 */
        const val MIN_DEPTH = 0.3f

        /** 최대 깊이 (미터) — 이보다 멀면 무시 */
        const val MAX_DEPTH = 20.0f
    }

    // ── 활성 앵커 풀 ──
    private val anchors = ConcurrentHashMap<String, SpatialAnchor>()

    // ── 최신 VIO 포즈 캐시 ──
    @Volatile
    private var currentPose: PoseState? = null

    // ── 카메라 모델 ──
    /** RGB 감지에 사용되는 카메라 (기본: RGB, SLAM fallback 가능) */
    private var activeCameraModel: CameraModel = CameraModel.rgbCamera()

    /** SLAM rectified 카메라 (스테레오 깊이 쿼리용) */
    private val slamCamera = CameraModel.slamCamera()

    /** RGB 카메라 활성 여부 (HardwareManager에서 업데이트) */
    @Volatile
    var isRgbCameraActive: Boolean = true
        set(value) {
            field = value
            activeCameraModel = if (value) CameraModel.rgbCamera() else CameraModel.slamCamera()
            log("Camera model switched: ${activeCameraModel}")
        }

    // ── 코루틴 ──
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var eventJob: Job? = null
    private var pruneJob: Job? = null

    // ── 재투영 스로틀 ──
    private var lastReprojectTime = 0L

    // ── Public API ──

    /** EventBus 구독 시작 */
    fun start() {
        log("SpatialAnchorManager starting...")

        // PlaceRecognitionManager 고스트 앵커 콜백 등록
        placeRecognitionManager?.onGhostsRestored = { ghosts ->
            injectGhostAnchors(ghosts)
        }

        // 포즈 업데이트 구독
        eventJob = scope.launch {
            launch {
                eventBus.events.filterIsInstance<XRealEvent.PerceptionEvent.HeadPoseUpdated>()
                    .collect { onPoseUpdate(it) }
            }
            launch {
                eventBus.events.filterIsInstance<XRealEvent.PerceptionEvent.ObjectsDetected>()
                    .collect { onObjectsDetected(it.results) }
            }
            launch {
                eventBus.events.filterIsInstance<XRealEvent.PerceptionEvent.OcrDetected>()
                    .collect { onOcrDetected(it.results, it.width, it.height) }
            }
        }

        // 만료 앵커 정리 루프
        pruneJob = scope.launch {
            while (isActive) {
                delay(PRUNE_INTERVAL_MS)
                pruneExpiredAnchors()
            }
        }

        // 시그니처 저장 루프 (60초마다 확정 앵커 확인 → 시그니처 저장)
        scope.launch {
            while (isActive) {
                delay(60_000L)
                trySavePlaceSignature()
            }
        }

        log("SpatialAnchorManager started (max=$MAX_ANCHORS, mergeDistance=${MERGE_DISTANCE_M}m)")
    }

    /**
     * 고스트 앵커 주입 (PlaceRecognitionManager 매칭 성공 시 콜백).
     *
     * 고스트 앵커는 confidence=0.3, id="ghost_xxx"로 생성.
     * 실제 감지(YOLO/OCR)로 같은 라벨 + 근접 거리 확인 시 정상 앵커로 병합/승격.
     */
    fun injectGhostAnchors(ghosts: List<SpatialAnchor>) {
        var injected = 0
        for (ghost in ghosts) {
            if (anchors.size >= MAX_ANCHORS) break

            // 이미 같은 라벨의 활성 앵커가 근처에 있으면 스킵
            val existing = findNearestAnchor(
                ghost.label, ghost.worldX, ghost.worldY, ghost.worldZ,
                MERGE_DISTANCE_M * 2 // 고스트는 넓은 범위로 체크
            )
            if (existing != null) continue

            anchors[ghost.id] = ghost
            injected++
        }

        if (injected > 0) {
            log("Injected $injected ghost anchors (total: ${anchors.size})")
        }
    }

    /**
     * 확정 앵커 기반 장소 시그니처 저장 시도.
     */
    private fun trySavePlaceSignature() {
        val confirmed = anchors.values.filter { it.isConfirmed }
        if (confirmed.size >= (PlaceRecognitionManager.MIN_ANCHORS_FOR_SIGNATURE)) {
            placeRecognitionManager?.saveCurrentPlaceSignature(confirmed)
        }
    }

    /** 정지 */
    fun stop() {
        // 종료 전 시그니처 저장 시도
        trySavePlaceSignature()

        eventJob?.cancel()
        pruneJob?.cancel()
        anchors.clear()
        log("SpatialAnchorManager stopped (${anchors.size} anchors cleared)")
    }

    /** 현재 활성 앵커 목록 (스냅샷) */
    fun getActiveAnchors(): List<SpatialAnchor> = anchors.values.toList()

    /** 앵커 개수 */
    fun getAnchorCount(): Int = anchors.size

    /** 앵커 ID로 조회 */
    fun getAnchorById(id: String): SpatialAnchor? = anchors[id]

    /**
     * 프로그래밍 방식으로 앵커를 직접 추가.
     *
     * 비전(YOLO/OCR) 감지 없이 GPS 좌표나 코드에서 직접 3D 월드 좌표 앵커를 생성.
     * 러닝 랩 마커, 네비게이션 포인트, 사용자 정의 마커 등에 사용.
     *
     * 주의: worldX/Y/Z는 VIO 월드 좌표(미터)이며, GPS 좌표가 아닙니다.
     * GPS 기반 앵커는 현재 VIO 포즈를 기준으로 오프셋 계산이 필요합니다.
     *
     * @param anchor 추가할 앵커 (id, label, worldX/Y/Z 등 미리 설정)
     * @param mergeExisting true이면 같은 라벨의 근접 앵커와 병합 (기본 false)
     * @return 추가/병합 성공 여부
     */
    fun addProgrammaticAnchor(anchor: SpatialAnchor, mergeExisting: Boolean = false): Boolean {
        if (anchors.size >= MAX_ANCHORS) {
            evictOldestAnchor()
        }

        if (mergeExisting) {
            val existing = findNearestAnchor(
                anchor.label, anchor.worldX, anchor.worldY, anchor.worldZ, MERGE_DISTANCE_M
            )
            if (existing != null) {
                existing.worldX = anchor.worldX
                existing.worldY = anchor.worldY
                existing.worldZ = anchor.worldZ
                existing.lastSeenAt = System.currentTimeMillis()
                existing.seenCount++
                // Update label via metadata if provided
                return true
            }
        }

        anchors[anchor.id] = anchor

        eventBus.publish(XRealEvent.PerceptionEvent.SpatialAnchorEvent(
            anchorId = anchor.id, action = "CREATED", label = anchor.label,
            worldX = anchor.worldX, worldY = anchor.worldY, worldZ = anchor.worldZ,
            timestamp = System.currentTimeMillis()
        ))

        log("Programmatic anchor added: ${anchor.label} (${anchor.id.take(8)})")
        return true
    }

    /**
     * 앵커의 라벨을 업데이트 (프로그래밍 방식).
     * 러닝 랩에서 누적 정보 갱신 시 사용.
     */
    fun updateAnchorLabel(anchorId: String, newLabel: String): Boolean {
        val anchor = anchors[anchorId] ?: return false
        // SpatialAnchor.label is val, so we need to replace the anchor
        val updated = anchor.copy(label = newLabel, lastSeenAt = System.currentTimeMillis())
        anchors[anchorId] = updated
        return true
    }

    /**
     * 현재 VIO 포즈 상태 반환 (외부에서 GPS→VIO 좌표 변환에 활용).
     */
    fun getCurrentPose(): PoseState? = currentPose

    // ── 이벤트 핸들러 ──

    /**
     * HeadPoseUpdated → 포즈 캐시 갱신 + 앵커 재투영.
     */
    private fun onPoseUpdate(event: XRealEvent.PerceptionEvent.HeadPoseUpdated) {
        currentPose = PoseState(
            x = event.x, y = event.y, z = event.z,
            qx = event.qx, qy = event.qy, qz = event.qz, qw = event.qw,
            is6DoF = event.is6DoF,
            timestamp = System.currentTimeMillis()
        )

        // 6-DoF가 아니면 재투영 스킵 (3-DoF 회전만으로는 3D 투영 불가)
        if (!event.is6DoF) return

        // 스로틀: 33ms (30fps) 이하로 제한
        val now = System.currentTimeMillis()
        if (now - lastReprojectTime < REPROJECT_INTERVAL_MS) return
        lastReprojectTime = now

        if (anchors.isNotEmpty()) {
            reprojectAnchors()
        }
    }

    /**
     * ObjectsDetected → 깊이 추정 → 앵커 생성/업데이트.
     */
    private fun onObjectsDetected(detections: List<Detection>) {
        val pose = currentPose ?: return
        if (!pose.is6DoF) return

        for (det in detections) {
            if (det.confidence < MIN_CONFIDENCE) continue

            // Detection 좌표: 중심(x,y) + 크기(width,height) — 픽셀
            val centerU = det.x
            val centerV = det.y
            val bboxHeight = det.height

            createOrUpdateAnchor(
                label = det.label,
                type = AnchorType.OBJECT,
                u = centerU,
                v = centerV,
                bboxHeight = bboxHeight,
                confidence = det.confidence,
                metadata = emptyMap()
            )
        }
    }

    /**
     * OcrDetected → 깊이 추정 → OCR 앵커 생성.
     */
    private fun onOcrDetected(results: List<OcrResult>, width: Int, height: Int) {
        val pose = currentPose ?: return
        if (!pose.is6DoF) return

        for (result in results) {
            if (!result.isValid || result.text.length < 2) continue

            // OCR 박스 중심 (픽셀)
            val centerU = (result.box.left + result.box.right) / 2f
            val centerV = (result.box.top + result.box.bottom) / 2f
            val bboxHeight = (result.box.bottom - result.box.top).toFloat()
            val bboxAreaRatio = (bboxHeight * (result.box.right - result.box.left).toFloat()) /
                                (width.toFloat() * height.toFloat())

            createOrUpdateAnchor(
                label = result.text.take(20),  // 라벨은 20자로 제한
                type = AnchorType.OCR_TEXT,
                u = centerU,
                v = centerV,
                bboxHeight = bboxHeight,
                confidence = 0.75f,  // OCR 기본 신뢰도
                metadata = mapOf("fullText" to result.text, "bboxAreaRatio" to bboxAreaRatio)
            )
        }
    }

    // ── 핵심 로직 ──

    /**
     * 감지 → 3D 앵커 변환 파이프라인.
     *
     * 1. 깊이 추정 (stereo → bbox fallback → category fallback)
     * 2. 역투영: (u, v, depth) → 카메라 로컬 3D 좌표
     * 3. 월드 변환: 카메라 3D → 월드 3D (현재 VIO 포즈)
     * 4. 기존 앵커와 중복 확인 (라벨 + 거리)
     * 5. 신규 생성 or 기존 업데이트
     */
    private fun createOrUpdateAnchor(
        label: String,
        type: AnchorType,
        u: Float,
        v: Float,
        bboxHeight: Float,
        confidence: Float,
        metadata: Map<String, Any>
    ) {
        val pose = currentPose ?: return

        // 1. 깊이 추정 (3단계 폴백)
        val (depth, depthSource) = estimateDepth(u, v, label, bboxHeight, type, metadata)
        if (depth < MIN_DEPTH || depth > MAX_DEPTH) return

        // 2. 역투영: 이미지 (u, v) + depth → 카메라 로컬 3D
        //    RGB 감지 좌표를 사용하여 역투영 (activeCameraModel)
        val camPos = activeCameraModel.unproject(u, v, depth)

        // 3. 월드 변환: 카메라 3D → VIO 월드 3D
        val poseMatrix = PoseTransform.poseToMatrix(pose)
        val worldPos = PoseTransform.cameraToWorld(camPos, poseMatrix)
        val worldX = worldPos[0]
        val worldY = worldPos[1]
        val worldZ = worldPos[2]

        // 4. 기존 앵커와 중복 확인
        val mergeDistance = if (type == AnchorType.OCR_TEXT) OCR_MERGE_DISTANCE_M else MERGE_DISTANCE_M
        val existing = findNearestAnchor(label, worldX, worldY, worldZ, mergeDistance)

        if (existing != null) {
            // 5a. 기존 앵커 업데이트: 위치 가중 평균
            val isGhostPromotion = existing.id.startsWith("ghost_")

            if (isGhostPromotion) {
                // 고스트 승격: 실제 감지 위치로 교체 (고스트 위치는 부정확할 수 있음)
                existing.worldX = worldX
                existing.worldY = worldY
                existing.worldZ = worldZ
                existing.seenCount = 1
                existing.depthMeters = depth
                log("Ghost promoted: ${existing.label} (${existing.id.take(12)})")
            } else {
                // 일반 업데이트: 위치 가중 평균
                val weight = existing.seenCount.toFloat() / (existing.seenCount + 1f)
                val newPos = PoseTransform.weightedAverage(
                    floatArrayOf(existing.worldX, existing.worldY, existing.worldZ),
                    floatArrayOf(worldX, worldY, worldZ),
                    weight
                )
                existing.worldX = newPos[0]
                existing.worldY = newPos[1]
                existing.worldZ = newPos[2]
                existing.seenCount++
                existing.depthMeters = depth
            }
            existing.lastSeenAt = System.currentTimeMillis()

            eventBus.publish(XRealEvent.PerceptionEvent.SpatialAnchorEvent(
                anchorId = existing.id,
                action = if (isGhostPromotion) "PROMOTED" else "UPDATED",
                label = label,
                worldX = existing.worldX, worldY = existing.worldY, worldZ = existing.worldZ,
                timestamp = System.currentTimeMillis()
            ))
        } else {
            // 5b. 신규 앵커 생성
            if (anchors.size >= MAX_ANCHORS) {
                evictOldestAnchor()
            }

            val anchor = SpatialAnchor(
                id = UUID.randomUUID().toString(),
                label = label,
                type = type,
                worldX = worldX,
                worldY = worldY,
                worldZ = worldZ,
                confidence = confidence,
                createdAt = System.currentTimeMillis(),
                lastSeenAt = System.currentTimeMillis(),
                seenCount = 1,
                depthMeters = depth,
                depthSource = depthSource,
                metadata = metadata
            )
            anchors[anchor.id] = anchor

            eventBus.publish(XRealEvent.PerceptionEvent.SpatialAnchorEvent(
                anchorId = anchor.id, action = "CREATED", label = label,
                worldX = worldX, worldY = worldY, worldZ = worldZ,
                timestamp = System.currentTimeMillis()
            ))
        }
    }

    /**
     * 깊이 추정 (3단계 폴백).
     *
     * 1. 스테레오 디스패리티 (StereoDepthEngine)
     * 2. bbox 크기 기반 (DepthPriors.estimateDepthFromBbox)
     * 3. 카테고리 기본값 (DepthPriors.getCategoryDepth / getOcrDepth)
     */
    private fun estimateDepth(
        u: Float, v: Float,
        label: String, bboxHeight: Float,
        type: AnchorType,
        metadata: Map<String, Any>
    ): Pair<Float, DepthSource> {
        // 1단계: 스테레오 디스패리티
        //   RGB 좌표를 SLAM 좌표로 변환 후 쿼리
        val (slamU, slamV) = if (isRgbCameraActive) {
            CameraModel.rgbToSlamPixel(u, v)
        } else {
            Pair(u, v)
        }

        val stereoDepth = stereoDepthEngine.queryDepthAt(slamU.toInt(), slamV.toInt())
        if (stereoDepth != null && stereoDepth in MIN_DEPTH..MAX_DEPTH) {
            return Pair(stereoDepth, DepthSource.STEREO_DISPARITY)
        }

        // 2단계: bbox 크기 기반 (OBJECT에만 적용)
        if (type == AnchorType.OBJECT) {
            val focalLength = activeCameraModel.fy  // 이미지 좌표계 기준 focal length
            val bboxDepth = DepthPriors.estimateDepthFromBbox(label, bboxHeight, focalLength)
            if (bboxDepth != null) {
                return Pair(bboxDepth, DepthSource.BBOX_SIZE)
            }
        }

        // 3단계: 카테고리/OCR 기본값
        val fallbackDepth = if (type == AnchorType.OCR_TEXT) {
            val textLength = (metadata["fullText"] as? String)?.length ?: label.length
            val bboxAreaRatio = (metadata["bboxAreaRatio"] as? Float) ?: 0.02f
            DepthPriors.getOcrDepth(textLength, bboxAreaRatio)
        } else {
            DepthPriors.getCategoryDepth(label)
        }

        return Pair(fallbackDepth, DepthSource.CATEGORY_PRIOR)
    }

    /**
     * 앵커 재투영 → HUD AnchorLabelsUpdate 발행.
     *
     * 매 HeadPoseUpdated (~30Hz):
     * 1. 각 앵커: 월드 3D → 카메라 로컬 3D
     * 2. 카메라 3D → 이미지 2D
     * 3. 이미지 2D → 화면 퍼센트 좌표
     * 4. AnchorLabelsUpdate 이벤트 발행
     */
    private fun reprojectAnchors() {
        val pose = currentPose ?: return
        val poseMatrix = PoseTransform.poseToMatrix(pose)

        val visibleLabels = mutableListOf<AnchorLabel2D>()

        for (anchor in anchors.values) {
            val worldPos = floatArrayOf(anchor.worldX, anchor.worldY, anchor.worldZ)

            // 월드 → 카메라 로컬
            val camPos = PoseTransform.worldToCamera(worldPos, poseMatrix)

            // 카메라 뒤에 있으면 스킵
            if (camPos[2] <= 0f) continue

            // 카메라 로컬 → 이미지 2D (활성 카메라 모델 사용)
            val uv = activeCameraModel.project(camPos[0], camPos[1], camPos[2]) ?: continue

            // 화면 범위 체크 (10% 여유)
            if (!activeCameraModel.isVisible(camPos[0], camPos[1], camPos[2], margin = 0.1f)) continue

            // 이미지 2D → 화면 퍼센트 (0-100)
            val (xPct, yPct) = activeCameraModel.pixelToPercent(uv.first, uv.second)

            // 사용자로부터 거리
            val distance = activeCameraModel.distanceFromCamera(camPos[0], camPos[1], camPos[2])

            visibleLabels.add(AnchorLabel2D(
                anchorId = anchor.id,
                label = anchor.label,
                screenXPercent = xPct.coerceIn(0f, 100f),
                screenYPercent = yPct.coerceIn(0f, 100f),
                distanceMeters = distance,
                confidence = anchor.confidence,
                type = anchor.type,
                isGhost = anchor.id.startsWith("ghost_")
            ))
        }

        // 이벤트 발행 (빈 리스트도 발행 → 이전 라벨 제거)
        eventBus.publish(XRealEvent.ActionRequest.AnchorLabelsUpdate(visibleLabels))
    }

    /**
     * 가장 가까운 동일 라벨 앵커 찾기 (중복 병합용).
     */
    private fun findNearestAnchor(
        label: String,
        worldX: Float, worldY: Float, worldZ: Float,
        maxDistance: Float
    ): SpatialAnchor? {
        var nearest: SpatialAnchor? = null
        var minDist = maxDistance

        for (anchor in anchors.values) {
            if (anchor.label != label) continue

            val dist = PoseTransform.distance3D(
                floatArrayOf(anchor.worldX, anchor.worldY, anchor.worldZ),
                floatArrayOf(worldX, worldY, worldZ)
            )
            if (dist < minDist) {
                minDist = dist
                nearest = anchor
            }
        }
        return nearest
    }

    /**
     * 만료 앵커 정리 (expirySeconds 초과 미관측).
     */
    private fun pruneExpiredAnchors() {
        val now = System.currentTimeMillis()
        val expired = anchors.values.filter { anchor ->
            (now - anchor.lastSeenAt) > anchor.expirySeconds * 1000
        }

        for (anchor in expired) {
            anchors.remove(anchor.id)
            eventBus.publish(XRealEvent.PerceptionEvent.SpatialAnchorEvent(
                anchorId = anchor.id, action = "REMOVED", label = anchor.label,
                worldX = anchor.worldX, worldY = anchor.worldY, worldZ = anchor.worldZ,
                timestamp = now
            ))
        }

        if (expired.isNotEmpty()) {
            log("Pruned ${expired.size} expired anchors (remaining: ${anchors.size})")
        }
    }

    /**
     * 가장 오래된 앵커 제거 (MAX_ANCHORS 초과 시).
     */
    private fun evictOldestAnchor() {
        val oldest = anchors.values.minByOrNull { it.lastSeenAt } ?: return
        anchors.remove(oldest.id)
        log("Evicted oldest anchor: ${oldest.label} (${oldest.id.take(8)})")
    }
}
