package com.lumaxr.player.music

import com.lumaxr.player.data.AudioTrackEntity
import com.lumaxr.player.data.MusicLibraryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile

data class EditableTrackMetadata(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val composer: String,
    val year: Int,
    val coverArtBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EditableTrackMetadata) return false
        return uri == other.uri && title == other.title && artist == other.artist &&
            album == other.album && albumArtist == other.albumArtist &&
            composer == other.composer && year == other.year &&
            (coverArtBytes contentEquals other.coverArtBytes)
    }

    override fun hashCode(): Int {
        var result = uri.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + (coverArtBytes?.contentHashCode() ?: 0)
        return result
    }
}

@Serializable
private data class SidecarTagDto(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val composer: String,
    val year: Int,
)

class TagEditorManager(private val musicDao: MusicLibraryDao) {

    private val json = Json { prettyPrint = true }

    suspend fun applyMetadataEdit(edit: EditableTrackMetadata): Result<AudioTrackEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val existing = musicDao.getTrack(edit.uri) ?: error("Track not found: ${edit.uri}")
            val file = File(edit.uri.substringBefore("#cue="))
            var writtenArtPath: String? = null
            if (edit.coverArtBytes != null && file.exists() && file.parentFile != null) {
                val coverFile = File(file.parentFile, "cover.jpg")
                coverFile.writeBytes(edit.coverArtBytes)
                writtenArtPath = coverFile.absolutePath
            }

            if (file.exists() && file.canWrite() && file.extension.equals("flac", ignoreCase = true)) {
                writeFlacSidecarTagsSafe(file, edit)
            }

            val updated = existing.copy(
                title = edit.title.ifBlank { existing.title },
                artist = edit.artist.ifBlank { existing.artist },
                album = edit.album.ifBlank { existing.album },
                albumArtist = edit.albumArtist.ifBlank { existing.albumArtist },
                composer = edit.composer,
                year = edit.year,
                artworkUri = writtenArtPath ?: existing.artworkUri,
            )
            musicDao.upsertTracks(listOf(updated))
            updated
        }
    }

    private fun writeFlacSidecarTagsSafe(file: File, edit: EditableTrackMetadata) {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 4) return
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (magic.decodeToString() != "fLaC") return
        }
        val sidecar = File(file.parentFile, "${file.nameWithoutExtension}.lumaxr_tags.json")
        sidecar.writeText(
            json.encodeToString(
                SidecarTagDto(edit.title, edit.artist, edit.album, edit.albumArtist, edit.composer, edit.year),
            ),
        )
    }
}
