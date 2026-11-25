package com.lumaxr.player.visuals

import android.media.audiofx.Visualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.hypot
import kotlin.math.ln

data class AudioFftFrame(
    val bass: Float = 0f,     // 20 - 140 Hz
    val mid: Float = 0f,      // 140 - 2000 Hz
    val treble: Float = 0f,   // 2000 - 16000 Hz
    val spectrumBars: List<Float> = List(48) { 0f },
    val waveformSamples: List<Float> = List(64) { 0f },
)

/**
 * Low-latency Android Visualizer FFT & Waveform analyzer.
 * Strictly binds to prepared audioSessionId > 0, uses SCALING_MODE_NORMALIZED,
 * converts millihertz samplingRate / 1000f for accurate bin mapping, and disables before release().
 */
class AudioFftAnalyzer {

    private var visualizer: Visualizer? = null
    private var attachedSessionId: Int = 0

    private val _frame = MutableStateFlow(AudioFftFrame())
    val frame: StateFlow<AudioFftFrame> = _frame.asStateFlow()

    private var smoothBass = 0f
    private var smoothMid = 0f
    private var smoothTreble = 0f

    fun bindToSession(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        if (attachedSessionId == audioSessionId && visualizer != null) return
        release()
        runCatching {
            val v = Visualizer(audioSessionId)
            val range = Visualizer.getCaptureSizeRange()
            val targetSize = 1024.coerceIn(range[0], range[1])
            v.captureSize = Integer.highestOneBit(targetSize)
            v.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                        if (waveform == null || waveform.isEmpty()) return
                        val step = (waveform.size / 64).coerceAtLeast(1)
                        val wave = List(64) { idx ->
                            val raw = (waveform[(idx * step).coerceAtMost(waveform.lastIndex)].toInt() and 0xFF) - 128
                            (raw / 128f).coerceIn(-1f, 1f)
                        }
                        _frame.value = _frame.value.copy(waveformSamples = wave)
                    }

                    override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRateMilliHz: Int) {
                        if (fft == null || fft.size < 4) return
                        val sampleRateHz = (samplingRateMilliHz / 1000f).coerceAtLeast(8000f)
                        processFft(fft, sampleRateHz)
                    }
                },
                (Visualizer.getMaxCaptureRate() / 2).coerceAtLeast(10_000),
                true,
                true,
            )
            v.enabled = true
            visualizer = v
            attachedSessionId = audioSessionId
        }
    }

    private fun processFft(fft: ByteArray, sampleRateHz: Float) {
        val bins = fft.size / 2
        val binHz = sampleRateHz / fft.size.toFloat()
        var bassSum = 0f
        var bassCnt = 0
        var midSum = 0f
        var midCnt = 0
        var trebSum = 0f
        var trebCnt = 0

        val mags = FloatArray(bins)
        for (i in 1 until bins) {
            val re = fft[2 * i].toFloat()
            val im = fft[2 * i + 1].toFloat()
            val mag = (hypot(re, im) / 128f).coerceIn(0f, 1.5f)
            mags[i] = mag
            val freq = i * binHz
            when {
                freq in 20f..140f -> { bassSum += mag; bassCnt++ }
                freq in 140f..2000f -> { midSum += mag; midCnt++ }
                freq in 2000f..16000f -> { trebSum += mag; trebCnt++ }
            }
        }

        val rawBass = if (bassCnt > 0) bassSum / bassCnt else 0f
        val rawMid = if (midCnt > 0) midSum / midCnt else 0f
        val rawTreb = if (trebCnt > 0) trebSum / trebCnt else 0f

        smoothBass = smoothBass * 0.65f + rawBass * 0.35f
        smoothMid = smoothMid * 0.68f + rawMid * 0.32f
        smoothTreble = smoothTreble * 0.72f + rawTreb * 0.28f

        // 48 logarithmic frequency bars
        val bars = List(48) { b ->
            val t0 = b / 48f
            val t1 = (b + 1) / 48f
            val f0 = 30f * kotlin.math.exp(ln(16000f / 30f) * t0)
            val f1 = 30f * kotlin.math.exp(ln(16000f / 30f) * t1)
            val k0 = (f0 / binHz).toInt().coerceIn(1, bins - 1)
            val k1 = (f1 / binHz).toInt().coerceIn(k0, bins - 1)
            var peak = 0f
            for (k in k0..k1) if (mags[k] > peak) peak = mags[k]
            peak.coerceIn(0f, 1f)
        }

        _frame.value = _frame.value.copy(
            bass = smoothBass.coerceIn(0f, 1.5f),
            mid = smoothMid.coerceIn(0f, 1.5f),
            treble = smoothTreble.coerceIn(0f, 1.5f),
            spectrumBars = bars,
        )
    }

    fun release() {
        visualizer?.let { v ->
            runCatching {
                v.enabled = false
                v.release()
            }
        }
        visualizer = null
        attachedSessionId = 0
    }
}
