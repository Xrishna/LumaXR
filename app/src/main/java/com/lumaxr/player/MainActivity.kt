package com.lumaxr.player.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.lumaxr.player.LumaXrApplication
import com.lumaxr.player.pro.PipController
import com.lumaxr.player.video.DesktopInputHandler

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var pipController: PipController? = null
    private val externalVideoUri = mutableStateOf<String?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.any { it }) {
            viewModel.refreshMediaLibraries()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        val app = application as LumaXrApplication
        app.container.videoCoordinator.bindActivity(this)

        pipController = PipController(
            activity = this,
            refreshRateManager = app.container.refreshRateManager,
            onPlayPause = {
                val cur = app.container.videoCoordinator.isPaused()
                app.container.videoCoordinator.setPaused(!cur)
            },
            onSeekDeltaSec = { delta ->
                app.container.videoCoordinator.seekRelative(delta)
            },
        ).also { it.register() }

        requestRuntimeMediaPermissions()

        externalVideoUri.value = intent?.dataString
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF38BDF8),
                    secondary = Color(0xFFA78BFA),
                    background = Color.Black,
                    surface = Color(0xFF0E131F),
                ),
            ) {
                LumaNavigationRoot(
                    viewModel = viewModel,
                    externalVideoUri = externalVideoUri.value,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uri = intent.dataString
        if (!uri.isNullOrBlank()) {
            externalVideoUri.value = uri
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipController?.onPipModeChanged(isInPictureInPictureMode)
    }

    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>?,
        menu: Menu?,
        deviceId: Int,
    ) {
        data?.addAll(DesktopInputHandler.buildSystemKeyboardShortcuts())
    }

    override fun onDestroy() {
        (application as LumaXrApplication).container.videoCoordinator.unbindActivity(this)
        pipController?.unregister()
        super.onDestroy()
    }

    private fun requestRuntimeMediaPermissions() {
        val needed = buildList {
            val hasFullVideo = ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.READ_MEDIA_VIDEO,
            ) == PackageManager.PERMISSION_GRANTED
            val hasPartialVisual = ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasFullVideo && !hasPartialVisual) {
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.READ_MEDIA_AUDIO,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
