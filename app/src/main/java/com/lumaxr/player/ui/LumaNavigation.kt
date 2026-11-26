package com.lumaxr.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable

@Serializable
sealed interface LumaRoute : NavKey {
    @Serializable
    data object VideoLibrary : LumaRoute

    @Serializable
    data object MusicLibrary : LumaRoute

    @Serializable
    data object SettingsAndNetwork : LumaRoute

    @Serializable
    data class VideoPlayer(val uri: String, val title: String) : LumaRoute

    @Serializable
    data class NowPlaying(val heroKey: String) : LumaRoute
}

fun isGridOriginVisible(gridState: LazyGridState, key: Any): Boolean {
    return gridState.layoutInfo.visibleItemsInfo.any { it.key == key }
}

fun isListOriginVisible(listState: LazyListState, key: Any): Boolean {
    return listState.layoutInfo.visibleItemsInfo.any { it.key == key }
}

/**
 * Phase B Single-Root SharedTransitionLayout & Navigation 3 Back Stack with non-conflicting
 * Predictive Back transitions matching Android 16 NavigationEvent edge direction and Reduce Motion.
 */
@Composable
fun LumaNavigationRoot(
    viewModel: MainViewModel,
    externalVideoUri: String? = null,
) {
    val context = LocalContext.current
    val backStack = rememberNavBackStack(LumaRoute.VideoLibrary)

    LaunchedEffect(externalVideoUri) {
        if (!externalVideoUri.isNullOrBlank()) {
            val top = backStack.lastOrNull()
            val alreadyOpen = top is LumaRoute.VideoPlayer && top.uri == externalVideoUri
            if (!alreadyOpen) {
                if (top is LumaRoute.VideoPlayer && backStack.size > 1) {
                    backStack.removeLastOrNull()
                }
                backStack.add(
                    LumaRoute.VideoPlayer(
                        externalVideoUri,
                        externalVideoUri.substringAfterLast('/').ifBlank { "External Video" },
                    ),
                )
            }
        }
    }

    val backStyle by viewModel.backStyle.collectAsStateWithLifecycle()
    val effectiveStyle = if (isReduceMotionEnabled(context)) PredictiveBackStyle.NONE else backStyle
    val musicState by viewModel.container.musicCoordinator.state.collectAsStateWithLifecycle()
    val videoGridState = remember { LazyGridState() }
    val musicListState = remember { LazyListState() }

    val topRoute = backStack.lastOrNull() ?: LumaRoute.VideoLibrary
    val isFullScreenPlayer = topRoute is LumaRoute.VideoPlayer || topRoute is LumaRoute.NowPlaying

    SharedTransitionLayout(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val sharedScope: SharedTransitionScope = this

        Box(modifier = Modifier.fillMaxSize()) {
            // Z = 0f: NavDisplay Content
            NavDisplay(
                backStack = backStack,
                onBack = {
                    if (backStack.lastOrNull() is LumaRoute.VideoPlayer) {
                        viewModel.container.videoCoordinator.stopAndRestoreDisplay()
                    }
                    if (backStack.size > 1) backStack.removeLastOrNull()
                },
                sharedTransitionScope = sharedScope,
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
                transitionSpec = {
                    when (effectiveStyle) {
                        PredictiveBackStyle.NONE -> EnterTransition.None togetherWith ExitTransition.None
                        PredictiveBackStyle.MATERIAL_FADE_THROUGH -> fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                        PredictiveBackStyle.PREDICTIVE_DETACH_SHARED ->
                            (fadeIn(tween(200)) + scaleIn(initialScale = 0.96f, animationSpec = tween(220))) togetherWith
                                fadeOut(tween(150))
                    }
                },
                popTransitionSpec = {
                    when (effectiveStyle) {
                        PredictiveBackStyle.NONE -> EnterTransition.None togetherWith ExitTransition.None
                        PredictiveBackStyle.MATERIAL_FADE_THROUGH -> fadeIn(tween(180)) togetherWith fadeOut(tween(180))
                        PredictiveBackStyle.PREDICTIVE_DETACH_SHARED ->
                            fadeIn(tween(200)) togetherWith
                                (scaleOut(targetScale = 0.90f, animationSpec = tween(220)) + fadeOut(tween(200)))
                    }
                },
                predictivePopTransitionSpec = { swipeEdge ->
                    when (effectiveStyle) {
                        PredictiveBackStyle.NONE -> EnterTransition.None togetherWith ExitTransition.None
                        PredictiveBackStyle.MATERIAL_FADE_THROUGH -> fadeIn(tween(180)) togetherWith fadeOut(tween(180))
                        PredictiveBackStyle.PREDICTIVE_DETACH_SHARED -> {
                            val dir = if (swipeEdge == 0) 1 else -1
                            (fadeIn(tween(180)) + scaleIn(initialScale = 0.95f)) togetherWith
                                (slideOutHorizontally(targetOffsetX = { w -> dir * (w / 5) }) +
                                    scaleOut(targetScale = 0.88f) +
                                    fadeOut(tween(200)))
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(0f),
                entryProvider = entryProvider {
                    entry<LumaRoute.VideoLibrary> {
                        VideoLibraryScreen(
                            viewModel = viewModel,
                            gridState = videoGridState,
                            sharedScope = sharedScope,
                            sharedElementsVisible = true,
                            onOpenVideo = { item ->
                                backStack.add(LumaRoute.VideoPlayer(item.uri, item.title))
                            },
                        )
                    }
                    entry<LumaRoute.MusicLibrary> {
                        MusicLibraryScreen(
                            viewModel = viewModel,
                            listState = musicListState,
                            onPlayTracks = { tracks, index ->
                                viewModel.container.musicCoordinator.playQueue(tracks, index)
                            },
                            onOpenNowPlaying = {
                                backStack.add(LumaRoute.NowPlaying("mini_player_art"))
                            },
                        )
                    }
                    entry<LumaRoute.SettingsAndNetwork> {
                        NetworkAndSettingsScreen(
                            viewModel = viewModel,
                            onPlayRemoteUrl = { url, title ->
                                backStack.add(LumaRoute.VideoPlayer(url, title))
                            },
                        )
                    }
                    entry<LumaRoute.VideoPlayer> { route ->
                        val originVisible = isGridOriginVisible(videoGridState, route.uri)
                        VideoPlayerScreen(
                            uri = route.uri,
                            title = route.title,
                            viewModel = viewModel,
                            sharedScope = sharedScope,
                            sharedElementsVisible = originVisible && effectiveStyle == PredictiveBackStyle.PREDICTIVE_DETACH_SHARED,
                            onExit = {
                                viewModel.container.videoCoordinator.stopAndRestoreDisplay()
                                if (backStack.size > 1) backStack.removeLastOrNull()
                            },
                            onHandoffToMusicNowPlaying = {
                                if (viewModel.container.videoToAudioHandoff.handoffToBackgroundAudio()) {
                                    if (backStack.size > 1) backStack.removeLastOrNull()
                                    backStack.add(LumaRoute.NowPlaying("mini_player_art"))
                                }
                            },
                        )
                    }
                    entry<LumaRoute.NowPlaying> { route ->
                        NowPlayingScreen(
                            heroKey = route.heroKey,
                            viewModel = viewModel,
                            sharedScope = sharedScope,
                            sharedElementsVisible = effectiveStyle == PredictiveBackStyle.PREDICTIVE_DETACH_SHARED,
                            onClose = {
                                if (backStack.size > 1) backStack.removeLastOrNull()
                            },
                        )
                    }
                },
            )

            // Z = 30f: Pinned Morphing TopAppBar
            AnimatedVisibility(
                visible = !isFullScreenPlayer,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(30f),
            ) {
                Surface(
                    color = Color(0xCC0B101B),
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "LumaXR",
                            color = Color(0xFF38BDF8),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            text = when (topRoute) {
                                LumaRoute.VideoLibrary -> "Cinema Mastering XR • 120 Hz"
                                LumaRoute.MusicLibrary -> "Audiophile Hi-Res & Dolby"
                                LumaRoute.SettingsAndNetwork -> "Network, Calibration & Backup"
                                else -> ""
                            },
                            color = Color(0xFFCBD5E1),
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            // Z = 20f: Pinned MiniPlayer + Floating NavigationBar
            AnimatedVisibility(
                visible = !isFullScreenPlayer,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(20f),
            ) {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (musicState.currentTrack != null) {
                        MiniPlayerBar(
                            state = musicState,
                            sharedScope = sharedScope,
                            sharedElementsVisible = effectiveStyle == PredictiveBackStyle.PREDICTIVE_DETACH_SHARED,
                            onTogglePlayPause = { viewModel.container.musicCoordinator.togglePlayPause() },
                            onNext = { viewModel.container.musicCoordinator.nextTrack() },
                            onPrev = { viewModel.container.musicCoordinator.prevTrack() },
                            onExpand = { backStack.add(LumaRoute.NowPlaying("mini_player_art")) },
                        )
                    }
                    NavigationBar(
                        containerColor = Color(0xE6111827),
                        modifier = Modifier.clip(RoundedCornerShape(22.dp)),
                    ) {
                        NavigationBarItem(
                            selected = topRoute == LumaRoute.VideoLibrary,
                            onClick = {
                                if (topRoute != LumaRoute.VideoLibrary) {
                                    backStack.clear()
                                    backStack.add(LumaRoute.VideoLibrary)
                                }
                            },
                            icon = { Icon(Icons.Default.Movie, contentDescription = "Video") },
                            label = { Text("Cinema") },
                        )
                        NavigationBarItem(
                            selected = topRoute == LumaRoute.MusicLibrary,
                            onClick = {
                                if (topRoute != LumaRoute.MusicLibrary) {
                                    backStack.clear()
                                    backStack.add(LumaRoute.MusicLibrary)
                                }
                            },
                            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "Music") },
                            label = { Text("Hi-Res Music") },
                        )
                        NavigationBarItem(
                            selected = topRoute == LumaRoute.SettingsAndNetwork,
                            onClick = {
                                if (topRoute != LumaRoute.SettingsAndNetwork) {
                                    backStack.clear()
                                    backStack.add(LumaRoute.SettingsAndNetwork)
                                }
                            },
                            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                            label = { Text("Pro & Backup") },
                        )
                    }
                }
            }
        }
    }
}
