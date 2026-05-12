package io.github.jetmil.aimoodpet.eyes

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

enum class FsmState {
    Sleeping,
    Drowsy,
    Idle,
    Attentive,
    Focused,
    Excited,
    Startled,
}

data class EyeRenderParams(
    val state: FsmState,
    val pupilScale: Float,
    val eyelidTop: Float,
    val eyelidBot: Float,
    val browInnerY: Float,
    val browOuterY: Float,
    val gazeOffset: Offset,
    val scleraColor: Color,
    val glowColor: Color,
    val glowAlpha: Float,
    val blink: Float,
    val blinkLeft: Float = 0f,    // override blink для конкретного глаза (зеркало wink)
    val blinkRight: Float = 0f,
    val mouthSkew: Float = 0f,    // -1..+1, мoth кривится
    val tongueOut: Float = 0f,    // 0..1 — высунутый язык
)
