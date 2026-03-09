package com.xreal.nativear.policy

/**
 * PolicyReader — IPolicyStore shadow read 유틸리티.
 *
 * Koin 미초기화, IPolicyStore 미등록 등 모든 예외 상황에서
 * 하드코딩 fallback 반환을 보장. hot path에서 안전하게 사용 가능.
 */
object PolicyReader {
    private val store: IPolicyStore?
        get() = try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull<IPolicyStore>()
        } catch (_: Exception) { null }

    fun getInt(key: String, fallback: Int): Int =
        store?.getInt(key, fallback) ?: fallback

    fun getLong(key: String, fallback: Long): Long =
        store?.getLong(key, fallback) ?: fallback

    fun getFloat(key: String, fallback: Float): Float =
        store?.getFloat(key, fallback) ?: fallback

    fun getBoolean(key: String, fallback: Boolean): Boolean =
        store?.getBoolean(key, fallback) ?: fallback

    fun getString(key: String, fallback: String): String =
        store?.getString(key, fallback) ?: fallback
}
