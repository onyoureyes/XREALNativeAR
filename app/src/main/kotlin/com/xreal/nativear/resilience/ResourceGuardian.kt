package com.xreal.nativear.resilience

import android.util.Log
import com.xreal.nativear.ai.IAICallService
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.policy.PolicyReader
import com.xreal.nativear.policy.PolicyRegistry
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * ResourceGuardian — AI 기반 자원 수호자.
 *
 * ## 핵심 원칙
 * "하드코딩된 재시도 루프 대신, AI가 상황을 판단하고 정책을 업데이트하며,
 *  모든 시스템은 정책만을 따른다."
 *
 * ## 동작 흐름
 * ```
 * 1. 이상 징후 수집 (에러, 재시도 실패, 온도, 좀비 스레드)
 *    ↓
 * 2. AI 판단 요청 (Edge LLM — $0 비용)
 *    "USB 3회 실패, 온도 47℃ — 재시도 중지? 어떤 자원 비활성화?"
 *    ↓
 * 3. AI 응답 파싱 → PolicyRegistry 업데이트
 *    resource.hw_retry_enabled=false
 *    resource.hw_suspend_until_ms=1709971200000
 *    ↓
 * 4. 모든 시스템이 PolicyReader로 정책 확인 → 자동 반영
 * ```
 *
 * ## AI 없이도 동작 (fallback)
 * Edge LLM 미준비 시 규칙 기반 판단:
 * - 연속 실패 3+ → 해당 자원 비활성화
 * - 온도 46℃+ → 불필요한 자원 정지
 * - 같은 에러 5+ 반복 → 해당 기능 일시 중지
 */
class ResourceGuardian(
    private val eventBus: GlobalEventBus,
    private val policyRegistry: PolicyRegistry,
    private val aiRegistry: IAICallService? = null
) {
    companion object {
        private const val TAG = "ResourceGuardian"
        private const val MAX_ANOMALY_BUFFER = 50

        private val GUARDIAN_SYSTEM_PROMPT = """
당신은 AR 글래스 시스템의 자원 수호자입니다.
시스템 이상 징후를 분석하고 정책 변경을 제안합니다.

원칙:
1. 반복 실패하는 자원은 즉시 비활성화 (에너지 낭비 금지)
2. 과열 시 비필수 기능 정지 (하드웨어 보호 우선)
3. 좀비 프로세스/반복 에러 → 근본 원인의 기능 축소
4. 최소한의 변경만 제안 (과잉 반응 금지)
5. 서버 API(cloud)는 현재 비활성화 상태 — 활성화하지 마세요

응답은 POLICY:키=값 형식만 사용하세요. 설명 불필요.
""".trimIndent()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val limitedDispatcher = Dispatchers.Default.limitedParallelism(2)
    private val scope = CoroutineScope(SupervisorJob() + limitedDispatcher)
    private var guardJob: Job? = null

    // ── 이상 징후 버퍼 ──

    data class Anomaly(
        val timestamp: Long = System.currentTimeMillis(),
        val source: String,        // "USB_RGB", "AI_CLOUD", "COROUTINE", "THERMAL"
        val type: AnomalyType,
        val detail: String,
        val count: Int = 1         // 동일 이상 반복 횟수
    )

    enum class AnomalyType {
        RETRY_EXHAUSTED,    // 재시도 한도 초과
        REPEATED_ERROR,     // 같은 에러 반복
        ZOMBIE_DETECTED,    // 좀비 프로세스/스레드 감지
        THERMAL_WARNING,    // 과열 경고
        RESOURCE_WASTE,     // 불필요한 자원 소비
        API_COST_ALERT      // API 비용 경고
    }

    private val anomalyBuffer = ConcurrentLinkedQueue<Anomaly>()
    private val anomalyCounts = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

    // ── 공개 API: 이상 징후 보고 ──

    /**
     * 시스템 어디서든 이상 징후 보고.
     * CameraStreamManager, HardwareManager, AIResourceRegistry 등에서 호출.
     */
    fun reportAnomaly(source: String, type: AnomalyType, detail: String) {
        val key = "${source}_${type.name}"
        val count = anomalyCounts.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()

        val anomaly = Anomaly(
            source = source,
            type = type,
            detail = detail,
            count = count
        )

        // 버퍼 관리
        while (anomalyBuffer.size >= MAX_ANOMALY_BUFFER) anomalyBuffer.poll()
        anomalyBuffer.add(anomaly)

        Log.w(TAG, "이상 징후: [$source] ${type.name} — $detail (반복: ${count}회)")

        // ★ 즉각 대응이 필요한 심각 징후는 즉시 판단
        if (count >= 3 && type in setOf(AnomalyType.RETRY_EXHAUSTED, AnomalyType.ZOMBIE_DETECTED)) {
            scope.launch { evaluateAndAct() }
        }
    }

    // ── 시작/종료 ──

    fun start() {
        // EventBus 구독 — 시스템 에러 자동 수집
        scope.launch {
            eventBus.events.collect { event ->
                try {
                    when (event) {
                        is XRealEvent.SystemEvent.Error -> {
                            val source = event.code.substringBefore("_ERROR").ifBlank { "SYSTEM" }
                            reportAnomaly(source, AnomalyType.REPEATED_ERROR, event.message)
                        }
                        is XRealEvent.SystemEvent.ResourceAlert -> {
                            if (event.batteryTempC > 44f) {
                                reportAnomaly("THERMAL", AnomalyType.THERMAL_WARNING,
                                    "배터리 온도 ${event.batteryTempC}°C")
                            }
                        }
                        else -> {}
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "이벤트 수집 오류: ${e.message}")
                }
            }
        }

        // 주기적 AI 판단 루프
        guardJob = scope.launch {
            delay(10_000) // 초기 안정화 대기
            while (isActive) {
                val intervalMs = PolicyReader.getLong("resource.guard_interval_ms", 30_000L)
                val enabled = PolicyReader.getBoolean("resource.guard_enabled", true)
                if (enabled && anomalyBuffer.isNotEmpty()) {
                    try {
                        evaluateAndAct()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "AI 판단 실패 — 규칙 기반 fallback: ${e.message}")
                        evaluateWithRules()
                    }
                }
                delay(intervalMs)
            }
        }

        Log.i(TAG, "ResourceGuardian 시작 — AI 기반 자원 수호 활성화")
    }

    fun stop() {
        guardJob?.cancel()
        Log.i(TAG, "ResourceGuardian 종료")
    }

    // ── AI 판단 ──

    private suspend fun evaluateAndAct() {
        val anomalies = anomalyBuffer.toList()
        if (anomalies.isEmpty()) return

        // 먼저 규칙 기반 즉각 대응 (AI 응답 대기 없이)
        evaluateWithRules()

        // AI에게 상황 판단 요청 (Edge LLM — $0)
        if (aiRegistry == null) return

        val situationSummary = buildSituationSummary(anomalies)
        val prompt = buildAIPrompt(situationSummary)

        try {
            val result = aiRegistry.routeText(
                messages = listOf(com.xreal.nativear.ai.AIMessage("user", prompt)),
                systemPrompt = GUARDIAN_SYSTEM_PROMPT,
                temperature = 0.1f,
                maxTokens = 300
            )

            val aiResponse = result?.response?.text?.trim()
            if (!aiResponse.isNullOrBlank()) {
                parseAndApplyPolicyChanges(aiResponse, "ai_guardian")
                Log.i(TAG, "AI 판단 완료 → 정책 업데이트 적용")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "AI 판단 실패 (규칙 기반만 적용): ${e.message}")
        }

        // 처리된 anomaly 정리
        val processedBefore = System.currentTimeMillis() - 60_000
        anomalyBuffer.removeAll { it.timestamp < processedBefore }
    }

    // ── 규칙 기반 즉각 대응 (AI 없이도 동작) ──

    private fun evaluateWithRules() {
        val anomalies = anomalyBuffer.toList()

        // 규칙 1: USB 재시도 연속 실패 3+ → 재시도 비활성화
        val usbRetryExhausted = anomalyCounts["USB_RGB_RETRY_EXHAUSTED"]?.get() ?: 0
        if (usbRetryExhausted >= 1) {
            applyPolicy("resource.hw_retry_enabled", "false", "rule:usb_retry_exhausted")
            // 5분 후 재시도 허용 (자동 복구 기회)
            val suspendUntil = System.currentTimeMillis() + 300_000
            applyPolicy("resource.hw_suspend_until_ms", suspendUntil.toString(), "rule:usb_cooldown")
        }

        // 규칙 2: 과열 46℃+ → 불필요한 자원 정지 (카메라 재시도 금지, AI 호출 최소화)
        val thermalCount = anomalyCounts["THERMAL_THERMAL_WARNING"]?.get() ?: 0
        if (thermalCount >= 2) {
            applyPolicy("resource.hw_retry_enabled", "false", "rule:thermal_protect")
            applyPolicy("resource.max_concurrent_ai_calls", "1", "rule:thermal_protect")
        }

        // 규칙 3: 같은 에러 5+ 반복 → 해당 소스 관련 기능 축소
        for ((key, counter) in anomalyCounts) {
            if (counter.get() >= 5 && key.endsWith("_REPEATED_ERROR")) {
                val source = key.removeSuffix("_REPEATED_ERROR")
                Log.w(TAG, "규칙 대응: $source 에러 ${counter.get()}회 반복 — 관련 기능 축소")
                // 에러 카운터 리셋 (무한 반복 방지)
                counter.set(0)
            }
        }

        // 규칙 4: 좀비 감지 → 최대 동시 코루틴 축소
        val zombieCount = anomalyCounts.entries
            .filter { it.key.endsWith("_ZOMBIE_DETECTED") }
            .sumOf { it.value.get() }
        if (zombieCount >= 2) {
            val current = PolicyReader.getInt("resource.max_concurrent_coroutines", 8)
            val reduced = (current - 2).coerceAtLeast(2)
            applyPolicy("resource.max_concurrent_coroutines", reduced.toString(), "rule:zombie_throttle")
        }
    }

    // ── AI 프롬프트 생성 ──

    private fun buildSituationSummary(anomalies: List<Anomaly>): String = buildString {
        // 최근 이상 징후 요약
        val grouped = anomalies.groupBy { "${it.source}_${it.type}" }
        appendLine("## 이상 징후 (최근 ${anomalies.size}건)")
        for ((key, items) in grouped.entries.take(10)) {
            val latest = items.maxByOrNull { it.timestamp }!!
            appendLine("- [$key] ${latest.detail} (반복: ${latest.count}회)")
        }

        // 현재 정책 상태
        appendLine("\n## 현재 정책")
        appendLine("- cloud_enabled: ${PolicyReader.getBoolean("resource.cloud_enabled", false)}")
        appendLine("- remote_enabled: ${PolicyReader.getBoolean("resource.remote_enabled", true)}")
        appendLine("- edge_enabled: ${PolicyReader.getBoolean("resource.edge_enabled", true)}")
        appendLine("- hw_retry_enabled: ${PolicyReader.getBoolean("resource.hw_retry_enabled", true)}")
        appendLine("- max_concurrent_ai_calls: ${PolicyReader.getInt("resource.max_concurrent_ai_calls", 2)}")
        appendLine("- max_concurrent_coroutines: ${PolicyReader.getInt("resource.max_concurrent_coroutines", 8)}")
    }

    private fun buildAIPrompt(situation: String): String = """
$situation

위 이상 징후를 분석하고 정책 변경을 제안하세요.
응답 형식 (한 줄에 하나씩, 변경 없으면 빈 응답):
POLICY:키=값
POLICY:키=값

예시:
POLICY:resource.hw_retry_enabled=false
POLICY:resource.max_concurrent_ai_calls=1

규칙:
- 반복 실패 자원은 비활성화 (나중에 자동 복구 가능)
- 과열 시 불필요한 자원 정지
- 좀비/반복 에러 → 관련 기능 축소
- 변경이 필요 없으면 아무것도 출력하지 마세요
""".trimIndent()

    // ── AI 응답 파싱 → 정책 적용 ──

    private fun parseAndApplyPolicyChanges(aiResponse: String, source: String) {
        val policyPattern = Regex("""POLICY:(\S+)=(\S+)""")
        var appliedCount = 0

        for (line in aiResponse.lines()) {
            val match = policyPattern.find(line.trim()) ?: continue
            val key = match.groupValues[1]
            val value = match.groupValues[2]

            if (applyPolicy(key, value, source)) {
                appliedCount++
            }
        }

        if (appliedCount > 0) {
            Log.i(TAG, "AI 판단 → $appliedCount 개 정책 변경 적용 (source=$source)")
            scope.launch {
                eventBus.publish(XRealEvent.SystemEvent.DebugLog(
                    "ResourceGuardian: AI가 $appliedCount 개 정책 변경"
                ))
            }
        }
    }

    private fun applyPolicy(key: String, value: String, source: String): Boolean {
        // resource.* 정책만 변경 허용 (안전 범위)
        if (!key.startsWith("resource.") && !key.startsWith("camera.")) {
            Log.w(TAG, "정책 변경 거부: $key (허용 범위 외)")
            return false
        }
        return policyRegistry.set(key, value, source)
    }

    // ── 상태 조회 (디버그/HUD) ──

    fun getStatusSummary(): String = buildString {
        val total = anomalyBuffer.size
        val activeAnomalies = anomalyCounts.entries
            .filter { it.value.get() > 0 }
            .sortedByDescending { it.value.get() }
            .take(3)

        append("Guard: ${total}건")
        if (activeAnomalies.isNotEmpty()) {
            append(" [")
            append(activeAnomalies.joinToString(", ") { "${it.key.take(10)}:${it.value.get()}" })
            append("]")
        }
    }

    /** 이상 카운터 리셋 (상태 정상화 시 호출) */
    fun resetAnomalyCounters(source: String? = null) {
        if (source != null) {
            anomalyCounts.keys.filter { it.startsWith(source) }.forEach {
                anomalyCounts[it]?.set(0)
            }
        } else {
            anomalyCounts.values.forEach { it.set(0) }
        }
        Log.i(TAG, "이상 카운터 리셋: ${source ?: "전체"}")
    }

}
