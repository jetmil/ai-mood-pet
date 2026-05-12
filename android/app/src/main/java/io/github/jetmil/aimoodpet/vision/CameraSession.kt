package io.github.jetmil.aimoodpet.vision

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraSession(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onFrame: (ImageProxy) -> Unit,
) {
    private var executor: ExecutorService? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var provider: ProcessCameraProvider? = null

    @Suppress("MissingPermission")
    fun start() {
        if (executor != null) return
        executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(executor!!) { proxy -> onFrame(proxy) }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis,
                )
                imageAnalysis = analysis
                Log.i(TAG, "camera bound (front 640x480)")
            } catch (e: Throwable) {
                Log.e(TAG, "camera start failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        try {
            imageAnalysis?.clearAnalyzer()
            provider?.unbindAll()
        } catch (e: Throwable) {
            Log.w(TAG, "stop", e)
        }
        executor?.shutdown()
        executor = null
        imageAnalysis = null
        provider = null
    }

    companion object {
        private const val TAG = "CameraSession"
    }
}
