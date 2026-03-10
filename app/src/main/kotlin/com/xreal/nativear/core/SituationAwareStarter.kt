package com.xreal.nativear.core

import android.util.Log
import com.xreal.nativear.context.LifeSituation
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * SituationAwareStarter — 이벤트 기반 서비스 지연 시작.
 *
 * AppBootstrapper에서 ~40개 서비스의 start()를 부팅 시점에서 제거하고,
 * 해당 서비스가 실제로 필요한 이벤트가 발생할 때 lazy start.
 *
 * ## 트리거 매핑
 * - SituationChanged(RUNNING) → RunningCoachManager
 * - SituationChanged(MEETING) → MeetingContextService, ReminderScheduler
 * - PersonIdentified → RelationshipTracker
 * - HandsDetected → HandInteractionManager, SpatialUIManager
 * - 부팅 5분 후 → 학습/백업 서비스
 *
 * ## 안전성
 * - 각 서비스는 최대 1회만 start() (ConcurrentHashSet 보호)
 * - start() 실패 시 다음 이벤트에서 재시도
 * - replay=0 EventBus이므로 start() 전 이벤트는 유실 (허용)
 */
class SituationAwareStarter(
    private val eventBus: GlobalEventBus,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "SituationAwareStarter"
        private const val DEFERRED_DELAY_MS = 300_000L  // 5분 후 학습/백업 서비스 시작
    }

    private val startedServices: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var collectJob: Job? = null
    private var deferredJob: Job? = null

    fun start() {
        Log.i(TAG, "SituationAwareStarter 시작 (이벤트 기반 서비스 지연 로딩)")

        // 이벤트 구독
        collectJob = scope.launch(Dispatchers.Default) {
            eventBus.events.collect { event ->
                try {
                    when (event) {
                        is XRealEvent.SystemEvent.SituationChanged ->
                            onSituationChanged(event.newSituation)
                        is XRealEvent.PerceptionEvent.PersonIdentified ->
                            onPersonIdentified()
                        is XRealEvent.PerceptionEvent.HandsDetected ->
                            onHandsDetected()
                        else -> { /* 무시 */ }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "이벤트 처리 오류 (루프 유지됨): ${e.message}", e)
                }
            }
        }

        // 5분 후 지연 서비스 일괄 시작
        deferredJob = scope.launch(Dispatchers.Default) {
            delay(DEFERRED_DELAY_MS)
            startDeferredServices()
        }
    }

    fun stop() {
        collectJob?.cancel()
        deferredJob?.cancel()
        Log.i(TAG, "SituationAwareStarter 정지 (${startedServices.size}개 서비스 시작됨)")
    }

    // ─── 트리거 핸들러 ───

    private fun onSituationChanged(situation: LifeSituation) {
        when (situation) {
            LifeSituation.RUNNING -> {
                // RunningCoachManager는 init에서 EventBus 구독, startRun()으로 활성화 — lazy start 불필요
            }
            LifeSituation.IN_MEETING -> {
                lazyStart("MeetingContextService") {
                    koin().getOrNull<com.xreal.nativear.meeting.MeetingContextService>()?.start()
                }
                lazyStart("ReminderScheduler") {
                    koin().getOrNull<com.xreal.nativear.meeting.ReminderScheduler>()?.schedule()
                }
            }
            LifeSituation.TEACHING -> {
                // 수업 시 회의 서비스도 유용
                lazyStart("MeetingContextService") {
                    koin().getOrNull<com.xreal.nativear.meeting.MeetingContextService>()?.start()
                }
            }
            LifeSituation.SOCIAL_GATHERING -> {
                lazyStart("RelationshipTracker") {
                    koin().getOrNull<com.xreal.nativear.companion.RelationshipTracker>()?.start()
                }
            }
            else -> { /* 다른 상황에는 추가 서비스 불필요 */ }
        }

        // 첫 상황 인식 시 전문가 팀 + HUD 시작
        lazyStart("ExpertTeamManager") {
            koin().getOrNull<com.xreal.nativear.expert.ExpertTeamManager>()?.start()
        }
        lazyStart("HUDModeManager") {
            koin().getOrNull<com.xreal.nativear.hud.HUDModeManager>()?.start()
        }
    }

    private fun onPersonIdentified() {
        lazyStart("RelationshipTracker") {
            koin().getOrNull<com.xreal.nativear.companion.RelationshipTracker>()?.start()
        }
        lazyStart("FamiliarityEngine") {
            koin().getOrNull<com.xreal.nativear.companion.FamiliarityEngine>()?.start()
        }
    }

    private fun onHandsDetected() {
        lazyStart("HandInteractionManager") {
            koin().getOrNull<com.xreal.nativear.interaction.HandInteractionManager>()?.start()
        }
        lazyStart("SpatialUIManager") {
            koin().getOrNull<com.xreal.nativear.spatial.SpatialUIManager>()?.start()
        }
    }

    /**
     * 5분 지연 서비스 — 부팅 즉시 필요하지 않은 것들.
     */
    private fun startDeferredServices() {
        Log.i(TAG, "지연 서비스 일괄 시작 (부팅 ${DEFERRED_DELAY_MS / 1000}초 후)")

        // 학습 파이프라인
        lazyStart("DriveTrainingScheduler") {
            koin().getOrNull<com.xreal.nativear.learning.DriveTrainingScheduler>()?.schedule()
        }

        // 백업/동기화
        lazyStart("BackupSyncScheduler") {
            koin().getOrNull<com.xreal.nativear.sync.BackupSyncScheduler>()?.schedule()
        }
        lazyStart("PredictionSyncService") {
            koin().getOrNull<com.xreal.nativear.sync.PredictionSyncService>()?.start()
        }
        lazyStart("OrchestratorClient") {
            koin().getOrNull<com.xreal.nativear.sync.OrchestratorClient>()?.start()
        }

        // 진화/원격 도구
        lazyStart("EvolutionBridge") {
            koin().getOrNull<com.xreal.nativear.evolution.EvolutionBridge>()?.start()
        }

        // 디바이스 연결
        lazyStart("CompanionDeviceManager") {
            koin().getOrNull<com.xreal.nativear.companion.CompanionDeviceManager>()?.start()
        }
        lazyStart("NetworkCameraClient") {
            koin().getOrNull<com.xreal.nativear.remote.NetworkCameraClient>()?.start()
        }

        // 자기 성장
        lazyStart("AgentPersonalityEvolution") {
            koin().getOrNull<com.xreal.nativear.companion.AgentPersonalityEvolution>()?.loadCharacters()
        }

        // 디버그
        lazyStart("DebugHUD") {
            koin().getOrNull<com.xreal.nativear.hud.DebugHUD>()?.start()
        }

        Log.i(TAG, "지연 서비스 시작 완료 (${startedServices.size}개 서비스)")
    }

    // ─── 유틸 ───

    private inline fun lazyStart(key: String, crossinline starter: () -> Unit) {
        if (startedServices.add(key)) {
            try {
                starter()
                Log.d(TAG, "⏳→✅ $key lazy started")
            } catch (e: Exception) {
                startedServices.remove(key)  // 재시도 허용
                Log.w(TAG, "⏳→❌ $key lazy start 실패: ${e.message}")
            }
        }
    }

    private fun koin() = org.koin.java.KoinJavaComponent.getKoin()

    fun getStatus(): String {
        return "SituationAwareStarter: ${startedServices.size}개 서비스 시작됨 " +
               "[${startedServices.joinToString(", ")}]"
    }
}
