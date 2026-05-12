package io.github.jetmil.aimoodpet.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import io.github.jetmil.aimoodpet.eyes.EmotionPalette
import io.github.jetmil.aimoodpet.eyes.FsmState
import io.github.jetmil.aimoodpet.eyes.MirrorEmotion
import io.github.jetmil.aimoodpet.eyes.MoodVector
import io.github.jetmil.aimoodpet.eyes.Plutchik8

/**
 * Спидометр-доверия: 180° полудуга, дуга-арка от тёмного к ярко-эмоциональному цвету,
 * стрелка показывает stableStrength (0..1). Минималистично, не отвлекает.
 *
 * Технические подписи (mirror/fsm) убраны — оставлен только верхний эмоция-лейбл
 * как тонкий subtitle над спидометром.
 */
@Composable
fun StatusBar(
    state: FsmState,
    mood: MoodVector,
    mirror: MirrorEmotion,
    faceVisible: Boolean,
    stableEmotion: Plutchik8,
    stableStrength: Float,
    modifier: Modifier = Modifier,
) {
    val color = EmotionPalette.colorOf(stableEmotion)
    // Стрелка — общий уровень возбуждения (arousal). Не скачет при смене
    // dominant-эмоции. Цвет меняется отдельно — это «температура» состояния.
    // Делаем aggregate: 60% arousal + 30% доминирующая сила + 10% energy.
    val rawTarget = (
        mood.arousal * 0.60f
        + stableStrength.coerceIn(0f, 1f) * 0.30f
        + mood.energy * 0.10f
    ).coerceIn(0f, 1f)
    val needle by animateFloatAsState(
        rawTarget,
        tween(900, easing = FastOutSlowInEasing),
        label = "needle",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = dominantLabel(stableEmotion).lowercase(),
            color = color.copy(alpha = 0.85f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp,
        )
        Box(
            modifier = Modifier
                .width(160.dp)
                .height(54.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(54.dp)) {
                drawSpeedometer(
                    fillRatio = needle,
                    accent = color,
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSpeedometer(
    fillRatio: Float,
    accent: Color,
) {
    val w = size.width
    val h = size.height
    // Дуга — нижняя половина овала, центр в нижней середине.
    val cx = w / 2f
    val cy = h * 0.96f
    val rOuter = h * 0.92f
    val rInner = rOuter * 0.78f
    val arcWidth = rOuter - rInner
    val centerR = (rOuter + rInner) / 2f

    // 1) подложка-арка — тёмная, постоянная
    drawArc180(cx, cy, centerR, arcWidth, Color(0xFF26221C), null)

    // 2) активная-арка — градиент серый→accent, длина = fillRatio
    if (fillRatio > 0.02f) {
        val brush = Brush.sweepGradient(
            colors = listOf(
                Color(0xFF555048),
                accent.copy(alpha = 0.9f),
                accent.copy(alpha = 0.4f),
            ),
            center = Offset(cx, cy),
        )
        drawArc180(cx, cy, centerR, arcWidth * 0.85f, accent, brush, sweepFraction = fillRatio)
    }

    // 3) шкала-деления (10 шт)
    val ticks = 10
    for (i in 0..ticks) {
        val frac = i.toFloat() / ticks
        val angle = Math.PI - (frac * Math.PI)
        val majorLen = if (i % 5 == 0) arcWidth * 0.55f else arcWidth * 0.30f
        val isMajor = i % 5 == 0
        val tickColor = if (isMajor) Color(0xFFD8C8A8) else Color(0xFF6E6253)
        val outX = cx + cos(angle).toFloat() * (rOuter + arcWidth * 0.05f)
        val outY = cy - sin(angle).toFloat() * (rOuter + arcWidth * 0.05f)
        val inX = cx + cos(angle).toFloat() * (rOuter - majorLen)
        val inY = cy - sin(angle).toFloat() * (rOuter - majorLen)
        drawLine(tickColor, Offset(outX, outY), Offset(inX, inY), strokeWidth = if (isMajor) 2.4f else 1.2f, cap = StrokeCap.Round)
    }

    // 4) стрелка — от центра к точке на дуге (немного за неё)
    val needleAngle = Math.PI - (fillRatio.coerceIn(0f, 1f) * Math.PI)
    val needleR = rOuter - arcWidth * 0.15f
    val tipX = cx + cos(needleAngle).toFloat() * needleR
    val tipY = cy - sin(needleAngle).toFloat() * needleR
    val baseR = arcWidth * 0.10f
    val perpAngle = needleAngle + Math.PI / 2
    val baseX = cx
    val baseY = cy
    val basePerpX = cos(perpAngle).toFloat() * baseR
    val basePerpY = -sin(perpAngle).toFloat() * baseR
    val needlePath = Path().apply {
        moveTo(tipX, tipY)
        lineTo(baseX + basePerpX, baseY + basePerpY)
        lineTo(baseX - basePerpX, baseY - basePerpY)
        close()
    }
    drawPath(needlePath, color = accent)
    // 5) ступица — тёмный круг с обводкой accent
    drawCircle(Color(0xFF1B1814), radius = arcWidth * 0.32f, center = Offset(cx, cy))
    drawCircle(accent, radius = arcWidth * 0.32f, center = Offset(cx, cy), style = Stroke(width = 1.6f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArc180(
    cx: Float, cy: Float, centerR: Float, strokeW: Float,
    fallback: Color, brush: Brush?,
    sweepFraction: Float = 1f,
) {
    val sweepDeg = -180f * sweepFraction
    val startDeg = 180f
    if (brush != null) {
        drawArc(
            brush = brush,
            startAngle = startDeg,
            sweepAngle = sweepDeg,
            useCenter = false,
            topLeft = Offset(cx - centerR, cy - centerR),
            size = Size(centerR * 2f, centerR * 2f),
            style = Stroke(width = strokeW, cap = StrokeCap.Round),
        )
    } else {
        drawArc(
            color = fallback,
            startAngle = startDeg,
            sweepAngle = sweepDeg,
            useCenter = false,
            topLeft = Offset(cx - centerR, cy - centerR),
            size = Size(centerR * 2f, centerR * 2f),
            style = Stroke(width = strokeW, cap = StrokeCap.Round),
        )
    }
}

private fun dominantLabel(p: Plutchik8): String = when (p) {
    Plutchik8.Joy -> "радость"
    Plutchik8.Trust -> "доверие"
    Plutchik8.Fear -> "страх"
    Plutchik8.Surprise -> "удивление"
    Plutchik8.Sadness -> "грусть"
    Plutchik8.Disgust -> "отвращение"
    Plutchik8.Anger -> "гнев"
    Plutchik8.Anticipation -> "предвкушение"
}
