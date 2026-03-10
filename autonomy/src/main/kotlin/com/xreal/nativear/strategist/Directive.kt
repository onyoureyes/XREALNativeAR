package com.xreal.nativear.strategist

import org.json.JSONObject
import java.util.UUID

data class Directive(
    val id: String = UUID.randomUUID().toString(),
    val targetPersonaId: String,       // persona_id or "*" for all
    val instruction: String,
    val rationale: String,
    val confidence: Float,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = createdAt + 3600_000L,  // default 1h TTL
    val sourcePattern: String? = null,
    // ★ 긴급 지시사항 플래그 — DirectiveConsumer에서 일반 지시보다 먼저 처리
    // EmergencyOrchestrator가 에러 패턴 감지 후 생성하는 지시에 사용
    val isEmergency: Boolean = false
) {
    val isExpired: Boolean
        get() = System.currentTimeMillis() > expiresAt

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("target", targetPersonaId)
        put("instruction", instruction)
        put("rationale", rationale)
        put("confidence", confidence.toDouble())
        put("created_at", createdAt)
        put("expires_at", expiresAt)
        sourcePattern?.let { put("source_pattern", it) }
        if (isEmergency) put("is_emergency", true)
    }

    companion object {
        fun fromJson(json: JSONObject): Directive = Directive(
            id = json.optString("id", UUID.randomUUID().toString()),
            targetPersonaId = json.optString("target", "*"),
            instruction = json.optString("instruction", ""),
            rationale = json.optString("rationale", ""),
            confidence = json.optDouble("confidence", 0.5).toFloat(),
            createdAt = json.optLong("created_at", System.currentTimeMillis()),
            expiresAt = json.optLong("expires_at", System.currentTimeMillis() + 3600_000L),
            sourcePattern = if (json.has("source_pattern")) json.getString("source_pattern") else null,
            isEmergency = json.optBoolean("is_emergency", false)
        )
    }
}
