package com.xreal.nativear.sync

import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * PredictionSyncService — PC 서버의 디지털트윈 예측 결과를 주기적으로 pull.
 *
 * ## 데이터 흐름
 * 1. PC 서버 (DuckDB 10년 이력 + 앱 백업 데이터) → ML 예측 엔진
 * 2. 이 서비스가 /api/digital-twin/daily, /api/digital-twin/predictions pull
 * 3. structured_data(domain="dt_prediction")에 저장
 * 4. DigitalTwinBuilder, RunningCoachManager 등이 소비
 *
 * ## 동기화 주기
 * - 일일 예측: 1시간 주기
 * - 주간 프로파일: 6시간 주기
 * - 데이터 싱크 후 캐시 무효화 요청
 */
class PredictionSyncService(
    private val database: UnifiedMemoryDatabase,
    private val eventBus: GlobalEventBus,
    private val syncConfig: BackupSyncConfig
) {
    companion object {
        private const val TAG = "PredictionSync"
        private const val DOMAIN = "dt_prediction"
        private const val DAILY_INTERVAL_MS = 3600_000L       // 1시간
        private const val WEEKLY_INTERVAL_MS = 6 * 3600_000L  // 6시간
        private const val INITIAL_DELAY_MS = 120_000L          // 2분 (앱 초기화 후)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dailyJob: Job? = null
    private var weeklyJob: Job? = null

    // 최근 예측 결과 캐시 (메모리)
    @Volatile var latestDailyPrediction: JSONObject? = null
        private set
    @Volatile var latestWeeklyProfile: JSONObject? = null
        private set

    fun start() {
        if (!syncConfig.isConfigured) {
            Log.d(TAG, "Sync not configured, PredictionSync disabled")
            return
        }

        // 시작 시 DB에서 캐시 복원
        loadCachedPredictions()

        // 일일 예측 루프
        dailyJob = scope.launch {
            delay(INITIAL_DELAY_MS)
            Log.i(TAG, "Daily prediction sync started (interval: ${DAILY_INTERVAL_MS / 60_000}min)")
            while (isActive) {
                try {
                    syncDailyPredictions()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Daily prediction sync failed: ${e.message}")
                }
                delay(DAILY_INTERVAL_MS)
            }
        }

        // 주간 프로파일 루프
        weeklyJob = scope.launch {
            delay(INITIAL_DELAY_MS + 30_000L)  // 일일 먼저, 30초 후
            Log.i(TAG, "Weekly profile sync started (interval: ${WEEKLY_INTERVAL_MS / 3600_000}h)")
            while (isActive) {
                try {
                    syncWeeklyProfile()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Weekly profile sync failed: ${e.message}")
                }
                delay(WEEKLY_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        dailyJob?.cancel()
        weeklyJob?.cancel()
        scope.cancel()
    }

    // ─── 일일 예측 동기화 ───

    private suspend fun syncDailyPredictions() {
        val serverUrl = syncConfig.serverUrl.ifBlank { return }
        val apiKey = syncConfig.apiKey.ifBlank { return }

        val json = httpGet("$serverUrl/api/digital-twin/daily", apiKey) ?: return

        // DB 저장
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        database.upsertStructuredData(
            domain = DOMAIN,
            dataKey = "daily_$today",
            value = json.toString(),
            tags = "prediction,daily,$today"
        )

        latestDailyPrediction = json
        Log.i(TAG, "Daily prediction synced: recovery=${json.optJSONObject("recovery")?.optDouble("recovery_score")}")

        // 이벤트 발행 — DigitalTwinBuilder 등이 구독
        try {
            eventBus.publish(XRealEvent.SystemEvent.DebugLog(
                "디지털트윈 일일 예측 갱신 (회복: ${json.optJSONObject("recovery")?.optDouble("recovery_score", 0.0)})"
            ))
        } catch (_: Exception) {}
    }

    // ─── 주간 프로파일 동기화 ───

    private suspend fun syncWeeklyProfile() {
        val serverUrl = syncConfig.serverUrl.ifBlank { return }
        val apiKey = syncConfig.apiKey.ifBlank { return }

        val json = httpGet("$serverUrl/api/digital-twin/predictions", apiKey) ?: return

        // DB 저장 (단일 키 — 항상 최신 1건만)
        database.upsertStructuredData(
            domain = DOMAIN,
            dataKey = "weekly_profile",
            value = json.toString(),
            tags = "prediction,profile,baseline"
        )

        latestWeeklyProfile = json
        Log.i(TAG, "Weekly profile synced: CS=${json.optJSONObject("baselines")?.optDouble("critical_speed_mps")} m/s")
    }

    // ─── DB 캐시 복원 ───

    private fun loadCachedPredictions() {
        try {
            val weekly = database.getStructuredDataExact(DOMAIN, "weekly_profile")
            if (weekly != null) {
                latestWeeklyProfile = JSONObject(weekly.value)
                Log.d(TAG, "Restored cached weekly profile")
            }

            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date())
            val daily = database.getStructuredDataExact(DOMAIN, "daily_$today")
            if (daily != null) {
                latestDailyPrediction = JSONObject(daily.value)
                Log.d(TAG, "Restored cached daily prediction")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cache restore failed: ${e.message}")
        }
    }

    // ─── 외부 접근 API ───

    /** 회복 점수 (0~1). 없으면 null. */
    fun getRecoveryScore(): Float? =
        latestDailyPrediction?.optJSONObject("recovery")?.optDouble("recovery_score")?.toFloat()

    /** 오늘 권장 운동 강도 (rest/easy/moderate/hard) */
    fun getRecommendation(): String? =
        latestDailyPrediction?.optJSONObject("recovery")?.optString("recommendation")

    /** 부상 위험 레벨 (low/moderate/high) */
    fun getInjuryRiskLevel(): String? =
        latestDailyPrediction?.optJSONObject("injury_risk")?.optString("risk_level")

    /** LTHR (bpm). 없으면 기본값 165. */
    fun getLthr(): Int =
        latestWeeklyProfile?.optJSONObject("baselines")?.optInt("lthr_bpm", 165) ?: 165

    /** Critical Speed (m/s) */
    fun getCriticalSpeed(): Float =
        latestWeeklyProfile?.optJSONObject("baselines")?.optDouble("critical_speed_mps", 3.0)?.toFloat() ?: 3.0f

    /** 안정시 HR 기저선 */
    fun getRestingHrBaseline(): Int =
        latestWeeklyProfile?.optJSONObject("baselines")?.optInt("resting_hr", 60) ?: 60

    /** 수면 효율 기저선 (0~1) */
    fun getSleepEfficiencyBaseline(): Float =
        latestWeeklyProfile?.optJSONObject("baselines")?.optDouble("avg_sleep_efficiency", 0.78)?.toFloat() ?: 0.78f

    /** 러닝 역학 시그니처 타입 (elastic/grinder/balanced) */
    fun getRunnerType(): String =
        latestWeeklyProfile?.optJSONObject("running_signature")?.optString("type", "unknown") ?: "unknown"

    /** 프로파일 전체를 문자열 요약 (AI 프롬프트 주입용) */
    fun getProfileSummary(): String {
        val profile = latestWeeklyProfile ?: return "디지털트윈 프로파일 없음 (서버 동기화 대기)"
        val baselines = profile.optJSONObject("baselines") ?: return "기저선 데이터 없음"
        val sig = profile.optJSONObject("running_signature")
        val daily = latestDailyPrediction

        return buildString {
            appendLine("[생리 기저선]")
            appendLine("- 안정시 HR: ${baselines.optInt("resting_hr")} bpm")
            appendLine("- Critical Speed: ${baselines.optDouble("critical_speed_pace")} min/km")
            appendLine("- LTHR: ${baselines.optInt("lthr_bpm")} bpm")
            appendLine("- 수면 효율: ${(baselines.optDouble("avg_sleep_efficiency") * 100).toInt()}%")

            if (sig != null) {
                appendLine("[러닝 시그니처: ${sig.optString("type")}]")
                appendLine("- Stiffness: ${sig.optDouble("avg_stiffness_kn")} kN/m, GCT: ${sig.optInt("avg_gct_ms")}ms")
            }

            if (daily != null) {
                val recovery = daily.optJSONObject("recovery")
                if (recovery != null) {
                    appendLine("[오늘 상태]")
                    appendLine("- 회복: ${(recovery.optDouble("recovery_score") * 100).toInt()}% (${recovery.optString("recommendation")})")
                    appendLine("- HR 추세: ${recovery.optString("hr_trend")}")
                }
                val injury = daily.optJSONObject("injury_risk")
                if (injury != null) {
                    appendLine("- 부상 위험: ${injury.optString("risk_level")} (ACWR: ${injury.optDouble("acwr")})")
                }
            }
        }.trim()
    }

    // ─── HTTP Helper ───

    private suspend fun httpGet(url: String, apiKey: String): JSONObject? {
        return withContext(Dispatchers.IO) {
            try {
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.connectTimeout = 10_000
                connection.readTimeout = 30_000

                if (connection.responseCode == 200) {
                    val body = connection.inputStream.bufferedReader().readText()
                    JSONObject(body)
                } else {
                    Log.w(TAG, "HTTP $url → ${connection.responseCode}")
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "HTTP GET $url failed: ${e.message}")
                null
            }
        }
    }
}
