package io.github.jetmil.aimoodpet.debug

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Глобальный кольцевой лог-буфер для on-screen overlay.
 * Параллельно пишет в logcat с TAG=Tama.
 *
 * Расширение 08.05.2026:
 * - MAX 28 → 80, чтобы дольше копилась цепочка mic→stt→llm→tts→audio
 * - timestamp HH:mm:ss.SSS — миллисекунды для measurement
 * - durationMs — опциональный суффикс «850ms» для timing-событий
 * - Новые категории: STT, LLM, TTS, NET, GPU, TIMING
 */
object DebugLog {
    private const val MAX = 80
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val items = ArrayDeque<DebugItem>()
    private val _flow = MutableStateFlow<List<DebugItem>>(emptyList())
    val flow: StateFlow<List<DebugItem>> = _flow.asStateFlow()

    fun event(cat: DebugCat, message: String, durationMs: Long? = null) {
        val now = System.currentTimeMillis()
        val item = DebugItem(now, cat, message, durationMs)
        synchronized(items) {
            items.addLast(item)
            while (items.size > MAX) items.removeFirst()
            _flow.value = items.toList()
        }
        val durSuffix = durationMs?.let { " (${formatDur(it)})" } ?: ""
        Log.i("Tama", "[${cat.tag}] $message$durSuffix")
    }

    fun timeStr(ts: Long): String = sdf.format(Date(ts))

    fun formatDur(ms: Long): String = when {
        ms < 0 -> "?ms"
        ms < 1000 -> "${ms}ms"
        ms < 10_000 -> String.format(Locale.US, "%.1fs", ms / 1000.0)
        else -> "${ms / 1000}s"
    }
}

data class DebugItem(
    val tsMs: Long,
    val cat: DebugCat,
    val message: String,
    val durationMs: Long? = null,
)

/** Категория события — заголовок и цвет для overlay. */
data class DebugCat(val tag: String, val argb: Long) {
    companion object {
        val WS = DebugCat("ws", 0xFF7AC8FF)
        val VOICE = DebugCat("voice", 0xFFFFD954)
        val MIC = DebugCat("mic", 0xFFA0E55E)
        val VISION = DebugCat("vision", 0xFFFF7AB6)
        val MOOD = DebugCat("mood", 0xFFFFB060)
        val PHOTO = DebugCat("photo", 0xFFEDDFB7)
        val DIALOG = DebugCat("dialog", 0xFFC2B5FF)
        val ERROR = DebugCat("err", 0xFFFF5470)
        val WAKE = DebugCat("wake", 0xFFFFE08A)
        // Сервер-side стадии: stt = whisper, llm = gemma/gemini, tts = edge+ffmpeg.
        val STT = DebugCat("stt", 0xFF8FE7CC)
        val LLM = DebugCat("llm", 0xFFE7C28F)
        val TTS = DebugCat("tts", 0xFFCAA0FF)
        // Сетевые/инфра события: ping RTT, reconnect-counters.
        val NET = DebugCat("net", 0xFF6FC1FF)
        // GPU/VRAM hint: «видяха занята другой моделью».
        val GPU = DebugCat("gpu", 0xFFFF8E5E)
    }
}
