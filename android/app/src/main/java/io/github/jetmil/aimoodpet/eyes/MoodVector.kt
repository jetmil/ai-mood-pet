package io.github.jetmil.aimoodpet.eyes

import kotlin.math.exp
import kotlin.math.max

enum class Plutchik8 { Joy, Trust, Fear, Surprise, Sadness, Disgust, Anger, Anticipation }

data class MoodVector(
    val joy: Float = 0f,
    val trust: Float = 0.15f,
    val fear: Float = 0f,
    val surprise: Float = 0f,
    val sadness: Float = 0f,
    val disgust: Float = 0f,
    val anger: Float = 0f,
    val anticipation: Float = 0.10f,
    val arousal: Float = 0.30f,
    val energy: Float = 0.70f,
) {
    fun decay(dt: Float): MoodVector {
        val tFast = 8f
        val tMid = 30f
        val tSlow = 120f
        val baselineTrust = 0.05f
        val baselineAnticipation = 0.05f
        return copy(
            joy = joy * exp(-dt / tMid),
            trust = baselineTrust + (trust - baselineTrust) * exp(-dt / tSlow),
            fear = fear * exp(-dt / tFast),
            surprise = surprise * exp(-dt / tFast),
            sadness = sadness * exp(-dt / tMid),
            disgust = disgust * exp(-dt / tMid),
            anger = anger * exp(-dt / tFast),
            anticipation = baselineAnticipation + (anticipation - baselineAnticipation) * exp(-dt / tSlow),
            arousal = (arousal * exp(-dt / 20f)
                + targetArousal() * (1f - exp(-dt / 20f))).coerceIn(0f, 1f),
            energy = (energy - dt * 0.0008f).coerceIn(0f, 1f),
        )
    }

    private fun targetArousal(): Float {
        return max(max(fear, surprise), max(anger, anticipation)) * 0.8f + joy * 0.4f
    }

    fun applyDelta(d: MoodDelta): MoodVector = copy(
        joy = (joy + d.joy).coerceIn(0f, 1f),
        trust = (trust + d.trust).coerceIn(0f, 1f),
        fear = (fear + d.fear).coerceIn(0f, 1f),
        surprise = (surprise + d.surprise).coerceIn(0f, 1f),
        sadness = (sadness + d.sadness).coerceIn(0f, 1f),
        disgust = (disgust + d.disgust).coerceIn(0f, 1f),
        anger = (anger + d.anger).coerceIn(0f, 1f),
        anticipation = (anticipation + d.anticipation).coerceIn(0f, 1f),
        arousal = (arousal + d.arousal).coerceIn(0f, 1f),
        energy = (energy + d.energy).coerceIn(0f, 1f),
    )

    fun dominant(): Pair<Plutchik8, Float> = listOf(
        Plutchik8.Joy to joy,
        Plutchik8.Trust to trust,
        Plutchik8.Fear to fear,
        Plutchik8.Surprise to surprise,
        Plutchik8.Sadness to sadness,
        Plutchik8.Disgust to disgust,
        Plutchik8.Anger to anger,
        Plutchik8.Anticipation to anticipation,
    ).maxBy { it.second }

    fun valueOf(p: Plutchik8): Float = when (p) {
        Plutchik8.Joy -> joy
        Plutchik8.Trust -> trust
        Plutchik8.Fear -> fear
        Plutchik8.Surprise -> surprise
        Plutchik8.Sadness -> sadness
        Plutchik8.Disgust -> disgust
        Plutchik8.Anger -> anger
        Plutchik8.Anticipation -> anticipation
    }

    fun ema(prev: MoodVector, alpha: Float): MoodVector = copy(
        joy = prev.joy + (joy - prev.joy) * alpha,
        trust = prev.trust + (trust - prev.trust) * alpha,
        fear = prev.fear + (fear - prev.fear) * alpha,
        surprise = prev.surprise + (surprise - prev.surprise) * alpha,
        sadness = prev.sadness + (sadness - prev.sadness) * alpha,
        disgust = prev.disgust + (disgust - prev.disgust) * alpha,
        anger = prev.anger + (anger - prev.anger) * alpha,
        anticipation = prev.anticipation + (anticipation - prev.anticipation) * alpha,
        arousal = prev.arousal + (arousal - prev.arousal) * alpha,
        energy = prev.energy + (energy - prev.energy) * alpha,
    )
}

data class MoodDelta(
    val joy: Float = 0f,
    val trust: Float = 0f,
    val fear: Float = 0f,
    val surprise: Float = 0f,
    val sadness: Float = 0f,
    val disgust: Float = 0f,
    val anger: Float = 0f,
    val anticipation: Float = 0f,
    val arousal: Float = 0f,
    val energy: Float = 0f,
)
