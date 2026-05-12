package io.github.jetmil.aimoodpet.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult

/**
 * Object detection через EfficientDet-Lite0 (INT8) на тех же CameraX-кадрах
 * что и FaceLandmarker. Запускается раз в N мс (DETECT_INTERVAL_MS), чтобы
 * не нагревать телефон. При появлении нового класса в кадре — onNewObject(label).
 *
 * Локализация COCO 80 классов на русский — словарь LABEL_RU.
 */
class ObjectTracker(
    context: Context,
    private val onNewObject: (label: String, confidence: Float) -> Unit,
) {
    private val detector: ObjectDetector? = try {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("efficientdet_lite0.tflite")
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(5)
            .setScoreThreshold(0.45f)
            .build()
        ObjectDetector.createFromOptions(context, options)
    } catch (e: Throwable) {
        Log.e(TAG, "ObjectDetector init failed", e)
        null
    }

    @Volatile private var lastDetectAt: Long = 0L
    private val recentLabels = ArrayDeque<Pair<String, Long>>()

    fun maybeDetect(bitmap: Bitmap) {
        val ready = detector ?: return
        val now = System.currentTimeMillis()
        if (now - lastDetectAt < DETECT_INTERVAL_MS) return
        lastDetectAt = now
        try {
            val result: ObjectDetectorResult = ready.detect(BitmapImageBuilder(bitmap).build())
            val detections = result.detections() ?: return
            for (det in detections) {
                val cats = det.categories() ?: continue
                val cat = cats.firstOrNull() ?: continue
                val raw = cat.categoryName().lowercase()
                if (raw == "person") continue   // лицо у нас уже от FaceLandmarker
                val ru = LABEL_RU[raw] ?: continue
                if (isRecent(ru, now)) continue
                recentLabels.addLast(ru to now)
                while (recentLabels.size > 12) recentLabels.removeFirst()
                Log.i(TAG, "new object: $ru (${cat.score()})")
                onNewObject(ru, cat.score())
                break  // одна реакция за тик
            }
        } catch (e: Throwable) {
            Log.w(TAG, "detect failed", e)
        }
    }

    private fun isRecent(label: String, now: Long): Boolean =
        recentLabels.any { it.first == label && now - it.second < SAME_LABEL_COOLDOWN_MS }

    fun close() {
        try { detector?.close() } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "ObjectTracker"
        private const val DETECT_INTERVAL_MS = 2200L
        private const val SAME_LABEL_COOLDOWN_MS = 90_000L

        private val LABEL_RU = mapOf(
            "bicycle" to "велосипед", "car" to "машина", "motorcycle" to "мотоцикл",
            "airplane" to "самолёт", "bus" to "автобус", "train" to "поезд",
            "truck" to "грузовик", "boat" to "лодка",
            "traffic light" to "светофор", "fire hydrant" to "гидрант",
            "stop sign" to "знак стоп", "parking meter" to "паркомат", "bench" to "скамейка",
            "bird" to "птица", "cat" to "кот", "dog" to "собака", "horse" to "лошадь",
            "sheep" to "овца", "cow" to "корова", "elephant" to "слон", "bear" to "медведь",
            "zebra" to "зебра", "giraffe" to "жираф",
            "backpack" to "рюкзак", "umbrella" to "зонт", "handbag" to "сумка",
            "tie" to "галстук", "suitcase" to "чемодан",
            "frisbee" to "фрисби", "skis" to "лыжи", "snowboard" to "сноуборд",
            "sports ball" to "мяч", "kite" to "воздушный змей", "baseball bat" to "бита",
            "baseball glove" to "перчатка", "skateboard" to "скейт", "surfboard" to "доска",
            "tennis racket" to "ракетка",
            "bottle" to "бутылка", "wine glass" to "бокал", "cup" to "кружка",
            "fork" to "вилка", "knife" to "нож", "spoon" to "ложка", "bowl" to "миска",
            "banana" to "банан", "apple" to "яблоко", "sandwich" to "бутерброд",
            "orange" to "апельсин", "broccoli" to "брокколи", "carrot" to "морковь",
            "hot dog" to "хот-дог", "pizza" to "пицца", "donut" to "пончик", "cake" to "торт",
            "chair" to "стул", "couch" to "диван", "potted plant" to "цветок",
            "bed" to "кровать", "dining table" to "стол", "toilet" to "унитаз",
            "tv" to "телевизор", "laptop" to "ноутбук", "mouse" to "мышь",
            "remote" to "пульт", "keyboard" to "клавиатура", "cell phone" to "телефон",
            "microwave" to "микроволновка", "oven" to "духовка", "toaster" to "тостер",
            "sink" to "раковина", "refrigerator" to "холодильник",
            "book" to "книга", "clock" to "часы", "vase" to "ваза", "scissors" to "ножницы",
            "teddy bear" to "плюшевый мишка", "hair drier" to "фен", "toothbrush" to "зубная щётка",
        )
    }
}
