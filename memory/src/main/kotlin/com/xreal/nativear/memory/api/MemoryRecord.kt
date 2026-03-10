package com.xreal.nativear.memory.api

/**
 * 메모리 시스템의 공용 데이터 타입.
 * Android/DB 의존성 없음 — 순수 Kotlin, JVM 테스트 가능.
 * 모든 소비자는 이 타입만 사용한다.
 */
data class MemoryRecord(
    val id: Long = 0,
    val timestamp: Long,
    val content: String,
    val role: String,
    val level: Int = 0,
    val parentId: Long? = null,
    val importance: Float = 0.5f,
    val metadata: String? = null,
    val personaId: String? = null,
    val sessionId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)

/**
 * 검색 결과. 레코드 + 관련성 점수 + 검색 출처.
 */
data class SearchResult(
    val record: MemoryRecord,
    val score: Float,
    val source: SearchSource = SearchSource.KEYWORD
)

enum class SearchSource {
    SEMANTIC,   // 벡터 유사도 검색
    KEYWORD,    // 텍스트 LIKE 검색
    TEMPORAL,   // 시간 범위 검색
    SPATIAL,    // 공간 범위 검색
    EMOTION     // 감정 기반 검색
}

/**
 * AI가 대화에서 추출한 사실 단위.
 * Mem0 A.U.D.N. 사이클의 입력.
 */
data class ExtractedFact(
    val content: String,
    val category: FactCategory,
    val confidence: Float,
    val emotionValence: Float? = null,
    val relatedEntityIds: List<Long> = emptyList()
)

enum class FactCategory {
    PERSONAL,       // "L5-S1 디스크 환자"
    RELATIONSHIP,   // "김민수는 내 학생"
    PREFERENCE,     // "치킨 좋아함 → 싫어함"
    EVENT,          // "3/5 수업 이탈 사건"
    HABIT,          // "출근길에 커피숍 들림"
    EMOTIONAL       // "김민수 이야기하면 걱정 톤"
}

enum class ConflictResolution {
    ADD,    // 새 사실 추가
    UPDATE, // 기존 사실 수정
    DELETE, // 기존 사실 무효화
    NOOP    // 이미 있음, 변경 없음
}
