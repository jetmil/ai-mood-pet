package io.github.jetmil.aimoodpet.eyes

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

/**
 * Магнитное поле. Baseline ~30-50 µT (геомагнитное). Магнит близко → 200-1000+.
 * EMA сглаживание + детект spike. onSpike(intensity in 0..1).
 */
class MagnetDetector(
    private val sensorManager: SensorManager,
    private val onSpike: (Float) -> Unit,
) : SensorEventListener {

    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private var smoothed: Float = 50f
    private var lastSpikeTs: Long = 0L
    private val cooldownMs: Long = 1500L

    fun start() {
        if (sensor == null) {
            Log.w(TAG, "TYPE_MAGNETIC_FIELD sensor not present")
            return
        }
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        Log.i(TAG, "MagnetDetector started")
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values.getOrElse(0) { 0f }
        val y = event.values.getOrElse(1) { 0f }
        val z = event.values.getOrElse(2) { 0f }
        val mag = sqrt(x * x + y * y + z * z)
        smoothed = smoothed * 0.95f + mag * 0.05f
        val dev = mag - smoothed
        if (dev > SPIKE_THRESHOLD) {
            val now = System.currentTimeMillis()
            if (now - lastSpikeTs > cooldownMs) {
                lastSpikeTs = now
                val intensity = ((dev - SPIKE_THRESHOLD) / 200f).coerceIn(0f, 1f)
                Log.i(TAG, "magnet spike dev=${dev.toInt()}µT mag=${mag.toInt()} baseline=${smoothed.toInt()} → intensity=$intensity")
                onSpike(intensity)
            }
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit

    companion object {
        private const val SPIKE_THRESHOLD = 25f  // µT отклонение от baseline
        private const val TAG = "MagnetDetector"
    }
}
