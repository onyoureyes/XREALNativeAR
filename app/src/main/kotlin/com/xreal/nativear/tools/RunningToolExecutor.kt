package com.xreal.nativear.tools

class RunningToolExecutor : IToolExecutor {

    override val supportedTools = setOf("get_running_stats", "control_running_session", "get_running_advice")

    // Lazy Koin injection for RunningCoachManager
    private val manager: com.xreal.nativear.running.RunningCoachManager? by lazy {
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull()
        } catch (e: Exception) { null }
    }

    // Lazy Koin injection for DB (running history)
    private val database: com.xreal.nativear.UnifiedMemoryDatabase? by lazy {
        try {
            org.koin.java.KoinJavaComponent.getKoin().getOrNull()
        } catch (e: Exception) { null }
    }

    override suspend fun execute(name: String, args: Map<String, Any?>): ToolResult {
        return when (name) {
            "get_running_stats" -> getRunningStats()
            "control_running_session" -> controlSession(args)
            "get_running_advice" -> getRunningAdvice()
            else -> ToolResult(false, "Unsupported tool: $name")
        }
    }

    private fun getRunningStats(): ToolResult {
        val mgr = manager ?: return ToolResult(false, "Running coach is not active.")
        val session = mgr.session
        val analyzer = mgr.dynamicsAnalyzer
        val elapsed = session.getElapsedSeconds()
        val cadence = analyzer.computeCadence()
        val stability = analyzer.computeHeadStability()
        val stats = buildString {
            appendLine("Running Stats:")
            appendLine("- Elapsed: ${elapsed / 60}m ${elapsed % 60}s")
            appendLine("- Distance: ${String.format("%.2f", session.totalDistanceMeters / 1000f)} km")
            appendLine("- Cadence: ${cadence.toInt()} spm")
            appendLine("- Vertical Oscillation: ${String.format("%.1f", analyzer.computeVerticalOscillation())} cm")
            appendLine("- Ground Contact Time: ${analyzer.computeGroundContactTime().toInt()} ms")
            appendLine("- Ground Reaction Force: ${String.format("%.1f", analyzer.computeGroundReactionForce())} G")
            appendLine("- Head Stability: ${stability?.stabilityScore?.toInt() ?: 0}/100")
            appendLine("- Lateral Balance: ${String.format("%.2f", stability?.lateralBalance ?: 0f)}")
            appendLine("- Session State: ${session.state}")
            // Watch biometrics
            if (mgr.lastWatchHr > 0) appendLine("- Heart Rate: ${mgr.lastWatchHr.toInt()} bpm")
            if (mgr.lastWatchHrv > 0) appendLine("- HRV (RMSSD): ${String.format("%.1f", mgr.lastWatchHrv)} ms")
            if (mgr.lastWatchHrvSdnn > 0) appendLine("- HRV (SDNN): ${String.format("%.1f", mgr.lastWatchHrvSdnn)} ms")
            if (mgr.lastWatchSpO2 > 0) appendLine("- SpO2: ${mgr.lastWatchSpO2}%")
            if (mgr.lastWatchSkinTemp > 0) appendLine("- Skin Temp: ${String.format("%.1f", mgr.lastWatchSkinTemp)}°C")
            if (mgr.lastWatchAmbientTemp > 0) appendLine("- Ambient Temp: ${String.format("%.1f", mgr.lastWatchAmbientTemp)}°C")
            // Position fusion info
            appendLine("- Position Mode: ${mgr.routeTracker.currentPositionMode}")
            val floorDelta = mgr.routeTracker.currentFloorDelta
            if (floorDelta != 0) appendLine("- Floor Changes: ${if (floorDelta > 0) "+" else ""}${floorDelta}F")
        }.trimEnd()
        return ToolResult(true, stats)
    }

    private fun controlSession(args: Map<String, Any?>): ToolResult {
        val mgr = manager ?: return ToolResult(false, "Running coach is not available.")
        val action = args["action"] as? String ?: ""
        val result = when (action.lowercase()) {
            "start" -> { mgr.startRun(); "Running session started." }
            "stop" -> { mgr.stopRun(); "Running session stopped." }
            "pause" -> { mgr.pauseRun(); "Running session paused." }
            "resume" -> { mgr.resumeRun(); "Running session resumed." }
            "lap" -> { mgr.recordLap(); "Lap recorded." }
            else -> "Unknown running action: $action"
        }
        return ToolResult(true, result)
    }

    private fun getRunningAdvice(): ToolResult {
        val mgr = manager ?: return ToolResult(false, "Running coach is not active.")
        val analyzer = mgr.dynamicsAnalyzer
        val cadence = analyzer.computeCadence()
        val vo = analyzer.computeVerticalOscillation()
        val gct = analyzer.computeGroundContactTime()
        val stability = analyzer.computeHeadStability()
        val advice = buildString {
            appendLine("Current Form Analysis:")
            // Biometric alerts (highest priority)
            if (mgr.lastWatchHr > 190) appendLine("- WARNING: Heart rate dangerously high (${mgr.lastWatchHr.toInt()} bpm). Slow down immediately.")
            else if (mgr.lastWatchHr > 180) appendLine("- Heart rate very high (${mgr.lastWatchHr.toInt()} bpm). Consider reducing pace.")
            if (mgr.lastWatchSpO2 in 1..91) appendLine("- WARNING: SpO2 low (${mgr.lastWatchSpO2}%). Focus on deep breathing.")
            else if (mgr.lastWatchSpO2 in 92..94) appendLine("- SpO2 slightly low (${mgr.lastWatchSpO2}%). Monitor breathing.")
            if (mgr.lastWatchHrv > 0 && mgr.lastWatchHrv < 20) appendLine("- Autonomic fatigue detected (HRV RMSSD: ${String.format("%.1f", mgr.lastWatchHrv)}ms). Recovery interval recommended.")
            if (mgr.lastWatchSkinTemp > 38.5f) appendLine("- Overheating risk (skin temp: ${String.format("%.1f", mgr.lastWatchSkinTemp)}°C). Hydrate and seek shade.")
            // Biomechanical checks
            if (cadence > 0 && cadence < 160) appendLine("- Cadence low (${cadence.toInt()} spm). Ideal: 170-180.")
            if (vo > 8f) appendLine("- Vertical oscillation high (${String.format("%.1f", vo)}cm). Try running lower.")
            if (gct > 300f) appendLine("- Ground contact time high (${gct.toInt()}ms). Aim for lighter footstrike.")
            stability?.let {
                if (it.stabilityScore < 60) appendLine("- Head unstable (${it.stabilityScore.toInt()}). Look forward, relax shoulders.")
                if (Math.abs(it.lateralBalance) > 0.3) appendLine("- Lateral imbalance: ${if (it.lateralBalance > 0) "right" else "left"} lean.")
            }
            if (length == "Current Form Analysis:\n".length) append("Form looks good! Keep it up.")

            // Append recent running history from DB
            val history = getRecentRunningHistory()
            if (history.isNotBlank()) {
                appendLine()
                appendLine(history)
            }
        }
        return ToolResult(true, advice)
    }

    private fun getRecentRunningHistory(): String {
        val db = database ?: return ""
        return try {
            val records = db.queryStructuredData("running_session", limit = 5)
            if (records.isEmpty()) return ""

            buildString {
                appendLine("Recent Running History (${records.size} sessions):")
                for (record in records) {
                    try {
                        val json = org.json.JSONObject(record.value)
                        val distKm = json.optDouble("distance_meters", 0.0) / 1000.0
                        val durationMin = json.optLong("duration_ms", 0) / 60_000
                        val pace = json.optDouble("avg_pace_min_per_km", 0.0)
                        appendLine("- ${record.dataKey}: ${String.format("%.1f", distKm)}km, ${durationMin}min, pace ${String.format("%.1f", pace)}min/km")
                    } catch (_: Exception) {
                        appendLine("- ${record.dataKey}: (parse error)")
                    }
                }
            }
        } catch (e: Exception) {
            ""
        }
    }
}
