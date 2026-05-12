package io.github.jetmil.aimoodpet.eyes

/**
 * Гистерезис на доминирующую эмоцию. Не позволяет StatusBar и форме глаз
 * мерцать между эмоциями, когда mood-вектор почти равен между двумя каналами.
 *
 * Правила:
 *  - текущая dominant держится минимум HOLD_MS даже если другая канал перегнал
 *    на чуть-чуть
 *  - смена происходит когда новая канал > текущей × OVERTAKE_FACTOR хотя бы
 *    SETTLE_MS подряд
 */
class StableDominant(
    initial: Plutchik8 = Plutchik8.Trust,
) {
    var current: Plutchik8 = initial
        private set
    var strength: Float = 0f
        private set

    private var pendingCandidate: Plutchik8? = null
    private var pendingSinceMs: Long = 0L

    fun update(mood: MoodVector, nowMs: Long = System.currentTimeMillis()): Plutchik8 {
        val (top, topVal) = mood.dominant()
        strength = topVal
        if (top == current) {
            pendingCandidate = null
            return current
        }
        val curVal = mood.valueOf(current)
        val overtakes = topVal > curVal * OVERTAKE_FACTOR && topVal > MIN_SWITCH_VALUE
        if (!overtakes) {
            pendingCandidate = null
            return current
        }
        if (pendingCandidate != top) {
            pendingCandidate = top
            pendingSinceMs = nowMs
            return current
        }
        if (nowMs - pendingSinceMs >= SETTLE_MS) {
            current = top
            pendingCandidate = null
        }
        return current
    }

    companion object {
        private const val OVERTAKE_FACTOR = 1.15f
        private const val MIN_SWITCH_VALUE = 0.08f
        private const val SETTLE_MS = 300L
    }
}
