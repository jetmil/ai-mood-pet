package io.github.jetmil.aimoodpet.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Биквад (IIR 2-го порядка) — Robert Bristow-Johnson cookbook.
 * Используем band-pass для голосового диапазона: гул и шипение режутся,
 * речь почти целиком проходит.
 *
 * Применять как фильтр real-time на PCM-сэмплы (s16): process(sample) → filtered.
 */
class Biquad private constructor(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(x0: Double): Double {
        val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x0
        y2 = y1; y1 = y0
        return y0
    }

    fun reset() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }

    companion object {
        /**
         * Band-pass (constant skirt gain). centerHz — центральная частота,
         * q — добротность (0.5 широкий, 5 узкий). sampleRate — Гц.
         */
        fun bandPass(centerHz: Double, q: Double, sampleRate: Double): Biquad {
            val omega = 2.0 * PI * centerHz / sampleRate
            val sinO = sin(omega)
            val cosO = cos(omega)
            val alpha = sinO / (2.0 * q)
            val a0 = 1.0 + alpha
            val b0 =  alpha / a0
            val b1 =  0.0
            val b2 = -alpha / a0
            val a1 = -2.0 * cosO / a0
            val a2 = (1.0 - alpha) / a0
            return Biquad(b0, b1, b2, a1, a2)
        }
    }
}
