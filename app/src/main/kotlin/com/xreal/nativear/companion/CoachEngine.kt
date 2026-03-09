package com.xreal.nativear.companion

import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.context.ContextSnapshot
import com.xreal.nativear.context.LifeSituation
import com.xreal.nativear.context.TimeSlot
import com.xreal.nativear.cadence.UserState
import com.xreal.nativear.goal.IGoalService
import com.xreal.nativear.learning.IOutcomeRecorder
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import com.xreal.nativear.policy.PolicyReader
import kotlinx.coroutines.*
import java.util.UUID

/**
 * CoachEngine: Transforms AI from an information provider into a coach
 * that sharpens the user through challenges, questions, memory priming,
 * and thought stimulation.
 *
 * Coaching philosophy:
 * - Ask questions instead of giving answers
 * - Prime memories so the user recalls them naturally
 * - Provide appropriate challenges to develop observation, thinking, and communication
 * - Guide users toward independent judgment, reducing AI dependency
 *
 * Coaching mode is determined by biometrics + situation + time of day:
 * HIGH_ENERGY → challenging questions, new perspectives
 * LOW_ENERGY → gentle reflection, gratitude prompts
 * FOCUSED → deep thinking induction
 * ACTIVE → observation challenges, environmental awareness
 * SOCIAL → relationship coaching, communication tips
 * STRESSED → breathing/mindfulness, pressure reduction
 */
class CoachEngine(
    private val familiarityEngine: FamiliarityEngine,
    private val relationshipTracker: RelationshipTracker,
    private val goalTracker: IGoalService,
    private val outcomeTracker: IOutcomeRecorder,
    private val database: UnifiedMemoryDatabase,
    private val eventBus: GlobalEventBus,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "CoachEngine"
        private val CHALLENGE_COOLDOWN_MS: Long get() = PolicyReader.getLong("companion.challenge_cooldown_ms", 1_800_000L)
        private val MEMORY_PRIME_COOLDOWN_MS: Long get() = PolicyReader.getLong("companion.memory_prime_cooldown_ms", 1_200_000L)
        private val MAX_ACTIVE_CHALLENGES: Int get() = PolicyReader.getInt("companion.max_active_challenges", 2)
    }

    private var lastChallengeAt = 0L
    private var lastMemoryPrimeAt = 0L
    private val activeChallenges = mutableListOf<Challenge>()
    private var totalChallengesIssued = 0
    private var totalChallengesCompleted = 0
    private var totalMemoryPrimes = 0

    // FocusMode 상태 (lazy inject) — PRIVATE 시 코칭 일시 중지
    private val focusModeManager: com.xreal.nativear.focus.FocusModeManager? by lazy {
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull() } catch (e: Exception) { null }
    }

    // ─── Coaching Mode Determination ───

    fun determineCoachingMode(snapshot: ContextSnapshot): CoachingMode {
        // Priority 1: Stress detection (HRV very low or SpO2 drop)
        val hrv = snapshot.hrv
        val spo2 = snapshot.spo2
        if (hrv != null && hrv < 20f) return CoachingMode.STRESSED
        if (spo2 != null && spo2 < 94) return CoachingMode.STRESSED

        // Priority 2: Social context (people present)
        if (snapshot.visiblePeople.isNotEmpty() ||
            snapshot.currentUserState == UserState.IN_CONVERSATION) {
            return CoachingMode.SOCIAL
        }

        // Priority 3: Active/moving
        val heartRate = snapshot.heartRate
        if (snapshot.isMoving && (heartRate == null || heartRate > 100)) {
            return CoachingMode.ACTIVE
        }
        if (snapshot.currentUserState == UserState.RUNNING) {
            return CoachingMode.ACTIVE
        }

        // Priority 4: Focused state
        if (snapshot.currentUserState == UserState.FOCUSED_TASK) {
            return CoachingMode.FOCUSED
        }
        if (snapshot.headStabilityScore != null && snapshot.headStabilityScore > 0.8f) {
            return CoachingMode.FOCUSED
        }

        // Priority 5: Energy level based on biometrics + time
        val isHighEnergy = when {
            heartRate != null && hrv != null ->
                heartRate in 60..100 && hrv > 40f
            snapshot.timeSlot in listOf(TimeSlot.MORNING, TimeSlot.AFTERNOON) -> true
            else -> false
        }

        return if (isHighEnergy) CoachingMode.HIGH_ENERGY else CoachingMode.LOW_ENERGY
    }

    // ─── Challenge Generation ───

    fun generateChallenge(mode: CoachingMode, snapshot: ContextSnapshot): Challenge? {
        // FocusMode PRIVATE/DND 시 코칭 챌린지 일시 중지
        val fmm = focusModeManager
        if (fmm != null && !fmm.canAIAct(com.xreal.nativear.focus.AITrigger.PROACTIVE_COACHING)) {
            Log.d(TAG, "코칭 챌린지 억제됨 (FocusMode: ${fmm.currentMode})")
            return null
        }

        val now = System.currentTimeMillis()

        // Cooldown check
        if (now - lastChallengeAt < CHALLENGE_COOLDOWN_MS) return null

        // Max active challenges check
        cleanupExpiredChallenges()
        if (activeChallenges.size >= MAX_ACTIVE_CHALLENGES) return null

        val challengeType = selectChallengeType(mode, snapshot)
        val content = buildChallengeContent(challengeType, mode, snapshot) ?: return null
        val difficulty = calculateDifficulty(mode, snapshot)

        val challenge = Challenge(
            id = UUID.randomUUID().toString().take(12),
            type = challengeType,
            content = content,
            difficulty = difficulty,
            estimatedDurationMin = estimateDuration(challengeType, difficulty),
            relatedGoalId = findRelatedGoal(challengeType),
            coachingMode = mode,
            createdAt = now,
            expiresAt = now + getExpirationMs(challengeType)
        )

        activeChallenges.add(challenge)
        lastChallengeAt = now
        totalChallengesIssued++

        Log.d(TAG, "Challenge issued: [${challengeType.name}] difficulty=$difficulty — ${content.take(50)}")
        return challenge
    }

    private fun selectChallengeType(mode: CoachingMode, snapshot: ContextSnapshot): ChallengeType {
        val weights = mutableMapOf<ChallengeType, Float>()
        ChallengeType.entries.forEach { weights[it] = 1.0f }

        // Mode-based weights
        when (mode) {
            CoachingMode.HIGH_ENERGY -> {
                weights[ChallengeType.COGNITIVE] = weights[ChallengeType.COGNITIVE]!! + 2.0f
                weights[ChallengeType.OBSERVATION] = weights[ChallengeType.OBSERVATION]!! + 1.5f
                weights[ChallengeType.CREATIVE] = weights[ChallengeType.CREATIVE]!! + 1.0f
            }
            CoachingMode.LOW_ENERGY -> {
                weights[ChallengeType.REFLECTION] = weights[ChallengeType.REFLECTION]!! + 3.0f
                weights[ChallengeType.MEMORY] = weights[ChallengeType.MEMORY]!! + 2.0f
            }
            CoachingMode.FOCUSED -> {
                weights[ChallengeType.COGNITIVE] = weights[ChallengeType.COGNITIVE]!! + 3.0f
                weights[ChallengeType.CREATIVE] = weights[ChallengeType.CREATIVE]!! + 1.5f
            }
            CoachingMode.ACTIVE -> {
                weights[ChallengeType.OBSERVATION] = weights[ChallengeType.OBSERVATION]!! + 3.0f
                weights[ChallengeType.PHYSICAL] = weights[ChallengeType.PHYSICAL]!! + 2.0f
            }
            CoachingMode.SOCIAL -> {
                weights[ChallengeType.SOCIAL] = weights[ChallengeType.SOCIAL]!! + 4.0f
                weights[ChallengeType.MEMORY] = weights[ChallengeType.MEMORY]!! + 1.0f
            }
            CoachingMode.STRESSED -> {
                weights[ChallengeType.REFLECTION] = weights[ChallengeType.REFLECTION]!! + 3.0f
                // Suppress high-effort challenges when stressed
                weights[ChallengeType.COGNITIVE] = 0.1f
                weights[ChallengeType.PHYSICAL] = 0.1f
            }
        }

        // Environment bonuses
        if (snapshot.visibleObjects.size > 5) {
            weights[ChallengeType.OBSERVATION] = weights[ChallengeType.OBSERVATION]!! + 1.0f
        }
        if (snapshot.familiarLocation) {
            weights[ChallengeType.OBSERVATION] = weights[ChallengeType.OBSERVATION]!! + 0.5f // find new things in familiar place
        }

        // Weighted random selection
        val totalWeight = weights.values.sum()
        var random = (Math.random() * totalWeight).toFloat()
        for ((type, w) in weights) {
            random -= w
            if (random <= 0) return type
        }
        return ChallengeType.OBSERVATION
    }

    private fun buildChallengeContent(
        type: ChallengeType,
        mode: CoachingMode,
        snapshot: ContextSnapshot
    ): String? {
        val objects = snapshot.visibleObjects
        val people = snapshot.visiblePeople
        val place = snapshot.placeName ?: "현재 장소"

        return when (type) {
            ChallengeType.OBSERVATION -> {
                val templates = listOf(
                    "주변에서 평소 눈치채지 못한 것을 하나 찾아보세요.",
                    "지금 보이는 것 중 가장 오래된 것은 무엇일까요?",
                    "이 장소에서 색이 가장 특이한 물건을 찾아보세요.",
                    "지금 들리는 소리 3가지를 구별해보세요.",
                    "주변에서 대칭인 것과 비대칭인 것을 각각 하나씩 찾아보세요."
                )
                if (objects.isNotEmpty()) {
                    "주변의 [${objects.take(3).joinToString(", ")}] 중에서 " +
                    "가장 최근에 바뀐 것을 찾아보세요."
                } else {
                    templates.random()
                }
            }

            ChallengeType.SOCIAL -> {
                if (people.isNotEmpty()) {
                    val person = people.first()
                    val profile = relationshipTracker.getProfileByName(person)
                    if (profile != null && profile.topTopics.isNotEmpty()) {
                        "오늘 ${person}에게 평소 주제(${profile.topTopics.first()}) 대신 " +
                        "새로운 주제로 대화를 시작해보세요."
                    } else {
                        "오늘 만나는 사람에게 진심 어린 질문을 하나 던져보세요."
                    }
                } else {
                    "다음에 누군가를 만나면 그 사람의 오늘 기분을 먼저 물어보세요."
                }
            }

            ChallengeType.COGNITIVE -> {
                val templates = listOf(
                    "지금 보이는 장면에서 10년 후 달라질 것 3가지를 예측해보세요.",
                    "현재 상황을 완전히 반대로 뒤집으면 어떤 모습일까요?",
                    "지금 주변의 물건 중 하나를 다른 용도로 사용한다면?",
                    "이 장소의 설계자가 가장 신경 쓴 부분은 어디일까요?",
                    "지금 보이는 것들의 공통점을 3초 안에 하나 찾아보세요."
                )
                if (objects.size > 2) {
                    "[${objects.take(3).joinToString(", ")}] — 이 세 가지의 " +
                    "의외의 공통점은 무엇일까요?"
                } else {
                    templates.random()
                }
            }

            ChallengeType.PHYSICAL -> {
                val templates = listOf(
                    "다음 100걸음 동안 자세를 의식하며 걸어보세요.",
                    "지금 30초간 깊은 숨을 3번 쉬어보세요.",
                    "주변을 360도 천천히 둘러보세요. 무엇이 눈에 띄나요?",
                    "눈을 감고 10초간 주변 소리에만 집중해보세요.",
                    "1분간 의식적으로 느린 동작으로 움직여보세요."
                )
                templates.random()
            }

            ChallengeType.CREATIVE -> {
                val templates = listOf(
                    "지금 장면을 그림으로 그린다면 어떤 색을 먼저 쓰겠어요?",
                    "이 순간을 한 줄의 시로 표현한다면?",
                    "지금 보이는 풍경에 어울리는 음악은 어떤 장르일까요?",
                    "이 장소를 영화의 한 장면으로 만든다면 장르는?",
                    "주변 소리들을 악기로 바꾼다면 어떤 오케스트라가 될까요?"
                )
                if (objects.isNotEmpty()) {
                    "[${objects.first()}]을(를) 예술 작품으로 만든다면 어떤 형태가 좋을까요?"
                } else {
                    templates.random()
                }
            }

            ChallengeType.MEMORY -> {
                val templates = listOf(
                    "이 장소에서의 첫 기억은 무엇인가요?",
                    "오늘과 같은 날씨였던 특별한 날을 떠올려보세요.",
                    "지금 시간에 가장 많이 했던 활동은 무엇이었나요?",
                    "최근 가장 감사했던 순간은 언제였나요?",
                    "어제와 오늘의 가장 큰 차이점은 무엇인가요?"
                )
                if (snapshot.familiarLocation) {
                    "[$place]에서 처음 왔을 때와 지금, 가장 달라진 것은 무엇인가요?"
                } else {
                    templates.random()
                }
            }

            ChallengeType.REFLECTION -> {
                val templates = listOf(
                    "오늘 하루 중 가장 의미 있었던 순간은 언제였나요?",
                    "지금 이 순간 가장 감사한 것 하나는 무엇인가요?",
                    "오늘 누군가에게 받은 작은 친절이 있었나요?",
                    "이번 주에 새로 알게 된 것이 있나요?",
                    "지금 마음 상태를 날씨로 표현한다면?"
                )
                templates.random()
            }
        }
    }

    // ─── Memory Priming ───

    fun primeMemory(snapshot: ContextSnapshot): MemoryPrime? {
        val now = System.currentTimeMillis()
        if (now - lastMemoryPrimeAt < MEMORY_PRIME_COOLDOWN_MS) return null

        val prime = when {
            // Place-based memory
            snapshot.familiarLocation && snapshot.placeName != null -> {
                buildPlaceMemoryPrime(snapshot)
            }
            // Person-based memory
            snapshot.visiblePeople.isNotEmpty() -> {
                buildPersonMemoryPrime(snapshot)
            }
            // Time-based memory
            snapshot.timeSlot == TimeSlot.EVENING || snapshot.timeSlot == TimeSlot.NIGHT -> {
                buildReflectiveMemoryPrime(snapshot)
            }
            // Object-based memory
            snapshot.visibleObjects.isNotEmpty() -> {
                buildObjectMemoryPrime(snapshot)
            }
            else -> null
        }

        if (prime != null) {
            lastMemoryPrimeAt = now
            totalMemoryPrimes++
            Log.d(TAG, "Memory prime: ${prime.question.take(60)}")
        }

        return prime
    }

    private fun buildPlaceMemoryPrime(snapshot: ContextSnapshot): MemoryPrime {
        val place = snapshot.placeName ?: "이곳"
        val questions = listOf(
            "[$place]에 처음 왔을 때를 기억하시나요? 어떤 느낌이었나요?",
            "[$place]에서 가장 특별했던 순간은 무엇이었나요?",
            "[$place]이 당신에게 어떤 의미를 갖게 되었나요?",
            "[$place]에서 시간이 가장 빨리 흘렀던 때는 언제였나요?"
        )
        return MemoryPrime(
            question = questions.random(),
            relatedMemoryId = null,
            context = "place=${snapshot.placeName}, familiar=${snapshot.familiarLocation}",
            triggerType = MemoryPrimeTrigger.PLACE,
            difficulty = if (snapshot.familiarLocation) 2 else 4
        )
    }

    private fun buildPersonMemoryPrime(snapshot: ContextSnapshot): MemoryPrime {
        val person = snapshot.visiblePeople.first()
        val profile = relationshipTracker.getProfileByName(person)
        val question = if (profile != null && profile.sharedMemories.isNotEmpty()) {
            "${person}님과 함께했던 [${profile.sharedMemories.first()}] 기억나시나요?"
        } else {
            "${person}님을 처음 만났을 때를 기억하시나요? 첫 인상이 어땠나요?"
        }
        return MemoryPrime(
            question = question,
            relatedMemoryId = null,
            context = "person=$person, relationship=${profile?.relationship?.displayName}",
            triggerType = MemoryPrimeTrigger.PERSON,
            difficulty = 3
        )
    }

    private fun buildReflectiveMemoryPrime(snapshot: ContextSnapshot): MemoryPrime {
        val questions = listOf(
            "오늘 하루를 한 단어로 표현한다면 무엇인가요?",
            "오늘 가장 생생하게 기억나는 장면은 무엇인가요?",
            "오늘 예상과 달랐던 일이 있었나요?",
            "오늘 누군가에게 고마웠던 순간이 있었나요?"
        )
        return MemoryPrime(
            question = questions.random(),
            relatedMemoryId = null,
            context = "time=${snapshot.timeSlot.name}, state=${snapshot.currentUserState.name}",
            triggerType = MemoryPrimeTrigger.TIME,
            difficulty = 1
        )
    }

    private fun buildObjectMemoryPrime(snapshot: ContextSnapshot): MemoryPrime {
        val obj = snapshot.visibleObjects.first()
        val entity = familiarityEngine.getEntity(EntityType.OBJECT, obj)
        val question = if (entity != null && entity.totalEncounters > 10) {
            "[$obj]을(를) ${entity.totalEncounters}번이나 봤네요. 처음 봤을 때와 지금 느낌이 어떻게 다른가요?"
        } else {
            "[$obj]을(를) 보면 떠오르는 기억이 있나요?"
        }
        return MemoryPrime(
            question = question,
            relatedMemoryId = null,
            context = "object=$obj, encounters=${entity?.totalEncounters ?: 0}",
            triggerType = MemoryPrimeTrigger.OBJECT,
            difficulty = if (entity != null && entity.totalEncounters > 10) 2 else 4
        )
    }

    // ─── Coaching Intensity Adjustment ───

    fun adjustIntensity(baseChallenge: Challenge, snapshot: ContextSnapshot): Challenge {
        var adjustedDifficulty = baseChallenge.difficulty

        // Fatigue detection: lower difficulty
        val hrv = snapshot.hrv
        val spo2 = snapshot.spo2
        if (hrv != null && hrv < 30f) {
            adjustedDifficulty = (adjustedDifficulty - 1).coerceAtLeast(1)
        }
        if (spo2 != null && spo2 < 96) {
            adjustedDifficulty = (adjustedDifficulty - 1).coerceAtLeast(1)
        }

        // Late night: reduce intensity
        if (snapshot.timeSlot == TimeSlot.LATE_NIGHT || snapshot.timeSlot == TimeSlot.NIGHT) {
            adjustedDifficulty = (adjustedDifficulty - 1).coerceAtLeast(1)
        }

        // High energy morning: can increase
        if (snapshot.timeSlot == TimeSlot.MORNING && hrv != null && hrv > 50f) {
            adjustedDifficulty = (adjustedDifficulty + 1).coerceAtMost(5)
        }

        // Post-exercise: boost achievement feel, lighter challenge
        if (snapshot.heartRate != null && snapshot.heartRate > 120) {
            adjustedDifficulty = (adjustedDifficulty - 1).coerceAtLeast(1)
        }

        return baseChallenge.copy(difficulty = adjustedDifficulty)
    }

    // ─── AI Prompt Style ───

    fun getCoachingPromptStyle(mode: CoachingMode): String {
        return when (mode) {
            CoachingMode.HIGH_ENERGY ->
                "사용자에게 도전적 질문을 던지세요. 답을 주지 말고 스스로 생각하게 하세요. " +
                "관찰력과 추론력을 자극하세요. 톤: 에너지 넘치고 격려하는."

            CoachingMode.LOW_ENERGY ->
                "부드럽게 하루를 돌아보게 하세요. 작은 성취를 칭찬하세요. " +
                "감사와 성찰을 유도하세요. 톤: 따뜻하고 편안한."

            CoachingMode.FOCUSED ->
                "깊은 사고를 유도하는 열린 질문을 하세요. " +
                "현재 집중하고 있는 것과 연결지어 더 넓은 관점을 제시하세요. 톤: 차분하고 깊은."

            CoachingMode.ACTIVE ->
                "이동 중 관찰할 수 있는 짧은 도전을 제시하세요. " +
                "환경 인식과 감각 활용을 자극하세요. 톤: 가볍고 활동적인."

            CoachingMode.SOCIAL ->
                "대인관계 기술을 향상시키는 부드러운 제안을 하세요. " +
                "경청, 공감, 질문 기술을 코칭하세요. 톤: 지지적이고 세심한."

            CoachingMode.STRESSED ->
                "판단 없이 현재에 집중하게 도와주세요. " +
                "호흡이나 감각에 주의를 돌리세요. 도전이나 과제를 제시하지 마세요. " +
                "톤: 평화롭고 안정적인."
        }
    }

    fun getCoachingPromptSection(mode: CoachingMode, snapshot: ContextSnapshot): String {
        val style = getCoachingPromptStyle(mode)
        val challenge = activeChallenges.firstOrNull()
        val effectiveness = outcomeTracker.getOverallStats()

        return buildString {
            appendLine("[COACHING PHILOSOPHY]")
            appendLine("당신은 정보를 주는 AI가 아니라, 사용자를 날카롭게 만드는 코치입니다.")
            appendLine("- 답을 바로 주기보다 질문으로 사고를 자극하세요")
            appendLine("- 사용자의 기억을 환기시켜 스스로 떠올리게 하세요")
            appendLine("- 적절한 도전을 통해 관찰력, 사고력, 소통 능력을 키우세요")
            appendLine("- 사용자가 의존하지 않도록 점점 스스로 판단하게 유도하세요")
            appendLine()
            appendLine("현재 코칭 모드: ${mode.displayName}")
            appendLine("코칭 스타일: $style")

            if (challenge != null) {
                appendLine("진행 중인 도전: [${challenge.type.displayName}] ${challenge.content}")
            }

            if (effectiveness.totalInterventions > 10) {
                appendLine("전체 채택률: ${
                    if (effectiveness.totalInterventions > 0)
                        "%.0f%%".format(effectiveness.followed.toFloat() / effectiveness.totalInterventions * 100)
                    else "N/A"
                }")
            }
        }
    }

    // ─── Challenge Completion ───

    fun completeChallenge(challengeId: String) {
        val challenge = activeChallenges.find { it.id == challengeId }
        if (challenge != null) {
            activeChallenges.remove(challenge)
            totalChallengesCompleted++

            // Record as goal progress if linked
            challenge.relatedGoalId?.let { goalId ->
                scope.launch {
                    try {
                        goalTracker.recordProgress(goalId, 1f, "coach_challenge", challenge.content)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to record challenge as goal progress: ${e.message}")
                    }
                }
            }

            Log.d(TAG, "Challenge completed: [${challenge.type.name}] ${challenge.content.take(40)}")
        }
    }

    fun getActiveChallenges(): List<Challenge> {
        cleanupExpiredChallenges()
        return activeChallenges.toList()
    }

    private fun cleanupExpiredChallenges() {
        val now = System.currentTimeMillis()
        activeChallenges.removeAll { it.expiresAt < now }
    }

    // ─── Helper Methods ───

    private fun calculateDifficulty(mode: CoachingMode, snapshot: ContextSnapshot): Int {
        val baseDifficulty = when (mode) {
            CoachingMode.HIGH_ENERGY -> 3
            CoachingMode.FOCUSED -> 4
            CoachingMode.ACTIVE -> 2
            CoachingMode.SOCIAL -> 3
            CoachingMode.LOW_ENERGY -> 1
            CoachingMode.STRESSED -> 1
        }

        // Adjust by time of day
        val timeBonus = when (snapshot.timeSlot) {
            TimeSlot.MORNING, TimeSlot.AFTERNOON -> 1
            TimeSlot.EVENING -> 0
            TimeSlot.NIGHT, TimeSlot.LATE_NIGHT -> -1
            TimeSlot.EARLY_MORNING -> 0
        }

        return (baseDifficulty + timeBonus).coerceIn(1, 5)
    }

    private fun estimateDuration(type: ChallengeType, difficulty: Int): Int {
        val base = when (type) {
            ChallengeType.OBSERVATION -> 3
            ChallengeType.SOCIAL -> 10
            ChallengeType.COGNITIVE -> 5
            ChallengeType.PHYSICAL -> 3
            ChallengeType.CREATIVE -> 5
            ChallengeType.MEMORY -> 2
            ChallengeType.REFLECTION -> 3
        }
        return base + (difficulty - 1) * 2
    }

    private fun getExpirationMs(type: ChallengeType): Long {
        return when (type) {
            ChallengeType.OBSERVATION -> 60 * 60 * 1000L      // 1 hour
            ChallengeType.SOCIAL -> 4 * 60 * 60 * 1000L       // 4 hours
            ChallengeType.COGNITIVE -> 30 * 60 * 1000L         // 30 min
            ChallengeType.PHYSICAL -> 15 * 60 * 1000L          // 15 min
            ChallengeType.CREATIVE -> 2 * 60 * 60 * 1000L     // 2 hours
            ChallengeType.MEMORY -> 60 * 60 * 1000L            // 1 hour
            ChallengeType.REFLECTION -> 60 * 60 * 1000L        // 1 hour
        }
    }

    private fun findRelatedGoal(challengeType: ChallengeType): String? {
        return try {
            val activeGoals = goalTracker.getActiveGoals(null)
            activeGoals.firstOrNull { goal ->
                val goalText = (goal.title + " " + (goal.description ?: "")).lowercase()
                when (challengeType) {
                    ChallengeType.OBSERVATION -> goalText.contains("관찰") || goalText.contains("주의")
                    ChallengeType.SOCIAL -> goalText.contains("소통") || goalText.contains("관계")
                    ChallengeType.COGNITIVE -> goalText.contains("사고") || goalText.contains("학습")
                    ChallengeType.PHYSICAL -> goalText.contains("운동") || goalText.contains("건강")
                    ChallengeType.CREATIVE -> goalText.contains("창의") || goalText.contains("예술")
                    ChallengeType.MEMORY -> goalText.contains("기억") || goalText.contains("추억")
                    ChallengeType.REFLECTION -> goalText.contains("성찰") || goalText.contains("마음")
                }
            }?.id
        } catch (e: Exception) {
            null
        }
    }

    // ─── Statistics ───

    fun getCoachingStats(): CoachingStats {
        return CoachingStats(
            totalChallengesIssued = totalChallengesIssued,
            totalChallengesCompleted = totalChallengesCompleted,
            totalMemoryPrimes = totalMemoryPrimes,
            activeChallenges = activeChallenges.size,
            completionRate = if (totalChallengesIssued > 0)
                totalChallengesCompleted.toFloat() / totalChallengesIssued else 0f
        )
    }
}

// ─── Data Types ───

data class Challenge(
    val id: String,
    val type: ChallengeType,
    val content: String,
    val difficulty: Int,               // 1-5
    val estimatedDurationMin: Int,
    val relatedGoalId: String?,
    val coachingMode: CoachingMode,
    val createdAt: Long,
    val expiresAt: Long
)

enum class ChallengeType(val displayName: String) {
    OBSERVATION("관찰력 도전"),
    SOCIAL("소통 도전"),
    COGNITIVE("인지 도전"),
    PHYSICAL("신체 도전"),
    CREATIVE("창의적 도전"),
    MEMORY("기억 도전"),
    REFLECTION("성찰 도전")
}

data class MemoryPrime(
    val question: String,
    val relatedMemoryId: Long?,
    val context: String,
    val triggerType: MemoryPrimeTrigger,
    val difficulty: Int                // 1=recent/easy, 5=old/hard
)

enum class MemoryPrimeTrigger {
    PLACE, PERSON, OBJECT, TIME
}

enum class CoachingMode(val displayName: String) {
    HIGH_ENERGY("높은 에너지"),
    LOW_ENERGY("낮은 에너지"),
    FOCUSED("집중"),
    ACTIVE("활동 중"),
    SOCIAL("사교"),
    STRESSED("스트레스")
}

data class CoachingStats(
    val totalChallengesIssued: Int,
    val totalChallengesCompleted: Int,
    val totalMemoryPrimes: Int,
    val activeChallenges: Int,
    val completionRate: Float
)
