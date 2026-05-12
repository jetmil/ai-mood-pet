package io.github.jetmil.aimoodpet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jetmil.aimoodpet.eyes.MoodVector
import io.github.jetmil.aimoodpet.eyes.Plutchik8

@Composable
fun DebugHud(
    mood: MoodVector,
    stableEmotion: Plutchik8,
    stableStrength: Float,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color(0x88000000))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .width(180.dp),
    ) {
        Line("STABLE", "$stableEmotion ${pct(stableStrength)}", strong = true)
        Line("joy", pct(mood.joy))
        Line("trust", pct(mood.trust))
        Line("fear", pct(mood.fear))
        Line("surprise", pct(mood.surprise))
        Line("sadness", pct(mood.sadness))
        Line("disgust", pct(mood.disgust))
        Line("anger", pct(mood.anger))
        Line("anticip", pct(mood.anticipation))
        Line("arousal", pct(mood.arousal))
        Line("energy", pct(mood.energy))
    }
}

@Composable
private fun Line(name: String, value: String, strong: Boolean = false) {
    Text(
        text = "$name: $value",
        color = if (strong) Color(0xFFFFD23F) else Color(0xFFB3FF99),
        fontSize = if (strong) 11.sp else 9.sp,
        fontFamily = FontFamily.Monospace,
    )
}

private fun pct(v: Float): String = "%3d%%".format((v * 100).toInt())
