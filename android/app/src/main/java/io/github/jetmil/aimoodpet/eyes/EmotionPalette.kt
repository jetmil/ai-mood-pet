package io.github.jetmil.aimoodpet.eyes

import androidx.compose.ui.graphics.Color

object EmotionPalette {
    val Joy = Color(0xFFFFD23F)
    val Trust = Color(0xFFB3FF66)
    val Fear = Color(0xFF339966)
    val Surprise = Color(0xFF66CCFF)
    val Sadness = Color(0xFF3366CC)
    val Disgust = Color(0xFF9966CC)
    val Anger = Color(0xFFCC3333)
    val Anticipation = Color(0xFFFF9933)

    val Background = Color(0xFF0A0808)
    val ScleraNeutral = Color(0xFFF4ECDC)
    val ScleraShadow = Color(0xFF1A1310)
    val ScleraInnerShade = Color(0x3D5C3A2E)
    val IrisCore = Color(0xFF8C5A3C)
    val IrisLimbal = Color(0xFF3A1F12)
    val IrisLimbalRing = Color(0xCC2A140A)
    val PupilCore = Color(0xFF050303)
    val Glint = Color(0xFFFFFAF0)
    val LashLine = Color(0xFF18100C)
    val UnderEyeShadow = Color(0xFF2A1810)
    val BrowColor = Color(0xFF1A0F0A)

    fun colorOf(e: Plutchik8): Color = when (e) {
        Plutchik8.Joy -> Joy
        Plutchik8.Trust -> Trust
        Plutchik8.Fear -> Fear
        Plutchik8.Surprise -> Surprise
        Plutchik8.Sadness -> Sadness
        Plutchik8.Disgust -> Disgust
        Plutchik8.Anger -> Anger
        Plutchik8.Anticipation -> Anticipation
    }
}
