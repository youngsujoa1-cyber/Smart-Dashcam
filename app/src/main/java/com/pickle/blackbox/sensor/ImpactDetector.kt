package com.pickle.blackbox.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Detects an impact (sudden acceleration change) with the accelerometer.
 *
 * The magnitude of the acceleration vector is compared with a G threshold that can
 * change at runtime (user sensitivity setting). Detections are rate limited so a single
 * crash does not produce a burst of callbacks.
 */
class ImpactDetector(
    context: Context,
    private val thresholdProvider: () -> Float,
    private val onImpact: (gForce: Float) -> Unit
) : SensorEventListener {

    private val sensorManager: SensorManager? =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val accelerometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var lastImpactAt = 0L
    private var running = false

    val isAvailable: Boolean
        get() = accelerometer != null

    val isRunning: Boolean
        get() = running

    fun start() {
        val sensor = accelerometer ?: return
        if (running) return
        running = sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME) == true
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        if (event.values.size < 3) return

        val gx = event.values[0] / SensorManager.GRAVITY_EARTH
        val gy = event.values[1] / SensorManager.GRAVITY_EARTH
        val gz = event.values[2] / SensorManager.GRAVITY_EARTH
        val magnitude = sqrt(gx * gx + gy * gy + gz * gz)

        val now = SystemClock.elapsedRealtime()
        if (magnitude >= thresholdProvider() && now - lastImpactAt >= COOLDOWN_MS) {
            lastImpactAt = now
            onImpact(magnitude)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val COOLDOWN_MS = 2_000L
    }
}
