package com.xreal.nativear.learning

import com.xreal.nativear.context.LifeSituation

/**
 * IOutcomeRecorder: AI 개입 결과 기록 및 조회 인터페이스.
 * 구현체: OutcomeTracker
 */
interface IOutcomeRecorder {
    fun start()
    fun stop()
    fun recordIntervention(
        expertId: String,
        situation: LifeSituation,
        action: String,
        contextSummary: String? = null
    ): String
    fun recordOutcome(
        interventionId: String,
        outcome: InterventionOutcome,
        satisfaction: Float? = null,
        notes: String? = null
    )
    fun getExpertEffectiveness(expertId: String): Float
    fun getSituationStrategies(situation: LifeSituation, limit: Int = 5): List<StrategyRecord>
    fun getBestStrategyFor(expertId: String, situation: LifeSituation): StrategyRecord?
    fun getOverallStats(): OutcomeStats
    fun getRecentOutcomeSummary(): String
}
