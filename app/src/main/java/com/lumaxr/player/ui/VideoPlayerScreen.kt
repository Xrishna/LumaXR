package com.lumaxr.player.ui

import android.app.Activity
import android.view.SurfaceView
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.lumaxr.player.video.AspectMode
import com.lumaxr.player.video.DesktopInputHandler.lumaDesktopShortcuts
import com.lumaxr.player.video.DesktopInputHandler.lumaMouseAndTrackpadCanvas
import com.lumaxr.player.video.DesktopShortcutCallbacks
import com.lumaxr.player.video.EngineSwitchPreference
import com.lumaxr.player.video.HdrToysToneMapper
import com.lumaxr.player.video.SubtitlePlacement
import com.lumaxr.player.video.XrPreset
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    uri: String,
    title: String,
    viewModel: MainViewModel,
    sharedScope: SharedTransitionScope,
    sharedElementsVisible: Boolean,
    onExit: () -> Unit,
    onHandoffToMusicNowPlaying: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val coordinator = viewModel.container.videoCoordinator

    val xrPreset by viewModel.xrPreset.collectAsState()
    val refreshPolicy by viewModel.refreshPolicy.collectAsState()
    val enginePref by viewModel.enginePreference.collectAsState()
    val hdrToysMapper by viewModel.hdrToysMapper.collectAsState()
    val experimentalHbm by viewModel.experimentalHbm.collectAsState()
    val subtitlePeakNits by viewModel.subtitlePeakNits.collectAsState()
    val showTelemetryPill by viewModel.showTelemetryPill.collectAsState()

    val hudTelemetry by coordinator.hudTelemetry.collectAsState()
    val mpvTelemetry by coordinator.mpvEngine.telemetry.collectAsState()

    var showHud by remember { mutableStateOf(true) }
    var showMasteringSheet by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }
    var splitWipe by remember { mutableFloatStateOf(1.0f) }
    var aspectMode by remember { mutableStateOf(AspectMode.ORIGINAL) }
    var subPlacement by remember { mutableStateOf(SubtitlePlacement.ABOVE_LETTERBOX) }
    var abLoopA by remember { mutableStateOf<Double?>(null) }
    var surfaceViewRef by remember { mutableStateOf<SurfaceView?>(null) }

    // True HyperOS 3 edge-to-edge immersive mode
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    LaunchedEffect(surfaceViewRef, uri, xrPreset, enginePref, hdrToysMapper, experimentalHbm, refreshPolicy) {
        val sv = surfaceViewRef ?: return@LaunchedEffect
        coordinator.startPlayback(
            surfaceView = sv,
            uri = uri,
            preset = xrPreset,
            refreshPolicy = refreshPolicy,
            enginePreference = enginePref,
            experimentalHbm = experimentalHbm,
            hdrToysMapper = hdrToysMapper,
            subtitlePeakNits = subtitlePeakNits,
        )
    }

    with(sharedScope) {
        val sharedState = rememberSharedContentState(key = "video_$uri")
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .sharedElementWithCallerManagedVisibility(
                    sharedContentState = sharedState,
                    visible = sharedElementsVisible,
                    clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(0.dp)),
                )
                .lumaDesktopShortcuts(
                    DesktopShortcutCallbacks(
                        onTogglePlayPause = { coordinator.setPaused(!mpvTelemetry.paused) },
                        onSeekRelativeSec = { delta -> coordinator.seekRelative(delta) },
                        onToggleTelemetryPill = { viewModel.toggleTelemetryPill() },
                        onToggleDeband = { Toast.makeText(context, "Deband toggled", Toast.LENGTH_SHORT).show() },
                        onCycleToneMap = {
                            val values = HdrToysToneMapper.entries
                            val next = values[(hdrToysMapper.ordinal + 1) % values.size]
                            viewModel.updateHdrToysMapper(next)
                        },
                        onTakeSnapshot = {
                            val out = File(context.cacheDir, "snap_${System.currentTimeMillis()}.png")
                            coordinator.mpvEngine.takeScreenshot(out, withSubtitles = true)
                            Toast.makeText(context, "Snapshot saved: ${out.name}", Toast.LENGTH_SHORT).show()
                        },
                    ),
                )
                .lumaMouseAndTrackpadCanvas(
                    onScrollSeekOrVolume = { dy, isRight ->
                        if (!isRight) coordinator.seekRelative(if (dy < 0) 5.0 else -5.0)
                    },
                    onSecondaryContextMenu = { showContextMenu = true },
                )
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { showHud = !showHud })
                },
        ) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).also { sv ->
                        surfaceViewRef = sv
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Live A/B Split Wipe Draggable Handle when splitWipe < 0.99f
            if (splitWipe < 0.99f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { _, dx ->
                                splitWipe = (splitWipe + dx / size.width.toFloat()).coerceIn(0f, 1f)
                                coordinator.setSplitWipe(splitWipe)
                            }
                        },
                )
            }

            // M3 Telemetry Pill HUD (Toggleable)
            AnimatedVisibility(
                visible = showTelemetryPill && showHud,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 18.dp),
            ) {
                Surface(
                    color = Color(0xB30F172A),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Text(
                        text = "${hudTelemetry.engineLabel} • ${hudTelemetry.decoderLabel} • ${hudTelemetry.formatLabel} • " +
                            "${hudTelemetry.outputLabel} • ${hudTelemetry.refreshRateHz}Hz • " +
                            "${"%.0f".format(hudTelemetry.physicalNits)}nit (${"%.2f".format(hudTelemetry.inverseGainX)}x) • " +
                            "${hudTelemetry.thermalTier.name} • Drops:${hudTelemetry.droppedFrames}",
                        color = Color(0xFFE2E8F0),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }

            // Glassmorphic Player HUD
            AnimatedVisibility(
                visible = showHud,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                    Row(
                        modifier = Modifier.align(Alignment.TopStart),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onExit) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                        Text(
                            text = title,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                        )
                    }

                    Row(
                        modifier = Modifier.align(Alignment.TopEnd),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // Press-and-Hold "Original" Bypass
                        Surface(
                            color = Color(0xCC1E293B),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        coordinator.setBypassOriginal(true)
                                        tryAwaitRelease()
                                        coordinator.setBypassOriginal(false)
                                    },
                                )
                            },
                        ) {
                            Text(
                                text = "Hold Original",
                                color = Color(0xFF38BDF8),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }

                        IconButton(onClick = onHandoffToMusicNowPlaying) {
                            Icon(Icons.Default.Headphones, contentDescription = "Background Audio", tint = Color.White)
                        }
                        IconButton(onClick = {
                            viewModel.addBookmark(uri, title, (mpvTelemetry.positionSec * 1000).toLong(), "Bookmark")
                            Toast.makeText(context, "Timeline Bookmark Saved", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.BookmarkAdd, contentDescription = "Bookmark", tint = Color.White)
                        }
                        IconButton(onClick = {
                            val out = File(context.cacheDir, "snap_${System.currentTimeMillis()}.png")
                            coordinator.mpvEngine.takeScreenshot(out, withSubtitles = true)
                            Toast.makeText(context, "Snapshot: ${out.name}", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.CameraAlt, contentDescription = "Snapshot", tint = Color.White)
                        }
                        IconButton(onClick = { showMasteringSheet = true }) {
                            Icon(Icons.Default.Tune, contentDescription = "XR Mastering", tint = Color(0xFF38BDF8))
                        }
                    }

                    // Bottom Seekbar & Transport
                    Surface(
                        color = Color(0xCC0B1120),
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                            val dur = mpvTelemetry.durationSec.coerceAtLeast(1.0)
                            val pos = mpvTelemetry.positionSec.coerceIn(0.0, dur)
                            Slider(
                                value = (pos / dur).toFloat(),
                                onValueChange = { frac ->
                                    coordinator.mpvEngine.seekToSeconds(frac * dur)
                                },
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${formatTimeSec(pos)} / ${formatTimeSec(dur)}",
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 12.sp,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { coordinator.mpvEngine.frameStepBackward() }) {
                                        Icon(Icons.Default.SkipPrevious, contentDescription = "Frame Back", tint = Color.White)
                                    }
                                    IconButton(onClick = { coordinator.setPaused(!mpvTelemetry.paused) }) {
                                        Icon(
                                            imageVector = if (mpvTelemetry.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                            contentDescription = "Play/Pause",
                                            tint = Color(0xFF38BDF8),
                                        )
                                    }
                                    IconButton(onClick = { coordinator.mpvEngine.frameStepForward() }) {
                                        Icon(Icons.Default.SkipNext, contentDescription = "Frame Forward", tint = Color.White)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = splitWipe < 0.99f,
                                        onClick = {
                                            splitWipe = if (splitWipe < 0.99f) 1.0f else 0.5f
                                            coordinator.setSplitWipe(splitWipe)
                                        },
                                        label = { Text("A/B Wipe") },
                                    )
                                    FilterChip(
                                        selected = aspectMode != AspectMode.ORIGINAL,
                                        onClick = {
                                            val vals = AspectMode.entries
                                            aspectMode = vals[(aspectMode.ordinal + 1) % vals.size]
                                            coordinator.setAspectMode(aspectMode, subPlacement)
                                        },
                                        label = { Text(aspectMode.label) },
                                        leadingIcon = { Icon(Icons.Default.AspectRatio, contentDescription = null) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Right-Click Desktop Context Menu
            DropdownMenu(
                expanded = showContextMenu,
                onDismissRequest = { showContextMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Toggle Telemetry Pill (I)") },
                    onClick = { viewModel.toggleTelemetryPill(); showContextMenu = false },
                )
                DropdownMenuItem(
                    text = { Text("XR Mastering Sheet") },
                    onClick = { showMasteringSheet = true; showContextMenu = false },
                )
                DropdownMenuItem(
                    text = { Text(if (abLoopA == null) "Set A-B Loop Point A" else "Set A-B Loop Point B") },
                    onClick = {
                        if (abLoopA == null) {
                            abLoopA = mpvTelemetry.positionSec
                        } else {
                            coordinator.mpvEngine.setAbLoop(abLoopA, mpvTelemetry.positionSec)
                            abLoopA = null
                        }
                        showContextMenu = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Clear A-B Loop") },
                    onClick = { coordinator.mpvEngine.setAbLoop(null, null); abLoopA = null; showContextMenu = false },
                )
            }
        }
    }

    if (showMasteringSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMasteringSheet = false },
            containerColor = Color(0xFF0F172A),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Cinema Mastering XR Studio", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Routing: ${hudTelemetry.routingReason}", color = Color(0xFF38BDF8), fontSize = 12.sp)
                hudTelemetry.experimentalHbmWarning?.let { warn ->
                    Text(warn, color = Color(0xFFFBBF24), fontSize = 12.sp)
                }

                Text("XR Preset", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    XrPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = xrPreset == preset,
                            onClick = { viewModel.updateXrPreset(preset) },
                            label = { Text(preset.displayName, fontSize = 11.sp) },
                        )
                    }
                }

                Text("Engine Switch", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineSwitchPreference.entries.forEach { pref ->
                        FilterChip(
                            selected = enginePref == pref,
                            onClick = { viewModel.updateEnginePreference(pref) },
                            label = { Text(pref.name.replace('_', ' '), fontSize = 11.sp) },
                        )
                    }
                }

                Text("HDR Subtitle Peak Brightness: $subtitlePeakNits nits", color = Color.White, fontSize = 13.sp)
                Slider(
                    value = subtitlePeakNits.toFloat(),
                    onValueChange = { viewModel.setSubtitlePeakNits(it.toInt()) },
                    valueRange = 80f..260f,
                )

                Text("Live A/B Split Wipe: ${(splitWipe * 100).toInt()}%", color = Color.White, fontSize = 13.sp)
                Slider(
                    value = splitWipe,
                    onValueChange = {
                        splitWipe = it
                        coordinator.setSplitWipe(it)
                    },
                )

                Text("Subtitle Placement", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubtitlePlacement.entries.forEach { pl ->
                        FilterChip(
                            selected = subPlacement == pl,
                            onClick = {
                                subPlacement = pl
                                coordinator.setAspectMode(aspectMode, pl)
                            },
                            label = { Text(pl.label) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

private fun formatTimeSec(sec: Double): String {
    val total = sec.toLong().coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
