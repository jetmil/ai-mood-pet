package io.github.jetmil.aimoodpet.eyes

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp

object EyeMapper {

    fun render(
        state: FsmState,
        mood: MoodVector,
        gaze: Offset,
        blink: Float,
        mirror: MirrorEmotion = MirrorEmotion(),
    ): EyeRenderParams {
        val (dom, domVal) = mood.dominant()
        val emoColor = EmotionPalette.colorOf(dom)
        val tintFactor = (domVal * 0.18f).coerceIn(0f, 0.25f)
        val sclera = lerp(EmotionPalette.ScleraNeutral, emoColor, tintFactor)

        val basePupil = when (state) {
            FsmState.Sleeping -> 0.5f
            FsmState.Drowsy -> 0.85f
            FsmState.Startled -> 1.55f
            FsmState.Focused -> 0.85f
            FsmState.Excited -> 1.30f
            else -> 1.00f
        }
        val pupil = (basePupil
            + mood.surprise * 0.25f
            + mood.fear * 0.15f
            - mood.anger * 0.12f
            - mood.disgust * 0.08f
            + mirror.jawOpen * 0.30f
            + mirror.eyeWide * 0.20f
            - mirror.browDown * 0.10f).coerceIn(0.5f, 2.2f)

        val baseEyelidTop = when (state) {
            FsmState.Sleeping -> 1f
            FsmState.Drowsy -> 0.55f
            FsmState.Focused -> 0.18f
            FsmState.Startled -> 0f
            FsmState.Excited -> 0.05f
            else -> 0.10f
        }
        val eyelidTop = (baseEyelidTop
            + mood.sadness * 0.25f
            - mood.anger * 0.05f
            - mirror.eyeWide * 0.20f
            - mirror.jawOpen * 0.10f
            + blink * 0.65f).coerceIn(0f, 1f)

        val baseEyelidBot = when (state) {
            FsmState.Sleeping -> 1f
            FsmState.Drowsy -> 0.30f
            FsmState.Focused -> 0.20f
            else -> 0f
        }
        val eyelidBot = (baseEyelidBot
            + mood.disgust * 0.25f
            + mood.anger * 0.18f
            + mirror.smile * 0.35f
            + mirror.eyeSquint * 0.20f
            + blink * 0.40f).coerceIn(0f, 1f)

        val browInner = (mood.sadness * 0.65f
            - mood.anger * 0.75f
            - mood.surprise * 0.40f
            + mood.joy * 0.20f
            - mood.fear * 0.30f
            + mirror.browInnerUp * 0.55f
            - mirror.browDown * 0.55f).coerceIn(-1f, 1f)

        val browOuter = (mood.surprise * 0.55f
            + mood.joy * 0.18f
            - mood.sadness * 0.30f
            - mood.fear * 0.20f
            - mood.disgust * 0.25f
            + mirror.browOuterUp * 0.50f
            - mirror.browDown * 0.30f).coerceIn(-1f, 1f)

        // Прямое зеркало: front-cam уже отзеркалена, поэтому оставляем
        // left→left (твой левый глаз ↔ его левый глаз).
        val winkLeft = (mirror.blinkLeft - 0.30f).coerceAtLeast(0f) / 0.70f
        val winkRight = (mirror.blinkRight - 0.30f).coerceAtLeast(0f) / 0.70f
        return EyeRenderParams(
            state = state,
            pupilScale = pupil,
            eyelidTop = eyelidTop,
            eyelidBot = eyelidBot,
            browInnerY = browInner,
            browOuterY = browOuter,
            gazeOffset = gaze,
            scleraColor = sclera,
            glowColor = emoColor,
            glowAlpha = (domVal * 0.30f).coerceIn(0f, 0.35f),
            blink = blink,
            blinkLeft = winkLeft.coerceIn(0f, 1f),
            blinkRight = winkRight.coerceIn(0f, 1f),
            mouthSkew = mirror.mouthSkew, // прямое: front-cam уже отзеркалена
            tongueOut = mirror.tongueOut.coerceIn(0f, 1f),
        )
    }
}
