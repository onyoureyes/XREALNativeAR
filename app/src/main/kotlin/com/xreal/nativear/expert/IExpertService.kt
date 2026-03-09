package com.xreal.nativear.expert

/**
 * IExpertService: 전문가 팀 관리 및 상태 조회 인터페이스.
 * 구현체: ExpertTeamManager
 */
interface IExpertService {
    fun start()
    fun stop()
    fun getActiveExperts(): List<ExpertProfile>
    fun getActiveDomainIds(): Set<String>
    fun getActiveSessions(): List<ActiveDomainSession>
    fun isDomainActive(domainId: String): Boolean
    fun forceActivateDomain(domainId: String)
    fun forceDeactivateDomain(domainId: String)
    fun applyCompositionDirective(instruction: String): Boolean
}
