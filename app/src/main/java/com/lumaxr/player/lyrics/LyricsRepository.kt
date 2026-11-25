package com.lumaxr.player.lyrics

import android.net.Uri
import com.lumaxr.player.data.AudioTrackEntity
import com.lumaxr.player.data.LyricsCacheDao
import com.lumaxr.player.data.LyricsCacheEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

@Serializable
private data class LrcLibResponse(
    val id: Long = 0,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

data class LoadedLyricsState(
    val document: ParsedLyricsDocument = ParsedLyricsDocument(emptyList(), false, false, "NONE"),
    val offsetMs: Int = 0,
    val provider: String = "",
    val trackKey: String = "",
)

class LyricsRepository(
    private val cacheDao: LyricsCacheDao,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    companion object {
        private const val USER_AGENT = "LumaXR/1.0 (https://github.com/Xrishna/LumaXR)"
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun buildTrackKey(track: AudioTrackEntity): String {
        val durSec = (track.durationMs / 1000L).coerceAtLeast(1L)
        val artist = track.artist.lowercase(Locale.ROOT).trim()
        val title = track.title.lowercase(Locale.ROOT).trim()
        return if (artist.isBlank() && title.isBlank()) track.uri else "$artist|$title|$durSec"
    }

    suspend fun loadLyricsForTrack(track: AudioTrackEntity): LoadedLyricsState = withContext(Dispatchers.IO) {
        val key = buildTrackKey(track)

        cacheDao.get(key)?.let { cached ->
            return@withContext LoadedLyricsState(
                document = WordSyncedLyricsParser.parseAuto(cached.rawPayload, cached.provider),
                offsetMs = cached.offsetMs,
                provider = cached.provider,
                trackKey = key,
            )
        }

        val sidecar = findLocalSidecarLyrics(track.uri.substringBefore("#cue="))
        if (sidecar != null) {
            val (raw, fmt) = sidecar
            cacheDao.upsert(LyricsCacheEntity(key, fmt, raw, "Local Sidecar"))
            return@withContext LoadedLyricsState(
                document = WordSyncedLyricsParser.parseAuto(raw, "Local Sidecar"),
                offsetMs = 0,
                provider = "Local Sidecar",
                trackKey = key,
            )
        }

        val amllRaw = fetchAmllOrLyricsPlusTtml(track.title, track.artist, track.durationMs)
        if (!amllRaw.isNullOrBlank()) {
            cacheDao.upsert(LyricsCacheEntity(key, "TTML", amllRaw, "AMLL TTML DB"))
            return@withContext LoadedLyricsState(
                document = WordSyncedLyricsParser.parseAuto(amllRaw, "AMLL TTML DB"),
                offsetMs = 0,
                provider = "AMLL TTML DB",
                trackKey = key,
            )
        }

        val lrcRes = fetchLrcLib(track.title, track.artist, track.album, (track.durationMs / 1000L).toInt())
        if (lrcRes != null) {
            val payload = lrcRes.syncedLyrics?.takeIf { it.isNotBlank() } ?: lrcRes.plainLyrics.orEmpty()
            if (payload.isNotBlank()) {
                val fmt = if (!lrcRes.syncedLyrics.isNullOrBlank()) "LRC" else "PLAIN"
                cacheDao.upsert(LyricsCacheEntity(key, fmt, payload, "LRCLIB"))
                return@withContext LoadedLyricsState(
                    document = WordSyncedLyricsParser.parseAuto(payload, "LRCLIB"),
                    offsetMs = 0,
                    provider = "LRCLIB",
                    trackKey = key,
                )
            }
        }

        LoadedLyricsState(trackKey = key)
    }

    suspend fun searchManual(trackKey: String, query: String): LoadedLyricsState = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext LoadedLyricsState(trackKey = trackKey)
        val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", cleanQuery)
            .build()
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val list = runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val body = resp.body?.string().orEmpty()
                json.decodeFromString<List<LrcLibResponse>>(body)
            }
        }.getOrDefault(emptyList())

        val best = list.firstOrNull { !it.syncedLyrics.isNullOrBlank() } ?: list.firstOrNull()
        val payload = best?.syncedLyrics?.takeIf { it.isNotBlank() } ?: best?.plainLyrics.orEmpty()
        if (payload.isNotBlank()) {
            val fmt = if (!best?.syncedLyrics.isNullOrBlank()) "LRC" else "PLAIN"
            cacheDao.upsert(LyricsCacheEntity(trackKey, fmt, payload, "LRCLIB Manual Match"))
            return@withContext LoadedLyricsState(
                document = WordSyncedLyricsParser.parseAuto(payload, "LRCLIB Manual Match"),
                offsetMs = 0,
                provider = "LRCLIB Manual Match",
                trackKey = trackKey,
            )
        }
        LoadedLyricsState(trackKey = trackKey)
    }

    suspend fun saveOffset(trackKey: String, offsetMs: Int) {
        val clamped = offsetMs.coerceIn(-3000, 3000)
        cacheDao.updateOffset(trackKey, clamped)
    }

    private fun findLocalSidecarLyrics(audioUriOrPath: String): Pair<String, String>? {
        val parsed = runCatching { Uri.parse(audioUriOrPath) }.getOrNull()
        val fsPath = when {
            parsed?.scheme == "file" -> parsed.path ?: return null
            parsed?.scheme == null -> audioUriOrPath
            else -> return null
        }
        val f = File(fsPath)
        val dir = f.parentFile ?: return null
        val base = f.nameWithoutExtension
        val ttml = File(dir, "$base.ttml")
        if (ttml.exists() && ttml.length() > 0L) return ttml.readText() to "TTML"
        val lrc = File(dir, "$base.lrc")
        if (lrc.exists() && lrc.length() > 0L) return lrc.readText() to "LRC"
        return null
    }

    private fun fetchAmllOrLyricsPlusTtml(title: String, artist: String, durationMs: Long): String? {
        val url = "https://lyricsplus.binimum.org/v2/lyrics/get".toHttpUrl().newBuilder()
            .addQueryParameter("title", title)
            .addQueryParameter("artist", artist)
            .addQueryParameter("duration", (durationMs / 1000L).toString())
            .addQueryParameter("source", "apple,lyricsplus")
            .build()
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                if (body.contains("<tt", ignoreCase = true)) body else null
            }
        }.getOrNull()
    }

    private fun fetchLrcLib(title: String, artist: String, album: String, durationSec: Int): LrcLibResponse? {
        val builder = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
        if (album.isNotBlank()) builder.addQueryParameter("album_name", album)
        if (durationSec in 1..3600) builder.addQueryParameter("duration", durationSec.toString())

        val req = Request.Builder().url(builder.build()).header("User-Agent", USER_AGENT).build()
        val exact = runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                json.decodeFromString<LrcLibResponse>(resp.body?.string().orEmpty())
            }
        }.getOrNull()
        if (exact != null && (!exact.syncedLyrics.isNullOrBlank() || !exact.plainLyrics.isNullOrBlank())) {
            return exact
        }

        val sUrl = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .build()
        val sReq = Request.Builder().url(sUrl).header("User-Agent", USER_AGENT).build()
        return runCatching {
            client.newCall(sReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val items = json.decodeFromString<List<LrcLibResponse>>(resp.body?.string().orEmpty())
                items.firstOrNull { !it.syncedLyrics.isNullOrBlank() } ?: items.firstOrNull()
            }
        }.getOrNull()
    }
}
