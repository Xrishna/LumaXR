package com.lumaxr.player.music

import com.lumaxr.player.data.AudioTrackEntity
import com.lumaxr.player.data.ScrobbleQueueDao
import com.lumaxr.player.data.ScrobbleQueueEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.TreeMap

@Serializable
data class ScrobbleCredentials(
    val lastFmApiKey: String = "",
    val lastFmSecret: String = "",
    val lastFmSessionKey: String = "",
    val listenBrainzToken: String = "",
)

/**
 * Native Last.fm (AudioScrobbler 2.0) & ListenBrainz (/1/submit-listens) scrobbler with Room offline retry queue.
 */
class ScrobbleManager(
    private val dao: ScrobbleQueueDao,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { encodeDefaults = true }

    @Volatile
    var credentials = ScrobbleCredentials()

    suspend fun enqueueAndFlush(track: AudioTrackEntity) {
        val nowSec = System.currentTimeMillis() / 1000L
        dao.enqueue(
            ScrobbleQueueEntity(
                artist = track.artist,
                track = track.title,
                album = track.album,
                durationSec = (track.durationMs / 1000L).toInt().coerceAtLeast(30),
                timestampEpochSec = nowSec,
            ),
        )
        flushPending()
    }

    suspend fun flushPending() = withContext(Dispatchers.IO) {
        val creds = credentials
        if (creds.lastFmApiKey.isNotBlank() && creds.lastFmSessionKey.isNotBlank()) {
            val batch = dao.pendingLastFm().take(50)
            if (batch.isNotEmpty() && sendLastFmBatch(creds, batch)) {
                dao.markLastFmSent(batch.map { it.id })
            }
        }
        if (creds.listenBrainzToken.isNotBlank()) {
            val batch = dao.pendingListenBrainz().take(100)
            if (batch.isNotEmpty() && sendListenBrainzBatch(creds.listenBrainzToken, batch)) {
                dao.markListenBrainzSent(batch.map { it.id })
            }
        }
    }

    /**
     * Builds strict ASCII-sorted Last.fm api_sig (excluding `format` and `callback`).
     */
    fun buildLastFmApiSig(params: Map<String, String>, secret: String): String {
        val sorted = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER.let { Comparator { a, b -> a.compareTo(b) } })
        params.forEach { (k, v) ->
            if (k != "format" && k != "callback") sorted[k] = v
        }
        val raw = buildString {
            sorted.forEach { (k, v) -> append(k).append(v) }
            append(secret)
        }
        val md = MessageDigest.getInstance("MD5").digest(raw.encodeToByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    private fun sendLastFmBatch(creds: ScrobbleCredentials, items: List<ScrobbleQueueEntity>): Boolean {
        val params = mutableMapOf(
            "method" to "track.scrobble",
            "api_key" to creds.lastFmApiKey,
            "sk" to creds.lastFmSessionKey,
        )
        items.forEachIndexed { idx, item ->
            params["artist[$idx]"] = item.artist
            params["track[$idx]"] = item.track
            params["album[$idx]"] = item.album
            params["timestamp[$idx]"] = item.timestampEpochSec.toString()
            params["duration[$idx]"] = item.durationSec.toString()
        }
        val sig = buildLastFmApiSig(params, creds.lastFmSecret)
        val bodyBuilder = FormBody.Builder()
        params.forEach { (k, v) -> bodyBuilder.add(k, v) }
        bodyBuilder.add("api_sig", sig)
        bodyBuilder.add("format", "json")

        val req = Request.Builder()
            .url("https://ws.audioscrobbler.com/2.0/")
            .post(bodyBuilder.build())
            .build()
        return runCatching { client.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    @Serializable
    private data class LbTrackMeta(val artist_name: String, val track_name: String, val release_name: String)

    @Serializable
    private data class LbListen(val listened_at: Long, val track_metadata: LbTrackMeta)

    @Serializable
    private data class LbPayload(val listen_type: String, val payload: List<LbListen>)

    private fun sendListenBrainzBatch(token: String, items: List<ScrobbleQueueEntity>): Boolean {
        val dto = LbPayload(
            listen_type = if (items.size == 1) "single" else "import",
            payload = items.map {
                LbListen(
                    listened_at = it.timestampEpochSec,
                    track_metadata = LbTrackMeta(it.artist, it.track, it.album),
                )
            },
        )
        val body = json.encodeToString(dto).toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://api.listenbrainz.org/1/submit-listens")
            .header("Authorization", "Token $token")
            .post(body)
            .build()
        return runCatching { client.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
    }
}
