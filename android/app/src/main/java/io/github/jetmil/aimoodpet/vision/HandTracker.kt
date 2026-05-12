package io.github.jetmil.aimoodpet.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.hypot

/**
 * Распознавание жестов руки через MediaPipe Hands. 21 landmark на кадр,
 * простая classification по числу вытянутых пальцев и их комбинации.
 *
 * Зачем: gemma3 vision принимает три пальца за «зверя» / «лапу». Локальная
 * MediaPipe-проверка идёт ДО vision-LLM и при наличии руки клиент шлёт
 * сразу `gesture_seen=<label>` (минуя vision pipeline), сервер реагирует
 * специальным prompt'ом без галлюцинации про животных.
 *
 * Жесты:
 *  - fist          (0 вытянутых пальцев)
 *  - open_palm     (5)
 *  - peace         (index + middle, остальные собраны)
 *  - three_fingers (index + middle + ring)
 *  - point         (только index)
 *  - thumbs_up     (только thumb)
 *  - ok            (thumb tip близко к index tip + остальные вытянуты)
 *  - rock          (index + pinky, как «коза»)
 */
class HandTracker(
    context: Context,
    private val onGesture: (label: String, confidence: Float) -> Unit,
) {
    private val landmarker: HandLandmarker?

    init {
        landmarker = try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_PATH)
                .setDelegate(Delegate.CPU)
                .build()
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener { result, _ -> handleResult(result) }
                .setErrorListener { Log.e(TAG, "HandLandmarker error", it) }
                .build()
            HandLandmarker.createFromOptions(context, options).also {
                Log.i(TAG, "HandLandmarker ready (CPU)")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "HandLandmarker init failed", e)
            null
        }
    }

    fun detectAsync(bitmap: Bitmap, timestampMs: Long) {
        val l = landmarker ?: return
        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            l.detectAsync(mpImage, timestampMs)
        } catch (e: Throwable) {
            Log.w(TAG, "detectAsync failed", e)
        }
    }

    fun close() {
        try { landmarker?.close() } catch (e: Throwable) { Log.w(TAG, "close", e) }
    }

    // Дебаунс: один и тот же жест присылаем не чаще раза в 4 секунды,
    // чтобы тамагочи не комментировал каждый кадр.
    @Volatile private var lastGesture: String = ""
    @Volatile private var lastEmitAt: Long = 0L

    private fun handleResult(result: HandLandmarkerResult) {
        val hands = result.landmarks()
        if (hands.isEmpty()) return
        val lm = hands.first()
        if (lm.size < 21) return

        val label = classifyGesture(lm)
        if (label.isEmpty()) return

        val now = System.currentTimeMillis()
        if (label == lastGesture && now - lastEmitAt < 4000) return

        lastGesture = label
        lastEmitAt = now
        onGesture(label, 0.9f)
    }

    /** Классифицируем 21-landmark в один из жестов. */
    private fun classifyGesture(lm: List<NormalizedLandmark>): String {
        // Wrist 0; для каждого пальца: TIP далеко от wrist означает "вытянут".
        // Используем threshold по distance(tip → wrist) > distance(pip → wrist) * 1.05.
        val w = lm[0]
        val tipIds = intArrayOf(4, 8, 12, 16, 20)
        val pipIds = intArrayOf(2, 6, 10, 14, 18)
        val extended = BooleanArray(5) { i ->
            val tipD = dist(lm[tipIds[i]], w)
            val pipD = dist(lm[pipIds[i]], w)
            tipD > pipD * 1.05f
        }
        val n = extended.count { it }
        // OK-знак: thumb tip близко к index tip + middle/ring/pinky вытянуты
        val thumbTip = lm[4]
        val indexTip = lm[8]
        val thumbIndexClose = dist(thumbTip, indexTip) < dist(lm[5], w) * 0.5f
        if (thumbIndexClose && extended[2] && extended[3] && extended[4]) return "ok"
        // Rock «коза» — index + pinky, остальные собраны
        if (extended[1] && !extended[2] && !extended[3] && extended[4]) return "rock"
        return when {
            n == 0 -> "fist"
            n == 5 -> "open_palm"
            extended[1] && extended[2] && !extended[3] && !extended[4] -> "peace"
            extended[1] && extended[2] && extended[3] && !extended[4] -> "three_fingers"
            extended[1] && !extended[2] && !extended[3] && !extended[4] -> "point"
            extended[0] && !extended[1] && !extended[2] && !extended[3] && !extended[4] -> "thumbs_up"
            // Общий fallback для каунта 4 (всё кроме одного) — не отличаем какой собран
            n == 4 -> "four_fingers"
            else -> ""
        }
    }

    private fun dist(a: NormalizedLandmark, b: NormalizedLandmark): Float =
        hypot((a.x() - b.x()).toDouble(), (a.y() - b.y()).toDouble()).toFloat()

    companion object {
        private const val TAG = "HandTracker"
        private const val MODEL_PATH = "models/vision/hand_landmarker.task"
    }
}
