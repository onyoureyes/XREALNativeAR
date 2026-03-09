package com.xreal.nativear.interaction

import android.util.Log
import com.xreal.nativear.SceneDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * InteractionTemplateManager — 상호작용 패턴 캐시 시스템.
 *
 * ## 3-Layer 아키텍처
 * | Layer | 소스 | 토큰 비용 |
 * |-------|------|-----------|
 * | L1: 하드코딩 프리미티브 | HandInteractionManager 기본 규칙 | 0 |
 * | L2: 캐시 템플릿 | DB interaction_templates | 0 |
 * | L3: AI 구성 | Gemini tool call → 신규 규칙 생성 | ~500-1000 토큰 |
 *
 * ## 패턴 승격 흐름
 * 1. AI가 새 상호작용 규칙 생성 (L3)
 * 2. 규칙 실행 시 useCount 증가
 * 3. useCount >= 3 → DB 템플릿으로 승격 (L2)
 * 4. 이후 같은 컨텍스트에서 DB에서 로드 (AI 호출 불필요)
 *
 * ## Strategist 통합
 * Strategist가 5분마다 사용 패턴 분석 → 승격/강등 결정.
 */
class InteractionTemplateManager(
    private val sceneDatabase: SceneDatabase,
    private val log: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "InteractionTemplate"

        /** 템플릿 승격 임계값 (이 횟수 이상 사용되면 DB에 저장) */
        const val PROMOTION_THRESHOLD = 3

        /** 템플릿 최대 수 (DB) */
        const val MAX_TEMPLATES = 100

        /** 템플릿 만료 일수 (미사용 시) */
        const val EXPIRY_DAYS = 30
    }

    // ── 인메모리 사용 카운터 (아직 DB에 없는 임시 규칙) ──
    private val pendingUseCounts = mutableMapOf<String, Int>()

    // ── Public API ──

    /**
     * 컨텍스트에 맞는 템플릿 로드.
     *
     * @param contextTags 현재 컨텍스트 태그 (예: "실내", "걷기", "공원")
     * @return 매칭 템플릿 → InteractionRule 변환 리스트
     */
    fun loadTemplates(vararg contextTags: String): List<InteractionRule> {
        val templates = queryTemplates(contextTags.toList())
        return templates.mapNotNull { templateToRule(it) }
    }

    /**
     * 규칙 사용 기록 + 승격 확인.
     *
     * @param rule 사용된 규칙
     * @param success 성공 여부 (사용자 반응 기반)
     */
    fun recordUsage(rule: InteractionRule, success: Boolean = true) {
        // DB에 이미 있는 템플릿인지 확인
        val existing = getTemplateByName(rule.name)
        if (existing != null) {
            // 기존 템플릿 사용 횟수 증가
            updateUsage(existing.id, success)
            return
        }

        // 인메모리 카운터 증가
        val count = (pendingUseCounts[rule.id] ?: 0) + 1
        pendingUseCounts[rule.id] = count

        // 승격 체크
        if (count >= PROMOTION_THRESHOLD) {
            promoteToTemplate(rule)
            pendingUseCounts.remove(rule.id)
            log("Template promoted: '${rule.name}' (used $count times)")
        }
    }

    /**
     * AI가 생성한 규칙을 즉시 템플릿으로 저장.
     */
    fun saveAsTemplate(rule: InteractionRule, creatorPersona: String = "", contextTags: List<String> = emptyList()) {
        val actionsJson = serializeActions(rule.actions)
        insertTemplate(InteractionTemplate(
            name = rule.name,
            triggerType = rule.trigger.name,
            targetFilter = rule.targetFilter,
            actionsJson = actionsJson,
            useCount = 1,
            contextTags = contextTags.joinToString(","),
            creatorPersona = creatorPersona
        ))
    }

    /**
     * 오래된/사용 안 되는 템플릿 정리.
     */
    fun pruneOldTemplates() {
        val cutoff = System.currentTimeMillis() - EXPIRY_DAYS * 24 * 60 * 60 * 1000L
        try {
            sceneDatabase.writableDatabase.execSQL(
                "DELETE FROM interaction_templates WHERE last_used_at < ? AND use_count < ?",
                arrayOf(cutoff, PROMOTION_THRESHOLD * 2)
            )
            log("Pruned old interaction templates")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prune templates", e)
        }
    }

    /**
     * 전체 템플릿 통계 (Strategist용).
     */
    fun getTemplateStats(): String {
        return try {
            val db = sceneDatabase.readableDatabase
            val cursor = db.rawQuery(
                "SELECT COUNT(*), SUM(use_count), AVG(success_rate) FROM interaction_templates", null
            )
            if (cursor.moveToFirst()) {
                val count = cursor.getInt(0)
                val totalUses = cursor.getInt(1)
                val avgSuccess = cursor.getFloat(2)
                cursor.close()
                "Templates: $count, Total uses: $totalUses, Avg success: ${"%.1f".format(avgSuccess * 100)}%"
            } else {
                cursor.close()
                "No templates"
            }
        } catch (e: Exception) {
            "Template stats unavailable: ${e.message}"
        }
    }

    // ── DB 연산 ──

    private fun queryTemplates(contextTags: List<String>): List<InteractionTemplate> {
        val templates = mutableListOf<InteractionTemplate>()
        try {
            val db = sceneDatabase.readableDatabase
            val query = if (contextTags.isEmpty()) {
                "SELECT * FROM interaction_templates ORDER BY use_count DESC LIMIT 50"
            } else {
                val conditions = contextTags.joinToString(" OR ") { "context_tags LIKE '%$it%'" }
                "SELECT * FROM interaction_templates WHERE $conditions ORDER BY use_count DESC LIMIT 50"
            }
            val cursor = db.rawQuery(query, null)
            while (cursor.moveToNext()) {
                templates.add(InteractionTemplate(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    triggerType = cursor.getString(cursor.getColumnIndexOrThrow("trigger_type")),
                    targetFilter = cursor.getString(cursor.getColumnIndexOrThrow("target_filter")),
                    actionsJson = cursor.getString(cursor.getColumnIndexOrThrow("actions_json")),
                    useCount = cursor.getInt(cursor.getColumnIndexOrThrow("use_count")),
                    successRate = cursor.getFloat(cursor.getColumnIndexOrThrow("success_rate")),
                    contextTags = cursor.getString(cursor.getColumnIndexOrThrow("context_tags")),
                    creatorPersona = cursor.getString(cursor.getColumnIndexOrThrow("creator_persona")),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                    lastUsedAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_used_at"))
                ))
            }
            cursor.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query templates", e)
        }
        return templates
    }

    private fun getTemplateByName(name: String): InteractionTemplate? {
        return try {
            val db = sceneDatabase.readableDatabase
            val cursor = db.rawQuery(
                "SELECT * FROM interaction_templates WHERE name = ? LIMIT 1",
                arrayOf(name)
            )
            val template = if (cursor.moveToFirst()) {
                InteractionTemplate(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    triggerType = cursor.getString(cursor.getColumnIndexOrThrow("trigger_type")),
                    targetFilter = cursor.getString(cursor.getColumnIndexOrThrow("target_filter")),
                    actionsJson = cursor.getString(cursor.getColumnIndexOrThrow("actions_json")),
                    useCount = cursor.getInt(cursor.getColumnIndexOrThrow("use_count")),
                    successRate = cursor.getFloat(cursor.getColumnIndexOrThrow("success_rate")),
                    contextTags = cursor.getString(cursor.getColumnIndexOrThrow("context_tags")),
                    creatorPersona = cursor.getString(cursor.getColumnIndexOrThrow("creator_persona")),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                    lastUsedAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_used_at"))
                )
            } else null
            cursor.close()
            template
        } catch (e: Exception) {
            null
        }
    }

    private fun insertTemplate(template: InteractionTemplate) {
        try {
            val db = sceneDatabase.writableDatabase
            db.execSQL(
                """INSERT OR REPLACE INTO interaction_templates
                   (name, trigger_type, target_filter, actions_json, use_count, success_rate,
                    context_tags, creator_persona, created_at, last_used_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                arrayOf(
                    template.name, template.triggerType, template.targetFilter,
                    template.actionsJson, template.useCount, template.successRate,
                    template.contextTags, template.creatorPersona,
                    template.createdAt, template.lastUsedAt
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert template", e)
        }
    }

    private fun updateUsage(templateId: Long, success: Boolean) {
        try {
            val db = sceneDatabase.writableDatabase
            if (success) {
                db.execSQL(
                    """UPDATE interaction_templates
                       SET use_count = use_count + 1,
                           success_rate = (success_rate * use_count + 1.0) / (use_count + 1),
                           last_used_at = ?
                       WHERE id = ?""",
                    arrayOf(System.currentTimeMillis(), templateId)
                )
            } else {
                db.execSQL(
                    """UPDATE interaction_templates
                       SET use_count = use_count + 1,
                           success_rate = (success_rate * use_count) / (use_count + 1),
                           last_used_at = ?
                       WHERE id = ?""",
                    arrayOf(System.currentTimeMillis(), templateId)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update template usage", e)
        }
    }

    private fun promoteToTemplate(rule: InteractionRule) {
        insertTemplate(InteractionTemplate(
            name = rule.name,
            triggerType = rule.trigger.name,
            targetFilter = rule.targetFilter,
            actionsJson = serializeActions(rule.actions),
            useCount = PROMOTION_THRESHOLD,
            contextTags = "",
            creatorPersona = "auto_promoted"
        ))
    }

    // ── 직렬화 ──

    fun serializeActions(actions: List<RuleAction>): String {
        val arr = JSONArray()
        for (action in actions) {
            val obj = JSONObject()
            obj.put("type", action.type.name)
            val params = JSONObject()
            for ((k, v) in action.params) {
                params.put(k, v)
            }
            obj.put("params", params)
            arr.put(obj)
        }
        return arr.toString()
    }

    fun deserializeActions(json: String): List<RuleAction> {
        return try {
            val arr = JSONArray(json)
            val actions = mutableListOf<RuleAction>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val type = ActionType.valueOf(obj.getString("type"))
                val params = mutableMapOf<String, Any>()
                val paramsJson = obj.optJSONObject("params")
                if (paramsJson != null) {
                    for (key in paramsJson.keys()) {
                        params[key] = paramsJson.get(key)
                    }
                }
                actions.add(RuleAction(type, params))
            }
            actions
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize actions: $json", e)
            emptyList()
        }
    }

    private fun templateToRule(template: InteractionTemplate): InteractionRule? {
        return try {
            val trigger = TriggerType.valueOf(template.triggerType)
            val actions = deserializeActions(template.actionsJson)
            if (actions.isEmpty()) return null

            InteractionRule(
                id = "tmpl_${template.id}",
                name = template.name,
                trigger = trigger,
                targetFilter = template.targetFilter,
                actions = actions,
                priority = template.useCount  // 자주 쓸수록 높은 우선순위
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert template to rule: ${template.name}", e)
            null
        }
    }
}
