package io.github.jetmil.aimoodpet

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.WindowManager
import io.github.jetmil.aimoodpet.ui.EyesScreen
import io.github.jetmil.aimoodpet.ui.TamaTheme

class MainActivity : ComponentActivity() {

    private val vm: EyesViewModel by viewModels()

    private val multiPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        result[Manifest.permission.CAMERA]?.let { vm.setCameraGranted(it) }
        result[Manifest.permission.RECORD_AUDIO]?.let { vm.setMicGranted(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Foreground service — Battery Saver не убьёт mic/camera
        TamaForegroundService.start(this)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val toRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) {
            vm.setCameraGranted(true)
        } else toRequest += Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) {
            vm.setMicGranted(true)
        } else toRequest += Manifest.permission.RECORD_AUDIO
        if (toRequest.isNotEmpty()) {
            multiPermLauncher.launch(toRequest.toTypedArray())
        }

        setContent {
            TamaTheme {
                EyesScreen(vm)
            }
        }
    }
}
