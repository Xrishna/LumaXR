package com.lumaxr.player

import com.google.common.truth.Truth.assertThat
import com.lumaxr.player.visuals.AudioFftAnalyzer
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioFftAnalyzerTest {

    @Test
    fun bassSineWavePeaksBassOverTrebleWithoutVisualizerPermission() {
        val analyzer = AudioFftAnalyzer()
        val sr = 48_000
        val samples = FloatArray(256) { i ->
            sin(2.0 * PI * 110.0 * i / sr).toFloat()
        }
        repeat(4) { analyzer.submitPcmSamples(samples, sr) }
        val frame = analyzer.frame.value
        assertThat(frame.bass).isGreaterThan(0.15f)
        assertThat(frame.bass).isGreaterThan(frame.treble)
        assertThat(frame.spectrumBars.any { it > 0.1f }).isTrue()
    }

    @Test
    fun highFrequencySineWavePeaksTrebleOverBass() {
        val analyzer = AudioFftAnalyzer()
        val sr = 48_000
        val samples = FloatArray(256) { i ->
            sin(2.0 * PI * 6000.0 * i / sr).toFloat()
        }
        repeat(4) { analyzer.submitPcmSamples(samples, sr) }
        val frame = analyzer.frame.value
        assertThat(frame.treble).isGreaterThan(0.15f)
        assertThat(frame.treble).isGreaterThan(frame.bass)
    }
}
