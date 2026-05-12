package io.github.jetmil.aimoodpet.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Vosk-based offline wake-word detector. Без Picovoice / email-регистрации.
 *
 * Слушает PCM-чанки от ContinuousMic параллельно. Recognizer настроен на
 * grammar — узкий словарь wake-слов, чтобы не путать с реальной речью.
 * При hit — onWake(word) → ViewModel форсирует startUtterance().
 *
 * Модель ~45 MB, копируется из assets/vosk-model-ru → files/vosk-model-ru
 * на первом старте (Vosk не умеет читать compressed asset).
 */
class WakeWordEngine(
    private val context: Context,
    private val keywords: List<String>,
    private val onWake: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initJob: Job? = null
    @Volatile private var model: Model? = null
    @Volatile private var recognizer: Recognizer? = null
    @Volatile var ready: Boolean = false
        private set
    @Volatile private var lastWakeAt: Long = 0L
    // Vosk JNI выполняется только на этом потоке — никогда не на audio thread
    // (там SIGABRT'ит). Single-thread bounded executor с непринятыми задачами
    // отбрасывает старые чтобы не накапливалась очередь.
    private val voskExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Vosk-feed").apply { isDaemon = true; priority = Thread.NORM_PRIORITY }
    }
    private val pendingFeeds = AtomicInteger(0)

    fun init() {
        if (initJob != null) return
        initJob = scope.launch {
            try {
                val modelDir = ensureModelOnDisk()
                Log.i(TAG, "loading model from ${modelDir.absolutePath}")
                val m = Model(modelDir.absolutePath)
                // Grammar JSON: ["атама","робот","тамагочи","[unk]"] — последний нужен,
                // иначе Vosk будет навязывать одно из слов в любом аудио.
                val grammar = JSONArrayLike(keywords + "[unk]")
                val r = Recognizer(m, SAMPLE_RATE.toFloat(), grammar)
                r.setMaxAlternatives(0)
                model = m
                recognizer = r
                ready = true
                Log.i(TAG, "wake-word ready, keywords=$keywords")
            } catch (e: Throwable) {
                Log.e(TAG, "wake-word init failed", e)
            }
        }
    }

    /**
     * Принимает PCM s16le 16kHz mono. Vosk ожидает byte[]. Возвращается
     * FinalResult (json) когда ASR завершил сегмент.
     */
    fun feed(pcm: ByteArray) {
        if (!ready) return
        // Кладём чанк в single-thread queue. Если очередь раздулась
        // (Vosk не успевает обработать) — дропаем чтобы не было задержки.
        val q = pendingFeeds.incrementAndGet()
        if (q > MAX_PENDING) {
            pendingFeeds.decrementAndGet()
            return
        }
        try {
            voskExecutor.submit {
                pendingFeeds.decrementAndGet()
                val r = recognizer ?: return@submit
                try {
                    val finished = r.acceptWaveForm(pcm, pcm.size)
                    val text = if (finished) {
                        JSONObject(r.result).optString("text", "")
                    } else {
                        JSONObject(r.partialResult).optString("partial", "")
                    }
                    if (text.isNotBlank()) checkHit(text)
                } catch (e: Throwable) {
                    Log.w(TAG, "feed task failed", e)
                }
            }
        } catch (e: Throwable) {
            pendingFeeds.decrementAndGet()
            Log.w(TAG, "submit failed", e)
        }
    }

    private fun checkHit(text: String) {
        val now = System.currentTimeMillis()
        if (now - lastWakeAt < COOLDOWN_MS) return
        val lower = text.lowercase(Locale.getDefault())
        val match = keywords.firstOrNull { kw ->
            lower.contains(kw.lowercase(Locale.getDefault()))
        } ?: return
        lastWakeAt = now
        Log.i(TAG, "WAKE detected: '$match' in '$text'")
        // Сбрасываем recognizer чтобы тот же hit не повторялся.
        try { recognizer?.reset() } catch (_: Throwable) {}
        onWake(match)
    }

    fun reset() {
        // Vosk Recognizer.reset() НЕ thread-safe относительно feed().
        // Параллельный native-вызов → SIGABRT в LatticeIncrementalDecoder.
        // Submit на тот же executor — гарантирует сериализацию.
        try {
            voskExecutor.submit {
                try { recognizer?.reset() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) { /* executor уже shutdown */ }
    }

    fun close() {
        try { recognizer?.close() } catch (_: Throwable) {}
        try { model?.close() } catch (_: Throwable) {}
        recognizer = null
        model = null
        ready = false
        initJob?.cancel()
        try { voskExecutor.shutdownNow() } catch (_: Throwable) {}
    }

    private fun ensureModelOnDisk(): File {
        val target = File(context.filesDir, "vosk-model-ru")
        val markerVersion = 1   // если модель меняется — поднимаем
        val marker = File(target, ".version")
        if (target.isDirectory && marker.exists()
            && marker.readText().trim() == markerVersion.toString()
        ) return target
        target.deleteRecursively()
        target.mkdirs()
        copyAssetDir("vosk-model-ru", target)
        marker.writeText(markerVersion.toString())
        return target
    }

    private fun copyAssetDir(assetPath: String, target: File) {
        val entries = context.assets.list(assetPath).orEmpty()
        if (entries.isEmpty()) {
            // Файл, не папка
            context.assets.open(assetPath).use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
            return
        }
        target.mkdirs()
        for (e in entries) {
            val sub = "$assetPath/$e"
            val subTarget = File(target, e)
            val children = context.assets.list(sub).orEmpty()
            if (children.isEmpty()) {
                context.assets.open(sub).use { input ->
                    FileOutputStream(subTarget).use { out -> input.copyTo(out) }
                }
            } else {
                copyAssetDir(sub, subTarget)
            }
        }
    }

    /** Vosk Recognizer requires plain JSON-array string. */
    @Suppress("FunctionName")
    private fun JSONArrayLike(items: List<String>): String =
        items.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val TAG = "WakeWord"
        private const val COOLDOWN_MS = 2500L
        private const val MAX_PENDING = 6   // ~600мс PCM в очереди — больше дропаем
    }
}
