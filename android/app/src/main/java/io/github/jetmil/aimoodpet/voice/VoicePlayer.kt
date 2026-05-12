package io.github.jetmil.aimoodpet.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Проигрыватель ogg-фраз из assets. Один поток в момент времени.
 * Speak() suspend до завершения проигрывания. Cancel() прерывает.
 *
 * Параллельно проигрывает envelope из <id>.env.json (сгенерён tools/gen_envelopes.py)
 * через колбэк onAmplitude — синхронно с движением рта на экране. Если sidecar нет
 * (старый bank) — рот двигается простой пульсацией, не статикой.
 */
class VoicePlayer(
    private val context: Context,
    private val onAmplitude: (Float) -> Unit = {},
) {

    private var mp: MediaPlayer? = null
    @Volatile var currentBasePath: String = "voice/baby_robot"

    private val _current = MutableStateFlow<VoicePhrase?>(null)
    val current: StateFlow<VoicePhrase?> = _current.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var envelopeJob: Job? = null

    suspend fun speak(phrase: VoicePhrase) = withContext(Dispatchers.IO) {
        try {
            stopInternal()
            val fd = context.assets.openFd("$currentBasePath/${phrase.id}.ogg")
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                prepare()
            }
            fd.close()
            mp = player
            _current.value = phrase
            // Запускаем envelope ПЕРЕД start() — чтобы старт совпал.
            startEnvelopePlayback(phrase.id)
            suspendCancellableCoroutine<Unit> { cont ->
                player.setOnCompletionListener {
                    if (cont.isActive) cont.resume(Unit)
                }
                player.setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MP error what=$what extra=$extra phrase=${phrase.id}")
                    if (cont.isActive) cont.resume(Unit)
                    true
                }
                cont.invokeOnCancellation { stopInternal() }
                player.start()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "speak failed for ${phrase.id}", e)
        } finally {
            stopInternal()
            _current.value = null
        }
    }

    fun cancel() {
        stopInternal()
        _current.value = null
    }

    private fun startEnvelopePlayback(phraseId: String) {
        envelopeJob?.cancel()
        val sidecarPath = "$currentBasePath/$phraseId.env.json"
        val (fps, frames) = try {
            val raw = context.assets.open(sidecarPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val obj = JSONObject(raw)
            val fps = obj.optInt("fps", 20)
            val arr = obj.optJSONArray("frames") ?: JSONArray()
            val list = FloatArray(arr.length()) { i -> arr.optDouble(i, 0.0).toFloat() }
            fps to list
        } catch (_: Throwable) {
            // sidecar отсутствует — рисуем мягкую пульсацию длиной ~2 сек чтобы рот
            // двигался хоть немного.
            20 to FloatArray(40) { i ->
                val phase = (i / 4f) % 1f
                if (phase < 0.5f) 0.35f else 0.10f
            }
        }
        if (frames.isEmpty()) return
        envelopeJob = scope.launch {
            val frameDelay = (1000L / fps).coerceAtLeast(20L)
            for (v in frames) {
                onAmplitude(v.coerceIn(0f, 1f))
                delay(frameDelay)
            }
            onAmplitude(0f)
        }
    }

    private fun stopInternal() {
        envelopeJob?.cancel()
        envelopeJob = null
        onAmplitude(0f)
        try { mp?.stop() } catch (_: Throwable) {}
        try { mp?.release() } catch (_: Throwable) {}
        mp = null
    }

    companion object { private const val TAG = "VoicePlayer" }
}
