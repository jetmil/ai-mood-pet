package io.github.jetmil.aimoodpet.eyes

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import kotlin.math.cos
import kotlin.math.sin

/**
 * 8-точечные шаблоны для каждой Plutchik-эмоции в нормализованных координатах [-1..+1].
 * Все формы используют ровно 8 точек по периметру → smooth morph через point-by-point lerp,
 * без скачков топологии.
 */
data class ShapePoints(val pts: List<Offset>) {

    init {
        require(pts.size == COUNT) { "ShapePoints expects exactly $COUNT points, got ${pts.size}" }
    }

    fun lerpTo(other: ShapePoints, t: Float): ShapePoints {
        val tt = t.coerceIn(0f, 1f)
        return ShapePoints(
            pts.zip(other.pts) { a, b ->
                Offset(
                    a.x + (b.x - a.x) * tt,
                    a.y + (b.y - a.y) * tt,
                )
            }
        )
    }

    fun toPath(scale: Float, center: Offset, rotationRad: Float = 0f): Path {
        val cosR = cos(rotationRad)
        val sinR = sin(rotationRad)
        val transformed = pts.map { p ->
            val rx = p.x * cosR - p.y * sinR
            val ry = p.x * sinR + p.y * cosR
            Offset(center.x + rx * scale, center.y + ry * scale)
        }
        return Path().apply {
            moveTo(transformed[0].x, transformed[0].y)
            for (i in 1 until transformed.size) {
                val p = transformed[i]
                val prev = transformed[i - 1]
                val mid = Offset((prev.x + p.x) / 2f, (prev.y + p.y) / 2f)
                quadraticBezierTo(prev.x, prev.y, mid.x, mid.y)
            }
            // close back to first
            val first = transformed[0]
            val last = transformed.last()
            val mid = Offset((last.x + first.x) / 2f, (last.y + first.y) / 2f)
            quadraticBezierTo(last.x, last.y, mid.x, mid.y)
            lineTo(first.x, first.y)
            close()
        }
    }

    companion object { const val COUNT = 8 }
}

object EmotionShapes {

    private fun ring8(r: Float, sx: Float = 1f, sy: Float = 1f, dy: Float = 0f): ShapePoints {
        val pts = (0 until 8).map { i ->
            val a = (Math.PI * 2 * i / 8).toFloat() - (Math.PI / 2).toFloat()
            Offset(cos(a) * r * sx, sin(a) * r * sy + dy)
        }
        return ShapePoints(pts)
    }

    /** Радость — ровный круг */
    val Joy: ShapePoints = ring8(0.78f)

    /** Доверие — квадрат с лёгким скруглением (углы + середины граней) */
    val Trust: ShapePoints = ShapePoints(listOf(
        Offset(0f, -0.85f),
        Offset(0.85f, -0.85f),
        Offset(0.85f, 0f),
        Offset(0.85f, 0.85f),
        Offset(0f, 0.85f),
        Offset(-0.85f, 0.85f),
        Offset(-0.85f, 0f),
        Offset(-0.85f, -0.85f),
    ))

    /** Гнев — треугольник вершиной вниз (стандартный «угрожающий» знак) */
    val Anger: ShapePoints = ShapePoints(listOf(
        Offset(0f, 0.95f),
        Offset(0.45f, 0.30f),
        Offset(0.90f, -0.50f),
        Offset(0.45f, -0.85f),
        Offset(0f, -0.85f),
        Offset(-0.45f, -0.85f),
        Offset(-0.90f, -0.50f),
        Offset(-0.45f, 0.30f),
    ))

    /** Грусть — приплюснутый горизонтальный прямоугольник, опущен по Y */
    val Sadness: ShapePoints = ShapePoints(listOf(
        Offset(0f, 0.10f),
        Offset(0.85f, 0.10f),
        Offset(0.95f, 0.40f),
        Offset(0.85f, 0.55f),
        Offset(0f, 0.55f),
        Offset(-0.85f, 0.55f),
        Offset(-0.95f, 0.40f),
        Offset(-0.85f, 0.10f),
    ))

    /** Удивление — ромб / 4-конечная звезда (длинные оси, короткие диагонали) */
    val Surprise: ShapePoints = ShapePoints(listOf(
        Offset(0f, -0.95f),
        Offset(0.40f, -0.40f),
        Offset(0.95f, 0f),
        Offset(0.40f, 0.40f),
        Offset(0f, 0.95f),
        Offset(-0.40f, 0.40f),
        Offset(-0.95f, 0f),
        Offset(-0.40f, -0.40f),
    ))

    /** Страх — сжатый по Y овал, чуть сужающийся книзу (капля) */
    val Fear: ShapePoints = ShapePoints(listOf(
        Offset(0f, -0.75f),
        Offset(0.55f, -0.55f),
        Offset(0.75f, 0f),
        Offset(0.50f, 0.50f),
        Offset(0f, 0.85f),
        Offset(-0.50f, 0.50f),
        Offset(-0.75f, 0f),
        Offset(-0.55f, -0.55f),
    ))

    /** Отвращение — серп / полумесяц, обращённый влево */
    val Disgust: ShapePoints = ShapePoints(listOf(
        Offset(0.30f, -0.85f),
        Offset(0.65f, -0.55f),
        Offset(0.55f, 0f),
        Offset(0.65f, 0.55f),
        Offset(0.30f, 0.85f),
        Offset(-0.20f, 0.55f),
        Offset(-0.40f, 0f),
        Offset(-0.20f, -0.55f),
    ))

    /** Предвкушение — стрелка вершиной вверх */
    val Anticipation: ShapePoints = ShapePoints(listOf(
        Offset(0f, -0.95f),
        Offset(0.55f, -0.40f),
        Offset(0.85f, 0.20f),
        Offset(0.50f, 0.55f),
        Offset(0f, 0.55f),
        Offset(-0.50f, 0.55f),
        Offset(-0.85f, 0.20f),
        Offset(-0.55f, -0.40f),
    ))

    fun forEmotion(p: Plutchik8): ShapePoints = when (p) {
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

/**
 * Рассчитывает смесь top-2 эмоций по их интенсивности — даёт плавный
 * индикатор когда mood находится «между» двумя соседними чувствами.
 */
fun MoodVector.shapeMix(): ShapePoints {
    val ranked = listOf(
        Plutchik8.Joy to joy,
        Plutchik8.Trust to trust,
        Plutchik8.Fear to fear,
        Plutchik8.Surprise to surprise,
        Plutchik8.Sadness to sadness,
        Plutchik8.Disgust to disgust,
        Plutchik8.Anger to anger,
        Plutchik8.Anticipation to anticipation,
    ).sortedByDescending { it.second }

    val (e1, w1) = ranked[0]
    val (e2, w2) = ranked[1]
    val s1 = EmotionShapes.forEmotion(e1)
    if (w1 < 0.05f) return s1
    val s2 = EmotionShapes.forEmotion(e2)
    val total = w1 + w2
    val t = (w2 / total).coerceIn(0f, 0.5f)
    return s1.lerpTo(s2, t)
}
