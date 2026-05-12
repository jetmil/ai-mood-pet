package io.github.jetmil.aimoodpet.eyes

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2

/**
 * Распознаёт ориентацию телефона по accelerometer (gravity vector):
 *  - Upright: телефон вертикально, как обычно держат — нейтрально
 *  - Tilted face-down: наклон вперёд > 60° — грусть/жалоба
 *  - Tilted back: наклон назад > 60° — удивление, "что показываешь?"
 *  - Flipped (face-down полностью): z < -7 — паника
 *
 * Шлёт onTilt(state, magnitude) когда состояние сменилось дольше HOLD_MS.
 */
class TiltDetector(
    private val sensorManager: SensorManager,
    private val onTilt: (TiltState, Float) -> Unit,
) : SensorEventListener {

    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var lastState: TiltState = TiltState.Upright
    private var stateStartedAt: Long = 0L
    private var lastReportedAt: Long = 0L

    fun start() {
        sensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values.getOrElse(0) { 0f }
        val y = event.values.getOrElse(1) { 0f }
        val z = event.values.getOrElse(2) { 0f }
        // Считаем «pitch» (наклон вперёд-назад) и «flip» (телефон вверх ногами).
        // y близко к +9.81 → нормально (телефон вертикально, экран к юзеру).
        // y близко к 0, z>5 — лежит экраном вверх.
        // z < -7 — экраном вниз (panic).
        val newState = when {
            z < -7.0f -> TiltState.Flipped
            y < -3.0f -> TiltState.UpsideDown
            y < 3.0f && z > 7.0f -> TiltState.FaceUp
            y < 5.0f && z > 4.0f -> TiltState.TiltedBack
            y > 5.0f && z < -2.0f -> TiltState.TiltedForward
            else -> TiltState.Upright
        }
        val now = System.currentTimeMillis()
        if (newState != lastState) {
            lastState = newState
            stateStartedAt = now
            return
        }
        // Стабильное состояние >= HOLD_MS, и от прошлого репорта прошло COOLDOWN_MS.
        if (newState != TiltState.Upright
            && now - stateStartedAt >= HOLD_MS
            && now - lastReportedAt >= COOLDOWN_MS
        ) {
            lastReportedAt = now
            // Magnitude: насколько резкий наклон (0..1) — пригодится для силы реакции.
            val mag = when (newState) {
                TiltState.Flipped -> 1.0f
                TiltState.UpsideDown -> 0.85f
                TiltState.TiltedForward -> 0.55f
                TiltState.TiltedBack -> 0.45f
                TiltState.FaceUp -> 0.30f
                TiltState.Upright -> 0f
            }
            onTilt(newState, mag)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val HOLD_MS = 600L
        private const val COOLDOWN_MS = 4000L
    }
}

enum class TiltState {
    Upright,         // нормально вертикально
    TiltedForward,   // наклон от себя (показывает кому-то?)
    TiltedBack,      // наклон на себя (положил на колени?)
    FaceUp,          // лежит экраном вверх (на столе)
    UpsideDown,      // вверх ногами вертикально
    Flipped,         // экраном вниз — паника
}
