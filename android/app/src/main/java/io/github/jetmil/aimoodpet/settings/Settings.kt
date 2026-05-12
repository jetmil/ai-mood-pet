package io.github.jetmil.aimoodpet.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Voice persona. The text labels are localized to whatever language fits
 * your voice-bank assets — replace them with your own keys if you want.
 *
 * The key is also used as a folder name under `assets/voice/<key>/`.
 */
enum class VoiceStyle(val key: String, val label: String) {
    BabyRobot("baby_robot", "Baby robot"),
    BassGrumpy("bass_grumpy", "Grumpy bass"),
    Sage("sage", "Sage"),
    Flatterer("flatterer", "Flatterer"),
    Mowgli("mowgli", "Mowgli"),
    Statham("statham", "Statham"),
    ;
    companion object {
        fun byKey(k: String?): VoiceStyle = entries.firstOrNull { it.key == k } ?: BassGrumpy
    }
}

/**
 * Dialog backend mode. The pet sends `mode` in the WS hello — your server
 * decides what it means (which model, which RAG, which TTS).
 *
 * - `local_only`: phone-only, no WebSocket; just the pre-recorded voice bank.
 * - `local`: your self-hosted server (LLM + STT + TTS).
 * - `local_rag`: same server, but with a retrieval-augmented prompt.
 * - `cloud_lite`: a lightweight cloud fallback (low-latency on the road).
 * - `google`: cloud Gemini-style API (your server forwards to it).
 */
enum class DialogMode(val key: String, val label: String, val description: String) {
    LocalOnly("local_only", "Phone only", "Offline — only the pre-recorded voice bank"),
    MyServer("local", "My server", "Your self-hosted LLM + STT + TTS"),
    LocalRag("local_rag", "Server + RAG", "Your self-hosted LLM with retrieval augmentation"),
    CloudLite("cloud_lite", "Cloud (lite)", "Cloud fallback for low latency on mobile networks"),
    GoogleApi("google", "Cloud API", "Cloud LLM (e.g. Gemini) via your server"),
    ;
    companion object {
        fun byKey(k: String?): DialogMode = entries.firstOrNull { it.key == k } ?: MyServer
    }
}

/**
 * Non-sensitive UI / behaviour preferences. Stored in a plain SharedPreferences
 * because none of these reveal identity or grant access.
 */
data class TamaSettings(
    val voiceStyle: VoiceStyle = VoiceStyle.BassGrumpy,
    val dialogMode: DialogMode = DialogMode.MyServer,
    val visionEnabled: Boolean = true,
    val debugLogVisible: Boolean = false,
    /** Wall-clock time of the first launch. Used to compute the pet's age. */
    val birthMs: Long = 0L,
)

/**
 * Server credentials and identity. Stored in EncryptedSharedPreferences so
 * that the auth token never ends up in plain prefs xml in the data dir.
 *
 * - [wsUrl]    — full WebSocket URL of the user's server (`wss://host/ws/dialog`).
 * - [authToken] — bearer-style token sent in the WS hello frame.
 * - [ownerName] — what the pet should call the user (e.g. "Alex"). Defaults to "owner".
 * - [userId]    — opaque stable UUID generated on first launch. Not personal.
 */
data class UserConfig(
    val wsUrl: String = "",
    val authToken: String = "",
    val ownerName: String = "owner",
    val userId: String = "",
) {
    /** True when the user has filled in the minimum required fields. */
    val isConfigured: Boolean
        get() = wsUrl.isNotBlank()
}

class SettingsRepo(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Encrypted prefs for the WS URL / token / owner name. The master key is
    // hardware-backed where available (TEE / StrongBox).
    private val securePrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): TamaSettings {
        val savedBirth = prefs.getLong(KEY_BIRTH, 0L)
        val birth = if (savedBirth > 0L) savedBirth else {
            val now = System.currentTimeMillis()
            prefs.edit().putLong(KEY_BIRTH, now).apply()
            now
        }
        return TamaSettings(
            voiceStyle = VoiceStyle.byKey(prefs.getString(KEY_STYLE, null)),
            dialogMode = DialogMode.byKey(prefs.getString(KEY_MODE, null)),
            visionEnabled = prefs.getBoolean(KEY_VISION, true),
            debugLogVisible = prefs.getBoolean(KEY_DEBUG_LOG, false),
            birthMs = birth,
        )
    }

    fun save(s: TamaSettings) {
        prefs.edit()
            .putString(KEY_STYLE, s.voiceStyle.key)
            .putString(KEY_MODE, s.dialogMode.key)
            .putBoolean(KEY_VISION, s.visionEnabled)
            .putBoolean(KEY_DEBUG_LOG, s.debugLogVisible)
            .apply()
    }

    fun loadUserConfig(): UserConfig {
        var uid = securePrefs.getString(KEY_USER_ID, null)
        if (uid.isNullOrBlank()) {
            uid = UUID.randomUUID().toString()
            securePrefs.edit().putString(KEY_USER_ID, uid).apply()
        }
        return UserConfig(
            wsUrl = securePrefs.getString(KEY_WS_URL, "") ?: "",
            authToken = securePrefs.getString(KEY_AUTH_TOKEN, "") ?: "",
            ownerName = securePrefs.getString(KEY_OWNER_NAME, "owner") ?: "owner",
            userId = uid,
        )
    }

    fun saveUserConfig(c: UserConfig) {
        securePrefs.edit()
            .putString(KEY_WS_URL, c.wsUrl.trim())
            .putString(KEY_AUTH_TOKEN, c.authToken.trim())
            .putString(KEY_OWNER_NAME, c.ownerName.trim().ifBlank { "owner" })
            .putString(KEY_USER_ID, c.userId)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "tama_settings"
        private const val SECURE_PREFS_NAME = "tama_secure"
        private const val KEY_STYLE = "voice_style"
        private const val KEY_MODE = "dialog_mode"
        private const val KEY_VISION = "vision_enabled"
        private const val KEY_DEBUG_LOG = "debug_log_visible"
        private const val KEY_BIRTH = "birth_ms"
        private const val KEY_WS_URL = "ws_url"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_OWNER_NAME = "owner_name"
        private const val KEY_USER_ID = "user_id"
    }
}
