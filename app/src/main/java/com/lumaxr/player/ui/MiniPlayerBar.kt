package com.lumaxr.player.ui

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lumaxr.player.music.MusicPlaybackState
import java.util.Locale

/**
 * Persistent frosted-glass MiniPlayer pinned at zIndex(20f) inside the root SharedTransitionLayout.
 * Supports swipe left/right to skip tracks and caller-managed shared-element morph into NowPlayingScreen.
 */
@Composable
fun MiniPlayerBar(
    state: MusicPlaybackState,
    sharedScope: SharedTransitionScope,
    sharedElementsVisible: Boolean,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onExpand: () -> Unit,
) {
    val track = state.currentTrack ?: return
    val interaction = remember { MutableInteractionSource() }
    var dragAccum by remember { mutableFloatStateOf(0f) }

    with(sharedScope) {
        val artShape = RoundedCornerShape(12.dp)
        val sharedState = rememberSharedContentState(key = "mini_player_art")

        Surface(
            color = Color(0xD9151E2E),
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .lumaPressScale(interaction)
                .pointerInput(track.uri) {
                    val swipeThresholdPx = 48.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragCancel = { dragAccum = 0f },
                        onDragEnd = {
                            if (dragAccum < -swipeThresholdPx) onNext()
                            else if (dragAccum > swipeThresholdPx) onPrev()
                            dragAccum = 0f
                        },
                        onHorizontalDrag = { _, dx -> dragAccum += dx },
                    )
                }
                .clickable(interactionSource = interaction, indication = null, onClick = onExpand),
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .sharedElementWithCallerManagedVisibility(
                                sharedContentState = sharedState,
                                visible = sharedElementsVisible,
                                clipInOverlayDuringTransition = OverlayClip(artShape),
                            )
                            .clip(artShape)
                            .background(Color(0xFF1E293B)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!track.artworkUri.isNullOrBlank()) {
                            AsyncImage(
                                model = track.artworkUri,
                                contentDescription = track.album,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.matchParentSize(),
                            )
                        } else {
                            Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color(0xFF38BDF8))
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = track.title,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val rateLabel = if (track.sampleRateHz % 1000 == 0) {
                            "${track.sampleRateHz / 1000}k"
                        } else {
                            String.format(Locale.US, "%.1fk", track.sampleRateHz / 1000f)
                        }
                        Text(
                            text = "${track.artist} • ${track.codec.uppercase(Locale.US)} ${track.bitDepth}/$rateLabel",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    IconButton(onClick = onTogglePlayPause) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = onNext) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = Color.White,
                        )
                    }
                }
                val progress = if (state.durationMs > 0L) {
                    (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF38BDF8),
                    trackColor = Color(0xFF1E293B),
                )
            }
        }
    }
}
