package com.xreal.nativear.companion

import com.xreal.nativear.context.LifeSituation

/**
 * AgentOwnPlan — 에이전트 자체 계획 설정 (Phase F-3).
 *
 * 각 AI 에이전트(페르소나)가 자신이 담당하는 상황에 대해
 * 어떻게 준비하고, 어떤 장기 목표를 가지며, 어떤 지식을 미리 수집할지 정의.
 *
 * ## 설계 원칙
 * - 기존 PersonaManager 페르소나 ID(vision_analyst, context_predictor, safety_monitor, memory_curator)와 1:1 매핑
 * - 기존 PlanManager.createTodo() 재사용 (createdBy="agent", category="AGENT_WARMUP")
 * - 기존 UnifiedMemoryDB persona_id 태깅 재사용 (PersonaMemoryService 패턴)
 *
 * ## 에이전트 → 담당 상황 매핑
 * ```
 * running_coach    → RUNNING, GYM_WORKOUT, WALKING_EXERCISE
 * meeting_agent    → IN_MEETING, AT_DESK_WORKING, TEACHING
 * health_agent     → MORNING_ROUTINE, SLEEPING_PREP, RELAXING_HOME
 * travel_agent     → TRAVELING_NEW_PLACE, TRAVELING_TRANSIT, COMMUTING
 * social_agent     → SOCIAL_GATHERING, PHONE_CALL, DINING_OUT
 * ```
 */
data class AgentOwnPlan(
    /** 에이전트 식별자 (PersonaManager 페르소나 ID와 동일) */
    val agentId: String,

    /** 사용자 표시 이름 */
    val displayName: String,

    /** 이번 달 장기 목표 */
    val longTermGoal: String,

    /** 이 에이전트가 담당하는 상황 목록 */
    val targetSituations: List<LifeSituation>,

    /**
     * 워밍업 프롬프트 템플릿.
     * {situation} = 상황명, {time} = 예상 시각, {user_goal} = 장기 목표
     */
    val warmupPromptTemplate: String,

    /** 도메인 지식 갱신 주기 (일) — F-4 KnowledgePrefetcher가 사용 */
    val knowledgeRefreshIntervalDays: Int = 3,

    /** 워밍업 예상 소요 토큰 */
    val estimatedWarmupTokens: Int = 300,

    /** 활성화 여부 */
    val isActive: Boolean = true
) {
    companion object {

        /**
         * 기본 에이전트 계획 목록.
         * 시스템에 내장된 5개 에이전트 정의.
         * (나중에 structured_data에서 동적으로 로드 가능 — F-6 이후)
         */
        fun getDefaultPlans(): List<AgentOwnPlan> = listOf(

            AgentOwnPlan(
                agentId = "running_coach",
                displayName = "러닝 코치",
                longTermGoal = "사용자의 지속적 훈련 습관 형성과 부상 예방",
                targetSituations = listOf(
                    LifeSituation.RUNNING,
                    LifeSituation.GYM_WORKOUT,
                    LifeSituation.WALKING_EXERCISE
                ),
                warmupPromptTemplate = """
                    다음 {situation} 상황을 위한 코칭 준비:
                    - 예상 시각: {time}
                    - 현재 목표: {user_goal}

                    다음을 간결하게 준비해주세요 (총 200자 이내):
                    1. 오늘 권장 강도 (이유 포함)
                    2. 준비 또는 주의 사항 1가지
                    3. 동기 부여 한 마디

                    데이터가 없으면 일반적인 조언으로 대체하세요.
                """.trimIndent(),
                knowledgeRefreshIntervalDays = 7,
                estimatedWarmupTokens = 250
            ),

            AgentOwnPlan(
                agentId = "meeting_agent",
                displayName = "회의 어시스턴트",
                longTermGoal = "회의 맥락 이해 지원 및 일정 관리 최적화",
                targetSituations = listOf(
                    LifeSituation.IN_MEETING,
                    LifeSituation.AT_DESK_WORKING,
                    LifeSituation.TEACHING
                ),
                warmupPromptTemplate = """
                    {situation} 상황 준비 ({time} 예정):

                    다음을 준비해주세요 (총 200자 이내):
                    1. 현재 시간대에 자주 나오는 주제나 안건 유형
                    2. 유용한 배경 지식 1가지
                    3. 사용자가 놓치기 쉬운 점 1가지
                """.trimIndent(),
                knowledgeRefreshIntervalDays = 3,
                estimatedWarmupTokens = 300
            ),

            AgentOwnPlan(
                agentId = "health_agent",
                displayName = "건강 관리사",
                longTermGoal = "사용자 웰빙 모니터링 및 생활 패턴 최적화 지원",
                targetSituations = listOf(
                    LifeSituation.MORNING_ROUTINE,
                    LifeSituation.SLEEPING_PREP,
                    LifeSituation.RELAXING_HOME,
                    LifeSituation.LUNCH_BREAK
                ),
                warmupPromptTemplate = """
                    {situation} 시간 건강 브리핑 준비:

                    간결하게 준비해주세요 (총 150자 이내):
                    1. {situation}에 적합한 건강 팁 1가지
                    2. 오늘 하루 주의할 점 (수면/영양/스트레스 중 하나)
                    3. 격려 한 마디
                """.trimIndent(),
                knowledgeRefreshIntervalDays = 5,
                estimatedWarmupTokens = 200
            ),

            AgentOwnPlan(
                agentId = "travel_agent",
                displayName = "이동 가이드",
                longTermGoal = "이동 중 유용한 정보 제공 및 새 장소 탐색 지원",
                targetSituations = listOf(
                    LifeSituation.TRAVELING_NEW_PLACE,
                    LifeSituation.TRAVELING_TRANSIT,
                    LifeSituation.COMMUTING,
                    LifeSituation.SHOPPING
                ),
                warmupPromptTemplate = """
                    {situation} 이동 준비:

                    간결하게 준비해주세요 (총 150자 이내):
                    1. 이 시간대 이동 시 유의사항 1가지
                    2. 도움이 될 수 있는 정보 또는 팁
                """.trimIndent(),
                knowledgeRefreshIntervalDays = 7,
                estimatedWarmupTokens = 200
            ),

            AgentOwnPlan(
                agentId = "social_agent",
                displayName = "소셜 어시스턴트",
                longTermGoal = "사회적 상황 맥락 지원 및 관계 관리 보조",
                targetSituations = listOf(
                    LifeSituation.SOCIAL_GATHERING,
                    LifeSituation.PHONE_CALL,
                    LifeSituation.DINING_OUT
                ),
                warmupPromptTemplate = """
                    {situation} 상황 대화 준비:

                    간결하게 준비해주세요 (총 150자 이내):
                    1. 이 상황에서 자연스러운 대화 주제 2가지
                    2. 주의하면 좋을 사항 1가지
                """.trimIndent(),
                knowledgeRefreshIntervalDays = 14,
                estimatedWarmupTokens = 200
            )
        )

        /** 특정 상황을 담당하는 에이전트 목록 */
        fun getAgentsForSituation(situation: LifeSituation): List<AgentOwnPlan> =
            getDefaultPlans().filter {
                it.isActive && situation in it.targetSituations
            }
    }
}
