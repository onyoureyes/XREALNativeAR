package com.xreal.nativear.mission

/**
 * IMissionService: 미션 활성화/비활성화 및 상태 조회 인터페이스.
 * 구현체: MissionConductor
 */
interface IMissionService {
    fun activateMission(type: MissionType, triggerMetadata: Map<String, String> = emptyMap())
    fun activateCustomMission(config: MissionTemplateConfig, metadata: Map<String, String> = mapOf("source" to "strategist"))
    fun deactivateMission(missionId: String)
    fun deactivateMissionsByType(type: MissionType)
    fun deactivateMissionsByMetadata(key: String, value: String)
    fun isMissionActive(type: MissionType): Boolean
    fun getActiveMissionTypes(): List<MissionType>
    fun release()
}
