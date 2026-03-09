package com.xreal.nativear.profile

import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase

/**
 * UserDNAManager — UserDNA structured_data CRUD + 피드백 기반 점진적 업데이트.
 *
 * ## 저장 구조
 * structured_data table:
 *   domain = "user_dna"
 *   data_key = 특성명 (예: "expertWeight")
 *   value = Float 문자열 (예: "0.90")
 *
 * ## 업데이트 전략
 * - FeedbackSessionManager 분석 결과로 lerp(현재, 신호, 0.1f) 점진 반영
 * - 급격한 변화 방지: 한 번 피드백으로 최대 10% 이동
 *
 * ## Koin 등록
 * AppModule.kt: single { UserDNAManager(database = get()) }
 */
class UserDNAManager(private val database: UnifiedMemoryDatabase) {

    private val TAG = "UserDNAManager"
    private val DOMAIN = "user_dna"

    private val TRAIT_KEYS = listOf(
        "expertWeight", "dataDrivenWeight", "primingPreference",
        "aiAutonomyTrust", "surpriseAppetite", "statisticalSummaryWeight", "qualityOverCost"
    )

    /**
     * DB에서 UserDNA 로드. 없으면 기본값으로 초기화 후 저장.
     */
    fun loadDNA(): UserDNA {
        return try {
            val values = TRAIT_KEYS.associate { key ->
                key to (database.getStructuredDataExact(DOMAIN, key)?.value?.toFloatOrNull() ?: -1f)
            }

            if (values.values.all { it >= 0f }) {
                UserDNA(
                    expertWeight             = values["expertWeight"]!!,
                    dataDrivenWeight         = values["dataDrivenWeight"]!!,
                    primingPreference        = values["primingPreference"]!!,
                    aiAutonomyTrust          = values["aiAutonomyTrust"]!!,
                    surpriseAppetite         = values["surpriseAppetite"]!!,
                    statisticalSummaryWeight = values["statisticalSummaryWeight"]!!,
                    qualityOverCost          = values["qualityOverCost"]!!
                )
            } else {
                Log.i(TAG, "UserDNA 미초기화 → 기본값으로 초기화")
                initializeDefault()
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadDNA 실패, 기본값 반환: ${e.message}")
            UserDNA()
        }
    }

    /**
     * UserDNA 전체 저장.
     */
    fun saveDNA(dna: UserDNA) {
        try {
            val traitMap = mapOf(
                "expertWeight"             to dna.expertWeight,
                "dataDrivenWeight"         to dna.dataDrivenWeight,
                "primingPreference"        to dna.primingPreference,
                "aiAutonomyTrust"          to dna.aiAutonomyTrust,
                "surpriseAppetite"         to dna.surpriseAppetite,
                "statisticalSummaryWeight" to dna.statisticalSummaryWeight,
                "qualityOverCost"          to dna.qualityOverCost
            )
            traitMap.forEach { (key, value) ->
                database.upsertStructuredData(DOMAIN, key, value.toString())
            }
            database.upsertStructuredData(DOMAIN, "updated_at", dna.updatedAt.toString())
            Log.d(TAG, "UserDNA 저장 완료")
        } catch (e: Exception) {
            Log.w(TAG, "saveDNA 실패: ${e.message}")
        }
    }

    /**
     * 피드백 세션 분석 결과로 DNA 점진적 업데이트.
     * lerp(현재값, 신호값, 0.1) — 한 번 피드백으로 최대 10% 변화.
     *
     * @param expertSignal 전문가 의견 필요도 신호 (0.0~1.0)
     * @param dataSignal 데이터 기반 결정 필요도 신호 (0.0~1.0)
     * @param autonomySignal AI 자율성 선호도 신호 (0.0~1.0)
     */
    fun updateFromFeedback(
        expertSignal: Float,
        dataSignal: Float,
        autonomySignal: Float
    ) {
        try {
            val current = loadDNA()
            val updated = current.copy(
                expertWeight     = lerp(current.expertWeight, expertSignal, 0.1f),
                dataDrivenWeight = lerp(current.dataDrivenWeight, dataSignal, 0.1f),
                aiAutonomyTrust  = lerp(current.aiAutonomyTrust, autonomySignal, 0.1f),
                updatedAt        = System.currentTimeMillis()
            )
            saveDNA(updated)
            Log.i(TAG, "UserDNA 업데이트: expert=${updated.expertWeight}, data=${updated.dataDrivenWeight}, autonomy=${updated.aiAutonomyTrust}")
        } catch (e: Exception) {
            Log.w(TAG, "updateFromFeedback 실패: ${e.message}")
        }
    }

    /**
     * PersonaManager 주입용 프롬프트 스니펫 생성.
     * 임계값(0.7) 초과 특성만 명시적으로 포함.
     */
    fun buildDNAPromptLayer(dna: UserDNA = loadDNA()): String? {
        val lines = mutableListOf<String>()
        if (dna.expertWeight > 0.7f)
            lines.add("- 전문가 의견 기반 답변 선호 (근거/출처 명시, 데이터 뒷받침)")
        if (dna.dataDrivenWeight > 0.7f)
            lines.add("- 데이터·통계 기반 분석 선호 (수치 포함, DB 재활용 언급)")
        if (dna.primingPreference > 0.7f)
            lines.add("- 답을 직접 주기보다 사용자가 스스로 해법을 도출하도록 질문/프라이밍 우선")
        if (dna.aiAutonomyTrust > 0.7f)
            lines.add("- AI가 상황 판단해 능동적으로 제안해도 됨 (하드코딩 규칙 최소화, 유연한 대응)")
        if (dna.surpriseAppetite > 0.6f)
            lines.add("- 예상치 못한 인사이트·관점 환영 (창의적 제안 적극 허용)")
        if (dna.statisticalSummaryWeight > 0.7f)
            lines.add("- 주요 결정 후 통계 요약 및 다음 계획 연결 항상 포함")
        if (dna.qualityOverCost > 0.7f)
            lines.add("- 토큰 비용보다 응답 품질·깊이 우선 (상세 분석 권장)")

        if (lines.isEmpty()) return null
        return "[사용자 성향 DNA]\n" + lines.joinToString("\n")
    }

    private fun initializeDefault(): UserDNA {
        val dna = UserDNA()
        saveDNA(dna)
        return dna
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = (a * (1f - t) + b * t).coerceIn(0f, 1f)
}
