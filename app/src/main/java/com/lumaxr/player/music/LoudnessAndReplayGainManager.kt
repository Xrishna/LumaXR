package com.lumaxr.player.music

import android.media.LoudnessCodecController
import android.media.MediaCodec
import android.os.Build
import android.os.Bundle
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

/**
 * Android 15+ (API 35+) LoudnessCodecController (CTA-2075) + ReplayGain 2.0 / EBU R128 / Opus Q7.8 manager.
 * Guarantees audioSessionId > 0 before creating LoudnessCodecController and prevents inter-sample clipping.
 */
class LoudnessAndReplayGainManager : AutoCloseable {

    private var loudnessController: LoudnessCodecController? = null
    private var attachedSessionId: Int = 0

    fun attachToSession(audioSessionId: Int) {
        if (audioSessionId <= 0) return // Guard: 0 / AUDIO_SESSION_ID_GENERATE throws IAE on API 35+
        if (attachedSessionId == audioSessionId && loudnessController != null) return
        close()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runCatching {
                loudnessController = LoudnessCodecController.create(
                    audioSessionId,
                    { it.run() },
                    object : LoudnessCodecController.OnLoudnessCodecUpdateListener {
                        override fun onLoudnessCodecUpdate(mediaCodec: MediaCodec, codecParams: Bundle): Bundle {
                            return codecParams
                        }
                    },
                )
                attachedSessionId = audioSessionId
            }
        }
    }

    fun registerCodec(codec: MediaCodec) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runCatching { loudnessController?.addMediaCodec(codec) }
        }
    }

    fun unregisterCodec(codec: MediaCodec) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runCatching { loudnessController?.removeMediaCodec(codec) }
        }
    }

    /**
     * Parses Vorbis/ID3 TXXX/MP4 ReplayGain strings (e.g. "-6.42 dB") OR RFC 7845 Opus R128 Q7.8 integer tags.
     */
    fun parseTags(rawTags: Map<String, String>): ReplayGainTags {
        val norm = rawTags.entries.associate { it.key.uppercase() to it.value.trim() }

        val r128Track = norm["R128_TRACK_GAIN"]?.toIntOrNull()?.let { (it / 256.0f) + 5.0f } // -23 LUFS -> -18 LUFS ref
        val r128Album = norm["R128_ALBUM_GAIN"]?.toIntOrNull()?.let { (it / 256.0f) + 5.0f }

        val rgTrack = parseDb(norm["REPLAYGAIN_TRACK_GAIN"]) ?: r128Track
        val rgAlbum = parseDb(norm["REPLAYGAIN_ALBUM_GAIN"]) ?: r128Album
        val tPeak = norm["REPLAYGAIN_TRACK_PEAK"]?.toFloatOrNull()
        val aPeak = norm["REPLAYGAIN_ALBUM_PEAK"]?.toFloatOrNull()

        return ReplayGainTags(rgTrack, rgAlbum, tPeak, aPeak)
    }

    /**
     * Computes effective linear amplitude multiplier [0..1+] with anti-clipping peak limiter.
     */
    fun computeEffectiveLinearGain(
        tags: ReplayGainTags,
        mode: ReplayGainMode,
        preampDb: Float,
        preventClipping: Boolean,
        isAlbumPlayback: Boolean,
    ): Float {
        if (mode == ReplayGainMode.OFF) return 1.0f
        val gainDb = when (mode) {
            ReplayGainMode.TRACK -> tags.trackGainDb
            ReplayGainMode.ALBUM -> tags.albumGainDb ?: tags.trackGainDb
            ReplayGainMode.AUTO -> if (isAlbumPlayback) (tags.albumGainDb ?: tags.trackGainDb) else tags.trackGainDb
            ReplayGainMode.OFF -> 0f
        } ?: 0f

        var linear = 10f.pow((gainDb + preampDb) / 20f)
        if (preventClipping) {
            val peak = (if (mode == ReplayGainMode.ALBUM) tags.albumPeak else tags.trackPeak) ?: 1.0f
            if (peak > 0f && linear * peak > 1.0f) {
                linear = min(linear, 0.99f / peak)
            }
        }
        return linear.coerceIn(0.05f, 4.0f)
    }

    private fun parseDb(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        return raw.replace("dB", "", ignoreCase = true).trim().toFloatOrNull()
    }

    override fun close() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runCatching { loudnessController?.close() }
        }
        loudnessController = null
        attachedSessionId = 0
    }
}
