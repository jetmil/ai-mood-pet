package io.github.jetmil.aimoodpet.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import androidx.compose.ui.geometry.Offset
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import io.github.jetmil.aimoodpet.eyes.FaceFrame
import io.github.jetmil.aimoodpet.eyes.MirrorEmotion
import kotlin.math.abs
import kotlin.math.atan2

class FaceTracker(
    context: Context,
    private val onFrame: (FaceFrame) -> Unit,
) {
    // Nullable: если в `assets/models/vision/face_landmarker.task` модели нет
    // (юзер не скачал по docs/ANDROID.md), `createFromOptions` бросает
    // MediaPipeException — раньше валило весь Activity. Теперь tracker
    // создаётся в «mute»-режиме: detectAsync — no-op, isReady=false,
    // вызывающий код может показать баннер «models not found».
    private val landmarker: FaceLandmarker?
    val isReady: Boolean
    val initError: String?

    init {
        var lm: FaceLandmarker? = null
        var err: String? = null
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_PATH)
                .setDelegate(Delegate.CPU)
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setOutputFaceBlendshapes(true)
                .setOutputFacialTransformationMatrixes(true)
                .setResultListener { result, _ -> handleResult(result) }
                .setErrorListener { Log.e(TAG, "FaceLandmarker error", it) }
                .build()

            lm = FaceLandmarker.createFromOptions(context, options)
            Log.i(TAG, "FaceLandmarker ready (CPU)")
        } catch (t: Throwable) {
            err = "FaceLandmarker init failed: ${t.message ?: t::class.java.simpleName}. " +
                "Положи модель в assets/$MODEL_PATH — см. docs/ANDROID.md."
            Log.e(TAG, err, t)
        }
        landmarker = lm
        isReady = lm != null
        initError = err
    }

    fun detectAsync(bitmap: Bitmap, timestampMs: Long) {
        val lm = landmarker ?: return
        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            lm.detectAsync(mpImage, timestampMs)
        } catch (t: Throwable) {
            Log.w(TAG, "detectAsync skipped: ${t.message}")
        }
    }

    fun close() {
        try { landmarker?.close() } catch (e: Throwable) { Log.w(TAG, "close", e) }
    }

    private var firstResultLogged = false
    private var blendshapeNamesLogged = false
    private var smoothMirror = MirrorEmotion()
    private val smoothAlpha = 0.40f
    private var fingerprintCounter: Int = 0

    private fun handleResult(result: FaceLandmarkerResult) {
        if (!firstResultLogged) {
            firstResultLogged = true
            Log.i(TAG, "first face result received, faces=${result.faceLandmarks().size}")
        }
        val landmarks = result.faceLandmarks()
        if (landmarks.isEmpty()) {
            onFrame(FaceFrame.NoFace)
            return
        }

        val blends = result.faceBlendshapes().orElse(null)?.firstOrNull().orEmpty()
        val map = HashMap<String, Float>(blends.size)
        for (b in blends) map[b.categoryName()] = b.score()
        if (!blendshapeNamesLogged && blends.isNotEmpty()) {
            blendshapeNamesLogged = true
            Log.i(TAG, "blendshapes count=${blends.size}")
            blends.forEachIndexed { i, b -> Log.i(TAG, "  [$i] ${b.categoryName()}") }
        }

        val matrix = result.facialTransformationMatrixes().orElse(null)?.firstOrNull()
        val (yaw, pitch, roll) = matrix?.let { eulerFromMatrix(it) } ?: Triple(0f, 0f, 0f)
        val lookingAt = isLookingAtCamera(map, yaw, pitch)

        val smileLeft = map["mouthSmileLeft"] ?: 0f
        val smileRight = map["mouthSmileRight"] ?: 0f
        val mouthLeftBs = map["mouthLeft"] ?: 0f
        val mouthRightBs = map["mouthRight"] ?: 0f
        val rawMirror = MirrorEmotion(
            smile = (smileLeft + smileRight) / 2f,
            frown = avg(map, "mouthFrownLeft", "mouthFrownRight"),
            browDown = avg(map, "browDownLeft", "browDownRight"),
            browInnerUp = map["browInnerUp"] ?: 0f,
            browOuterUp = avg(map, "browOuterUpLeft", "browOuterUpRight"),
            eyeWide = avg(map, "eyeWideLeft", "eyeWideRight"),
            eyeSquint = avg(map, "eyeSquintLeft", "eyeSquintRight"),
            jawOpen = map["jawOpen"] ?: 0f,
            noseSneer = avg(map, "noseSneerLeft", "noseSneerRight"),
            mouthPress = avg(map, "mouthPressLeft", "mouthPressRight"),
            blinkLeft = map["eyeBlinkLeft"] ?: 0f,
            blinkRight = map["eyeBlinkRight"] ?: 0f,
            mouthSkew = ((smileLeft - smileRight) + (mouthLeftBs - mouthRightBs)).coerceIn(-1f, 1f),
            // Псевдо-детект языка: MediaPipe не выдаёт tongueOut, выводим из косвенных
            // признаков — открытый рот + опущенная нижняя губа + БЕЗ улыбки/смеха.
            // Это шумно (путается с зевотой), но тренируется на ходу через mouthLowerDown.
            tongueOut = run {
                val jaw = map["jawOpen"] ?: 0f
                val lowerDown = (
                    (map["mouthLowerDownLeft"] ?: 0f) +
                    (map["mouthLowerDownRight"] ?: 0f)
                ) / 2f
                val smile = (smileLeft + smileRight) / 2f
                val funnel = map["mouthFunnel"] ?: 0f
                if (jaw > 0.20f && smile < 0.20f && funnel < 0.20f) {
                    (lowerDown * jaw * 2.4f).coerceIn(0f, 1f)
                } else 0f
            },
        )
        smoothMirror = rawMirror.ema(smoothMirror, smoothAlpha)

        // Позиция кончика носа в кадре. MediaPipe ландмарки: [0,1] x [0,1].
        // Index 1 в Face Mesh = nose tip.
        val nose = landmarks.firstOrNull()?.getOrNull(1)
        val cx = nose?.x() ?: 0.5f
        val cy = nose?.y() ?: 0.5f
        val faceCenter = Offset(
            x = (cx * 2f - 1f).coerceIn(-1f, 1f),
            y = (cy * 2f - 1f).coerceIn(-1f, 1f),
        )

        fingerprintCounter++
        val fp = if (fingerprintCounter % 10 == 0) {
            FaceFingerprint.extract(landmarks.first())
        } else null
        onFrame(
            FaceFrame.Detected(
                headYawDeg = yaw,
                headPitchDeg = pitch,
                headRollDeg = roll,
                lookingAtCamera = lookingAt,
                mirror = smoothMirror,
                faceCenter = faceCenter,
                fingerprint = fp,
            )
        )
    }

    private fun avg(map: Map<String, Float>, a: String, b: String): Float {
        val va = map[a] ?: 0f
        val vb = map[b] ?: 0f
        return (va + vb) / 2f
    }

    private fun eulerFromMatrix(m: FloatArray): Triple<Float, Float, Float> {
        val m00 = m[0]; val m10 = m[1]
        val m02 = m[8]; val m12 = m[9]; val m22 = m[10]
        val yaw = atan2(m02.toDouble(), m22.toDouble())
        val pitch = atan2(
            -m12.toDouble(),
            kotlin.math.sqrt((m02 * m02 + m22 * m22).toDouble())
        )
        val roll = atan2(m10.toDouble(), m00.toDouble())
        return Triple(
            Math.toDegrees(yaw).toFloat(),
            Math.toDegrees(pitch).toFloat(),
            Math.toDegrees(roll).toFloat(),
        )
    }

    private fun isLookingAtCamera(map: Map<String, Float>, yawDeg: Float, pitchDeg: Float): Boolean {
        val eyeLookHoriz = (map["eyeLookInLeft"] ?: 0f) + (map["eyeLookOutLeft"] ?: 0f) +
            (map["eyeLookInRight"] ?: 0f) + (map["eyeLookOutRight"] ?: 0f)
        val eyeLookVert = (map["eyeLookUpLeft"] ?: 0f) + (map["eyeLookDownLeft"] ?: 0f) +
            (map["eyeLookUpRight"] ?: 0f) + (map["eyeLookDownRight"] ?: 0f)
        val gazeAtCamera = eyeLookHoriz < 0.6f && eyeLookVert < 0.6f
        val headForward = abs(yawDeg) < 22f && abs(pitchDeg) < 22f
        return gazeAtCamera && headForward
    }

    companion object {
        private const val TAG = "FaceTracker"
        private const val MODEL_PATH = "models/vision/face_landmarker.task"
    }
}
