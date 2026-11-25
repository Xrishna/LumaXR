package com.lumaxr.player.music

import android.media.audiofx.DynamicsProcessing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.abs

enum class DiagnosticAudioMode {
    NORMAL,
    MONO_SUM,
    CHANNEL_SWAP_L_R,
    OUT_OF_PHASE_DIFFERENCE,
}

data class AudioDspState(
    val enabled: Boolean = true,
    val bypassedForBitPerfect: Boolean = false,
    val bandGainsDb: List<Float> = List(15) { 0f },
    val preampDb: Float = 0f,
    val crossfeedEnabled: Boolean = false,
    val crossfeedStrength: Float = 0.35f,
    val nightModeDrcEnabled: Boolean = false,
    val karaokeVocalReduceStrength: Float = 0f,
    val diagnosticMode: DiagnosticAudioMode = DiagnosticAudioMode.NORMAL,
    val balanceLR: Float = 0f,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val activePresetName: String = "Flat Reference",
)

class AudioDspController {

    private val lock = Any()
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var attachedSessionId: Int = 0
    private var attachedChannels: Int = 2

    private val _state = MutableStateFlow(AudioDspState())
    val state: StateFlow<AudioDspState> = _state.asStateFlow()

    fun attachToSession(audioSessionId: Int, channelCount: Int = 2) {
        if (audioSessionId <= 0) return
        val clampedCh = channelCount.coerceIn(1, 8)
        synchronized(lock) {
            if (attachedSessionId == audioSessionId && attachedChannels == clampedCh && dynamicsProcessing != null) return
            releaseLocked()
            runCatching {
                val bands = AutoEqParser.ISO_15_BANDS_HZ.size
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    clampedCh,
                    true, bands,
                    true, 3,
                    true, bands,
                    true,
                ).build()
                val dp = DynamicsProcessing(0, audioSessionId, config)
                val cur = _state.value
                dp.enabled = cur.enabled && !cur.bypassedForBitPerfect
                dynamicsProcessing = dp
                attachedSessionId = audioSessionId
                attachedChannels = clampedCh
                applyStateToHardwareLocked(dp, cur)
            }
        }
    }

    fun setBitPerfectBypass(bypass: Boolean) {
        synchronized(lock) {
            val next = _state.value.copy(bypassedForBitPerfect = bypass)
            _state.value = next
            dynamicsProcessing?.enabled = next.enabled && !bypass
        }
    }

    fun applyAutoEqPreset(preset: AutoEqPreset) {
        synchronized(lock) {
            val next = _state.value.copy(
                bandGainsDb = preset.bandGainsDb.toList(),
                preampDb = preset.preampDb,
                activePresetName = preset.name,
            )
            _state.value = next
            dynamicsProcessing?.let { applyStateToHardwareLocked(it, next) }
        }
    }

    fun updateBand(index: Int, gainDb: Float) {
        synchronized(lock) {
            val mutable = _state.value.bandGainsDb.toMutableList()
            if (index in mutable.indices) {
                mutable[index] = gainDb.coerceIn(-12f, 12f)
                val next = _state.value.copy(bandGainsDb = mutable, activePresetName = "Custom")
                _state.value = next
                dynamicsProcessing?.let { applyStateToHardwareLocked(it, next) }
            }
        }
    }

    fun updateToggles(
        enabled: Boolean = _state.value.enabled,
        crossfeedEnabled: Boolean = _state.value.crossfeedEnabled,
        crossfeedStrength: Float = _state.value.crossfeedStrength,
        nightModeDrcEnabled: Boolean = _state.value.nightModeDrcEnabled,
        karaokeStrength: Float = _state.value.karaokeVocalReduceStrength,
        diagnosticMode: DiagnosticAudioMode = _state.value.diagnosticMode,
        balanceLR: Float = _state.value.balanceLR,
    ) {
        synchronized(lock) {
            val next = _state.value.copy(
                enabled = enabled,
                crossfeedEnabled = crossfeedEnabled,
                crossfeedStrength = crossfeedStrength.coerceIn(0f, 1f),
                nightModeDrcEnabled = nightModeDrcEnabled,
                karaokeVocalReduceStrength = karaokeStrength.coerceIn(0f, 1f),
                diagnosticMode = diagnosticMode,
                balanceLR = balanceLR.coerceIn(-1f, 1f),
            )
            _state.value = next
            dynamicsProcessing?.let { dp ->
                dp.enabled = next.enabled && !next.bypassedForBitPerfect
                applyStateToHardwareLocked(dp, next)
            }
        }
    }

    fun updateSpeedAndPitch(speed: Float, pitch: Float) {
        synchronized(lock) {
            _state.value = _state.value.copy(
                speed = speed.coerceIn(0.5f, 2.0f),
                pitch = pitch.coerceIn(0.5f, 2.0f),
            )
        }
    }

    private fun applyStateToHardwareLocked(dp: DynamicsProcessing, s: AudioDspState) {
        runCatching {
            dp.setInputGainAllChannelsTo(s.preampDb)
            if (attachedChannels >= 2 && abs(s.balanceLR) > 0.01f) {
                val leftAttenDb = if (s.balanceLR > 0f) -24f * s.balanceLR else 0f
                val rightAttenDb = if (s.balanceLR < 0f) -24f * (-s.balanceLR) else 0f
                dp.setInputGainbyChannel(0, s.preampDb + leftAttenDb)
                dp.setInputGainbyChannel(1, s.preampDb + rightAttenDb)
            }

            val eq = DynamicsProcessing.Eq(true, true, AutoEqParser.ISO_15_BANDS_HZ.size)
            AutoEqParser.ISO_15_BANDS_HZ.forEachIndexed { idx, hz ->
                eq.setBand(idx, DynamicsProcessing.EqBand(true, hz, s.bandGainsDb.getOrElse(idx) { 0f }))
            }
            dp.setPreEqAllChannelsTo(eq)

            val mbc = DynamicsProcessing.Mbc(true, s.nightModeDrcEnabled, 3)
            val ratio = if (s.nightModeDrcEnabled) 3.5f else 1.0f
            val threshold = if (s.nightModeDrcEnabled) -24f else 0f
            val cutoffs = floatArrayOf(220f, 3400f, 20000f)
            for (b in 0..2) {
                mbc.setBand(
                    b,
                    DynamicsProcessing.MbcBand(
                        s.nightModeDrcEnabled,
                        cutoffs[b],
                        15f,
                        60f,
                        ratio,
                        threshold,
                        4f,
                        -38f,
                        1.0f,
                        if (b == 1 && s.nightModeDrcEnabled) 2.5f else 0f,
                        0f,
                    ),
                )
            }
            dp.setMbcAllChannelsTo(mbc)

            dp.setLimiterAllChannelsTo(
                DynamicsProcessing.Limiter(true, true, 0, 1.0f, 60f, 12.0f, -0.3f, 0f),
            )
        }
    }

    fun buildMpvLavfiChain(): String {
        val s = _state.value
        if (!s.enabled || s.bypassedForBitPerfect) return ""
        val filters = mutableListOf<String>()

        if (abs(s.preampDb) > 0.05f) {
            filters += String.format(Locale.US, "volume=%.2fdB", s.preampDb)
        }
        AutoEqParser.ISO_15_BANDS_HZ.forEachIndexed { idx, hz ->
            val gain = s.bandGainsDb.getOrElse(idx) { 0f }
            if (abs(gain) > 0.05f) {
                filters += String.format(Locale.US, "equalizer=f=%d:t=q:w=1.41:g=%.2f", hz.toInt(), gain)
            }
        }
        if (abs(s.balanceLR) > 0.02f) {
            val lGain = (1f - s.balanceLR).coerceIn(0f, 1f)
            val rGain = (1f + s.balanceLR).coerceIn(0f, 1f)
            filters += String.format(Locale.US, "pan=stereo|c0=%.2f*c0|c1=%.2f*c1", lGain, rGain)
        }
        if (s.crossfeedEnabled) {
            val fcut = (650 + (1f - s.crossfeedStrength) * 200).toInt()
            val feed = 2.0f + s.crossfeedStrength * 7.5f
            filters += String.format(Locale.US, "bs2b=fcut=%d:feed=%.1f", fcut, feed)
        }
        if (s.karaokeVocalReduceStrength > 0.02f) {
            val k = s.karaokeVocalReduceStrength.coerceIn(0f, 0.95f)
            val mixKeep = 1.0f - k * 0.85f
            filters += String.format(Locale.US, "stereotools=mlev=%.2f:slev=1.15", mixKeep)
        }
        when (s.diagnosticMode) {
            DiagnosticAudioMode.MONO_SUM -> filters += "pan=stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0+0.5*c1"
            DiagnosticAudioMode.CHANNEL_SWAP_L_R -> filters += "pan=stereo|c0=c1|c1=c0"
            DiagnosticAudioMode.OUT_OF_PHASE_DIFFERENCE -> filters += "pan=stereo|c0=0.5*c0-0.5*c1|c1=0.5*c1-0.5*c0"
            DiagnosticAudioMode.NORMAL -> Unit
        }
        if (s.nightModeDrcEnabled) {
            filters += "acompressor=threshold=-22dB:ratio=3.5:attack=15:release=120:makeup=2.5"
        }
        return filters.joinToString(",")
    }

    fun release() {
        synchronized(lock) { releaseLocked() }
    }

    private fun releaseLocked() {
        runCatching { dynamicsProcessing?.release() }
        dynamicsProcessing = null
        attachedSessionId = 0
        attachedChannels = 2
    }
}
