package com.xreal.nativear.policy

/**
 * IPolicyStore — PolicyRegistry 읽기 인터페이스.
 *
 * :core 모듈에서 정의, :app의 PolicyRegistry가 구현.
 * PolicyReader가 Koin을 통해 이 인터페이스를 탐색.
 */
interface IPolicyStore {
    fun get(key: String): String?
    fun getInt(key: String, fallback: Int): Int
    fun getLong(key: String, fallback: Long): Long
    fun getFloat(key: String, fallback: Float): Float
    fun getBoolean(key: String, fallback: Boolean): Boolean
    fun getString(key: String, fallback: String): String
    fun getEntry(key: String): PolicyEntry?
}
