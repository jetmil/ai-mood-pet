package io.github.jetmil.aimoodpet.vision

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Хранит последний кадр с фронт-камеры (volatile — обновляется FaceTracker pipeline)
 * и умеет «снять» из него Polaroid-фотку: тёплая рамка + дата + текст-метка,
 * сохранение в Pictures/Tamagochi/.
 *
 * Только декоратор — основной capture идёт через тот же ImageAnalysis,
 * мы не запускаем отдельный CameraX use case (экономия батареи и совместимости).
 */
object PhotoSnapshotter {

    @Volatile var lastFrame: Bitmap? = null
        private set

    fun updateLatest(bm: Bitmap) {
        lastFrame = bm
    }

    /**
     * Сжимает последний кадр в JPEG 320x240 q70 — для отправки в LLM.
     * Минимальный токен-cost при сохранении читаемости.
     */
    fun lastFrameAsJpeg(): ByteArray {
        val src = lastFrame ?: return ByteArray(0)
        return try {
            val scaled = Bitmap.createScaledBitmap(src, 320, 240, true)
            val baos = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos)
            baos.toByteArray()
        } catch (e: Throwable) {
            Log.w(TAG, "lastFrameAsJpeg failed", e)
            ByteArray(0)
        }
    }

    /**
     * Захватить и сохранить. Возвращает абсолютный путь файла или null.
     */
    fun snapshot(context: Context, label: String? = null): SnapshotResult? {
        val src = lastFrame
        if (src == null) {
            Log.w(TAG, "snapshot: no last frame")
            return null
        }
        // Defensive copy — на случай если кадр recycled между вызовами.
        val safe = try {
            if (src.isRecycled) {
                Log.w(TAG, "lastFrame is recycled")
                return null
            }
            src.copy(Bitmap.Config.ARGB_8888, false) ?: run {
                Log.w(TAG, "src.copy returned null")
                return null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "src.copy failed", e)
            return null
        }
        val framed = try {
            decorate(safe, label)
        } catch (e: Throwable) {
            Log.e(TAG, "decorate failed", e)
            return null
        }
        val uri = saveToGallery(context, framed) ?: return null
        return SnapshotResult(uriOrPath = uri, framed = framed)
    }

    data class SnapshotResult(val uriOrPath: String, val framed: Bitmap)

    private fun decorate(src: Bitmap, label: String?): Bitmap {
        val padTop = src.width * 0.06f
        val padSide = src.width * 0.06f
        val padBot = src.width * 0.32f
        val out = Bitmap.createBitmap(
            (src.width + padSide * 2).toInt(),
            (src.height + padTop + padBot).toInt(),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(out)
        // Тёплый кремовый фон рамки
        canvas.drawColor(Color.rgb(248, 240, 220))
        // Лёгкая тень под фото
        val shadowPaint = Paint().apply {
            color = Color.argb(56, 0, 0, 0)
            isAntiAlias = true
        }
        canvas.drawRect(
            padSide + 4f, padTop + 4f,
            padSide + src.width + 4f, padTop + src.height + 4f,
            shadowPaint,
        )
        // Само фото
        val photoRect = Rect(
            padSide.toInt(), padTop.toInt(),
            (padSide + src.width).toInt(), (padTop + src.height).toInt(),
        )
        canvas.drawBitmap(src, null, photoRect, null)
        // Подпись внизу
        val captionPaint = Paint().apply {
            color = Color.rgb(80, 60, 40)
            isAntiAlias = true
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            textSize = src.width * 0.045f
            textAlign = Paint.Align.CENTER
        }
        val datePaint = Paint(captionPaint).apply {
            textSize = src.width * 0.035f
            color = Color.rgb(120, 90, 60)
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        val date = SimpleDateFormat("dd MMMM yyyy", Locale("ru")).format(Date())
        val captionText = label ?: "тамагочи помнит этот день"
        val cy = padTop + src.height + padBot * 0.40f
        canvas.drawText(captionText, out.width / 2f, cy, captionPaint)
        canvas.drawText(date, out.width / 2f, cy + src.width * 0.075f, datePaint)
        return out
    }

    private fun saveToGallery(context: Context, bitmap: Bitmap): String? {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fname = "tamagochi_$ts.jpg"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fname)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Tamagochi")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    ?: return null
                resolver.openOutputStream(uri)?.use { os ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, os)
                }
                cv.clear()
                cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, cv, null, null)
                Log.i(TAG, "saved photo: $uri")
                uri.toString()
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "Tamagochi",
                )
                if (!dir.isDirectory) dir.mkdirs()
                val out = File(dir, fname)
                FileOutputStream(out).use { os ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, os)
                }
                Log.i(TAG, "saved photo: ${out.absolutePath}")
                out.absolutePath
            }
        } catch (e: Throwable) {
            Log.e(TAG, "save failed", e)
            null
        }
    }

    private const val TAG = "PhotoSnapshotter"
}
