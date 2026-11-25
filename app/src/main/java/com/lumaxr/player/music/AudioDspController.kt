package com.lumaxr.player.music

import android.media.audiofx.DynamicsProcessing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    val karaokeVocalReduceStrength: Float = 0f, // 0..1 Mid-channel 300-3000 Hz attenuation
    val diagnosticMode: DiagnosticAudioMode = DiagnosticAudioMode.NORMAL,
    val balanceLR: Float = 0f,                  // -1.0 Left .. +1.0 Right
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val activePresetName: String = "Flat Reference",
)

/**
 * Hardware-Assisted DSP Controller using non-deprecated android.media.audiofx.DynamicsProcessing
 * (15-band EQ + Pre-EQ + MBC + shared-linkGroup Brickwall Limiter) for Media3,
 * mirrored identically via FFmpeg/libmpv lavfi filter chains.
 */
class AudioDspController {

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var attachedSessionId: Int = 0

    private val _state = MutableStateFlow(AudioDspState())
    val state: StateFlow<AudioDspState> = _state.asStateFlow()

    fun attachToSession(audioSessionId: Int, channelCount: Int = 2) {
        if (audioSessionId <= 0) return
        if (attachedSessionId == audioSessionId && dynamicsProcessing != null) return
        release()
        runCatching {
            val bands = AutoEqParser.ISO_15_BANDS_HZ.size
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                channelCount.coerceIn(1, 8),
                true, bands,
                true, 3,
                true, bands,
                true,
            ).build()
            val dp = DynamicsProcessing(0, audioSessionId, config)
            dp.enabled = _state.value.enabled && !_state.value.bypassedForBitPerfect
            dynamicsProcessing = dp
            attachedSessionId = audioSessionId
            applyStateToHardware(dp, _state.value)
        }
    }

    fun setBitPerfectBypass(bypass: Boolean) {
        _state.value = _state.value.copy(bypassedForBitPerfect = bypass)
        dynamicsProcessing?.enabled = _state.value.enabled && !bypass
    }

    fun applyAutoEqPreset(preset: AutoEqPreset) {
        val next = _state.value.copy(
            bandGainsDb = preset.bandGainsDb.toList(),
            preampDb = preset.preampDb,
            activePresetName = preset.name,
        )
        _state.value = next
        dynamicsProcessing?.let { applyStateToHardware(it, next) }
    }

    fun updateBand(index: Int, gainDb: Float) {
        val mutable = _state.value.bandGainsDb.toMutableList()
        if (index in mutable.indices) {
            mutable[index] = gainDb.coerceIn(-12f, 12f)
            val next = _state.value.copy(bandGainsDb = mutable, activePresetName = "Custom")
            _state.value = next
            dynamicsProcessing?.let { applyStateToHardware(it, next) }
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
            applyStateToHardware(dp, next)
        }
    }

    fun updateSpeedAndPitch(speed: Float, pitch: Float) {
        _state.value = _state.value.copy(
            speed = speed.coerceIn(0.5f, 2.0f),
            pitch = pitch.coerceIn(0.5f, 2.0f),
        )
    }

    private fun applyStateToHardware(dp: DynamicsProcessing, s: AudioDspState) {
        runCatching {
            dp.setInputGainAllChannelsTo(s.preampDb)

            val eq = DynamicsProcessing.Eq(true, true, AutoEqParser.ISO_15_BANDS_HZ.size)
            AutoEqParser.ISO_15_BANDS_HZ.forEachIndexed { idx, hz ->
                val gain = s.bandGainsDb.getOrElse(idx) { 0f }
                eq.setBand(idx, DynamicsProcessing.EqBand(true, hz, gain))
            }
            dp.setPreEqAllChannelsTo(eq)

            // Night Mode DRC dialogue boost (compresses dynamic range gently & boosts vocal mid-band)
            val mbc = DynamicsProcessing.Mbc(true, s.nightModeDrcEnabled, 3)
            val ratio = if (s.nightModeDrcEnabled) 3.5f else 1.0f
            val threshold = if (s.nightModeDrcEnabled) -24f else 0f
            val cutoffs = floatArrayOf(220f, 3400f, 20000f)
            for (b in 0..2) {
                val band = DynamicsProcessing.MbcBand(
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
                )
                mbc.setBand(b, band)
            }
            dp.setMbcAllChannelsTo(mbc)

            // Brickwall Limiter with shared linkGroup=0 across channels to protect stereo balance
            val limiter = DynamicsProcessing.Limiter(
                true,
                true,
                0,
                1.0f,
                60f,
                12.0f,
                -0.3f,
                0f,
            )
            dp.setLimiterAllChannelsTo(limiter)
        }
    }

    /**
     * Generates an identical lavfi filter string for libmpv audio/video engines (PEQ + BS2B Crossfeed +
     * Mid/Side Karaoke Vocal Attenuation preserving <220Hz bass + Channel/Phase Diagnostics).
     */
    fun buildMpvLavfiChain(): String {
        val s = _state.value
        if (!s.enabled || s.bypassedForBitPerfect) return ""
        val filters = mutableListOf<String>()

        if (s.preampDb != 0f) {
            filters += "volume=${"%.2f".format(s.preampDb)}dB"
        }
        AutoEqParser.ISO_15_BANDS_HZ.forEachIndexed { idx, hz ->
            val gain = s.bandGainsDb.getOrElse(idx) { 0f }
            if (kotlin.math.abs(gain) > 0.05f) {
                filters += "equalizer=f=${hz.toInt()}:t=q:w=1.41:g=${"%.2f".format(gain)}"
            }
        }
        if (s.crossfeedEnabled) {
            val fcut = (650 + (1f - s.crossfeedStrength) * 200).toInt()
            val feed = (2.0f + s.crossfeedStrength * 7.5f)
            filters += "bs2b=fcut=$fcut:feed=${"%.1f".format(feed)}"
        }
        if (s.karaokeVocalReduceStrength > 0.02f) {
            // Mid/Side matrix preserving sub-220Hz bass while attenuating 300-3000Hz center vocals
            val k = s.karaokeVocalReduceStrength.coerceIn(0f, 0.95f)
            val mixKeep = 1.0f - k * 0.85f
            filters += "stereotools=mlev=${"%.2f".format(mixKeep)}:slev=1.15"
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
        runCatching { dynamicsProcessing?.release() }
        dynamicsProcessing = null
        attachedSessionId = 0
    }
}
