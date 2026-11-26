package com.lumaxr.player.visuals

import android.media.audiofx.Visualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

data class AudioFftFrame(
    val bass: Float = 0f,
    val mid: Float = 0f,
    val treble: Float = 0f,
    val spectrumBars: List<Float> = List(48) { 0f },
    val waveformSamples: List<Float> = List(64) { 0f },
)

class AudioFftAnalyzer {

    companion object {
        private const val FFT_SIZE = 256
    }

    private var visualizer: Visualizer? = null
    private var attachedSessionId: Int = 0

    @Volatile
    private var lastPcmTapMs: Long = 0L

    private val _frame = MutableStateFlow(AudioFftFrame())
    val frame: StateFlow<AudioFftFrame> = _frame.asStateFlow()

    private var smoothBass = 0f
    private var smoothMid = 0f
    private var smoothTreble = 0f

    private val hannWindow = FloatArray(FFT_SIZE) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))).toFloat()
    }
    private val realBuf = FloatArray(FFT_SIZE)
    private val imagBuf = FloatArray(FFT_SIZE)
    private val magsBuf = FloatArray(FFT_SIZE / 2)

    /**
     * Submits decoded mono PCM samples (`[-1f, 1f]`) directly from ExoPlayer's TeeAudioProcessor.
     * Requires zero runtime permissions (`RECORD_AUDIO` not needed).
     */
    @Synchronized
    fun submitPcmSamples(samples: FloatArray, sampleRateHz: Int) {
        if (samples.size < 64) return
        lastPcmTapMs = System.currentTimeMillis()
        val n = FFT_SIZE
        val count = samples.size.coerceAtMost(n)
        for (i in 0 until n) {
            val s = if (i < count) samples[i] else 0f
            realBuf[i] = s * hannWindow[i]
            imagBuf[i] = 0f
        }

        val wave = List(64) { idx ->
            val srcIdx = ((idx.toLong() * count) / 64L).toInt().coerceIn(0, count - 1)
            samples[srcIdx].coerceIn(-1f, 1f)
        }

        fftInPlace(realBuf, imagBuf)
        val half = n / 2
        val sr = sampleRateHz.coerceIn(8000, 384000).toFloat()
        val binHz = sr / n.toFloat()

        var bassSum = 0f
        var bassPeak = 0f
        var bassCnt = 0
        var midSum = 0f
        var midPeak = 0f
        var midCnt = 0
        var trebSum = 0f
        var trebPeak = 0f
        var trebCnt = 0

        for (i in 1 until half) {
            // Normalize windowed FFT magnitude (~0.25 * n scale factor for Hann window)
            val mag = (hypot(realBuf[i], imagBuf[i]) / (n * 0.25f)).coerceIn(0f, 1.5f)
            magsBuf[i] = mag
            val freq = i * binHz
            when {
                freq < 220f -> {
                    bassSum += mag
                    if (mag > bassPeak) bassPeak = mag
                    bassCnt++
                }
                freq < 2200f -> {
                    midSum += mag
                    if (mag > midPeak) midPeak = mag
                    midCnt++
                }
                freq <= 18000f -> {
                    trebSum += mag
                    if (mag > trebPeak) trebPeak = mag
                    trebCnt++
                }
            }
        }

        val rawBass = if (bassCnt > 0) (bassSum / bassCnt) * 0.4f + bassPeak * 0.6f else 0f
        val rawMid = if (midCnt > 0) (midSum / midCnt) * 0.4f + midPeak * 0.6f else 0f
        val rawTreb = if (trebCnt > 0) (trebSum / trebCnt) * 0.4f + trebPeak * 0.6f else 0f

        smoothBass = smoothBass * 0.60f + rawBass * 0.40f
        smoothMid = smoothMid * 0.62f + rawMid * 0.38f
        smoothTreble = smoothTreble * 0.66f + rawTreb * 0.34f

        val bars = List(48) { b ->
            val t0 = b / 48f
            val t1 = (b + 1) / 48f
            val f0 = 30f * kotlin.math.exp(ln(16000f / 30f) * t0)
            val f1 = 30f * kotlin.math.exp(ln(16000f / 30f) * t1)
            val k0 = (f0 / binHz).toInt().coerceIn(1, half - 1)
            val k1 = (f1 / binHz).toInt().coerceIn(k0, half - 1)
            var peak = 0f
            for (k in k0..k1) if (magsBuf[k] > peak) peak = magsBuf[k]
            peak.coerceIn(0f, 1f)
        }

        _frame.value = AudioFftFrame(
            bass = smoothBass.coerceIn(0f, 1.5f),
            mid = smoothMid.coerceIn(0f, 1.5f),
            treble = smoothTreble.coerceIn(0f, 1.5f),
            spectrumBars = bars,
            waveformSamples = wave,
        )
    }

    /**
     * Optional Android Visualizer fallback (used for libmpv sessions when RECORD_AUDIO is granted).
     * Never throws if permission is denied; silenced automatically while direct PCM tap is active.
     */
    fun bindToSession(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        if (attachedSessionId == audioSessionId && visualizer != null) return
        releaseVisualizerOnly()
        var pending: Visualizer? = null
        try {
            val v = Visualizer(audioSessionId)
            pending = v
            val range = Visualizer.getCaptureSizeRange()
            val targetSize = 1024.coerceIn(range[0], range[1])
            v.captureSize = Integer.highestOneBit(targetSize)
            v.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
            val maxRate = Visualizer.getMaxCaptureRate().coerceAtLeast(1000)
            val rate = (maxRate / 2).coerceIn(1000, maxRate)
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                        if (waveform == null || waveform.isEmpty()) return
                        if (System.currentTimeMillis() - lastPcmTapMs < 600L) return
                        val wave = List(64) { idx ->
                            val sampleIdx = ((idx.toLong() * waveform.size) / 64L).toInt().coerceIn(0, waveform.lastIndex)
                            val raw = (waveform[sampleIdx].toInt() and 0xFF) - 128
                            (raw / 128f).coerceIn(-1f, 1f)
                        }
                        _frame.update { it.copy(waveformSamples = wave) }
                    }

                    override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRateMilliHz: Int) {
                        if (fft == null || fft.size < 4) return
                        if (System.currentTimeMillis() - lastPcmTapMs < 600L) return
                        val sampleRateHz = (samplingRateMilliHz / 1000f).coerceAtLeast(8000f)
                        processVisualizerFft(fft, sampleRateHz)
                    }
                },
                rate,
                true,
                true,
            )
            v.enabled = true
            visualizer = v
            attachedSessionId = audioSessionId
            pending = null
        } catch (_: Throwable) {
            runCatching { pending?.enabled = false }
            runCatching { pending?.release() }
        }
    }

    private fun processVisualizerFft(fft: ByteArray, sampleRateHz: Float) {
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
                freq in 20f..220f -> { bassSum += mag; bassCnt++ }
                freq in 220f..2200f -> { midSum += mag; midCnt++ }
                freq in 2200f..16000f -> { trebSum += mag; trebCnt++ }
            }
        }

        val rawBass = if (bassCnt > 0) bassSum / bassCnt else 0f
        val rawMid = if (midCnt > 0) midSum / midCnt else 0f
        val rawTreb = if (trebCnt > 0) trebSum / trebCnt else 0f

        smoothBass = smoothBass * 0.65f + rawBass * 0.35f
        smoothMid = smoothMid * 0.68f + rawMid * 0.32f
        smoothTreble = smoothTreble * 0.72f + rawTreb * 0.28f

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

        _frame.update {
            it.copy(
                bass = smoothBass.coerceIn(0f, 1.5f),
                mid = smoothMid.coerceIn(0f, 1.5f),
                treble = smoothTreble.coerceIn(0f, 1.5f),
                spectrumBars = bars,
            )
        }
    }

    private fun fftInPlace(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val ang = (-2.0 * PI / len)
            val wLenR = cos(ang).toFloat()
            val wLenI = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wR = 1.0f
                var wI = 0.0f
                for (k in 0 until half) {
                    val idx1 = i + k
                    val idx2 = idx1 + half
                    val uR = real[idx1]
                    val uI = imag[idx1]
                    val tR = real[idx2] * wR - imag[idx2] * wI
                    val tI = real[idx2] * wI + imag[idx2] * wR
                    real[idx1] = uR + tR
                    imag[idx1] = uI + tI
                    real[idx2] = uR - tR
                    imag[idx2] = uI - tI
                    val nextWR = wR * wLenR - wI * wLenI
                    wI = wR * wLenI + wI * wLenR
                    wR = nextWR
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun releaseVisualizerOnly() {
        visualizer?.let { v ->
            runCatching { v.enabled = false }
            runCatching { v.release() }
        }
        visualizer = null
        attachedSessionId = 0
    }

    fun release() {
        releaseVisualizerOnly()
        lastPcmTapMs = 0L
        smoothBass = 0f
        smoothMid = 0f
        smoothTreble = 0f
        _frame.value = AudioFftFrame()
    }
}
