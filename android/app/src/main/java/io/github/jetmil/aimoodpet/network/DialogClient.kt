package io.github.jetmil.aimoodpet.network

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import io.github.jetmil.aimoodpet.debug.DebugCat
import io.github.jetmil.aimoodpet.debug.DebugLog
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

enum class DialogConnectionState { Disconnected, Connecting, Connected, Error }

data class DialogEvent(val type: String, val payload: JSONObject)

class DialogClient(
    private var wsUrl: String,
    private val userId: String = "",
    private var style: String = "bass_grumpy",
    private var mode: String = "local",
    private var authToken: String = "",
    private val ageDaysProvider: () -> Int = { 0 },
) {

    fun setStyle(newStyle: String) { style = newStyle }
    fun setMode(newMode: String) { mode = newMode }
    /** Change the target URL. The live socket is NOT closed here —
     *  the caller must close() then connect() to apply it. */
    fun setUrl(newUrl: String) { wsUrl = newUrl }
    fun currentUrl(): String = wsUrl
    /** Update the auth token. Takes effect on the next reconnect. */
    fun setAuthToken(newToken: String) { authToken = newToken }
    // pingInterval отключён: FastAPI Starlette не автоматически отвечает на
    // RFC 6455 ping-frame, OkHttp через ~60с сбрасывал ws по mute timeout.
    // Делаем app-level ping (type=ping → type=pong) — сервер уже это умеет.
    private val client = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null
    private val seq = AtomicInteger(0)
    @Volatile private var lastPongAt: Long = 0L
    @Volatile private var lastPingAt: Long = 0L
    @Volatile var lastPongRttMs: Long = -1L; private set
    val msSinceLastPong: Long
        get() = if (lastPongAt == 0L) 0 else System.currentTimeMillis() - lastPongAt

    private val _state = MutableStateFlow(DialogConnectionState.Disconnected)
    val state: StateFlow<DialogConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<DialogEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<DialogEvent> = _events.asSharedFlow()

    fun connect() {
        if (wsUrl.isBlank()) {
            Log.i(TAG, "connect skipped — empty wsUrl (Setup not done?)")
            _state.value = DialogConnectionState.Disconnected
            return
        }
        // Force-cleanup: if ws != null but state is Error/Disconnected (e.g. a
        // late callback never arrived), drop the ref so the guard below doesn't
        // block a fresh connect.
        val cur = ws
        val s = _state.value
        if (cur != null && (s == DialogConnectionState.Error || s == DialogConnectionState.Disconnected)) {
            try { cur.close(1000, "stale") } catch (_: Throwable) {}
            ws = null
        }
        if (ws != null) return
        _state.value = DialogConnectionState.Connecting
        val req = Request.Builder().url(wsUrl).build()
        ws = client.newWebSocket(req, listener)
        Log.i(TAG, "connect → newWebSocket")
    }

    fun close() {
        try { ws?.close(1000, "bye") } catch (_: Throwable) {}
        ws = null
        _state.value = DialogConnectionState.Disconnected
    }

    fun startUtterance() {
        seq.set(0)
        sendJson(JSONObject().put("type", "audio_start"))
    }

    fun sendAudioChunk(pcm: ByteArray, isFinal: Boolean) {
        val s = seq.getAndIncrement()
        val obj = JSONObject()
            .put("type", "audio_chunk")
            .put("seq", s)
            .put("is_final", isFinal)
            .put("pcm_b64", Base64.encodeToString(pcm, Base64.NO_WRAP))
        sendJson(obj)
    }

    fun sendText(text: String) {
        sendJson(JSONObject().put("type", "text_input").put("text", text))
    }

    /** Прямая TTS-фраза без LLM. Сервер только синтезирует и шлёт reply_audio.
     *  Используется когда тамагочи должен СКАЗАТЬ конкретный текст (askForName,
     *  scripted greetings), а не отвечать на user-message. */
    fun sendSay(text: String) {
        sendJson(JSONObject().put("type", "say").put("text", text))
    }

    /**
     * Прикладываем JPEG (320x240, q70) к utterance — сервер использует
     * как vision tool call если whisper-text matches keywords ("что в руках",
     * "как выгляжу" итд). На остальных utterance сервер просто игнорирует.
     */
    fun sendCameraFrame(jpegBytes: ByteArray) {
        if (jpegBytes.isEmpty()) return
        sendJson(
            JSONObject()
                .put("type", "camera_frame")
                .put("mime", "image/jpeg")
                .put("b64", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
        )
    }

    fun sendPing() {
        lastPingAt = System.currentTimeMillis()
        sendJson(JSONObject().put("type", "ping"))
    }

    fun sendObjectSeen(label: String) {
        sendJson(JSONObject().put("type", "object_seen").put("label", label))
    }

    /** Локальное распознавание жеста руки (MediaPipe Hands).
     *  Сервер реагирует специальным prompt, без vision-LLM (gemma3 принимала
     *  пальцы за «лапу зверя»). */
    fun sendGesture(label: String, confidence: Float) {
        sendJson(
            JSONObject()
                .put("type", "gesture_seen")
                .put("label", label)
                .put("confidence", confidence.toDouble()),
        )
    }

    fun sendAmbientLook() {
        sendJson(JSONObject().put("type", "ambient_look"))
    }

    fun sendPhotoSuggest() {
        sendJson(JSONObject().put("type", "photo_suggest"))
    }

    fun sendMoodSnapshot(mood: Map<String, Float>) {
        val v = JSONObject()
        for ((k, vv) in mood) v.put(k, vv.toDouble())
        sendJson(JSONObject().put("type", "mood_snapshot").put("vector", v))
    }

    private fun sendJson(obj: JSONObject) {
        val current = ws ?: run {
            Log.w(TAG, "send dropped — ws=null type=${obj.optString("type")}")
            return
        }
        try {
            val s = obj.toString()
            // Каждое исходящее сообщение → лог. Большие base64 поля
            // (audio_chunk pcm_b64, camera_frame b64) не пишем целиком —
            // только их размер.
            val type = obj.optString("type", "?")
            val preview = when (type) {
                "audio_chunk" -> "seq=${obj.optInt("seq")} final=${obj.optBoolean("is_final")} b64=${obj.optString("pcm_b64").length}"
                "camera_frame" -> "b64=${obj.optString("b64").length}"
                "hello" -> "user=${obj.optString("user_id")} style=${obj.optString("style")} mode=${obj.optString("mode")} age=${obj.optInt("age_days")}"
                "text_input", "say" -> "'${obj.optString("text").take(40)}'"
                "object_seen" -> "label=${obj.optString("label")}"
                "mood_snapshot" -> "vec=${obj.optJSONObject("vector")?.length() ?: 0}"
                else -> ""
            }
            Log.d(TAG, "[ws->] $type $preview (${s.length}b)")
            // В overlay шлём ВСЁ кроме audio_chunk (флудит 50/сек).
            // audio_chunk показывается только при is_final.
            val showInOverlay = type != "audio_chunk" || obj.optBoolean("is_final")
            if (showInOverlay) {
                DebugLog.event(DebugCat.WS, "→ $type $preview")
            }
            current.send(s)
        } catch (e: Throwable) {
            Log.w(TAG, "ws send failed type=${obj.optString("type")}", e)
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "ws open ${response.code}")
            _state.value = DialogConnectionState.Connected
            lastPongAt = System.currentTimeMillis()  // считаем что подключение «тёплое»
            val hello = JSONObject()
                .put("type", "hello")
                .put("user_id", userId)
                .put("style", style)
                .put("mode", mode)
                .put("age_days", ageDaysProvider())
            if (authToken.isNotEmpty()) hello.put("token", authToken)
            sendJson(hello)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val obj = JSONObject(text)
                val type = obj.optString("type", "")
                if (type == "pong") {
                    val now = System.currentTimeMillis()
                    if (lastPingAt > 0L) lastPongRttMs = now - lastPingAt
                    lastPongAt = now
                }
                _events.tryEmit(DialogEvent(type, obj))
            } catch (e: Throwable) {
                Log.w(TAG, "ws message parse failed: $text", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Late callback от старого, уже заменённого сокета — игнорируем,
            // чтобы не сбросить state живого нового подключения.
            if (ws !== webSocket) {
                Log.i(TAG, "ws closing (stale) $code $reason — ignored")
                return
            }
            Log.i(TAG, "ws closing $code $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (ws !== webSocket) {
                Log.i(TAG, "ws closed (stale) $code $reason — ignored")
                return
            }
            _state.value = DialogConnectionState.Disconnected
            ws = null
            Log.i(TAG, "ws closed $code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (ws !== webSocket) {
                Log.w(TAG, "ws failure (stale): ${t.message} — ignored")
                return
            }
            Log.w(TAG, "ws failure: ${t.message} resp=${response?.code}")
            _state.value = DialogConnectionState.Error
            ws = null
        }
    }

    companion object { private const val TAG = "DialogClient" }
}
