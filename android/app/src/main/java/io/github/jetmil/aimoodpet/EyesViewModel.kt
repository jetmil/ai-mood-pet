package io.github.jetmil.aimoodpet

import android.app.Application
import android.content.Context
import android.hardware.SensorManager
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.jetmil.aimoodpet.audio.AudioReplyPlayer
import io.github.jetmil.aimoodpet.audio.ContinuousMic
import io.github.jetmil.aimoodpet.audio.PttRecorder
import io.github.jetmil.aimoodpet.audio.WakeWordEngine
import io.github.jetmil.aimoodpet.dialog.DialogPhase
import io.github.jetmil.aimoodpet.dialog.DialogUiState
import io.github.jetmil.aimoodpet.eyes.EyeMapper
import io.github.jetmil.aimoodpet.eyes.EyeRenderParams
import io.github.jetmil.aimoodpet.eyes.FaceFrame
import io.github.jetmil.aimoodpet.eyes.FsmState
import io.github.jetmil.aimoodpet.eyes.MirrorEmotion
import io.github.jetmil.aimoodpet.eyes.MoodEngine
import io.github.jetmil.aimoodpet.eyes.MoodVector
import io.github.jetmil.aimoodpet.eyes.Plutchik8
import io.github.jetmil.aimoodpet.eyes.MagnetDetector
import io.github.jetmil.aimoodpet.eyes.ShakeDetector
import io.github.jetmil.aimoodpet.eyes.StableDominant
import io.github.jetmil.aimoodpet.network.DialogClient
import io.github.jetmil.aimoodpet.network.DialogConnectionState
import io.github.jetmil.aimoodpet.settings.DialogMode
import io.github.jetmil.aimoodpet.settings.SettingsRepo
import io.github.jetmil.aimoodpet.settings.TamaSettings
import io.github.jetmil.aimoodpet.settings.VoiceStyle
import io.github.jetmil.aimoodpet.voice.VoiceBank
import io.github.jetmil.aimoodpet.voice.VoicePhrase
import io.github.jetmil.aimoodpet.voice.VoicePlayer
import io.github.jetmil.aimoodpet.voice.VoiceTriggerEngine

class EyesViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = MoodEngine(viewModelScope)
    private val sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val shake = ShakeDetector(sensorManager) { engine.onShake(it) }
    private val magnet = MagnetDetector(sensorManager) {
        engine.onMagnetSpike(it)
        voiceTrigger.onMagnetSpike(it)
    }
    private val tilt = io.github.jetmil.aimoodpet.eyes.TiltDetector(sensorManager) { state, mag ->
        engine.onTilt(state, mag)
        // Голосовая реакция на сильные tilt-события.
        when (state) {
            io.github.jetmil.aimoodpet.eyes.TiltState.Flipped -> voiceTrigger.speakNow("fear")
            io.github.jetmil.aimoodpet.eyes.TiltState.UpsideDown -> voiceTrigger.speakNow("surprise")
            io.github.jetmil.aimoodpet.eyes.TiltState.FaceUp -> voiceTrigger.speakNow("sadness")
            else -> Unit
        }
    }

    private val _mirror = MutableStateFlow(MirrorEmotion())
    val mirror: StateFlow<MirrorEmotion> = _mirror.asStateFlow()

    private val _faceVisible = MutableStateFlow(false)
    val faceVisible: StateFlow<Boolean> = _faceVisible.asStateFlow()

    private val _smoothMood = MutableStateFlow(MoodVector())
    val smoothMood: StateFlow<MoodVector> = _smoothMood.asStateFlow()

    val mood: StateFlow<MoodVector> = engine.mood
    val state: StateFlow<FsmState> = engine.state

    private val stableDominant = StableDominant()
    private val _stableEmotion = MutableStateFlow(Plutchik8.Trust)
    val stableEmotion: StateFlow<Plutchik8> = _stableEmotion.asStateFlow()
    private val _stableStrength = MutableStateFlow(0f)
    val stableStrength: StateFlow<Float> = _stableStrength.asStateFlow()

    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

    private val _forehead = MutableStateFlow<io.github.jetmil.aimoodpet.eyes.ForeheadBadge?>(null)
    val forehead: StateFlow<io.github.jetmil.aimoodpet.eyes.ForeheadBadge?> = _forehead.asStateFlow()

    private val _lastSnapshot = MutableStateFlow<android.graphics.Bitmap?>(null)
    val lastSnapshot: StateFlow<android.graphics.Bitmap?> = _lastSnapshot.asStateFlow()
    @Volatile private var lastSnapshotAt: Long = 0L

    val render: StateFlow<EyeRenderParams> = combine(
        engine.state,
        engine.mood,
        engine.gaze,
        engine.blink,
        _mirror,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val s = values[0] as FsmState
        val m = values[1] as MoodVector
        val g = values[2] as Offset
        val b = values[3] as Float
        val mir = values[4] as MirrorEmotion
        EyeMapper.render(s, m, g, b, mir)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(2000),
        initialValue = EyeMapper.render(FsmState.Idle, MoodVector(), Offset.Zero, 0f, MirrorEmotion()),
    )

    private val _cameraGranted = MutableStateFlow(false)
    val cameraGranted: StateFlow<Boolean> = _cameraGranted.asStateFlow()

    private val _micGranted = MutableStateFlow(false)
    val micGranted: StateFlow<Boolean> = _micGranted.asStateFlow()

    // Settings (persistent via SharedPreferences / EncryptedSharedPreferences).
    private val settingsRepo = SettingsRepo(app)
    private val _settings = MutableStateFlow(settingsRepo.load())
    val settings: StateFlow<TamaSettings> = _settings.asStateFlow()
    // User config: WS URL, auth token, owner name, stable UUID.
    private val _userConfig = MutableStateFlow(settingsRepo.loadUserConfig())
    val userConfig: StateFlow<io.github.jetmil.aimoodpet.settings.UserConfig> =
        _userConfig.asStateFlow()

    // Lipsync state — объявляем до VoicePlayer/AudioReplyPlayer, потому что
    // их колбэки пишут сюда.
    private val _speechAmp = MutableStateFlow(0f)
    val speechAmp: StateFlow<Float> = _speechAmp.asStateFlow()
    private var envelopeJob: kotlinx.coroutines.Job? = null
    // Серверный envelope — единственный источник истины для lipsync.
    // Visualizer на HyperOS отдаёт мусор без cleanup-ов и оставлял рот
    // полуоткрытым, поэтому отключён (см. AudioReplyPlayer).
    private val replyAudio = AudioReplyPlayer(app) { /* unused */ }

    // Voice — стиль выбирается через настройки.
    @Volatile private var voiceBank: VoiceBank = VoiceBank(app, _settings.value.voiceStyle.key)
    private val voicePlayer = VoicePlayer(app, onAmplitude = { amp ->
        // Pre-recorded фразы тоже двигают рот через envelope sidecar.
        // Перебивает только если AudioReplyPlayer не играет (иначе серверные
        // ответы и фразы из bank спорили бы за _speechAmp).
        if (!replyAudio.isPlaying) _speechAmp.value = amp
    }).also {
        it.currentBasePath = "voice/${_settings.value.voiceStyle.key}"
    }
    private val voiceTrigger = VoiceTriggerEngine(
        scope = viewModelScope,
        bankProvider = { voiceBank },
        player = voicePlayer,
        stableEmotion = _stableEmotion,
        mood = _smoothMood,
        faceVisible = _faceVisible,
        state = engine.state,
        dialogActive = { _dialog.value.phase != DialogPhase.Idle },
        // Mic активно пишет → bank молчит (acoustic feedback prevention).
        replyPlaying = { replyAudio.isPlaying || continuousMic.isCapturing },
    )
    val currentSpeak: StateFlow<VoicePhrase?> = voicePlayer.current

    // Dialog — the user supplies one WebSocket URL via Settings (Setup screen).
    // No backend-specific routing: bring your own server.
    private val dialogUrl: String get() = _userConfig.value.wsUrl
    private val dialogClient = DialogClient(
        dialogUrl,
        userId = _userConfig.value.userId,
        style = _settings.value.voiceStyle.key,
        mode = _settings.value.dialogMode.key,
        authToken = _userConfig.value.authToken,
        ageDaysProvider = {
            val birth = _settings.value.birthMs
            if (birth > 0L) ((System.currentTimeMillis() - birth) / 86_400_000L).toInt()
            else 0
        },
    )
    val connection: StateFlow<DialogConnectionState> = dialogClient.state
    // Continuous microphone — стартует один раз и крутится пока есть permission.
    // Pre-roll даёт первые 1.5 сек до VAD-триггера → не теряем начало фразы.
    // Wake-word: Vosk small с узкой grammar легко матчит созвучия,
    // поэтому здесь ТОЛЬКО call-имена. Photo / vision команды — через whisper
    // на сервере (full ASR, точнее).
    // Wake words: edit this list to whatever your Vosk grammar should match.
    // Keep them short and phonetically distinct. The asset `vosk-model-ru/`
    // is Russian — change the model and these keywords for other languages.
    private val wakeWord = WakeWordEngine(
        context = app,
        keywords = listOf("робот", "слышишь"),
        onWake = { word ->
            io.github.jetmil.aimoodpet.debug.DebugLog.event(
                io.github.jetmil.aimoodpet.debug.DebugCat.WAKE, "hit '$word'")
            forceStartUtteranceFromWake()
        },
    )

    private val continuousMic = ContinuousMic(
        onChunkInUtterance = { pcm, isFinal ->
            if (pcm.isNotEmpty()) dialogClient.sendAudioChunk(pcm, false)
            if (isFinal) {
                val durMs = if (voiceStartedAt > 0L) System.currentTimeMillis() - voiceStartedAt else 0L
                io.github.jetmil.aimoodpet.debug.DebugLog.event(
                    io.github.jetmil.aimoodpet.debug.DebugCat.MIC,
                    "is_final → отправлено",
                    durationMs = durMs,
                )
                val visionOn = _settings.value.visionEnabled
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    if (visionOn) {
                        val jpeg = io.github.jetmil.aimoodpet.vision.PhotoSnapshotter.lastFrameAsJpeg()
                        if (jpeg.isNotEmpty()) {
                            dialogClient.sendCameraFrame(jpeg)
                            io.github.jetmil.aimoodpet.debug.DebugLog.event(
                                io.github.jetmil.aimoodpet.debug.DebugCat.VISION, "frame ${jpeg.size}b → server")
                        }
                    }
                    dialogClient.sendAudioChunk(ByteArray(0), true)
                }
                lastUtteranceFinishedAt = System.currentTimeMillis()
                _dialog.value = _dialog.value.copy(phase = DialogPhase.Sent)
                viewModelScope.launch {
                    delay(30_000)
                    val s = _dialog.value
                    if (s.phase == DialogPhase.Sent || s.phase == DialogPhase.Transcribing) {
                        Log.w(TAG, "dialog watchdog: no reply 30s → reset")
                        _dialog.value = DialogUiState(phase = DialogPhase.Idle)
                    }
                }
            }
        },
        onChunkAlways = { pcm -> wakeWord.feed(pcm) },
    )
    private var micRunning: Boolean = false
    private var pttRecorder: PttRecorder? = null
    private var pttPressedAt: Long = 0L
    private var pttAuto: Boolean = false
    @Volatile private var listenDeadlineAt: Long = 0L
    @Volatile private var voiceStartedAt: Long = 0L
    @Volatile private var falseProbeTicks: Int = 0
    private var lastUtteranceFinishedAt: Long = 0L
    private val _dialog = MutableStateFlow(DialogUiState())
    val dialog: StateFlow<DialogUiState> = _dialog.asStateFlow()

    init {
        engine.start()
        engine.mirrorAmplifier = mirrorAmpFor(_settings.value.voiceStyle)
        shake.start()
        magnet.start()
        tilt.start()
        voiceTrigger.start()
        viewModelScope.launch {
            while (true) {
                delay(240)  // 4Hz вместо 8Hz EMA tick — холоднее
                val curMood = engine.mood.value
                val smooth = curMood.ema(_smoothMood.value, 0.30f)
                _smoothMood.value = smooth
                _thinking.value = engine.thinkingActiveExternal
                if (engine.thinkingActiveExternal) {
                    // Прямой override — обходим гистерезис StableDominant.
                    _stableEmotion.value = Plutchik8.Anticipation
                    _stableStrength.value = 0.95f
                } else {
                    val emo = stableDominant.update(smooth)
                    _stableEmotion.value = emo
                    _stableStrength.value = smooth.valueOf(emo)
                }
                _forehead.value = computeForeheadBadge(
                    thinking = engine.thinkingActiveExternal,
                    fsm = engine.state.value,
                    petting = engine.isPetting,
                    emo = _stableEmotion.value,
                    strength = _stableStrength.value,
                    smooth = smooth,
                )
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(2000)  // trace 0.5Hz — log в проде, не 2Hz
                val m = _smoothMood.value
                val emo = _stableEmotion.value
                val str = _stableStrength.value
                Log.i(TRACE, "mood joy=${pct(m.joy)} trust=${pct(m.trust)} sad=${pct(m.sadness)} anger=${pct(m.anger)} sur=${pct(m.surprise)} fear=${pct(m.fear)} | stable=$emo str=${pct(str)}")
            }
        }
        // Auto-listen ticker — 300ms (ниже частота — холоднее CPU)
        viewModelScope.launch {
            while (true) {
                delay(300)
                tickAutoListen()
            }
        }
        // Dance detector: voiceRms continuous > порог 3 сек ИЛИ серия пиков → dance.
        viewModelScope.launch {
            var loudStreakStart = 0L
            var lastEnergyCheck = 0L
            while (true) {
                delay(400)
                if (continuousMic.isMuted || replyAudio.isPlaying) {
                    loudStreakStart = 0L
                    if (engine.danceActive) engine.setDance(false)
                    continue
                }
                val voice = continuousMic.currentVoiceRms
                val now = System.currentTimeMillis()
                if (voice > 700) {
                    if (loudStreakStart == 0L) loudStreakStart = now
                    if (now - loudStreakStart > 2_500) {
                        if (!engine.danceActive) {
                            engine.setDance(true)
                            io.github.jetmil.aimoodpet.debug.DebugLog.event(
                                io.github.jetmil.aimoodpet.debug.DebugCat.MOOD, "🕺 dance ON")
                        }
                    }
                } else if (voice < 250) {
                    loudStreakStart = 0L
                    if (engine.danceActive && now - lastEnergyCheck > 4_000) {
                        engine.setDance(false)
                        io.github.jetmil.aimoodpet.debug.DebugLog.event(
                            io.github.jetmil.aimoodpet.debug.DebugCat.MOOD, "💤 dance off")
                    }
                } else {
                    lastEnergyCheck = now
                }
            }
        }
        // Photo suggestion — раз в 12-22 мин (random) если давно не было snapshot
        // и лицо смотрит в камеру. Тамагочи спросит "давай сфоткаю?", юзер
        // отвечает "да/давай" → server take_photo, иначе тихо проходит.
        viewModelScope.launch {
            delay(8 * 60_000L)  // первый запуск через 8 мин
            while (true) {
                val randMin = 12L + kotlin.random.Random.nextLong(11)  // 12..22
                delay(randMin * 60_000L)
                val now = System.currentTimeMillis()
                val sinceSnapshot = now - lastSnapshotAt
                if (sinceSnapshot < 60L * 60_000L) continue   // если меньше часа — пропуск
                if (!_faceVisible.value) continue
                if (_dialog.value.phase != DialogPhase.Idle) continue
                if (replyAudio.isPlaying) continue
                if (continuousMic.isCapturing) continue
                if (dialogClient.state.value != DialogConnectionState.Connected) continue
                io.github.jetmil.aimoodpet.debug.DebugLog.event(
                    io.github.jetmil.aimoodpet.debug.DebugCat.PHOTO, "предлагаю фото")
                dialogClient.sendPhotoSuggest()
            }
        }
        // Periodic vision — раз в 5 мин шлём camera_frame + ambient_look,
        // gemma3:12b vision локально опишет сцену в стиле текущего persona.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // первый запуск через 60 сек (даём время ownerу настроиться)
            delay(60_000)
            while (true) {
                if (_settings.value.visionEnabled
                    && dialogClient.state.value == DialogConnectionState.Connected
                    && _dialog.value.phase == DialogPhase.Idle
                    && !replyAudio.isPlaying
                    && !continuousMic.isCapturing
                ) {
                    val jpeg = io.github.jetmil.aimoodpet.vision.PhotoSnapshotter.lastFrameAsJpeg()
                    if (jpeg.isNotEmpty()) {
                        dialogClient.sendCameraFrame(jpeg)
                        delay(150)  // даём серверу принять frame
                        dialogClient.sendAmbientLook()
                        Log.i(TAG, "👁 ambient_look sent (${jpeg.size}b)")
                    }
                }
                delay(AMBIENT_INTERVAL_MS)
            }
        }
        // Acoustic-feedback gate: пока тамагочи говорит — мик muted и Vosk reset
        // (иначе recognizer накапливает «робот/атама» из текста реплики).
        // После окончания + 1500мс — затухание реверберации в комнате.
        viewModelScope.launch {
            var unmuteAt = 0L
            while (true) {
                delay(120)
                val anyPlaying = replyAudio.isPlaying || (voicePlayer.current.value != null)
                if (anyPlaying) {
                    if (!continuousMic.isMuted) {
                        continuousMic.setMuted(true)
                        wakeWord.reset()
                    }
                    unmuteAt = System.currentTimeMillis() + 1500
                } else if (continuousMic.isMuted && System.currentTimeMillis() >= unmuteAt) {
                    continuousMic.setMuted(false)
                    wakeWord.reset()  // чистый старт после паузы
                }
            }
        }
        // Thinking-фаза → форма меняется на Anticipation + взгляд вверх
        viewModelScope.launch {
            _dialog.collect { state ->
                val thinking = state.phase in setOf(
                    DialogPhase.Sent,
                    DialogPhase.Transcribing,
                    DialogPhase.Replying,
                )
                engine.setThinking(thinking)
            }
        }
        // Listen to dialog events
        viewModelScope.launch {
            dialogClient.events.collect { ev ->
                when (ev.type) {
                    "stt_start" -> {
                        _dialog.value = _dialog.value.copy(phase = DialogPhase.Transcribing)
                        engine.boostEnergy(0.25f)  // не давать energy упасть в Sleeping
                        io.github.jetmil.aimoodpet.debug.DebugLog.event(
                            io.github.jetmil.aimoodpet.debug.DebugCat.WS, "stt_start")
                    }
                    "hello_ack" -> {
                        io.github.jetmil.aimoodpet.debug.DebugLog.event(
                            io.github.jetmil.aimoodpet.debug.DebugCat.WS,
                            "← hello_ack mode=${ev.payload.optString("mode")}",
                        )
                    }
                    "silent_ack" -> {
                        val reason = ev.payload.optString("reason", "")
                        Log.i(TAG, "silent_ack: $reason")
                        io.github.jetmil.aimoodpet.debug.DebugLog.event(
                            io.github.jetmil.aimoodpet.debug.DebugCat.WS, "← silent_ack ($reason)",
                        )
                        envelopeJob?.cancel()
                        _speechAmp.value = 0f
                        _dialog.value = DialogUiState(phase = DialogPhase.Idle)
                    }
                    "take_photo" -> {
                        // Сервер распознал photo-команду в whisper-text → снимок без LLM.
                        Log.i(TAG, "📸 server intent: take_photo")
                        io.github.jetmil.aimoodpet.debug.DebugLog.event(
                            io.github.jetmil.aimoodpet.debug.DebugCat.PHOTO, "← take_photo trigger",
                        )
                        _dialog.value = DialogUiState(phase = DialogPhase.Idle)
                        triggerSnapshot()
                    }
                    "transcript" -> {
                        val t = ev.payload.optString("text", "")
                        _dialog.value = _dialog.value.copy(phase = DialogPhase.Transcribing, transcript = t)
                        if (t.isNotBlank()) {
                            io.github.jetmil.aimoodpet.debug.DebugLog.event(
                                io.github.jetmil.aimoodpet.debug.DebugCat.WS, "whisper: '$t'")
                            // Если ждали ИМЯ — используем text как имя и не отправляем в LLM.
                            if (maybeUseAsFaceName(t)) {
                                _dialog.value = DialogUiState(phase = DialogPhase.Idle)
                            }
                        }
                    }
                    "reply_text" -> {
                        val t = ev.payload.optString("text", "")
                        _dialog.value = _dialog.value.copy(phase = DialogPhase.Replying, reply = t)
                        engine.boostEnergy(0.30f)  // тамагочи говорит — точно не спит
                        io.github.jetmil.aimoodpet.debug.DebugLog.event(
                            io.github.jetmil.aimoodpet.debug.DebugCat.DIALOG, "reply: '${t.take(40)}'")
                        viewModelScope.launch {
                            delay(8000)
                            if (_dialog.value.reply == t) {
                                _dialog.value = DialogUiState(phase = DialogPhase.Idle)
                            }
                        }
                    }
                    "reply_envelope" -> {
                        val frames = ev.payload.optJSONArray("frames")
                        val fps = ev.payload.optInt("fps", 20)
                        val seq = ev.payload.optInt("seq", 0)
                        val isFinal = ev.payload.optBoolean("final", true)
                        val nFrames = frames?.length() ?: 0
                        io.github.jetmil.aimoodpet.debug.DebugLog.event(
                            io.github.jetmil.aimoodpet.debug.DebugCat.TTS,
                            "← envelope seq=$seq final=$isFinal frames=$nFrames",
                        )
                        // Каждый chunk envelope воспроизводим **после** предыдущего
                        // (sentinel — короткий sleep пока playing). Простейший подход:
                        // на seq=0 cancel предыдущий job и стартуем сразу.
                        // На seq>0 — добавляем задержку = время предыдущих frames.
                        if (seq == 0) envelopeJob?.cancel()
                        if (frames != null && nFrames > 0) {
                            val frameDelay = (1000L / fps).coerceAtLeast(20L)
                            // Стартуем последовательно через короткую задержку
                            // (seq*duration примерно — но проще просто играть когда придёт).
                            envelopeJob = viewModelScope.launch {
                                for (i in 0 until nFrames) {
                                    val v = frames.optDouble(i, 0.0).toFloat()
                                    _speechAmp.value = v.coerceIn(0f, 1f)
                                    delay(frameDelay)
                                }
                                if (isFinal) _speechAmp.value = 0f
                            }
                        }
                    }
                    "reply_audio" -> {
                        val b64 = ev.payload.optString("ogg_b64", "")
                        val mime = ev.payload.optString("mime", "?")
                        val seq = ev.payload.optInt("seq", 0)
                        val isFinal = ev.payload.optBoolean("final", true)
                        if (b64.isNotEmpty()) {
                            try {
                                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                io.github.jetmil.aimoodpet.debug.DebugLog.event(
                                    io.github.jetmil.aimoodpet.debug.DebugCat.TTS,
                                    "← audio seq=$seq final=$isFinal ${bytes.size}b",
                                )
                                // На первом chunk (seq=0) отменяем предыдущую речь
                                // и сбрасываем очередь. Дальше enqueue добавляет в очередь.
                                if (seq == 0) {
                                    voicePlayer.cancel()
                                    replyAudio.cancel()
                                }
                                replyAudio.enqueue(bytes, seq = seq, isFinal = isFinal)
                            } catch (e: Throwable) {
                                Log.w(TAG, "reply_audio decode failed", e)
                                io.github.jetmil.aimoodpet.debug.DebugLog.event(
                                    io.github.jetmil.aimoodpet.debug.DebugCat.ERROR,
                                    "audio decode FAIL: ${e.message?.take(50)}",
                                )
                            }
                        } else {
                            io.github.jetmil.aimoodpet.debug.DebugLog.event(
                                io.github.jetmil.aimoodpet.debug.DebugCat.ERROR, "← reply_audio empty b64",
                            )
                        }
                    }
                    "telemetry" -> {
                        // Сервер шлёт длительности стадий: stt / llm / llm_warmup /
                        // llm_timeout / llm_vision / tts / tts_error.
                        val stage = ev.payload.optString("stage", "?")
                        val ms = ev.payload.optLong("ms", 0L)
                        val extra = ev.payload.optJSONObject("extra")
                        val cat = when (stage) {
                            "stt" -> io.github.jetmil.aimoodpet.debug.DebugCat.STT
                            "llm", "llm_warmup", "llm_vision" -> io.github.jetmil.aimoodpet.debug.DebugCat.LLM
                            "llm_timeout", "llm_vision_timeout", "llm_vision_error" -> io.github.jetmil.aimoodpet.debug.DebugCat.ERROR
                            "tts", "tts_empty" -> io.github.jetmil.aimoodpet.debug.DebugCat.TTS
                            "tts_error" -> io.github.jetmil.aimoodpet.debug.DebugCat.ERROR
                            else -> io.github.jetmil.aimoodpet.debug.DebugCat.WS
                        }
                        // Хвост — компактный summary extra-полей, чтобы видно было
                        // байт/символы/RMS — а не только длительность.
                        val tail = extra?.let { obj ->
                            val keys = obj.keys()
                            buildList {
                                while (keys.hasNext()) {
                                    val k = keys.next()
                                    val v = obj.opt(k)
                                    if (v != null) add("$k=$v")
                                }
                            }.joinToString(" ")
                        } ?: ""
                        val msg = if (tail.isBlank()) stage else "$stage  $tail"
                        io.github.jetmil.aimoodpet.debug.DebugLog.event(cat, msg, durationMs = ms)
                    }
                    "gpu_busy" -> {
                        // VRAM занят чужой моделью (Hermes/ComfyUI). Тамагочи отдельно
                        // ответит про «видяху», тут — индикатор в overlay.
                        val cur = ev.payload.optString("current_model", "?")
                        val tgt = ev.payload.optString("target_model", "?")
                        val swap = ev.payload.optBoolean("swap_started", false)
                        val tag = if (swap) "🔥 swap" else "🚧 busy"
                        io.github.jetmil.aimoodpet.debug.DebugLog.event(
                            io.github.jetmil.aimoodpet.debug.DebugCat.GPU,
                            "$tag VRAM: $cur (нужна $tgt)"
                        )
                    }
                    "error" -> {
                        val code = ev.payload.optString("code", "?")
                        val msg = ev.payload.optString("message", "")
                        Log.w(TAG, "server error: ${ev.payload}")
                        io.github.jetmil.aimoodpet.debug.DebugLog.event(
                            io.github.jetmil.aimoodpet.debug.DebugCat.ERROR,
                            if (msg.isBlank()) code else "$code: ${msg.take(60)}"
                        )
                    }
                }
            }
        }
        if (_settings.value.dialogMode != DialogMode.LocalOnly && _userConfig.value.isConfigured) {
            dialogClient.connect()
        }
        // App-level ping/pong: каждые 25 сек шлём type=ping. Если pong не
        // приходил >70 сек — force-close ws чтобы auto-reconnect его поднял.
        viewModelScope.launch {
            while (true) {
                delay(25_000)
                if (dialogClient.state.value == DialogConnectionState.Connected) {
                    dialogClient.sendPing()
                    delay(800)  // даём pong прийти, потом фиксируем RTT в overlay
                    val rtt = dialogClient.lastPongRttMs
                    if (rtt in 0..10_000) {
                        io.github.jetmil.aimoodpet.debug.DebugLog.event(
                            io.github.jetmil.aimoodpet.debug.DebugCat.NET,
                            "ping → pong",
                            durationMs = rtt,
                        )
                    }
                    val gap = dialogClient.msSinceLastPong
                    if (gap > 70_000) {
                        io.github.jetmil.aimoodpet.debug.DebugLog.event(
                            io.github.jetmil.aimoodpet.debug.DebugCat.WS,
                            "pong stale ${gap/1000}с → force close"
                        )
                        dialogClient.close()
                    }
                }
            }
        }
        // Auto-reconnect watcher: on Error/Disconnected, revive the WS with
        // exponential backoff. Without this, the first network glitch leaves
        // the socket in ws=Error forever.
        viewModelScope.launch {
            val backoff = longArrayOf(2_000, 5_000, 10_000, 30_000, 60_000)
            var attempt = 0
            while (true) {
                delay(3_000)
                if (_settings.value.dialogMode == DialogMode.LocalOnly) {
                    attempt = 0; continue
                }
                if (!_userConfig.value.isConfigured) {
                    attempt = 0; continue
                }
                val s = dialogClient.state.value
                if (s == DialogConnectionState.Connected || s == DialogConnectionState.Connecting) {
                    attempt = 0; continue
                }
                val wait = backoff[attempt.coerceAtMost(backoff.size - 1)]
                io.github.jetmil.aimoodpet.debug.DebugLog.event(
                    io.github.jetmil.aimoodpet.debug.DebugCat.WS,
                    "reconnect через ${wait/1000}с (state=$s)"
                )
                delay(wait)
                if (dialogClient.state.value !in
                    setOf(DialogConnectionState.Connected, DialogConnectionState.Connecting)) {
                    dialogClient.connect()
                    attempt++
                }
            }
        }
        Log.i(TAG, "ViewModel inited, voice phrases=${voiceBank.all.size}, dialog→$dialogUrl, mode=${_settings.value.dialogMode.key}")
    }

    fun updateSettings(new: TamaSettings) {
        val old = _settings.value
        if (old == new) return
        _settings.value = new
        settingsRepo.save(new)
        if (old.voiceStyle != new.voiceStyle) {
            voiceBank = VoiceBank(getApplication(), new.voiceStyle.key)
            voicePlayer.currentBasePath = "voice/${new.voiceStyle.key}"
            dialogClient.setStyle(new.voiceStyle.key)
            engine.mirrorAmplifier = mirrorAmpFor(new.voiceStyle)
            Log.i(TAG, "voice style → ${new.voiceStyle.key} (mirror×${engine.mirrorAmplifier})")
        }
        if (old.dialogMode != new.dialogMode) {
            dialogClient.setMode(new.dialogMode.key)
            // setMode only updates the field — the live session still has the
            // old mode in its hello frame. Force a reconnect so the next hello
            // carries the new mode.
            dialogClient.close()
            if (new.dialogMode != DialogMode.LocalOnly && _userConfig.value.isConfigured) {
                viewModelScope.launch {
                    delay(400)
                    dialogClient.connect()
                }
            }
            Log.i(TAG, "dialog mode → ${new.dialogMode.key} (reconnecting)")
        } else if (old.voiceStyle != new.voiceStyle) {
            // Style changed but mode same — reconnect so the new style reaches hello.
            dialogClient.close()
            viewModelScope.launch {
                delay(400)
                dialogClient.connect()
            }
        }
    }

    /** Apply a new user config (WS URL / token / owner name). Reconnects WS. */
    fun updateUserConfig(new: io.github.jetmil.aimoodpet.settings.UserConfig) {
        val old = _userConfig.value
        if (old == new) return
        _userConfig.value = new
        settingsRepo.saveUserConfig(new)
        if (old.wsUrl != new.wsUrl) {
            dialogClient.setUrl(new.wsUrl)
            Log.i(TAG, "ws URL changed → reconnecting")
        }
        if (old.authToken != new.authToken) {
            dialogClient.setAuthToken(new.authToken)
        }
        dialogClient.close()
        if (_settings.value.dialogMode != DialogMode.LocalOnly && new.isConfigured) {
            viewModelScope.launch {
                delay(400)
                dialogClient.connect()
            }
        }
    }

    override fun onCleared() {
        shake.stop()
        magnet.stop()
        tilt.stop()
        engine.stop()
        voiceTrigger.stop()
        dialogClient.close()
        envelopeJob?.cancel()
        replyAudio.cancel()
        continuousMic.stop()
        wakeWord.close()
        super.onCleared()
    }

    private fun forceStartUtteranceFromWake() {
        val DL = io.github.jetmil.aimoodpet.debug.DebugLog
        val DC = io.github.jetmil.aimoodpet.debug.DebugCat
        engine.boostEnergy(0.20f)  // wake-word — юзер обращается, не Sleeping
        if (!continuousMic.isAlive) {
            DL.event(DC.WAKE, "skip: mic NOT alive"); return
        }
        if (continuousMic.isCapturing) {
            DL.event(DC.WAKE, "skip: уже пишем utterance"); return
        }
        if (replyAudio.isPlaying) {
            DL.event(DC.WAKE, "skip: тамагочи говорит"); return
        }
        if (_dialog.value.phase != DialogPhase.Idle) {
            DL.event(DC.WAKE, "skip: phase=${_dialog.value.phase}"); return
        }
        if (dialogClient.state.value != DialogConnectionState.Connected) {
            DL.event(DC.WAKE, "skip: ws=${dialogClient.state.value}"); return
        }
        if (continuousMic.isMuted) {
            DL.event(DC.WAKE, "skip: mic muted (тамагочи только что говорил)"); return
        }
        DL.event(DC.WAKE, "✓ start utterance")
        listenDeadlineAt = System.currentTimeMillis() + VAD_TAIL_MS
        voiceStartedAt = System.currentTimeMillis()
        falseProbeTicks = 0
        dialogClient.startUtterance()
        continuousMic.startUtterance()
        _dialog.value = DialogUiState(phase = DialogPhase.Recording)
    }

    fun tap(normalized: Offset) {
        engine.onTap(normalized)
        val zone = engine.lastTapZone.value?.first ?: return
        // Triple-tap по носу удалён — конфликтовал с pet long-press.
        // Фото — отдельная кнопка 📷 в UI и voice-команда "сфоткай".
        val tag = when (zone) {
            io.github.jetmil.aimoodpet.eyes.TapZone.Forehead -> "petting"
            io.github.jetmil.aimoodpet.eyes.TapZone.Eye -> "fear"
            io.github.jetmil.aimoodpet.eyes.TapZone.Mouth -> "surprise"
            io.github.jetmil.aimoodpet.eyes.TapZone.Nose -> "curious"
            io.github.jetmil.aimoodpet.eyes.TapZone.EdgeMiss -> "anger"
        }
        voiceTrigger.speakNow(tag)
    }

    fun triggerSnapshot() {
        snapshotInternal()
    }

    private fun snapshotInternal() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ctx = getApplication<Application>()
            io.github.jetmil.aimoodpet.debug.DebugLog.event(
                io.github.jetmil.aimoodpet.debug.DebugCat.PHOTO, "snapshot start")
            engine.applyDelta(io.github.jetmil.aimoodpet.eyes.MoodDelta(joy = 0.50f, surprise = 0.40f, arousal = 0.60f, anticipation = 0.30f))
            voiceTrigger.speakNow("surprise")
            kotlinx.coroutines.delay(300)
            val result = io.github.jetmil.aimoodpet.vision.PhotoSnapshotter.snapshot(ctx, "память тамагочи")
            if (result != null) {
                Log.i(TAG, "📸 photo saved: ${result.uriOrPath}")
                lastSnapshotAt = System.currentTimeMillis()
                io.github.jetmil.aimoodpet.debug.DebugLog.event(
                    io.github.jetmil.aimoodpet.debug.DebugCat.PHOTO, "сохранено")
                engine.applyDelta(io.github.jetmil.aimoodpet.eyes.MoodDelta(joy = 0.40f, trust = 0.20f, arousal = 0.30f))
                voiceTrigger.speakNow("joy")
                _lastSnapshot.value = result.framed
                kotlinx.coroutines.delay(4500)
                if (_lastSnapshot.value === result.framed) _lastSnapshot.value = null
            } else {
                Log.w(TAG, "photo snapshot returned null (no frame yet?)")
                io.github.jetmil.aimoodpet.debug.DebugLog.event(
                    io.github.jetmil.aimoodpet.debug.DebugCat.ERROR, "photo: lastFrame=null")
                voiceTrigger.speakNow("sadness")
            }
            // Force-recovery: всё что могло застрять после photo flow — снимаем.
            // Без этого иногда capturing/mute оставался → mic не реагировал.
            //
            // ВАЖНО: setMuted(false) только если TTS НЕ играет. Иначе после
            // snapshot во время реплики питомца микрофон ловит его собственный
            // голос → acoustic feedback loop. В норме mute снимется автоматом
            // в основном лупе (строки ~378-388) когда anyPlaying упадёт в false.
            try {
                if (continuousMic.isCapturing) {
                    Log.w(TAG, "force recovery: stopUtterance after snapshot")
                    continuousMic.stopUtterance()
                }
                val ttsPlaying = replyAudio.isPlaying || (voicePlayer.current.value != null)
                if (!ttsPlaying) {
                    continuousMic.setMuted(false)
                    wakeWord.reset()
                    io.github.jetmil.aimoodpet.debug.DebugLog.event(
                        io.github.jetmil.aimoodpet.debug.DebugCat.MIC, "after snapshot: state cleared")
                } else {
                    io.github.jetmil.aimoodpet.debug.DebugLog.event(
                        io.github.jetmil.aimoodpet.debug.DebugCat.MIC,
                        "after snapshot: TTS играет, mute оставлен — снимется в основном лупе")
                }
                _dialog.value = DialogUiState(phase = DialogPhase.Idle)
            } catch (e: Throwable) {
                Log.w(TAG, "post-snapshot recovery", e)
            }
        }
    }

    fun dismissSnapshot() { _lastSnapshot.value = null }

    fun pet(active: Boolean, heldMs: Long) {
        if (active) {
            engine.onPettingStart()
            voiceTrigger.onPettingStart()
            // Long-press работает как ручной PTT поверх continuous mic:
            // принудительно начинаем utterance даже если RMS низкий.
            if (continuousMic.isAlive && !continuousMic.isCapturing
                && _dialog.value.phase == DialogPhase.Idle
                && !replyAudio.isPlaying) {
                Log.i(TAG, "manual long-press → utterance start")
                dialogClient.startUtterance()
                continuousMic.startUtterance()
                _dialog.value = DialogUiState(phase = DialogPhase.Recording)
                listenDeadlineAt = System.currentTimeMillis() + 30_000  // ручной режим — большой запас
            }
        } else {
            engine.onPettingStop(heldMs)
            voiceTrigger.onPettingStop(heldMs)
            if (continuousMic.isCapturing) {
                continuousMic.stopUtterance()
            }
        }
    }

    private fun tickAutoListen() {
        val now = System.currentTimeMillis()
        // Активная запись — ведём VAD по тишине (по голосовому RMS).
        if (continuousMic.isCapturing) {
            val voiceRms = continuousMic.currentVoiceRms
            // Если первые UTTERANCE_PROBE_MS прошли а голоса так и не было —
            // это был ложный старт (хвост эха тамагочи / дверь хлопнула).
            // Тихо отменяем без отправки final, чтобы whisper не получил мусор.
            val sinceStart = now - voiceStartedAt
            if (voiceStartedAt == 0L) voiceStartedAt = now  // safety
            if (sinceStart in 1..UTTERANCE_PROBE_MS && voiceRms < VAD_START_VOICE_RMS - 50) {
                if (++falseProbeTicks >= 3) {  // 3 tick подряд (~900мс) тишины с момента старта
                    Log.i(TAG, "VAD: false start (vRms=$voiceRms after ${sinceStart}ms) → cancel quietly")
                    continuousMic.stopUtterance()
                    falseProbeTicks = 0
                    voiceStartedAt = 0L
                    _dialog.value = DialogUiState(phase = DialogPhase.Idle)
                    return
                }
            } else {
                falseProbeTicks = 0
            }
            if (voiceRms > VAD_HOLD_VOICE_RMS) {
                listenDeadlineAt = now + VAD_TAIL_MS
            } else if (now > listenDeadlineAt) {
                Log.i(TAG, "VAD: silence tail → stop utterance (vRms=$voiceRms)")
                continuousMic.stopUtterance()
                falseProbeTicks = 0
                voiceStartedAt = 0L
            }
            return
        }
        // Готовим триггер старта.
        if (!_micGranted.value) return
        if (dialogClient.state.value != DialogConnectionState.Connected) return
        if (replyAudio.isPlaying) return
        if (_dialog.value.phase != DialogPhase.Idle) return
        if (now - lastUtteranceFinishedAt < POST_REPLY_COOLDOWN_MS) return
        if (!continuousMic.isAlive) return
        // Триггер: голосовой RMS (band-pass 700Hz Q=1) ПЛЮС voice/raw ratio gate.
        // Клавиатура/удары/щелчки имеют широкий спектр — voiceRms/rawRms < 0.35.
        // Голос — 0.50–0.80. Это убирает ложные старты от клавиатуры.
        val voiceRms = continuousMic.currentVoiceRms
        val rawRms = continuousMic.currentRms.coerceAtLeast(1)
        val ratio = voiceRms.toFloat() / rawRms.toFloat()
        val jaw = _mirror.value.jawOpen
        val faceBonus = _faceVisible.value && jaw > 0.15f
        val voiceHit = voiceRms > VAD_START_VOICE_RMS && ratio > VOICE_RATIO_GATE
        if (!voiceHit) {
            voiceStartedAt = 0L
            return
        }
        if (voiceStartedAt == 0L) {
            voiceStartedAt = now
            return
        }
        val holdMs = if (faceBonus) VAD_START_HOLD_FAST_MS else VAD_START_HOLD_MS
        if (now - voiceStartedAt < holdMs) return
        Log.i(TAG, "VAD: start (vRms=$voiceRms raw=$rawRms ratio=${"%.2f".format(ratio)} jaw=${"%.2f".format(jaw)} hold=${holdMs}ms)")
        voiceStartedAt = 0L
        listenDeadlineAt = now + VAD_TAIL_MS
        dialogClient.startUtterance()
        continuousMic.startUtterance()
        _dialog.value = DialogUiState(phase = DialogPhase.Recording)
    }

    fun setCameraGranted(granted: Boolean) {
        Log.i(TAG, "camera granted=$granted")
        _cameraGranted.value = granted
    }

    fun setMicGranted(granted: Boolean) {
        Log.i(TAG, "mic granted=$granted")
        _micGranted.value = granted
        if (granted && !micRunning) {
            micRunning = continuousMic.start()
            Log.i(TAG, "ContinuousMic.start → $micRunning")
            wakeWord.init()  // загружает Vosk-model в IO-потоке
        }
    }

    fun onGesture(label: String, confidence: Float) {
        if (!_settings.value.visionEnabled) return
        Log.i(TAG, "✋ gesture: '$label' conf=$confidence")
        io.github.jetmil.aimoodpet.debug.DebugLog.event(
            io.github.jetmil.aimoodpet.debug.DebugCat.VISION,
            "✋ ${label} (${(confidence * 100).toInt()}%)",
        )
        engine.applyDelta(io.github.jetmil.aimoodpet.eyes.MoodDelta(
            anticipation = 0.15f, joy = 0.10f, surprise = 0.12f, arousal = 0.20f,
        ))
        if (dialogClient.state.value == DialogConnectionState.Connected
            && _dialog.value.phase == DialogPhase.Idle
            && !replyAudio.isPlaying) {
            dialogClient.sendGesture(label, confidence)
        }
    }

    fun onNewObject(label: String, confidence: Float) {
        if (!_settings.value.visionEnabled) return
        Log.i(TAG, "👀 new object: '$label' conf=$confidence")
        engine.applyDelta(io.github.jetmil.aimoodpet.eyes.MoodDelta(
            anticipation = 0.20f, joy = 0.08f, arousal = 0.20f,
        ))
        // Если онлайн — серверный TTS-комментарий с названием объекта.
        // Иначе fallback — короткий curious-сигнал из bank.
        if (dialogClient.state.value == DialogConnectionState.Connected
            && _dialog.value.phase == DialogPhase.Idle
            && !replyAudio.isPlaying
            && !continuousMic.isCapturing
        ) {
            dialogClient.sendObjectSeen(label)
        } else {
            voiceTrigger.speakNow("curious")
        }
    }

    private val faceMemory = io.github.jetmil.aimoodpet.vision.FaceMemory(app)
    @Volatile private var lastRecognizedId: Long = -1L
    @Volatile private var lastRecognizedAt: Long = 0L

    fun onFaceFrame(frame: FaceFrame) {
        when (frame) {
            FaceFrame.NoFace -> {
                _faceVisible.value = false
                io.github.jetmil.aimoodpet.ui.lastFaceVisible = false
                _mirror.value = MirrorEmotion()
            }
            is FaceFrame.Detected -> {
                _faceVisible.value = true
                io.github.jetmil.aimoodpet.ui.lastFaceVisible = true
                _mirror.value = frame.mirror
                frame.fingerprint?.let { fp ->
                    if (frame.lookingAtCamera) {
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            recognizeFace(fp)
                        }
                    }
                }
            }
        }
        engine.onFaceFrame(frame)
    }

    private fun recognizeFace(fp: FloatArray) {
        val DL = io.github.jetmil.aimoodpet.debug.DebugLog
        val DC = io.github.jetmil.aimoodpet.debug.DebugCat
        val now = System.currentTimeMillis()
        val match = faceMemory.findBest(fp, threshold = 0.86f)
        if (match != null) {
            val (row, sim) = match
            val cooldown = if (row.id == lastRecognizedId) FACE_GREET_SAME_MS else FACE_GREET_NEW_MS
            if (now - lastRecognizedAt < cooldown) return
            lastRecognizedId = row.id
            lastRecognizedAt = now
            faceMemory.touch(row.id)
            DL.event(DC.VISION, "распознал ${row.name} (sim=${"%.2f".format(sim)} ×${row.encounters})")
            // Если имя ещё anonymous (друг_N) — спрашиваем повторно
            if (row.name.startsWith("друг_") && row.encounters > 2) {
                askForName(row.id)
            } else {
                voiceTrigger.speakNow("greeting")
            }
        } else {
            val sinceFirstSeen = now - firstUnknownAt
            if (firstUnknownAt == 0L) {
                firstUnknownAt = now
                return
            }
            if (sinceFirstSeen >= 5_000) {
                val n = faceMemory.all().size + 1
                val newId = faceMemory.add("друг_$n", fp)
                lastRecognizedId = newId
                lastRecognizedAt = now
                firstUnknownAt = 0L
                DL.event(DC.VISION, "запомнил новое лицо как друг_$n")
                askForName(newId)
            }
        }
    }
    @Volatile private var firstUnknownAt: Long = 0L

    /** Состояние «жду имя для face_id» — следующая короткая фраза станет именем. */
    @Volatile private var awaitingNameForFaceId: Long = 0L
    @Volatile private var awaitingNameUntilMs: Long = 0L

    private fun askForName(faceId: Long) {
        val now = System.currentTimeMillis()
        // Per-face rate-limit: глобальный lastNameAskAt был общий — если час назад
        // спрашивали у Васи, новому лицу спрашивать не дадим. Теперь — per face_id.
        val lastAskForThis = lastNameAskAtByFace[faceId] ?: 0L
        if (now - lastAskForThis < 60 * 60_000L) return
        lastNameAskAtByFace[faceId] = now
        awaitingNameForFaceId = faceId
        awaitingNameUntilMs = now + 30_000L
        io.github.jetmil.aimoodpet.debug.DebugLog.event(
            io.github.jetmil.aimoodpet.debug.DebugCat.VISION, "спрашиваю имя face_id=$faceId")
        // Тёплое pre-recorded greeting → пауза → серверная TTS-фраза «как зовут»
        // через type=say (НЕ через text_input — иначе LLM ОТВЕТИТ на это
        // сообщение вместо того чтобы тамагочи сам спросил).
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(800)
            voiceTrigger.speakNow("greeting")
            kotlinx.coroutines.delay(2500)
            if (dialogClient.state.value == DialogConnectionState.Connected) {
                dialogClient.sendSay("Привет, новый друг! Как тебя зовут? Скажи одним словом.")
            }
        }
    }
    private val lastNameAskAtByFace = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /** Перехват whisper-текста. Возвращает true если использовали как имя. */
    private fun maybeUseAsFaceName(text: String): Boolean {
        if (awaitingNameForFaceId == 0L) return false
        val now = System.currentTimeMillis()
        if (now > awaitingNameUntilMs) {
            awaitingNameForFaceId = 0L
            return false
        }
        // 1-3 слова, без вопроса, имеется хотя бы одна буква
        val cleaned = text.trim().trimEnd('.', '!', '?', ',')
        val words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size > 3 || words.isEmpty()) return false
        if (cleaned.endsWith("?")) return false
        if (!cleaned.any { it.isLetter() }) return false
        val name = cleaned.replaceFirstChar { it.uppercaseChar() }
        val faceId = awaitingNameForFaceId
        awaitingNameForFaceId = 0L
        awaitingNameUntilMs = 0L
        faceMemory.rename(faceId, name)
        io.github.jetmil.aimoodpet.debug.DebugLog.event(
            io.github.jetmil.aimoodpet.debug.DebugCat.VISION, "имя для face_id=$faceId → $name")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            voiceTrigger.speakNow("joy")
        }
        return true
    }

    private fun pct(v: Float): String = "%3d".format((v * 100).toInt())

    private fun mirrorAmpFor(style: io.github.jetmil.aimoodpet.settings.VoiceStyle): Float = when (style) {
        io.github.jetmil.aimoodpet.settings.VoiceStyle.Flatterer -> 1.55f   // восхищается чужой радостью сильнее
        io.github.jetmil.aimoodpet.settings.VoiceStyle.Mowgli -> 1.75f      // агрессивно отвечает на мимику
        io.github.jetmil.aimoodpet.settings.VoiceStyle.BabyRobot -> 1.30f   // дети заразительны
        io.github.jetmil.aimoodpet.settings.VoiceStyle.BassGrumpy -> 0.90f  // насмешливо холоднее
        io.github.jetmil.aimoodpet.settings.VoiceStyle.Sage -> 1.00f
        io.github.jetmil.aimoodpet.settings.VoiceStyle.Statham -> 0.65f     // лысый бэтхем не зеркалит мимику — каменное лицо
    }

    @Suppress("unused") private val pttRecorderUnused: PttRecorder? = null  // legacy, удаляется в след. сборке

    private fun computeForeheadBadge(
        thinking: Boolean,
        fsm: io.github.jetmil.aimoodpet.eyes.FsmState,
        petting: Boolean,
        emo: io.github.jetmil.aimoodpet.eyes.Plutchik8,
        strength: Float,
        smooth: io.github.jetmil.aimoodpet.eyes.MoodVector,
    ): io.github.jetmil.aimoodpet.eyes.ForeheadBadge? {
        // Приоритет: думает → спит → ласкают → яркая эмоция → null
        if (thinking) return io.github.jetmil.aimoodpet.eyes.ForeheadBadge.Thinking
        if (fsm == io.github.jetmil.aimoodpet.eyes.FsmState.Sleeping
            || fsm == io.github.jetmil.aimoodpet.eyes.FsmState.Drowsy)
            return io.github.jetmil.aimoodpet.eyes.ForeheadBadge.Sleeping
        if (petting) return io.github.jetmil.aimoodpet.eyes.ForeheadBadge.Petting
        if (smooth.surprise > 0.55f) return io.github.jetmil.aimoodpet.eyes.ForeheadBadge.Surprise
        if (smooth.fear > 0.50f) return io.github.jetmil.aimoodpet.eyes.ForeheadBadge.Fear
        if (smooth.anger > 0.45f) return io.github.jetmil.aimoodpet.eyes.ForeheadBadge.Anger
        if (smooth.disgust > 0.45f) return io.github.jetmil.aimoodpet.eyes.ForeheadBadge.Disgust
        if (smooth.sadness > 0.40f) return io.github.jetmil.aimoodpet.eyes.ForeheadBadge.Sadness
        if (smooth.joy > 0.40f) return io.github.jetmil.aimoodpet.eyes.ForeheadBadge.Joy
        if (strength < 0.20f) return null
        return when (emo) {
            io.github.jetmil.aimoodpet.eyes.Plutchik8.Joy -> io.github.jetmil.aimoodpet.eyes.ForeheadBadge.Joy
            io.github.jetmil.aimoodpet.eyes.Plutchik8.Trust -> null
            io.github.jetmil.aimoodpet.eyes.Plutchik8.Fear -> io.github.jetmil.aimoodpet.eyes.ForeheadBadge.Fear
            io.github.jetmil.aimoodpet.eyes.Plutchik8.Surprise -> io.github.jetmil.aimoodpet.eyes.ForeheadBadge.Surprise
            io.github.jetmil.aimoodpet.eyes.Plutchik8.Sadness -> io.github.jetmil.aimoodpet.eyes.ForeheadBadge.Sadness
            io.github.jetmil.aimoodpet.eyes.Plutchik8.Disgust -> io.github.jetmil.aimoodpet.eyes.ForeheadBadge.Disgust
            io.github.jetmil.aimoodpet.eyes.Plutchik8.Anger -> io.github.jetmil.aimoodpet.eyes.ForeheadBadge.Anger
            io.github.jetmil.aimoodpet.eyes.Plutchik8.Anticipation -> io.github.jetmil.aimoodpet.eyes.ForeheadBadge.Anticipation
        }
    }

    private fun computeRms(pcm: ByteArray): Int {
        if (pcm.size < 2) return 0
        var sum = 0L
        var n = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = (((pcm[i + 1].toInt() and 0xFF) shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            sum += sample.toLong() * sample.toLong()
            n++
            i += 2
        }
        if (n == 0) return 0
        return kotlin.math.sqrt(sum.toDouble() / n).toInt()
    }

    companion object {
        private const val TAG = "EyesViewModel"
        private const val TRACE = "TamaTrace"
        // VAD-параметры на голосовом RMS (band-pass 700 Hz Q=1).
        private const val VAD_START_VOICE_RMS = 220    // старт записи
        private const val VAD_HOLD_VOICE_RMS = 150     // удержание (тише чем старт)
        private const val VAD_START_HOLD_MS = 350L     // 350мс непрерывно — клавиатурные клики 50-150мс не пройдут
        private const val VAD_START_HOLD_FAST_MS = 150L // если лицо+рот рядом — быстрее
        private const val VAD_TAIL_MS = 2500L          // 2.5с тишины — ownerу нужно время подумать
        private const val UTTERANCE_PROBE_MS = 1000L
        private const val POST_REPLY_COOLDOWN_MS = 2800L
        private const val VOICE_RATIO_GATE = 0.40f
        private const val AMBIENT_INTERVAL_MS = 5L * 60L * 1000L   // 5 мин
        private const val FACE_GREET_SAME_MS = 30L * 60L * 1000L   // не приветствовать одно лицо чаще раза в 30 мин
        private const val FACE_GREET_NEW_MS = 5_000L                // между разными лицами хотя бы 5с
    }
}
