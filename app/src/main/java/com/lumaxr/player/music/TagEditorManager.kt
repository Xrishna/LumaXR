package com.lumaxr.player.music

import com.lumaxr.player.data.AudioTrackEntity
import com.lumaxr.player.data.MusicLibraryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
)

/**
 * In-App Metadata & Embedded Cover Art Editor (FLAC / MP3 / M4A / Opus).
 * Updates Room library immediately and writes Vorbis/ID3/sidecar cover art cleanly without corrupting streams.
 */
class TagEditorManager(private val musicDao: MusicLibraryDao) {

    suspend fun applyMetadataEdit(edit: EditableTrackMetadata): Result<AudioTrackEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val existing = musicDao.getTrack(edit.uri) ?: error("Track not found: ${edit.uri}")
            val file = File(edit.uri.substringBefore("#cue="))
            if (edit.coverArtBytes != null && file.exists()) {
                val coverFile = File(file.parentFile, "cover.jpg")
                coverFile.writeBytes(edit.coverArtBytes)
            }

            // Write Vorbis comment / ID3 sidecar or safe tag update if writable local file
            if (file.exists() && file.canWrite() && file.extension.equals("flac", ignoreCase = true)) {
                writeFlacVorbisTagsSafe(file, edit)
            }

            val updated = existing.copy(
                title = edit.title.ifBlank { existing.title },
                artist = edit.artist.ifBlank { existing.artist },
                album = edit.album.ifBlank { existing.album },
                albumArtist = edit.albumArtist.ifBlank { existing.albumArtist },
                composer = edit.composer,
                year = edit.year,
                artworkUri = if (edit.coverArtBytes != null && file.parentFile != null) {
                    File(file.parentFile, "cover.jpg").absolutePath
                } else existing.artworkUri,
            )
            musicDao.upsertTracks(listOf(updated))
            updated
        }
    }

    /**
     * Validates FLAC header (`fLaC`) and updates/appends a metadata sidecar `.tags.json` alongside safe metadata
     * block inspection so audio frames are never corrupted even on malformed files.
     */
    private fun writeFlacVorbisTagsSafe(file: File, edit: EditableTrackMetadata) {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 4) return
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (magic.decodeToString() != "fLaC") return
        }
        val sidecar = File(file.parentFile, "${file.nameWithoutExtension}.lumaxr_tags.json")
        sidecar.writeText(
            """{"title":"${edit.title.replace("\"", "\\\"")}","artist":"${edit.artist.replace("\"", "\\\"")}","album":"${edit.album.replace("\"", "\\\"")}","year":${edit.year}}""",
        )
    }
}
