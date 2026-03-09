package com.xreal.nativear.companion

import android.util.Log
import com.xreal.nativear.learning.IOutcomeRecorder
import com.xreal.nativear.monitoring.TokenEconomyManager
import com.xreal.nativear.policy.PolicyReader
import kotlinx.coroutines.CoroutineScope

/**
 * AgentMetaManager: Oversees all agent performance and generates optimization suggestions.
 *
 * Responsibilities:
 * - Weekly agent performance reports
 * - Cost-efficiency analysis (tokens per accepted intervention)
 * - Optimization suggestions for underperforming agents
 * - Agent ranking and comparison
 *
 * This is the "HR department" for AI agents — tracks who's effective,
 * who needs coaching, and who should change strategy.
 */
class AgentMetaManager(
    private val agentEvolution: AgentPersonalityEvolution,
    private val outcomeTracker: IOutcomeRecorder,
    private val tokenEconomy: TokenEconomyManager,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "AgentMetaManager"
        private val LOW_EFFECTIVENESS_THRESHOLD: Float get() = PolicyReader.getFloat("companion.low_effectiveness_threshold", 0.3f)
        private val HIGH_EFFECTIVENESS_THRESHOLD: Float get() = PolicyReader.getFloat("companion.high_effectiveness_threshold", 0.7f)
    }

    // ─── Weekly Performance Report ───

    fun generateWeeklyReport(): AgentPerformanceReport {
        val now = System.currentTimeMillis()
        val weekStart = now - 7 * 24 * 60 * 60 * 1000L
        val characters = agentEvolution.getAllCharacters()

        val agentReports = characters.map { character ->
            val effectiveness = try {
                outcomeTracker.getExpertEffectiveness(character.agentId)
            } catch (e: Exception) { 0f }

            val bestStrategy = try {
                outcomeTracker.getBestStrategyFor(character.agentId,
                    com.xreal.nativear.context.LifeSituation.UNKNOWN)
            } catch (e: Exception) { null }

            SingleAgentReport(
                agentId = character.agentId,
                agentName = character.name,
                growthStage = character.growthStage,
                interventionCount = character.totalInteractions,
                acceptanceRate = character.successRate,
                topStrategy = bestStrategy?.actionSummary,
                weakArea = inferWeakArea(character),
                trustScore = character.userTrustScore,
                traitsEvolved = character.evolvedTraits.map { it.trait }
            )
        }

        val overallStats = outcomeTracker.getOverallStats()

        return AgentPerformanceReport(
            weekStart = weekStart,
            weekEnd = now,
            agentReports = agentReports,
            totalTokensUsed = try {
                val usage = tokenEconomy.getUsageForPeriod(weekStart, now)
                (usage.totalInputTokens + usage.totalOutputTokens).toInt()
            } catch (_: Exception) { 0 },
            totalInterventions = overallStats.totalInterventions,
            overallAcceptanceRate = overallStats.acceptanceRate
        )
    }

    // ─── Optimization Suggestions ───

    fun suggestOptimizations(): List<AgentOptimization> {
        val characters = agentEvolution.getAllCharacters()
        val optimizations = mutableListOf<AgentOptimization>()

        for (character in characters) {
            if (character.totalInteractions < 10) continue // too few data points

            val effectiveness = try {
                outcomeTracker.getExpertEffectiveness(character.agentId)
            } catch (e: Exception) { continue }

            when {
                // Low effectiveness — needs strategy change
                effectiveness < LOW_EFFECTIVENESS_THRESHOLD -> {
                    optimizations.add(AgentOptimization(
                        agentId = character.agentId,
                        agentName = character.name,
                        currentEffectiveness = effectiveness,
                        suggestion = "채택률 ${"%.0f".format(effectiveness * 100)}% — " +
                                "프롬프트 조정 필요. ${inferOptimizationAction(character)}",
                        priority = 5
                    ))
                }

                // Moderate effectiveness — fine-tuning needed
                effectiveness < HIGH_EFFECTIVENESS_THRESHOLD -> {
                    optimizations.add(AgentOptimization(
                        agentId = character.agentId,
                        agentName = character.name,
                        currentEffectiveness = effectiveness,
                        suggestion = "채택률 ${"%.0f".format(effectiveness * 100)}% — " +
                                "미세 조정 가능. ${inferFinetuneAction(character)}",
                        priority = 3
                    ))
                }

                // High effectiveness — maintain strategy
                else -> {
                    optimizations.add(AgentOptimization(
                        agentId = character.agentId,
                        agentName = character.name,
                        currentEffectiveness = effectiveness,
                        suggestion = "채택률 ${"%.0f".format(effectiveness * 100)}% — 현재 전략 유지. 우수한 성과.",
                        priority = 1
                    ))
                }
            }
        }

        return optimizations.sortedByDescending { it.priority }
    }

    // ─── Cost Efficiency ───

    fun getCostEfficiency(): Map<String, Float> {
        val result = mutableMapOf<String, Float>()
        val characters = agentEvolution.getAllCharacters()

        for (character in characters) {
            if (character.successCount == 0) {
                result[character.agentId] = Float.MAX_VALUE // no successes = infinite cost per success
                continue
            }
            // Rough estimate: each interaction ≈ 500 tokens average
            val estimatedTokens = character.totalInteractions * 500f
            result[character.agentId] = estimatedTokens / character.successCount
        }

        return result
    }

    // ─── Briefing Summary ───

    fun generateBriefingSummary(): String {
        val characters = agentEvolution.getAllCharacters()
        if (characters.isEmpty()) return ""

        return buildString {
            appendLine("에이전트 성과 요약:")
            val sorted = characters.sortedByDescending { it.successRate }
            for (character in sorted) {
                if (character.totalInteractions == 0) continue
                val effectiveness = try {
                    outcomeTracker.getExpertEffectiveness(character.agentId)
                } catch (e: Exception) { character.successRate }

                appendLine("  ${character.name} [${character.growthStage.displayName}]: " +
                        "채택률 ${"%.0f".format(effectiveness * 100)}%, " +
                        "신뢰도 ${"%.0f".format(character.userTrustScore * 100)}%, " +
                        "${character.totalInteractions}회 상호작용")
            }

            val optimizations = suggestOptimizations().filter { it.priority >= 4 }
            if (optimizations.isNotEmpty()) {
                appendLine("개선 필요:")
                optimizations.forEach {
                    appendLine("  ⚠️ ${it.agentName}: ${it.suggestion}")
                }
            }
        }
    }

    // ─── Helper Methods ───

    private fun inferWeakArea(character: AgentCharacter): String? {
        val memories = agentEvolution.getRecentAgentMemories(character.agentId, limit = 20)
        val failures = memories.filter { it.wasSuccessful == false }
        if (failures.isEmpty()) return null

        // Find common patterns in failures
        val failureContexts = failures.mapNotNull { it.content }
        return when {
            failureContexts.any { "타이밍" in it || "시간" in it } -> "개입 타이밍 부적절"
            failureContexts.any { "과도" in it || "너무" in it } -> "과도한 정보 제공"
            failureContexts.any { "반복" in it || "같은" in it } -> "반복적 제안"
            failureContexts.any { "상황" in it || "맥락" in it } -> "상황 판단 부정확"
            else -> "채택률 개선 필요"
        }
    }

    private fun inferOptimizationAction(character: AgentCharacter): String {
        val weakArea = inferWeakArea(character)
        return when (weakArea) {
            "개입 타이밍 부적절" -> "개입 빈도를 줄이고 사용자가 수용적인 시점을 학습하세요."
            "과도한 정보 제공" -> "메시지를 더 짧고 핵심적으로 전달하세요."
            "반복적 제안" -> "새로운 전략을 시도하고 이전 실패를 참조하세요."
            "상황 판단 부정확" -> "상황 인식 정확도를 높이세요."
            else -> "사용자 피드백 패턴을 분석하여 전략을 조정하세요."
        }
    }

    private fun inferFinetuneAction(character: AgentCharacter): String {
        val strongTraits = character.evolvedTraits.filter { it.strength > 0.5f }
        return if (strongTraits.isNotEmpty()) {
            "강점(${strongTraits.joinToString(", ") { it.trait }})을 더 활용하세요."
        } else {
            "성공 패턴을 분석하여 강점을 파악하세요."
        }
    }
}
