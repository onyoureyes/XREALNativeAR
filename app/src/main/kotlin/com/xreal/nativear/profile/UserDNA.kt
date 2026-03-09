package com.xreal.nativear.profile

/**
 * UserDNA — 사용자 의사결정 성향 프로필.
 *
 * ## 역할
 * - 사용자의 AI 활용 스타일을 0.0~1.0 특성 점수로 인코딩
 * - PersonaManager 10번째 레이어로 Gemini 시스템 프롬프트에 주입
 * - FeedbackSessionManager 피드백 분석 후 점진적 업데이트 (lerp 10%)
 *
 * ## 초기값 (사용자 선호 직접 반영)
 * - expertWeight=0.90: 전문가 의견 기반 의사결정 중시
 * - dataDrivenWeight=0.90: 데이터/DB 기반 결정 선호
 * - primingPreference=0.80: 답 직접 제공보다 스스로 해법 도출 유도 선호
 * - aiAutonomyTrust=0.85: AI 재량 자율 조정 신뢰
 * - surpriseAppetite=0.70: 예상치 못한 결과/인사이트 환영
 * - statisticalSummaryWeight=0.85: 통계 요약 → 다음 계획 연결 선호
 * - qualityOverCost=0.85: 비용보다 품질/양질 지원 우선
 *
 * ## 저장 위치
 * structured_data (domain="user_dna", data_key=특성명) — 각 특성별 1개 레코드
 */
data class UserDNA(
    val expertWeight: Float = 0.90f,
    val dataDrivenWeight: Float = 0.90f,
    val primingPreference: Float = 0.80f,
    val aiAutonomyTrust: Float = 0.85f,
    val surpriseAppetite: Float = 0.70f,
    val statisticalSummaryWeight: Float = 0.85f,
    val qualityOverCost: Float = 0.85f,
    val updatedAt: Long = System.currentTimeMillis()
)
