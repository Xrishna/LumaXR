package com.lumaxr.player.ui

import android.opengl.GLSurfaceView
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.lumaxr.player.lyrics.AmLyricsView
import com.lumaxr.player.visuals.AgslAlbumBackdrop
import com.lumaxr.player.visuals.VisualStudioRenderer
import com.lumaxr.player.visuals.VisualizerMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tablet-First 16:10 Landscape Dual-Pane Now Playing:
 * - Native Kawarp / MilkDrop GLES 3.2 GL_RGBA16F backdrop + AGSL fallback
 * - Left pane: Shared-element morph album card, Waveform Seekbar, Transport, Signal-Path pill, Output Switcher
 * - Right pane: Tabbed AmLyricsView | Up Next Queue | Parametric EQ & Spatial
 */
@Composable
fun NowPlayingScreen(
    heroKey: String,
    viewModel: MainViewModel,
    sharedScope: SharedTransitionScope,
    sharedElementsVisible: Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coordinator = viewModel.container.musicCoordinator
    val state by coordinator.state.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()
    val lyricStyle by viewModel.lyricStyle.collectAsStateWithLifecycle()
    val glowConfig by viewModel.glowConfig.collectAsStateWithLifecycle()
    val peaks by viewModel.waveformPeaks.collectAsStateWithLifecycle()
    val fftFrame by viewModel.container.audioFftAnalyzer.frame.collectAsStateWithLifecycle()
    val thermal by viewModel.container.thermalGovernor.snapshot.collectAsStateWithLifecycle()

    var rightTab by remember { mutableIntStateOf(0) }
    var showSignalPath by remember { mutableStateOf(false) }
    var showVisualStudio by remember { mutableStateOf(false) }
    var showLyricsStudio by remember { mutableStateOf(false) }
    var glSurfaceViewRef by remember { mutableStateOf<GLSurfaceView?>(null) }

    val renderer = remember {
        VisualStudioRenderer(
            context = context,
            fftProvider = { viewModel.container.audioFftAnalyzer.frame.value },
            configProvider = { viewModel.glowConfig.value },
            maxFpsProvider = { thermal.tier.visualizerMaxFps },
        )
    }

    val track = state.currentTrack
    val oscPath = remember { Path() }

    LaunchedEffect(track?.uri, track?.artworkUri) {
        val artUri = track?.artworkUri?.takeIf { it.isNotBlank() }
        if (artUri == null) {
            renderer.submitArtworkOrVideoFrame(
                VisualStudioRenderer.createAmbientPaletteBitmap(track?.title ?: "LumaXR-Aurora"),
            )
            return@LaunchedEffect
        }
        val req = ImageRequest.Builder(context)
            .data(artUri)
            .allowHardware(false)
            .size(512)
            .build()
        val res = withContext(Dispatchers.IO) {
            runCatching { context.imageLoader.execute(req) }.getOrNull()
        }
        val bmp = (res as? SuccessResult)?.drawable?.toBitmap(config = android.graphics.Bitmap.Config.ARGB_8888)
        if (bmp != null) {
            renderer.submitArtworkOrVideoFrame(bmp)
        } else {
            renderer.submitArtworkOrVideoFrame(
                VisualStudioRenderer.createAmbientPaletteBitmap(track?.title ?: "LumaXR-Aurora"),
            )
        }
    }

    DisposableEffect(lifecycleOwner, glSurfaceViewRef) {
        val view = glSurfaceViewRef
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view?.onPause()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Native 120 Hz Kawarp / MilkDrop GLES 3.2 Backdrop
        if (glowConfig.visualizerMode != VisualizerMode.MINIMAL_STATIC) {
            AndroidView(
                factory = { ctx ->
                    GLSurfaceView(ctx).apply {
                        setEGLContextClientVersion(3)
                        setEGLWindowSurfaceFactory(VisualStudioRenderer.createSrgbWindowFactory())
                        setRenderer(renderer)
                        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    }.also { glSurfaceViewRef = it }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AgslAlbumBackdrop.AgslMeshBackdrop(
                primaryColor = Color(0xFF0A1224),
                secondaryColor = Color(0xFF1A1030),
                dimmer = glowConfig.backdropDimmer,
            )
        }

        // Optional Harmonic Spectrum or Dual-Channel Oscilloscope/Lissajous overlay
        if (glowConfig.visualizerMode == VisualizerMode.HARMONIC_SPECTRUM) {
            Canvas(modifier = Modifier.fillMaxSize().padding(32.dp)) {
                val barW = size.width / fftFrame.spectrumBars.size.coerceAtLeast(1)
                fftFrame.spectrumBars.forEachIndexed { idx, v ->
                    val h = size.height * 0.35f * v
                    drawRect(
                        color = Color(0x5538BDF8),
                        topLeft = Offset(idx * barW, size.height - h),
                        size = androidx.compose.ui.geometry.Size(barW * 0.7f, h),
                    )
                }
            }
        } else if (glowConfig.visualizerMode == VisualizerMode.OSCILLOSCOPE_LISSAJOUS) {
            Canvas(modifier = Modifier.fillMaxSize().padding(32.dp)) {
                oscPath.rewind()
                val samples = fftFrame.waveformSamples
                samples.forEachIndexed { idx, s ->
                    val x = (idx.toFloat() / (samples.size - 1).coerceAtLeast(1)) * size.width
                    val y = size.height * 0.8f + s * size.height * 0.14f
                    if (idx == 0) oscPath.moveTo(x, y) else oscPath.lineTo(x, y)
                }
                drawPath(oscPath, color = Color(0x8838BDF8), style = Stroke(width = 3f))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 14.dp),
        ) {
            // Top Chrome
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { showSignalPath = true },
                        label = {
                            Text(
                                text = track?.let { formatAudioBadge(it) ?: it.codec.uppercase() } ?: "SIGNAL PATH",
                                color = Color(0xFF38BDF8),
                            )
                        },
                    )
                    IconButton(onClick = { viewModel.container.deviceProfileRouter.showSystemOutputSwitcher() }) {
                        Icon(Icons.Default.SpeakerGroup, contentDescription = "Output Switcher", tint = Color.White)
                    }
                    IconButton(onClick = { showVisualStudio = true }) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "Visual Studio", tint = Color(0xFFA78BFA))
                    }
                    IconButton(onClick = { showLyricsStudio = true }) {
                        Icon(Icons.Default.Lyrics, contentDescription = "Lyrics Studio", tint = Color(0xFF38BDF8))
                    }
                }
            }

            // 16:10 Landscape Dual-Pane Body
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // LEFT PANE: Artwork + Waveform Seekbar + Transport
                Column(
                    modifier = Modifier
                        .weight(0.45f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    with(sharedScope) {
                        val artShape = RoundedCornerShape(22.dp)
                        val sharedState = rememberSharedContentState(key = heroKey)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.75f)
                                .aspectRatio(1f)
                                .sharedElementWithCallerManagedVisibility(
                                    sharedContentState = sharedState,
                                    visible = sharedElementsVisible,
                                    clipInOverlayDuringTransition = OverlayClip(artShape),
                                )
                                .clip(artShape)
                                .background(Color(0xFF1E293B)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!track?.artworkUri.isNullOrBlank()) {
                                AsyncImage(
                                    model = track?.artworkUri,
                                    contentDescription = track?.album,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(64.dp))
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        track?.title ?: "No Track Selected",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${track?.artist ?: ""} • ${track?.album ?: ""}",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(16.dp))

                    // Real Waveform Seekbar
                    val progress = if (state.durationMs > 0L) (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                    WaveformSeekbar(
                        peaks = peaks,
                        progress = progress,
                        onSeekFraction = { frac, emitHaptic ->
                            if (emitHaptic) viewModel.container.haptics.playSeekDetent()
                            coordinator.seekToMs((frac * state.durationMs).toLong())
                        },
                    )

                    Spacer(Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { viewModel.container.haptics.playTactileClick(); coordinator.prevTrack() }) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                        Surface(
                            color = Color(0xFF38BDF8),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .size(62.dp)
                                .clickable {
                                    viewModel.container.haptics.playTactileClick()
                                    coordinator.togglePlayPause()
                                },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    tint = Color.Black,
                                    modifier = Modifier.size(34.dp),
                                )
                            }
                        }
                        IconButton(onClick = { viewModel.container.haptics.playTactileClick(); coordinator.nextTrack() }) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                    }
                }

                // RIGHT PANE: Lyrics | Up Next Queue | Parametric EQ & Spatial
                Column(
                    modifier = Modifier
                        .weight(0.55f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0x990B1120)),
                ) {
                    TabRow(selectedTabIndex = rightTab, containerColor = Color.Transparent, contentColor = Color(0xFF38BDF8)) {
                        Tab(selected = rightTab == 0, onClick = { rightTab = 0 }, text = { Text("Lyrics") })
                        Tab(selected = rightTab == 1, onClick = { rightTab = 1 }, text = { Text("Up Next") })
                        Tab(selected = rightTab == 2, onClick = { rightTab = 2 }, text = { Text("PEQ & Spatial") })
                    }

                    when (rightTab) {
                        0 -> AmLyricsView(
                            lyricsState = lyricsState,
                            playerPositionMs = state.positionMs,
                            isPlaying = state.isPlaying,
                            playbackSpeed = 1.0f,
                            style = lyricStyle,
                            onSeekToMs = { coordinator.seekToMs(it) },
                        )
                        1 -> LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(state.queue, key = { idx, q -> "${idx}_${q.uri}" }) { idx, q ->
                                Surface(
                                    color = if (idx == state.currentIndex) Color(0xFF1E3A5F) else Color(0xFF111827),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().clickable { coordinator.playQueue(state.queue, idx) },
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "${idx + 1}. ${q.title}",
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                                        )
                                        Text(
                                            q.artist,
                                            color = Color(0xFF94A3B8),
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                        2 -> EqualizerScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }

    if (showSignalPath) {
        SignalPathSheet(
            telemetry = state.signalPath,
            onDismiss = { showSignalPath = false },
        )
    }
    if (showVisualStudio) {
        VisualStudioSheet(
            config = glowConfig,
            onUpdate = { viewModel.updateGlowConfig(it) },
            onDismiss = { showVisualStudio = false },
        )
    }
    if (showLyricsStudio) {
        LyricsStudioSheet(
            lyricsState = lyricsState,
            style = lyricStyle,
            onUpdateStyle = { viewModel.updateLyricStyle(it) },
            onUpdateOffset = { viewModel.updateLyricsOffset(it) },
            onSearchManual = { viewModel.searchLyricsManual(it) },
            onDismiss = { showLyricsStudio = false },
        )
    }
}

@Composable
private fun WaveformSeekbar(
    peaks: List<Float>,
    progress: Float,
    onSeekFraction: (Float, Boolean) -> Unit,
) {
    var lastHapticBucket by remember { mutableIntStateOf(-1) }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .pointerInput(peaks) {
                detectTapGestures { offset ->
                    val frac = (offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    onSeekFraction(frac, true)
                }
            }
            .pointerInput(peaks) {
                detectHorizontalDragGestures(
                    onDragStart = { lastHapticBucket = -1 },
                    onDragEnd = { lastHapticBucket = -1 },
                    onDragCancel = { lastHapticBucket = -1 },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val frac = (change.position.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                        val bucket = (frac * 40f).toInt()
                        val emitHaptic = bucket != lastHapticBucket
                        lastHapticBucket = bucket
                        onSeekFraction(frac, emitHaptic)
                    },
                )
            },
    ) {
        val count = peaks.size.coerceAtLeast(1)
        val step = size.width / count
        peaks.forEachIndexed { idx, amp ->
            val x = idx * step + step * 0.5f
            val h = (size.height * amp).coerceAtLeast(6f)
            val top = (size.height - h) * 0.5f
            val frac = idx.toFloat() / count
            val color = if (frac <= progress) Color(0xFF38BDF8) else Color(0xFF334155)
            drawLine(
                color = color,
                start = Offset(x, top),
                end = Offset(x, top + h),
                strokeWidth = (step * 0.58f).coerceAtLeast(2f),
            )
        }
    }
}
