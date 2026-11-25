package com.lumaxr.player.music

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Android 16 (API 36) Richer Haptics using VibrationEffect.WaveformEnvelopeBuilder
 * gated on Vibrator.areEnvelopeEffectsSupported() and clamped to hardware VibratorEnvelopeEffectInfo
 * and VibratorFrequencyProfile, with clean API 34-35 VibrationEffect.Composition fallback.
 */
class LumaHaptics(context: Context) {

    private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)

    fun playTactileClick() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 36 && v.areEnvelopeEffectsSupported()) {
            runCatching {
                val envInfo = v.envelopeEffectInfo
                val freqProfile = v.frequencyProfile
                val minFreq = freqProfile?.minFrequencyHz ?: 80f
                val maxFreq = freqProfile?.maxFrequencyHz ?: 220f
                val stepMs = (envInfo?.minControlPointDurationMillis ?: 10L).coerceAtLeast(10L)

                val effect = VibrationEffect.WaveformEnvelopeBuilder()
                    .setInitialFrequencyHz(minFreq.coerceIn(60f, maxFreq))
                    .addControlPoint(0.85f, ((minFreq + maxFreq) * 0.55f).coerceIn(minFreq, maxFreq), stepMs)
                    .addControlPoint(0.0f, minFreq.coerceIn(60f, maxFreq), stepMs)
                    .build()
                v.vibrate(effect)
                return
            }
        }
        val comp = VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f)
            .compose()
        v.vibrate(comp)
    }

    fun playSeekDetent() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 36 && v.areEnvelopeEffectsSupported()) {
            runCatching {
                val envInfo = v.envelopeEffectInfo
                val freqProfile = v.frequencyProfile
                val minFreq = freqProfile?.minFrequencyHz ?: 70f
                val maxFreq = freqProfile?.maxFrequencyHz ?: 200f
                val stepMs = (envInfo?.minControlPointDurationMillis ?: 8L).coerceAtLeast(8L)
                val effect = VibrationEffect.WaveformEnvelopeBuilder()
                    .setInitialFrequencyHz(minFreq)
                    .addControlPoint(0.45f, (minFreq * 1.4f).coerceAtMost(maxFreq), stepMs)
                    .addControlPoint(0.0f, minFreq, stepMs)
                    .build()
                v.vibrate(effect)
                return
            }
        }
        val comp = VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.45f)
            .compose()
        v.vibrate(comp)
    }
}
