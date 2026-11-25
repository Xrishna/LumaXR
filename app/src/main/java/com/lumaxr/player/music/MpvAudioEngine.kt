package com.lumaxr.player.music

import android.content.Context
import is.xyz.mpv.MPV
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class MpvAudioState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val decoderLabel: String = "libmpv 64-bit Float (SoXR)",
    val audioSessionId: Int = 0,
)

/**
 * Engine 2 (libmpv): Niche Hi-Res & Lossless Studio Audio Path.
 * Handles DSD (.dsf/.dff), Dolby TrueHD/MLP (.thd/.mlp/.mka), DTS/DTS-HD MA (.dts),
 * WavPack (.wv), Monkey's Audio (.ape), Musepack (.mpc), and .cue sheet virtual album tracks.
 * Configured with vid=no, gapless-audio=yes, audio-resample-filter-size=32, 64-bit float internal mixing,
 * and optional SOFA HRTF binaural downmix when system Spatializer is inactive.
 */
class MpvAudioEngine(
    private val context: Context,
    private val dspController: AudioDspController,
) : MPV.EventObserver {

    private val configDir = File(context.filesDir, "mpv_audio_cfg").apply { mkdirs() }
    private val cacheDir = File(context.cacheDir, "mpv_audio_cache").apply { mkdirs() }

    private var mpv: MPV? = null
    private val _state = MutableStateFlow(MpvAudioState())
    val state: StateFlow<MpvAudioState> = _state.asStateFlow()

    var onTrackEnded: (() -> Unit)? = null

    private fun ensureInitialized() {
        if (mpv != null) return
        val instance = MPV(context, configDir.absolutePath, cacheDir.absolutePath)
        instance.nativeCreate(context)
        instance.setOptionString("vid", "no")
        instance.setOptionString("vo", "null")
        instance.setOptionString("gapless-audio", "yes")
        instance.setOptionString("audio-resample-filter-size", "32")
        instance.setOptionString("audio-format", "floatp")
        instance.setOptionString("replaygain", "track")
        instance.setOptionString("replaygain-clip", "yes")
        instance.setOptionString("keep-open", "no")
        instance.nativeInit()
        instance.initSession()
        instance.addObserver(this)
        instance.observeProperty("time-pos", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
        instance.observeProperty("duration", MPV.mpvFormat.MPV_FORMAT_DOUBLE)
        instance.observeProperty("pause", MPV.mpvFormat.MPV_FORMAT_FLAG)
        mpv = instance
    }

    fun playUri(
        uri: String,
        startMs: Long = 0L,
        endMs: Long = 0L,
        useSofaHrtf: Boolean = false,
        replayGainMode: ReplayGainMode = ReplayGainMode.TRACK,
    ) {
        ensureInitialized()
        val m = mpv ?: return
        m.setPropertyString(
            "replaygain",
            when (replayGainMode) {
                ReplayGainMode.OFF -> "no"
                ReplayGainMode.ALBUM -> "album"
                else -> "track"
            },
        )
        val lavfi = dspController.buildMpvLavfiChain()
        val sofaFilter = if (useSofaHrtf) "sofalizer" else ""
        val combined = listOf(lavfi, sofaFilter).filter { it.isNotBlank() }.joinToString(",")
        m.setPropertyString("af", if (combined.isNotBlank()) "lavfi=[$combined]" else "")
        m.setPropertyString("start", if (startMs > 0L) (startMs / 1000.0).toString() else "none")
        m.setPropertyString("end", if (endMs > 0L) (endMs / 1000.0).toString() else "none")
        m.command("loadfile", uri)
        _state.value = _state.value.copy(isPlaying = true)
    }

    fun setPaused(paused: Boolean) {
        mpv?.setPropertyBoolean("pause", paused)
        _state.value = _state.value.copy(isPlaying = !paused)
    }

    fun seekToMs(ms: Long) {
        mpv?.command("seek", (ms.coerceAtLeast(0L) / 1000.0).toString(), "absolute")
    }

    fun setVolume(vol: Float) {
        mpv?.setPropertyDouble("volume", (vol.coerceIn(0f, 1f) * 100.0))
    }

    fun setSpeedAndPitch(speed: Float, pitch: Float) {
        mpv?.setPropertyDouble("speed", speed.coerceIn(0.5f, 2.0f).toDouble())
        val semi = 12.0 * kotlin.math.ln(pitch.coerceIn(0.5f, 2.0f).toDouble()) / kotlin.math.ln(2.0)
        if (kotlin.math.abs(semi) > 0.05) {
            mpv?.setPropertyString("af", "rubberband=pitch-scale=${"%.3f".format(pitch)}")
        }
    }

    fun refreshLavfiChain(useSofaHrtf: Boolean) {
        val m = mpv ?: return
        val lavfi = dspController.buildMpvLavfiChain()
        val sofa = if (useSofaHrtf) "sofalizer" else ""
        val combined = listOf(lavfi, sofa).filter { it.isNotBlank() }.joinToString(",")
        m.setPropertyString("af", if (combined.isNotBlank()) "lavfi=[$combined]" else "")
    }

    override fun eventProperty(property: String) {}
    override fun eventProperty(property: String, value: Long) {}
    override fun eventProperty(property: String, value: String) {}
    override fun eventProperty(property: String, value: is.xyz.mpv.MPVNode) {}

    override fun eventProperty(property: String, value: Boolean) {
        if (property == "pause") {
            _state.value = _state.value.copy(isPlaying = !value)
        }
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> _state.value = _state.value.copy(positionMs = (value * 1000.0).toLong().coerceAtLeast(0L))
            "duration" -> _state.value = _state.value.copy(durationMs = (value * 1000.0).toLong().coerceAtLeast(0L))
        }
    }

    override fun event(eventId: Int, data: is.xyz.mpv.MPVNode) {
        if (eventId == MPV.mpvEvent.MPV_EVENT_END_FILE) {
            _state.value = _state.value.copy(isPlaying = false)
            onTrackEnded?.invoke()
        }
    }

    fun stop() {
        mpv?.command("stop")
        _state.value = _state.value.copy(isPlaying = false)
    }

    fun release() {
        mpv?.let {
            runCatching {
                it.removeObserver(this)
                it.destroySession()
                it.nativeDestroy()
                it.close()
            }
        }
        mpv = null
    }
}
