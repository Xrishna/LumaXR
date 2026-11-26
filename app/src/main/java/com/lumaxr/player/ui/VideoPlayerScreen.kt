package com.lumaxr.player.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumaxr.player.video.AspectMode
import com.lumaxr.player.video.DesktopInputHandler.lumaDesktopShortcuts
import com.lumaxr.player.video.DesktopInputHandler.lumaMouseAndTrackpadCanvas
import com.lumaxr.player.video.DesktopShortcutCallbacks
import com.lumaxr.player.video.EngineSwitchPreference
import com.lumaxr.player.video.HdrToysToneMapper
import com.lumaxr.player.video.SubtitlePlacement
import com.lumaxr.player.video.VideoEngineKind
import com.lumaxr.player.video.XrPreset
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private enum class PlayerDragGestureMode {
    NONE,
    SEEK,
    BRIGHTNESS,
    VOLUME,
    SPLIT_WIPE,
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val coordinator = viewModel.container.videoCoordinator

    val xrPreset by viewModel.xrPreset.collectAsStateWithLifecycle()
    val refreshPolicy by viewModel.refreshPolicy.collectAsStateWithLifecycle()
    val enginePref by viewModel.enginePreference.collectAsStateWithLifecycle()
    val hdrToysMapper by viewModel.hdrToysMapper.collectAsStateWithLifecycle()
    val experimentalHbm by viewModel.experimentalHbm.collectAsStateWithLifecycle()
    val subtitlePeakNits by viewModel.subtitlePeakNits.collectAsStateWithLifecycle()
    val showTelemetryPill by viewModel.showTelemetryPill.collectAsStateWithLifecycle()

    val hudTelemetry by coordinator.hudTelemetry.collectAsStateWithLifecycle()
    val mpvTelemetry by coordinator.mpvEngine.telemetry.collectAsStateWithLifecycle()
    val media3Telemetry by coordinator.media3Engine.telemetry.collectAsStateWithLifecycle()
    val activeEngine by coordinator.activeEngineKind.collectAsStateWithLifecycle()
    val mpvTracks by coordinator.mpvEngine.tracks.collectAsStateWithLifecycle()

    var showHud by remember { mutableStateOf(true) }
    var showMasteringSheet by remember { mutableStateOf(false) }
    var showTracksSheet by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }
    var splitWipe by remember { mutableFloatStateOf(1.0f) }
    var aspectMode by remember { mutableStateOf(AspectMode.ORIGINAL) }
    var subPlacement by remember { mutableStateOf(SubtitlePlacement.ABOVE_LETTERBOX) }
    var abLoopA by remember { mutableStateOf<Double?>(null) }
    var surfaceViewRef by remember { mutableStateOf<SurfaceView?>(null) }
    var playbackStarted by remember(uri, enginePref, experimentalHbm, refreshPolicy) { mutableStateOf(false) }
    var gestureHudBadge by remember { mutableStateOf<String?>(null) }
    var pendingSeekTargetSec by remember { mutableDoubleStateOf(0.0) }

    val externalSubLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { subUri ->
        if (subUri != null) {
            coordinator.mpvEngine.addExternalSubtitle(subUri.toString())
            Toast.makeText(context, "Loaded external subtitle", Toast.LENGTH_SHORT).show()
        }
    }

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

    // Guarantee engine + display cleanup on exit
    DisposableEffect(uri) {
        onDispose {
            coordinator.stopAndRestoreDisplay()
        }
    }

    LaunchedEffect(surfaceViewRef, uri, enginePref, experimentalHbm, refreshPolicy) {
        val sv = surfaceViewRef ?: return@LaunchedEffect
        playbackStarted = false
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
        playbackStarted = true
    }

    LaunchedEffect(playbackStarted, xrPreset, hdrToysMapper, subtitlePeakNits) {
        if (playbackStarted) {
            coordinator.updateLiveMasteringOptions(
                preset = xrPreset,
                hdrToysMapper = hdrToysMapper,
                subtitlePeakNits = subtitlePeakNits,
            )
        }
    }

    val isPausedNow = if (activeEngine == VideoEngineKind.LIBMPV_PLACEBO_XR) {
        mpvTelemetry.paused
    } else {
        !media3Telemetry.isPlaying
    }
    val posSecNow = if (activeEngine == VideoEngineKind.LIBMPV_PLACEBO_XR) {
        mpvTelemetry.positionSec
    } else {
        media3Telemetry.positionMs / 1000.0
    }
    val durSecNow = (if (activeEngine == VideoEngineKind.LIBMPV_PLACEBO_XR) {
        mpvTelemetry.durationSec
    } else {
        media3Telemetry.durationMs / 1000.0
    }).coerceAtLeast(1.0)

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
                        onTogglePlayPause = { coordinator.setPaused(!coordinator.isPaused()) },
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
                    detectTapGestures(
                        onTap = { showHud = !showHud },
                        onDoubleTap = { offset ->
                            when {
                                offset.x < size.width * 0.35f -> {
                                    coordinator.seekRelative(-10.0)
                                    gestureHudBadge = "Seek -10s"
                                }
                                offset.x > size.width * 0.65f -> {
                                    coordinator.seekRelative(10.0)
                                    gestureHudBadge = "Seek +10s"
                                }
                                else -> coordinator.setPaused(!coordinator.isPaused())
                            }
                        },
                    )
                }
                .pointerInput(splitWipe, durSecNow) {
                    var dragMode = PlayerDragGestureMode.NONE
                    var startX = 0f
                    var accumX = 0f
                    var accumY = 0f
                    var startBrightness = 0.5f
                    var startVolume = 8
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    var startPosSec = 0.0
                    var seekActive = false

                    detectDragGestures(
                        onDragStart = { offset ->
                            dragMode = PlayerDragGestureMode.NONE
                            startX = offset.x
                            accumX = 0f
                            accumY = 0f
                            seekActive = false
                            startBrightness = activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.55f
                            startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            startPosSec = coordinator.currentPositionSec()
                        },
                        onDragCancel = {
                            dragMode = PlayerDragGestureMode.NONE
                            gestureHudBadge = null
                            seekActive = false
                        },
                        onDragEnd = {
                            if (dragMode == PlayerDragGestureMode.SEEK && seekActive) {
                                coordinator.seekToSec(pendingSeekTargetSec)
                            }
                            dragMode = PlayerDragGestureMode.NONE
                            gestureHudBadge = null
                            seekActive = false
                        },
                        onDrag = { change, dragAmount ->
                            accumX += dragAmount.x
                            accumY += dragAmount.y
                            if (dragMode == PlayerDragGestureMode.NONE) {
                                if (hypot(accumX, accumY) > viewConfiguration.touchSlop) {
                                    dragMode = if (abs(accumX) >= abs(accumY)) {
                                        if (splitWipe < 0.99f) PlayerDragGestureMode.SPLIT_WIPE else PlayerDragGestureMode.SEEK
                                    } else {
                                        if (startX < size.width * 0.5f) {
                                            PlayerDragGestureMode.BRIGHTNESS
                                        } else {
                                            PlayerDragGestureMode.VOLUME
                                        }
                                    }
                                }
                            }
                            if (dragMode != PlayerDragGestureMode.NONE) {
                                change.consume()
                                when (dragMode) {
                                    PlayerDragGestureMode.SPLIT_WIPE -> {
                                        splitWipe = (splitWipe + dragAmount.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                                        coordinator.setSplitWipe(splitWipe)
                                        gestureHudBadge = "XR Wipe ${(splitWipe * 100).roundToInt()}%"
                                    }
                                    PlayerDragGestureMode.SEEK -> {
                                        val deltaSec = (accumX / size.width.toFloat().coerceAtLeast(1f)) * 90.0
                                        pendingSeekTargetSec = (startPosSec + deltaSec).coerceIn(0.0, durSecNow)
                                        seekActive = true
                                        val sign = if (deltaSec >= 0) "+" else "-"
                                        gestureHudBadge = "${formatTimeSec(pendingSeekTargetSec)} ($sign${abs(deltaSec).toInt()}s)"
                                    }
                                    PlayerDragGestureMode.BRIGHTNESS -> {
                                        val deltaFrac = -accumY / (size.height * 0.65f).coerceAtLeast(1f)
                                        val nextB = (startBrightness + deltaFrac).coerceIn(0.02f, 1.0f)
                                        activity?.window?.let { win ->
                                            val lp = win.attributes
                                            lp.screenBrightness = nextB
                                            win.attributes = lp
                                        }
                                        gestureHudBadge = "Brightness ${(nextB * 100).roundToInt()}%"
                                    }
                                    PlayerDragGestureMode.VOLUME -> {
                                        val deltaFrac = -accumY / (size.height * 0.65f).coerceAtLeast(1f)
                                        val nextVol = (startVolume + deltaFrac * maxVolume).roundToInt().coerceIn(0, maxVolume)
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nextVol, 0)
                                        gestureHudBadge = "Volume ${((nextVol * 100f) / maxVolume).roundToInt()}%"
                                    }
                                    PlayerDragGestureMode.NONE -> Unit
                                }
                            }
                        },
                    )
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

            // Transient Gesture / Seek Feedback Pill
            gestureHudBadge?.let { badge ->
                Surface(
                    color = Color(0xCC0F172A),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Text(
                        text = badge,
                        color = Color(0xFF38BDF8),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
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
                            "${String.format(Locale.US, "%.0f", hudTelemetry.physicalNits)}nit (${String.format(Locale.US, "%.2f", hudTelemetry.inverseGainX)}x) • " +
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
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
                                        try {
                                            coordinator.setBypassOriginal(true)
                                            tryAwaitRelease()
                                        } finally {
                                            coordinator.setBypassOriginal(false)
                                        }
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

                        if (activeEngine == VideoEngineKind.LIBMPV_PLACEBO_XR) {
                            IconButton(onClick = { showTracksSheet = true }) {
                                Icon(Icons.Default.Subtitles, contentDescription = "Tracks & Subtitles", tint = Color.White)
                            }
                        }
                        IconButton(onClick = onHandoffToMusicNowPlaying) {
                            Icon(Icons.Default.Headphones, contentDescription = "Background Audio", tint = Color.White)
                        }
                        IconButton(onClick = {
                            viewModel.addBookmark(uri, title, (coordinator.currentPositionSec() * 1000).toLong(), "Bookmark")
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
                            val dur = durSecNow
                            val pos = posSecNow.coerceIn(0.0, dur)
                            Slider(
                                value = (pos / dur).toFloat(),
                                onValueChange = { frac ->
                                    coordinator.seekToSec(frac * dur)
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
                                    IconButton(onClick = { coordinator.setPaused(!isPausedNow) }) {
                                        Icon(
                                            imageVector = if (isPausedNow) Icons.Default.PlayArrow else Icons.Default.Pause,
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
                    text = { Text("Audio, Subtitles & Speed") },
                    onClick = { showTracksSheet = true; showContextMenu = false },
                )
                DropdownMenuItem(
                    text = { Text("XR Mastering Sheet") },
                    onClick = { showMasteringSheet = true; showContextMenu = false },
                )
                DropdownMenuItem(
                    text = { Text(if (abLoopA == null) "Set A-B Loop Point A" else "Set A-B Loop Point B") },
                    onClick = {
                        if (abLoopA == null) {
                            abLoopA = coordinator.currentPositionSec()
                        } else {
                            coordinator.mpvEngine.setAbLoop(abLoopA, coordinator.currentPositionSec())
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

    if (showTracksSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val audioTracks = mpvTracks.filter { it.type == "audio" }
        val subTracks = mpvTracks.filter { it.type == "sub" }
        val speeds = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)

        ModalBottomSheet(
            onDismissRequest = { showTracksSheet = false },
            sheetState = sheetState,
            containerColor = Color(0xFF0F172A),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Tracks, Subtitles & Speed", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)

                Text("Playback Speed", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    speeds.forEach { spd ->
                        FilterChip(
                            selected = abs(mpvTelemetry.speed - spd) < 0.05,
                            onClick = { coordinator.mpvEngine.setSpeed(spd) },
                            label = { Text("${spd}x", fontSize = 11.sp) },
                        )
                    }
                }

                Text("Audio Tracks", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                if (audioTracks.isEmpty()) {
                    Text("No selectable audio tracks reported yet", color = Color(0xFF64748B), fontSize = 12.sp)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        audioTracks.forEach { tr ->
                            val label = listOfNotNull(
                                tr.lang.takeIf { it.isNotBlank() },
                                tr.title.takeIf { it.isNotBlank() },
                                tr.codec.takeIf { it.isNotBlank() },
                            ).joinToString(" • ").ifBlank { "Track ${tr.id}" }
                            FilterChip(
                                selected = tr.selected,
                                onClick = { coordinator.mpvEngine.selectTrack("aid", tr.id) },
                                leadingIcon = { Icon(Icons.Default.Audiotrack, contentDescription = null) },
                                label = { Text(label, fontSize = 11.sp) },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Subtitle Tracks", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                    OutlinedButton(onClick = { externalSubLauncher.launch(arrayOf("*/*")) }) {
                        Text("Load External Sub")
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = subTracks.none { it.selected },
                        onClick = { coordinator.mpvEngine.selectTrack("sid", 0) },
                        label = { Text("Subtitles Off", fontSize = 11.sp) },
                    )
                    subTracks.forEach { tr ->
                        val label = listOfNotNull(
                            tr.lang.takeIf { it.isNotBlank() },
                            tr.title.takeIf { it.isNotBlank() },
                            tr.codec.takeIf { it.isNotBlank() },
                        ).joinToString(" • ").ifBlank { "Sub ${tr.id}" }
                        FilterChip(
                            selected = tr.selected,
                            onClick = { coordinator.mpvEngine.selectTrack("sid", tr.id) },
                            label = { Text(label, fontSize = 11.sp) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (showMasteringSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showMasteringSheet = false },
            sheetState = sheetState,
            containerColor = Color(0xFF0F172A),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Cinema Mastering XR Studio", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Routing: ${hudTelemetry.routingReason}", color = Color(0xFF38BDF8), fontSize = 12.sp)
                hudTelemetry.experimentalHbmWarning?.let { warn ->
                    Text(warn, color = Color(0xFFFBBF24), fontSize = 12.sp)
                }

                Text("XR Preset", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    XrPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = xrPreset == preset,
                            onClick = { viewModel.updateXrPreset(preset) },
                            label = { Text(preset.displayName, fontSize = 11.sp) },
                        )
                    }
                }

                Text("Engine Switch", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}
