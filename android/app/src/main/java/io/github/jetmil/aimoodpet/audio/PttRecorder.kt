package io.github.jetmil.aimoodpet.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AudioRecord PCM 16k mono на ОТДЕЛЬНОМ Thread (не Coroutine — blocking read
 * нельзя на coroutine workers, ANR'ит main thread через 10 сек).
 *
 * Источник AudioSource.VOICE_COMMUNICATION — включает hardware AEC + NS на чипе.
 */
class PttRecorder(
    private val onChunk: (ByteArray, Boolean) -> Unit,
) {
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    private val totalBuf = ByteArrayOutputStream()

    @Suppress("MissingPermission")
    fun start(): Boolean {
        if (running.get()) return true
        val sampleRate = SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize=$minBuf — no mic?")
            return false
        }
        val bufSize = (minBuf * 2).coerceAtLeast(SAMPLE_RATE * 2 / 4)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
        } catch (e: Throwable) {
            Log.e(TAG, "AudioRecord init failed", e)
            return false
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
            Log.e(TAG, "recordingState=${rec.recordingState} — HyperOS Privacy блокирует?")
            rec.release()
            return false
        }
        recorder = rec
        running.set(true)
        synchronized(totalBuf) { totalBuf.reset() }
        val chunkBytes = SAMPLE_RATE * 2 * 150 / 1000  // 150 мс
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val tmp = ByteArray(chunkBytes)
            try {
                while (running.get()) {
                    val r = rec.read(tmp, 0, tmp.size)
                    if (r <= 0) {
                        if (r < 0) {
                            Log.w(TAG, "read returned $r, stopping")
                            break
                        }
                        continue
                    }
                    val piece = tmp.copyOf(r)
                    synchronized(totalBuf) { totalBuf.write(piece) }
                    try { onChunk(piece, false) } catch (e: Throwable) { Log.w(TAG, "onChunk failed", e) }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "audio thread crashed", e)
            }
        }, "PttRecorder").apply { isDaemon = true; start() }
        Log.i(TAG, "recording started bufSize=$bufSize chunk=$chunkBytes")
        return true
    }

    fun stop(): ByteArray {
        if (!running.compareAndSet(true, false)) return ByteArray(0)
        try {
            recorder?.stop()
        } catch (e: Throwable) {
            Log.w(TAG, "stop failed", e)
        }
        recorder?.release()
        recorder = null
        try { thread?.join(800) } catch (_: InterruptedException) {}
        thread = null
        val total = synchronized(totalBuf) { totalBuf.toByteArray() }
        try { onChunk(ByteArray(0), true) } catch (e: Throwable) { Log.w(TAG, "final onChunk", e) }
        Log.i(TAG, "stopped, total=${total.size}b dur=${(total.size * 1000) / (SAMPLE_RATE * 2)}ms")
        return total
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val TAG = "PttRecorder"
    }
}
