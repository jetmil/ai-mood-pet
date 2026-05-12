package io.github.jetmil.aimoodpet.eyes

import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class MoodEngine(private val scope: CoroutineScope) {

    private val _mood = MutableStateFlow(MoodVector())
    val mood: StateFlow<MoodVector> = _mood.asStateFlow()

    private val _state = MutableStateFlow(FsmState.Idle)
    val state: StateFlow<FsmState> = _state.asStateFlow()

    private val _gaze = MutableStateFlow(Offset.Zero)
    val gaze: StateFlow<Offset> = _gaze.asStateFlow()

    private val _blink = MutableStateFlow(0f)
    val blink: StateFlow<Float> = _blink.asStateFlow()

    private var lastInteraction: Long = System.currentTimeMillis()
    private var faceVisible: Boolean = false
    private var tickJob: Job? = null
    private var saccadeJob: Job? = null
    private var blinkJob: Job? = null

    @Volatile var isPetting: Boolean = false
        private set

    @Volatile var thinkingActiveExternal: Boolean = false
        private set

    /** Усиление face-mirror reaction в зависимости от persona (1.0 = обычно). */
    @Volatile var mirrorAmplifier: Float = 1.0f

    @Volatile var danceActive: Boolean = false
        private set
    private var danceJob: Job? = null

    fun setDance(active: Boolean) {
        if (danceActive == active) return
        danceActive = active
        if (active) {
            danceJob?.cancel()
            danceJob = scope.launch {
                val startMs = System.currentTimeMillis()
                while (danceActive) {
                    val t = (System.currentTimeMillis() - startMs) / 1000.0
                    val freq = 1.4   // ~1.4 Hz — естественное покачивание
                    val ampX = 0.45f
                    val ampY = 0.18f
                    val gx = (kotlin.math.sin(t * freq * 2 * Math.PI) * ampX).toFloat()
                    val gy = (kotlin.math.cos(t * freq * 2 * Math.PI * 0.7) * ampY).toFloat()
                    _gaze.value = Offset(gx, gy)
                    // Мягкий приток joy/anticipation — но не overwhelm
                    pullToward(
                        target = MoodVector(joy = 0.65f, anticipation = 0.45f, arousal = 0.70f),
                        factor = 0.04f,
                        channels = setOf(Plutchik8.Joy, Plutchik8.Anticipation),
                    )
                    delay(80)
                }
            }
        } else {
            danceJob?.cancel()
            danceJob = null
        }
    }

    fun start() {
        if (tickJob != null) return
        tickJob = scope.launch {
            var prevT = System.nanoTime()
            while (true) {
                delay(200)  // 5Hz вместо 10Hz — экономия CPU
                val now = System.nanoTime()
                val dt = (now - prevT) / 1_000_000_000f
                prevT = now
                tick(dt.coerceIn(0f, 1f))
            }
        }
        saccadeJob = scope.launch {
            while (true) {
                val nextDelay = Random.nextLong(1400, 3200)
                delay(nextDelay)
                if (faceVisible) continue
                if (_state.value !in setOf(FsmState.Sleeping, FsmState.Drowsy)) {
                    val arousal = _mood.value.arousal
                    val mag = 0.12f + arousal * 0.40f
                    val angle = Random.nextFloat() * (2f * PI.toFloat())
                    _gaze.value = Offset(cos(angle) * mag, sin(angle) * mag * 0.40f)
                }
            }
        }
        blinkJob = scope.launch {
            while (true) {
                val ene = _mood.value.energy
                val baseMin = if (ene > 0.6f) 3000L else 1800L
                val baseMax = if (ene > 0.6f) 7000L else 5000L
                delay(Random.nextLong(baseMin, baseMax))
                if (_state.value != FsmState.Sleeping) {
                    blinkOnce()
                }
            }
        }
    }

    fun stop() {
        tickJob?.cancel(); tickJob = null
        saccadeJob?.cancel(); saccadeJob = null
        blinkJob?.cancel(); blinkJob = null
    }

    private suspend fun blinkOnce() {
        val closeFrames = 6
        val openFrames = 12
        for (i in 0..closeFrames) {
            val t = i / closeFrames.toFloat()
            _blink.value = t * t
            delay(14)
        }
        for (i in 0..openFrames) {
            val t = i / openFrames.toFloat()
            _blink.value = 1f - t * (2f - t)
            delay(16)
        }
        _blink.value = 0f
    }

    private fun tick(dt: Float) {
        if (thinkingActive) {
            // Пока тамагочи "думает" — не даём decay сжирать anticipation/trust
            // и не возвращаем gaze к (0,0). Этим управляет thinkingJob.
            // Остальные каналы пусть decay'ят как обычно — это нормально.
            val cur = _mood.value
            val decayed = cur.decay(dt)
            _mood.value = decayed.copy(
                anticipation = cur.anticipation,
                trust = cur.trust,
            )
            updateFsm()
            return
        }
        _mood.value = _mood.value.decay(dt)
        updateFsm()
        val g = _gaze.value
        val pull = (1f - dt * 0.6f).coerceIn(0f, 1f)
        _gaze.value = Offset(g.x * pull, g.y * pull)
    }

    /** Поднять energy на boost. Вызывается ViewModel'ом когда юзер активно
     *  общается (transcript, reply, mic) — иначе energy decay'ит до Sleeping
     *  через ~20 мин и значок 💤 застревает на лбу даже во время диалога. */
    fun boostEnergy(boost: Float = 0.30f) {
        val cur = _mood.value
        _mood.value = cur.copy(energy = (cur.energy + boost).coerceIn(0f, 1f))
        lastInteraction = System.currentTimeMillis()
    }

    private fun updateFsm() {
        val m = _mood.value
        val sinceInteractionSec = (System.currentTimeMillis() - lastInteraction) / 1000f
        // Диалог-active или face-visible → НИКОГДА не Sleeping/Drowsy. Иначе
        // 💤 значок остаётся на лбу пока тамагочи отвечает, рассинхрон.
        val activeContext = thinkingActive || faceVisible || sinceInteractionSec < 8f
        val next = when {
            m.energy < 0.15f && !activeContext -> FsmState.Sleeping
            m.energy < 0.32f && sinceInteractionSec > 60f && !faceVisible -> FsmState.Drowsy
            m.fear > 0.55f || m.surprise > 0.7f -> FsmState.Startled
            m.joy > 0.5f && m.arousal > 0.45f -> FsmState.Excited
            sinceInteractionSec < 6f && m.arousal > 0.3f -> FsmState.Focused
            sinceInteractionSec < 30f || faceVisible -> FsmState.Attentive
            else -> FsmState.Idle
        }
        _state.value = next
    }

    /**
     * Тап по экрану. Координаты normalized в (-1..1). Реакция зависит от зоны:
     * лоб — ласка, глаз — «не тыкай», рот — щекотка, нос — играю, край — недовольство.
     * Возвращаем зону через _lastTapZone чтобы UI/voice могли взять подходящий tag.
     */
    fun onTap(normalized: Offset) {
        lastInteraction = System.currentTimeMillis()
        val x = normalized.x.coerceIn(-1f, 1f)
        val y = normalized.y.coerceIn(-1f, 1f)
        _gaze.value = Offset(x, y)
        val zone = classifyZone(x, y)
        _lastTapZone.value = zone to System.currentTimeMillis()
        when (zone) {
            TapZone.Forehead -> applyDelta(MoodDelta(
                joy = 0.12f, trust = 0.18f, anticipation = 0.10f, arousal = 0.12f, energy = 0.05f,
            ))
            TapZone.Eye -> applyDelta(MoodDelta(
                fear = 0.30f, anger = 0.25f, surprise = 0.40f, arousal = 0.55f,
            ))
            TapZone.Mouth -> applyDelta(MoodDelta(
                joy = 0.30f, surprise = 0.25f, anticipation = 0.15f, arousal = 0.40f,
            ))
            TapZone.Nose -> applyDelta(MoodDelta(
                anticipation = 0.30f, joy = 0.12f, arousal = 0.25f, energy = 0.06f,
            ))
            TapZone.EdgeMiss -> applyDelta(MoodDelta(
                disgust = 0.15f, anger = 0.10f, sadness = 0.05f, arousal = 0.15f,
            ))
        }
        scope.launch {
            delay(70)
            blinkOnce()
        }
    }

    private val _lastTapZone = MutableStateFlow<Pair<TapZone, Long>?>(null)
    val lastTapZone: StateFlow<Pair<TapZone, Long>?> = _lastTapZone.asStateFlow()

    private fun classifyZone(x: Float, y: Float): TapZone {
        val absX = kotlin.math.abs(x)
        val absY = kotlin.math.abs(y)
        // Углы экрана — промах.
        if (absX > 0.78f && absY > 0.55f) return TapZone.EdgeMiss
        // Лоб — верхняя треть, относительно центра.
        if (y < -0.42f && absX < 0.65f) return TapZone.Forehead
        // Глаза — два круга на высоте y -0.10..0.18, по бокам.
        if (y in -0.20f..0.22f && absX in 0.30f..0.62f) return TapZone.Eye
        // Рот — нижняя треть, в центре.
        if (y > 0.40f && absX < 0.55f) return TapZone.Mouth
        // Нос — близко к центру.
        if (absX < 0.18f && absY < 0.20f) return TapZone.Nose
        return TapZone.EdgeMiss
    }

    private var pettingJob: Job? = null
    private var thinkingJob: Job? = null
    @Volatile private var thinkingActive: Boolean = false

    /**
     * Когда сервер обрабатывает — глаза «уходят вверх», anticipation
     * тянется к 0.95 (форма Anticipation в CozmoEyeShape), face mirror отключён.
     * Gaze свободен идти вверх с jitter — классическое «задумался».
     */
    fun setThinking(active: Boolean) {
        if (thinkingActive == active) return
        thinkingActive = active
        thinkingActiveExternal = active
        if (active) {
            // Сразу подскакиваем — не ждём pull.
            _mood.value = _mood.value.copy(
                anticipation = kotlin.math.max(_mood.value.anticipation, 0.95f),
                trust = kotlin.math.max(_mood.value.trust, 0.50f),
            )
            _gaze.value = Offset(0f, -0.55f)
            thinkingJob?.cancel()
            thinkingJob = scope.launch {
                while (true) {
                    // Удержание на 0.95 вне зависимости от decay.
                    _mood.value = _mood.value.copy(
                        anticipation = kotlin.math.max(_mood.value.anticipation, 0.95f),
                        trust = kotlin.math.max(_mood.value.trust, 0.50f),
                    )
                    val jx = (kotlin.random.Random.nextFloat() - 0.5f) * 0.5f
                    val jy = -0.55f + (kotlin.random.Random.nextFloat() - 0.5f) * 0.22f
                    _gaze.value = Offset(jx.coerceIn(-1f, 1f), jy.coerceIn(-1f, 1f))
                    delay(260)
                }
            }
        } else {
            thinkingJob?.cancel()
            thinkingJob = null
            // Мягко спускаем anticipation к фону — иначе StableDominant продолжит держать.
            _mood.value = _mood.value.copy(
                anticipation = (_mood.value.anticipation * 0.40f).coerceAtLeast(0.10f),
            )
            _gaze.value = Offset.Zero
        }
    }

    /**
     * owner «чешет пузо» — long-press на экране. Пока контакт держится,
     * каждые 200 мс tamagochi получает joy+trust bump, через гистерезис
     * StableDominant встаёт на Joy → форма Cozmo Joy (полумесяц улыбки) =
     * глаза прикрываются.
     */
    fun onPettingStart() {
        lastInteraction = System.currentTimeMillis()
        pettingJob?.cancel()
        pettingJob = scope.launch {
            // pull joy/trust к высокому target пока чешут — без аккумуляции
            val target = MoodVector(joy = 0.85f, trust = 0.70f, arousal = 0.55f, energy = 0.85f)
            while (true) {
                lastInteraction = System.currentTimeMillis()
                pullToward(
                    target = target,
                    factor = 0.10f,
                    channels = setOf(Plutchik8.Joy, Plutchik8.Trust),
                )
                delay(80)
            }
        }
    }

    fun onPettingStop(heldMs: Long) {
        pettingJob?.cancel()
        pettingJob = null
    }

    fun onShake(magnitude: Float) {
        lastInteraction = System.currentTimeMillis()
        applyDelta(MoodDelta(
            fear = (magnitude * 0.30f).coerceAtMost(0.7f),
            surprise = (magnitude * 0.45f).coerceAtMost(0.85f),
            arousal = (magnitude * 0.50f).coerceAtMost(1f),
        ))
    }

    fun onTilt(state: TiltState, magnitude: Float) {
        lastInteraction = System.currentTimeMillis()
        when (state) {
            TiltState.Flipped -> applyDelta(MoodDelta(
                fear = 0.65f * magnitude, surprise = 0.55f * magnitude,
                anger = 0.40f * magnitude, arousal = 0.95f * magnitude,
            ))
            TiltState.UpsideDown -> applyDelta(MoodDelta(
                fear = 0.40f * magnitude, surprise = 0.50f * magnitude,
                arousal = 0.65f * magnitude,
            ))
            TiltState.TiltedForward -> applyDelta(MoodDelta(
                anticipation = 0.30f * magnitude, surprise = 0.20f * magnitude,
                arousal = 0.30f * magnitude,
            ))
            TiltState.TiltedBack -> applyDelta(MoodDelta(
                trust = 0.20f * magnitude, joy = 0.10f * magnitude,
                anticipation = 0.15f * magnitude,
            ))
            TiltState.FaceUp -> applyDelta(MoodDelta(
                sadness = 0.30f * magnitude, anger = 0.15f * magnitude,
                arousal = -0.10f * magnitude, energy = -0.10f * magnitude,
            ))
            TiltState.Upright -> Unit
        }
    }

    fun onMagnetSpike(intensity: Float) {
        lastInteraction = System.currentTimeMillis()
        applyDelta(MoodDelta(
            fear = (intensity * 0.50f).coerceAtMost(0.85f),
            surprise = (intensity * 0.55f).coerceAtMost(0.95f),
            anger = (intensity * 0.30f).coerceAtMost(0.6f),
            disgust = (intensity * 0.25f).coerceAtMost(0.6f),
            arousal = (intensity * 0.7f).coerceAtMost(1f),
        ))
    }

    fun onFaceDetected(
        visible: Boolean,
        lookingAtMe: Boolean = false,
        headYawDeg: Float = 0f,
        headPitchDeg: Float = 0f,
    ) {
        faceVisible = visible
        if (visible) {
            lastInteraction = System.currentTimeMillis()
            applyDelta(MoodDelta(
                anticipation = 0.04f,
                trust = if (lookingAtMe) 0.05f else 0f,
                joy = if (lookingAtMe) 0.03f else 0f,
                arousal = 0.08f,
            ))
            _gaze.value = Offset(
                (headYawDeg / 25f).coerceIn(-1f, 1f),
                (headPitchDeg / 25f).coerceIn(-1f, 1f),
            )
        }
    }

    fun onFaceFrame(frame: FaceFrame) {
        // Во время "думания" не позволяем face mirror перебивать pull в anticipation
        // и взгляд вверх — иначе глаза прыгают за лицом и форма не успевает сменить.
        if (thinkingActive) return
        when (frame) {
            FaceFrame.NoFace -> onFaceDetected(visible = false)
            is FaceFrame.Detected -> {
                faceVisible = true
                lastInteraction = System.currentTimeMillis()
                _gaze.value = Offset(
                    x = (-frame.faceCenter.x).coerceIn(-1f, 1f),
                    y = frame.faceCenter.y.coerceIn(-1f, 1f),
                )
                // PULL-to-target вместо delta. Frame-rate-independent.
                // mirrorAmplifier ставится ViewModel'ой по выбранному style:
                // flatterer ×1.5, mowgli ×1.7, остальные ×1.0.
                val attractor = frame.mirror.toAttractor()
                pullToward(
                    target = attractor,
                    factor = (0.18f * mirrorAmplifier).coerceAtMost(0.45f),
                    channels = setOf(
                        Plutchik8.Joy,
                        Plutchik8.Sadness,
                        Plutchik8.Anger,
                        Plutchik8.Surprise,
                        Plutchik8.Fear,
                        Plutchik8.Disgust,
                    ),
                )
                if (frame.lookingAtCamera) {
                    pullToward(
                        target = MoodVector(trust = 0.45f, anticipation = 0.30f),
                        factor = 0.03f,
                        channels = setOf(Plutchik8.Trust, Plutchik8.Anticipation),
                    )
                }
                // Язык — игривый сигнал, тянет joy + anticipation
                if (frame.mirror.tongueOut > 0.30f) {
                    pullToward(
                        target = MoodVector(joy = 0.70f, anticipation = 0.55f, trust = 0.50f),
                        factor = 0.10f,
                        channels = setOf(Plutchik8.Joy, Plutchik8.Anticipation, Plutchik8.Trust),
                    )
                }
            }
        }
    }

    fun applyDelta(d: MoodDelta) {
        _mood.value = _mood.value.applyDelta(d)
    }

    /**
     * Pull-to-target: подтягивает указанные каналы [channels] mood-вектора
     * к target-значениям с долей factor. factor ~0.04 на кадр @ 30fps даёт
     * полную сходимость за ~1 секунду без аккумулирующегося переполнения.
     */
    fun pullToward(target: MoodVector, factor: Float, channels: Set<Plutchik8>) {
        val cur = _mood.value
        val a = factor.coerceIn(0f, 1f)
        _mood.value = cur.copy(
            joy = if (Plutchik8.Joy in channels) cur.joy + (target.joy - cur.joy) * a else cur.joy,
            trust = if (Plutchik8.Trust in channels) cur.trust + (target.trust - cur.trust) * a else cur.trust,
            fear = if (Plutchik8.Fear in channels) cur.fear + (target.fear - cur.fear) * a else cur.fear,
            surprise = if (Plutchik8.Surprise in channels) cur.surprise + (target.surprise - cur.surprise) * a else cur.surprise,
            sadness = if (Plutchik8.Sadness in channels) cur.sadness + (target.sadness - cur.sadness) * a else cur.sadness,
            disgust = if (Plutchik8.Disgust in channels) cur.disgust + (target.disgust - cur.disgust) * a else cur.disgust,
            anger = if (Plutchik8.Anger in channels) cur.anger + (target.anger - cur.anger) * a else cur.anger,
            anticipation = if (Plutchik8.Anticipation in channels) cur.anticipation + (target.anticipation - cur.anticipation) * a else cur.anticipation,
        )
    }
}
