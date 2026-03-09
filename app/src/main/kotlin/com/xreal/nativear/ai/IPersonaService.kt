package com.xreal.nativear.ai

/**
 * IPersonaService: 페르소나 관리 및 프롬프트 빌딩 인터페이스.
 * 구현체: PersonaManager
 */
interface IPersonaService {
    fun registerPersona(persona: Persona)
    fun getPersona(id: String): Persona?
    fun getAllPersonas(): List<Persona>
    fun getEnabledPersonas(): List<Persona>
    suspend fun buildPromptForPersona(personaId: String): String
    suspend fun buildContextAddendum(personaId: String = "vision_analyst"): String
    suspend fun buildMessagesForPersona(
        personaId: String,
        userQuery: String,
        additionalContext: String? = null
    ): Pair<String, List<AIMessage>>
    fun getProviderForPersona(personaId: String): IAIProvider?
}
