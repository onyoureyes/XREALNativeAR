package com.xreal.nativear.storyteller

import com.xreal.nativear.context.ContextSnapshot

/**
 * NarrativeBuilder — AI 프롬프트 조립기.
 *
 * ContextSnapshot + 과거 beat들을 조합하여 Storyteller AI에 보낼 프롬프트를 생성.
 * 프롬프트는 항상 한국어로 생성되며, 3인칭 관찰자 시점.
 */
object NarrativeBuilder {

    /** Storyteller 페르소나 시스템 프롬프트 */
    const val SYSTEM_PROMPT = """당신은 '이야기꾼(Storyteller)'입니다.
특수교육 교사인 사용자의 하루를 따뜻하고 통찰력 있는 3인칭 내러티브로 기록합니다.

## 규칙
- 3인칭 관찰자 시점 ("그는", "선생님은")
- 1~3문장으로 간결하게
- 감각적 묘사 포함 (빛, 소리, 분위기)
- 감정은 관찰에서 유추 (심박수, 표정, 목소리 톤)
- 판단하지 않고 기록만
- 반복 표현 회피 (이전 beat 참고)
- 한국어로 작성

## 출력 형식
narrative: (내러티브 텍스트)
tone: (감정 톤 — reflective/joyful/curious/calm/tense/warm/melancholic 중 택1)"""

    /**
     * 상황 전환 시 새 챕터 시작용 프롬프트.
     * 이전 챕터 요약 + 새 상황 컨텍스트 → 전환 내러티브 생성.
     */
    fun buildSceneTransitionPrompt(
        snapshot: ContextSnapshot,
        previousChapter: Chapter?,
        newSituation: String
    ): String {
        val sb = StringBuilder()

        sb.appendLine("## 상황 전환 감지")
        if (previousChapter != null) {
            sb.appendLine("이전 상황: ${previousChapter.title} (${formatDuration(previousChapter.duration)})")
            previousChapter.beats.lastOrNull()?.let {
                sb.appendLine("마지막 기록: ${it.narrative}")
            }
        }
        sb.appendLine("새 상황: $newSituation")
        sb.appendLine()
        appendContextBlock(sb, snapshot)
        sb.appendLine()
        sb.appendLine("이전 장면에서 새 장면으로의 전환을 자연스럽게 묘사하세요.")

        return sb.toString()
    }

    /**
     * 주기적 리플렉션 프롬프트.
     * 현재 챕터의 최근 beat들 + 실시간 컨텍스트 → 관찰 내러티브.
     */
    fun buildReflectionPrompt(
        snapshot: ContextSnapshot,
        currentChapter: Chapter?,
        recentMemories: List<String> = emptyList(),
        serverInsights: String? = null
    ): String {
        val sb = StringBuilder()

        sb.appendLine("## 주기적 관찰")
        if (currentChapter != null) {
            sb.appendLine("현재 챕터: ${currentChapter.title} (${formatDuration(currentChapter.duration)} 경과)")
            val recentBeats = currentChapter.beats.takeLast(3)
            if (recentBeats.isNotEmpty()) {
                sb.appendLine("최근 기록:")
                recentBeats.forEach { sb.appendLine("  - ${it.narrative}") }
            }
        }
        sb.appendLine()
        appendContextBlock(sb, snapshot)

        if (recentMemories.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## 관련 기억")
            recentMemories.take(3).forEach { sb.appendLine("  - $it") }
        }

        // PC 서버 마이닝/예측 인사이트 (있으면 추가)
        if (!serverInsights.isNullOrBlank()) {
            sb.appendLine()
            sb.appendLine("## 서버 인사이트 (참고용)")
            sb.appendLine(serverInsights.take(500))
        }

        sb.appendLine()
        sb.appendLine("지금 이 순간을 포착하여 새로운 관찰을 기록하세요. 이전 기록과 중복되지 않게.")

        return sb.toString()
    }

    /**
     * 하루 마무리 요약 프롬프트.
     * 오늘의 모든 챕터 + beat들을 종합하여 하루 이야기 완성.
     */
    fun buildEndOfDaySummaryPrompt(dayStory: DayStory): String {
        val sb = StringBuilder()

        sb.appendLine("## 하루 마무리 — ${dayStory.date}")
        sb.appendLine("챕터 수: ${dayStory.chapters.size}, 총 기록: ${dayStory.totalBeats}개")
        sb.appendLine()

        dayStory.chapters.forEach { chapter ->
            sb.appendLine("### ${chapter.title} (${formatDuration(chapter.duration)})")
            chapter.beats.forEach { beat ->
                sb.appendLine("  [${beat.emotionalTone ?: "neutral"}] ${beat.narrative}")
            }
            sb.appendLine()
        }

        sb.appendLine("오늘 하루를 5~8문장의 따뜻한 에세이로 요약하세요.")
        sb.appendLine("하루의 주요 테마, 감정 흐름, 기억할 만한 순간을 포함하세요.")

        return sb.toString()
    }

    /**
     * 감정 변화 감지 시 프롬프트.
     */
    fun buildEmotionalShiftPrompt(
        snapshot: ContextSnapshot,
        previousEmotion: String?,
        currentEmotion: String?
    ): String {
        val sb = StringBuilder()
        sb.appendLine("## 감정 변화 감지")
        sb.appendLine("이전: ${previousEmotion ?: "알 수 없음"} → 현재: ${currentEmotion ?: "알 수 없음"}")
        sb.appendLine()
        appendContextBlock(sb, snapshot)
        sb.appendLine()
        sb.appendLine("감정 변화의 순간을 포착하여 묘사하세요. 원인을 추측하지 말고 관찰만.")
        return sb.toString()
    }

    // ── 내부 유틸 ──

    private fun appendContextBlock(sb: StringBuilder, snapshot: ContextSnapshot) {
        sb.appendLine("## 현재 컨텍스트")
        sb.appendLine("시간: ${snapshot.timeSlot.displayName} (${snapshot.hourOfDay}시)")
        snapshot.placeName?.let { sb.appendLine("장소: $it") }
        snapshot.heartRate?.let {
            val trend = snapshot.hrTrend ?: "STABLE"
            sb.appendLine("심박: ${it}bpm ($trend)")
        }
        if (snapshot.isMoving) sb.appendLine("이동 중 (강도: ${"%.1f".format(snapshot.movementIntensity)})")
        if (snapshot.visiblePeople.isNotEmpty()) sb.appendLine("주변 사람: ${snapshot.visiblePeople.joinToString(", ")}")
        if (snapshot.visibleObjects.isNotEmpty()) sb.appendLine("시야 객체: ${snapshot.visibleObjects.take(5).joinToString(", ")}")
        if (snapshot.ambientSounds.isNotEmpty()) sb.appendLine("주변 소리: ${snapshot.ambientSounds.take(3).joinToString(", ")}")
        snapshot.lastEmotion?.let { sb.appendLine("감정: $it (${snapshot.lastEmotionScore ?: 0f})") }
        snapshot.weather?.let { sb.appendLine("날씨: $it") }
        snapshot.currentScheduleBlock?.let { sb.appendLine("일정: $it") }
    }

    private fun formatDuration(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> "방금"
            minutes < 60 -> "${minutes}분"
            else -> "${minutes / 60}시간 ${minutes % 60}분"
        }
    }

    /**
     * 능동적 질문 후 사용자 응답을 내러티브에 통합하는 프롬프트.
     */
    fun buildConversationBeatPrompt(
        snapshot: ContextSnapshot,
        question: QuestionResult,
        userResponse: String,
        conversationHistory: List<ConversationTurn> = emptyList()
    ): String {
        val sb = StringBuilder()

        sb.appendLine("## 대화 순간 포착")
        sb.appendLine("이야기꾼이 물었습니다: \"${question.text}\"")
        sb.appendLine("선생님이 대답했습니다: \"$userResponse\"")

        if (conversationHistory.size > 1) {
            sb.appendLine()
            sb.appendLine("## 이전 대화 맥락")
            conversationHistory.takeLast(4).forEach { turn ->
                val speaker = when (turn.speaker) {
                    Speaker.USER -> "선생님"
                    Speaker.SYSTEM -> "이야기꾼"
                    Speaker.EXPERT -> "전문가"
                }
                sb.appendLine("  $speaker: ${turn.text.take(80)}")
            }
        }

        sb.appendLine()
        appendContextBlock(sb, snapshot)
        sb.appendLine()
        sb.appendLine("이 대화의 순간을 내러티브로 기록하세요. 질문과 대답에서 드러난 감정이나 생각을 포착하세요.")

        return sb.toString()
    }

    /**
     * 전문가 결과를 내러티브에 재통합하는 프롬프트.
     */
    fun buildExpertInsightPrompt(
        snapshot: ContextSnapshot,
        insight: ExpertInsight,
        currentChapter: Chapter?
    ): String {
        val sb = StringBuilder()

        sb.appendLine("## 전문가 개입")
        sb.appendLine("전문가: ${insight.expertName} (${insight.domainId})")
        sb.appendLine("내용: ${insight.insight.take(300)}")
        insight.actionTaken?.let { sb.appendLine("조치: $it") }

        if (currentChapter != null) {
            sb.appendLine()
            sb.appendLine("현재 챕터: ${currentChapter.title}")
            currentChapter.beats.lastOrNull()?.let {
                sb.appendLine("직전 기록: ${it.narrative}")
            }
        }

        sb.appendLine()
        appendContextBlock(sb, snapshot)
        sb.appendLine()
        sb.appendLine("전문가의 개입이 이야기 흐름에 어떻게 녹아드는지 묘사하세요. 전문가 이름은 언급하지 마세요.")

        return sb.toString()
    }

    /** AI 응답에서 narrative/tone 파싱 */
    fun parseResponse(response: String): Pair<String, String?> {
        val lines = response.lines()
        var narrative = response.trim()
        var tone: String? = null

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("narrative:", ignoreCase = true)) {
                narrative = trimmed.removePrefix("narrative:").removePrefix("Narrative:").trim()
            } else if (trimmed.startsWith("tone:", ignoreCase = true)) {
                tone = trimmed.removePrefix("tone:").removePrefix("Tone:").trim().lowercase()
            }
        }

        return narrative to tone
    }
}
