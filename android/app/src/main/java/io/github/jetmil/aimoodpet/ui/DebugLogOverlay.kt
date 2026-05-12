package io.github.jetmil.aimoodpet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jetmil.aimoodpet.debug.DebugItem
import io.github.jetmil.aimoodpet.debug.DebugLog

/**
 * Полупрозрачный лог-overlay внизу экрана. Скроллится снизу-вверх к свежим.
 * Расширение 08.05.2026:
 *  - выше (220→320dp) и шире (360→440dp) — копится больше истории
 *  - ms-таймстамп
 *  - duration-суффикс «850ms / 1.4s» для timing-событий
 *  - категория жирная — глаз быстрее цепляется
 */
@Composable
fun DebugLogOverlay(
    items: List<DebugItem>,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val scroll = rememberScrollState()
    LaunchedEffect(items.size) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .heightIn(max = 320.dp)
                .background(
                    color = Color(0xD60C0A06),
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .verticalScroll(scroll),
        ) {
            for (item in items) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = DebugLog.timeStr(item.tsMs),
                        color = Color(0xFF8E8576),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(72.dp),
                    )
                    Text(
                        text = item.cat.tag,
                        color = Color(item.cat.argb),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(48.dp),
                    )
                    val msgWithDur = if (item.durationMs != null) {
                        "${item.message}  ⏱${DebugLog.formatDur(item.durationMs)}"
                    } else item.message
                    Text(
                        text = msgWithDur,
                        color = Color(0xFFE8E0CE),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(modifier = Modifier.padding(top = 1.dp))
            }
        }
    }
}
