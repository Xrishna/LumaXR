package com.lumaxr.player.ui

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

@Composable
fun VideoLibraryScreen(
    viewModel: MainViewModel,
    gridState: LazyGridState,
    sharedScope: SharedTransitionScope,
    sharedElementsVisible: Boolean,
    onOpenVideo: (VideoLibraryItem) -> Unit,
) {
    val videos by viewModel.videos.collectAsState()
    var streamUrlInput by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(top = 86.dp, bottom = 148.dp, start = 20.dp, end = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = streamUrlInput,
                onValueChange = { streamUrlInput = it },
                placeholder = { Text("Paste MKV / MP4 / HTTP / SMB stream URL...") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = streamUrlInput.isNotBlank(),
                onClick = {
                    val url = streamUrlInput.trim()
                    if (url.isNotEmpty()) {
                        onOpenVideo(
                            VideoLibraryItem(
                                uri = url,
                                title = url.substringAfterLast('/').ifBlank { "Stream" },
                                folderPath = "",
                                durationMs = 0L,
                                width = 0,
                                height = 0,
                                sizeBytes = 0L,
                            ),
                        )
                    }
                },
            ) {
                Text("Play URL")
            }
            IconButton(onClick = { viewModel.refreshMediaLibraries() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Rescan", tint = Color(0xFF38BDF8))
            }
        }

        Spacer(Modifier.height(14.dp))

        if (videos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.Movie, contentDescription = null, tint = Color(0xFF475569), modifier = Modifier.padding(8.dp))
                    Text("No local videos found yet. Grant storage permission or paste a stream URL above.", color = Color(0xFF94A3B8))
                    OutlinedButton(onClick = { viewModel.refreshMediaLibraries() }) {
                        Text("Rescan Libraries")
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 260.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(videos, key = { it.uri }) { item ->
                    VideoHeroCard(
                        item = item,
                        sharedScope = sharedScope,
                        sharedElementsVisible = sharedElementsVisible,
                        onClick = { onOpenVideo(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoHeroCard(
    item: VideoLibraryItem,
    sharedScope: SharedTransitionScope,
    sharedElementsVisible: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(19.dp)

    with(sharedScope) {
        val sharedState = rememberSharedContentState(key = "video_${item.uri}")
        Surface(
            color = Color(0xFF111827),
            shape = shape,
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(10f)
                .lumaPressScale(interaction)
                .sharedElementWithCallerManagedVisibility(
                    sharedContentState = sharedState,
                    visible = sharedElementsVisible,
                    clipInOverlayDuringTransition = OverlayClip(shape),
                )
                .clip(shape)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 10f)
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF0F172A), Color(0xFF1E293B))),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayCircle,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.padding(12.dp),
                    )
                    if (item.height >= 2160) {
                        AssistChip(
                            onClick = onClick,
                            label = { Text("4K UHD", fontSize = 11.sp) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp),
                        )
                    }
                }
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = item.title,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    val subtitle = buildString {
                        if (item.width > 0 && item.height > 0) append("${item.width}×${item.height}")
                        val folder = item.folderPath.substringAfterLast('/')
                        if (folder.isNotBlank()) {
                            if (isNotEmpty()) append(" • ")
                            append(folder)
                        }
                        if (isEmpty()) append("Stream URL")
                    }
                    Text(
                        text = subtitle,
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
