package com.lumaxr.player.music

import android.content.Context
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

data class MpvAudioState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val decoderLabel: String = "libmpv 64-bit Float (SoXR)",
    val audioSessionId: Int = 0,
)

class MpvAudioEngine(
    context: Context,
    private val dspController: AudioDspController,
) : MPV.EventObserver {

    private val appContext = context.applicationContext
    private val configDir = File(appContext.filesDir, "mpv_audio_cfg").apply { mkdirs() }
    private val cacheDir = File(appContext.cacheDir, "mpv_audio_cache").apply { mkdirs() }

    private val lock = Any()
    private var mpv: MPV? = null
    private val _state = MutableStateFlow(MpvAudioState())
    val state: StateFlow<MpvAudioState> = _state.asStateFlow()

    @Volatile
    private var suppressNextEndFile = false

    @Volatile
    private var currentPitch = 1.0f

    var onTrackEnded: (() -> Unit)? = null

    private fun ensureInitializedLocked(): MPV? {
        mpv?.let { return it }
        val instance = MPV(appContext, configDir.absolutePath, cacheDir.absolutePath)
        return try {
            instance.nativeCreate(appContext)
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
            instance
        } catch (_: Throwable) {
            runCatching { instance.nativeDestroy(); instance.close() }
            null
        }
    }

    fun playUri(
        uri: String,
        startMs: Long = 0L,
        endMs: Long = 0L,
        useSofaHrtf: Boolean = false,
        replayGainMode: ReplayGainMode = ReplayGainMode.TRACK,
    ) {
        synchronized(lock) {
            val m = ensureInitializedLocked() ?: return
            suppressNextEndFile = _state.value.isPlaying
            m.setPropertyString(
                "replaygain",
                when (replayGainMode) {
                    ReplayGainMode.OFF -> "no"
                    ReplayGainMode.ALBUM -> "album"
                    else -> "track"
                },
            )
            applyCombinedAfLocked(m, useSofaHrtf)
            m.setPropertyString("start", if (startMs > 0L) String.format(Locale.US, "%.3f", startMs / 1000.0) else "none")
            m.setPropertyString("end", if (endMs > 0L) String.format(Locale.US, "%.3f", endMs / 1000.0) else "none")
            _state.value = _state.value.copy(isPlaying = true, positionMs = 0L, durationMs = 0L)
            m.command("loadfile", uri)
        }
    }

    fun setPaused(paused: Boolean) {
        synchronized(lock) {
            mpv?.setPropertyBoolean("pause", paused)
            _state.value = _state.value.copy(isPlaying = !paused)
        }
    }

    fun seekToMs(ms: Long) {
        synchronized(lock) {
            mpv?.command("seek", String.format(Locale.US, "%.3f", ms.coerceAtLeast(0L) / 1000.0), "absolute")
        }
    }

    fun setVolume(vol: Float) {
        synchronized(lock) {
            mpv?.setPropertyDouble("volume", vol.coerceIn(0f, 1f) * 100.0)
        }
    }

    fun setSpeedAndPitch(speed: Float, pitch: Float, useSofaHrtf: Boolean = false) {
        synchronized(lock) {
            currentPitch = pitch.coerceIn(0.5f, 2.0f)
            val m = mpv ?: return
            m.setPropertyDouble("speed", speed.coerceIn(0.5f, 2.0f).toDouble())
            applyCombinedAfLocked(m, useSofaHrtf)
        }
    }

    fun refreshLavfiChain(useSofaHrtf: Boolean) {
        synchronized(lock) {
            val m = mpv ?: return
            applyCombinedAfLocked(m, useSofaHrtf)
        }
    }

    private fun applyCombinedAfLocked(m: MPV, useSofaHrtf: Boolean) {
        val lavfi = dspController.buildMpvLavfiChain()
        val sofa = if (useSofaHrtf) "sofalizer" else ""
        val semi = 12.0 * ln(currentPitch.toDouble()) / ln(2.0)
        val pitchFilter = if (abs(semi) > 0.05) String.format(Locale.US, "rubberband=pitch-scale=%.3f", currentPitch) else ""

        val lavfiParts = listOf(lavfi, sofa).filter { it.isNotBlank() }.joinToString(",")
        val combined = listOfNotNull(
            lavfiParts.takeIf { it.isNotBlank() }?.let { "lavfi=[$it]" },
            pitchFilter.takeIf { it.isNotBlank() },
        ).joinToString(",")
        m.setPropertyString("af", combined)
    }

    override fun eventProperty(property: String) {}
    override fun eventProperty(property: String, value: Long) {}
    override fun eventProperty(property: String, value: String) {}
    override fun eventProperty(property: String, value: MPVNode) {}

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

    override fun event(eventId: Int, data: MPVNode) {
        if (eventId == MPV.mpvEvent.MPV_EVENT_END_FILE) {
            if (suppressNextEndFile) {
                suppressNextEndFile = false
                return
            }
            val reason = runCatching { data["reason"]?.asString() }.getOrNull()
            if (reason == null || reason == "eof") {
                _state.value = _state.value.copy(isPlaying = false)
                onTrackEnded?.invoke()
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            suppressNextEndFile = true
            mpv?.command("stop")
            _state.value = _state.value.copy(isPlaying = false, positionMs = 0L, durationMs = 0L)
        }
    }

    fun release() {
        synchronized(lock) {
            suppressNextEndFile = true
            mpv?.let {
                runCatching {
                    it.removeObserver(this)
                    it.destroySession()
                    it.nativeDestroy()
                    it.close()
                }
            }
            mpv = null
            _state.value = MpvAudioState()
        }
    }
}
