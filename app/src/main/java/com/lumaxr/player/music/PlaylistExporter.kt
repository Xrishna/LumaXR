package com.lumaxr.player.music

import com.lumaxr.player.data.AudioTrackEntity
import com.lumaxr.player.data.MusicLibraryDao
import com.lumaxr.player.data.MusicPlaylistEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class ExportedPlaylistDto(
    val name: String,
    val tracks: List<String>,
)

/**
 * M3U8 and JSON Playlist Import & Export.
 */
class PlaylistExporter(private val musicDao: MusicLibraryDao) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun exportM3u8(playlistName: String, tracks: List<AudioTrackEntity>): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#PLAYLIST:$playlistName")
        for (t in tracks) {
            val sec = (t.durationMs / 1000L).coerceAtLeast(0L)
            appendLine("#EXTINF:$sec,${t.artist} - ${t.title}")
            appendLine(t.uri)
        }
    }

    fun exportJson(playlistName: String, tracks: List<AudioTrackEntity>): String {
        return json.encodeToString(ExportedPlaylistDto(playlistName, tracks.map { it.uri }))
    }

    suspend fun importM3u8OrJson(defaultName: String, rawText: String): MusicPlaylistEntity {
        val trimmed = rawText.trimStart('\uFEFF').trim()
        val uris: List<String>
        var resolvedName = defaultName

        if (trimmed.startsWith("{")) {
            val dto = json.decodeFromString<ExportedPlaylistDto>(trimmed)
            resolvedName = dto.name.ifBlank { defaultName }
            uris = dto.tracks
        } else {
            val list = mutableListOf<String>()
            for (line in trimmed.lines()) {
                val l = line.trim()
                if (l.startsWith("#PLAYLIST:")) {
                    resolvedName = l.substringAfter("#PLAYLIST:").trim().ifBlank { resolvedName }
                } else if (l.isNotEmpty() && !l.startsWith("#")) {
                    list += l
                }
            }
            uris = list
        }

        val entity = MusicPlaylistEntity(
            playlistId = UUID.randomUUID().toString(),
            name = resolvedName,
            trackUrisJson = json.encodeToString(uris),
        )
        musicDao.upsertPlaylist(entity)
        return entity
    }
}
