package com.xreal.wear.sensor

import org.json.JSONObject

/**
 * Sensor data types streamed from Galaxy Watch to phone.
 */
sealed class SensorData {
    abstract val timestamp: Long
    abstract fun toJson(): String

    data class HeartRate(
        val bpm: Float,
        override val timestamp: Long
    ) : SensorData() {
        override fun toJson() = JSONObject().apply {
            put("type", "hr")
            put("bpm", bpm.toDouble())
            put("ts", timestamp)
        }.toString()
    }

    data class Hrv(
        val rmssd: Float,
        val sdnn: Float,
        val meanRR: Float,
        val sampleCount: Int,
        override val timestamp: Long
    ) : SensorData() {
        override fun toJson() = JSONObject().apply {
            put("type", "hrv")
            put("rmssd", rmssd.toDouble())
            put("sdnn", sdnn.toDouble())
            put("mean_rr", meanRR.toDouble())
            put("samples", sampleCount)
            put("ts", timestamp)
        }.toString()
    }

    data class Gps(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val accuracy: Float,
        val speed: Float,
        override val timestamp: Long
    ) : SensorData() {
        override fun toJson() = JSONObject().apply {
            put("type", "gps")
            put("lat", latitude)
            put("lon", longitude)
            put("alt", altitude)
            put("acc", accuracy.toDouble())
            put("spd", speed.toDouble())
            put("ts", timestamp)
        }.toString()
    }

    data class SkinTemperature(
        val temperature: Float,
        val ambientTemperature: Float = 0f,
        override val timestamp: Long
    ) : SensorData() {
        override fun toJson() = JSONObject().apply {
            put("type", "skin_temp")
            put("temp", temperature.toDouble())
            put("ambient", ambientTemperature.toDouble())
            put("ts", timestamp)
        }.toString()
    }

    data class Accelerometer(
        val x: Float,
        val y: Float,
        val z: Float,
        override val timestamp: Long
    ) : SensorData() {
        override fun toJson() = JSONObject().apply {
            put("type", "accel")
            put("x", x.toDouble())
            put("y", y.toDouble())
            put("z", z.toDouble())
            put("ts", timestamp)
        }.toString()
    }

    data class SpO2(
        val spo2: Int,
        override val timestamp: Long
    ) : SensorData() {
        override fun toJson() = JSONObject().apply {
            put("type", "spo2")
            put("spo2", spo2)
            put("ts", timestamp)
        }.toString()
    }

    companion object {
        fun fromJson(json: String): SensorData? {
            return try {
                val obj = JSONObject(json)
                when (obj.getString("type")) {
                    "hr" -> HeartRate(
                        bpm = obj.getDouble("bpm").toFloat(),
                        timestamp = obj.getLong("ts")
                    )
                    "hrv" -> Hrv(
                        rmssd = obj.getDouble("rmssd").toFloat(),
                        sdnn = obj.getDouble("sdnn").toFloat(),
                        meanRR = obj.getDouble("mean_rr").toFloat(),
                        sampleCount = obj.getInt("samples"),
                        timestamp = obj.getLong("ts")
                    )
                    "gps" -> Gps(
                        latitude = obj.getDouble("lat"),
                        longitude = obj.getDouble("lon"),
                        altitude = obj.getDouble("alt"),
                        accuracy = obj.getDouble("acc").toFloat(),
                        speed = obj.getDouble("spd").toFloat(),
                        timestamp = obj.getLong("ts")
                    )
                    "skin_temp" -> SkinTemperature(
                        temperature = obj.getDouble("temp").toFloat(),
                        ambientTemperature = obj.optDouble("ambient", 0.0).toFloat(),
                        timestamp = obj.getLong("ts")
                    )
                    "accel" -> Accelerometer(
                        x = obj.getDouble("x").toFloat(),
                        y = obj.getDouble("y").toFloat(),
                        z = obj.getDouble("z").toFloat(),
                        timestamp = obj.getLong("ts")
                    )
                    "spo2" -> SpO2(
                        spo2 = obj.getInt("spo2"),
                        timestamp = obj.getLong("ts")
                    )
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
