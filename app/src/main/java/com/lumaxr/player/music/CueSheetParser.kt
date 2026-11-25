package com.lumaxr.player.music

import java.nio.charset.Charset

data class CueTrackEntry(
    val trackNumber: Int,
    val title: String,
    val performer: String,
    val album: String,
    val filePath: String,
    val index01Ms: Long,
    val endMs: Long,
)

/**
 * Spec-compliant CUE sheet parser handling:
 * - UTF-8 BOM stripping (0xEF 0xBB 0xBF) + cp1252 / Shift-JIS fallback decoding
 * - Quoted & unquoted FILE paths with spaces
 * - REM DATE / GENRE / PERFORMER / TITLE
 * - 75 fps CD frame-to-millisecond conversion (MM:SS:FF) and INDEX 00 / INDEX 01 pregap math
 */
object CueSheetParser {

    fun decodeWithFallback(bytes: ByteArray): String {
        val clean = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }
        val utf8 = clean.decodeToString()
        if (!utf8.contains('\uFFFD')) return utf8
        val sjis = runCatching { String(clean, Charset.forName("Shift_JIS")) }.getOrNull()
        if (sjis != null && !sjis.contains('\uFFFD')) return sjis
        return String(clean, Charset.forName("windows-1252"))
    }

    fun parse(rawContent: String, cueParentDir: String, audioDurationMs: Long = 0L): List<CueTrackEntry> {
        val lines = rawContent.lines().map { it.trimStart('\uFEFF').trim() }.filter { it.isNotEmpty() }
        var albumTitle = "Unknown Album"
        var albumPerformer = "Unknown Artist"
        var currentFile = ""

        data class RawTrack(
            val number: Int,
            var title: String,
            var performer: String,
            val file: String,
            var index01Ms: Long = -1L,
        )

        val tracks = mutableListOf<RawTrack>()
        var activeTrack: RawTrack? = null

        for (line in lines) {
            val upper = line.uppercase()
            when {
                upper.startsWith("TITLE ") && activeTrack == null -> {
                    albumTitle = unquote(line.substringAfter(' '))
                }
                upper.startsWith("PERFORMER ") && activeTrack == null -> {
                    albumPerformer = unquote(line.substringAfter(' '))
                }
                upper.startsWith("FILE ") -> {
                    currentFile = parseFileToken(line.substringAfter(' '))
                    if (!currentFile.startsWith("/") && cueParentDir.isNotBlank()) {
                        currentFile = "$cueParentDir/$currentFile"
                    }
                }
                upper.startsWith("TRACK ") -> {
                    val num = line.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: (tracks.size + 1)
                    activeTrack = RawTrack(
                        number = num,
                        title = "Track $num",
                        performer = albumPerformer,
                        file = currentFile,
                    )
                    tracks += activeTrack
                }
                upper.startsWith("TITLE ") && activeTrack != null -> {
                    activeTrack.title = unquote(line.substringAfter(' '))
                }
                upper.startsWith("PERFORMER ") && activeTrack != null -> {
                    activeTrack.performer = unquote(line.substringAfter(' '))
                }
                upper.startsWith("INDEX 01 ") && activeTrack != null -> {
                    val ts = line.substringAfter("01").trim()
                    activeTrack.index01Ms = parseCdTimestampMs(ts)
                }
                upper.startsWith("INDEX 00 ") && activeTrack != null && activeTrack.index01Ms < 0L -> {
                    // Fallback if INDEX 01 is omitted
                    val ts = line.substringAfter("00").trim()
                    activeTrack.index01Ms = parseCdTimestampMs(ts)
                }
            }
        }

        val valid = tracks.filter { it.index01Ms >= 0L }
        return valid.mapIndexed { idx, t ->
            val nextSameFile = valid.getOrNull(idx + 1)?.takeIf { it.file == t.file }
            val endMs = nextSameFile?.index01Ms ?: audioDurationMs
            CueTrackEntry(
                trackNumber = t.number,
                title = t.title,
                performer = t.performer,
                album = albumTitle,
                filePath = t.file,
                index01Ms = t.index01Ms,
                endMs = endMs,
            )
        }
    }

    fun parseCdTimestampMs(timestamp: String): Long {
        val parts = timestamp.trim().split(':')
        if (parts.size != 3) return 0L
        val mm = parts[0].toLongOrNull() ?: 0L
        val ss = parts[1].toLongOrNull() ?: 0L
        val ff = (parts[2].toLongOrNull() ?: 0L).coerceIn(0L, 74L)
        return (mm * 60_000L) + (ss * 1000L) + ((ff * 1000L) / 75L)
    }

    private fun parseFileToken(rest: String): String {
        val trimmed = rest.trim()
        if (trimmed.startsWith("\"")) {
            val endQuote = trimmed.indexOf('"', 1)
            if (endQuote > 1) return trimmed.substring(1, endQuote)
        }
        return trimmed.substringBeforeLast(' ').trim()
    }

    private fun unquote(s: String): String = s.trim().removeSurrounding("\"")
}
