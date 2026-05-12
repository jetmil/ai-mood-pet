package io.github.jetmil.aimoodpet.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * Винтажная медно-золотая рамка поверх экрана. Отображает уже декорированный
 * Bitmap (Polaroid-кадр от PhotoSnapshotter) на ~4.5 сек или до тапа.
 */
@Composable
fun SnapshotOverlay(
    bitmap: Bitmap?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = bitmap != null,
        enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.85f, animationSpec = tween(280)),
        exit = fadeOut(tween(280)) + scaleOut(targetScale = 0.92f, animationSpec = tween(280)),
        modifier = modifier,
    ) {
        if (bitmap == null) return@AnimatedVisibility
        val img = remember(bitmap) { bitmap.asImageBitmap() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC0B0905))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            // Многослойная рамка: золотая полоска → медная окантовка → кремовое паспарту → фото
            Box(
                modifier = Modifier
                    .padding(28.dp)
                    .background(Color(0xFFB48A4E))      // медный фон-каркас
                    .padding(3.dp)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFFE9C77B), Color(0xFFB48A4E), Color(0xFFE9C77B),
                            ),
                        ),
                    )
                    .padding(2.dp)
                    .background(Color(0xFF7A5A2E))     // тёмная внутренняя полоса
                    .padding(2.dp)
                    .background(Color(0xFFEDDFB7))    // паспарту
                    .padding(10.dp),
            ) {
                Image(
                    bitmap = img,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .border(width = 1.dp, color = Color(0xFF7A5A2E))
                        .padding(0.dp),
                )
            }
        }
    }
}
