package com.xreal.nativear.cadence

import android.util.Log
import com.xreal.nativear.SceneDatabase
import com.xreal.nativear.UnifiedMemoryDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.Calendar

/**
 * Mines behavioral patterns from UnifiedMemoryDB + SceneDB to build a Digital Twin.
 * Called periodically by StrategistService during reflection cycles.
 *
 * Profiles are persisted in UnifiedMemoryDB with role="DIGITAL_TWIN".
 */
class DigitalTwinBuilder(
    private val memoryDatabase: UnifiedMemoryDatabase,
    private val sceneDatabase: SceneDatabase,
    private val memorySaveHelper: com.xreal.nativear.memory.IMemoryAccess? = null,
    private val predictionSyncService: com.xreal.nativear.sync.PredictionSyncService? = null
) {
    private val TAG = "DigitalTwinBuilder"

    private val _profile = MutableStateFlow(UserProfileModel())
    val profile: StateFlow<UserProfileModel> = _profile.asStateFlow()

    companion object {
        private const val FAMILIAR_VISIT_THRESHOLD = 3
        private const val LOCATION_CLUSTER_RADIUS_KM = 0.05 // ~50 meters
    }

    /**
     * Full profile rebuild from DB data. Called during Strategist reflection cycles.
     */
    fun rebuildProfile() {
        try {
            val locationPatterns = mineLocationPatterns()
            val topics = mineConversationTopics()
            val interactions = mineInteractionPatterns()
            val emotions = mineEmotionPatterns()
            val routine = mineDailyRoutine()

            val newProfile = UserProfileModel(
                desire = DesireAxis(
                    topTopics = topics,
                    emotionDistribution = emotions,
                    preferredActivities = emptyList() // Future: mine from activity patterns
                ),
                ethics = EthicsAxis(
                    safetyAwareness = computeSafetyAwareness(),
                    socialResponsiveness = computeSocialResponsiveness(interactions),
                    privacyBehavior = 0.5f
                ),
                practical = PracticalAxis(
                    dailyRoutine = routine,
                    avgWalkingSpeedMs = 1.2f, // Future: compute from location data
                    toolUsageFrequency = emptyMap()
                ),
                physiology = buildPhysiologyFromPredictions(),
                locationPatterns = locationPatterns,
                interactionPatterns = interactions,
                lastUpdated = System.currentTimeMillis()
            )

            _profile.value = newProfile
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                persistProfile(newProfile)
            }
            Log.i(TAG, "Profile rebuilt: ${locationPatterns.size} locations, ${topics.size} topics, ${interactions.size} interactions")
        } catch (e: Exception) {
            Log.e(TAG, "Profile rebuild failed: ${e.message}", e)
        }
    }

    /**
     * Check if a GPS location is familiar (visited >= 3 times).
     * Used by UserStateTracker for WALKING_FAMILIAR vs WALKING_UNFAMILIAR.
     */
    fun isLocationFamiliar(lat: Double, lon: Double): Boolean {
        return _profile.value.locationPatterns.any { pattern ->
            pattern.isFamiliar && haversineDistanceKm(lat, lon, pattern.latitude, pattern.longitude) < LOCATION_CLUSTER_RADIUS_KM
        }
    }

    /**
     * Mine location patterns from SYSTEM_LOG nodes with lat/lon.
     */
    private fun mineLocationPatterns(): List<LocationPattern> {
        return try {
            // Get nodes that have location data
            val oneDayAgo = System.currentTimeMillis() - 7 * 24 * 3600_000L // Last 7 days
            val nodes = memoryDatabase.getNodesInTimeRange(oneDayAgo, System.currentTimeMillis())
            val locatedNodes = nodes.filter { it.latitude != null && it.longitude != null }

            if (locatedNodes.isEmpty()) return emptyList()

            // Cluster nearby locations
            val clusters = clusterLocations(locatedNodes.map { it.latitude!! to it.longitude!! })

            clusters.map { (center, count) ->
                LocationPattern(
                    label = "위치(${String.format("%.4f", center.first)}, ${String.format("%.4f", center.second)})",
                    latitude = center.first,
                    longitude = center.second,
                    visitCount = count
                )
            }.sortedByDescending { it.visitCount }
        } catch (e: Exception) {
            Log.w(TAG, "Location mining failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Mine conversation topics from WHISPER transcripts (word frequency).
     */
    private fun mineConversationTopics(): List<String> {
        return try {
            val whisperNodes = memoryDatabase.getNodesByRole("WHISPER", limit = 100)
            if (whisperNodes.isEmpty()) return emptyList()

            val stopWords = setOf("은", "는", "이", "가", "을", "를", "의", "에", "에서", "와", "과",
                "도", "만", "까지", "부터", "로", "으로", "하고", "그리고", "하지만", "그래서",
                "a", "the", "is", "it", "in", "to", "and", "of", "that", "this")

            val wordFreq = mutableMapOf<String, Int>()
            for (node in whisperNodes) {
                val words = node.content.split(Regex("[\\s,.:;!?]+"))
                    .map { it.trim().lowercase() }
                    .filter { it.length > 1 && it !in stopWords }
                for (word in words) {
                    wordFreq[word] = (wordFreq[word] ?: 0) + 1
                }
            }

            wordFreq.entries
                .filter { it.value >= 2 }
                .sortedByDescending { it.value }
                .take(10)
                .map { it.key }
        } catch (e: Exception) {
            Log.w(TAG, "Topic mining failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Mine interaction patterns from SceneDB interactions table.
     */
    private fun mineInteractionPatterns(): List<InteractionPattern> {
        return try {
            val persons = sceneDatabase.getAllPersons()
            if (persons.isEmpty()) return emptyList()

            persons.mapNotNull { person ->
                val interactions = sceneDatabase.getInteractionsByPerson(person.id, limit = 50)
                if (interactions.isEmpty()) return@mapNotNull null

                val emotionCounts = mutableMapOf<String, Int>()
                for (interaction in interactions) {
                    val emotion = interaction.expression ?: interaction.audioEmotion ?: continue
                    emotionCounts[emotion] = (emotionCounts[emotion] ?: 0) + 1
                }
                val dominantEmotion = emotionCounts.maxByOrNull { it.value }?.key

                InteractionPattern(
                    personId = person.id,
                    personName = person.name ?: "Person #${person.id}",
                    meetCount = interactions.size,
                    dominantEmotion = dominantEmotion
                )
            }.sortedByDescending { it.meetCount }
        } catch (e: Exception) {
            Log.w(TAG, "Interaction mining failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Mine emotion patterns from VoiceLogs.
     */
    private fun mineEmotionPatterns(): Map<String, Float> {
        return try {
            val voiceLogs = sceneDatabase.getAllVoiceLogs()
            if (voiceLogs.isEmpty()) return emptyMap()

            val emotionCounts = mutableMapOf<String, Int>()
            var total = 0
            for (log in voiceLogs) {
                val emotion = log.emotion ?: continue
                if ((log.emotionScore ?: 0f) < 0.3f) continue
                emotionCounts[emotion] = (emotionCounts[emotion] ?: 0) + 1
                total++
            }

            if (total == 0) return emptyMap()
            emotionCounts.mapValues { it.value.toFloat() / total }
        } catch (e: Exception) {
            Log.w(TAG, "Emotion mining failed: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Mine daily routine: most common activity per hour from memory roles.
     */
    private fun mineDailyRoutine(): Map<Int, String> {
        return try {
            val oneDayAgo = System.currentTimeMillis() - 7 * 24 * 3600_000L
            val nodes = memoryDatabase.getNodesInTimeRange(oneDayAgo, System.currentTimeMillis())
            if (nodes.isEmpty()) return emptyMap()

            val hourActivity = mutableMapOf<Int, MutableMap<String, Int>>()

            for (node in nodes) {
                val cal = Calendar.getInstance().apply { timeInMillis = node.timestamp }
                val hour = cal.get(Calendar.HOUR_OF_DAY)

                val activity = when (node.role) {
                    "WHISPER" -> "대화"
                    "CAMERA" -> "시각기록"
                    "USER" -> "사용자요청"
                    else -> continue
                }

                val activities = hourActivity.getOrPut(hour) { mutableMapOf() }
                activities[activity] = (activities[activity] ?: 0) + 1
            }

            hourActivity.mapValues { (_, activities) ->
                activities.maxByOrNull { it.value }?.key ?: "기타"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Routine mining failed: ${e.message}")
            emptyMap()
        }
    }

    private fun computeSafetyAwareness(): Float {
        // Measure based on safety_monitor persona activity
        return try {
            val safetyNodes = memoryDatabase.getNodesByPersonaId("safety_monitor", limit = 20)
            val recentNodes = memoryDatabase.getNodesByRole("USER", limit = 50)
            if (recentNodes.isEmpty()) return 0.5f
            // Ratio of safety interactions to total
            (safetyNodes.size.toFloat() / recentNodes.size).coerceIn(0f, 1f)
        } catch (e: Exception) {
            0.5f
        }
    }

    private fun computeSocialResponsiveness(interactions: List<InteractionPattern>): Float {
        if (interactions.isEmpty()) return 0.5f
        val totalMeets = interactions.sumOf { it.meetCount }
        // Normalize: more interactions = higher responsiveness (cap at 1.0)
        return (totalMeets.toFloat() / 50).coerceIn(0f, 1f)
    }

    /**
     * Simple grid-based location clustering.
     */
    private fun clusterLocations(locations: List<Pair<Double, Double>>): List<Pair<Pair<Double, Double>, Int>> {
        // Grid-based: round to ~50m precision
        val gridSize = 0.0005 // ~50m at mid latitudes
        val gridCounts = mutableMapOf<Pair<Long, Long>, MutableList<Pair<Double, Double>>>()

        for ((lat, lon) in locations) {
            val gridKey = (lat / gridSize).toLong() to (lon / gridSize).toLong()
            gridCounts.getOrPut(gridKey) { mutableListOf() }.add(lat to lon)
        }

        return gridCounts.values.map { points ->
            val avgLat = points.sumOf { it.first } / points.size
            val avgLon = points.sumOf { it.second } / points.size
            (avgLat to avgLon) to points.size
        }
    }

    private fun haversineDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }

    /**
     * PC 서버 예측 엔진 결과를 PhysiologyAxis로 변환.
     * PredictionSyncService가 없거나 데이터 미수신 시 기본값 반환.
     */
    private fun buildPhysiologyFromPredictions(): PhysiologyAxis {
        val sync = predictionSyncService ?: return PhysiologyAxis()
        return try {
            val weekly = sync.latestWeeklyProfile
            val daily = sync.latestDailyPrediction

            val baselines = weekly?.optJSONObject("baselines")
            val signature = weekly?.optJSONObject("running_signature")
            val recovery = daily?.optJSONObject("recovery")
            val injury = daily?.optJSONObject("injury_risk")

            PhysiologyAxis(
                restingHr = baselines?.optInt("resting_hr", 60) ?: 60,
                lthrBpm = baselines?.optInt("lthr_bpm", 165) ?: 165,
                criticalSpeedMps = baselines?.optDouble("critical_speed_mps", 3.0)?.toFloat() ?: 3.0f,
                criticalSpeedPace = baselines?.optDouble("critical_speed_pace", 5.56)?.toFloat() ?: 5.56f,
                avgSleepEfficiency = baselines?.optDouble("avg_sleep_efficiency", 0.78)?.toFloat() ?: 0.78f,
                avgSleepHours = baselines?.optDouble("avg_sleep_hours", 7.0)?.toFloat() ?: 7.0f,
                runnerType = signature?.optString("type", "unknown") ?: "unknown",
                avgStiffnessKn = signature?.optDouble("avg_stiffness_kn", 0.0)?.toFloat() ?: 0f,
                avgGctMs = signature?.optInt("avg_gct_ms", 0) ?: 0,
                avgVerticalOscCm = signature?.optDouble("avg_vertical_osc_cm", 0.0)?.toFloat() ?: 0f,
                recoveryScore = recovery?.optDouble("recovery_score", 0.7)?.toFloat() ?: 0.7f,
                recommendation = recovery?.optString("recommendation", "moderate") ?: "moderate",
                injuryRiskLevel = injury?.optString("risk_level", "low") ?: "low",
                acwr = injury?.optDouble("acwr", 1.0)?.toFloat() ?: 1.0f,
                hrTrend = recovery?.optString("hr_trend", "STABLE") ?: "STABLE",
                lastSyncedAt = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Physiology build failed (using defaults): ${e.message}")
            PhysiologyAxis()
        }
    }

    /**
     * Persist profile to UnifiedMemoryDB with role="DIGITAL_TWIN".
     */
    private suspend fun persistProfile(profile: UserProfileModel) {
        try {
            if (memorySaveHelper != null) {
                memorySaveHelper.saveMemory(
                    content = profile.toSummaryString(),
                    role = "DIGITAL_TWIN",
                    metadata = profile.toJson().toString()
                )
            } else {
                // fallback: MemorySaveHelper 미주입 시 직접 저장 (임베딩 누락 허용)
                val node = UnifiedMemoryDatabase.MemoryNode(
                    timestamp = profile.lastUpdated,
                    role = "DIGITAL_TWIN",
                    content = profile.toSummaryString(),
                    metadata = profile.toJson().toString()
                )
                memoryDatabase.insertNode(node)
            }
            Log.d(TAG, "Profile persisted to DB")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist profile: ${e.message}")
        }
    }

    /**
     * Load the most recent profile from DB (on app restart).
     */
    fun loadFromDatabase() {
        try {
            val nodes = memoryDatabase.getNodesByRole("DIGITAL_TWIN", limit = 1)
            if (nodes.isNotEmpty()) {
                val json = JSONObject(nodes.first().metadata ?: return)
                _profile.value = UserProfileModel.fromJson(json)
                Log.i(TAG, "Loaded profile from DB (updated: ${_profile.value.lastUpdated})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load profile from DB: ${e.message}")
        }
    }
}
