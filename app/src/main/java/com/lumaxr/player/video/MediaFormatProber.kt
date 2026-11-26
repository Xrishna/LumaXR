package com.lumaxr.player.video

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.Locale

enum class HdrFormat {
    DV_P5,
    DV_P7_MEL_FEL,
    DV_P8_1,
    DV_P8_4,
    HDR10_PLUS,
    HDR10_PQ,
    HLG,
    SDR,
}

data class ProbedMediaInfo(
    val uri: String,
    val title: String,
    val container: String,
    val hdrFormat: HdrFormat,
    val width: Int,
    val height: Int,
    val bitDepth: Int,
    val bitrateMbps: Double,
    val fps: Double,
    val videoCodec: String,
    val audioCodec: String,
    val durationMs: Long,
    val dvProfile: Int? = null,
    val dvBlSignalCompatibilityId: Int? = null,
    val hasHdr10PlusSei: Boolean = false,
)

class MediaFormatProber(private val context: Context) {

    suspend fun probe(uriString: String): ProbedMediaInfo = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        val container = detectContainer(uriString)
        val headerBytes = readHeaderSample(uri, maxBytes = 262_144)

        var width = 1920
        var height = 1080
        var fps = 24.0
        var bitrateMbps = 12.0
        var bitDepth = 8
        var videoCodec = "hevc"
        var audioCodec = "aac"
        var durationMs = 0L
        var colorTransfer = -1
        var isDolbyVisionMime = false
        var hasCsdHdr10Plus = false
        var hasCsdDvBox: Pair<Int, Int>? = null
        var bestVideoArea = -1

        val extractor = MediaExtractor()
        try {
            when {
                uri.scheme == null || uri.scheme == "file" -> extractor.setDataSource(uri.path ?: uriString)
                uri.scheme == "http" || uri.scheme == "https" -> extractor.setDataSource(uriString, emptyMap())
                else -> extractor.setDataSource(context, uri, null)
            }
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    val w = fmt.getIntSafe(MediaFormat.KEY_WIDTH, 1920)
                    val h = fmt.getIntSafe(MediaFormat.KEY_HEIGHT, 1080)
                    val area = w * h
                    if (area > bestVideoArea) {
                        bestVideoArea = area
                        width = w
                        height = h
                        fps = fmt.getFloatSafe(MediaFormat.KEY_FRAME_RATE, 24.0f).toDouble()
                        val bps = fmt.getIntSafe(MediaFormat.KEY_BIT_RATE, 0)
                        if (bps > 0) bitrateMbps = bps / 1_000_000.0
                        durationMs = fmt.getLongSafe(MediaFormat.KEY_DURATION, 0L) / 1000L
                        colorTransfer = fmt.getIntSafe(MediaFormat.KEY_COLOR_TRANSFER, -1)
                        videoCodec = mime.substringAfter("video/")
                        isDolbyVisionMime = mime.equals("video/dolby-vision", ignoreCase = true)
                        val profile = fmt.getIntSafe(MediaFormat.KEY_PROFILE, -1)
                        if (mime.contains("hevc") && (profile in setOf(2, 4096, 8192) ||
                                colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
                                colorTransfer == MediaFormat.COLOR_TRANSFER_HLG)
                        ) {
                            bitDepth = 10
                        }
                        if (isDolbyVisionMime) bitDepth = 10
                        val csd0 = fmt.getByteBuffer("csd-0")
                        if (csd0 != null) {
                            if (containsHdr10PlusT35(csd0)) hasCsdHdr10Plus = true
                            val arr = ByteArray(csd0.remaining().coerceAtMost(8192)).also { csd0.duplicate().get(it) }
                            if (hasCsdDvBox == null) hasCsdDvBox = parseDvcCOrDvvC(arr)
                        }
                    }
                } else if (mime.startsWith("audio/") && audioCodec == "aac") {
                    audioCodec = mime.substringAfter("audio/")
                }
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { extractor.release() }
        }

        val dvBox = parseDvcCOrDvvC(headerBytes) ?: hasCsdDvBox
        val hasHdr10Plus = hasCsdHdr10Plus || containsHdr10PlusBytes(headerBytes)
        val fileName = resolveDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: uriString.substringAfterLast('/')
        val resolvedContainer = if (container == "STREAM" && fileName.contains('.')) {
            detectContainer(fileName)
        } else {
            container
        }
        val hdrFormat = classifyHdrFormat(
            dvProfile = dvBox?.first,
            dvCompatibilityId = dvBox?.second,
            isDolbyVisionMime = isDolbyVisionMime,
            hasHdr10PlusSei = hasHdr10Plus,
            colorTransfer = colorTransfer,
            hintName = fileName,
        )
        if (hdrFormat != HdrFormat.SDR && bitDepth < 10) bitDepth = 10

        ProbedMediaInfo(
            uri = uriString,
            title = fileName,
            container = resolvedContainer,
            hdrFormat = hdrFormat,
            width = width,
            height = height,
            bitDepth = bitDepth,
            bitrateMbps = bitrateMbps,
            fps = fps,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            durationMs = durationMs,
            dvProfile = dvBox?.first,
            dvBlSignalCompatibilityId = dvBox?.second,
            hasHdr10PlusSei = hasHdr10Plus,
        )
    }

    fun classifyHdrFormat(
        dvProfile: Int?,
        dvCompatibilityId: Int?,
        isDolbyVisionMime: Boolean,
        hasHdr10PlusSei: Boolean,
        colorTransfer: Int,
        hintName: String = "",
    ): HdrFormat {
        val lower = hintName.lowercase(Locale.ROOT)
        return when (dvProfile) {
            5 -> HdrFormat.DV_P5
            7 -> HdrFormat.DV_P7_MEL_FEL
            8 -> if (dvCompatibilityId == 4) HdrFormat.DV_P8_4 else HdrFormat.DV_P8_1
            else -> when {
                isDolbyVisionMime -> when {
                    Regex("""\b(p7|fel|mel|remux)\b""").containsMatchIn(lower) -> HdrFormat.DV_P7_MEL_FEL
                    Regex("""\b(p5|dv5)\b""").containsMatchIn(lower) -> HdrFormat.DV_P5
                    Regex("""\b(p8\.4|hlg)\b""").containsMatchIn(lower) -> HdrFormat.DV_P8_4
                    else -> HdrFormat.DV_P8_1
                }
                hasHdr10PlusSei -> HdrFormat.HDR10_PLUS
                colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 -> HdrFormat.HDR10_PQ
                colorTransfer == MediaFormat.COLOR_TRANSFER_HLG -> HdrFormat.HLG
                else -> when {
                    Regex("""\b(dv\.?p7|profile\.?7|fel)\b""").containsMatchIn(lower) -> HdrFormat.DV_P7_MEL_FEL
                    Regex("""\b(dv\.?p5|profile\.?5)\b""").containsMatchIn(lower) -> HdrFormat.DV_P5
                    "hdr10+" in lower || "hdr10plus" in lower -> HdrFormat.HDR10_PLUS
                    Regex("""\bhlg\b""").containsMatchIn(lower) -> HdrFormat.HLG
                    Regex("""\bhdr10\b""").containsMatchIn(lower) -> HdrFormat.HDR10_PQ
                    else -> HdrFormat.SDR
                }
            }
        }
    }

    fun parseDvcCOrDvvC(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 12) return null
        val tags = arrayOf("dvcC".encodeToByteArray(), "dvvC".encodeToByteArray())
        for (tag in tags) {
            for (i in 0..bytes.size - 10) {
                if (bytes[i] == tag[0] && bytes[i + 1] == tag[1] &&
                    bytes[i + 2] == tag[2] && bytes[i + 3] == tag[3]
                ) {
                    val base = i + 4
                    val verMajor = bytes[base].toInt() and 0xFF
                    val verMinor = bytes[base + 1].toInt() and 0xFF
                    if (verMajor != 1 || verMinor != 0) continue
                    val b2 = bytes[base + 2].toInt() and 0xFF
                    val dvProfile = (b2 ushr 1) and 0x7F
                    val b4 = bytes[base + 4].toInt() and 0xFF
                    val blCompatId = (b4 ushr 4) and 0x0F
                    if (dvProfile in 1..10) return dvProfile to blCompatId
                }
            }
        }
        return null
    }

    fun containsHdr10PlusBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 7) return false
        for (i in 0..bytes.size - 7) {
            if ((bytes[i].toInt() and 0xFF) == 0xB5 &&
                (bytes[i + 1].toInt() and 0xFF) == 0x00 &&
                (bytes[i + 2].toInt() and 0xFF) == 0x3C &&
                (bytes[i + 3].toInt() and 0xFF) == 0x00 &&
                (bytes[i + 4].toInt() and 0xFF) == 0x01 &&
                (bytes[i + 5].toInt() and 0xFF) == 0x04
            ) {
                return true
            }
        }
        return false
    }

    private fun containsHdr10PlusT35(buf: ByteBuffer): Boolean {
        val dup = buf.duplicate()
        val arr = ByteArray(dup.remaining().coerceAtMost(8192))
        dup.get(arr)
        return containsHdr10PlusBytes(arr)
    }

    private fun readHeaderSample(uri: Uri, maxBytes: Int): ByteArray {
        return try {
            when (uri.scheme) {
                "file", null -> {
                    val file = File(uri.path ?: return ByteArray(0))
                    if (!file.exists()) return ByteArray(0)
                    RandomAccessFile(file, "r").use { raf ->
                        val len = raf.length().coerceAtMost(maxBytes.toLong()).toInt()
                        ByteArray(len).also { raf.readFully(it) }
                    }
                }
                "content" -> context.contentResolver.openInputStream(uri)?.use { input ->
                    val buf = ByteArray(maxBytes)
                    var total = 0
                    while (total < maxBytes) {
                        val read = input.read(buf, total, maxBytes - total)
                        if (read <= 0) break
                        total += read
                    }
                    buf.copyOf(total)
                } ?: ByteArray(0)
                else -> ByteArray(0)
            }
        } catch (_: Throwable) {
            ByteArray(0)
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        if (uri.scheme != "content") return null
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
    }

    private fun detectContainer(uri: String): String {
        val clean = uri.substringBefore('?').lowercase(Locale.ROOT)
        return when {
            clean.endsWith(".mkv") -> "MKV"
            clean.endsWith(".mp4") || clean.endsWith(".m4v") -> "MP4"
            clean.endsWith(".m2ts") -> "M2TS"
            clean.endsWith(".ts") -> "TS"
            clean.endsWith(".webm") -> "WEBM"
            else -> "STREAM"
        }
    }

    private fun MediaFormat.getIntSafe(key: String, default: Int): Int {
        if (!containsKey(key)) return default
        return when (getValueTypeForKey(key)) {
            MediaFormat.TYPE_INTEGER -> getInteger(key)
            MediaFormat.TYPE_FLOAT -> getFloat(key).toInt()
            MediaFormat.TYPE_LONG -> getLong(key).toInt()
            else -> default
        }
    }

    private fun MediaFormat.getFloatSafe(key: String, default: Float): Float {
        if (!containsKey(key)) return default
        return when (getValueTypeForKey(key)) {
            MediaFormat.TYPE_FLOAT -> getFloat(key)
            MediaFormat.TYPE_INTEGER -> getInteger(key).toFloat()
            MediaFormat.TYPE_LONG -> getLong(key).toFloat()
            else -> default
        }
    }

    private fun MediaFormat.getLongSafe(key: String, default: Long): Long {
        if (!containsKey(key)) return default
        return when (getValueTypeForKey(key)) {
            MediaFormat.TYPE_LONG -> getLong(key)
            MediaFormat.TYPE_INTEGER -> getInteger(key).toLong()
            else -> default
        }
    }
}
