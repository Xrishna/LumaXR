package com.lumaxr.player.lyrics

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

enum class LyricAgentRole {
    PRIMARY_V1,
    DUET_V2,
    GROUP_V1000,
}

data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(1L)
}

data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val agent: LyricAgentRole = LyricAgentRole.PRIMARY_V1,
    val words: List<LyricWord>,
    val backgroundVocalWords: List<LyricWord> = emptyList(),
    val translation: String? = null,
    val romanization: String? = null,
    val isBackgroundOnly: Boolean = false,
) {
    val fullText: String get() = words.joinToString("") { it.text }.trim()
}

data class ParsedLyricsDocument(
    val lines: List<LyricLine>,
    val isSyllableSynced: Boolean,
    val isLineSynced: Boolean,
    val sourceFormat: String,
    val songwriters: List<String> = emptyList(),
    val providerAttribution: String = "",
)

object WordSyncedLyricsParser {

    fun parseAuto(raw: String, providerAttribution: String = ""): ParsedLyricsDocument {
        val trimmed = raw.trimStart('\uFEFF').trim()
        if (trimmed.isEmpty()) {
            return ParsedLyricsDocument(emptyList(), isSyllableSynced = false, isLineSynced = false, sourceFormat = "EMPTY")
        }
        return when {
            trimmed.startsWith("<") && trimmed.contains("<tt", ignoreCase = true) ->
                runCatching { parseTtml(trimmed, providerAttribution) }
                    .getOrElse { ParsedLyricsDocument(emptyList(), false, false, "TTML_ERROR", providerAttribution = providerAttribution) }
            Regex("""\[\d+,\d+]\(\d+,\d+""").containsMatchIn(trimmed) ->
                parseYrc(trimmed, providerAttribution)
            Regex("""\[\d+,\d+].*\(\d+,\d+\)""").containsMatchIn(trimmed) ->
                parseQrc(trimmed, providerAttribution)
            Regex("""<\d{1,2}:\d{2}[.:]\d{2,3}>""").containsMatchIn(trimmed) ->
                parseEnhancedLrc(trimmed, providerAttribution)
            Regex("""\[\d{1,2}:\d{2}[.:]\d{2,3}]""").containsMatchIn(trimmed) ->
                parseStandardLrc(trimmed, providerAttribution)
            else -> {
                val plainLines = trimmed.lines().filter { it.isNotBlank() }.mapIndexed { idx, line ->
                    LyricLine(
                        startMs = idx * 4000L,
                        endMs = (idx + 1) * 4000L,
                        words = listOf(LyricWord(line.trim(), idx * 4000L, (idx + 1) * 4000L)),
                    )
                }
                ParsedLyricsDocument(plainLines, isSyllableSynced = false, isLineSynced = false, sourceFormat = "PLAIN", providerAttribution = providerAttribution)
            }
        }
    }

    private data class SpanFrame(
        val role: String?,
        val startMs: Long,
        val endMs: Long,
    )

    fun parseTtml(xml: String, providerAttribution: String = ""): ParsedLyricsDocument {
        val lines = mutableListOf<LyricLine>()
        val songwriters = mutableListOf<String>()
        var hasWordSpans = false

        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser = factory.newPullParser().apply { setInput(StringReader(xml)) }

        var inBody = false
        var inParagraph = false
        var pStartMs = 0L
        var pEndMs = 0L
        var pAgent = LyricAgentRole.PRIMARY_V1

        var currentWords = mutableListOf<LyricWord>()
        var currentBgWords = mutableListOf<LyricWord>()
        val translationBuf = StringBuilder()
        val romanBuf = StringBuilder()

        val spanStack = ArrayDeque<SpanFrame>()
        var inSongwriterTag = false

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.substringAfter(':').lowercase()
                    when (name) {
                        "body" -> inBody = true
                        "songwriter" -> inSongwriterTag = true
                        "p" -> if (inBody) {
                            inParagraph = true
                            pStartMs = parseTtmlTimestampMs(parser.getAttributeValue(null, "begin"))
                            val rawEnd = parser.getAttributeValue(null, "end")
                            val rawDur = parser.getAttributeValue(null, "dur")
                            pEndMs = when {
                                rawEnd != null -> parseTtmlTimestampMs(rawEnd)
                                rawDur != null -> pStartMs + parseTtmlTimestampMs(rawDur)
                                else -> 0L
                            }
                            val agentAttr = parser.getAttributeValue(null, "ttm:agent")
                                ?: parser.getAttributeValue(null, "agent")
                            pAgent = when (agentAttr?.lowercase()) {
                                "v2" -> LyricAgentRole.DUET_V2
                                "v1000" -> LyricAgentRole.GROUP_V1000
                                else -> LyricAgentRole.PRIMARY_V1
                            }
                            currentWords = mutableListOf()
                            currentBgWords = mutableListOf()
                            translationBuf.clear()
                            romanBuf.clear()
                            spanStack.clear()
                        }
                        "span" -> if (inParagraph) {
                            val parent = spanStack.lastOrNull()
                            val role = (parser.getAttributeValue(null, "ttm:role")
                                ?: parser.getAttributeValue(null, "role"))?.lowercase()
                                ?: parent?.role
                            val sBegin = parser.getAttributeValue(null, "begin")
                            val sEnd = parser.getAttributeValue(null, "end")
                            val startMs = if (sBegin != null) {
                                hasWordSpans = true
                                parseTtmlTimestampMs(sBegin)
                            } else parent?.startMs ?: pStartMs
                            val endMs = if (sEnd != null) {
                                hasWordSpans = true
                                parseTtmlTimestampMs(sEnd)
                            } else parent?.endMs ?: pEndMs
                            spanStack.addLast(SpanFrame(role, startMs, endMs))
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val txt = parser.text
                    if (inSongwriterTag && !txt.isNullOrBlank()) {
                        songwriters += txt.trim()
                    } else if (inParagraph && txt != null && txt.isNotEmpty()) {
                        val top = spanStack.lastOrNull()
                        when (top?.role) {
                            "x-translation" -> translationBuf.append(txt)
                            "x-roman" -> romanBuf.append(txt)
                            "x-bg" -> if (txt.isNotBlank() || currentBgWords.isNotEmpty()) {
                                val st = top.startMs
                                val en = top.endMs.coerceAtLeast(st + 50L)
                                currentBgWords += LyricWord(txt, st, en)
                            }
                            else -> if (txt.isNotBlank() || currentWords.isNotEmpty()) {
                                val st = top?.startMs ?: pStartMs
                                val en = (top?.endMs ?: pEndMs).coerceAtLeast(st + 60L)
                                currentWords += LyricWord(txt, st, en)
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.substringAfter(':').lowercase()
                    when (name) {
                        "songwriter" -> inSongwriterTag = false
                        "span" -> if (spanStack.isNotEmpty()) spanStack.removeLast()
                        "p" -> if (inParagraph) {
                            inParagraph = false
                            val resolvedEnd = when {
                                pEndMs > pStartMs -> pEndMs
                                currentWords.isNotEmpty() -> currentWords.maxOf { it.endMs }
                                else -> pStartMs + 2000L
                            }
                            val wordsFinal = if (currentWords.isNotEmpty()) currentWords
                            else synthesizeProportionalWords(currentBgWords.joinToString("") { it.text }, pStartMs, resolvedEnd)
                            if (wordsFinal.isNotEmpty() || currentBgWords.isNotEmpty()) {
                                lines += LyricLine(
                                    startMs = pStartMs,
                                    endMs = resolvedEnd,
                                    agent = pAgent,
                                    words = wordsFinal,
                                    backgroundVocalWords = currentBgWords,
                                    translation = translationBuf.toString().trim().takeIf { it.isNotEmpty() },
                                    romanization = romanBuf.toString().trim().takeIf { it.isNotEmpty() },
                                )
                            }
                        }
                        "body" -> inBody = false
                    }
                }
            }
            eventType = parser.next()
        }

        return ParsedLyricsDocument(
            lines = lines.sortedBy { it.startMs },
            isSyllableSynced = hasWordSpans,
            isLineSynced = lines.isNotEmpty(),
            sourceFormat = "TTML",
            songwriters = songwriters.distinct(),
            providerAttribution = providerAttribution,
        )
    }

    fun parseEnhancedLrc(raw: String, providerAttribution: String = ""): ParsedLyricsDocument {
        val linePattern = Regex("""^\[(\d{1,2}):(\d{2})[.:](\d{2,3})](.*)$""")
        val wordPattern = Regex("""<(\d{1,2}):(\d{2})[.:](\d{2,3})>([^<]*)""")
        val lines = mutableListOf<LyricLine>()

        for (rawLine in raw.lines()) {
            val lm = linePattern.find(rawLine.trim()) ?: continue
            val lineStart = lrcTagToMs(lm.groupValues[1], lm.groupValues[2], lm.groupValues[3])
            val body = lm.groupValues[4]
            val matches = wordPattern.findAll(body).toList()
            if (matches.isEmpty()) continue

            val words = mutableListOf<LyricWord>()
            val prefix = body.substring(0, matches.first().range.first)
            if (prefix.isNotBlank()) {
                val firstTagMs = lrcTagToMs(matches.first().groupValues[1], matches.first().groupValues[2], matches.first().groupValues[3])
                words += LyricWord(prefix, lineStart, firstTagMs.coerceAtLeast(lineStart + 40L))
            }
            for (i in matches.indices) {
                val m = matches[i]
                val wStart = lrcTagToMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                val wText = m.groupValues[4]
                val wEnd = if (i + 1 < matches.size) {
                    lrcTagToMs(matches[i + 1].groupValues[1], matches[i + 1].groupValues[2], matches[i + 1].groupValues[3])
                } else {
                    wStart + (wText.trim().length * 160L).coerceIn(250L, 1800L)
                }
                if (wText.isNotEmpty()) {
                    words += LyricWord(wText, wStart, wEnd.coerceAtLeast(wStart + 40L))
                }
            }
            if (words.isNotEmpty()) {
                lines += LyricLine(
                    startMs = lineStart,
                    endMs = words.last().endMs.coerceAtLeast(lineStart + 100L),
                    words = words,
                )
            }
        }

        return ParsedLyricsDocument(
            lines = lines.sortedBy { it.startMs },
            isSyllableSynced = true,
            isLineSynced = lines.isNotEmpty(),
            sourceFormat = "ELRC",
            providerAttribution = providerAttribution,
        )
    }

    fun parseStandardLrc(raw: String, providerAttribution: String = ""): ParsedLyricsDocument {
        val tagPattern = Regex("""\[(\d{1,2}):(\d{2})[.:](\d{2,3})]""")
        val entries = mutableListOf<Pair<Long, String>>()
        for (line in raw.lines()) {
            val matches = tagPattern.findAll(line).toList()
            if (matches.isEmpty()) continue
            val text = line.replace(tagPattern, "").trim()
            if (text.isEmpty()) continue
            for (m in matches) {
                val ms = lrcTagToMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                entries += ms to text
            }
        }
        val sorted = entries.sortedBy { it.first }
        val lines = sorted.mapIndexed { idx, (start, text) ->
            val end = sorted.getOrNull(idx + 1)?.first?.coerceAtLeast(start + 300L)
                ?: (start + (text.length * 90L).coerceIn(1500L, 6000L))
            LyricLine(
                startMs = start,
                endMs = end,
                words = synthesizeProportionalWords(text, start, end),
            )
        }
        return ParsedLyricsDocument(
            lines = lines,
            isSyllableSynced = false,
            isLineSynced = lines.isNotEmpty(),
            sourceFormat = "LRC",
            providerAttribution = providerAttribution,
        )
    }

    private fun parseYrc(raw: String, providerAttribution: String): ParsedLyricsDocument {
        val linePat = Regex("""^\[(\d+),(\d+)](.*)$""")
        val wordPat = Regex("""\((\d+),(\d+),\d+\)([^(]*)""")
        val lines = mutableListOf<LyricLine>()
        for (l in raw.lines()) {
            val lm = linePat.find(l.trim()) ?: continue
            val start = lm.groupValues[1].toLongOrNull() ?: continue
            val dur = lm.groupValues[2].toLongOrNull() ?: 2000L
            val words = wordPat.findAll(lm.groupValues[3]).map { m ->
                val ws = m.groupValues[1].toLongOrNull() ?: start
                val wd = m.groupValues[2].toLongOrNull() ?: 200L
                LyricWord(m.groupValues[3], ws, ws + wd.coerceAtLeast(30L))
            }.toList()
            if (words.isNotEmpty()) lines += LyricLine(start, start + dur, words = words)
        }
        val sorted = lines.sortedBy { it.startMs }
        return ParsedLyricsDocument(sorted, isSyllableSynced = sorted.isNotEmpty(), isLineSynced = sorted.isNotEmpty(), sourceFormat = "YRC", providerAttribution = providerAttribution)
    }

    private fun parseQrc(raw: String, providerAttribution: String): ParsedLyricsDocument {
        val linePat = Regex("""^\[(\d+),(\d+)](.*)$""")
        val wordPat = Regex("""([^(]+)\((\d+),(\d+)\)""")
        val lines = mutableListOf<LyricLine>()
        for (l in raw.lines()) {
            val lm = linePat.find(l.trim()) ?: continue
            val start = lm.groupValues[1].toLongOrNull() ?: continue
            val dur = lm.groupValues[2].toLongOrNull() ?: 2000L
            val words = wordPat.findAll(lm.groupValues[3]).map { m ->
                val ws = m.groupValues[2].toLongOrNull() ?: start
                val wd = m.groupValues[3].toLongOrNull() ?: 200L
                LyricWord(m.groupValues[1], ws, ws + wd.coerceAtLeast(30L))
            }.toList()
            if (words.isNotEmpty()) lines += LyricLine(start, start + dur, words = words)
        }
        val sorted = lines.sortedBy { it.startMs }
        return ParsedLyricsDocument(sorted, isSyllableSynced = sorted.isNotEmpty(), isLineSynced = sorted.isNotEmpty(), sourceFormat = "QRC", providerAttribution = providerAttribution)
    }

    fun synthesizeProportionalWords(lineText: String, startMs: Long, endMs: Long): List<LyricWord> {
        val tokens = Regex("""\S+\s*""").findAll(lineText).map { it.value }.toList()
        if (tokens.isEmpty()) return emptyList()
        val totalDur = (endMs - startMs).coerceAtLeast(100L)
        val totalWeight = tokens.sumOf { it.trim().length.coerceAtLeast(1) }.coerceAtLeast(1)
        var cursor = startMs
        return tokens.mapIndexed { idx, tok ->
            val w = tok.trim().length.coerceAtLeast(1)
            val slice = if (idx == tokens.lastIndex) (endMs - cursor).coerceAtLeast(1L)
            else ((totalDur * w) / totalWeight).coerceAtLeast(1L)
            val word = LyricWord(tok, cursor, (cursor + slice).coerceAtMost(endMs))
            cursor = word.endMs
            word
        }
    }

    fun parseTtmlTimestampMs(ts: String?): Long {
        if (ts.isNullOrBlank()) return 0L
        val s = ts.trim()
        if (s.endsWith("ms", ignoreCase = true)) {
            return s.dropLast(2).toDoubleOrNull()?.toLong() ?: 0L
        }
        if (s.endsWith("s", ignoreCase = true) && !s.contains(":")) {
            return ((s.dropLast(1).toDoubleOrNull() ?: 0.0) * 1000.0).toLong()
        }
        val parts = s.split(':')
        return when (parts.size) {
            4 -> {
                val h = parts[0].toLongOrNull() ?: 0L
                val m = parts[1].toLongOrNull() ?: 0L
                val sec = parts[2].toLongOrNull() ?: 0L
                val frames = parts[3].toLongOrNull() ?: 0L
                ((h * 3600L + m * 60L + sec) * 1000L) + (frames * 1000L / 30L)
            }
            3 -> {
                val h = parts[0].toLongOrNull() ?: 0L
                val m = parts[1].toLongOrNull() ?: 0L
                val sec = parts[2].toDoubleOrNull() ?: 0.0
                ((h * 3600L + m * 60L) * 1000L) + (sec * 1000.0).toLong()
            }
            2 -> {
                val m = parts[0].toLongOrNull() ?: 0L
                val sec = parts[1].toDoubleOrNull() ?: 0.0
                (m * 60_000L) + (sec * 1000.0).toLong()
            }
            else -> ((s.toDoubleOrNull() ?: 0.0) * 1000.0).toLong()
        }
    }

    private fun lrcTagToMs(mm: String, ss: String, frac: String): Long {
        val m = mm.toLongOrNull() ?: 0L
        val s = ss.toLongOrNull() ?: 0L
        val f = frac.toLongOrNull() ?: 0L
        val msPart = if (frac.length == 2) f * 10L else f
        return m * 60_000L + s * 1000L + msPart
    }
}
