package com.lumaxr.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumaxr.player.data.AudioTrackEntity
import kotlinx.serialization.json.Json
import java.util.Locale

@Composable
fun MusicLibraryScreen(
    viewModel: MainViewModel,
    listState: LazyListState,
    onPlayTracks: (List<AudioTrackEntity>, Int) -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    val allTracks by viewModel.audioTracks.collectAsState()
    val hiResDolby by viewModel.hiResDolbyTracks.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val listeningEvents by viewModel.listeningEvents.collectAsState()
    val totalMs by viewModel.totalListenedMs.collectAsState()

    val hiResListState = rememberLazyListState()
    val albumsGroups = remember(allTracks) { allTracks.groupBy { it.album } }
    val artistsGroups = remember(allTracks) { allTracks.groupBy { it.artist } }
    val foldersGroups = remember(allTracks) { allTracks.groupBy { it.folderPath.substringAfterLast('/') } }
    val tracksByUri = remember(allTracks) { allTracks.associateBy { it.uri } }

    val tabs = listOf("Tracks", "Albums", "Artists", "Folders", "Dolby & Hi-Res", "Playlists", "Insights")
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(top = 82.dp, bottom = 148.dp),
    ) {
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color(0xFF0B101B),
            contentColor = Color(0xFF38BDF8),
            edgePadding = 16.dp,
        ) {
            tabs.forEachIndexed { idx, label ->
                Tab(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    text = { Text(label) },
                )
            }
        }

        when (selectedTab) {
            0 -> TrackListPane(allTracks, listState) { idx ->
                onPlayTracks(allTracks, idx)
                onOpenNowPlaying()
            }
            1 -> GroupedAudioPane(albumsGroups) { groupTracks ->
                onPlayTracks(groupTracks, 0)
                onOpenNowPlaying()
            }
            2 -> GroupedAudioPane(artistsGroups) { groupTracks ->
                onPlayTracks(groupTracks, 0)
                onOpenNowPlaying()
            }
            3 -> GroupedAudioPane(foldersGroups) { groupTracks ->
                onPlayTracks(groupTracks, 0)
                onOpenNowPlaying()
            }
            4 -> TrackListPane(hiResDolby, hiResListState) { idx ->
                onPlayTracks(hiResDolby, idx)
                onOpenNowPlaying()
            }
            5 -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(playlists, key = { _, p -> p.playlistId }) { _, pl ->
                    val resolvedTracks = remember(pl.trackUrisJson, tracksByUri) {
                        val uris = runCatching {
                            Json.decodeFromString<List<String>>(pl.trackUrisJson)
                        }.getOrDefault(emptyList())
                        uris.mapNotNull { tracksByUri[it] }
                    }
                    Surface(
                        color = Color(0xFF111827),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = resolvedTracks.isNotEmpty()) {
                                onPlayTracks(resolvedTracks, 0)
                                onOpenNowPlaying()
                            },
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(pl.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${resolvedTracks.size} tracks • Custom / Imported Playlist",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
            6 -> Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Audiophile Listening Insights", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Total Playtime: ${totalMs / 60_000L} min • Scrobbled Events: ${listeningEvents.size}", color = Color(0xFF38BDF8))
                val dolbyCnt = listeningEvents.count { it.isDolby }
                val hiResCnt = listeningEvents.count { it.isHiRes }
                Text("Dolby Atmos/AC-4 Plays: $dolbyCnt • Hi-Res (>48kHz/24-bit/DSD) Plays: $hiResCnt", color = Color(0xFFCBD5E1))
            }
        }
    }
}

@Composable
private fun TrackListPane(
    tracks: List<AudioTrackEntity>,
    listState: LazyListState,
    onSelect: (Int) -> Unit,
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(tracks, key = { idx, t -> "${idx}_${t.uri}" }) { idx, track ->
            Surface(
                color = Color(0xFF111827),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(idx) },
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(track.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${track.artist} • ${track.album}", color = Color(0xFF94A3B8), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val badge = formatAudioBadge(track)
                    if (badge != null) {
                        FilterChip(
                            selected = true,
                            onClick = { onSelect(idx) },
                            label = { Text(badge, fontSize = 10.sp) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupedAudioPane(
    groups: Map<String, List<AudioTrackEntity>>,
    onPlayGroup: (List<AudioTrackEntity>) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(groups.entries.toList()) { _, entry ->
            Surface(
                color = Color(0xFF111827),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlayGroup(entry.value) },
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(entry.key.ifBlank { "Unknown" }, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text("${entry.value.size} tracks", color = Color(0xFF94A3B8), fontSize = 12.sp)
                }
            }
        }
    }
}

fun formatAudioBadge(track: AudioTrackEntity): String? {
    val rateStr = if (track.sampleRateHz % 1000 == 0) {
        "${track.sampleRateHz / 1000}"
    } else {
        String.format(Locale.US, "%.1f", track.sampleRateHz / 1000f)
    }
    return when {
        track.isDolbyAtmos -> "DOLBY ATMOS"
        track.isDolbyAc4 -> "AC-4"
        track.isDsd -> "DSD"
        track.sampleRateHz > 48000 || track.bitDepth > 16 -> "HI-RES ${track.bitDepth}/$rateStr"
        track.isLossless -> "LOSSLESS ${track.codec.uppercase(Locale.US)}"
        else -> null
    }
}
