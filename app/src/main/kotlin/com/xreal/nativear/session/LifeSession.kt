package com.xreal.nativear.session

import java.util.UUID

/**
 * LifeSession — 연속 활동 기간 단위 세션.
 *
 * 메모리, 결정 로그를 session_id로 묶어 "오전 수업 세션 요약" 같은
 * 세션 단위 조회/요약을 가능하게 한다.
 *
 * DB: life_sessions 테이블 (UnifiedMemoryDatabase v18 마이그레이션)
 */
data class LifeSession(
    val id: String = UUID.randomUUID().toString(),
    val situation: String? = null,          // 선택적 상황 설명 (예: "수업", "러닝", "여행")
    val startAt: Long = System.currentTimeMillis(),
    val endAt: Long? = null,
    val summary: String? = null,            // endSession(generateSummary=true) 시 생성
    val memoryCount: Int = 0                // 이 세션 동안 저장된 메모리 수
) {
    val isActive: Boolean get() = endAt == null
    val durationMs: Long get() = (endAt ?: System.currentTimeMillis()) - startAt
    val durationMinutes: Long get() = durationMs / 60_000L
}
