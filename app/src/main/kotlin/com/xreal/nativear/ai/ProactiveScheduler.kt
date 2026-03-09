package com.xreal.nativear.ai

import android.util.Log
import com.xreal.nativear.policy.PolicyReader
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * ProactiveScheduler — 모든 자율행동 AI 호출의 중앙 스케줄러.
 *
 * ## 문제
 * 기존: 10+ 독립 while(isActive) 루프가 각자 타이머로 AI 호출 → 글로벌 제어 불가.
 * - StrategistService: 5분 반성 루프
 * - MissionAgentRunner: 에이전트당 while(isActive) 루프 (10초~10분)
 * - MissionConductor: 미션당 monitorLoop (5분)
 * - ExpertDomainTemplates: translator 10초, safety_runner 30초 등
 *
 * ## 해결
 * 모든 주기적 AI 호출을 ProactiveScheduler에 등록.
 * 스케줄러가 AICallGateway를 통해 글로벌 rate limit/budget/thermal 체크 후 실행.
 *
 * ## 정책 키
 * - gateway.proactive_scheduler_enabled (기본 true)
 * - gateway.max_concurrent_proactive (기본 2) — 동시 실행 상한
 * - gateway.min_cloud_interval_ms (기본 60000) — 최소 간격
 *
 * ## 사용법
 * ```kotlin
 * proactiveScheduler.register(
 *     ProactiveTask(
 *         id = "strategist_reflection",
 *         intervalMs = 300_000L,
 *         priority = TaskPriority.LOW,
 *         action = { runReflectionCycle() }
 *     )
 * )
 * proactiveScheduler.start(scope)
 * ```
 */
class ProactiveScheduler(
    private val aiCallGateway: AICallGateway,
    private val storyPhaseController: com.xreal.nativear.storyteller.IStoryPhaseGate? = null
) {
    companion object {
        private const val TAG = "ProactiveScheduler"
        private const val SCHEDULER_TICK_MS = 5_000L  // 5초마다 실행 가능 태스크 확인
    }

    // ── 태스크 우선순위 ──

    enum class TaskPriority(val level: Int) {
        /** 사용자 직접 트리거 (음성 "지금 분석해") */
        HIGH(0),
        /** 반응형 (이벤트 기반: 장면 변화 후 분석) */
        MEDIUM(1),
        /** 정기 자율행동 (리플렉션, 모니터링) */
        LOW(2),
        /** 백그라운드 유지보수 (메모리 압축 등) */
        BACKGROUND(3)
    }

    // ── 태스크 정의 ──

    data class ProactiveTask(
        val id: String,
        /** 기본 실행 간격 (ms). 예산 상태에 따라 자동 스케일링됨 */
        val intervalMs: Long,
        val priority: TaskPriority = TaskPriority.LOW,
        /** 결과가 사용자에게 보이는가 */
        val isUserFacing: Boolean = false,
        /** 예상 토큰 사용량 */
        val estimatedTokens: Int = 500,
        /** 실행 액션 (suspend) */
        val action: suspend () -> Unit
    )

    // ── 내부 상태 ──

    private data class TaskState(
        val task: ProactiveTask,
        var lastExecutedMs: Long = 0L,
        var consecutiveSkips: Int = 0,
        var isRunning: Boolean = false,
        var enabled: Boolean = true
    )

    private val tasks = ConcurrentHashMap<String, TaskState>()
    private var schedulerJob: Job? = null
    @Volatile private var isStarted = false

    // ── 등록/해제 ──

    fun register(task: ProactiveTask) {
        tasks[task.id] = TaskState(task = task)
        Log.d(TAG, "태스크 등록: ${task.id} (${task.intervalMs}ms, ${task.priority})")
    }

    fun unregister(taskId: String) {
        tasks.remove(taskId)
        Log.d(TAG, "태스크 해제: $taskId")
    }

    fun setEnabled(taskId: String, enabled: Boolean) {
        tasks[taskId]?.enabled = enabled
    }

    // ── 시작/정지 ──

    fun start(scope: CoroutineScope) {
        if (isStarted) return
        isStarted = true

        schedulerJob = scope.launch {
            Log.i(TAG, "ProactiveScheduler 시작 (등록 태스크: ${tasks.size})")
            delay(30_000L)  // 앱 시작 30초 후 첫 실행

            while (isActive) {
                try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "스케줄러 tick 오류: ${e.message}", e)
                }
                delay(SCHEDULER_TICK_MS)
            }
        }
    }

    fun stop() {
        isStarted = false
        schedulerJob?.cancel()
        schedulerJob = null
        tasks.values.forEach { it.isRunning = false }
        Log.i(TAG, "ProactiveScheduler 정지")
    }

    // ── 스케줄러 tick ──

    private suspend fun tick() {
        if (!PolicyReader.getBoolean("gateway.proactive_scheduler_enabled", true)) return

        // ★ 상태 머신 1차 게이트: NARRATING이 아니면 모든 프로액티브 태스크 차단
        val phase = storyPhaseController?.currentPhase
        if (phase != null && !phase.allowsProactiveTasks) {
            return  // DORMANT, SLEEPING, OBSERVING 등 → 아무것도 안 함
        }

        // 비활성 검사 (상태 머신에 위임)
        storyPhaseController?.checkInactivity()

        val now = System.currentTimeMillis()
        val budgetRatio = try {
            val tracker = org.koin.java.KoinJavaComponent.getKoin()
                .getOrNull<com.xreal.nativear.router.persona.TokenBudgetTracker>()
            tracker?.getGlobalUsageRatio() ?: 0f
        } catch (_: Exception) { 0f }

        // 실행 대상 태스크 선별 (우선순위순, 간격 충족, 실행 중 아닌 것)
        val eligible = tasks.values
            .filter { state ->
                state.enabled &&
                !state.isRunning &&
                (now - state.lastExecutedMs) >= computeScaledInterval(state.task.intervalMs, budgetRatio)
            }
            .sortedBy { it.task.priority.level }

        if (eligible.isEmpty()) return

        // 동시 실행 중 태스크 수 확인
        val maxConcurrent = PolicyReader.getInt("gateway.max_concurrent_proactive", 2)
        val currentRunning = tasks.values.count { it.isRunning }
        val slotsAvailable = (maxConcurrent - currentRunning).coerceAtLeast(0)

        if (slotsAvailable == 0) return

        // 슬롯 수만큼 실행
        val toRun = eligible.take(slotsAvailable)
        for (state in toRun) {
            launchTask(state, budgetRatio)
        }
    }

    private fun launchTask(state: TaskState, budgetRatio: Float) {
        val task = state.task

        // AICallGateway 체크
        val gateDecision = kotlinx.coroutines.runBlocking {
            aiCallGateway.checkGate(
                priority = AICallGateway.CallPriority.PROACTIVE,
                visibility = if (task.isUserFacing) AICallGateway.VisibilityIntent.USER_FACING
                             else AICallGateway.VisibilityIntent.INTERNAL_ONLY,
                estimatedTokens = task.estimatedTokens
            )
        }

        if (!gateDecision.allowed) {
            state.consecutiveSkips++
            if (state.consecutiveSkips % 10 == 1) { // 매 10회 스킵마다 로그
                Log.d(TAG, "태스크 스킵: ${task.id} (${gateDecision.reason}, 연속 ${state.consecutiveSkips}회)")
            }
            return
        }

        state.isRunning = true
        state.consecutiveSkips = 0

        // 별도 코루틴에서 실행
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                aiCallGateway.onProactiveCallStart()
                Log.d(TAG, "태스크 실행: ${task.id}")
                task.action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "태스크 실패: ${task.id} — ${e.message}")
            } finally {
                aiCallGateway.onProactiveCallEnd()
                state.isRunning = false
                state.lastExecutedMs = System.currentTimeMillis()
            }
        }
    }

    // ── 예산 기반 간격 스케일링 ──

    private fun computeScaledInterval(baseMs: Long, budgetRatio: Float): Long {
        val multiplier = when {
            budgetRatio >= 0.90f -> Float.MAX_VALUE  // 실질적 정지
            budgetRatio >= 0.70f -> PolicyReader.getFloat("gateway.budget_scale_70_multiplier", 4.0f)
            budgetRatio >= 0.50f -> PolicyReader.getFloat("gateway.budget_scale_50_multiplier", 2.0f)
            else -> 1.0f
        }
        // overflow 방지
        return if (multiplier >= Float.MAX_VALUE) Long.MAX_VALUE / 2
               else (baseMs * multiplier).toLong()
    }

    // ── 진단 ──

    fun getStatusSummary(): String {
        val running = tasks.values.count { it.isRunning }
        val total = tasks.size
        val skipped = tasks.values.sumOf { it.consecutiveSkips }
        return "Scheduler: $running/$total running, ${skipped} skips"
    }

    fun getTaskList(): List<Map<String, Any>> = tasks.values.map { state ->
        mapOf(
            "id" to state.task.id,
            "priority" to state.task.priority.name,
            "intervalMs" to state.task.intervalMs,
            "isRunning" to state.isRunning,
            "enabled" to state.enabled,
            "consecutiveSkips" to state.consecutiveSkips,
            "lastExecutedMs" to state.lastExecutedMs
        )
    }
}
