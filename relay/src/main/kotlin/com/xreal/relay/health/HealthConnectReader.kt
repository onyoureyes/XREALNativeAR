package com.xreal.relay.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Health Connect API — Samsung Health / Garmin Connect 데이터 전량 읽기.
 *
 * 전제조건:
 *   1. Health Connect 앱 설치 (Android 14+는 시스템 내장)
 *   2. Samsung Health 6.22.5+: Android 14+ 자동 동기화, 13 이하 수동 설정 필요
 *   3. Garmin Connect 4.70+: 앱 설정 → Health Connect 연동 활성화 (단방향 쓰기만)
 *   4. 런타임 권한 승인 (Activity에서 PermissionController 사용)
 *
 * Samsung Health 동기화 데이터 (17종):
 *   Steps, Exercise, HeartRate, Sleep, Weight, BodyFat, Height, BMR,
 *   TotalCalories, Distance, Speed, Power, Vo2Max, BloodGlucose,
 *   BloodPressure, SpO2, Nutrition
 *
 * Garmin Connect 동기화 데이터 (12종):
 *   ActiveCalories, TotalCalories, CyclingCadence, Distance, Elevation,
 *   Exercise, FloorsClimbed, HeartRate, Speed, Steps, Sleep, BodyFat, Weight
 *
 * Garmin 미동기화: Body Battery, Training Load/Effect, Intensity Minutes,
 *   Running Power, Running Cadence, Sweat Loss, HRV Status, Endurance Score
 */
class HealthConnectReader(private val context: Context) {

    private val TAG = "HealthConnectReader"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    private var healthConnectClient: HealthConnectClient? = null

    // 최근 읽기 결과 (JSON)
    var latestSnapshot: JSONObject? = null
        private set

    // 읽기 주기 (기본 5분)
    var readIntervalMs = 5 * 60 * 1000L

    // 권한 상태
    var permissionsGranted = false
        private set

    companion object {
        /** Activity에서 사용할 필요 권한 목록 — Samsung + Garmin 전체 커버 */
        val REQUIRED_PERMISSIONS = setOf(
            // Activity
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(FloorsClimbedRecord::class),
            HealthPermission.getReadPermission(ElevationGainedRecord::class),
            HealthPermission.getReadPermission(SpeedRecord::class),
            HealthPermission.getReadPermission(PowerRecord::class),
            HealthPermission.getReadPermission(CyclingPedalingCadenceRecord::class),
            HealthPermission.getReadPermission(Vo2MaxRecord::class),
            // Vitals
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(BloodPressureRecord::class),
            HealthPermission.getReadPermission(BloodGlucoseRecord::class),
            HealthPermission.getReadPermission(BodyTemperatureRecord::class),
            HealthPermission.getReadPermission(RespiratoryRateRecord::class),
            // Body Measurements
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(HeightRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
            HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
            HealthPermission.getReadPermission(LeanBodyMassRecord::class),
            HealthPermission.getReadPermission(BoneMassRecord::class),
            HealthPermission.getReadPermission(BodyWaterMassRecord::class),
            // Sleep
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            // Nutrition
            HealthPermission.getReadPermission(NutritionRecord::class),
            HealthPermission.getReadPermission(HydrationRecord::class),
        )
    }

    fun start() {
        if (running.getAndSet(true)) return

        val availability = HealthConnectClient.getSdkStatus(context)
        if (availability != HealthConnectClient.SDK_AVAILABLE) {
            Log.w(TAG, "Health Connect 사용 불가 (status=$availability)")
            running.set(false)
            return
        }

        healthConnectClient = HealthConnectClient.getOrCreate(context)
        Log.i(TAG, "Health Connect 클라이언트 생성됨")

        scope.launch {
            checkPermissions()

            while (running.get()) {
                if (permissionsGranted) {
                    try {
                        latestSnapshot = readAll()
                        Log.d(TAG, "Health Connect 스냅샷 갱신 완료")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Health Connect 권한 거부됨: ${e.message}")
                        permissionsGranted = false
                    } catch (e: Exception) {
                        Log.w(TAG, "Health Connect 읽기 오류: ${e.message}")
                    }
                } else {
                    checkPermissions()
                }
                delay(if (permissionsGranted) readIntervalMs else 30_000)
            }
        }
    }

    fun stop() {
        running.set(false)
        scope.cancel()
        Log.i(TAG, "Health Connect 읽기 종료")
    }

    /** 권한 상태 확인 */
    suspend fun checkPermissions() {
        val client = healthConnectClient ?: return
        try {
            val granted = client.permissionController.getGrantedPermissions()
            // 일부 권한만 있어도 읽기 시도 (있는 것만 읽기)
            permissionsGranted = granted.isNotEmpty()
            val total = REQUIRED_PERMISSIONS.size
            val grantedCount = REQUIRED_PERMISSIONS.count { it in granted }
            if (grantedCount < total) {
                Log.w(TAG, "Health Connect 권한: $grantedCount/$total 승인됨")
            } else {
                Log.i(TAG, "Health Connect 권한 모두 승인됨 ($total)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "권한 확인 실패: ${e.message}")
            permissionsGranted = false
        }
    }

    /** 오늘 기준 전체 헬스 데이터 읽기 */
    suspend fun readAll(): JSONObject {
        val client = healthConnectClient
            ?: throw IllegalStateException("Health Connect not initialized")
        val result = JSONObject()
        val today = LocalDate.now()
        val startOfDay = today.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val now = Instant.now()

        result.put("timestamp", now.toEpochMilli())
        result.put("date", today.toString())

        // ===== VITALS =====

        readHeartRate(client, startOfDay, now, result)
        readHrv(client, startOfDay, now, result)
        readRestingHeartRate(client, startOfDay, now, result)
        readSpO2(client, startOfDay, now, result)
        readBloodPressure(client, startOfDay, now, result)
        readBloodGlucose(client, startOfDay, now, result)
        readBodyTemperature(client, startOfDay, now, result)
        readRespiratoryRate(client, startOfDay, now, result)

        // ===== ACTIVITY =====

        readSteps(client, startOfDay, now, result)
        readDistance(client, startOfDay, now, result)
        readCalories(client, startOfDay, now, result)
        readActiveCalories(client, startOfDay, now, result)
        readFloorsClimbed(client, startOfDay, now, result)
        readElevationGained(client, startOfDay, now, result)
        readVo2Max(client, today, now, result)
        readExerciseSessions(client, today, now, result)
        readSpeed(client, startOfDay, now, result)
        readPower(client, startOfDay, now, result)
        readCyclingCadence(client, startOfDay, now, result)

        // ===== BODY MEASUREMENTS =====

        readWeight(client, today, now, result)
        readHeight(client, today, now, result)
        readBodyFat(client, today, now, result)
        readBasalMetabolicRate(client, today, now, result)
        readLeanBodyMass(client, today, now, result)
        readBoneMass(client, today, now, result)
        readBodyWaterMass(client, today, now, result)

        // ===== SLEEP =====

        readSleep(client, today, now, result)

        // ===== NUTRITION =====

        readNutrition(client, startOfDay, now, result)
        readHydration(client, startOfDay, now, result)

        logSummary(result)
        return result
    }

    // ==================== VITALS ====================

    private suspend fun readHeartRate(
        client: HealthConnectClient, start: Instant, end: Instant, result: JSONObject
    ) {
        try {
            val records = client.readRecords(
                ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(start, end))
            )
            val arr = JSONArray()
            for (record in records.records) {
                val source = classifySource(record.metadata.dataOrigin.packageName)
                for (sample in record.samples) {
                    arr.put(JSONObject().apply {
                        put("bpm", sample.beatsPerMinute)
                        put("ts", sample.time.toEpochMilli())
                        put("source", source)
                    })
                }
            }
            result.put("heart_rate", arr)
        } catch (e: Exception) { logSkip("심박수", e) }
    }

    private suspend fun readHrv(
        client: HealthConnectClient, start: Instant, end: Instant, result: JSONObject
    ) {
        try {
            val records = client.readRecords(
                ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, TimeRangeFilter.between(start, end))
            )
            val arr = JSONArray()
            for (record in records.records) {
                arr.put(JSONObject().apply {
                    put("rmssd_ms", record.heartRateVariabilityMillis)
                    put("ts", record.time.toEpochMilli())
                    put("source", classifySource(record.metadata.dataOrigin.packageName))
                })
            }
            result.put("hrv", arr)
        } catch (e: Exception) { logSkip("HRV", e) }
    }

    private suspend fun readRestingHeartRate(
        client: HealthConnectClient, start: Instant, end: Instant, result: JSONObject
    ) {
        try {
            val records = client.readRecords(
                ReadRecordsRequest(RestingHeartRateRecord::class, TimeRangeFilter.between(start, end))
            )
            val arr = JSONArray()
            for (record in records.records) {
                arr.put(JSONObject().apply {
                    put("bpm", record.beatsPerMinute)
                    put("ts", record.time.toEpochMilli())
                    put("source", classifySource(record.metadata.dataOrigin.packageName))
                })
            }
            result.put("resting_heart_rate", arr)
        } catch (e: Exception) { logSkip("안정시 심박", e) }
    }

    private suspend fun readSpO2(
        client: HealthConnectClient, start: Instant, end: Instant, result: JSONObject
    ) {
        try {
            val records = client.readRecords(
                ReadRecordsRequest(OxygenSaturationRecord::class, TimeRangeFilter.between(start, end))
            )
            val arr = JSONArray()
            for (record in records.records) {
                arr.put(JSONObject().apply {
                    put("percentage", record.percentage.value)
                    put("ts", record.time.toEpochMilli())
                    put("source", classifySource(record.metadata.dataOrigin.packageName))
                })
            }
            result.put("spo2", arr)
        } catch (e: Exception) { logSkip("SpO2", e) }
    }

    private suspend fun readBloodPressure(
        client: HealthConnectClient, start: Instant, end: Instant, result: JSONObject
    ) {
        try {
            val records = client.readRecords(
                ReadRecordsRequest(BloodPressureRecord::class, TimeRangeFilter.between(start, end))
            )
            val arr = JSONArray()
            for (record in records.records) {
                arr.put(JSONObject().apply {
                    put("systolic_mmhg", record.systolic.inMillimetersOfMercury)
                    put("diastolic_mmhg", record.diastolic.inMillimetersOfMercury)
                    put("body_position", record.bodyPosition)
                    put("measurement_location", record.measurementLocation)
                    put("ts", record.time.toEpochMilli())
                    put("source", classifySource(record.metadata.dataOrigin.packageName))
                })
            }
            result.put("blood_pressure", arr)
        } catch (e: Exception) { logSkip("혈압", e) }
    }

    private suspend fun readBloodGlucose(
        client: HealthConnectClient, start: Instant, end: Instant, result: JSONObject
    ) {
        try {
            val records = client.readRecords(
                ReadRecordsRequest(BloodGlucoseRecord::class, TimeRangeFilter.between(start, end))
            )
            val arr = JSONArray()
            for (record in records.records) {
                arr.put(JSONObject().apply {
                    put("mmol_per_l", record.level.inMillimolesPerLiter)
                    put("specimen_source", record.specimenSource)
                    put("meal_type", record.mealType)
                    put("relation_to_meal", record.relationToMeal)
                    put("ts", record.time.toEpochMilli())
                    put("source", classifySource(record.metadata.dataOrigin.packageName))
                })
            }
            result.put("blood_glucose", arr)
        } catch (e: Exception) { logSkip("혈당", e) }
    }

    private suspend fun readBodyTemperature(
        client: HealthConnectClient, start: Instant, end: Instant, result: JSONObject
    ) {
        try {
            val records = client.readRecords(
                ReadRecordsRequest(BodyTemperatureRecord::class, TimeRangeFilter.between(start, end))
            )
            val arr = JSONArray()
            for (record in records.records) {
                arr.put(JSONObject().apply {
                    put("celsius", record.temperature.inCelsius)
                    put("measurement_location", record.measurementLocation)
                    put("ts", record.time.toEpochMilli())
                })
            }
            result.put("body_temperature", arr)
        } catch (e: Exception) { logSkip("체온", e) }
    }

    private suspend fun readRespiratoryRate(
        client: HealthConnectClient, start: Instant, end: Instant, result: JSONObject
    ) {
        try {
            val records = client.readRecords(
                ReadRecordsRequest(RespiratoryRateRecord::class, TimeRangeFilter.between(start, end))
            )
            val arr = JSONArray()
            for (record in records.records) {
                arr.put(JSONObject().apply {
                    put("breaths_per_min", record.rate)
                    put("ts", record.time.toEpochMilli())
                    put("source", classifySource(record.metadata.dataOrigin.packageName))
                })
            }
            result.put("respiratory_rate", arr)
        } catch (e: Exception) { logSkip("호흡수", e) }
    }

    // ==================== ACTIVITY ====================

    private suspend fun readSteps(
        client: HealthConnectClient, start: Instant, end: Instant, result: JSONObject
    ) {
        try {
            val records = client.readRecords(
                ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(start, end))
            )
            var totalSteps = 0L
            val bySource = mutableMapOf<String, Long>()
            for (record in records.records) {
                totalSteps += record.count
                val source = classifySource(record.metadata.dataOrigin.packageName)
                bySource[source] = (bySource[source] ?: 0L) + record.count
            }
            result.put("steps", totalSteps)
            result.put("steps_by_source", JSONObject(bySource as Map<*, *>))
        } catch (e: Exception) { logSkip("걸음수", e) }
    }

    private suspend fun readDistance(
        client: HealthConnectClient, start: Instant, end: Instant, result: JSONObject
    ) {
        try {
            val records = client.readRecords(
                ReadRecordsRequest(DistanceRecord::class, TimeRangeFilter.between(start, end))
            )
            var totalMeters = 0.0
            for (record in records.records) {
                totalMeters += record.distance.inMeters
            }
            result.put("distance_m", totalMeters)
        } catch (e: Exception) { logSkip("거리", e) }
    }

    private suspend fun readCalories(
        client: HealthConnectClient, start: Instant, end: Instant, result: JSONObject
    ) {
        try {
            val records = client.readRecords(
                ReadRecordsRequest(TotalCaloriesBurnedRecord::class, TimeRangeFilter.between(start, end))
            )
            var totalKcal = 0.0
            for (record in records.records) {
                totalKcal += record.energy.inKilocalories
            }
            result.put("calories_total_kcal", totalKcal)
        } catch (e: Exception) { logSkip("총 칼로리", e) }
    }

    private suspend fun readActiveCalories(
        client: HealthConnectClient, start: Instant, end: Instant, result: JSONObject
    ) {
        try {
            val records = client.readRecords(
                ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, TimeRangeFilter.between(start, end))
            )
            var activeKcal = 0.0
            for (record in records.records) {
                activeKcal += record.energy.inKilocalories
            }
            result.put("calories_active_kcal", activeKcal)
        } catch (e: Exception) { logSkip("활성 칼로리", e) }
    }

    private suspend fun readFloorsClimbed(
        client: HealthConnectClient, start: Instant, end: Instant, result: JSONObject
    ) {
        try {
            val records = client.readRecords(
                ReadRecordsRequest(FloorsClimbedRecord::class, TimeRangeFilter.between(start, end))
            )
            var totalFloors = 0.0
            for (record in records.records) {
                totalFloors += record.floors
            }
            result.put("floors_climbed", totalFloors)
        } catch (e: Exception) { logSkip("층수", e) }
    }

    private suspend fun readElevationGained(
        client: HealthConnectClient, start: Instant, end: Instant, result: JSONObject
    ) {
        try {
            val records = client.readRecords(
                ReadRecordsRequest(ElevationGainedRecord::class, TimeRangeFilter.between(start, end))
            )
            var totalMeters = 0.0
            for (record in records.records) {
                totalMeters += record.elevation.inMeters
            }
            result.put("elevation_gained_m", totalMeters)
        } catch (e: Exception) { logSkip("고도 상승", e) }
    }

    private suspend fun readVo2Max(
        client: HealthConnectClient, today: LocalDate, end: Instant, result: JSONObject
    ) {
        try {
            val start30d = today.minusDays(30).atStartOfDay(ZoneId.systemDefault()).toInstant()
            val records = client.readRecords(
                ReadRecordsRequest(Vo2MaxRecord::class, TimeRangeFilter.between(start30d, end))
            )
            val arr = JSONArray()
            for (record in records.records) {
                arr.put(JSONObject().apply {
                    put("vo2_max_ml_kg_min", record.vo2MillilitersPerMinuteKilogram)
                    put("measurement_method", record.measurementMethod)
                    put("ts", record.time.toEpochMilli())
                    put("source", classifySource(record.metadata.dataOrigin.packageName))
                })
            }
            result.put("vo2_max", arr)
        } catch (e: Exception) { logSkip("VO2 Max", e) }
    }

    private suspend fun readExerciseSessions(
        client: HealthConnectClient, today: LocalDate, end: Instant, result: JSONObject
    ) {
        try {
            val start7d = today.minusDays(7).atStartOfDay(ZoneId.systemDefault()).toInstant()
            val records = client.readRecords(
                ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.between(start7d, end))
            )
            val arr = JSONArray()
            for (record in records.records) {
                val duration = Duration.between(record.startTime, record.endTime)
                arr.put(JSONObject().apply {
                    put("type", exerciseTypeName(record.exerciseType))
                    put("type_id", record.exerciseType)
                    put("start", record.startTime.toEpochMilli())
                    put("end", record.endTime.toEpochMilli())
                    put("duration_min", duration.toMinutes())
                    put("title", record.title ?: "")
                    put("notes", record.notes ?: "")
                    put("source", classifySource(record.metadata.dataOrigin.packageName))
                })
            }
            result.put("exercises", arr)
        } catch (e: Exception) { logSkip("운동 세션", e) }
    }

    private suspend fun readSpeed(
        client: HealthConnectClient, start: Instant, end: Instant, result: JSONObject
    ) {
        try {
            val records = client.readRecords(
                ReadRecordsRequest(SpeedRecord::class, TimeRangeFilter.between(start, end))
            )
            val arr = JSONArray()
            for (record in records.records) {
                for (sample in record.samples) {
                    arr.put(JSONObject().apply {
                        put("m_per_s", sample.speed.inMetersPerSecond)
                        put("ts", sample.time.toEpochMilli())
                    })
                }
            }
            if (arr.length() > 0) result.put("speed", arr)
        } catch (e: Exception) { logSkip("속도", e) }
    }

    private suspend fun readPower(
        client: HealthConnectClient, start: Instant, end: Instant, result: JSONObject
    ) {
        try {
            val records = client.readRecords(
                ReadRecordsRequest(PowerRecord::class, TimeRangeFilter.between(start, end))
            )
            val arr = JSONArray()
            for (record in records.records) {
                for (sample in record.samples) {
                    arr.put(JSONObject().apply {
                        put("watts", sample.power.inWatts)
                        put("ts", sample.time.toEpochMilli())
                    })
                }
            }
            if (arr.length() > 0) result.put("power", arr)
        } catch (e: Exception) { logSkip("파워", e) }
    }

    private suspend fun readCyclingCadence(
        client: HealthConnectClient, start: Instant, end: Instant, result: JSONObject
    ) {
        try {
            val records = client.readRecords(
                ReadRecordsRequest(CyclingPedalingCadenceRecord::class, TimeRangeFilter.between(start, end))
            )
            val arr = JSONArray()
            for (record in records.records) {
                for (sample in record.samples) {
                    arr.put(JSONObject().apply {
                        put("rpm", sample.revolutionsPerMinute)
                        put("ts", sample.time.toEpochMilli())
                    })
                }
            }
            if (arr.length() > 0) result.put("cycling_cadence", arr)
        } catch (e: Exception) { logSkip("사이클링 케이던스", e) }
    }

    // ==================== BODY MEASUREMENTS ====================

    private suspend fun readWeight(
        client: HealthConnectClient, today: LocalDate, end: Instant, result: JSONObject
    ) {
        try {
            val start90d = today.minusDays(90).atStartOfDay(ZoneId.systemDefault()).toInstant()
            val records = client.readRecords(
                ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.between(start90d, end))
            )
            val arr = JSONArray()
            for (record in records.records) {
                arr.put(JSONObject().apply {
                    put("kg", record.weight.inKilograms)
                    put("ts", record.time.toEpochMilli())
                    put("source", classifySource(record.metadata.dataOrigin.packageName))
                })
            }
            result.put("weight", arr)
        } catch (e: Exception) { logSkip("체중", e) }
    }

    private suspend fun readHeight(
        client: HealthConnectClient, today: LocalDate, end: Instant, result: JSONObject
    ) {
        try {
            val start = today.minusDays(365).atStartOfDay(ZoneId.systemDefault()).toInstant()
            val records = client.readRecords(
                ReadRecordsRequest(HeightRecord::class, TimeRangeFilter.between(start, end))
            )
            if (records.records.isNotEmpty()) {
                val latest = records.records.last()
                result.put("height_cm", latest.height.inMeters * 100.0)
            }
        } catch (e: Exception) { logSkip("키", e) }
    }

    private suspend fun readBodyFat(
        client: HealthConnectClient, today: LocalDate, end: Instant, result: JSONObject
    ) {
        try {
            val start90d = today.minusDays(90).atStartOfDay(ZoneId.systemDefault()).toInstant()
            val records = client.readRecords(
                ReadRecordsRequest(BodyFatRecord::class, TimeRangeFilter.between(start90d, end))
            )
            val arr = JSONArray()
            for (record in records.records) {
                arr.put(JSONObject().apply {
                    put("percentage", record.percentage.value)
                    put("ts", record.time.toEpochMilli())
                    put("source", classifySource(record.metadata.dataOrigin.packageName))
                })
            }
            result.put("body_fat", arr)
        } catch (e: Exception) { logSkip("체지방", e) }
    }

    private suspend fun readBasalMetabolicRate(
        client: HealthConnectClient, today: LocalDate, end: Instant, result: JSONObject
    ) {
        try {
            val start30d = today.minusDays(30).atStartOfDay(ZoneId.systemDefault()).toInstant()
            val records = client.readRecords(
                ReadRecordsRequest(BasalMetabolicRateRecord::class, TimeRangeFilter.between(start30d, end))
            )
            if (records.records.isNotEmpty()) {
                val latest = records.records.last()
                result.put("bmr_kcal_per_day", latest.basalMetabolicRate.inKilocaloriesPerDay)
            }
        } catch (e: Exception) { logSkip("BMR", e) }
    }

    private suspend fun readLeanBodyMass(
        client: HealthConnectClient, today: LocalDate, end: Instant, result: JSONObject
    ) {
        try {
            val start90d = today.minusDays(90).atStartOfDay(ZoneId.systemDefault()).toInstant()
            val records = client.readRecords(
                ReadRecordsRequest(LeanBodyMassRecord::class, TimeRangeFilter.between(start90d, end))
            )
            if (records.records.isNotEmpty()) {
                val latest = records.records.last()
                result.put("lean_body_mass_kg", latest.mass.inKilograms)
            }
        } catch (e: Exception) { logSkip("제지방량", e) }
    }

    private suspend fun readBoneMass(
        client: HealthConnectClient, today: LocalDate, end: Instant, result: JSONObject
    ) {
        try {
            val start = today.minusDays(365).atStartOfDay(ZoneId.systemDefault()).toInstant()
            val records = client.readRecords(
                ReadRecordsRequest(BoneMassRecord::class, TimeRangeFilter.between(start, end))
            )
            if (records.records.isNotEmpty()) {
                val latest = records.records.last()
                result.put("bone_mass_kg", latest.mass.inKilograms)
            }
        } catch (e: Exception) { logSkip("골량", e) }
    }

    private suspend fun readBodyWaterMass(
        client: HealthConnectClient, today: LocalDate, end: Instant, result: JSONObject
    ) {
        try {
            val start = today.minusDays(90).atStartOfDay(ZoneId.systemDefault()).toInstant()
            val records = client.readRecords(
                ReadRecordsRequest(BodyWaterMassRecord::class, TimeRangeFilter.between(start, end))
            )
            if (records.records.isNotEmpty()) {
                val latest = records.records.last()
                result.put("body_water_mass_kg", latest.mass.inKilograms)
            }
        } catch (e: Exception) { logSkip("체수분", e) }
    }

    // ==================== SLEEP ====================

    private suspend fun readSleep(
        client: HealthConnectClient, today: LocalDate, end: Instant, result: JSONObject
    ) {
        try {
            val sleepStart = today.minusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
            val records = client.readRecords(
                ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(sleepStart, end))
            )
            val arr = JSONArray()
            for (record in records.records) {
                val duration = Duration.between(record.startTime, record.endTime)
                val stagesArray = JSONArray()
                for (stage in record.stages) {
                    stagesArray.put(JSONObject().apply {
                        put("stage", stageName(stage.stage))
                        put("start", stage.startTime.toEpochMilli())
                        put("end", stage.endTime.toEpochMilli())
                        put("duration_min", Duration.between(stage.startTime, stage.endTime).toMinutes())
                    })
                }
                arr.put(JSONObject().apply {
                    put("start", record.startTime.toEpochMilli())
                    put("end", record.endTime.toEpochMilli())
                    put("duration_min", duration.toMinutes())
                    put("stages", stagesArray)
                    put("source", classifySource(record.metadata.dataOrigin.packageName))
                })
            }
            result.put("sleep", arr)
        } catch (e: Exception) { logSkip("수면", e) }
    }

    // ==================== NUTRITION ====================

    private suspend fun readNutrition(
        client: HealthConnectClient, start: Instant, end: Instant, result: JSONObject
    ) {
        try {
            val records = client.readRecords(
                ReadRecordsRequest(NutritionRecord::class, TimeRangeFilter.between(start, end))
            )
            val arr = JSONArray()
            for (record in records.records) {
                arr.put(JSONObject().apply {
                    put("name", record.name ?: "")
                    put("meal_type", record.mealType)
                    put("energy_kcal", record.energy?.inKilocalories ?: 0.0)
                    put("protein_g", record.protein?.inGrams ?: 0.0)
                    put("fat_g", record.totalFat?.inGrams ?: 0.0)
                    put("carbs_g", record.totalCarbohydrate?.inGrams ?: 0.0)
                    put("fiber_g", record.dietaryFiber?.inGrams ?: 0.0)
                    put("sugar_g", record.sugar?.inGrams ?: 0.0)
                    put("sodium_mg", record.sodium?.inMilligrams ?: 0.0)
                    put("cholesterol_mg", record.cholesterol?.inMilligrams ?: 0.0)
                    put("saturated_fat_g", record.saturatedFat?.inGrams ?: 0.0)
                    put("caffeine_mg", record.caffeine?.inMilligrams ?: 0.0)
                    put("start", record.startTime.toEpochMilli())
                    put("end", record.endTime.toEpochMilli())
                    put("source", classifySource(record.metadata.dataOrigin.packageName))
                })
            }
            if (arr.length() > 0) result.put("nutrition", arr)
        } catch (e: Exception) { logSkip("영양", e) }
    }

    private suspend fun readHydration(
        client: HealthConnectClient, start: Instant, end: Instant, result: JSONObject
    ) {
        try {
            val records = client.readRecords(
                ReadRecordsRequest(HydrationRecord::class, TimeRangeFilter.between(start, end))
            )
            var totalLiters = 0.0
            for (record in records.records) {
                totalLiters += record.volume.inLiters
            }
            if (totalLiters > 0) result.put("hydration_liters", totalLiters)
        } catch (e: Exception) { logSkip("수분 섭취", e) }
    }

    // ==================== HELPERS ====================

    private fun logSkip(name: String, e: Exception) {
        if (e is SecurityException) {
            Log.d(TAG, "$name: 권한 없음")
        } else {
            Log.w(TAG, "$name 읽기 실패: ${e.message}")
        }
    }

    private fun logSummary(result: JSONObject) {
        Log.i(TAG, "Health Connect 읽기 완료: " +
                "HR=${result.optJSONArray("heart_rate")?.length() ?: 0}, " +
                "steps=${result.optLong("steps", 0)}, " +
                "sleep=${result.optJSONArray("sleep")?.length() ?: 0}, " +
                "exercises=${result.optJSONArray("exercises")?.length() ?: 0}, " +
                "weight=${result.optJSONArray("weight")?.length() ?: 0}")
    }

    private fun classifySource(packageName: String): String {
        return when {
            packageName.contains("samsung") || packageName.contains("shealth") -> "samsung_health"
            packageName.contains("garmin") -> "garmin_connect"
            packageName.contains("fitbit") -> "fitbit"
            packageName.contains("google") -> "google_fit"
            packageName.contains("strava") -> "strava"
            packageName.contains("polar") -> "polar"
            packageName.contains("whoop") -> "whoop"
            packageName.contains("coros") -> "coros"
            packageName.contains("suunto") -> "suunto"
            else -> packageName
        }
    }

    private fun stageName(stage: Int): String {
        return when (stage) {
            SleepSessionRecord.STAGE_TYPE_AWAKE -> "awake"
            SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
            SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
            SleepSessionRecord.STAGE_TYPE_REM -> "rem"
            SleepSessionRecord.STAGE_TYPE_SLEEPING -> "sleeping"
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "out_of_bed"
            else -> "unknown_$stage"
        }
    }

    private fun exerciseTypeName(type: Int): String {
        return when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "running"
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walking"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "biking"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "swimming_open_water"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "swimming_pool"
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "hiking"
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "yoga"
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "weightlifting"
            ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "elliptical"
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "rowing"
            ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING -> "stair_climbing"
            ExerciseSessionRecord.EXERCISE_TYPE_GOLF -> "golf"
            ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "dancing"
            ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON -> "badminton"
            ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "tennis"
            ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS -> "table_tennis"
            ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "soccer"
            ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL -> "basketball"
            ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL -> "baseball"
            ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS -> "martial_arts"
            ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "pilates"
            ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING -> "rock_climbing"
            ExerciseSessionRecord.EXERCISE_TYPE_SKIING -> "skiing"
            ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING -> "snowboarding"
            ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> "stretching"
            ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "calisthenics"
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "hiit"
            else -> "exercise_$type"
        }
    }

    /** PC 서버로 전송할 요약 JSON */
    fun getSyncPayload(): JSONObject {
        return latestSnapshot ?: JSONObject().put("status", "no_data")
    }

    val isRunning: Boolean get() = running.get()
}
