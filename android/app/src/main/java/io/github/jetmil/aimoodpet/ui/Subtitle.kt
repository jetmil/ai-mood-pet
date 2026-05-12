package io.github.jetmil.aimoodpet.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Subtitle(text: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = !text.isNullOrEmpty(),
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(450)),
        modifier = modifier,
    ) {
        Text(
            text = text ?: "",
            color = Color(0xFFF4ECDC).copy(alpha = 0.95f),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .background(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 18.dp, vertical = 12.dp)
                .wrapAlign(),
        )
    }
}

private fun Modifier.wrapAlign(): Modifier = this
