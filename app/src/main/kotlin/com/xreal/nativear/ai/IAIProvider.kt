package com.xreal.nativear.ai

interface IAIProvider {
    val providerId: ProviderId
    val isAvailable: Boolean

    /**
     * Send a message with optional system prompt, tools, and conversation history.
     * Must be called on IO dispatcher.
     */
    suspend fun sendMessage(
        messages: List<AIMessage>,
        systemPrompt: String? = null,
        tools: List<AIToolDefinition> = emptyList(),
        temperature: Float? = null,
        maxTokens: Int? = null
    ): AIResponse

    /** Update API key at runtime. */
    fun updateApiKey(apiKey: String)

    /** Get current model name. */
    fun getModelName(): String
}
