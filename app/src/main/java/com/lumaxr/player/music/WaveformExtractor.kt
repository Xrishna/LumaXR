package com.lumaxr.player.music

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.lumaxr.player.data.MusicLibraryDao
import com.lumaxr.player.data.WaveformCacheEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs

class WaveformExtractor(private val musicDao: MusicLibraryDao) {

    suspend fun getOrExtractPeaks(trackUri: String, binCount: Int = 120): List<Float> = withContext(Dispatchers.IO) {
        musicDao.getWaveform(trackUri)?.let { cached ->
            val parsed = cached.peaksCsv.split(",").mapNotNull { it.toFloatOrNull() }
            if (parsed.size == binCount) return@withContext parsed
        }

        val extracted = extractFromAudioFile(trackUri.substringBefore("#cue="), binCount)
        val csv = extracted.joinToString(",") { String.format(Locale.US, "%.3f", it) }
        musicDao.saveWaveform(WaveformCacheEntity(trackUri, csv))
        extracted
    }

    private fun extractFromAudioFile(path: String, binCount: Int): List<Float> {
        val bins = FloatArray(binCount) { 0.18f }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(path)
            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    format = f
                    break
                }
            }
            if (audioTrack < 0 || format == null) return bins.toList()
            extractor.selectTrack(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 180_000_000L

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            val info = MediaCodec.BufferInfo()
            val stepUs = (durUs / binCount).coerceAtLeast(100_000L)
            for (bin in 0 until binCount step 2) {
                extractor.seekTo(bin * stepUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                codec.flush()
                val inIdx = codec.dequeueInputBuffer(5000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx) ?: continue
                    val size = extractor.readSampleData(buf, 0)
                    if (size > 0) {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                    } else {
                        codec.queueInputBuffer(inIdx, 0, 0, 0L, 0)
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 8000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    val peak = if (outBuf != null && info.size > 2) measurePcm16Peak(outBuf, info.offset, info.size) else 0.35f
                    bins[bin] = peak.coerceIn(0.08f, 1.0f)
                    if (bin + 1 < binCount) bins[bin + 1] = (peak * 0.92f).coerceIn(0.08f, 1.0f)
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
            bins.toList()
        } catch (_: Throwable) {
            List(binCount) { i ->
                val env = 0.28f + 0.55f * abs(kotlin.math.sin(i * 0.24f) * kotlin.math.cos(i * 0.09f))
                env.coerceIn(0.12f, 0.98f)
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun measurePcm16Peak(buffer: ByteBuffer, offset: Int, sizeBytes: Int): Float {
        val dup = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        dup.position(offset)
        dup.limit((offset + sizeBytes).coerceAtMost(dup.capacity()))
        var maxAbs = 0
        val count = (dup.remaining() / 2).coerceAtMost(512)
        for (i in 0 until count) {
            if (dup.remaining() < 2) break
            val sample = abs(dup.short.toInt())
            if (sample > maxAbs) maxAbs = sample
        }
        return maxAbs / 32768.0f
    }
}
