package io.github.jetmil.aimoodpet.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Проигрывает ogg/mp3-байты из `reply_audio`. Поддерживает streaming —
 * сервер шлёт несколько reply_audio с инкрементируемым `seq`, плеер
 * накапливает их в FIFO и проигрывает по очереди. На onCompletion
 * автоматически запускает следующий, чтобы между предложениями была
 * минимальная пауза.
 *
 * cancel() — прерывает текущий + очищает очередь.
 */
class AudioReplyPlayer(
    private val context: Context,
    private val onAmplitude: (Float) -> Unit = {},
) {

    private val queue = ArrayDeque<QueueItem>()
    private var mp: MediaPlayer? = null
    private var currentFile: File? = null
    @Volatile private var _isPlaying: Boolean = false
    private val sessionId = AtomicLong(0)
    val isPlaying: Boolean get() = _isPlaying || queue.isNotEmpty()

    private data class QueueItem(val sid: Long, val bytes: ByteArray, val seq: Int, val isFinal: Boolean)

    /** Старая API сохранена для совместимости (sendSay, single reply от gemini fallback и т.п.).
     *  Это «replace» поведение — отменяет очередь и играет одну фразу. */
    fun play(oggBytes: ByteArray, onDone: () -> Unit = {}) {
        cancel()
        enqueue(oggBytes, seq = 0, isFinal = true, onDone = onDone)
    }

    /** Streaming-режим. Добавить в очередь. Если ничего не играет — старт. */
    fun enqueue(oggBytes: ByteArray, seq: Int = 0, isFinal: Boolean = false, onDone: () -> Unit = {}) {
        val sid = sessionId.get()
        synchronized(queue) {
            queue.addLast(QueueItem(sid, oggBytes, seq, isFinal))
            Log.i(TAG, "enqueue seq=$seq final=$isFinal bytes=${oggBytes.size} qsize=${queue.size}")
        }
        if (!_isPlaying) startNext(onDone)
    }

    private fun startNext(onDone: () -> Unit) {
        val item: QueueItem
        synchronized(queue) {
            if (queue.isEmpty()) {
                _isPlaying = false
                onAmplitude(0f)
                return
            }
            item = queue.removeFirst()
        }
        // Если sessionId сменился (cancel + новая речь) — пропускаем устаревшее.
        if (item.sid != sessionId.get()) {
            Log.i(TAG, "skip stale seq=${item.seq} (sid mismatch)")
            startNext(onDone)
            return
        }
        try {
            val tmp = File(context.cacheDir, "reply_${System.currentTimeMillis()}_${item.seq}.ogg")
            tmp.writeBytes(item.bytes)
            currentFile = tmp
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(tmp.absolutePath)
                setOnCompletionListener {
                    Log.i(TAG, "playback done seq=${item.seq} (${item.bytes.size}b)")
                    cleanupOne()
                    // Сразу следующий chunk — никакой паузы.
                    startNext(onDone)
                }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "playback error seq=${item.seq} what=$what extra=$extra")
                    cleanupOne()
                    startNext(onDone)
                    true
                }
                prepare()
                start()
            }
            mp = player
            _isPlaying = true
            // Если это финал и очередь пуста — onDone после завершения.
            if (item.isFinal && queue.isEmpty()) {
                // обработается через setOnCompletionListener → startNext → пустая очередь
            }
            Log.i(TAG, "playback start seq=${item.seq} (${item.bytes.size}b)")
        } catch (e: Throwable) {
            Log.e(TAG, "play failed seq=${item.seq}", e)
            cleanupOne()
            startNext(onDone)
        }
    }

    fun cancel() {
        sessionId.incrementAndGet()
        synchronized(queue) { queue.clear() }
        cleanupOne()
        _isPlaying = false
        onAmplitude(0f)
    }

    private fun cleanupOne() {
        try { mp?.stop() } catch (_: Throwable) {}
        try { mp?.release() } catch (_: Throwable) {}
        mp = null
        try { currentFile?.delete() } catch (_: Throwable) {}
        currentFile = null
    }

    companion object { private const val TAG = "AudioReplyPlayer" }
}
