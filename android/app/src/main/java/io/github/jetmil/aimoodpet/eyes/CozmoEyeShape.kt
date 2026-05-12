package io.github.jetmil.aimoodpet.eyes

/**
 * Параметры формы глаза в стиле Anki Cozmo. Все эмоции выражаются
 * комбинацией этих 6 чисел — без внутренних символов, без зрачка, без радужки.
 *
 * Морфинг между шаблонами — плавная интерполяция каждого канала
 * через animateFloatAsState.
 */
data class CozmoEyeShape(
    val scaleX: Float,    // 0.5..1.4 — растяжение по X (joy шире, fear ýже)
    val scaleY: Float,    // 0.10..1.30 — высота (sleepy узкая, surprise высокая)
    val topRadius: Float, // 0..1 — скруглённость верхних углов (1 = сильное)
    val botRadius: Float, // 0..1 — скруглённость нижних углов
    val outerLift: Float, // -0.4..+0.4 — подъём внешнего угла (anger ↑, sad ↓)
    val innerLift: Float, // -0.4..+0.4 — подъём внутреннего угла (sad ↓, surprise ↑)
    val lowerArc: Float,  // 0..1 — выпуклость нижней грани вверх (joy crescent — высокий)
    val upperArc: Float,  // 0..1 — выпуклость верхней грани вниз (sad — высокий)
) {
    companion object {

        val Neutral = CozmoEyeShape(
            scaleX = 1.0f, scaleY = 1.0f,
            topRadius = 0.55f, botRadius = 0.55f,
            outerLift = 0f, innerLift = 0f,
            lowerArc = 0f, upperArc = 0f,
        )

        // Joy — широкий полумесяц улыбки. Низ сильно выпуклый вверх (smile arc),
        // высота резко уменьшена (глаза щурятся в радости).
        val Joy = CozmoEyeShape(
            scaleX = 1.30f, scaleY = 0.40f,
            topRadius = 1.00f, botRadius = 0.10f,
            outerLift = -0.15f, innerLift = -0.05f,
            lowerArc = 0.95f, upperArc = 0f,
        )

        // Trust — почти Neutral, чуть мягче, шире.
        val Trust = CozmoEyeShape(
            scaleX = 1.05f, scaleY = 0.95f,
            topRadius = 0.70f, botRadius = 0.70f,
            outerLift = 0f, innerLift = 0f,
            lowerArc = 0f, upperArc = 0f,
        )

        // Anger — треугольный наклон, внешний угол сильно поднят, внутренний
        // опущен, верх вогнут (frown brow). \\ //
        val Anger = CozmoEyeShape(
            scaleX = 1.0f, scaleY = 0.80f,
            topRadius = 0.10f, botRadius = 0.45f,
            outerLift = 0.50f, innerLift = -0.35f,
            lowerArc = 0f, upperArc = 0.70f,
        )

        // Sadness — перевёрнутый полумесяц. Внутренние углы сильно опущены,
        // верх выпуклый вниз (грустная бровь), глаза приплюснуты сверху.
        val Sadness = CozmoEyeShape(
            scaleX = 0.95f, scaleY = 0.50f,
            topRadius = 0.20f, botRadius = 0.95f,
            outerLift = 0f, innerLift = 0.45f,
            lowerArc = 0f, upperArc = 0.85f,
        )

        // Surprise — большие почти круглые, оба угла приподняты.
        val Surprise = CozmoEyeShape(
            scaleX = 1.10f, scaleY = 1.30f,
            topRadius = 0.95f, botRadius = 0.95f,
            outerLift = 0.10f, innerLift = 0.10f,
            lowerArc = 0f, upperArc = 0f,
        )

        // Fear — узкие высокие, лёгкий tilt внешних углов вниз (зажатость).
        val Fear = CozmoEyeShape(
            scaleX = 0.75f, scaleY = 1.20f,
            topRadius = 0.85f, botRadius = 0.85f,
            outerLift = -0.15f, innerLift = 0.10f,
            lowerArc = 0f, upperArc = 0f,
        )

        // Disgust — один tilt сильнее, асимметричный squint, нижний угол поднят.
        val Disgust = CozmoEyeShape(
            scaleX = 0.95f, scaleY = 0.65f,
            topRadius = 0.50f, botRadius = 0.30f,
            outerLift = -0.25f, innerLift = 0.15f,
            lowerArc = 0.30f, upperArc = 0.20f,
        )

        // Anticipation — широко открытые, верхний край приподнят, готовность.
        val Anticipation = CozmoEyeShape(
            scaleX = 1.05f, scaleY = 1.15f,
            topRadius = 0.80f, botRadius = 0.65f,
            outerLift = 0.05f, innerLift = 0.10f,
            lowerArc = 0f, upperArc = 0f,
        )

        // Thinking — щурится и закатывает глаза вверх. Не Anticipation
        // (которая распахивает глаза), а реальный «задумчивый прищур»:
        // scaleY узкая, верх скруглён, низ почти линия (упор на верхнее веко).
        val Thinking = CozmoEyeShape(
            scaleX = 1.00f, scaleY = 0.40f,
            topRadius = 0.85f, botRadius = 0.30f,
            outerLift = 0.10f, innerLift = 0.05f,
            lowerArc = 0.55f, upperArc = 0f,
        )

        fun forEmotion(p: Plutchik8): CozmoEyeShape = when (p) {
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

    fun lerpTo(other: CozmoEyeShape, t: Float): CozmoEyeShape {
        val tt = t.coerceIn(0f, 1f)
        return CozmoEyeShape(
            scaleX = scaleX + (other.scaleX - scaleX) * tt,
            scaleY = scaleY + (other.scaleY - scaleY) * tt,
            topRadius = topRadius + (other.topRadius - topRadius) * tt,
            botRadius = botRadius + (other.botRadius - botRadius) * tt,
            outerLift = outerLift + (other.outerLift - outerLift) * tt,
            innerLift = innerLift + (other.innerLift - innerLift) * tt,
            lowerArc = lowerArc + (other.lowerArc - lowerArc) * tt,
            upperArc = upperArc + (other.upperArc - upperArc) * tt,
        )
    }
}
