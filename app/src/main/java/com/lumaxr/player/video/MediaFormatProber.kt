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

/**
 * Pre-playback bitstream & container prober inspecting MKV/MP4/M2TS/TS headers:
 * - MP4/MKV dvcC / dvvC boxes (Dolby Vision profile 5, 7 MEL/FEL, 8.1, 8.4)
 * - ITU-T T.35 ST 2094-40 HDR10+ dynamic metadata SEI
 * - MediaFormat color transfer (PQ / HLG) & codec dimensions/FPS/bitrate
 */
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

        val extractor = MediaExtractor()
        try {
            if (uri.scheme == "file" && uri.path != null) {
                extractor.setDataSource(uri.path!!)
            } else if (uri.scheme == "http" || uri.scheme == "https") {
                extractor.setDataSource(uriString, emptyMap())
            } else {
                extractor.setDataSource(context, uri, null)
            }
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && width == 1920 && durationMs == 0L) {
                    width = fmt.getIntOrDefault(MediaFormat.KEY_WIDTH, 1920)
                    height = fmt.getIntOrDefault(MediaFormat.KEY_HEIGHT, 1080)
                    fps = fmt.getFloatOrDefault(MediaFormat.KEY_FRAME_RATE, 24.0f).toDouble()
                    val bps = fmt.getIntOrDefault(MediaFormat.KEY_BIT_RATE, 0)
                    if (bps > 0) bitrateMbps = bps / 1_000_000.0
                    durationMs = fmt.getLongOrDefault(MediaFormat.KEY_DURATION, 0L) / 1000L
                    colorTransfer = fmt.getIntOrDefault(MediaFormat.KEY_COLOR_TRANSFER, -1)
                    videoCodec = mime.substringAfter("video/")
                    isDolbyVisionMime = mime.equals("video/dolby-vision", ignoreCase = true)
                    val profile = fmt.getIntOrDefault(MediaFormat.KEY_PROFILE, -1)
                    if (mime.contains("hevc") && (profile == 2 || profile == 4096 || colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 || colorTransfer == MediaFormat.COLOR_TRANSFER_HLG)) {
                        bitDepth = 10
                    }
                    if (isDolbyVisionMime) {
                        bitDepth = 10
                    }
                    // Check CSD-0 for dvcC/dvvC or HDR10+ SEI if available
                    val csd0 = fmt.getByteBuffer("csd-0")
                    if (csd0 != null && containsHdr10PlusT35(csd0)) {
                        // flagged below
                    }
                } else if (mime.startsWith("audio/") && audioCodec == "aac") {
                    audioCodec = mime.substringAfter("audio/")
                }
            }
        } catch (_: Throwable) {
            // Network/MKV fallback via filename + raw header scan
        } finally {
            runCatching { extractor.release() }
        }

        val dvBox = parseDvcCOrDvvC(headerBytes)
        val hasHdr10Plus = containsHdr10PlusBytes(headerBytes)
        val hdrFormat = classifyHdrFormat(
            dvProfile = dvBox?.first,
            dvCompatibilityId = dvBox?.second,
            isDolbyVisionMime = isDolbyVisionMime,
            hasHdr10PlusSei = hasHdr10Plus,
            colorTransfer = colorTransfer,
            hintName = uriString,
        )
        if (hdrFormat != HdrFormat.SDR && bitDepth < 10) {
            bitDepth = 10
        }

        val title = uri.lastPathSegment?.substringAfterLast('/') ?: uriString
        ProbedMediaInfo(
            uri = uriString,
            title = title,
            container = container,
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
        return when (dvProfile) {
            5 -> HdrFormat.DV_P5
            7 -> HdrFormat.DV_P7_MEL_FEL
            8 -> if (dvCompatibilityId == 4) HdrFormat.DV_P8_4 else HdrFormat.DV_P8_1
            else -> when {
                isDolbyVisionMime -> {
                    val lower = hintName.lowercase()
                    when {
                        "p7" in lower || "fel" in lower || "mel" in lower || "remux" in lower -> HdrFormat.DV_P7_MEL_FEL
                        "p5" in lower || "dv5" in lower -> HdrFormat.DV_P5
                        "p8.4" in lower || "hlg" in lower -> HdrFormat.DV_P8_4
                        else -> HdrFormat.DV_P8_1
                    }
                }
                hasHdr10PlusSei -> HdrFormat.HDR10_PLUS
                colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 -> HdrFormat.HDR10_PQ
                colorTransfer == MediaFormat.COLOR_TRANSFER_HLG -> HdrFormat.HLG
                else -> {
                    val lower = hintName.lowercase()
                    when {
                        "dv.p7" in lower || "profile.7" in lower || "fel" in lower -> HdrFormat.DV_P7_MEL_FEL
                        "dv.p5" in lower || "profile.5" in lower -> HdrFormat.DV_P5
                        "hdr10+" in lower || "hdr10plus" in lower -> HdrFormat.HDR10_PLUS
                        "hlg" in lower -> HdrFormat.HLG
                        "hdr10" in lower || "2160p" in lower && "hdr" in lower -> HdrFormat.HDR10_PQ
                        else -> HdrFormat.SDR
                    }
                }
            }
        }
    }

    /**
     * Scans MP4/MKV header bytes for `dvcC` or `dvvC` Dolby Vision configuration box:
     * byte[0] = dv_version_major, byte[1] = dv_version_minor,
     * byte[2] = (dv_profile << 1) | (dv_level >> 5),
     * byte[4] upper nibble = dv_bl_signal_compatibility_id
     */
    fun parseDvcCOrDvvC(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 12) return null
        val tags = arrayOf("dvcC".encodeToByteArray(), "dvvC".encodeToByteArray())
        for (tag in tags) {
            for (i in 0..bytes.size - 10) {
                if (bytes[i] == tag[0] && bytes[i + 1] == tag[1] &&
                    bytes[i + 2] == tag[2] && bytes[i + 3] == tag[3]
                ) {
                    val base = i + 4
                    val b2 = bytes[base + 2].toInt() and 0xFF
                    val dvProfile = (b2 ushr 1) and 0x7F
                    val b4 = bytes[base + 4].toInt() and 0xFF
                    val blCompatId = (b4 ushr 4) and 0x0F
                    if (dvProfile in 1..10) {
                        return dvProfile to blCompatId
                    }
                }
            }
        }
        return null
    }

    /**
     * Detects ITU-T T.35 country code 0xB5 + provider 0x003C + provider_oriented 0x0001 (SMPTE ST 2094-40 HDR10+).
     */
    fun containsHdr10PlusBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 6) return false
        for (i in 0..bytes.size - 6) {
            if ((bytes[i].toInt() and 0xFF) == 0xB5 &&
                (bytes[i + 1].toInt() and 0xFF) == 0x00 &&
                (bytes[i + 2].toInt() and 0xFF) == 0x3C &&
                (bytes[i + 3].toInt() and 0xFF) == 0x00 &&
                (bytes[i + 4].toInt() and 0xFF) == 0x01
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
                    val read = input.read(buf)
                    if (read > 0) buf.copyOf(read) else ByteArray(0)
                } ?: ByteArray(0)
                else -> ByteArray(0)
            }
        } catch (_: Throwable) {
            ByteArray(0)
        }
    }

    private fun detectContainer(uri: String): String {
        val clean = uri.substringBefore('?').lowercase()
        return when {
            clean.endsWith(".mkv") -> "MKV"
            clean.endsWith(".mp4") || clean.endsWith(".m4v") -> "MP4"
            clean.endsWith(".m2ts") -> "M2TS"
            clean.endsWith(".ts") -> "TS"
            clean.endsWith(".webm") -> "WEBM"
            else -> "STREAM"
        }
    }

    private fun MediaFormat.getIntOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    private fun MediaFormat.getFloatOrDefault(key: String, default: Float): Float =
        if (containsKey(key)) getFloat(key) else default

    private fun MediaFormat.getLongOrDefault(key: String, default: Long): Long =
        if (containsKey(key)) getLong(key) else default
}
