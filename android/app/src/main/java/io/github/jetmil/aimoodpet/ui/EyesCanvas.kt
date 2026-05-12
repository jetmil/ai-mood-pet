package io.github.jetmil.aimoodpet.ui

import kotlin.math.pow
import kotlin.random.Random
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.remember
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import io.github.jetmil.aimoodpet.eyes.CozmoEyeShape
import io.github.jetmil.aimoodpet.eyes.EmotionPalette
import io.github.jetmil.aimoodpet.eyes.EyeRenderParams
import io.github.jetmil.aimoodpet.eyes.ForeheadBadge
import io.github.jetmil.aimoodpet.eyes.Plutchik8

private val NeutralEyeSber = Color(0xFF21A038)
private val MouthLineColor = Color(0xFFEFE8DA)
private const val LONG_PRESS_MS = 500L
private const val MOVE_SLOP_SQ = 30f * 30f

@Composable
fun EyesCanvas(
    params: EyeRenderParams,
    stableEmotion: Plutchik8,
    stableStrength: Float,
    jawOpen: Float,
    speechAmp: Float,
    thinking: Boolean,
    forehead: ForeheadBadge?,
    onTap: (Offset) -> Unit,
    petListener: (Boolean, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Thinking-форма перебивает обычную mood-форму глаз: щурит +
    // mid-arc приподнят (концентрация). EmojiBadge на лбу подсветит
    // что именно происходит.
    val targetMix = if (thinking) CozmoEyeShape.Thinking
    else CozmoEyeShape.forEmotion(stableEmotion)
    val textMeasurer = rememberTextMeasurer()
    val foreheadAlpha by animateFloatAsState(
        if (forehead != null) 1f else 0f,
        tween(280, easing = FastOutSlowInEasing),
        label = "foreheadAlpha",
    )

    // Рандом-jitter: каждый раз когда меняется stableEmotion (или thinking),
    // подменяется duration ±20% и easing — анимация никогда не повторяется одинаково.
    // Это даёт «живую» вариативность в духе Cozmo без расширения словаря шейпов.
    val animPalette = remember(stableEmotion, thinking) {
        AnimPalette(
            durMul = 0.80f + Random.nextFloat() * 0.40f,        // 0.80..1.20
            easing = listOf(
                FastOutSlowInEasing,
                LinearOutSlowInEasing,
                CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f),
                CubicBezierEasing(0.25f, 0.1f, 0.25f, 1.0f),
                CubicBezierEasing(0.55f, 0.0f, 0.45f, 1.0f),
            ).random(),
            scaleXFudge = (Random.nextFloat() - 0.5f) * 0.05f,   // ±2.5%
            scaleYFudge = (Random.nextFloat() - 0.5f) * 0.05f,
            outerFudge = (Random.nextFloat() - 0.5f) * 0.04f,
            innerFudge = (Random.nextFloat() - 0.5f) * 0.04f,
        )
    }
    val baseDur = 350
    val animDur = (baseDur * animPalette.durMul).toInt().coerceIn(220, 600)
    val ease = animPalette.easing
    val scaleX by animateFloatAsState(
        (targetMix.scaleX + animPalette.scaleXFudge),
        tween(animDur, easing = ease), label = "sx")
    val scaleY by animateFloatAsState(
        (targetMix.scaleY + animPalette.scaleYFudge),
        tween(animDur, easing = ease), label = "sy")
    val topR by animateFloatAsState(targetMix.topRadius, tween(animDur, easing = ease), label = "tr")
    val botR by animateFloatAsState(targetMix.botRadius, tween(animDur, easing = ease), label = "br")
    val outerL by animateFloatAsState(
        (targetMix.outerLift + animPalette.outerFudge),
        tween(animDur, easing = ease), label = "ol")
    val innerL by animateFloatAsState(
        (targetMix.innerLift + animPalette.innerFudge),
        tween(animDur, easing = ease), label = "il")
    val lowerArc by animateFloatAsState(targetMix.lowerArc, tween(animDur, easing = ease), label = "la")
    val upperArc by animateFloatAsState(targetMix.upperArc, tween(animDur, easing = ease), label = "ua")

    val emoColor = EmotionPalette.colorOf(stableEmotion)
    val targetColor = lerp(NeutralEyeSber, emoColor, (stableStrength * 1.0f).coerceIn(0f, 0.50f))
    val eyeColor by animateColorAsState(targetColor, tween(700), label = "color")

    val smoothGaze by animateOffsetAsState(
        targetValue = params.gazeOffset,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "gaze",
    )

    // Mouth params, derived from stableEmotion + jawOpen + speech
    val mouthSpec = mouthSpecFor(stableEmotion)
    val mouthStretch by animateFloatAsState(
        mouthSpec.stretch, tween(500, easing = FastOutSlowInEasing), label = "mStr"
    )
    val mouthCurve by animateFloatAsState(
        mouthSpec.curve, tween(500, easing = FastOutSlowInEasing), label = "mCv"
    )
    val mouthOpening by animateFloatAsState(
        (jawOpen * 0.85f).coerceIn(0f, 1f),
        tween(120, easing = FastOutSlowInEasing),
        label = "mOpen",
    )
    // Lipsync: расширяем динамику amplitude — иначе envelope (среднее ~0.15)
    // даёт чуть приоткрытый рот без артикуляции. Гамма 0.55 + умножение
    // вытягивает тихие места в выраженный ритм слогов.
    val speechIntensity by animateFloatAsState(
        run {
            val a = speechAmp.coerceIn(0f, 1f)
            if (a < 0.04f) 0f
            else (a.pow(0.55f) * 1.6f).coerceIn(0f, 1f)
        },
        tween(60, easing = FastOutSlowInEasing),
        label = "mSpeech",
    )

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Сами разруливаем: short-tap (<500мс) = zone-tap, long-press = pet.
                // Иначе onPress срабатывает на любое касание и перекрывает zone-spec.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downId = down.id
                    val downPos = down.position
                    val downTimeNanos = System.nanoTime()
                    var movedAway = false
                    var pressed = true

                    // Ждём 500мс: либо up, либо переход в long-press.
                    val finishedQuickly = withTimeoutOrNull(LONG_PRESS_MS) {
                        while (pressed) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            for (ch in event.changes) {
                                if (ch.id == downId) {
                                    if (ch.changedToUp()) {
                                        pressed = false
                                        return@withTimeoutOrNull true
                                    }
                                    if (!movedAway && ch.positionChange().getDistanceSquared() > MOVE_SLOP_SQ) {
                                        movedAway = true
                                    }
                                }
                            }
                        }
                        false
                    } ?: false

                    if (finishedQuickly && !movedAway) {
                        // Короткий тап — zone-aware reaction
                        val nx = (downPos.x / size.width) * 2f - 1f
                        val ny = (downPos.y / size.height) * 2f - 1f
                        onTap(Offset(nx, ny))
                    } else if (!finishedQuickly && !movedAway) {
                        // Long press — pet, держим до release
                        val petStarted = System.currentTimeMillis()
                        petListener(true, 0L)
                        try {
                            while (pressed) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                for (ch in event.changes) {
                                    if (ch.id == downId && ch.changedToUp()) {
                                        pressed = false
                                    }
                                }
                            }
                        } finally {
                            petListener(false, System.currentTimeMillis() - petStarted)
                        }
                    }
                    // если двинул палец — игнорируем (drag, не tap и не pet)
                }
            }
    ) {
        drawRect(EmotionPalette.Background)
        drawForehead(textMeasurer, forehead, foreheadAlpha)
        for (left in listOf(true, false)) {
            val perEyeWink = if (left) params.blinkLeft else params.blinkRight
            val effectiveBlink = (params.blink + perEyeWink).coerceAtMost(1f)
            drawCozmoEye(
                left = left,
                gaze = smoothGaze,
                blink = effectiveBlink,
                color = eyeColor,
                scaleX = scaleX, scaleY = scaleY,
                topR = topR, botR = botR,
                outerLift = outerL, innerLift = innerL,
                lowerArc = lowerArc, upperArc = upperArc,
            )
        }
        drawMouth(
            emotion = stableEmotion,
            stretch = mouthStretch,
            curve = mouthCurve,
            opening = mouthOpening,
            speech = speechIntensity,
            skew = params.mouthSkew,
        )
        if (params.tongueOut > 0.10f) {
            drawTongue(
                extension = params.tongueOut,
                skew = params.mouthSkew,
                mouthCurve = mouthCurve,
                mouthOpening = mouthOpening,
                speechAmp = speechIntensity,
            )
        }
    }
}

private fun DrawScope.drawForehead(
    textMeasurer: TextMeasurer,
    badge: ForeheadBadge?,
    alpha: Float,
) {
    if (badge == null || alpha < 0.02f) return
    val w = size.width
    val h = size.height
    val emojiSize = (h * 0.085f)
    val cx = w / 2f
    val cy = h * 0.18f
    // Светящаяся подложка-кружок, цвет от badge.
    val glowColor = Color(badge.argb).copy(alpha = alpha * 0.55f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(glowColor, Color.Transparent),
            center = Offset(cx, cy),
            radius = emojiSize * 1.6f,
        ),
        radius = emojiSize * 1.6f,
        center = Offset(cx, cy),
    )
    // Сам emoji
    val style = TextStyle(
        color = Color.White.copy(alpha = alpha),
        fontSize = emojiSize.toSp(this),
    )
    val measured = textMeasurer.measure(badge.emoji, style = style)
    drawText(
        textMeasurer = textMeasurer,
        text = badge.emoji,
        style = style,
        topLeft = Offset(cx - measured.size.width / 2f, cy - measured.size.height / 2f),
    )
}

private fun Float.toSp(scope: DrawScope): androidx.compose.ui.unit.TextUnit =
    with(scope) { (this@toSp / density).sp }

private fun DrawScope.drawTongue(
    extension: Float,
    skew: Float,
    mouthCurve: Float,
    mouthOpening: Float,
    speechAmp: Float,
) {
    val w = size.width
    val h = size.height
    val cx = w / 2f + w * 0.04f * skew
    val mouthY = h * 0.66f

    // Геометрия рта (та же что в drawMouth) — нужна чтобы язык начинался
    // от верхней кромки рта, а не от центра.
    val curveAmp = h * 0.05f * mouthCurve
    val open = mouthOpening + speechAmp * 0.6f
    val openAmp = h * 0.04f * open
    val mouthTopY = mouthY - curveAmp - openAmp        // верхняя дуга рта
    val mouthBotY = mouthY - curveAmp + openAmp        // нижняя дуга рта

    // Корень языка — между губами (середина рта).
    val tongueRootY = (mouthTopY + mouthBotY) / 2f
    val tongueLen = h * 0.075f * extension
    val tongueTipY = mouthBotY + tongueLen             // кончик ниже нижней кромки
    val tongueWidth = w * 0.04f * (1f + extension * 0.4f)

    // Сама форма языка — каплевидный овал, расширенный к кончику
    val path = Path().apply {
        moveTo(cx - tongueWidth, tongueRootY)
        // правая сторона: вниз и плавно к кончику
        cubicTo(
            cx - tongueWidth * 1.2f, tongueRootY + tongueLen * 0.5f,
            cx - tongueWidth * 1.0f, tongueTipY,
            cx, tongueTipY + h * 0.005f,
        )
        cubicTo(
            cx + tongueWidth * 1.0f, tongueTipY,
            cx + tongueWidth * 1.2f, tongueRootY + tongueLen * 0.5f,
            cx + tongueWidth, tongueRootY,
        )
        close()
    }
    // Тёмная подложка по низу — даёт глубину
    drawPath(path = path, color = Color(0xFFB23A55))
    // Светлая середина (тёплый розовый язык)
    val highlight = Path().apply {
        val hw = tongueWidth * 0.55f
        moveTo(cx - hw, tongueRootY + h * 0.008f)
        cubicTo(
            cx - hw * 0.9f, (tongueRootY + tongueTipY) / 2f,
            cx - hw * 0.5f, tongueTipY - h * 0.008f,
            cx, tongueTipY - h * 0.005f,
        )
        cubicTo(
            cx + hw * 0.5f, tongueTipY - h * 0.008f,
            cx + hw * 0.9f, (tongueRootY + tongueTipY) / 2f,
            cx + hw, tongueRootY + h * 0.008f,
        )
        close()
    }
    drawPath(path = highlight, color = Color(0xFFE85D7F))
    // Контур
    drawPath(
        path = path,
        color = Color(0xFF7A2538),
        style = Stroke(width = w * 0.004f),
    )
}

private data class AnimPalette(
    val durMul: Float,
    val easing: Easing,
    val scaleXFudge: Float,
    val scaleYFudge: Float,
    val outerFudge: Float,
    val innerFudge: Float,
)

private data class MouthSpec(
    val stretch: Float,  // 0.5..1.4 ширина
    val curve: Float,    // -1..+1 (-1 печаль, 0 ровно, +1 улыбка)
)

private fun mouthSpecFor(p: Plutchik8): MouthSpec = when (p) {
    Plutchik8.Joy -> MouthSpec(stretch = 1.30f, curve = 0.85f)
    Plutchik8.Trust -> MouthSpec(stretch = 1.00f, curve = 0.20f)
    Plutchik8.Anger -> MouthSpec(stretch = 0.85f, curve = -0.20f)
    Plutchik8.Sadness -> MouthSpec(stretch = 0.95f, curve = -0.85f)
    Plutchik8.Surprise -> MouthSpec(stretch = 0.55f, curve = 0.0f)
    Plutchik8.Fear -> MouthSpec(stretch = 0.70f, curve = -0.35f)
    Plutchik8.Disgust -> MouthSpec(stretch = 0.85f, curve = -0.50f)
    Plutchik8.Anticipation -> MouthSpec(stretch = 1.05f, curve = 0.30f)
}

private fun DrawScope.drawMouth(
    emotion: Plutchik8,
    stretch: Float,
    curve: Float,
    opening: Float,
    speech: Float,
    skew: Float,
) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h * 0.66f
    val halfW = w * 0.16f * stretch
    val curveAmp = h * 0.05f * curve
    // speech * 1.4 — артикуляция выраженная, на пиках слогов рот реально открывается;
    // на затиханиях (a < 0.04) — speech=0 → rot закрыт через else-branch ниже.
    val open = opening + speech * 1.4f
    val openAmp = h * 0.06f * open
    val strokeWidth = w * 0.008f
    // skew наклоняет середину рта в сторону: правая часть выше или ниже левой
    val skewYDelta = h * 0.025f * skew
    val skewXShift = halfW * 0.20f * skew

    if (open < 0.05f) {
        val path = Path().apply {
            moveTo(cx - halfW, cy + skewYDelta)
            quadraticBezierTo(cx + skewXShift, cy - curveAmp, cx + halfW, cy - skewYDelta)
        }
        drawPath(
            path = path,
            color = MouthLineColor,
            style = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    } else {
        val top = Path().apply {
            moveTo(cx - halfW, cy + skewYDelta)
            quadraticBezierTo(cx + skewXShift, cy - curveAmp - openAmp, cx + halfW, cy - skewYDelta)
        }
        val bot = Path().apply {
            moveTo(cx - halfW, cy + skewYDelta)
            quadraticBezierTo(cx + skewXShift, cy - curveAmp + openAmp, cx + halfW, cy - skewYDelta)
        }
        drawPath(
            path = top,
            color = MouthLineColor,
            style = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
        drawPath(
            path = bot,
            color = MouthLineColor,
            style = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}

private fun DrawScope.drawCozmoEye(
    left: Boolean,
    gaze: Offset,
    blink: Float,
    color: Color,
    scaleX: Float,
    scaleY: Float,
    topR: Float,
    botR: Float,
    outerLift: Float,
    innerLift: Float,
    lowerArc: Float,
    upperArc: Float,
) {
    val w = size.width
    val h = size.height
    val baseHalfW = w * 0.18f * scaleX
    // +15% по высоте — глаза вытянуты вертикально согласно ТЗ.
    val baseHalfH = h * 0.085f * scaleY * 1.15f * (1f - blink * 0.92f)
    val gap = w * 0.10f
    val cxBase = if (left) w / 2f - baseHalfW - gap / 2f
    else w / 2f + baseHalfW + gap / 2f
    val cyBase = h * 0.46f

    val cx = cxBase + gaze.x * baseHalfW * 0.30f
    val cy = cyBase + gaze.y * baseHalfH * 0.35f

    val outerSign = if (left) -1f else 1f

    val outerLiftPx = outerLift * baseHalfH * 1.4f * outerSign
    val innerLiftPx = innerLift * baseHalfH * 1.4f * (-outerSign)

    val outerX = cx + outerSign * baseHalfW
    val innerX = cx + (-outerSign) * baseHalfW

    val topY = cy - baseHalfH
    val botY = cy + baseHalfH

    val outerTopY = topY + outerLiftPx
    val innerTopY = topY + innerLiftPx
    val outerBotY = botY + outerLiftPx * 0.30f
    val innerBotY = botY + innerLiftPx * 0.30f

    // Critical: радиусы углов не должны превышать halfH или сумма обоих > 1.9*halfH,
    // иначе lineTo по боковой грани идёт в обратную сторону и рисует "ступеньку".
    val maxR = baseHalfH * 0.85f
    var rTop = (baseHalfH * (0.20f + topR * 0.85f)).coerceAtMost(maxR)
    var rBot = (baseHalfH * (0.20f + botR * 0.85f)).coerceAtMost(maxR)
    val totalR = rTop + rBot
    val cap = baseHalfH * 1.85f
    if (totalR > cap) {
        val k = cap / totalR
        rTop *= k
        rBot *= k
    }

    // Усилен multiplier для arc — Cozmo Joy/Sad полумесяцы должны быть выраженными.
    val upperApexY = (outerTopY + innerTopY) / 2f + upperArc * baseHalfH * 1.50f
    val lowerApexY = (outerBotY + innerBotY) / 2f - lowerArc * baseHalfH * 1.50f
    val midX = cx

    val shape = Path().apply {
        // start near outer-top corner, after the corner-radius offset along top edge
        val outerTopCornerX = outerX + (-outerSign) * rTop
        moveTo(outerTopCornerX, outerTopY)
        // top edge with optional downward arch (upperArc)
        quadraticBezierTo(midX, upperApexY, innerX + (outerSign) * rTop, innerTopY)
        // inner-top rounded corner
        quadraticBezierTo(innerX, innerTopY, innerX, innerTopY + rTop)
        // inner edge straight
        lineTo(innerX, innerBotY - rBot)
        // inner-bot rounded corner
        quadraticBezierTo(innerX, innerBotY, innerX + (outerSign) * rBot, innerBotY)
        // bottom edge with optional upward arch (lowerArc)
        quadraticBezierTo(midX, lowerApexY, outerX + (-outerSign) * rBot, outerBotY)
        // outer-bot rounded corner
        quadraticBezierTo(outerX, outerBotY, outerX, outerBotY - rBot)
        // outer edge straight
        lineTo(outerX, outerTopY + rTop)
        // outer-top rounded corner
        quadraticBezierTo(outerX, outerTopY, outerTopCornerX, outerTopY)
        close()
    }

    // Outer halo glow
    drawPath(
        path = shape,
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.40f), Color.Transparent),
            center = Offset(cx, cy),
            radius = baseHalfW * 1.8f,
        ),
        style = Stroke(width = baseHalfH * 0.55f),
    )

    // Solid fill — Cozmo-style glow
    drawPath(
        path = shape,
        brush = Brush.radialGradient(
            colors = listOf(
                lerp(color, Color.White, 0.30f),
                color,
                lerp(color, EmotionPalette.Background, 0.30f),
            ),
            center = Offset(cx, cy - baseHalfH * 0.10f),
            radius = baseHalfW * 1.10f,
        ),
    )

    // Subtle inner highlight (top sheen)
    drawPath(
        path = shape,
        brush = Brush.linearGradient(
            colors = listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
            start = Offset(cx, outerTopY),
            end = Offset(cx, cy),
        ),
    )
}
