package com.lumaxr.player.pro

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

enum class SegmentKind {
    INTRO,
    RECAP,
    CREDITS,
    PREVIEW,
}

data class MediaSegment(
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val kind: SegmentKind?,
)

data class BifFrameEntry(
    val timestampMs: Long,
    val offset: Long,
    val length: Int,
)

class ChapterAndIntroSkipper(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class JfSegmentDto(
        val Id: String = "",
        val Type: String = "",
        val StartTicks: Long = 0L,
        val EndTicks: Long = 0L,
    )

    @Serializable
    private data class JfSegmentsResponse(val Items: List<JfSegmentDto> = emptyList())

    fun classifyChapterTitle(title: String): SegmentKind? {
        val t = title.trim().lowercase(Locale.ROOT)
        return when {
            Regex("""\b(op|opening|intro)\b""").containsMatchIn(t) -> SegmentKind.INTRO
            Regex("""\b(recap|previously)\b""").containsMatchIn(t) -> SegmentKind.RECAP
            Regex("""\b(ed|ending|credits|outro)\b""").containsMatchIn(t) -> SegmentKind.CREDITS
            Regex("""\b(preview|next episode)\b""").containsMatchIn(t) -> SegmentKind.PREVIEW
            else -> null
        }
    }

    suspend fun fetchJellyfinSegments(
        serverBaseUrl: String,
        itemId: String,
        apiKey: String,
    ): List<MediaSegment> = withContext(Dispatchers.IO) {
        val url = serverBaseUrl.trimEnd('/').toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegment("MediaSegments")
            ?.addPathSegment(itemId)
            ?.build() ?: return@withContext emptyList()

        val req = Request.Builder()
            .url(url)
            .header("X-Emby-Token", apiKey)
            .build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val dto = json.decodeFromString<JfSegmentsResponse>(resp.body?.string().orEmpty())
                dto.Items.map { seg ->
                    val kind = when (seg.Type.lowercase(Locale.ROOT)) {
                        "intro" -> SegmentKind.INTRO
                        "recap" -> SegmentKind.RECAP
                        "outro" -> SegmentKind.CREDITS
                        "preview" -> SegmentKind.PREVIEW
                        else -> null
                    }
                    MediaSegment(
                        title = seg.Type,
                        startMs = seg.StartTicks / 10_000L,
                        endMs = seg.EndTicks / 10_000L,
                        kind = kind,
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun parseBifIndex(bytes: ByteArray): List<BifFrameEntry> {
        if (bytes.size < 64) return emptyList()
        if (bytes[0] != 0x89.toByte() || bytes[1] != 0x42.toByte() ||
            bytes[2] != 0x49.toByte() || bytes[3] != 0x46.toByte()
        ) {
            return emptyList()
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.getInt(12).coerceIn(0, 20000)
        val rawMultiplier = buf.getInt(16)
        val multiplierMs = if (rawMultiplier <= 0) 1000L else rawMultiplier.toLong()
        val entries = mutableListOf<BifFrameEntry>()
        var pos = 64
        for (i in 0 until count) {
            if (pos + 16 > bytes.size) break
            val ts = (buf.getInt(pos).toLong() and 0xFFFFFFFFL) * multiplierMs
            val offset = buf.getInt(pos + 4).toLong() and 0xFFFFFFFFL
            val nextOffset = buf.getInt(pos + 12).toLong() and 0xFFFFFFFFL
            if (offset <= bytes.size.toLong() && nextOffset >= offset && nextOffset <= bytes.size.toLong()) {
                entries += BifFrameEntry(ts, offset, (nextOffset - offset).toInt())
            }
            pos += 8
        }
        return entries
    }

    fun findSkipTargetMs(positionMs: Long, segments: List<MediaSegment>): MediaSegment? {
        return segments.firstOrNull { seg ->
            seg.kind != null && positionMs in seg.startMs until seg.endMs
        }
    }
}
