package io.github.jetmil.aimoodpet.eyes

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class ShakeDetector(
    private val sensorManager: SensorManager,
    private val onShake: (Float) -> Unit,
) : SensorEventListener {

    private val sensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var lastShakeTs: Long = 0L
    private val cooldownMs = 700L
    private val threshold = 12f

    fun start() {
        sensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values.getOrElse(0) { 0f }
        val y = event.values.getOrElse(1) { 0f }
        val z = event.values.getOrElse(2) { 0f }
        val mag = sqrt(x * x + y * y + z * z) - if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) 9.81f else 0f
        if (mag > threshold) {
            val now = System.currentTimeMillis()
            if (now - lastShakeTs > cooldownMs) {
                lastShakeTs = now
                val intensity = ((mag - 8f) / 14f).coerceIn(0f, 1f)
                onShake(intensity)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
