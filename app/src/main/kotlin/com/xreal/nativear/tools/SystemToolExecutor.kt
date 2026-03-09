package com.xreal.nativear.tools

import android.util.Log
import com.xreal.nativear.ILocationService
import com.xreal.nativear.sync.BackupSyncConfig
import com.xreal.nativear.sync.BackupSyncScheduler

class SystemToolExecutor(
    private val locationService: ILocationService
) : IToolExecutor {

    override val supportedTools = setOf(
        "get_current_location",
        "trigger_backup_sync",
        "configure_backup",
        "get_backup_status",
        "get_system_health"  // ★ Phase E: SystemConductor 상태 조회
    )

    // Lazy Koin injection for SystemConductor
    private val systemConductor: com.xreal.nativear.resilience.SystemConductor? by lazy {
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull() } catch (_: Exception) { null }
    }

    // Lazy Koin injection
    private val syncScheduler: BackupSyncScheduler? by lazy {
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull() } catch (_: Exception) { null }
    }
    private val syncConfig: BackupSyncConfig? by lazy {
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull() } catch (_: Exception) { null }
    }

    override suspend fun execute(name: String, args: Map<String, Any?>): ToolResult {
        return when (name) {
            "get_current_location" -> {
                val loc = locationService.getCurrentLocation()
                if (loc != null) {
                    ToolResult(true, "Current Location: Lat ${loc.latitude}, Lon ${loc.longitude}, Speed ${loc.speed}m/s")
                } else {
                    ToolResult(false, "Location unavailable.")
                }
            }
            "trigger_backup_sync" -> {
                val scheduler = syncScheduler
                if (scheduler != null) {
                    scheduler.triggerNow()
                    ToolResult(true, "Backup sync triggered. Data will be uploaded when network is available.")
                } else {
                    ToolResult(false, "Backup sync not configured.")
                }
            }
            "configure_backup" -> {
                val config = syncConfig ?: return ToolResult(false, "Backup system not available.")
                val serverUrl = args["server_url"] as? String
                    ?: return ToolResult(false, "Missing required parameter: server_url")
                val apiKey = args["api_key"] as? String
                    ?: return ToolResult(false, "Missing required parameter: api_key")

                config.configure(serverUrl, apiKey)

                // Apply optional settings
                (args["sync_interval_minutes"] as? Number)?.let { config.syncIntervalMinutes = it.toLong() }
                (args["require_wifi"] as? Boolean)?.let { config.requireWifi = it }

                // Schedule periodic sync
                syncScheduler?.schedule()

                ToolResult(true, "Backup configured: ${config.toSummary()}")
            }
            "get_backup_status" -> {
                val config = syncConfig
                if (config != null) {
                    ToolResult(true, config.toSummary())
                } else {
                    ToolResult(false, "Backup system not available.")
                }
            }
            "get_system_health" -> {
                // ★ Phase E: SystemConductor에서 현재 시스템 상태 요약 반환
                val conductor = systemConductor
                if (conductor != null) {
                    val state = conductor.currentState
                    val decision = conductor.getLastDecision()
                    val summary = buildString {
                        appendLine("## 시스템 상태 (SystemConductor 기준)")
                        appendLine(state.toSummary())
                        appendLine()
                        appendLine("### 하드웨어")
                        appendLine("- 배터리: ${state.batteryPercent}%${if (state.isCharging) " (충전중)" else ""}")
                        appendLine("- XREAL 글래스: ${if (state.isGlassesConnected) "연결됨 (${state.glassesFrameRateFps.toInt()}fps)" else "⚠️ 미연결"}")
                        appendLine("- 갤럭시 워치: ${if (state.isWatchConnected) "연결됨" else "미연결"}")
                        appendLine("- 온도: ${state.batteryTempC}°C (thermal=${state.thermalStatus})")
                        appendLine()
                        appendLine("### 계산 자원")
                        appendLine("- CPU: ${state.cpuPercent}%")
                        appendLine("- RAM: ${state.ramUsedMb}/${state.ramTotalMb}MB")
                        appendLine()
                        appendLine("### 네트워크 & AI")
                        appendLine("- 네트워크: ${if (state.isNetworkAvailable) state.networkType else "없음"}")
                        appendLine("- 엣지 LLM: ${if (state.isEdgeLlmReady) "준비됨" else "미준비"}")
                        appendLine()
                        appendLine("### 현재 결정")
                        appendLine("- 능력 등급: ${state.currentTier.name}")
                        appendLine("- 운영 모드: ${state.currentMode.name}")
                        appendLine("- 활성 에러: ${state.activeEmergencies}건")
                        if (decision != null) {
                            appendLine("- 최종 결정자: ${decision.winningSection.displayName} (${decision.winningSection.name})")
                            appendLine("- 결정 이유: ${decision.reason}")
                            if (decision.goalTierHint != null) {
                                appendLine("- 상황 목표 등급: ${decision.goalTierHint} (현재 제한으로 미달성)")
                            }
                            if (decision.overriddenSections.isNotEmpty()) {
                                appendLine("- 무시된 섹션: ${decision.overriddenSections.joinToString()}")
                            }
                        }
                    }
                    ToolResult(true, summary)
                } else {
                    ToolResult(false, "SystemConductor 미초기화 — 시스템 상태 조회 불가")
                }
            }
            else -> ToolResult(false, "Unsupported tool: $name")
        }
    }
}
