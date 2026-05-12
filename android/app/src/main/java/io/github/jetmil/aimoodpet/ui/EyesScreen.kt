package io.github.jetmil.aimoodpet.ui

import androidx.camera.core.ImageProxy
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jetmil.aimoodpet.EyesViewModel
import io.github.jetmil.aimoodpet.dialog.DialogPhase
import io.github.jetmil.aimoodpet.vision.CameraSession
import io.github.jetmil.aimoodpet.vision.FaceTracker
import io.github.jetmil.aimoodpet.vision.HandTracker
import io.github.jetmil.aimoodpet.vision.ObjectTracker
import io.github.jetmil.aimoodpet.vision.PhotoSnapshotter
import io.github.jetmil.aimoodpet.vision.rotated

@Composable
fun EyesScreen(vm: EyesViewModel = viewModel()) {
    val params by vm.render.collectAsState()
    val cameraGranted by vm.cameraGranted.collectAsState()
    val faceVisible by vm.faceVisible.collectAsState()
    val mood by vm.smoothMood.collectAsState()
    val state by vm.state.collectAsState()
    val mirror by vm.mirror.collectAsState()
    val stableEmotion by vm.stableEmotion.collectAsState()
    val stableStrength by vm.stableStrength.collectAsState()
    val currentSpeak by vm.currentSpeak.collectAsState()
    val dialog by vm.dialog.collectAsState()
    val connection by vm.connection.collectAsState()
    val settings by vm.settings.collectAsState()
    val userConfig by vm.userConfig.collectAsState()
    val speechAmp by vm.speechAmp.collectAsState()
    val thinking by vm.thinking.collectAsState()
    val forehead by vm.forehead.collectAsState()
    val lastSnapshot by vm.lastSnapshot.collectAsState()
    val debugLog by io.github.jetmil.aimoodpet.debug.DebugLog.flow.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    // First launch / Setup screen: if the user has not entered a WS URL yet,
    // pop the settings sheet so they can configure the pet before anything
    // tries to connect.
    LaunchedEffect(userConfig.isConfigured) {
        if (!userConfig.isConfigured) showSettings = true
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    if (cameraGranted) {
        DisposableEffect(lifecycleOwner) {
            val appCtx = context.applicationContext
            val tracker = FaceTracker(appCtx) { frame -> vm.onFaceFrame(frame) }
            val objectTracker = ObjectTracker(appCtx) { label, conf -> vm.onNewObject(label, conf) }
            val handTracker = HandTracker(appCtx) { label, conf -> vm.onGesture(label, conf) }
            val session = CameraSession(appCtx, lifecycleOwner) { proxy ->
                feedTracker(proxy, tracker, objectTracker, handTracker)
            }
            session.start()
            onDispose {
                session.stop()
                tracker.close()
                objectTracker.close()
                handTracker.close()
            }
        }
    }

    val subtitleText = when {
        dialog.phase == DialogPhase.Replying && dialog.reply.isNotEmpty() -> dialog.reply
        dialog.phase == DialogPhase.Transcribing && dialog.transcript.isNotEmpty() -> "« ${dialog.transcript} »"
        dialog.phase == DialogPhase.Transcribing -> "слушаю..."
        dialog.phase == DialogPhase.Recording -> "запись..."
        dialog.phase == DialogPhase.Sent -> "думаю..."
        currentSpeak != null -> currentSpeak?.text
        else -> null
    }

    if (showSettings) {
        SettingsSheet(
            current = settings,
            userConfig = userConfig,
            onChange = { vm.updateSettings(it) },
            onUserConfigChange = { vm.updateUserConfig(it) },
            onDismiss = { showSettings = false },
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {
            EyesCanvas(
                params = params,
                stableEmotion = stableEmotion,
                stableStrength = stableStrength,
                jawOpen = mirror.jawOpen,
                speechAmp = speechAmp,
                thinking = thinking,
                forehead = forehead,
                onTap = vm::tap,
                petListener = vm::pet,
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Subtitle(text = subtitleText)
                StatusBar(
                    state = state,
                    mood = mood,
                    mirror = mirror,
                    faceVisible = faceVisible,
                    stableEmotion = stableEmotion,
                    stableStrength = stableStrength,
                )
            }
            // Кнопки управления — Photo и Settings в правом верхнем углу.
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 36.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CameraIconButton(onClick = { vm.triggerSnapshot() })
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color(0xFF21A038).copy(alpha = 0.18f), CircleShape)
                        .clickable { showSettings = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "⚙",
                        color = Color(0xFF21A038),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            // Snapshot overlay — на 4.5 сек поверх всего
            SnapshotOverlay(
                bitmap = lastSnapshot,
                onDismiss = { vm.dismissSnapshot() },
            )
            // Debug log overlay — toggleable из Settings, по умолчанию off
            if (settings.debugLogVisible) {
                DebugLogOverlay(
                    items = debugLog,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 140.dp),
                )
            }
        }
    }
}

/** Контурный зелёный значок камеры в стиле шестерёнки ⚙. */
@Composable
private fun CameraIconButton(onClick: () -> Unit) {
    val green = Color(0xFF21A038)
    Box(
        modifier = Modifier
            .size(56.dp)
            .background(green.copy(alpha = 0.18f), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(28.dp)) {
            val s = size.minDimension
            val stroke = s * 0.10f
            // корпус камеры (закруглённый прямоугольник)
            val left = s * 0.06f
            val top = s * 0.22f
            val right = s * 0.94f
            val bottom = s * 0.84f
            val cr = s * 0.10f
            drawRoundRect(
                color = green,
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cr, cr),
                style = Stroke(width = stroke),
            )
            // выступ-видоискатель сверху (трапеция через линии)
            val vfLeft = s * 0.32f
            val vfRight = s * 0.62f
            val vfTop = s * 0.10f
            drawLine(green, Offset(vfLeft, top), Offset(vfLeft + s * 0.04f, vfTop), strokeWidth = stroke)
            drawLine(green, Offset(vfRight, top), Offset(vfRight - s * 0.04f, vfTop), strokeWidth = stroke)
            drawLine(green, Offset(vfLeft + s * 0.04f, vfTop), Offset(vfRight - s * 0.04f, vfTop), strokeWidth = stroke)
            // объектив
            drawCircle(
                color = green,
                center = Offset(s * 0.50f, (top + bottom) / 2f),
                radius = s * 0.18f,
                style = Stroke(width = stroke),
            )
            // маленький индикатор справа
            drawCircle(
                color = green,
                center = Offset(s * 0.82f, top + s * 0.10f),
                radius = s * 0.025f,
            )
        }
    }
}

// Skip каждый 2-й кадр от 30fps камеры → MediaPipe + рендер ≈15fps.
// CPU освобождается ~50%, телефон холодный.
private var feedFrameCounter: Int = 0
// Hand detection rate-limit — обновляется из FaceTracker callback в VM.
@Volatile internal var lastFaceVisible: Boolean = false
@Volatile private var lastHandDetectMs: Long = 0L

private fun feedTracker(
    proxy: ImageProxy,
    tracker: FaceTracker,
    objectTracker: ObjectTracker,
    handTracker: HandTracker,
) {
    try {
        feedFrameCounter++
        if (feedFrameCounter % 2 != 0) return
        val rotation = proxy.imageInfo.rotationDegrees
        val bitmap = proxy.toBitmap().rotated(rotation)
        // Каждый 6-й кадр (~3 fps) — копия в SOFTWARE-формат для PhotoSnapshotter.
        // bitmap.config может быть null (HARDWARE на API 31+), поэтому насильно ARGB_8888.
        if (feedFrameCounter % 6 == 0) {
            try {
                val safeCopy = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                if (safeCopy != null) PhotoSnapshotter.updateLatest(safeCopy)
            } catch (e: Throwable) {
                android.util.Log.w("EyesScreen", "lastFrame copy failed", e)
            }
            objectTracker.maybeDetect(bitmap)
        }
        val tsMs = proxy.imageInfo.timestamp / 1_000_000
        tracker.detectAsync(bitmap, tsMs)
        // Hand detection — самый тяжёлый MediaPipe (21 landmark, 8MB model).
        // На Redmi Note 14 одновременная работа FaceLandmarker + ObjectDetector +
        // HandLandmarker роняет fps. Поэтому жесты — только когда лицо В КАДРЕ
        // (юзер близко, имеет смысл смотреть на руки) и не чаще раза в 1500мс.
        val nowMs = System.currentTimeMillis()
        if (lastFaceVisible && nowMs - lastHandDetectMs >= 1500L) {
            lastHandDetectMs = nowMs
            handTracker.detectAsync(bitmap, tsMs)
        }
    } catch (e: Throwable) {
        android.util.Log.w("EyesScreen", "feedTracker", e)
    } finally {
        proxy.close()
    }
}
