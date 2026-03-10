package com.xreal.nativear.storyteller

import android.util.Log
import com.xreal.nativear.context.ContextSnapshot
import com.xreal.nativear.context.LifeSituation
import com.xreal.nativear.policy.PolicyReader

/**
 * ProactiveQuestionEngine — 맥락 기반 능동적 질문 생성.
 *
 * 이야기꾼의 핵심 차별점: 시스템이 먼저 질문한다.
 * - 상황 전환 시 → 새 상황에 대한 질문
 * - 감정 변화 시 → 감정 확인 질문
 * - 일정/루틴 기반 → 하루 계획, 준비 상태 질문
 * - 대화 흐름 기반 → 이전 대화 이어가기
 *
 * ## 질문 타이밍 제어
 * - 최소 간격 (기본 10분) 미만이면 억제
 * - DORMANT/SLEEPING/AWAKENING에서는 질문 안 함
 * - 집중 작업 중에는 질문 억제 (TEACHING, FOCUSED_TASK)
 */
class ProactiveQuestionEngine {
    companion object {
        private const val TAG = "ProactiveQuestion"

        private val MIN_QUESTION_INTERVAL_MS: Long get() =
            PolicyReader.getLong("storyteller.min_question_interval_ms", 600_000L)  // 10분
        private val MAX_QUESTIONS_PER_HOUR: Int get() =
            PolicyReader.getInt("storyteller.max_questions_per_hour", 4)
    }

    private var lastQuestionTime: Long = 0
    private val questionsThisHour = mutableListOf<Long>()
    private var lastConversationTopic: String? = null

    /**
     * 질문 생성 시도. 타이밍/상황 조건 불충족 시 null 반환.
     *
     * @param snapshot 현재 컨텍스트
     * @param currentSituation 현재 상황
     * @param previousSituation 이전 상황 (전환 감지용)
     * @param recentBeats 최근 beat (반복 방지)
     * @param conversationHistory 진행 중 대화 (이어가기)
     * @return 질문 텍스트 + 질문 유형, 또는 null
     */
    fun tryGenerateQuestion(
        snapshot: ContextSnapshot,
        currentSituation: LifeSituation,
        previousSituation: LifeSituation? = null,
        recentBeats: List<StoryBeat> = emptyList(),
        conversationHistory: List<ConversationTurn> = emptyList()
    ): QuestionResult? {
        // 타이밍 게이트
        val now = System.currentTimeMillis()
        if (now - lastQuestionTime < MIN_QUESTION_INTERVAL_MS) return null

        // 시간당 상한
        questionsThisHour.removeAll { now - it > 3_600_000L }
        if (questionsThisHour.size >= MAX_QUESTIONS_PER_HOUR) return null

        // 상황별 억제 (집중 필요한 상황에서는 질문 안 함)
        if (currentSituation in SUPPRESS_SITUATIONS) return null

        // 질문 생성 (우선순위순)
        val question = generateSituationTransitionQuestion(snapshot, currentSituation, previousSituation)
            ?: generateConversationFollowUp(conversationHistory, snapshot)
            ?: generateEmotionalCheckIn(snapshot, recentBeats)
            ?: generateScheduleBasedQuestion(snapshot, currentSituation)
            ?: generateTimeBasedQuestion(snapshot, currentSituation)
            ?: return null

        lastQuestionTime = now
        questionsThisHour.add(now)
        lastConversationTopic = question.topic
        Log.d(TAG, "질문 생성: [${question.type}] ${question.text}")

        return question
    }

    // ── 질문 생성 전략 ──

    /** 상황 전환 시 질문 (가장 높은 우선순위) */
    private fun generateSituationTransitionQuestion(
        snapshot: ContextSnapshot,
        current: LifeSituation,
        previous: LifeSituation?
    ): QuestionResult? {
        if (previous == null || previous == current) return null

        val text = when (current) {
            LifeSituation.MORNING_ROUTINE -> pickRandom(
                "좋은 아침이에요. 오늘 기분이 어때요?",
                "일어났군요! 어젯밤 잘 잤어요?",
                "새로운 하루가 시작됐어요. 오늘 특별한 계획이 있나요?"
            )
            LifeSituation.COMMUTING -> when (previous) {
                LifeSituation.MORNING_ROUTINE -> pickRandom(
                    "출근길이네요. 오늘 학교에서 뭘 할 예정이에요?",
                    "운전 조심하세요. 오늘 수업 준비는 됐나요?"
                )
                LifeSituation.TEACHING -> pickRandom(
                    "퇴근이군요. 오늘 수업은 어땠어요?",
                    "수고하셨어요. 돌아가는 길에 뭐 할 생각이에요?"
                )
                else -> "이동 중이네요. 어디 가는 길이에요?"
            }
            LifeSituation.TEACHING -> pickRandom(
                "수업 시작이네요. 오늘 아이들 상태는 어때 보여요?",
                "교실에 왔군요. 오늘 수업에서 기대되는 게 있어요?"
            )
            LifeSituation.LUNCH_BREAK -> pickRandom(
                "점심시간이에요! 뭐 먹을 거예요?",
                "오전 수업이 끝났네요. 피곤하지 않아요?"
            )
            LifeSituation.IN_MEETING -> "회의가 시작되나 봐요. 어떤 안건이에요?"
            LifeSituation.RUNNING -> pickRandom(
                "달리기 시작이군요! 오늘 목표가 있어요?",
                "좋은 컨디션이에요? 몸 상태는 어때요?"
            )
            LifeSituation.RELAXING_HOME -> pickRandom(
                "집에 왔군요. 오늘 하루 수고하셨어요.",
                "쉬는 시간이네요. 뭐 하면서 쉴 거예요?"
            )
            LifeSituation.EVENING_WIND_DOWN -> pickRandom(
                "하루를 마무리하는 시간이네요. 오늘 가장 기억에 남는 순간이 뭐예요?",
                "오늘 뭐가 제일 좋았어요?"
            )
            LifeSituation.SOCIAL_GATHERING -> "사람들이랑 모였네요! 무슨 모임이에요?"
            LifeSituation.GUITAR_PRACTICE -> "기타 연습이군요! 요즘 무슨 곡 치고 있어요?"
            else -> return null
        }

        return QuestionResult(
            text = text,
            type = QuestionType.SITUATION_TRANSITION,
            topic = "상황_전환_${current.name}",
            expectedResponseType = ResponseType.FREE_FORM
        )
    }

    /** 진행 중 대화 이어가기 */
    private fun generateConversationFollowUp(
        history: List<ConversationTurn>,
        snapshot: ContextSnapshot
    ): QuestionResult? {
        if (history.isEmpty()) return null
        val lastTurn = history.lastOrNull() ?: return null

        // 마지막 대화가 10분 이내이고 사용자 발화였으면 이어가기
        val elapsed = System.currentTimeMillis() - lastTurn.timestamp
        if (elapsed > 600_000L || lastTurn.speaker != Speaker.USER) return null

        return QuestionResult(
            text = "[대화 이어가기] ${lastTurn.topic ?: "이전 대화"}에 대해 더 이야기해볼까요?",
            type = QuestionType.CONVERSATION_FOLLOWUP,
            topic = lastTurn.topic ?: "이전_대화",
            expectedResponseType = ResponseType.YES_NO,
            aiPromptHint = "사용자의 이전 발화: \"${lastTurn.text.take(100)}\"\n이 주제를 자연스럽게 이어가세요."
        )
    }

    /** 감정 확인 질문 */
    private fun generateEmotionalCheckIn(
        snapshot: ContextSnapshot,
        recentBeats: List<StoryBeat>
    ): QuestionResult? {
        val emotion = snapshot.lastEmotion ?: return null
        val score = snapshot.lastEmotionScore ?: return null

        // 강한 감정(긍정/부정)일 때만 질문
        if (score < 0.6f) return null

        // 최근 beat에서 이미 감정 관련 beat가 있으면 스킵
        val recentEmotionBeat = recentBeats.any {
            it.type == BeatType.EMOTIONAL_SHIFT &&
            System.currentTimeMillis() - it.timestamp < 300_000L
        }
        if (recentEmotionBeat) return null

        val text = when {
            emotion.contains("happy", true) || emotion.contains("joy", true) ->
                "기분이 좋아 보여요! 무슨 좋은 일 있었어요?"
            emotion.contains("sad", true) || emotion.contains("upset", true) ->
                "좀 힘들어 보여요. 괜찮아요? 이야기하고 싶은 거 있어요?"
            emotion.contains("angry", true) || emotion.contains("frustrat", true) ->
                "좀 긴장된 것 같아요. 무슨 일 있었어요?"
            emotion.contains("surprise", true) ->
                "뭔가 놀란 것 같은데, 무슨 일이에요?"
            emotion.contains("fear", true) || emotion.contains("anxious", true) ->
                "걱정되는 게 있어요? 도움이 필요하면 말해주세요."
            else -> return null
        }

        return QuestionResult(
            text = text,
            type = QuestionType.EMOTIONAL_CHECKIN,
            topic = "감정_${emotion}",
            expectedResponseType = ResponseType.FREE_FORM,
            aiPromptHint = "감지된 감정: $emotion (강도: $score). 공감하며 자연스럽게 대화하세요."
        )
    }

    /** 일정 기반 질문 */
    private fun generateScheduleBasedQuestion(
        snapshot: ContextSnapshot,
        situation: LifeSituation
    ): QuestionResult? {
        val schedule = snapshot.currentScheduleBlock ?: return null

        return QuestionResult(
            text = "곧 '$schedule' 일정이 있네요. 준비는 됐어요?",
            type = QuestionType.SCHEDULE_BASED,
            topic = "일정_${schedule.take(20)}",
            expectedResponseType = ResponseType.YES_NO,
            aiPromptHint = "다가오는 일정: $schedule. 준비 상태를 확인하고 필요하면 도움을 제안하세요."
        )
    }

    /** 시간대별 기본 질문 (최저 우선순위) */
    private fun generateTimeBasedQuestion(
        snapshot: ContextSnapshot,
        situation: LifeSituation
    ): QuestionResult? {
        // 2시간에 한 번 정도만 시간 기반 질문
        val hourSlot = snapshot.hourOfDay / 2
        val lastHourSlot = if (lastQuestionTime > 0) {
            java.util.Calendar.getInstance().apply { timeInMillis = lastQuestionTime }
                .get(java.util.Calendar.HOUR_OF_DAY) / 2
        } else -1
        if (hourSlot == lastHourSlot) return null

        val text = when (snapshot.hourOfDay) {
            in 10..11 -> "오전이 거의 지나가네요. 지금까지 어땠어요?"
            in 14..15 -> "오후가 시작됐어요. 에너지는 괜찮아요?"
            in 16..17 -> "하루가 거의 끝나가네요. 오늘 마무리할 게 있어요?"
            else -> return null
        }

        return QuestionResult(
            text = text,
            type = QuestionType.TIME_BASED,
            topic = "시간_${snapshot.hourOfDay}시",
            expectedResponseType = ResponseType.FREE_FORM
        )
    }

    // ── 유틸 ──

    private fun pickRandom(vararg options: String): String =
        options[(System.currentTimeMillis() % options.size).toInt()]

    private val SUPPRESS_SITUATIONS = setOf(
        LifeSituation.SLEEPING,
        LifeSituation.SLEEPING_PREP,
        LifeSituation.PHONE_CALL,
        LifeSituation.UNKNOWN
    )
}

// ── 질문 결과 데이터 ──

data class QuestionResult(
    val text: String,
    val type: QuestionType,
    val topic: String,
    val expectedResponseType: ResponseType = ResponseType.FREE_FORM,
    val aiPromptHint: String? = null  // AI가 대화를 이어갈 때 참고할 힌트
)

enum class QuestionType {
    SITUATION_TRANSITION,   // 상황 전환 시
    CONVERSATION_FOLLOWUP,  // 이전 대화 이어가기
    EMOTIONAL_CHECKIN,      // 감정 확인
    SCHEDULE_BASED,         // 일정 관련
    TIME_BASED,             // 시간대별
    EXPERT_RESULT           // 전문가 결과 공유
}

enum class ResponseType {
    FREE_FORM,  // 자유 응답
    YES_NO,     // 예/아니오
    CHOICE      // 선택지
}

// ── 대화 맥락 데이터 ──

enum class Speaker { USER, SYSTEM, EXPERT }

data class ConversationTurn(
    val timestamp: Long = System.currentTimeMillis(),
    val speaker: Speaker,
    val text: String,
    val topic: String? = null,
    val questionType: QuestionType? = null  // 시스템 질문이었다면 유형
)
