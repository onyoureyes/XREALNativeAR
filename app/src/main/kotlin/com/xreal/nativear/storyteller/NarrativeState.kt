package com.xreal.nativear.storyteller

/**
 * NarrativeState — Storyteller 내러티브 상태 데이터 모델.
 *
 * 하루를 하나의 "이야기"로 구성:
 * - DayStory: 하루 전체 (여러 Chapter)
 * - Chapter: 상황 전환 단위 (출근, 수업, 점심 등)
 * - StoryBeat: 개별 순간 (감지된 이벤트 → 내러티브 조각)
 */

/** 내러티브 beat 유형 */
enum class BeatType {
    /** 상황 전환 감지 (새 챕터 시작) */
    SCENE_TRANSITION,
    /** 감정 변화 포착 */
    EMOTIONAL_SHIFT,
    /** 주목할 만한 시각 이벤트 */
    VISUAL_HIGHLIGHT,
    /** 의미 있는 대화 */
    CONVERSATION_MOMENT,
    /** 신체 활동 변화 */
    PHYSICAL_ACTIVITY,
    /** 주기적 반성 (리플렉션) */
    PERIODIC_REFLECTION,
    /** 하루 마무리 요약 */
    END_OF_DAY_SUMMARY
}

/** 개별 내러티브 조각 */
data class StoryBeat(
    val timestamp: Long = System.currentTimeMillis(),
    val type: BeatType,
    /** AI가 생성한 내러티브 텍스트 (1~3문장) */
    val narrative: String,
    /** 생성 시점 컨텍스트 요약 */
    val contextSummary: String,
    /** 감정 톤 ("reflective", "joyful", "curious", "calm" 등) */
    val emotionalTone: String? = null,
    /** 관련 장소명 */
    val placeName: String? = null
)

/** 하나의 상황 구간 (챕터) */
data class Chapter(
    val id: String,
    val startedAt: Long = System.currentTimeMillis(),
    var endedAt: Long? = null,
    /** 챕터 제목 (AI 생성 또는 상황명) */
    val title: String,
    /** 소속된 beat 목록 */
    val beats: MutableList<StoryBeat> = mutableListOf(),
    /** 시작 시 상황 */
    val situation: String
) {
    val isActive: Boolean get() = endedAt == null
    val duration: Long get() = (endedAt ?: System.currentTimeMillis()) - startedAt
}

/** 진행 중인 대화 스레드 (StoryBeat과 연결될 수 있음) */
data class ConversationThread(
    val startedAt: Long = System.currentTimeMillis(),
    val topic: String,
    val participantCount: Int = 0,
    var messageCount: Int = 0
)

/** 하루 전체 내러티브 */
data class DayStory(
    val date: String,  // "2026-03-09"
    val chapters: MutableList<Chapter> = mutableListOf(),
    var morningTheme: String? = null,  // 하루 시작 시 AI가 설정한 테마
    var eveningSummary: String? = null  // 하루 마무리 요약
) {
    val currentChapter: Chapter? get() = chapters.lastOrNull { it.isActive }
    val totalBeats: Int get() = chapters.sumOf { it.beats.size }
}
