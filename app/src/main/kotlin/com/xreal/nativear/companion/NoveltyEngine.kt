package com.xreal.nativear.companion

import android.util.Log
import com.xreal.nativear.context.ContextSnapshot
import com.xreal.nativear.context.LifeSituation
import com.xreal.nativear.monitoring.TokenEconomyManager
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.policy.PolicyReader
import kotlinx.coroutines.*

/**
 * NoveltyEngine: Detects routine patterns and injects fresh perspectives.
 *
 * When user is in routine (familiar place, same time, same objects):
 * - Reduces analysis tokens by 30% (use cache)
 * - Invests saved tokens in novel perspectives (philosophical, artistic, scientific)
 *
 * When something new appears in a routine context, highlights it.
 */
class NoveltyEngine(
    private val familiarityEngine: FamiliarityEngine,
    private val analysisCacheManager: AnalysisCacheManager,
    private val tokenEconomy: TokenEconomyManager,
    private val eventBus: GlobalEventBus,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "NoveltyEngine"
        private val ROUTINE_NOVELTY_INTERVAL_MS: Long get() = PolicyReader.getLong("companion.routine_novelty_interval_ms", 900_000L)
        private val ACTIVE_NOVELTY_INTERVAL_MS: Long get() = PolicyReader.getLong("companion.active_novelty_interval_ms", 300_000L)
    }

    private var lastNoveltyAt = 0L
    private val noveltyCategories = listOf(
        "PHILOSOPHICAL", "SCIENTIFIC", "ARTISTIC", "HISTORICAL",
        "CHALLENGE", "MEMORY_PRIME", "CULTURAL", "NATURE"
    )

    // ─── Routine Detection ───

    fun isInRoutine(snapshot: ContextSnapshot): Boolean {
        val familiarLocation = snapshot.familiarLocation
        val isWeekday = !snapshot.isWeekend
        val familiarObjectRatio = calculateFamiliarObjectRatio(snapshot.visibleObjects)

        return familiarLocation &&
                familiarObjectRatio >= 0.8f &&
                isWeekday &&
                snapshot.currentUserState.name in listOf("IDLE", "WALKING", "SITTING")
    }

    private fun calculateFamiliarObjectRatio(visibleObjects: List<String>): Float {
        if (visibleObjects.isEmpty()) return 0f
        val familiarCount = visibleObjects.count { label ->
            val entity = familiarityEngine.getEntity(EntityType.OBJECT, label)
            entity != null && entity.familiarityLevel >= FamiliarityLevel.FAMILIAR
        }
        return familiarCount.toFloat() / visibleObjects.size
    }

    // ─── Token Allocation ───

    fun calculateTokenAllocation(isRoutine: Boolean, noveltyLevel: Float): TokenAllocation {
        val baseAnalysis = 500  // tokens per standard analysis
        val baseNovelty = 300   // tokens per novelty injection

        return if (isRoutine) {
            TokenAllocation(
                analysisTokenBudget = (baseAnalysis * 0.3f).toInt(), // 70% reduction
                noveltyTokenBudget = (baseNovelty * 1.5f).toInt(),   // 50% increase
                savedTokens = (baseAnalysis * 0.7f).toInt(),
                reason = "루틴 감지 — 분석 축소, 새로움 확대"
            )
        } else {
            TokenAllocation(
                analysisTokenBudget = baseAnalysis,
                noveltyTokenBudget = if (noveltyLevel > 0.5f) baseNovelty else (baseNovelty * 0.5f).toInt(),
                savedTokens = 0,
                reason = if (noveltyLevel > 0.5f) "새로운 환경 — 풍부한 분석" else "일반 환경"
            )
        }
    }

    // ─── Novelty Injection ───

    fun shouldInjectNovelty(situation: LifeSituation): Boolean {
        val now = System.currentTimeMillis()
        val interval = when (situation) {
            LifeSituation.COMMUTING, LifeSituation.WALKING_EXERCISE -> ACTIVE_NOVELTY_INTERVAL_MS
            LifeSituation.MORNING_ROUTINE, LifeSituation.AT_DESK_WORKING -> ROUTINE_NOVELTY_INTERVAL_MS
            LifeSituation.RELAXING_HOME -> ROUTINE_NOVELTY_INTERVAL_MS * 2
            else -> ROUTINE_NOVELTY_INTERVAL_MS
        }
        return now - lastNoveltyAt > interval
    }

    fun generateNoveltyPrompt(
        entity: EntityFamiliarity,
        snapshot: ContextSnapshot
    ): NoveltyPrompt? {
        if (!shouldInjectNovelty(LifeSituation.UNKNOWN)) return null

        val category = selectNoveltyCategory(entity, snapshot)
        lastNoveltyAt = System.currentTimeMillis()

        val prompt = when (category) {
            "PHILOSOPHICAL" -> buildPhilosophicalPrompt(entity, snapshot)
            "SCIENTIFIC" -> buildScientificPrompt(entity, snapshot)
            "ARTISTIC" -> buildArtisticPrompt(entity, snapshot)
            "HISTORICAL" -> buildHistoricalPrompt(entity, snapshot)
            "CHALLENGE" -> buildChallengePrompt(entity, snapshot)
            "MEMORY_PRIME" -> buildMemoryPrimePrompt(entity, snapshot)
            "CULTURAL" -> buildCulturalPrompt(entity, snapshot)
            "NATURE" -> buildNaturePrompt(entity, snapshot)
            else -> return null
        }

        return NoveltyPrompt(
            category = category,
            prompt = prompt,
            context = "entity=${entity.entityLabel}, encounters=${entity.totalEncounters}, " +
                    "situation=${snapshot.currentUserState.name}",
            maxTokens = 200
        )
    }

    private fun selectNoveltyCategory(entity: EntityFamiliarity, snapshot: ContextSnapshot): String {
        // Select category based on entity type and user context
        val weights = mutableMapOf<String, Float>()

        // Base weights
        noveltyCategories.forEach { weights[it] = 1.0f }

        // Entity-type bonuses
        when (entity.entityType) {
            EntityType.OBJECT -> {
                weights["SCIENTIFIC"] = weights["SCIENTIFIC"]!! + 1.0f
                weights["NATURE"] = weights["NATURE"]!! + 0.5f
            }
            EntityType.PERSON -> {
                weights["MEMORY_PRIME"] = weights["MEMORY_PRIME"]!! + 2.0f
                weights["CULTURAL"] = weights["CULTURAL"]!! + 0.5f
            }
            EntityType.PLACE -> {
                weights["HISTORICAL"] = weights["HISTORICAL"]!! + 1.5f
                weights["ARTISTIC"] = weights["ARTISTIC"]!! + 1.0f
            }
        }

        // Time-of-day bonuses
        when (snapshot.hourOfDay) {
            in 6..9 -> weights["PHILOSOPHICAL"] = weights["PHILOSOPHICAL"]!! + 1.0f
            in 10..14 -> weights["CHALLENGE"] = weights["CHALLENGE"]!! + 1.0f
            in 15..18 -> weights["SCIENTIFIC"] = weights["SCIENTIFIC"]!! + 0.5f
            in 19..22 -> weights["ARTISTIC"] = weights["ARTISTIC"]!! + 1.0f
        }

        // Familiarity bonuses: more familiar = more philosophical/creative
        if (entity.familiarityLevel >= FamiliarityLevel.INTIMATE) {
            weights["PHILOSOPHICAL"] = weights["PHILOSOPHICAL"]!! + 2.0f
            weights["ARTISTIC"] = weights["ARTISTIC"]!! + 1.5f
        }

        // Weighted random selection
        val totalWeight = weights.values.sum()
        var random = (Math.random() * totalWeight).toFloat()
        for ((cat, w) in weights) {
            random -= w
            if (random <= 0) return cat
        }
        return noveltyCategories.random()
    }

    // ─── Prompt Builders ───

    private fun buildPhilosophicalPrompt(entity: EntityFamiliarity, snapshot: ContextSnapshot): String {
        return "사용자가 [${entity.entityLabel}]을(를) ${entity.totalEncounters}번째 보고 있습니다. " +
                "이 대상에 대한 철학적/존재론적 관점을 1-2문장으로 제시하세요. " +
                "인간 경험, 시간의 흐름, 존재의 의미와 연결하세요. " +
                "절대 이전에 말한 것을 반복하지 마세요."
    }

    private fun buildScientificPrompt(entity: EntityFamiliarity, snapshot: ContextSnapshot): String {
        return "사용자가 [${entity.entityLabel}]과(와) 친숙합니다 (${entity.totalEncounters}회). " +
                "이 대상에 대한 흥미로운 과학적 사실을 1-2문장으로 알려주세요. " +
                "일반인이 모르는 놀라운 메커니즘이나 연구 결과를 우선하세요."
    }

    private fun buildArtisticPrompt(entity: EntityFamiliarity, snapshot: ContextSnapshot): String {
        return "사용자가 [${entity.entityLabel}]을(를) 보고 있습니다 (${entity.totalEncounters}회 경험). " +
                "이 장면/대상을 예술적 관점에서 1-2문장으로 재해석하세요. " +
                "빛, 구도, 색감, 또는 유명 예술 작품과의 연결을 시도하세요."
    }

    private fun buildHistoricalPrompt(entity: EntityFamiliarity, snapshot: ContextSnapshot): String {
        return "사용자가 있는 장소/대상 [${entity.entityLabel}]에 대한 역사적 맥락을 1-2문장으로 제공하세요. " +
                "100년 전 이 장소의 모습, 또는 이 대상의 역사적 기원/변천을 상상하게 하세요."
    }

    private fun buildChallengePrompt(entity: EntityFamiliarity, snapshot: ContextSnapshot): String {
        return "사용자에게 관찰력 도전을 제시하세요. [${entity.entityLabel}] 주변에서 " +
                "평소 눈치채지 못한 것을 발견하게 하는 질문을 1문장으로 던지세요. " +
                "답을 주지 말고, 사고를 자극하세요."
    }

    private fun buildMemoryPrimePrompt(entity: EntityFamiliarity, snapshot: ContextSnapshot): String {
        return "[${entity.entityLabel}]과(와) 관련된 사용자의 과거 기억을 환기하는 질문을 1문장으로 던지세요. " +
                "'여기서 언제 ~했는지 기억나세요?', '처음 ~을 봤을 때 어떤 느낌이었나요?' 형태로. " +
                "사용자가 스스로 기억을 떠올리게 유도하세요."
    }

    private fun buildCulturalPrompt(entity: EntityFamiliarity, snapshot: ContextSnapshot): String {
        return "[${entity.entityLabel}]에 대한 문화적/상징적 의미를 1-2문장으로 제시하세요. " +
                "동양/서양 문화에서의 상징, 풍속, 관습과 연결하세요."
    }

    private fun buildNaturePrompt(entity: EntityFamiliarity, snapshot: ContextSnapshot): String {
        return "[${entity.entityLabel}]을(를) 자연 생태계의 관점에서 1-2문장으로 설명하세요. " +
                "계절 변화, 생태적 역할, 또는 환경과의 상호작용을 중심으로."
    }
}

data class TokenAllocation(
    val analysisTokenBudget: Int,
    val noveltyTokenBudget: Int,
    val savedTokens: Int,
    val reason: String
)

data class NoveltyPrompt(
    val category: String,
    val prompt: String,
    val context: String,
    val maxTokens: Int
)
