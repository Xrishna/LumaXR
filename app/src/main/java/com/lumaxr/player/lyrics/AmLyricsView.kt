package com.lumaxr.player.lyrics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.lumaxr.player.visuals.AgslAlbumBackdrop
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Native 120 Hz Apple Music Word-by-Word Lyrics View (`AmLyricsView`).
 * - Choreographer `withFrameNanos` interpolated clock anchored to player position & speed (freezes when paused)
 * - Continuous horizontal word sweep + elastic spring lift (-3dp / 1.045x) + >700ms held-note bloom
 * - Duet alignment (v1 left, v2 right), x-bg smaller sub-vocals, translation & romanization
 * - Instrumental interlude (> 4s gap) breathing progress dots
 * - Distance-graded RenderEffect depth-of-field blur that clears immediately when user scrolls + "Resume Sync" pill
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AmLyricsView(
    lyricsState: LoadedLyricsState,
    playerPositionMs: Long,
    isPlaying: Boolean,
    playbackSpeed: Float,
    style: LyricStyleConfig,
    onSeekToMs: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val doc = lyricsState.document
    val lines = doc.lines
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 120 Hz interpolated clock
    var interpolatedMs by remember { mutableLongStateOf(playerPositionMs) }
    LaunchedEffect(playerPositionMs, isPlaying, playbackSpeed) {
        interpolatedMs = playerPositionMs
        if (!isPlaying) return@LaunchedEffect
        var lastNanos = 0L
        while (true) {
            withFrameNanos { now ->
                if (lastNanos != 0L) {
                    val deltaMs = ((now - lastNanos) / 1_000_000.0 * playbackSpeed).toLong()
                    interpolatedMs += deltaMs.coerceIn(0L, 100L)
                }
                lastNanos = now
            }
        }
    }

    val effectiveClockMs = interpolatedMs + lyricsState.offsetMs
    val activeIdx = remember(lines, effectiveClockMs) {
        lines.indexOfLast { effectiveClockMs >= it.startMs }.coerceAtLeast(0)
    }

    var userDetachedScroll by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            userDetachedScroll = true
        }
    }

    LaunchedEffect(activeIdx, userDetachedScroll) {
        if (!userDetachedScroll && lines.isNotEmpty() && activeIdx in lines.indices) {
            listState.animateScrollToItem(index = activeIdx, scrollOffset = -180)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (lines.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No synced lyrics loaded",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 18.sp,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 96.dp),
                verticalArrangement = Arrangement.spacedBy(style.lineSpacingDp.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(lines, key = { idx, item -> "${idx}_${item.startMs}" }) { index, line ->
                    val isActive = effectiveClockMs in line.startMs..line.endMs ||
                        (index == activeIdx && effectiveClockMs >= line.startMs)
                    val distance = abs(index - activeIdx)
                    val blurPx = if (userDetachedScroll || isActive) {
                        0f
                    } else {
                        (distance * 2.2f * style.inactiveBlurStrength).coerceIn(0f, 10f)
                    }

                    val nextLine = lines.getOrNull(index + 1)
                    val gapAfterMs = if (nextLine != null) nextLine.startMs - line.endMs else 0L

                    val isRightAligned = line.agent == LyricAgentRole.DUET_V2
                    val align = when (line.agent) {
                        LyricAgentRole.DUET_V2 -> Alignment.End
                        LyricAgentRole.GROUP_V1000 -> Alignment.CenterHorizontally
                        LyricAgentRole.PRIMARY_V1 -> Alignment.Start
                    }

                    Column(
                        horizontalAlignment = align,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                userDetachedScroll = false
                                onSeekToMs((line.startMs - lyricsState.offsetMs).coerceAtLeast(0L))
                            }
                            .padding(vertical = 4.dp, horizontal = 6.dp)
                            .graphicsLayer {
                                renderEffect = AgslAlbumBackdrop.createBlurRenderEffect(blurPx)
                                alpha = if (isActive) 1f else if (userDetachedScroll) 0.78f else (0.58f - distance * 0.06f).coerceIn(0.24f, 0.65f)
                            },
                    ) {
                        FlowRow(
                            horizontalArrangement = if (isRightAligned) Arrangement.End else Arrangement.Start,
                        ) {
                            line.words.forEach { word ->
                                val progress = if (isActive) LyricGlowModifier.wordProgress(word, effectiveClockMs)
                                else if (effectiveClockMs > word.endMs) 1f else 0f
                                val isWordActive = effectiveClockMs in word.startMs..word.endMs
                                val bloom = LyricGlowModifier.heldNoteBloomFactor(word, effectiveClockMs, style)

                                val liftDp by animateFloatAsState(
                                    targetValue = if (isWordActive) -3.5f else 0f,
                                    animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
                                    label = "wordLift",
                                )
                                val scale by animateFloatAsState(
                                    targetValue = if (isWordActive) 1.045f + bloom * 0.02f else 1.0f,
                                    animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
                                    label = "wordScale",
                                )

                                val brush = LyricGlowModifier.buildWordSweepBrush(
                                    progress = if (doc.isLineSynced) progress else 1f,
                                    highlightColor = style.highlightColor,
                                )

                                Text(
                                    text = word.text,
                                    style = TextStyle(
                                        brush = brush,
                                        fontSize = style.fontSizeSp.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = (bloom * 0.03f).em,
                                        shadow = if (bloom > 0.05f) {
                                            Shadow(
                                                color = style.highlightColor.copy(alpha = (0.55f * bloom).coerceAtMost(0.95f)),
                                                blurRadius = 18f * bloom,
                                            )
                                        } else null,
                                    ),
                                    modifier = Modifier.graphicsLayer {
                                        translationY = liftDp * density
                                        scaleX = scale
                                        scaleY = scale
                                    },
                                )
                            }
                        }

                        if (line.backgroundVocalWords.isNotEmpty()) {
                            Spacer(Modifier.height(2.dp))
                            FlowRow {
                                line.backgroundVocalWords.forEach { bgWord ->
                                    val p = LyricGlowModifier.wordProgress(bgWord, effectiveClockMs)
                                    Text(
                                        text = bgWord.text,
                                        style = TextStyle(
                                            brush = LyricGlowModifier.buildWordSweepBrush(
                                                progress = p,
                                                highlightColor = style.highlightColor.copy(alpha = 0.78f),
                                                inactiveAlpha = 0.28f,
                                            ),
                                            fontSize = (style.fontSizeSp * 0.72f).sp,
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                    )
                                }
                            }
                        }

                        if (style.showRomanization && !line.romanization.isNullOrBlank()) {
                            Text(
                                text = line.romanization,
                                color = Color.White.copy(alpha = 0.58f),
                                fontSize = (style.fontSizeSp * 0.54f).sp,
                                textAlign = if (isRightAligned) TextAlign.End else TextAlign.Start,
                            )
                        }
                        if (style.showTranslation && !line.translation.isNullOrBlank()) {
                            Text(
                                text = line.translation,
                                color = Color(0xFFA5F3FC).copy(alpha = 0.78f),
                                fontSize = (style.fontSizeSp * 0.60f).sp,
                                textAlign = if (isRightAligned) TextAlign.End else TextAlign.Start,
                            )
                        }

                        // Instrumental interlude breathing dots (> 4s gap)
                        if (gapAfterMs > 4000L && effectiveClockMs in line.endMs..(nextLine?.startMs ?: line.endMs)) {
                            val gapFrac = ((effectiveClockMs - line.endMs).toFloat() / gapAfterMs.toFloat()).coerceIn(0f, 1f)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            ) {
                                repeat(3) { dot ->
                                    val dotPhase = ((gapFrac * 3f) - dot).coerceIn(0f, 1f)
                                    Box(
                                        modifier = Modifier
                                            .size((8 + 4 * dotPhase).dp)
                                            .clip(CircleShape)
                                            .background(style.highlightColor.copy(alpha = 0.3f + 0.65f * dotPhase)),
                                    )
                                }
                            }
                        }
                    }
                }

                if (doc.songwriters.isNotEmpty() || doc.providerAttribution.isNotBlank()) {
                    item(key = "attribution_footer") {
                        Column(modifier = Modifier.padding(top = 16.dp)) {
                            if (doc.songwriters.isNotEmpty()) {
                                Text(
                                    text = "Written by ${doc.songwriters.joinToString(", ")}",
                                    color = Color.White.copy(alpha = 0.45f),
                                    fontSize = 12.sp,
                                )
                            }
                            if (doc.providerAttribution.isNotBlank()) {
                                Text(
                                    text = "Source: ${doc.providerAttribution} (${if (doc.isSyllableSynced) "Syllable-Synced" else "Line-Synced"})",
                                    color = Color.White.copy(alpha = 0.40f),
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = userDetachedScroll && lines.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
        ) {
            FilledTonalButton(
                onClick = {
                    userDetachedScroll = false
                    scope.launch {
                        if (activeIdx in lines.indices) {
                            listState.animateScrollToItem(activeIdx, -180)
                        }
                    }
                },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color(0xCC1E293B),
                    contentColor = Color.White,
                ),
            ) {
                Surface(color = Color.Transparent) {
                    Text("Resume Sync")
                }
            }
        }
    }
}
