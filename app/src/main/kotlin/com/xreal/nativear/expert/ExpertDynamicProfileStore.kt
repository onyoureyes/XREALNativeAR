package com.xreal.nativear.expert

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * ExpertDynamicProfileStore — 팀장(StrategistReflector/Gemini) 승인 후 부여된
 * 동적 도구 권한과 프롬프트 추가사항을 런타임에 관리.
 *
 * PersonaManager.buildPromptForPersona()에서 Layer 7b로 읽혀
 * 전문가 AI의 다음 호출 시 자동으로 시스템 프롬프트에 주입됨.
 *
 * ## 특징
 * - 순수 인메모리 (앱 재시작 시 초기화 — 요청 재승인 필요)
 * - 만료 자동 필터링 (getter 호출 시 pruneExpired() 자동 수행)
 * - 스레드 안전 (ConcurrentHashMap)
 */
class ExpertDynamicProfileStore {
    private val TAG = "ExpertDynamicProfileStore"

    data class DynamicAddition(
        val content: String,
        val expiresAt: Long,        // 0 = 무기한
        val requestId: String
    ) {
        val isExpired: Boolean
            get() = expiresAt > 0 && System.currentTimeMillis() > expiresAt
    }

    // expert_id → (tool_name → expiresAt)
    private val dynamicTools = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    // expert_id → List<DynamicAddition>
    private val dynamicPromptAdditions = ConcurrentHashMap<String, MutableList<DynamicAddition>>()

    // ─── 도구 권한 관리 ───

    fun addTool(expertId: String, toolName: String, expiresAt: Long) {
        dynamicTools.getOrPut(expertId) { ConcurrentHashMap() }[toolName] = expiresAt
        Log.i(TAG, "도구 권한 부여: $expertId → $toolName (만료: ${if (expiresAt > 0) "${(expiresAt - System.currentTimeMillis()) / 3600_000}h" else "무기한"})")
    }

    fun getDynamicTools(expertId: String): List<String> {
        val toolMap = dynamicTools[expertId] ?: return emptyList()
        val now = System.currentTimeMillis()
        // 만료된 항목 제거하면서 유효한 것만 반환
        val expired = toolMap.entries.filter { (_, exp) -> exp > 0 && now > exp }.map { it.key }
        expired.forEach { toolMap.remove(it) }
        return toolMap.keys.toList()
    }

    // ─── 프롬프트 추가 관리 ───

    fun addPromptAddition(expertId: String, content: String, expiresAt: Long, requestId: String) {
        val additions = dynamicPromptAdditions.getOrPut(expertId) { mutableListOf() }
        synchronized(additions) {
            additions.add(DynamicAddition(content, expiresAt, requestId))
        }
        Log.i(TAG, "프롬프트 추가 부여: $expertId → '${content.take(50)}...' (만료: ${if (expiresAt > 0) "${(expiresAt - System.currentTimeMillis()) / 3600_000}h" else "무기한"})")
    }

    fun getDynamicPromptAdditions(expertId: String): List<String> {
        val additions = dynamicPromptAdditions[expertId] ?: return emptyList()
        synchronized(additions) {
            // 만료 항목 제거
            additions.removeAll { it.isExpired }
            return additions.map { it.content }
        }
    }

    // ─── 취소 (StrategistService 효과 미검증 시 호출) ───

    fun revoke(expertId: String, requestId: String) {
        // 도구 권한 취소 불가 (requestId 매핑 없음 → 이름으로만 관리)
        // 프롬프트 추가 취소
        dynamicPromptAdditions[expertId]?.let { additions ->
            synchronized(additions) {
                val before = additions.size
                additions.removeAll { it.requestId == requestId }
                if (additions.size < before) {
                    Log.i(TAG, "프롬프트 추가 취소: $expertId / requestId=$requestId")
                }
            }
        }
    }

    fun revokeAllForExpert(expertId: String) {
        dynamicTools.remove(expertId)
        dynamicPromptAdditions.remove(expertId)
        Log.i(TAG, "전문가 동적 프로필 전체 취소: $expertId")
    }

    // ─── 만료 정리 (주기적 호출용) ───

    fun pruneExpired() {
        // 도구 권한
        for ((expertId, toolMap) in dynamicTools) {
            val now = System.currentTimeMillis()
            val expired = toolMap.entries.filter { (_, exp) -> exp > 0 && now > exp }.map { it.key }
            expired.forEach { toolMap.remove(it) }
            if (toolMap.isEmpty()) dynamicTools.remove(expertId)
        }
        // 프롬프트 추가
        for ((expertId, additions) in dynamicPromptAdditions) {
            synchronized(additions) {
                additions.removeAll { it.isExpired }
                if (additions.isEmpty()) dynamicPromptAdditions.remove(expertId)
            }
        }
    }

    fun hasAnyFor(expertId: String): Boolean {
        return getDynamicTools(expertId).isNotEmpty() ||
               getDynamicPromptAdditions(expertId).isNotEmpty()
    }
}
