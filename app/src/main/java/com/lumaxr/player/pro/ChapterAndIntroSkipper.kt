package com.lumaxr.player.pro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    val offset: Int,
    val length: Int,
)

/**
 * Chapter & Intro/Recap/Credits Auto-Skipper + Jellyfin Trickplay / Roku BIF Storyboard Thumbnail Parser.
 */
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
        val t = title.trim().lowercase()
        return when {
            t == "op" || "opening" in t || "intro" in t -> SegmentKind.INTRO
            "recap" in t || "previously" in t -> SegmentKind.RECAP
            t == "ed" || "ending" in t || "credits" in t || "outro" in t -> SegmentKind.CREDITS
            "preview" in t || "next episode" in t -> SegmentKind.PREVIEW
            else -> null
        }
    }

    suspend fun fetchJellyfinSegments(
        serverBaseUrl: String,
        itemId: String,
        apiKey: String,
    ): List<MediaSegment> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${serverBaseUrl.trimEnd('/')}/MediaSegments/$itemId")
            .header("X-Emby-Token", apiKey)
            .build()
        runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val dto = json.decodeFromString<JfSegmentsResponse>(resp.body?.string().orEmpty())
                dto.Items.map { seg ->
                    val kind = when (seg.Type.lowercase()) {
                        "intro" -> SegmentKind.INTRO
                        "recap" -> SegmentKind.RECAP
                        "outro" -> SegmentKind.CREDITS
                        "preview" -> SegmentKind.PREVIEW
                        else -> SegmentKind.INTRO
                    }
                    // Jellyfin MediaSegments uses 10,000,000 ticks per second -> 10,000 ticks per ms
                    MediaSegment(
                        title = seg.Type,
                        startMs = seg.StartTicks / 10_000L,
                        endMs = seg.EndTicks / 10_000L,
                        kind = kind,
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Parses Roku BIF binary storyboard index (magic bytes 0x89 'B' 'I' 'F').
     */
    fun parseBifIndex(bytes: ByteArray): List<BifFrameEntry> {
        if (bytes.size < 64) return emptyList()
        if (bytes[0] != 0x89.toByte() || bytes[1] != 0x42.toByte() ||
            bytes[2] != 0x49.toByte() || bytes[3] != 0x46.toByte()
        ) {
            return emptyList()
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.getInt(12).coerceIn(0, 20000)
        val multiplierMs = buf.getInt(16).coerceAtLeast(1000)
        val entries = mutableListOf<BifFrameEntry>()
        var pos = 64
        for (i in 0 until count) {
            if (pos + 16 > bytes.size) break
            val ts = buf.getInt(pos).toLong() * multiplierMs.toLong()
            val offset = buf.getInt(pos + 4)
            val nextOffset = buf.getInt(pos + 12)
            entries += BifFrameEntry(ts, offset, (nextOffset - offset).coerceAtLeast(0))
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
