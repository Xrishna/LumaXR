package com.lumaxr.player.music

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.lumaxr.player.data.AudioTrackEntity
import com.lumaxr.player.data.MusicLibraryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MusicScannerRepository(
    context: Context,
    private val musicDao: MusicLibraryDao,
) {
    private val appContext = context.applicationContext
    private var observer: ContentObserver? = null

    fun registerIncrementalObserver(onChanged: () -> Unit) {
        if (observer != null) return
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onChanged()
            }
        }.also {
            appContext.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                it,
            )
        }
    }

    fun unregisterObserver() {
        observer?.let { runCatching { appContext.contentResolver.unregisterContentObserver(it) } }
        observer = null
    }

    suspend fun scanLibrary(extraFolders: List<File> = emptyList()): Int = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<AudioTrackEntity>()
        val cueBackingFiles = mutableSetOf<String>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.COMPOSER,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.BITRATE,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        runCatching {
            appContext.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} ASC",
            )?.use { cursor ->
                val colId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val colTitle = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val colArtist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val colAlbum = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val colAlbumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val colComposer = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.COMPOSER)
                val colYear = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val colTrack = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val colDur = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val colData = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val colMime = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val colBitrate = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.BITRATE)
                val colAdded = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val path = cursor.getString(colData).orEmpty()
                    val file = File(path)
                    val parentDir = file.parentFile
                    val animCover = findAnimatedCover(parentDir)
                    val albumArtUri = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"),
                        cursor.getLong(colAlbumId),
                    ).toString()
                    val mime = cursor.getString(colMime).orEmpty()
                    val ext = file.extension.lowercase(Locale.ROOT)
                    val tech = probeAudioFormatQuick(path, mime)

                    val trackUri = path.ifBlank {
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(colId)).toString()
                    }
                    discovered += AudioTrackEntity(
                        uri = trackUri,
                        title = cursor.getString(colTitle) ?: file.nameWithoutExtension,
                        artist = cursor.getString(colArtist) ?: "Unknown Artist",
                        album = cursor.getString(colAlbum) ?: "Unknown Album",
                        albumArtist = cursor.getString(colArtist) ?: "Unknown Artist",
                        composer = cursor.getString(colComposer).orEmpty(),
                        year = cursor.getInt(colYear),
                        trackNumber = cursor.getInt(colTrack) % 1000,
                        discNumber = (cursor.getInt(colTrack) / 1000).coerceAtLeast(1),
                        durationMs = cursor.getLong(colDur),
                        folderPath = parentDir?.absolutePath.orEmpty(),
                        codec = tech.codec.ifBlank { ext },
                        container = ext.uppercase(Locale.US),
                        sampleRateHz = tech.sampleRateHz,
                        bitDepth = tech.bitDepth,
                        channelCount = tech.channels,
                        bitrateKbps = (cursor.getInt(colBitrate) / 1000).coerceAtLeast(0),
                        isDolbyAtmos = tech.isAtmos,
                        isDolbyAc4 = tech.isAc4,
                        isDsd = ext == "dsf" || ext == "dff",
                        isLossless = ext in setOf("flac", "alac", "wav", "aiff", "wv", "ape", "dsf", "dff"),
                        artworkUri = albumArtUri,
                        animatedCoverPath = animCover,
                        dateAddedSec = cursor.getLong(colAdded),
                    )
                }
            }
        }

        val nicheExts = setOf("dsf", "dff", "thd", "mlp", "dts", "wv", "ape", "mpc", "mka", "ec3", "ac4")
        extraFolders.filter { it.exists() && it.isDirectory }.forEach { root ->
            root.walkTopDown().maxDepth(4).forEach { f ->
                val ext = f.extension.lowercase(Locale.ROOT)
                if (ext == "cue") {
                    runCatching {
                        val txt = CueSheetParser.decodeWithFallback(f.readBytes())
                        val previewEntries = CueSheetParser.parse(txt, f.parent.orEmpty(), 0L)
                        val fileDurations = previewEntries.map { it.filePath }.distinct().associateWith { backingPath ->
                            probeAudioFormatQuick(backingPath, "")
                        }
                        val entries = previewEntries.groupBy { it.filePath }.flatMap { (path, _) ->
                            val dur = fileDurations[path]?.durationMs ?: 0L
                            CueSheetParser.parse(txt, f.parent.orEmpty(), dur).filter { it.filePath == path }
                        }
                        entries.forEach { ce ->
                            val backingFile = File(ce.filePath)
                            if (!backingFile.exists()) return@forEach
                            cueBackingFiles += backingFile.absolutePath
                            val tech = fileDurations[ce.filePath]
                            val backingExt = backingFile.extension.lowercase(Locale.ROOT)
                            discovered += AudioTrackEntity(
                                uri = "${ce.filePath}#cue=${ce.trackNumber}",
                                title = ce.title,
                                artist = ce.performer,
                                album = ce.album,
                                albumArtist = ce.performer,
                                composer = "",
                                year = 0,
                                trackNumber = ce.trackNumber,
                                discNumber = 1,
                                durationMs = (ce.endMs - ce.index01Ms).coerceAtLeast(0L),
                                folderPath = f.parent.orEmpty(),
                                codec = "CUE/${backingExt.uppercase(Locale.US)}",
                                container = "CUE",
                                sampleRateHz = tech?.sampleRateHz ?: 44100,
                                bitDepth = tech?.bitDepth ?: 16,
                                channelCount = tech?.channels ?: 2,
                                bitrateKbps = 1411,
                                isDolbyAtmos = tech?.isAtmos ?: false,
                                isDolbyAc4 = tech?.isAc4 ?: false,
                                isDsd = backingExt == "dsf" || backingExt == "dff",
                                isLossless = true,
                                animatedCoverPath = findAnimatedCover(f.parentFile),
                                cueSheetSourceUri = ce.filePath,
                                cueStartMs = ce.index01Ms,
                                cueEndMs = ce.endMs,
                            )
                        }
                    }
                } else if (ext in nicheExts && discovered.none { it.uri == f.absolutePath }) {
                    val tech = probeAudioFormatQuick(f.absolutePath, "")
                    discovered += AudioTrackEntity(
                        uri = f.absolutePath,
                        title = f.nameWithoutExtension,
                        artist = "Unknown Artist",
                        album = f.parentFile?.name ?: "Hi-Res Folder",
                        albumArtist = "Unknown Artist",
                        composer = "",
                        year = 0,
                        trackNumber = 1,
                        discNumber = 1,
                        durationMs = tech.durationMs,
                        folderPath = f.parent.orEmpty(),
                        codec = ext.uppercase(Locale.US),
                        container = ext.uppercase(Locale.US),
                        sampleRateHz = tech.sampleRateHz,
                        bitDepth = tech.bitDepth,
                        channelCount = tech.channels,
                        bitrateKbps = 0,
                        isDolbyAtmos = ext == "ec3" || tech.isAtmos,
                        isDolbyAc4 = ext == "ac4" || tech.isAc4,
                        isDsd = ext == "dsf" || ext == "dff",
                        isLossless = ext in setOf("dsf", "dff", "thd", "mlp", "wv", "ape", "mpc"),
                        animatedCoverPath = findAnimatedCover(f.parentFile),
                    )
                }
            }
        }

        // Suppress duplicate whole-file entries when virtual CUE tracks exist for that backing file
        val deduped = discovered.filterNot { it.cueSheetSourceUri == null && it.uri in cueBackingFiles }
        if (deduped.isNotEmpty()) {
            val existingByUri = musicDao.getAllTracks().associateBy { it.uri }
            val merged = deduped.map { fresh ->
                val old = existingByUri[fresh.uri]
                if (old != null) {
                    fresh.copy(
                        playCount = old.playCount,
                        lastPlayedMs = old.lastPlayedMs,
                        isFavorite = old.isFavorite,
                    )
                } else fresh
            }
            musicDao.upsertTracks(merged)
        }
        deduped.size
    }

    private fun findAnimatedCover(dir: File?): String? {
        if (dir == null || !dir.isDirectory) return null
        val candidates = listOf("cover.mp4", "cover.webm", "cover.webp", "animated_cover.mp4")
        return candidates.map { File(dir, it) }.firstOrNull { it.exists() && it.length() > 0L }?.absolutePath
    }

    private data class QuickAudioTech(
        val codec: String,
        val sampleRateHz: Int,
        val bitDepth: Int,
        val channels: Int,
        val isAtmos: Boolean,
        val isAc4: Boolean,
        val durationMs: Long = 0L,
    )

    private fun probeAudioFormatQuick(path: String, fallbackMime: String): QuickAudioTech {
        if (path.isBlank()) return QuickAudioTech(fallbackMime.substringAfter('/'), 48000, 16, 2, false, false)
        val ext = File(path).extension.lowercase(Locale.ROOT)
        if (ext == "dsf" || ext == "dff") {
            return QuickAudioTech("DSD", 2822400, 1, 2, false, false)
        }
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(path)
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    val sr = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 48000
                    val ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                    val durMs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1000L else 0L
                    val pcmEnc = if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) else 0
                    val bits = when (pcmEnc) {
                        android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                        android.media.AudioFormat.ENCODING_PCM_32BIT, android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
                        android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
                        else -> if (ext in setOf("flac", "alac", "wv", "ape") && sr > 48000) 24 else 16
                    }
                    val isAtmos = mime.equals("audio/eac3-joc", true) || (mime.equals("audio/eac3", true) && ch > 6)
                    val isAc4 = mime.equals("audio/ac4", true)
                    return QuickAudioTech(mime.substringAfter('/'), sr, bits, ch, isAtmos, isAc4, durMs)
                }
            }
            QuickAudioTech(ext, 48000, 16, 2, false, false)
        } catch (_: Throwable) {
            QuickAudioTech(ext, 48000, 16, 2, false, false)
        } finally {
            runCatching { ex.release() }
        }
    }
}
