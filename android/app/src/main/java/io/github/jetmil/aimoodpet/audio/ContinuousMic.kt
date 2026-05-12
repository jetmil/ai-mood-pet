package io.github.jetmil.aimoodpet.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Постоянно работающий микрофон (AudioRecord) с ring buffer pre-roll.
 *
 * Решает две проблемы старого PttRecorder:
 *  1) первые слоги терялись потому что AudioRecord стартовал по триггеру.
 *  2) RMS микрофона не был доступен для VAD до старта — приходилось гадать
 *     по jawOpen из face mirror.
 *
 * Сейчас:
 *  - микрофон крутится постоянно после start()
 *  - чанки 100мс пишутся в ring buffer на ~1.5 сек (15 чанков)
 *  - currentRms обновляется на каждый чанк → ViewModel читает для VAD
 *  - startUtterance() — выдаёт ring buffer как pre-roll и шлёт каждый
 *    последующий чанк через onChunkInUtterance до stopUtterance()
 */
class ContinuousMic(
    private val onChunkInUtterance: (ByteArray, Boolean) -> Unit,
    private val onChunkAlways: ((ByteArray) -> Unit)? = null,
) {
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    private val capturing = AtomicBoolean(false)
    private val rmsLatest = AtomicInteger(0)
    private val voiceRmsLatest = AtomicInteger(0)
    private val muted = AtomicBoolean(false)
    /** Сырой RMS — все частоты. */
    val currentRms: Int get() = if (muted.get()) 0 else rmsLatest.get()
    /** RMS после band-pass — отсекает гул и шипение. */
    val currentVoiceRms: Int get() = if (muted.get()) 0 else voiceRmsLatest.get()
    val isCapturing: Boolean get() = capturing.get()
    val isAlive: Boolean get() = running.get()
    val isMuted: Boolean get() = muted.get()

    /**
     * Ставим mic на паузу пока тамагочи говорит — иначе acoustic feedback:
     * динамик → воздух → микрофон → whisper → новый ответ → петля.
     * Ring buffer тоже очищается, чтобы pre-roll после unmute не содержал
     * хвост звука тамагочи.
     */
    fun setMuted(value: Boolean) {
        if (muted.compareAndSet(!value, value)) {
            if (value) {
                synchronized(ring) {
                    for (i in ring.indices) ring[i] = null
                    ringHead = 0
                    ringFilled = 0
                }
                rmsLatest.set(0)
                voiceRmsLatest.set(0)
                Log.i(TAG, "muted: ring cleared")
            } else {
                Log.i(TAG, "unmuted")
            }
        }
    }

    // Band-pass filter: центр 700 Hz, Q≈1.0 — фокус на речевом диапазоне 300-1600 Hz.
    // Голос здесь 60-80% энергии. Клавиатура / удары / шипение — основная энергия
    // выше 1500 Hz, поэтому voiceRms/rawRms ratio будет низкий → False-start gate.
    private val voiceFilter = Biquad.bandPass(centerHz = 700.0, q = 1.0, sampleRate = SAMPLE_RATE.toDouble())

    private val chunkBytes = SAMPLE_RATE * 2 * CHUNK_MS / 1000   // 100ms = 3200 bytes
    private val ringSlots = (PREROLL_MS / CHUNK_MS)              // 15 slots
    private val ring = arrayOfNulls<ByteArray>(ringSlots)
    private var ringHead = 0
    @Volatile private var ringFilled = 0

    @Suppress("MissingPermission")
    fun start(): Boolean {
        if (running.get()) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize=$minBuf — no mic?")
            return false
        }
        val bufSize = (minBuf * 4).coerceAtLeast(chunkBytes * 8)
        // AudioSource.MIC вместо VOICE_COMMUNICATION:
        // на HyperOS VOICE_COMMUNICATION + HW AEC душит микрофон 200-500мс
        // после воспроизведения, теряя первые слоги ответа ownerа.
        // Софт AEC/NS/AGC ниже включаем явно поверх MIC.
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
        } catch (e: Throwable) {
            Log.e(TAG, "AudioRecord init failed", e)
            return false
        }
        // Софтверные эффекты (если устройство поддерживает).
        try {
            val sid = rec.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sid)?.enabled = true
                Log.i(TAG, "AEC enabled on session $sid")
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sid)?.enabled = true
                Log.i(TAG, "NS enabled")
            }
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sid)?.enabled = true
                Log.i(TAG, "AGC enabled")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "audio effects setup partial fail", e)
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord state=${rec.state}, releasing")
            rec.release()
            return false
        }
        try {
            rec.startRecording()
        } catch (e: Throwable) {
            Log.e(TAG, "startRecording failed", e)
            rec.release()
            return false
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.e(TAG, "recordingState=${rec.recordingState} — HyperOS Privacy?")
            rec.release()
            return false
        }
        recorder = rec
        running.set(true)
        synchronized(ring) {
            for (i in ring.indices) ring[i] = null
            ringHead = 0
            ringFilled = 0
        }
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val tmp = ByteArray(chunkBytes)
            try {
                while (running.get()) {
                    val r = rec.read(tmp, 0, tmp.size)
                    if (r <= 0) {
                        if (r < 0) { Log.w(TAG, "read returned $r"); break }
                        continue
                    }
                    val piece = tmp.copyOf(r)
                    if (muted.get()) {
                        // Тамагочи говорит — игнорируем входящие данные полностью.
                        continue
                    }
                    val rms = computeRms(piece)
                    rmsLatest.set(rms)
                    voiceRmsLatest.set(computeVoiceRms(piece))
                    // Always-callback (wake-word listener получает вне зависимости от capturing).
                    onChunkAlways?.let {
                        try { it(piece) } catch (e: Throwable) { Log.w(TAG, "alwaysChunk failed", e) }
                    }
                    if (capturing.get()) {
                        try { onChunkInUtterance(piece, false) } catch (e: Throwable) {
                            Log.w(TAG, "onChunk failed", e)
                        }
                    } else {
                        // ring buffer — пишем для будущего pre-roll
                        synchronized(ring) {
                            ring[ringHead] = piece
                            ringHead = (ringHead + 1) % ring.size
                            if (ringFilled < ring.size) ringFilled++
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "audio thread crashed", e)
            }
        }, "ContinuousMic").apply { isDaemon = true; start() }
        Log.i(TAG, "started bufSize=$bufSize chunk=$chunkBytes ringSlots=$ringSlots")
        return true
    }

    /**
     * Начинаем "выпускать" чанки в onChunkInUtterance. Сначала — ring buffer
     * (pre-roll), потом continue с реальным временем.
     */
    fun startUtterance() {
        if (capturing.compareAndSet(false, true)) {
            // Сливаем pre-roll в правильном порядке (oldest → newest).
            val preRoll: List<ByteArray> = synchronized(ring) {
                val out = ArrayList<ByteArray>(ringFilled)
                val start = if (ringFilled < ring.size) 0 else ringHead
                for (i in 0 until ringFilled) {
                    val idx = (start + i) % ring.size
                    ring[idx]?.let { out.add(it) }
                }
                ringFilled = 0
                out
            }
            Log.i(TAG, "utterance start: pre-roll ${preRoll.size} chunks (${preRoll.sumOf { it.size }}b)")
            for (chunk in preRoll) {
                try { onChunkInUtterance(chunk, false) } catch (_: Throwable) {}
            }
        }
    }

    fun stopUtterance() {
        if (capturing.compareAndSet(true, false)) {
            try { onChunkInUtterance(ByteArray(0), true) } catch (_: Throwable) {}
            Log.i(TAG, "utterance stop")
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        capturing.set(false)
        try { recorder?.stop() } catch (_: Throwable) {}
        recorder?.release()
        recorder = null
        try { thread?.join(800) } catch (_: InterruptedException) {}
        thread = null
        Log.i(TAG, "stopped")
    }

    private fun computeRms(pcm: ByteArray): Int {
        if (pcm.size < 2) return 0
        var sum = 0L
        var n = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = (((pcm[i + 1].toInt() and 0xFF) shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            sum += s.toLong() * s.toLong()
            n++
            i += 2
        }
        if (n == 0) return 0
        return sqrt(sum.toDouble() / n).toInt()
    }

    /**
     * RMS только в голосовом диапазоне 200–3400 Hz.
     * Гул кондея (50–120 Hz), удары стола (низкие транзиенты), шипение
     * вентилятора (>5 кГц) — отфильтрованы. Голос ownerа остаётся почти весь.
     */
    private fun computeVoiceRms(pcm: ByteArray): Int {
        if (pcm.size < 2) return 0
        var sum = 0.0
        var n = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = (((pcm[i + 1].toInt() and 0xFF) shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toDouble()
            val filtered = voiceFilter.process(s)
            sum += filtered * filtered
            n++
            i += 2
        }
        if (n == 0) return 0
        return sqrt(sum / n).toInt()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_MS = 100
        const val PREROLL_MS = 1500          // 1.5 сек захвата ДО триггера
        private const val TAG = "ContinuousMic"
    }
}
