package io.github.jetmil.aimoodpet.voice

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import io.github.jetmil.aimoodpet.eyes.FsmState
import io.github.jetmil.aimoodpet.eyes.MoodVector
import io.github.jetmil.aimoodpet.eyes.Plutchik8
import kotlin.random.Random

/**
 * Слушает state-flows и при определённых переходах запускает фразу из VoiceBank.
 *
 * Стратегия:
 * — На переходы stableEmotion (Trust→Joy / →Sadness и т.п.) → фраза соотв. категории
 * — На лицо после долгого отсутствия → greeting
 * — На petting > 2.5 сек → petting
 * — На startled (FSM) → fear/surprise
 * — Каждые 30 сек idle с лицом → 6% шанс idle/curious фразы
 * — Каждые 60 сек idle БЕЗ лица → 8% шанс calling фразы
 *
 * Контекст: храним 5 последних произнесённых id, не повторяем подряд.
 */
class VoiceTriggerEngine(
    private val scope: CoroutineScope,
    private val bankProvider: () -> VoiceBank,
    private val player: VoicePlayer,
    private val stableEmotion: StateFlow<Plutchik8>,
    private val mood: StateFlow<MoodVector>,
    private val faceVisible: StateFlow<Boolean>,
    private val state: StateFlow<FsmState>,
    private val dialogActive: () -> Boolean = { false },
    private val replyPlaying: () -> Boolean = { false },
) {
    private val bank: VoiceBank get() = bankProvider()
    private val recentIds = ArrayDeque<String>(MAX_RECENT)
    private var lastEmotion: Plutchik8 = Plutchik8.Trust
    private var lastFaceSeenAt = System.currentTimeMillis()
    private var lastFaceVisible = false
    private var pettingStartedAt: Long = 0L
    private var pettingPhraseFired = false
    private var lastSpokenAt: Long = 0L
    private var summonStage: Int = -1     // индекс в SUMMON_CHECKPOINTS уже отыгранный
    private var emotionJob: Job? = null
    private var stateJob: Job? = null
    private var faceJob: Job? = null
    private var idleJob: Job? = null
    private var summonJob: Job? = null

    fun start() {
        emotionJob = scope.launch {
            stableEmotion.collectLatest { emo ->
                if (emo != lastEmotion) {
                    lastEmotion = emo
                    val strength = mood.value.valueOf(emo)
                    if (strength > 0.25f) {
                        val tag = when (emo) {
                            Plutchik8.Joy -> "joy"
                            Plutchik8.Sadness -> "sadness"
                            Plutchik8.Anger -> "anger"
                            Plutchik8.Surprise -> "surprise"
                            Plutchik8.Fear -> "fear"
                            Plutchik8.Anticipation -> "curious"
                            else -> null
                        }
                        if (tag != null) speakFromTag(tag, debounceMs = 7000)
                    }
                }
            }
        }
        stateJob = scope.launch {
            state.collectLatest { s ->
                when (s) {
                    FsmState.Sleeping -> speakFromTag("sleepy", debounceMs = 8000)
                    FsmState.Startled -> speakFromTag("fear", debounceMs = 4000)
                    else -> Unit
                }
            }
        }
        faceJob = scope.launch {
            faceVisible.collectLatest { visible ->
                val now = System.currentTimeMillis()
                if (visible && !lastFaceVisible) {
                    val absent = now - lastFaceSeenAt
                    if (absent > FACE_GREETING_AFTER_MS) {
                        speakFromTag("greeting", debounceMs = 0L)
                    }
                    summonStage = -1     // лицо появилось — сбросили эскалацию
                }
                if (!visible) lastFaceSeenAt = now
                lastFaceVisible = visible
            }
        }
        // Summon-эскалация: 1 / 2 / 4 / 12 / 20 минут отсутствия → разные пинки.
        summonJob = scope.launch {
            while (true) {
                delay(15_000)
                if (faceVisible.value) continue
                val absent = System.currentTimeMillis() - lastFaceSeenAt
                val stage = SUMMON_CHECKPOINTS.indexOfLast { it.first <= absent }
                if (stage > summonStage) {
                    summonStage = stage
                    val tag = SUMMON_CHECKPOINTS[stage].second
                    speakFromTag(tag, debounceMs = 0L)
                }
            }
        }
        idleJob = scope.launch {
            while (true) {
                delay(20_000)  // mumble нужны чаще чем idle, опрос 20s
                val now = System.currentTimeMillis()
                if (now - lastSpokenAt < 12_000) continue
                if (faceVisible.value) {
                    val r = Random.nextFloat()
                    when {
                        // Mumble — Cozmo-style chatter, бормотание себе под нос.
                        // Чаще чем большие фразы (8% за tick), но debounce их защитит.
                        r < 0.08f -> speakFromTag("mumble", debounceMs = 0L)
                        r < 0.10f -> speakFromTag("idle", debounceMs = 0L)
                        r < 0.115f -> speakFromTag("curious", debounceMs = 0L)
                    }
                } else {
                    if (Random.nextFloat() < 0.04f) {
                        speakFromTag("calling", debounceMs = 0L)
                    }
                }
            }
        }
    }

    fun stop() {
        emotionJob?.cancel(); emotionJob = null
        stateJob?.cancel(); stateJob = null
        faceJob?.cancel(); faceJob = null
        idleJob?.cancel(); idleJob = null
        summonJob?.cancel(); summonJob = null
        player.cancel()
    }

    /** Прямой вызов фразы на конкретный тег — для тапов по зонам. */
    fun speakNow(tag: String) {
        speakFromTag(tag, debounceMs = 1500)
    }

    fun onMagnetSpike(intensity: Float) {
        if (intensity < 0.30f) return
        // Магнит близко — пугается голосом тоже
        speakFromTag("fear", debounceMs = 4000)
    }

    fun onPettingStart() {
        pettingStartedAt = System.currentTimeMillis()
        pettingPhraseFired = false
        scope.launch {
            delay(2500)
            val held = System.currentTimeMillis() - pettingStartedAt
            if (held >= 2500 && !pettingPhraseFired) {
                pettingPhraseFired = true
                speakFromTag("petting", debounceMs = 0L)
            }
        }
    }

    fun onPettingStop(@Suppress("UNUSED_PARAMETER") heldMs: Long) {
        pettingPhraseFired = false
    }

    private fun speakFromTag(tag: String, debounceMs: Long) {
        if (!bank.has(tag)) return
        // Не перебиваем активный LLM-диалог и проигрывание реплики сервера.
        if (dialogActive() || replyPlaying()) {
            Log.i(TAG, "skip [$tag]: dialog active")
            return
        }
        val now = System.currentTimeMillis()
        if (debounceMs > 0 && now - lastSpokenAt < debounceMs) return
        val phrase = bank.pick(tag, recentIds) ?: return
        if (recentIds.size >= MAX_RECENT) recentIds.removeFirst()
        recentIds.addLast(phrase.id)
        lastSpokenAt = now
        Log.i(TAG, "speak [$tag] '${phrase.text}' (${phrase.id})")
        scope.launch { player.speak(phrase) }
    }

    companion object {
        private const val TAG = "VoiceTrigger"
        private const val MAX_RECENT = 5
        private const val FACE_GREETING_AFTER_MS = 30 * 60 * 1000L  // 30 мин

        // Эскалация звания при отсутствии лица. Mapping (мс отсутствия → tag)
        private val SUMMON_CHECKPOINTS = listOf(
             1L * 60_000L to "summon_1min",
             2L * 60_000L to "summon_2min",
             4L * 60_000L to "summon_4min",
            12L * 60_000L to "summon_12min",
            20L * 60_000L to "summon_20min",
        )
    }
}
