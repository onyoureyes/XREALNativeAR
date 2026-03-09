package com.xreal.nativear.meeting

import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase

/**
 * UserProfileManager: 사용자의 사회경제적 프로필(직업, 역할, 직장) 관리.
 *
 * ## 저장 방식
 * UnifiedMemoryDatabase의 structured_data 테이블 활용 (스키마 마이그레이션 불필요)
 * - domain: "user_profile"
 * - data_key: "occupation", "role", "workplace", "department", "specialization" 등
 *
 * ## 용도
 * - PersonaManager: AI 프롬프트에 사용자 맥락 주입
 * - MeetingContextService: 직업 맞춤 맥락 설명
 * - ContextSnapshot: userOccupation 필드 제공
 * - ExpertTeamManager: 도메인 우선순위 결정
 *
 * ## 프로필 항목
 * - occupation: 직업 (예: "초등학교 특수교사")
 * - role: 역할/직급 (예: "담임교사", "학년부장")
 * - workplace: 직장/소속 (예: "OO초등학교")
 * - department: 부서 (예: "특수학급")
 * - specialization: 전문분야 (예: "특수교육", "발달장애")
 * - workSchedule: 근무 패턴 (예: "평일 8:30-16:30")
 * - interests: 관심사 (예: "교육공학,AR교육")
 */
class UserProfileManager(
    private val database: UnifiedMemoryDatabase
) {
    companion object {
        private const val TAG = "UserProfileManager"
        private const val DOMAIN = "user_profile"
    }

    // ── Core profile keys ──

    var occupation: String?
        get() = getData("occupation")
        set(value) { setData("occupation", value) }

    var role: String?
        get() = getData("role")
        set(value) { setData("role", value) }

    var workplace: String?
        get() = getData("workplace")
        set(value) { setData("workplace", value) }

    var department: String?
        get() = getData("department")
        set(value) { setData("department", value) }

    var specialization: String?
        get() = getData("specialization")
        set(value) { setData("specialization", value) }

    var workSchedule: String?
        get() = getData("work_schedule")
        set(value) { setData("work_schedule", value) }

    var interests: String?
        get() = getData("interests")
        set(value) { setData("interests", value) }

    // ── Bulk Operations ──

    /**
     * 프로필 일괄 업데이트.
     * AI 또는 사용자 설정에서 호출.
     */
    fun updateProfile(
        occupation: String? = null,
        role: String? = null,
        workplace: String? = null,
        department: String? = null,
        specialization: String? = null,
        workSchedule: String? = null,
        interests: String? = null
    ) {
        occupation?.let { this.occupation = it }
        role?.let { this.role = it }
        workplace?.let { this.workplace = it }
        department?.let { this.department = it }
        specialization?.let { this.specialization = it }
        workSchedule?.let { this.workSchedule = it }
        interests?.let { this.interests = it }
        Log.i(TAG, "Profile updated: occupation=${occupation ?: "(unchanged)"}")
    }

    /**
     * AI 프롬프트 주입용 프로필 요약.
     * PersonaManager, MeetingContextService에서 사용.
     */
    fun getProfileSummary(): String {
        val parts = mutableListOf<String>()

        occupation?.let { parts.add("직업: $it") }
        role?.let { parts.add("역할: $it") }
        workplace?.let { parts.add("소속: $it") }
        department?.let { parts.add("부서: $it") }
        specialization?.let { parts.add("전문: $it") }
        workSchedule?.let { parts.add("근무: $it") }
        interests?.let { parts.add("관심: $it") }

        return if (parts.isEmpty()) {
            "[사용자 프로필: 미설정]"
        } else {
            "[사용자 프로필]\n${parts.joinToString("\n")}"
        }
    }

    /**
     * ContextSnapshot용 간단 직업 문자열.
     */
    fun getOccupationString(): String? {
        val occ = occupation ?: return null
        val r = role
        return if (r != null) "$occ ($r)" else occ
    }

    /**
     * 프로필이 설정되어 있는지 확인.
     */
    fun isProfileConfigured(): Boolean {
        return occupation != null
    }

    /**
     * 전체 프로필을 Map으로 반환.
     */
    fun toMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val records = database.queryStructuredData(domain = DOMAIN, limit = 20)
        records.forEach { record ->
            map[record.dataKey] = record.value
        }
        return map
    }

    /**
     * 커스텀 키-값 설정 (확장용).
     */
    fun setCustomData(key: String, value: String) {
        setData(key, value)
    }

    fun getCustomData(key: String): String? {
        return getData(key)
    }

    // ── Internal ──

    private fun getData(key: String): String? {
        return try {
            val records = database.queryStructuredData(domain = DOMAIN, dataKey = key, limit = 1)
            records.firstOrNull()?.value
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read $key: ${e.message}")
            null
        }
    }

    private fun setData(key: String, value: String?) {
        try {
            if (value != null) {
                database.upsertStructuredData(
                    domain = DOMAIN,
                    dataKey = key,
                    value = value,
                    tags = "user_profile"
                )
                Log.d(TAG, "Set $key = $value")
            } else {
                database.deleteStructuredData(domain = DOMAIN, dataKey = key)
                Log.d(TAG, "Deleted $key")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set $key: ${e.message}")
        }
    }
}
