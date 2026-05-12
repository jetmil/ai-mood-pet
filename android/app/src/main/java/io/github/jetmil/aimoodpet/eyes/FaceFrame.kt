package io.github.jetmil.aimoodpet.eyes

sealed class FaceFrame {
    data object NoFace : FaceFrame()

    data class Detected(
        val headYawDeg: Float,
        val headPitchDeg: Float,
        val headRollDeg: Float,
        val lookingAtCamera: Boolean,
        val mirror: MirrorEmotion,
        // Позиция носа в кадре в нормализованных координатах [-1,+1].
        // x: -1=левый край, +1=правый. y: -1=верх, +1=низ.
        val faceCenter: androidx.compose.ui.geometry.Offset,
        // Геометрический face fingerprint (64-D L2 unit) — null если landmarks
        // оказались incomplete или ipd слишком маленький.
        val fingerprint: FloatArray? = null,
    ) : FaceFrame()
}
