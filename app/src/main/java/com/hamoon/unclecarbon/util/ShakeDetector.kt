package com.hamoon.unclecarbon.util

import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Detects shakes based on accelerometer data.
 */
class ShakeDetector(
    private val listener: OnShakeListener, // This now expects an object of the interface below
    sensitivityLevel: Int
) {

    private var shakeTimestamp: Long = 0
    private var shakeCount = 0

    private val shakeThresholdGravity: Float
    private val SHAKE_SLOP_TIME_MS = 500
    private val SHAKE_COUNT_RESET_TIME_MS = 3000

    init {
        shakeThresholdGravity = 4.0f - (sensitivityLevel * 0.4f)
    }

    // THE FIX IS HERE: Define the proper interface that MonitoringService will implement.
    interface OnShakeListener {
        fun onShake(count: Int)
    }

    fun updateShake(x: Float, y: Float, z: Float) {
        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH

        if (gForce > shakeThresholdGravity) {
            val now = System.currentTimeMillis()

            if (shakeTimestamp + SHAKE_SLOP_TIME_MS > now) {
                return
            }

            if (shakeTimestamp + SHAKE_COUNT_RESET_TIME_MS < now) {
                shakeCount = 0
            }

            shakeTimestamp = now
            shakeCount++

            listener.onShake(shakeCount)
        }
    }
}