package com.lumaxr.player.music

import android.media.LoudnessCodecController
import android.media.MediaCodec
import android.os.Build
import android.os.Bundle
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.min
import kotlin.math.pow

enum class ReplayGainMode {
    OFF,
    TRACK,
    ALBUM,
    AUTO,
}

data class ReplayGainTags(
    val trackGainDb: Float? = null,
    val albumGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
)

class LoudnessAndReplayGainManager : AutoCloseable {

    private val lock = Any()
    private var loudnessController: LoudnessCodecController? = null
    private var attachedSessionId: Int = 0
    private val trackedCodecs = Collections.newSetFromMap(WeakHashMap<MediaCodec, Boolean>())

    fun attachToSession(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        synchronized(lock) {
            if (attachedSessionId == audioSessionId && loudnessController != null) return
            closeLocked()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                runCatching {
                    val ctrl = LoudnessCodecController.create(
                        audioSessionId,
                        { it.run() },
                        object : LoudnessCodecController.OnLoudnessCodecUpdateListener {
                            override fun onLoudnessCodecUpdate(mediaCodec: MediaCodec, codecParams: Bundle): Bundle {
                                return codecParams
                            }
                        },
                    )
                    loudnessController = ctrl
                    attachedSessionId = audioSessionId
                    trackedCodecs.forEach { codec -> runCatching { ctrl.addMediaCodec(codec) } }
                }
            }
        }
    }

    fun registerCodec(codec: MediaCodec) {
        synchronized(lock) {
            trackedCodecs.add(codec)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                runCatching { loudnessController?.addMediaCodec(codec) }
            }
        }
    }

    fun unregisterCodec(codec: MediaCodec) {
        synchronized(lock) {
            trackedCodecs.remove(codec)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                runCatching { loudnessController?.removeMediaCodec(codec) }
            }
        }
    }

    fun parseTags(rawTags: Map<String, String>): ReplayGainTags {
        val norm = rawTags.entries.associate { it.key.uppercase(Locale.ROOT) to it.value.trim() }

        val r128Track = norm["R128_TRACK_GAIN"]?.toIntOrNull()?.let { (it / 256.0f) + 5.0f }
        val r128Album = norm["R128_ALBUM_GAIN"]?.toIntOrNull()?.let { (it / 256.0f) + 5.0f }

        val rgTrack = parseFiniteDb(norm["REPLAYGAIN_TRACK_GAIN"]) ?: r128Track
        val rgAlbum = parseFiniteDb(norm["REPLAYGAIN_ALBUM_GAIN"]) ?: r128Album
        val tPeak = norm["REPLAYGAIN_TRACK_PEAK"]?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
        val aPeak = norm["REPLAYGAIN_ALBUM_PEAK"]?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }

        return ReplayGainTags(rgTrack, rgAlbum, tPeak, aPeak)
    }

    fun computeEffectiveLinearGain(
        tags: ReplayGainTags,
        mode: ReplayGainMode,
        preampDb: Float,
        preventClipping: Boolean,
        isAlbumPlayback: Boolean,
    ): Float {
        if (mode == ReplayGainMode.OFF) return 1.0f
        val safePreamp = if (preampDb.isFinite()) preampDb.coerceIn(-24f, 24f) else 0f

        val useAlbum = (mode == ReplayGainMode.ALBUM || (mode == ReplayGainMode.AUTO && isAlbumPlayback)) &&
            tags.albumGainDb != null
        val gainDb = (if (useAlbum) tags.albumGainDb else tags.trackGainDb)?.takeIf { it.isFinite() } ?: 0f

        var linear = 10f.pow((gainDb + safePreamp) / 20f).coerceIn(0.05f, 4.0f)
        if (preventClipping) {
            val peak = (if (useAlbum) (tags.albumPeak ?: tags.trackPeak) else tags.trackPeak) ?: 1.0f
            if (peak.isFinite() && peak > 0f && linear * peak > 1.0f) {
                linear = min(linear, 0.99f / peak)
            }
        }
        return if (linear.isFinite()) linear.coerceAtLeast(0.001f) else 1.0f
    }

    private fun parseFiniteDb(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        return raw.replace("dB", "", ignoreCase = true).trim().toFloatOrNull()?.takeIf { it.isFinite() }
    }

    override fun close() {
        synchronized(lock) { closeLocked() }
    }

    private fun closeLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runCatching { loudnessController?.close() }
        }
        loudnessController = null
        attachedSessionId = 0
    }
}
