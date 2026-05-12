package io.github.jetmil.aimoodpet.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val tamaScheme = darkColorScheme(
    background = Color(0xFF0A0808),
    surface = Color(0xFF0A0808),
    primary = Color(0xFFFFD23F),
    onPrimary = Color(0xFF0A0808),
    onBackground = Color(0xFFF4ECDC),
    onSurface = Color(0xFFF4ECDC),
)

@Composable
fun TamaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = tamaScheme, content = content)
}
