package com.xreal.nativear.policy

import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * PolicyRegistry — 모든 정책 상수를 한 곳에서 관리하는 영속 저장소.
 *
 * ## 3계층 우선순위
 * 1. DirectiveStore (임시, TTL 기반) — 기존 동작 유지, 가장 높은 우선순위
 * 2. PolicyRegistry override (영구, DB 기반) — AI가 학습한 최적값 영구 저장
 * 3. PolicyDefaults (코드 기본값) — 하드코딩 fallback
 *
 * ## hot path 설계
 * - get() = ConcurrentHashMap 읽기, 락 없음
 * - set() = 유효성 검증 + ConcurrentHashMap 쓰기 + DB persist + 리스너 알림
 *
 * ## DB 스키마
 * 기존 structured_data 테이블 재사용:
 *   domain = "policy"
 *   data_key = 정책 키 (예: "cadence.ocr_interval_ms")
 *   value = JSON (override, source, updated_at, ttl_ms)
 *   tags = 카테고리명
 */
class PolicyRegistry(
    private val database: UnifiedMemoryDatabase
) : IPolicyStore {
    private val TAG = "PolicyRegistry"

    // 인메모리 캐시 — hot path
    private val entries = ConcurrentHashMap<String, PolicyEntry>()

    // 변경 리스너
    private val listeners = CopyOnWriteArrayList<PolicyChangeListener>()

    fun interface PolicyChangeListener {
        fun onPolicyChanged(key: String, oldValue: String?, newValue: String)
    }

    /**
     * 초기화: 기본값 등록 → DB 오버라이드 로드.
     * AppModule에서 생성 직후 호출.
     */
    fun initialize() {
        // 1. 기본값 등록
        PolicyDefaults.getAllDefaults().forEach { entry ->
            entries[entry.key] = entry
        }
        Log.i(TAG, "기본 정책 ${entries.size}개 등록")

        // 2. DB 오버라이드 로드
        loadOverridesFromDb()
    }

    private fun loadOverridesFromDb() {
        try {
            val records = database.queryStructuredData(domain = DOMAIN, limit = 500)
            var loaded = 0
            for (record in records) {
                val entry = entries[record.dataKey] ?: continue
                try {
                    val json = JSONObject(record.value)
                    val overrideValue = json.optString("override", "")
                    val source = json.optString("source", "db")
                    val ttlMs = json.optLong("ttl_ms", 0L)
                    val updatedAt = json.optLong("updated_at", record.updatedAt)

                    // TTL 만료 확인 (ttl=0 → 영구)
                    if (ttlMs > 0 && System.currentTimeMillis() - updatedAt > ttlMs) {
                        database.deleteStructuredData(DOMAIN, record.dataKey)
                        continue
                    }

                    if (overrideValue.isNotBlank() && isValidValue(entry, overrideValue)) {
                        entries[record.dataKey] = entry.copy(
                            overrideValue = overrideValue,
                            overrideSource = source,
                            overrideTtlMs = ttlMs,
                            overrideUpdatedAt = updatedAt
                        )
                        loaded++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "DB 오버라이드 파싱 실패: ${record.dataKey} — ${e.message}")
                }
            }
            if (loaded > 0) Log.i(TAG, "DB 오버라이드 $loaded 개 로드")
        } catch (e: Exception) {
            Log.w(TAG, "DB 오버라이드 로드 실패 (정상 동작에 영향 없음): ${e.message}")
        }
    }

    // =========================================================================
    // 읽기 — hot path (락 없음)
    // =========================================================================

    /** 유효 값 반환 (override > default). null이면 키 없음. */
    override fun get(key: String): String? {
        val entry = entries[key] ?: return null
        return entry.overrideValue ?: entry.defaultValue
    }

    override fun getInt(key: String, fallback: Int): Int =
        get(key)?.toIntOrNull() ?: fallback

    override fun getLong(key: String, fallback: Long): Long =
        get(key)?.toLongOrNull() ?: fallback

    override fun getFloat(key: String, fallback: Float): Float =
        get(key)?.toFloatOrNull() ?: fallback

    override fun getBoolean(key: String, fallback: Boolean): Boolean {
        val v = get(key) ?: return fallback
        return v.equals("true", ignoreCase = true)
    }

    override fun getString(key: String, fallback: String): String =
        get(key) ?: fallback

    /** 해당 키의 PolicyEntry 전체 조회 (HUD 표시용). */
    override fun getEntry(key: String): PolicyEntry? = entries[key]

    // =========================================================================
    // 쓰기 — 유효성 검증 + DB persist + 리스너 알림
    // =========================================================================

    /**
     * 정책 오버라이드 설정.
     * @param key 정책 키
     * @param value 새 값 (문자열)
     * @param source 변경 주체 ("user_voice", "strategist", "ai_agent", "system")
     * @param ttlMs 유효 기간 (0 = 영구)
     * @return true if applied, false if invalid
     */
    fun set(key: String, value: String, source: String = "system", ttlMs: Long = 0L): Boolean {
        val entry = entries[key]
        if (entry == null) {
            Log.w(TAG, "알 수 없는 정책 키: $key")
            return false
        }

        if (!isValidValue(entry, value)) {
            Log.w(TAG, "정책 값 유효성 실패: $key=$value (범위: ${entry.min}~${entry.max})")
            return false
        }

        val oldValue = get(key)
        val now = System.currentTimeMillis()

        entries[key] = entry.copy(
            overrideValue = value,
            overrideSource = source,
            overrideTtlMs = ttlMs,
            overrideUpdatedAt = now
        )

        // DB persist
        persistToDb(key, value, source, ttlMs, now, entry.category.name)

        // 리스너 알림
        listeners.forEach { listener ->
            try {
                listener.onPolicyChanged(key, oldValue, value)
            } catch (e: Exception) {
                Log.w(TAG, "정책 변경 리스너 오류: ${e.message}")
            }
        }

        Log.i(TAG, "정책 변경: $key = $value (source=$source, ttl=${ttlMs}ms)")
        return true
    }

    /** 기본값으로 복원 (오버라이드 삭제). */
    fun reset(key: String): Boolean {
        val entry = entries[key] ?: return false
        val oldValue = get(key)

        entries[key] = entry.copy(
            overrideValue = null,
            overrideSource = null,
            overrideTtlMs = 0L,
            overrideUpdatedAt = 0L
        )

        // DB 삭제
        try {
            database.deleteStructuredData(DOMAIN, key)
        } catch (e: Exception) {
            Log.w(TAG, "정책 DB 삭제 실패: $key — ${e.message}")
        }

        listeners.forEach { listener ->
            try {
                listener.onPolicyChanged(key, oldValue, entry.defaultValue)
            } catch (e: Exception) {
                Log.w(TAG, "정책 변경 리스너 오류: ${e.message}")
            }
        }

        Log.i(TAG, "정책 초기화: $key → 기본값 ${entry.defaultValue}")
        return true
    }

    // =========================================================================
    // 조회 — 카테고리별 목록 (HUD / 설정 표시용)
    // =========================================================================

    fun listByCategory(category: PolicyCategory): List<PolicyEntry> =
        entries.values.filter { it.category == category }.sortedBy { it.key }

    fun listAll(): List<PolicyEntry> =
        entries.values.sortedBy { it.key }

    fun listOverrides(): List<PolicyEntry> =
        entries.values.filter { it.overrideValue != null }.sortedBy { it.key }

    // =========================================================================
    // 리스너
    // =========================================================================

    fun addChangeListener(listener: PolicyChangeListener) {
        listeners.add(listener)
    }

    fun removeChangeListener(listener: PolicyChangeListener) {
        listeners.remove(listener)
    }

    // =========================================================================
    // 내부
    // =========================================================================

    private fun persistToDb(key: String, value: String, source: String, ttlMs: Long, updatedAt: Long, category: String) {
        try {
            val json = JSONObject().apply {
                put("override", value)
                put("source", source)
                put("updated_at", updatedAt)
                put("ttl_ms", ttlMs)
            }
            database.upsertStructuredData(DOMAIN, key, json.toString(), category)
        } catch (e: Exception) {
            Log.w(TAG, "정책 DB 저장 실패: $key — ${e.message}")
        }
    }

    private fun isValidValue(entry: PolicyEntry, value: String): Boolean {
        val minStr = entry.min
        val maxStr = entry.max
        return try {
            when (entry.valueType) {
                PolicyValueType.INT -> {
                    val v = value.toInt()
                    (minStr == null || v >= minStr.toInt()) &&
                            (maxStr == null || v <= maxStr.toInt())
                }
                PolicyValueType.LONG -> {
                    val v = value.toLong()
                    (minStr == null || v >= minStr.toLong()) &&
                            (maxStr == null || v <= maxStr.toLong())
                }
                PolicyValueType.FLOAT -> {
                    val v = value.toFloat()
                    (minStr == null || v >= minStr.toFloat()) &&
                            (maxStr == null || v <= maxStr.toFloat())
                }
                PolicyValueType.BOOLEAN -> value.equals("true", true) || value.equals("false", true)
                PolicyValueType.STRING, PolicyValueType.STRING_LIST -> true
            }
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        const val DOMAIN = "policy"
    }
}

// PolicyCategory, PolicyValueType, PolicyEntry → :core-models 모듈 (com.xreal.nativear.policy)
// IPolicyStore → :core 모듈 (com.xreal.nativear.policy)
