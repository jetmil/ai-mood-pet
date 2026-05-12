package io.github.jetmil.aimoodpet.eyes

data class MirrorEmotion(
    val smile: Float = 0f,         // mouthSmile* / 2 → joy
    val frown: Float = 0f,         // mouthFrown* / 2 → sadness
    val browDown: Float = 0f,      // browDown* / 2 → anger / focus
    val browInnerUp: Float = 0f,   // → concern/sadness
    val browOuterUp: Float = 0f,   // → surprise
    val eyeWide: Float = 0f,       // → surprise/fear
    val eyeSquint: Float = 0f,     // → joy or disgust
    val jawOpen: Float = 0f,       // → surprise
    val noseSneer: Float = 0f,     // → disgust
    val mouthPress: Float = 0f,    // → anger
    // Асимметричные сигналы — для зеркаления подмигивания / кривой улыбки
    val blinkLeft: Float = 0f,     // 0=открыт, 1=закрыт (на твоём лице)
    val blinkRight: Float = 0f,
    val mouthSkew: Float = 0f,     // -1..+1: мoth кривится влево/вправо
    val tongueOut: Float = 0f,     // 0..1 — высунутый язык (ARKit blendshape)
) {
    fun ema(prev: MirrorEmotion, alpha: Float): MirrorEmotion = MirrorEmotion(
        smile = prev.smile + (smile - prev.smile) * alpha,
        frown = prev.frown + (frown - prev.frown) * alpha,
        browDown = prev.browDown + (browDown - prev.browDown) * alpha,
        browInnerUp = prev.browInnerUp + (browInnerUp - prev.browInnerUp) * alpha,
        browOuterUp = prev.browOuterUp + (browOuterUp - prev.browOuterUp) * alpha,
        eyeWide = prev.eyeWide + (eyeWide - prev.eyeWide) * alpha,
        eyeSquint = prev.eyeSquint + (eyeSquint - prev.eyeSquint) * alpha,
        jawOpen = prev.jawOpen + (jawOpen - prev.jawOpen) * alpha,
        noseSneer = prev.noseSneer + (noseSneer - prev.noseSneer) * alpha,
        mouthPress = prev.mouthPress + (mouthPress - prev.mouthPress) * alpha,
        blinkLeft = prev.blinkLeft + (blinkLeft - prev.blinkLeft) * alpha,
        blinkRight = prev.blinkRight + (blinkRight - prev.blinkRight) * alpha,
        mouthSkew = prev.mouthSkew + (mouthSkew - prev.mouthSkew) * alpha,
        tongueOut = prev.tongueOut + (tongueOut - prev.tongueOut) * alpha,
    )
    /** УСТАРЕЛО: использовать toAttractor() вместо аккумулирующейся delta. */
    @Deprecated("use toAttractor", ReplaceWith("toAttractor()"))
    fun toMoodDelta(scale: Float = 0.0f): MoodDelta = MoodDelta()

    /**
     * Возвращает «куда тянутся» эмоциональные каналы при текущей мимике —
     * целевые значения [0..1], а НЕ delta. Mood-engine должен делать pull
     * к этим target'ам с малым frame-factor, иначе аккумуляция за 30 fps
     * за секунды забьёт все каналы в потолок.
     */
    fun toAttractor(): MoodVector {
        val joySig = (smile * 1.4f + eyeSquint * smile * 0.6f).coerceIn(0f, 1f)
        val sadSig = (browInnerUp * 1.5f + frown * 0.8f).coerceIn(0f, 1f)
        // Anger подавляется только настоящей улыбкой (>0.20), а не лёгким squint.
        val smileGate = if (smile > 0.20f) (1f - (smile * 1.2f).coerceAtMost(1f)) else 1f
        // Anger более чувствительный + frown и noseSneer как доп. сигналы.
        val angSig = (((browDown - 0.12f).coerceAtLeast(0f) * 1.7f
            + mouthPress * 0.8f
            + (frown * 0.6f).coerceAtLeast(0f)
            + noseSneer * 0.30f) * smileGate).coerceIn(0f, 1f)
        val surSig = ((browOuterUp - 0.05f).coerceAtLeast(0f) * 1.2f
            + (jawOpen - 0.10f).coerceAtLeast(0f) * 0.7f
            + (eyeWide - 0.15f).coerceAtLeast(0f) * 0.6f).coerceIn(0f, 1f)
        val feaSig = ((eyeWide - 0.25f).coerceAtLeast(0f) * 0.9f).coerceIn(0f, 1f)
        val disSig = (noseSneer * 1.2f).coerceIn(0f, 1f)
        return MoodVector(
            joy = joySig,
            sadness = sadSig,
            anger = angSig,
            surprise = surSig,
            fear = feaSig,
            disgust = disSig,
            // эти каналы mirror'ом не управляются — оставляем «не активны»,
            // pullToward всё равно затащит их вниз пока их target=0
            trust = 0f,
            anticipation = 0f,
            arousal = 0f,
            energy = 0f,
        )
    }
}
