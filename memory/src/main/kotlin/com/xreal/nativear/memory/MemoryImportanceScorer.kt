package com.xreal.nativear.memory

import com.xreal.nativear.memory.api.MemoryRecord

/**
 * MemoryImportanceScorer — 규칙 기반 메모리 중요도 계산기.
 *
 * ## 점수 구성 (합산 후 0.0~1.0 clamp)
 * - Role 기반 기본점수: USER=0.70, WHISPER=0.65, GEMINI=0.60,
 *                       SYSTEM_SUMMARY=0.50, PERSONA_SUMMARY=0.50, CAMERA=0.35
 * - 내용 길이 보너스: 50~300자 = +0.08 (너무 짧거나 긴 건 평범)
 * - GPS 있음: +0.08 (공간 맥락 있는 기억 = 더 가치 있음)
 * - 질문 포함 (?): +0.07 (호기심/사고 유발)
 * - 숫자/수치 포함: +0.05 (구체적 데이터)
 * - 중요도 키워드 포함: +0.05
 *
 * AI 호출 없음 — 저장 시점 즉시 계산, 비용 0.
 */
class MemoryImportanceScorer {

    /**
     * MemoryRecord의 importance_score 계산 (0.0~1.0).
     */
    fun score(record: MemoryRecord): Float {
        var s = roleBase(record.role)
        s += lengthBonus(record.content)
        s += if (record.latitude != null && record.longitude != null) 0.08f else 0f
        s += if (record.content.contains('?')) 0.07f else 0f
        s += if (record.content.any { it.isDigit() }) 0.05f else 0f
        s += if (IMPORTANCE_KEYWORDS.any { record.content.contains(it) }) 0.05f else 0f
        return s.coerceIn(0f, 1f)
    }

    private fun roleBase(role: String): Float = when (role.uppercase()) {
        "USER"             -> 0.70f
        "WHISPER"          -> 0.65f
        "GEMINI", "AI"     -> 0.60f
        "SYSTEM_SUMMARY"   -> 0.50f
        "PERSONA_SUMMARY"  -> 0.50f
        "CAMERA"           -> 0.35f
        else               -> 0.50f
    }

    private fun lengthBonus(content: String): Float {
        val len = content.length
        return if (len in 50..300) 0.08f else 0f
    }

    companion object {
        private val IMPORTANCE_KEYWORDS = listOf(
            "중요", "반드시", "기억", "목표", "결정", "계획", "약속", "마감", "주의",
            "important", "must", "remember", "goal", "decision", "plan", "deadline"
        )
    }
}
