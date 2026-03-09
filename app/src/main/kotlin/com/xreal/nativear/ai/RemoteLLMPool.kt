package com.xreal.nativear.ai

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * RemoteLLMPool — 여러 Remote LLM 서버를 어레이로 관리.
 *
 * 클라우드 AI와 동일하게 다중 서버를 동시 활용:
 *   PC (gemma-3-12b, 39tok/s) + 스팀덱 (gemma-3-4b, 20tok/s) + 추후 추가 서버
 *
 * 전략:
 *   - HealthCheck: 60초 주기 /health 핑 → 살아있는 서버만 사용
 *   - SmartRouting: 복잡한 작업 → 큰 모델, 단순 작업 → 작은 모델
 *   - Failover: 한 서버 실패 → 다음 서버 즉시 시도 (에지 가기 전)
 *   - LoadBalance: 라운드로빈 (동일 tier 서버 간)
 *
 * Edge LLM은 이 풀에 포함하지 않음 — 최후의 보루로 별도 관리.
 */
class RemoteLLMPool(
    private val servers: Map<ProviderId, IAIProvider>
) {
    private val TAG = "RemoteLLMPool"

    // 서버 상태
    data class ServerStatus(
        val providerId: ProviderId,
        val modelSize: ModelSize,
        var isHealthy: Boolean = true,
        var lastSuccessMs: Long = 0,
        var lastFailureMs: Long = 0,
        var consecutiveFailures: Int = 0,
        var avgLatencyMs: Long = 0,
        var totalCalls: Long = 0,
        var totalFailures: Long = 0
    )

    enum class ModelSize(val rank: Int, val label: String) {
        LARGE(3, "12B+"),    // gemma-3-12b 등
        MEDIUM(2, "4B-8B"),  // gemma-3-4b 등
        SMALL(1, "1B-3B")    // 소형 모델
    }

    /** 작업 복잡도 — 라우팅 힌트 */
    enum class TaskComplexity {
        SIMPLE,   // 분류, 요약, 단답 → 작은 모델도 OK
        MODERATE, // 일반 대화, 번역 → 중간 모델 이상
        COMPLEX   // 추론, 코드, 분석 → 큰 모델 우선
    }

    private val statusMap = ConcurrentHashMap<ProviderId, ServerStatus>()
    private val roundRobinIndex = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var healthCheckJob: Job? = null

    init {
        for ((id, provider) in servers) {
            val size = classifyModelSize(provider.getModelName())
            statusMap[id] = ServerStatus(
                providerId = id,
                modelSize = size,
                isHealthy = provider.isAvailable
            )
        }
        Log.i(TAG, "RemoteLLMPool 초기화: ${servers.size}개 서버 " +
                statusMap.values.joinToString { "${it.providerId}(${it.modelSize.label})" })
    }

    /** 헬스체크 시작 (60초 주기) */
    fun startHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch {
            while (isActive) {
                checkAllHealth()
                delay(60_000)
            }
        }
    }

    fun stopHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = null
    }

    // =========================================================================
    // 라우팅
    // =========================================================================

    /**
     * 작업 복잡도에 따라 최적 서버 선택.
     * @return 정렬된 서버 목록 (1순위부터 시도)
     */
    fun getOrderedServers(complexity: TaskComplexity = TaskComplexity.MODERATE): List<IAIProvider> {
        val healthy = statusMap.values
            .filter { it.isHealthy }
            .sortedWith(compareBy<ServerStatus> {
                // 복잡도에 따른 우선순위
                when (complexity) {
                    TaskComplexity.COMPLEX -> -it.modelSize.rank   // 큰 모델 우선
                    TaskComplexity.SIMPLE -> it.modelSize.rank     // 작은 모델 우선 (빠르니까)
                    TaskComplexity.MODERATE -> -it.modelSize.rank  // 큰 모델 우선 (기본)
                }
            }.thenBy { it.avgLatencyMs }) // 같은 tier면 빠른 서버 우선

        return healthy.mapNotNull { servers[it.providerId] }
    }

    /**
     * 단일 서버 선택 (라운드로빈 + 복잡도 기반).
     * @return 가용 서버, 없으면 null (→ caller가 edge로 fallback)
     */
    fun pickServer(complexity: TaskComplexity = TaskComplexity.MODERATE): IAIProvider? {
        val ordered = getOrderedServers(complexity)
        if (ordered.isEmpty()) return null

        // COMPLEX: 항상 가장 큰 모델
        if (complexity == TaskComplexity.COMPLEX) return ordered.first()

        // SIMPLE/MODERATE: 동일 tier 내에서 라운드로빈
        val topTier = statusMap[ordered.first().providerId]?.modelSize
        val sameTier = ordered.filter {
            statusMap[it.providerId]?.modelSize == topTier
        }
        if (sameTier.size <= 1) return sameTier.firstOrNull()

        val idx = roundRobinIndex.getAndIncrement() % sameTier.size
        return sameTier[idx]
    }

    /**
     * Failover 포함 호출 — 한 서버 실패 시 다음 서버 자동 시도.
     * Edge로 가기 전에 모든 Remote 서버를 순회.
     */
    suspend fun callWithFailover(
        complexity: TaskComplexity = TaskComplexity.MODERATE,
        call: suspend (IAIProvider) -> AIResponse
    ): AIResponse? {
        val ordered = getOrderedServers(complexity)

        for (provider in ordered) {
            try {
                val startMs = System.currentTimeMillis()
                val response = call(provider)
                val latencyMs = System.currentTimeMillis() - startMs

                recordSuccess(provider.providerId, latencyMs)
                return response

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordFailure(provider.providerId, e)
                Log.w(TAG, "[Failover] ${provider.providerId} 실패 → 다음 서버 시도: ${e.message}")
            }
        }

        Log.w(TAG, "모든 Remote LLM 서버 실패 (${ordered.size}개)")
        return null // → caller가 edge fallback 처리
    }

    // =========================================================================
    // 상태 관리
    // =========================================================================

    fun recordSuccess(providerId: ProviderId, latencyMs: Long) {
        statusMap[providerId]?.let { status ->
            status.isHealthy = true
            status.lastSuccessMs = System.currentTimeMillis()
            status.consecutiveFailures = 0
            status.totalCalls++
            // EMA 레이턴시
            status.avgLatencyMs = if (status.avgLatencyMs == 0L) {
                latencyMs
            } else {
                (status.avgLatencyMs * 7 + latencyMs * 3) / 10
            }
        }
    }

    fun recordFailure(providerId: ProviderId, error: Exception? = null) {
        statusMap[providerId]?.let { status ->
            status.lastFailureMs = System.currentTimeMillis()
            status.consecutiveFailures++
            status.totalFailures++
            // 3연속 실패 → unhealthy (다음 healthcheck까지)
            if (status.consecutiveFailures >= 3) {
                status.isHealthy = false
                Log.w(TAG, "${providerId} unhealthy (${status.consecutiveFailures}연속 실패)")
            }
        }
    }

    private suspend fun checkAllHealth() {
        for ((id, provider) in servers) {
            val status = statusMap[id] ?: continue
            try {
                // 간단한 1-token 호출로 health check
                val response = withTimeout(5000) {
                    provider.sendMessage(
                        messages = listOf(AIMessage("user", "hi")),
                        maxTokens = 1
                    )
                }
                val text = response.text
                if (text != null && !text.startsWith("Error:") && !text.startsWith("API Error")) {
                    if (!status.isHealthy) {
                        Log.i(TAG, "${id} 복구됨 (healthy)")
                    }
                    status.isHealthy = true
                    status.consecutiveFailures = 0
                } else {
                    status.isHealthy = false
                    Log.d(TAG, "${id} health check 에러 응답: ${text?.take(80)}")
                }
            } catch (e: Exception) {
                status.isHealthy = false
                Log.d(TAG, "${id} health check 실패: ${e.message}")
            }
        }

        val healthyCount = statusMap.values.count { it.isHealthy }
        Log.d(TAG, "HealthCheck: ${healthyCount}/${statusMap.size} 서버 가용")
    }

    // =========================================================================
    // 유틸리티
    // =========================================================================

    private fun classifyModelSize(modelName: String): ModelSize {
        val lower = modelName.lowercase()
        return when {
            lower.contains("12b") || lower.contains("13b") || lower.contains("14b") ||
            lower.contains("27b") || lower.contains("70b") -> ModelSize.LARGE
            lower.contains("4b") || lower.contains("7b") || lower.contains("8b") -> ModelSize.MEDIUM
            else -> ModelSize.SMALL
        }
    }

    /** 가용 서버 수 */
    val healthyCount: Int get() = statusMap.values.count { it.isHealthy }

    /** 전체 서버 수 */
    val totalCount: Int get() = servers.size

    /** 가용 여부 */
    val hasAvailableServer: Boolean get() = healthyCount > 0

    /** 상태 보고 */
    fun getStatusReport(): String = buildString {
        appendLine("[Remote LLM Pool: ${healthyCount}/${totalCount} 가용]")
        for ((id, status) in statusMap) {
            val health = if (status.isHealthy) "✓" else "✗"
            val model = servers[id]?.getModelName() ?: "?"
            appendLine("  $health $id: $model (${status.modelSize.label}, " +
                    "avg=${status.avgLatencyMs}ms, calls=${status.totalCalls}, " +
                    "fails=${status.totalFailures})")
        }
    }.trimEnd()

    /** AI 프롬프트용 간략 상태 */
    fun getContextSummary(): String {
        val healthy = statusMap.values.filter { it.isHealthy }
        if (healthy.isEmpty()) return "Remote LLM: 전체 오프라인"
        return "Remote LLM: ${healthy.joinToString { "${it.providerId.name}(${it.modelSize.label})" }} 가용"
    }
}
