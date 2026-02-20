package com.xreal.hardware

import kotlin.math.sqrt

/**
 * Madgwick AHRS (Attitude and Heading Reference System) filter.
 *
 * Fuses gyroscope and accelerometer data to produce a stable quaternion
 * orientation estimate. Based on Sebastian Madgwick's 2010 paper:
 * "An efficient orientation filter for inertial and inertial/magnetic sensor arrays"
 *
 * The filter works by:
 * 1. Integrating gyroscope readings (fast but drifts)
 * 2. Correcting drift using accelerometer gravity reference
 * 3. Beta parameter controls correction aggressiveness
 *
 * Output quaternion represents rotation from sensor frame to earth frame.
 * Earth frame: X=North/East, Y=East/North, Z=Up
 *
 * @param beta Filter gain (0.01 = trust gyro more, 0.5 = trust accel more)
 *             Typical values: 0.033 for slow motion, 0.1 for fast motion
 * @param sampleFreq Expected sample frequency in Hz (for initial dt estimate)
 */
class MadgwickAHRS(
    private var beta: Float = 0.05f,
    private var sampleFreq: Float = 1000f
) {
    // Quaternion of sensor frame relative to earth frame [w, x, y, z]
    var q0 = 1.0f; private set
    var q1 = 0.0f; private set
    var q2 = 0.0f; private set
    var q3 = 0.0f; private set

    private var lastTimestampUs: Long = 0
    private var initialized = false

    /**
     * Update the filter with new gyroscope and accelerometer readings.
     *
     * @param gx Gyroscope X in rad/s
     * @param gy Gyroscope Y in rad/s
     * @param gz Gyroscope Z in rad/s
     * @param ax Accelerometer X in m/s² (or g, normalization handles it)
     * @param ay Accelerometer Y in m/s²
     * @param az Accelerometer Z in m/s²
     * @param timestampUs Sensor timestamp in microseconds
     */
    fun update(gx: Float, gy: Float, gz: Float,
               ax: Float, ay: Float, az: Float,
               timestampUs: Long) {

        // Calculate dt from timestamps
        val dt: Float
        if (!initialized || lastTimestampUs == 0L) {
            dt = 1.0f / sampleFreq
            initialized = true
        } else {
            val dtUs = timestampUs - lastTimestampUs
            dt = if (dtUs > 0 && dtUs < 1_000_000) { // sanity: 0 < dt < 1 second
                dtUs.toFloat() / 1_000_000f
            } else {
                1.0f / sampleFreq
            }
        }
        lastTimestampUs = timestampUs

        // Local copies for performance
        var q0 = this.q0
        var q1 = this.q1
        var q2 = this.q2
        var q3 = this.q3

        // Rate of change of quaternion from gyroscope
        var qDot1 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz)
        var qDot2 = 0.5f * ( q0 * gx + q2 * gz - q3 * gy)
        var qDot3 = 0.5f * ( q0 * gy - q1 * gz + q3 * gx)
        var qDot4 = 0.5f * ( q0 * gz + q1 * gy - q2 * gx)

        // Compute feedback only if accelerometer measurement valid
        // (avoids NaN in normalisation)
        val aNorm = sqrt(ax * ax + ay * ay + az * az)
        if (aNorm > 0.01f) {
            // Normalise accelerometer measurement
            val recipNorm = 1.0f / aNorm
            val axn = ax * recipNorm
            val ayn = ay * recipNorm
            val azn = az * recipNorm

            // Auxiliary variables to avoid repeated arithmetic
            val _2q0 = 2.0f * q0
            val _2q1 = 2.0f * q1
            val _2q2 = 2.0f * q2
            val _2q3 = 2.0f * q3
            val _4q0 = 4.0f * q0
            val _4q1 = 4.0f * q1
            val _4q2 = 4.0f * q2
            val _8q1 = 8.0f * q1
            val _8q2 = 8.0f * q2
            val q0q0 = q0 * q0
            val q1q1 = q1 * q1
            val q2q2 = q2 * q2
            val q3q3 = q3 * q3

            // Gradient descent algorithm corrective step
            var s0 = _4q0 * q2q2 + _2q2 * axn + _4q0 * q1q1 - _2q1 * ayn
            var s1 = _4q1 * q3q3 - _2q3 * axn + 4.0f * q0q0 * q1 - _2q0 * ayn - _4q1 + _8q1 * q1q1 + _8q1 * q2q2 + _4q1 * azn
            var s2 = 4.0f * q0q0 * q2 + _2q0 * axn + _4q2 * q3q3 - _2q3 * ayn - _4q2 + _8q2 * q1q1 + _8q2 * q2q2 + _4q2 * azn
            var s3 = 4.0f * q1q1 * q3 - _2q1 * axn + 4.0f * q2q2 * q3 - _2q2 * ayn

            // Normalise step magnitude
            val sNorm = sqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3)
            if (sNorm > 0.0f) {
                val recipSNorm = 1.0f / sNorm
                s0 *= recipSNorm
                s1 *= recipSNorm
                s2 *= recipSNorm
                s3 *= recipSNorm
            }

            // Apply feedback step
            qDot1 -= beta * s0
            qDot2 -= beta * s1
            qDot3 -= beta * s2
            qDot4 -= beta * s3
        }

        // Integrate rate of change of quaternion to yield quaternion
        q0 += qDot1 * dt
        q1 += qDot2 * dt
        q2 += qDot3 * dt
        q3 += qDot4 * dt

        // Normalise quaternion
        val qNorm = sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        if (qNorm > 0.0f) {
            val recipQNorm = 1.0f / qNorm
            q0 *= recipQNorm
            q1 *= recipQNorm
            q2 *= recipQNorm
            q3 *= recipQNorm
        }

        // Store back
        this.q0 = q0
        this.q1 = q1
        this.q2 = q2
        this.q3 = q3
    }

    /**
     * Get Euler angles from current quaternion (in degrees).
     * Returns [roll, pitch, yaw]
     *
     * Roll: rotation around X axis (-180 to 180)
     * Pitch: rotation around Y axis (-90 to 90)
     * Yaw: rotation around Z axis (-180 to 180)
     */
    fun getEulerDegrees(): FloatArray {
        val roll = Math.toDegrees(
            kotlin.math.atan2(
                2.0 * (q0 * q1 + q2 * q3),
                1.0 - 2.0 * (q1 * q1 + q2 * q2)
            )
        ).toFloat()

        val sinP = 2.0 * (q0 * q2 - q3 * q1)
        val pitch = if (kotlin.math.abs(sinP) >= 1.0) {
            (Math.copySign(90.0, sinP)).toFloat()
        } else {
            Math.toDegrees(kotlin.math.asin(sinP)).toFloat()
        }

        val yaw = Math.toDegrees(
            kotlin.math.atan2(
                2.0 * (q0 * q3 + q1 * q2),
                1.0 - 2.0 * (q2 * q2 + q3 * q3)
            )
        ).toFloat()

        return floatArrayOf(roll, pitch, yaw)
    }

    /**
     * Reset the filter to identity quaternion.
     */
    fun reset() {
        q0 = 1.0f; q1 = 0.0f; q2 = 0.0f; q3 = 0.0f
        lastTimestampUs = 0
        initialized = false
    }

    /**
     * Set the filter gain. Higher = more accelerometer correction.
     */
    fun setBeta(newBeta: Float) {
        beta = newBeta
    }
}
