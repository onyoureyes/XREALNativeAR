package com.xreal.nativear

import com.xreal.nativear.ai.AIToolDefinition

/**
 * GeminiProvider.registeredTools용 도구 목록 제공.
 * ToolDefinitionRegistry에 위임 — Gemini SDK 의존성 제거됨.
 *
 * 하위 호환: AppModule에서 GeminiTools.getAllToolDefinitions() 호출.
 */
object GeminiTools {

    /**
     * ToolDefinitionRegistry의 전체 도구 목록 반환.
     * GeminiProvider.registeredTools에 설정됨.
     */
    fun getAllToolDefinitions(registry: com.xreal.nativear.ai.ToolDefinitionRegistry): List<AIToolDefinition> {
        return registry.getAllToolDefinitions()
    }
}
